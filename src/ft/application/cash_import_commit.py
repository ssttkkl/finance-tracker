"""工作区事务内原子应用导入计划；执行异常回滚整次业务写入。"""
from __future__ import annotations

from ft.cash_diagnostics import cash_diagnostic_scope, row_locations, operation_locations

from collections import Counter
from hashlib import sha256

from sqlalchemy import select

from ft.adapters.relational.cash_mutations import CashMutationConflict, CashMutationRepository
from ft.adapters.relational.cash_source_documents import CashSourceDocumentRepository
from ft.adapters.relational.cash_sources import SourceBindingConflict
from ft.adapters.relational.models import CashTransactionModel
from ft.adapters.relational.projections import RelationalCashProjectionRepository
from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
from ft.application.cash_import_account_mappings import CashImportAccountMappings
from ft.application.cash_commit_outcome import CashCommitBoundary
from ft.application.cash_import_context import CashImportPermissionDenied
from ft.application.cash_import_issues import cash_import_issue
from ft.application.cash_import_operations import _batches
from ft.application.cash_import_plans import CashImportPlanError, CashImportPlanService, _session_id
from ft.application.cash_import_errors import ImportSessionExpired, ImportSessionForbidden, ImportSessionNotFound
from ft.application.cash_import_source_rows import file_document_evidence
from ft.application.cash_source_candidate_sets import frozen_source_candidate_sets, source_candidate_ids
from ft.application.cash_projections import CashProjectionService
from ft.application.cash_record_writer import _issues_unchanged
from ft.application.cash_plan_execution import CashPlanWriteOperations, _operation_fingerprint, _receipt_view
from ft.domain.cash_source_matching import _digest, conflicting_source_document_pages


