"""工作区锁内一次应用导入计划；流水和完整配对组分别隔离失败。"""
from __future__ import annotations

from collections import Counter, defaultdict
from hashlib import sha256

from sqlalchemy import select

from ft.adapters.relational.cash_mutations import CashMutationConflict, CashMutationRepository
from ft.adapters.relational.cash_source_documents import CashSourceDocumentRepository
from ft.adapters.relational.cash_sources import CashSourceRepository, SourceBindingConflict
from ft.adapters.relational.models import CashTransactionModel
from ft.adapters.relational.projections import RelationalCashProjectionRepository
from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
from ft.application.cash_import_account_mappings import CashImportAccountMappings
from ft.application.cash_commit_outcome import CashCommitBoundary
from ft.application.cash_import_context import CashImportPermissionDenied
from ft.application.cash_import_issues import cash_import_issue
from ft.application.cash_import_operations import _batches
from ft.application.cash_import_plans import CashImportPlanError, CashImportPlanService, _session_id
from ft.application.cash_import_staging import ImportSessionExpired, ImportSessionForbidden, ImportSessionNotFound
from ft.application.cash_import_source_rows import file_document_evidence
from ft.application.cash_source_candidate_sets import frozen_source_candidate_sets, source_candidate_ids
from ft.application.cash_projections import CashProjectionService
from ft.application.cash_record_writer import _issues_unchanged
from ft.application.cash_plan_execution import (CashPlanWriteOperations, _AccountChanged, _RowChanged,
    _operation_fingerprint, _reason, _receipt_view, _recoverable)
from ft.domain.cash_record_policy import CASH_FINANCIAL_FIELDS, MUTABLE_CASH_FIELDS, _normalize
from ft.domain.cash_source_matching import _digest, _index


def _equivalent_duplicate(before, current):
    if before["transaction_id"] is not None or current is None or current["planned_action"] != "duplicate":
        return False
    def values(operation):
        result = {key: _normalize(key, value) for key, value in operation["values"].items()
            if key in MUTABLE_CASH_FIELDS | CASH_FINANCIAL_FIELDS | {"state", "source_type"}}
        result["components"] = [{key: value for key, value in item.items() if key != "component_id"} for item in result["components"]]
        return result
    return values(before) == values(current) and before["source_scope_key"] == current["source_scope_key"]