class CashImportCommandService(CashPlanWriteOperations, CashImportPlanService):
    def __init__(self, uow, staging_store, *, user_id, **kwargs):
        super().__init__(uow, staging_store, user_id=user_id,
            builder=CashImportBusinessPlanBuilder(uow.workspace_id), **kwargs)

    def commit(self, plan_id, *, expected_session_revision, idempotency_key):
        boundary = CashCommitBoundary()
        with boundary.guard((CashImportPlanError, CashImportPermissionDenied, CashMutationConflict,
            ImportSessionExpired, ImportSessionForbidden, ImportSessionNotFound)):
            try:
                return self._commit(plan_id, expected_session_revision=expected_session_revision,
                    idempotency_key=idempotency_key, boundary=boundary)
            except CashImportPlanError as error:
                if boundary.commit_state == "not_committed":
                    error.commit_state = "not_committed"
                raise

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
            with cash_diagnostic_scope("submit_revalidation", scope="request"):
                current = self.current_for_commit(uow, plan_id, expected_session_revision=expected_session_revision)
            with cash_diagnostic_scope(files=current.files):
                plan = current.plan
                if not plan.get("can_commit"):
                    raise CashImportPlanError("plan_input_invalid")
                with cash_diagnostic_scope("submit_revalidation", operation_locations(plan["operations"]), scope="related"):
                    sources = self._prepare_sources(uow, current)
                    try:
                        fresh = self._builder(session=session, files=current.files, preview_input=current.preview_input,
                            previous_plan=plan, plan_id=plan_id, source_plan=sources)
                    except CashImportPlanError as error:
                        if error.code != "plan_input_invalid":
                            raise
                        changed = CashImportPlanError("plan_changed")
                        if hasattr(error, "_cash_diagnostic_context"):
                            changed._cash_diagnostic_context = error._cash_diagnostic_context
                        raise changed from None
                    if (len(plan["operations"]) != len(fresh["operations"]) or any(
                        _operation_fingerprint(old) != _operation_fingerprint(new)
                        for old, new in zip(plan["operations"], fresh["operations"])) or any(
                        _digest(plan.get(key, [])) != _digest(fresh.get(key, []))
                        for key in ("entry_edits", "relation_groups", "relation_failures", "row_decisions"))):
                        raise CashImportPlanError("plan_changed")
                    state = RelationalCashProjectionRepository(session, self._workspace_id).require_ready_state_lock()
                with cash_diagnostic_scope("source_evidence", [{"file_ref": file.file_ref} for file in current.files], scope="file" if len(current.files) == 1 else "related"):
                    self._save_evidence(session, current.files, sources=sources, plan=plan)
                actual, identity, component_ids, changes, affected = {}, {}, {}, [], set()
                fresh_ops = {item["occurrence_key"]: item for item in fresh["operations"]}
                records = {record.transaction_id: record for record in sources.records}
                candidate_sets = frozen_source_candidate_sets(plan.get("source_candidate_sets", {}))
                candidate_models = {}
                bulk = self._new_batches(session, plan["operations"], sources.rows)
                for operation in plan["operations"]:
                    with cash_diagnostic_scope("row_write", row_locations(operation["row_refs"]),
                        scope="row" if len(operation["row_refs"]) == 1 else "related"):
                        ref = operation["operation_ref"]
                        if operation["planned_action"] == "duplicate" and _issues_unchanged(operation):
                            record = records[operation["transaction_id"]]
                            missing = any(spec.key not in record.binding_keys for update in operation["source_updates"] if update["bind"]
                                for spec in sources.rows[update["row_ref"]].binding_specs)
                            if not missing:
                                identity[operation["preview_transaction_id"]] = operation["transaction_id"]
                                component_ids.update({part["component_id"]: part["component_id"] for part in operation["preview_components"]})
                                actual[ref] = {"outcome": "duplicate", "transaction_id": operation["transaction_id"]}
                                continue
                        if ref in bulk:
                            saved = bulk[ref]
                        else:
                            resolved = self._candidate_operation(session, operation, candidate_sets, candidate_models)
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
                rows = self._row_results(plan, actual)
                used = [choice for operation in plan["operations"]
                    for choice in fresh_ops[operation["occurrence_key"]].get("used_account_selections", ())]
                with cash_diagnostic_scope("account_mapping", operation_locations(plan["operations"]), scope="related"):
                    CashImportAccountMappings(session, self._workspace_id).publish(used, user_id=self._user_id)
                groups = self._save_groups(session, plan, fresh, identity, component_ids, affected,
                    manual={choice["suggestion_id"] for choice in current.preview_input["relation_choices"]})
                self._discovery_issues(session, plan, identity, affected)
                groups.extend(self._discovery_results(plan, identity))
                with cash_diagnostic_scope("balances", operation_locations(plan["operations"]), scope="related"):
                    self._balances(uow, changes)
                if affected:
                    # 只从成功操作及关系触及的旧／新当前成员展开；不全库重建。
                    with cash_diagnostic_scope("projection", operation_locations(plan["operations"]), scope="related"):
                        CashProjectionService.maintain_in_session(session, self._workspace_id, affected)
                from ft.application.cash_import_entry_choices import save_entry_choices
                with cash_diagnostic_scope("entry_edit", operation_locations(plan["operations"]), scope="related"):
                    edited_entries = save_entry_choices(session, self._workspace_id, plan.get("entry_edits", []), identity, groups)
                    session.flush()
                summary = Counter({key: 0 for key in ("created_posted", "updated_posted", "saved_draft",
                    "finalized_draft", "duplicate", "skipped", "relations_confirmed", "relations_deferred", "relations_ignored", "relations_failed")})
                summary.update(item["outcome"] for item in actual.values())
                if edited_entries:
                    summary["updated_entries"] = edited_entries
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
                    file_results.append({"result_kind": "file", "item_ref": file.file_ref,
                        "outcome": "recognized" if file.status == "parsed" else "skipped", "code": file.error_code})
                normal_skips = {"row_not_selected", "unpaid_closed", "failed_repay", "account_opening"}
                problem = (any(item["outcome"] == "skipped" and item.get("code") not in normal_skips for item in rows)
                    or any(item["outcome"] == "failed" for item in groups) or any(item["outcome"] == "skipped" for item in file_results))
                succeeded = any(item["outcome"] not in {"skipped", "failed"} for item in (*rows, *groups))
                written = any(item["outcome"] not in {"skipped", "duplicate", "duplicate_in_batch"} for item in actual.values())
                written = written or bool(edited_entries)
                written = written or any(item["outcome"] in {"confirmed", "deferred", "ignored"} for item in groups)
                status = "partial" if problem and succeeded else "failed" if problem else "completed" if written else "no_changes"
                receipt = receipts.create(user_id=self._user_id, idempotency_key=idempotency_key,
                    request_digest=digest, operation_kind="import", plan_id=plan_id,
                    session_digest=sha256(session_id.encode()).hexdigest(),
                    result_summary={"status": status, "summary": dict(summary), "needs_attention_count": pending,
                        "commit_outcome": {"has_new_failures": False, "has_business_writes": bool(written), "reason_codes": []},
                        "pending_item_count": pending_items, "projection_version": state.projection_version},
                    results=[*rows, *groups, *file_results])
                boundary.commit(uow)
                try:
                    self._staging.cancel(session_id, workspace_id=self._workspace_id, user_id=self._user_id, expected_revision=current.revision)
                except Exception:
                    # 已消费收据先阻断访问，固定 TTL 和后台清扫负责物理收尾。
                    pass
                return _receipt_view(receipt)

    def _candidate_operation(self, session, operation, candidate_sets, cache):
        key = operation.get("candidate_set_ref")
        if key is None:
            return operation
        candidates = source_candidate_ids(operation, candidate_sets)
        if key not in cache:
            models = {}
            for batch in _batches(int(identifier) for identifier in candidates):
                models.update((str(row.id), row) for row in session.scalars(select(CashTransactionModel).where(
                    CashTransactionModel.workspace_id == self._workspace_id, CashTransactionModel.id.in_(batch),
                    CashTransactionModel.deleted_at.is_(None)).execution_options(populate_existing=True)))
            if set(models) != set(candidates):
                raise SourceBindingConflict("source_candidate_changed")
            cache[key] = models
        return {**operation, "candidate_ids": candidates, "_source_candidate_models": cache[key]}

    def _new_batches(self, session, operations, source_rows):
        from ft.adapters.relational.cash_import_new_rows import CashImportNewRowsRepository
        eligible = []
        for operation in operations:
            rows = [source_rows[item["row_ref"]] for item in operation["source_updates"] if item["bind"]]
            if (operation["planned_action"] == "create_posted" and operation["transaction_id"] is None
                and not operation["issues"] and not operation["pending_fields"] and rows
                and all(not row.coverage.document_key and not row.document_page for row in rows)
                and not any(item["uncertain"] for item in operation["source_updates"])):
                eligible.append((operation, rows))
        result = {}
        if len(eligible) < 20:
            return result
        for offset in range(0, len(eligible), 100):
            entries = eligible[offset:offset + 100]
            with cash_diagnostic_scope("batch_write", operation_locations(operation for operation, _rows in entries), scope="related"):
                saved = CashImportNewRowsRepository(session, self._workspace_id).insert(entries)
                session.flush()
            result.update((operation["operation_ref"], value) for (operation, _rows), value in zip(entries, saved))
        return result

    def _save_evidence(self, session, files, *, sources, plan):
        conflicts = {value[:3] for value in conflicting_source_document_pages(
            sources.rows.values(), sources.records, evidence=sources.evidence)}
        skipped = {row["row_ref"] for row in plan["row_decisions"] if row["planned_action"] == "skip"}
        refs_by_namespace = {}
        for ref, row in sources.rows.items():
            if row.coverage.document_key:
                refs_by_namespace.setdefault((row.source_type, row.parser_version, row.coverage.document_key), set()).add(ref)
        excluded = {namespace for namespace in conflicts if refs_by_namespace.get(namespace, set()) <= skipped}
        needed_records = {operation["transaction_id"] for operation in plan["operations"] if operation["transaction_id"] is not None}
        needed_records.update(identifier for operation in plan["operations"] for identifier in operation.get("candidate_ids", ()))
        needed_records.update(identifier for values in plan.get("source_candidate_sets", {}).values() for identifier in values)
        excluded.difference_update(evidence.namespace for record in sources.records if record.transaction_id in needed_records
            for evidence in record.document_evidence)
        by_namespace = {}
        for file in files:
            for evidence in file_document_evidence((file,)):
                if evidence.coverage.document_key and evidence.namespace not in excluded:
                    by_namespace.setdefault(evidence.namespace, []).append(file)
        for members in by_namespace.values():
            with cash_diagnostic_scope("source_evidence", [{"file_ref": file.file_ref} for file in members],
                    scope="file" if len(members) == 1 else "related"):
                CashSourceDocumentRepository(session, self._workspace_id).save_empty_pages(members)
        session.flush()

    @staticmethod
    def _row_results(plan, actual):
        result = []
        for row in plan["row_decisions"]:
            reference = row.get("operation_ref")
            if reference in actual:
                item = dict(actual[reference])
                if row["planned_action"] == "duplicate_in_batch":
                    item["outcome"] = "duplicate_in_batch"
            elif row["planned_action"] == "skip":
                item = {"outcome": "skipped", "code": row.get("code", "row_unreadable")}
            else:
                item = {"outcome": "duplicate_in_batch" if row["planned_action"] == "duplicate_in_batch" else "duplicate",
                    "transaction_id": row.get("transaction_id")}
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
            value = CashMutationRepository(uow._state().session, self._workspace_id).lookup_key(self._user_id, idempotency_key)
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
            from ft.application.cash_import_plans import current_cash_plan_contract
            if current.cancelled or not current_cash_plan_contract(current.plan) or current.plan.get("plan_id") != plan_id:
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