class CashImportCommandService(CashPlanWriteOperations, CashImportPlanService):
    def __init__(self, uow, staging_store, *, user_id, **kwargs):
        super().__init__(uow, staging_store, user_id=user_id,
            builder=CashImportBusinessPlanBuilder(uow.workspace_id), **kwargs)

    def commit(self, plan_id, *, expected_session_revision, idempotency_key):
        boundary = CashCommitBoundary()
        with boundary.guard((CashImportPlanError, CashImportPermissionDenied, CashMutationConflict,
            ImportSessionExpired, ImportSessionForbidden, ImportSessionNotFound)):
            return self._commit(plan_id, expected_session_revision=expected_session_revision,
                idempotency_key=idempotency_key, boundary=boundary)

    def _commit(self, plan_id, *, expected_session_revision, idempotency_key, boundary):
        if type(expected_session_revision) is not int or expected_session_revision < 1:
            raise CashImportPlanError("plan_input_invalid")
        session_id = _session_id(plan_id)
        digest = _digest("cash-import-commit-v1", plan_id, expected_session_revision)
        with self._uow as uow:
            self._authorize(uow)
            session = uow._state().session
            receipts = CashMutationRepository(session, self._workspace_id)
            replay = receipts.replay(self._user_id, idempotency_key, request_digest=digest,
                operation_kind="import", plan_id=plan_id)
            if replay is not None:
                return _receipt_view(replay)
            consumed = receipts.for_context(self._user_id, session_digest=sha256(session_id.encode()).hexdigest())
            if consumed is not None:
                raise CashMutationConflict("session_committed", consumed["receipt_id"])
            boundary.no_prior_result()
            current = self.current_for_commit(uow, plan_id, expected_session_revision=expected_session_revision)
            plan = current.plan
            if not plan.get("can_commit"):
                raise CashImportPlanError("plan_input_invalid")
            sources = self._prepare_sources(uow, current)
            fresh = self._builder(session=session, files=current.files, preview_input=current.preview_input,
                previous_plan=plan, plan_id=plan_id, source_plan=sources)
            state = RelationalCashProjectionRepository(session, self._workspace_id).require_ready_state_lock()
            failed_namespaces = self._save_evidence(session, current.files)
            dependencies = self._source_dependencies(sources)
            actual, identity, component_ids, changes, affected = {}, {}, {}, [], set()
            fresh_ops = {item["occurrence_key"]: item for item in fresh["operations"]}
            records = {record.transaction_id: record for record in sources.records}
            candidate_sets = frozen_source_candidate_sets(plan.get("source_candidate_sets", {}))
            candidate_models, candidate_epoch = {}, 0
            bulk = self._new_batches(session, plan["operations"], fresh_ops, sources.rows, dependencies, failed_namespaces)
            failures, validated = set(), set()
            for operation in plan["operations"]:
                ref = operation["operation_ref"]
                candidate = fresh_ops.get(operation["occurrence_key"])
                try:
                    if any(dependencies.get(row, set()).intersection(failed_namespaces) for row in operation["row_refs"]):
                        namespace = next(namespace for row in operation["row_refs"]
                            for namespace in dependencies.get(row, set()) if namespace in failed_namespaces)
                        raise SourceBindingConflict(failed_namespaces[namespace])
                    if _equivalent_duplicate(operation, candidate):
                        with session.begin_nested():
                            repository = CashSourceRepository(session, self._workspace_id)
                            for update in candidate["source_updates"]:
                                if update["bind"]:
                                    repository.save_occurrence(candidate["transaction_id"], sources.rows[update["row_ref"]], uncertain=update["uncertain"])
                            session.flush()
                        identity[operation["preview_transaction_id"]] = candidate["transaction_id"]
                        current_parts = {part["ordinal"]: part["component_id"] for part in candidate["preview_components"]}
                        component_ids.update({part["component_id"]: current_parts[part["ordinal"]] for part in operation["preview_components"]})
                        actual[ref] = {"outcome": "duplicate", "transaction_id": candidate["transaction_id"]}
                        continue
                    if candidate is None or _operation_fingerprint(operation) != _operation_fingerprint(candidate):
                        if (candidate is not None and operation.get("expected_accounts")
                            and operation["transaction_id"] == candidate["transaction_id"]
                            and operation["expected_revision"] == candidate["expected_revision"]
                            and operation["expected_accounts"] != candidate["expected_accounts"]):
                            raise _AccountChanged
                        raise _RowChanged
                    validated.add(ref)
                    if operation["planned_action"] == "duplicate" and _issues_unchanged(operation):
                        record = records[operation["transaction_id"]]
                        missing = any(spec.key not in record.binding_keys for update in operation["source_updates"] if update["bind"]
                            for spec in sources.rows[update["row_ref"]].binding_specs)
                        if not missing:
                            identity[operation["preview_transaction_id"]] = operation["transaction_id"]
                            component_ids.update({part["component_id"]: part["component_id"] for part in operation["preview_components"]})
                            actual[ref] = {"outcome": "duplicate", "transaction_id": operation["transaction_id"]}
                            continue
                    # 不在 begin_nested() 之前修改 ORM 或共享余额缓存。
                    if ref in bulk:
                        saved = bulk[ref]
                    else:
                        with session.begin_nested():
                            resolved = self._candidate_operation(session, operation, candidate_sets, candidate_models, candidate_epoch)
                            saved = self._save_row(session, resolved, sources.rows)
                            session.flush()
                    identifier, mapping, before, after, touched = saved
                    identity[operation["preview_transaction_id"]] = str(identifier)
                    component_ids.update(mapping)
                    actual[ref] = {"outcome": self._row_outcome(operation), "transaction_id": str(identifier)}
                    if operation["planned_action"] != "duplicate" or operation["issues"]:
                        affected.add(identifier)
                    affected.update(touched)
                    changes.append((before, after))
                except Exception as error:
                    if not _recoverable(error):
                        raise
                    session.expire_all()
                    candidate_epoch += 1
                    failures.add(operation["preview_transaction_id"])
                    actual[ref] = {"outcome": "skipped", "code": _reason(error, "row_save_failed")}
                    if operation["transaction_id"] is not None:
                        actual[ref]["transaction_id"] = operation["transaction_id"]
            rows = self._row_results(plan, fresh, actual, dependencies, failed_namespaces)
            used = [choice for operation in plan["operations"]
                if actual[operation["operation_ref"]]["outcome"] != "skipped"
                for choice in fresh_ops[operation["occurrence_key"]].get("used_account_selections", ())]
            CashImportAccountMappings(session, self._workspace_id).publish(used, user_id=self._user_id)
            groups = self._save_groups(session, plan, fresh, identity, component_ids, failures, affected,
                manual={choice["suggestion_id"] for choice in current.preview_input["relation_choices"]})
            self._discovery_issues(session, plan, identity, failures, affected)
            groups.extend(self._discovery_results(plan, identity, failures))
            self._balances(uow, changes)
            if affected:
                # 只从成功操作及关系触及的旧／新当前成员展开；不全库重建。
                CashProjectionService.maintain_in_session(session, self._workspace_id, affected)
            session.flush()
            summary = Counter({key: 0 for key in ("created_posted", "updated_posted", "saved_draft",
                "finalized_draft", "duplicate", "skipped", "relations_confirmed", "relations_deferred", "relations_ignored", "relations_failed")})
            summary.update(item["outcome"] for item in actual.values())
            for item in rows:
                if item.get("operation_ref") is None:
                    summary[item["outcome"]] += 1
            summary["duplicate"] = sum(item["outcome"] in {"duplicate", "duplicate_in_batch"} for item in rows)
            summary["skipped"] = sum(item["outcome"] == "skipped" for item in rows)
            summary.pop("duplicate_in_batch", None)
            for item in groups:
                summary["relations_" + item["outcome"]] += 1
            successful = affected | {int(item["transaction_id"]) for item in actual.values()
                if item.get("transaction_id") is not None and item["outcome"] != "skipped"}
            pending = self._attention_count(session, successful)
            locations = RelationalCashProjectionRepository(session, self._workspace_id).current_references(successful, state=state)
            pending_items = len({value["projection_id"] for value in locations.values() if value["attention_count"] > 0})
            file_results = []
            for file in current.files:
                failed = [failed_namespaces[item.namespace] for item in file_document_evidence((file,)) if item.namespace in failed_namespaces]
                file_results.append({"result_kind": "file", "item_ref": file.file_ref,
                    "outcome": "recognized" if file.status == "parsed" and not failed else "skipped",
                    "code": failed[0] if failed else file.error_code})
            normal_skips = {"row_not_selected", "unpaid_closed", "failed_repay", "account_opening"}
            problem = (any(item["outcome"] == "skipped" and item.get("code") not in normal_skips for item in rows)
                or any(item["outcome"] == "failed" for item in groups) or any(item["outcome"] == "skipped" for item in file_results))
            succeeded = any(item["outcome"] not in {"skipped", "failed"} for item in (*rows, *groups))
            written = any(item["outcome"] not in {"skipped", "duplicate", "duplicate_in_batch"} for item in actual.values())
            written = written or any(item["outcome"] in {"confirmed", "deferred", "ignored"} for item in groups)
            status = "partial" if problem and succeeded else "failed" if problem else "completed" if written else "no_changes"
            from ft.application.cash_import_retry import CashImportRetryService, prepare_retry_context
            try:
                retry = prepare_retry_context(uow, self._staging, self._user_id, current, plan, sources, rows, validated)
            except Exception as error:
                if not _recoverable(error):
                    raise
                retry = {"retry_context_status": "reselect_file"}
            receipt = receipts.create(user_id=self._user_id, idempotency_key=idempotency_key,
                request_digest=digest, operation_kind="import", plan_id=plan_id,
                session_digest=sha256(session_id.encode()).hexdigest(),
                result_summary={"status": status, "summary": dict(summary), "needs_attention_count": pending,
                    "pending_item_count": pending_items, "projection_version": state.projection_version, **retry},
                results=[*rows, *groups, *file_results])
            boundary.commit(uow)
            try:
                self._staging.cancel(session_id, workspace_id=self._workspace_id, user_id=self._user_id, expected_revision=current.revision)
            except Exception:
                # 已消费收据先阻断访问，固定 TTL 和后台清扫负责物理收尾。
                pass
            try:
                CashImportRetryService(self._uow, self._staging, user_id=self._user_id).cleanup(current)
            except Exception:
                # 持久消费结果仍在所有重试读入口生效，不能依赖清理成功授权。
                pass
            return _receipt_view(receipt)

    def retry_session(self, receipt_id, *, row_refs=None, idempotency_key):
        from ft.application.cash_import_retry import CashImportRetryService
        return CashImportRetryService(self._uow, self._staging, user_id=self._user_id).derive(receipt_id,
            row_refs=row_refs, idempotency_key=idempotency_key)

    def _candidate_operation(self, session, operation, candidate_sets, cache, epoch):
        key = operation.get("candidate_set_ref")
        if key is None:
            return operation
        candidates = source_candidate_ids(operation, candidate_sets)
        if key not in cache or cache[key][0] != epoch:
            models = {}
            for batch in _batches(int(identifier) for identifier in candidates):
                models.update((str(row.id), row) for row in session.scalars(select(CashTransactionModel).where(
                    CashTransactionModel.workspace_id == self._workspace_id, CashTransactionModel.id.in_(batch),
                    CashTransactionModel.deleted_at.is_(None)).execution_options(populate_existing=True)))
            if set(models) != set(candidates):
                raise SourceBindingConflict("source_candidate_changed")
            cache[key] = epoch, models
        return {**operation, "candidate_ids": candidates, "_source_candidate_models": cache[key][1]}

    def _new_batches(self, session, operations, fresh, source_rows, dependencies, failed_namespaces):
        from ft.adapters.relational.cash_import_new_rows import CashImportNewRowsRepository
        eligible = []
        for operation in operations:
            current = fresh.get(operation["occurrence_key"])
            rows = [source_rows[item["row_ref"]] for item in operation["source_updates"] if item["bind"]]
            if (operation["planned_action"] == "create_posted" and operation["transaction_id"] is None
                and not operation["issues"] and not operation["pending_fields"] and rows
                and all(not row.coverage.document_key and not row.document_page for row in rows)
                and not any(item["uncertain"] for item in operation["source_updates"])
                and not any(dependencies.get(ref, set()).intersection(failed_namespaces) for ref in operation["row_refs"])
                and current is not None and _operation_fingerprint(current) == _operation_fingerprint(operation)):
                eligible.append((operation, rows))
        result = {}
        if len(eligible) < 20:
            return result
        for offset in range(0, len(eligible), 100):
            entries = eligible[offset:offset + 100]
            try:
                with session.begin_nested():
                    saved = CashImportNewRowsRepository(session, self._workspace_id).insert(entries)
                    session.flush()
                result.update((operation["operation_ref"], value) for (operation, _rows), value in zip(entries, saved))
            except Exception as error:
                if not _recoverable(error):
                    raise
                session.expire_all()
        return result

    def _save_evidence(self, session, files):
        by_namespace, failed = defaultdict(list), {}
        for file in files:
            for evidence in file_document_evidence((file,)):
                if evidence.coverage.document_key:
                    by_namespace[evidence.namespace].append(file)
        for namespace, members in by_namespace.items():
            try:
                with session.begin_nested():
                    CashSourceDocumentRepository(session, self._workspace_id).save_empty_pages(members)
                    session.flush()
            except Exception as error:
                if not _recoverable(error):
                    raise
                session.expire_all()
                failed[namespace] = _reason(error, "source_evidence_save_failed")
        return failed

    @staticmethod
    def _source_dependencies(sources):
        index, all_scopes = defaultdict(set), defaultdict(set)
        record_evidence = {record.transaction_id: {item.namespace for item in record.document_evidence} for record in sources.records}
        for record in sources.records:
            key = record.source_type, _index(record.fields)
            index[(*key, record.source_account_key)].update(record_evidence[record.transaction_id])
            all_scopes[key].update(record_evidence[record.transaction_id])
        groups = {group.group_ref: {namespace for identifier in group.candidate_ids for namespace in record_evidence.get(identifier, ())}
            for group in sources.plan.groups}
        resolutions = {row.row_ref: row for row in sources.plan.rows}
        result = {}
        for ref, row in sources.rows.items():
            resolution = resolutions.get(ref)
            values = set()
            if row.coverage.document_key:
                values.add((row.source_type, row.parser_version, row.coverage.document_key))
            fixed_index = _index(row.fields)
            if fixed_index is not None:
                key = row.source_type, fixed_index
                if row.source_account_key:
                    values.update(index[(*key, row.source_account_key)])
                    values.update(index[(*key, None)])
                else:
                    values.update(all_scopes[key])
            if resolution:
                values.update(namespace for identifier in resolution.candidate_ids for namespace in record_evidence.get(identifier, ()))
                if resolution.transaction_id:
                    values.update(record_evidence.get(resolution.transaction_id, ()))
                if resolution.group_ref in groups:
                    values.update(groups[resolution.group_ref])
            result[ref] = values
        return result


    @staticmethod
    def _row_results(plan, fresh, actual, dependencies, failed_namespaces):
        current = {row["row_ref"]: row for row in fresh["row_decisions"]}
        result = []
        for row in plan["row_decisions"]:
            reference = row.get("operation_ref")
            if reference in actual:
                item = dict(actual[reference])
                if row["planned_action"] == "duplicate_in_batch" and item["outcome"] != "skipped":
                    item["outcome"] = "duplicate_in_batch"
            elif row["planned_action"] == "skip":
                item = {"outcome": "skipped", "code": row.get("code", "row_unreadable")}
            elif dependencies.get(row["row_ref"], set()).intersection(failed_namespaces):
                namespace = next(value for value in dependencies[row["row_ref"]] if value in failed_namespaces)
                item = {"outcome": "skipped", "code": failed_namespaces[namespace]}
            elif _digest(row) == _digest(current.get(row["row_ref"])):
                item = {"outcome": "duplicate_in_batch" if row["planned_action"] == "duplicate_in_batch" else "duplicate",
                    "transaction_id": row.get("transaction_id")}
            else:
                item = {"outcome": "skipped", "code": "record_changed"}
            result.append({"result_kind": "row", "item_ref": row["row_ref"], "operation_ref": reference, **item})
        return result


    def receipt(self, receipt_id):
        with self._uow as uow:
            self._authorize(uow, write=False)
            value = CashMutationRepository(uow._state().session, self._workspace_id).get(self._user_id, receipt_id)
            if value is None:
                raise CashImportPlanError("receipt_not_found")
            return _receipt_view(value)

    def receipt_by_key(self, idempotency_key):
        with self._uow as uow:
            self._authorize(uow, write=False)
            value = CashMutationRepository(uow._state().session, self._workspace_id).by_key(self._user_id, idempotency_key)
            if value is None:
                raise CashImportPlanError("receipt_not_found")
            return _receipt_view(value)

    def resolve(self, plan_id, *, idempotency_key):
        session_id = _session_id(plan_id)
        from ft.adapters.relational.runtime import StorageError
        try:
            return self._resolve_locked(plan_id, session_id, idempotency_key)
        except StorageError as error:
            if error.code != "storage.busy":
                raise
            return {"status": "pending"}

    def _resolve_locked(self, plan_id, session_id, idempotency_key):
        with self._uow.nonblocking() as uow:
            self._authorize(uow)
            repository = CashMutationRepository(uow._state().session, self._workspace_id)
            receipt = repository.by_key(self._user_id, idempotency_key)
            if receipt is not None and (receipt["operation_kind"] != "import" or receipt["plan_id"] != plan_id):
                raise CashMutationConflict("idempotency_conflict", receipt["receipt_id"])
            receipt = receipt or repository.for_context(self._user_id, session_digest=sha256(session_id.encode()).hexdigest())
            if receipt is not None:
                return {"status": "committed", "receipt": _receipt_view(receipt)}
            try:
                current = self._read_context(uow, session_id)
            except (ImportSessionExpired, ImportSessionNotFound):
                return {"status": "not_committed"}
            except CashMutationConflict as error:
                if error.code != "retry_already_processed":
                    raise
                return {"status": "not_committed"}
            if current.cancelled or current.plan is None or current.plan.get("plan_id") != plan_id:
                return {"status": "not_committed"}
            return {"status": "retry_original"}

    def results(self, receipt_id, *, result_kind=None, after=0, limit=50):
        with self._uow as uow:
            self._authorize(uow, write=False)
            value = CashMutationRepository(uow._state().session, self._workspace_id).results(self._user_id,
                receipt_id, result_kind=result_kind, after=after, limit=limit)
            if value is None:
                raise CashImportPlanError("receipt_not_found")
            repository = RelationalCashProjectionRepository(uow._state().session, self._workspace_id)
            state = repository.ready_state_lock_or_none()
            identifiers = {item["transaction_id"] for item in value["items"]
                if isinstance(item.get("transaction_id"), str) and item["transaction_id"].isascii()
                and item["transaction_id"].isdecimal() and 0 < int(item["transaction_id"]) < 2 ** 63}
            locations = repository.current_references(identifiers, state=state)
            value["projection_refs"] = [{key: item[key] for key in ("transaction_id", "projection_id")}
                for item in locations.values()]
            value["projection_version"] = state.projection_version if state is not None else 0
            for item in value["items"]:
                if item.get("code"):
                    item["message"] = cash_import_issue(item["code"])["message"]
            return value
