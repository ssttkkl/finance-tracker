"""工作区锁内一次应用导入计划；流水和完整配对组分别隔离失败。"""
from __future__ import annotations

from collections import Counter, defaultdict
from copy import deepcopy
from dataclasses import replace
from datetime import datetime, timezone
from hashlib import sha256

from sqlalchemy import delete, select
from sqlalchemy.exc import DBAPIError, DataError, IntegrityError

from ft.adapters.relational.cash_mutations import CashMutationConflict, CashMutationRepository
from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.cash_source_documents import CashSourceDocumentRepository
from ft.adapters.relational.cash_sources import CashSourceRepository, SourceBindingConflict
from ft.adapters.relational.models import (AccountModel, CashRelationCandidateModel,
    CashTransactionComponentModel, CashTransactionIssueModel, CashTransactionModel, TransactionRelationModel)
from ft.adapters.relational.projections import RelationalCashProjectionRepository
from ft.application.cash_balances import apply_snapshot_components, counts_toward_balance
from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder, _edge_key
from ft.application.cash_import_issues import cash_import_issue
from ft.application.cash_import_operations import _batches
from ft.application.cash_import_plans import CashImportPlanError, CashImportPlanService, _session_id
from ft.application.cash_import_staging import ImportSessionExpired, ImportSessionNotFound
from ft.application.cash_import_source_plan import CashImportSourcePlanner
from ft.application.cash_import_source_rows import file_document_evidence
from ft.application.cash_projections import CashProjectionService
from ft.application.cash_relation_context import CashRelationGraphService
from ft.application.cash_relation_type_updates import CashRelationTypeUpdateService
from ft.domain.cash_record_policy import CASH_FINANCIAL_FIELDS, MUTABLE_CASH_FIELDS, CashRecordPolicyError, _normalize
from ft.domain.cash_relation_graph import CashRelationGraphEdge
from ft.domain.cash_relation_policy import CashRelationError, RelationRoleEvidence
from ft.domain.cash_source_matching import _digest, _index


class _RowChanged(ValueError):
    code = "record_changed"


def _recoverable(error):
    # 完整性错误可由保存点恢复；连接中断／锁与事务错误绝不伪装成单行失败。
    return not isinstance(error, DBAPIError) or (isinstance(error, (IntegrityError, DataError)) and not error.connection_invalidated)


def _reason(error, fallback):
    return error.code if isinstance(error, (_RowChanged, SourceBindingConflict, CashRecordPolicyError, CashRelationError)) else fallback


def _operation_fingerprint(operation):
    keys = {"occurrence_key", "transaction_id", "expected_revision", "planned_action", "current", "values",
        "manual_fields", "field_diffs", "pending_fields", "source_updates", "issues", "expected_issues",
        "retained_issue_ids", "expected_accounts", "source_explanation", "source_scope_key", "candidate_ids"}
    return _digest({key: operation[key] for key in keys if key in operation})


def _receipt_view(receipt):
    return {key: deepcopy(value) for key, value in receipt.items()
        if key not in {"request_digest", "session_digest", "edit_context_digest"}}


def _equivalent_duplicate(before, current):
    if before["transaction_id"] is not None or current is None or current["planned_action"] != "duplicate":
        return False
    def values(operation):
        result = {key: _normalize(key, value) for key, value in operation["values"].items()
            if key in MUTABLE_CASH_FIELDS | CASH_FINANCIAL_FIELDS | {"state", "source_type"}}
        result["components"] = [{key: value for key, value in item.items() if key != "component_id"} for item in result["components"]]
        return result
    return values(before) == values(current) and before["source_scope_key"] == current["source_scope_key"]


def _issues_unchanged(operation):
    previous = {(item["code"], item["field_path"]) for item in operation["expected_issues"]}
    desired = {(item["code"], item["field_path"]) for item in operation["issues"]}
    return previous == desired and not operation["pending_fields"] and not operation.get("candidate_ids")


class CashImportCommandService(CashImportPlanService):
    def __init__(self, uow, staging_store, *, user_id, **kwargs):
        super().__init__(uow, staging_store, user_id=user_id,
            builder=CashImportBusinessPlanBuilder(uow.workspace_id), **kwargs)

    def commit(self, plan_id, *, expected_session_revision, idempotency_key):
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
            current = self.current_for_commit(uow, plan_id, expected_session_revision=expected_session_revision)
            plan = current.plan
            if not plan.get("can_commit"):
                raise CashImportPlanError("plan_input_invalid")
            sources = CashImportSourcePlanner(session, self._workspace_id).prepare(current.files)
            fresh = self._builder(session=session, files=current.files, preview_input=current.preview_input,
                previous_plan=plan, plan_id=plan_id, source_plan=sources)
            state = RelationalCashProjectionRepository(session, self._workspace_id).require_ready_state_lock()
            failed_namespaces = self._save_evidence(session, current.files)
            dependencies = self._source_dependencies(sources)
            actual, identity, component_ids, changes, affected = {}, {}, {}, [], set()
            fresh_ops = {item["occurrence_key"]: item for item in fresh["operations"]}
            records = {record.transaction_id: record for record in sources.records}
            bulk = self._new_batches(session, plan["operations"], fresh_ops, sources.rows, dependencies, failed_namespaces)
            failures = set()
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
                        raise _RowChanged
                    if operation["planned_action"] == "duplicate" and _issues_unchanged(operation):
                        record = records[operation["transaction_id"]]
                        missing = any(spec.key not in record.binding_keys for update in operation["source_updates"] if update["bind"]
                            for spec in sources.rows[update["row_ref"]].binding_specs
                            if not update["uncertain"] or spec.scope_kind == "file_local")
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
                            saved = self._save_row(session, operation, sources.rows)
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
                    failures.add(operation["preview_transaction_id"])
                    actual[ref] = {"outcome": "skipped", "code": _reason(error, "row_save_failed")}
                    if operation["transaction_id"] is not None:
                        actual[ref]["transaction_id"] = operation["transaction_id"]
            rows = self._row_results(plan, fresh, actual, dependencies, failed_namespaces)
            groups = self._save_groups(session, plan, fresh, identity, component_ids, failures, affected,
                manual={choice["suggestion_id"] for choice in current.preview_input["relation_choices"]})
            self._discovery_issues(session, plan, identity, failures, affected)
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
            retryable = any(row.get("code") in {"row_save_failed", "source_evidence_save_failed", "record_changed", "account_changed"}
                and row["outcome"] == "skipped" for row in rows)
            retry = {"retry_context_id": session_id, "retry_context_expires_at": current.expires_at.isoformat()} if retryable else {}
            receipt = receipts.create(user_id=self._user_id, idempotency_key=idempotency_key,
                request_digest=digest, operation_kind="import", plan_id=plan_id,
                session_digest=sha256(session_id.encode()).hexdigest(),
                result_summary={"status": status, "summary": dict(summary), "needs_attention_count": pending,
                    "pending_item_count": pending_items, "projection_version": state.projection_version, **retry},
                results=[*rows, *groups, *file_results])
            uow.commit()
            if not retryable:
                try:
                    self._staging.cancel(session_id, workspace_id=self._workspace_id, user_id=self._user_id, expected_revision=current.revision)
                except Exception:
                    # 临时清理失败不改变收据；固定 TTL 和后台清扫仍负责收尾。
                    pass
            return _receipt_view(receipt)

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

    def _save_row(self, session, operation, source_rows):
        values = {key: _normalize(key, value) for key, value in operation["values"].items()}
        parent_id = int(operation["transaction_id"]) if operation["transaction_id"] else None
        parent = session.get(CashTransactionModel, parent_id) if parent_id else None
        before = deepcopy(operation["current"])
        old_context = (CashRelationGraphService(session, self._workspace_id).load({parent_id})
            if parent is not None and parent.state == "posted" and operation["type_changed"] else None)
        if parent is None:
            parent = CashTransactionModel(workspace_id=self._workspace_id)
            session.add(parent)
        if operation["planned_action"] != "duplicate":
            if parent_id is not None:
                parent.row_revision += 1
            for key in (MUTABLE_CASH_FIELDS | CASH_FINANCIAL_FIELDS | {"state", "source_type", "record_id"}) - {"components", "account_name"}:
                if key in values:
                    item = values[key]
                    setattr(parent, key, int(item) if key == "account_id" and item is not None else deepcopy(item))
            parent.manual_overrides = {**(parent.manual_overrides or {}), **{field: True for field in operation["manual_fields"]}}
            if "source_explanation" in operation and not operation["pending_fields"]:
                parent.relation_metadata = deepcopy(operation["source_explanation"])
        session.flush()
        parts = {part.id: part for part in session.scalars(select(CashTransactionComponentModel).where(
            CashTransactionComponentModel.workspace_id == self._workspace_id,
            CashTransactionComponentModel.cash_transaction_id == parent.id))} if parent_id is not None else {}
        wanted = {int(value["component_id"]) for value in values["components"] if value.get("component_id") is not None}
        removed = parts.keys() - wanted
        if removed:
            self._detach_draft_components(session, removed)
            for batch in _batches(removed):
                session.execute(delete(CashTransactionComponentModel).where(CashTransactionComponentModel.workspace_id == self._workspace_id,
                    CashTransactionComponentModel.id.in_(batch)))
        moving = [parts[int(value["component_id"])] for value in values["components"] if value.get("component_id") is not None
            and int(value["component_id"]) in parts and parts[int(value["component_id"])].ordinal != value["ordinal"]]
        if moving:
            blocked = {part.ordinal for part in parts.values()} | {value["ordinal"] for value in values["components"]}
            temporary = 0
            for part in moving:
                while temporary in blocked:
                    temporary += 1
                part.ordinal = temporary
                blocked.add(temporary)
            session.flush()
        mapping = {}
        virtual = {item["ordinal"]: item["component_id"] for item in operation["preview_components"]}
        for value in values["components"]:
            part = parts.get(int(value["component_id"])) if value.get("component_id") is not None else None
            if part is None:
                part = CashTransactionComponentModel(workspace_id=self._workspace_id, cash_transaction_id=parent.id)
                session.add(part)
            for key in ("ordinal", "account_id", "amount", "currency"):
                item = value.get(key)
                setattr(part, key, int(item) if key == "account_id" and item is not None else item)
            session.flush()
            mapping[str(virtual[value["ordinal"]])] = str(part.id)
        if old_context is not None:
            CashRelationTypeUpdateService(session, self._workspace_id).maintain(old_context, {parent.id}, publish=False)
        source = CashSourceRepository(session, self._workspace_id)
        first_binding = parent_id is None
        for update in operation["source_updates"]:
            if update["bind"]:
                source.save_occurrence(parent.id, source_rows[update["row_ref"]], uncertain=update["uncertain"],
                    first_binding=first_binding)
                first_binding = False
        if parent_id is not None or operation["issues"]:
            self._row_issues(session, source, parent.id, operation)
        if parent_id is not None and operation["planned_action"] != "duplicate":
            CurrentCashRelationRepository(session, self._workspace_id).mark_candidates_stale({parent.id})
        after = deepcopy(operation["values"])
        touched = set(old_context.transaction_ids) if old_context else set()
        if not _issues_unchanged(operation):
            touched.add(parent.id)
        return parent.id, mapping, before, after, touched

    def _detach_draft_components(self, session, component_ids):
        relations = {}
        for batch in _batches(component_ids):
            relations.update((row.id, row) for row in session.scalars(select(TransactionRelationModel).where(
                TransactionRelationModel.workspace_id == self._workspace_id,
                (TransactionRelationModel.primary_component_id.in_(batch)
                    | TransactionRelationModel.secondary_component_id.in_(batch)
                    | TransactionRelationModel.anchor_component_id.in_(batch)))))
        for row in relations.values():
            if row.active and row.state == "confirmed":
                raise CashRelationError("relation_context_changed")
            for role in ("primary", "secondary", "anchor"):
                if getattr(row, role + "_component_id") in component_ids:
                    setattr(row, role + "_component_id", None)
                    if role != "anchor":
                        setattr(row, role + "_role", None)
        for batch in _batches(component_ids):
            for row in session.scalars(select(CashRelationCandidateModel).where(
                CashRelationCandidateModel.workspace_id == self._workspace_id,
                CashRelationCandidateModel.candidate_component_id.in_(batch))):
                row.candidate_component_id, row.validity = None, "incomplete"
        session.flush()

    def _row_issues(self, session, sources, parent_id, operation):
        existing = list(session.scalars(select(CashTransactionIssueModel).where(
            CashTransactionIssueModel.workspace_id == self._workspace_id,
            CashTransactionIssueModel.transaction_id == parent_id)))
        by_reason = {(row.code, row.field_path): row for row in existing}
        retained = {int(identifier) for identifier in operation["retained_issue_ids"]}
        for issue in operation["issues"]:
            old = by_reason.get((issue["code"], issue["field_path"]))
            if old is not None and issue["code"] == "source_identity_ambiguous" and not operation.get("candidate_ids"):
                retained.add(old.id)
                continue
            candidates = {int(identifier): session.get(CashTransactionModel, int(identifier)).row_revision
                for identifier in operation.get("candidate_ids", [])} if issue["code"] == "source_identity_ambiguous" else {}
            pending = operation["pending_fields"] if issue["code"] == "source_update_pending" else {}
            if pending:
                pending = {**pending, "_source_explanation": operation.get("source_explanation", {})}
            saved = sources.save_issue(parent_id, old.issue_key if old else "import:" + _digest(issue["code"], issue["field_path"]),
                code=issue["code"], field_path=issue["field_path"], pending_fields={key: value for key, value in pending.items() if key != "source_payload"},
                pending_source_payload=pending.get("source_payload"), candidates=candidates)
            retained.add(saved.id)
        for old in existing:
            if old.id not in retained:
                sources.clear_issue(parent_id, old.issue_key)

    @staticmethod
    def _row_outcome(operation):
        return {"create_posted": "created_posted", "create_draft": "saved_draft", "update_draft": "saved_draft",
            "update_posted": "updated_posted", "finalize_draft": "finalized_draft", "duplicate": "duplicate"}[operation["planned_action"]]

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

    def _save_groups(self, session, plan, fresh, identity, components, failed_rows, affected, *, manual):
        groups, result = defaultdict(list), []
        current = {group["suggestion_id"]: group for group in fresh["relation_groups"]}
        for group in plan["relation_groups"]:
            groups[group.get("commit_group_id", group["suggestion_id"])].append(group)
        for commit_id, members in groups.items():
            code = None
            try:
                if any(set(group["parent_ids"]).intersection(failed_rows) for group in members):
                    raise CashRelationError("relation_context_changed")
                unchanged = self._existing_groups(session, members, identity, components)
                if unchanged is not None:
                    result.extend({"result_kind": "relation", "item_ref": group["suggestion_id"], "outcome": "duplicate",
                        "relation_id": str(unchanged[group["suggestion_id"]])} for group in members)
                    continue
                for group in members:
                    updated = current.get(group["suggestion_id"])
                    if group["action"] == "skip":
                        raise CashRelationError(group.get("code", "relation_save_failed"))
                    if updated is None or updated["action"] != group["action"] or updated["edges"] != group["edges"]:
                        raise CashRelationError("relation_context_changed")
                    if set(group["parent_ids"]).intersection(failed_rows):
                        raise CashRelationError("relation_context_changed")
                with session.begin_nested():
                    written = self._write_group(session, members, identity, components, commit_id, manual, unavailable=failed_rows)
                    session.flush()
                for group in members:
                    result.append({"result_kind": "relation", "item_ref": group["suggestion_id"],
                        "outcome": {"accept": "confirmed", "defer": "deferred", "ignore": "ignored"}[group["action"]],
                        "relation_id": str(written[group["suggestion_id"]])})
            except Exception as error:
                if not _recoverable(error):
                    raise
                session.expire_all()
                code = _reason(error, "relation_save_failed")
                result.extend({"result_kind": "relation", "item_ref": group["suggestion_id"], "outcome": "failed", "code": code}
                    for group in members)
            for group in members:
                parents = {int(identity.get(parent, parent)) for parent in group["parent_ids"]
                    if parent not in failed_rows and int(identity.get(parent, parent)) < 2 ** 63}
                affected.update(parents)
                if code:
                    self._relation_problem(session, parents, commit_id, code)
        return result

    def _existing_groups(self, session, groups, identity, components):
        additions = []
        for group in groups:
            if group["action"] == "skip":
                return None
            for value in group["edges"]:
                item = {**value}
                for role in ("primary", "secondary"):
                    key = role + "_transaction_id"
                    item[key] = identity.get(item[key], item[key])
                    if item[key] is not None and int(item[key]) >= 2 ** 63:
                        return None
                    key = role + "_component_id"
                    item[key] = components.get(item[key], item[key])
                additions.append((group, CashRelationGraphEdge(**item)))
        parents = {int(identifier) for _group, edge in additions for identifier in (edge.primary_transaction_id, edge.secondary_transaction_id) if identifier}
        repository = CurrentCashRelationRepository(session, self._workspace_id)
        existing = {_edge_key(CashRelationGraphEdge(**{key: str(row[key]) if key.endswith("_id") and row[key] is not None else row[key]
            for key in CashRelationGraphEdge.__dataclass_fields__})): row
            for row in repository.list_for_transactions(parents)}
        result, matched, joint_groups = {}, set(), set()
        for group, edge in additions:
            found = existing.get(_edge_key(edge))
            if found is None:
                return None
            for key in ("kind", "subtype", "state", "active", "primary_transaction_id", "secondary_transaction_id",
                "primary_component_id", "secondary_component_id", "primary_role", "secondary_role"):
                actual = found[key]
                if key.endswith("_id") and actual is not None:
                    actual = str(actual)
                if actual != getattr(edge, key):
                    return None
            expected_amount = _normalize("amount", edge.applied_amount) if edge.applied_amount is not None else 0
            anchor = components.get(group["anchors"][str(edge.id)], group["anchors"][str(edge.id)])
            anchor_parent = edge.primary_transaction_id if anchor == edge.primary_component_id else edge.secondary_transaction_id
            if (found["applied_amount"] != expected_amount or str(found["anchor_component_id"]) != str(anchor)
                or str(found["anchor_transaction_id"]) != str(anchor_parent) or found["evidence"] != group["evidence"][str(edge.id)]):
                return None
            if edge.state == "draft":
                expected = self._candidate_values(session, group, edge, anchor_parent, identity, components)
                def candidate_key(item):
                    return (str(item["candidate_transaction_id"]), str(item["candidate_component_id"]),
                        item["expected_row_revision"], _digest(item["evidence"]))
                if any(item["validity"] != "valid" for item in found["candidates"]) or {candidate_key(item) for item in expected} != {candidate_key(item) for item in found["candidates"]}:
                    return None
            matched.add(found["id"])
            if found["decision_group_id"]:
                joint_groups.add(found["decision_group_id"])
            result.setdefault(group["suggestion_id"], found["id"])
        if any(row["id"] not in matched or not row["active"] for row in repository.graph_rows_for_groups(joint_groups)):
            return None
        return result

    def _write_group(self, session, members, identity, components, commit_id, manual, *, unavailable=()):
        def parent(value):
            if value is None:
                return None
            result = identity.get(value, value)
            if not 0 < int(result) < 2 ** 63:
                raise CashRelationError("relation_endpoint_incomplete")
            return str(result)
        def part(value):
            if value is None:
                return None
            return components.get(value, value)
        additions, proof, group_by_id = [], {}, {}
        for group in members:
            for value in group["edges"]:
                edge = CashRelationGraphEdge(**dict(value, applied_amount=_normalize("amount", value["applied_amount"])
                    if value.get("applied_amount") is not None else None))
                converted = replace(edge, primary_transaction_id=parent(edge.primary_transaction_id),
                    secondary_transaction_id=parent(edge.secondary_transaction_id), primary_component_id=part(edge.primary_component_id),
                    secondary_component_id=part(edge.secondary_component_id))
                additions.append(converted)
                group_by_id[edge.id] = group
                proof[edge.id] = RelationRoleEvidence(**group["evidence"][str(edge.id)])
        parents = {int(identifier) for edge in additions for identifier in (edge.primary_transaction_id, edge.secondary_transaction_id) if identifier}
        service = CashRelationGraphService(session, self._workspace_id)
        context = service.load(parents)
        prepared = service.validate(context, additions=additions, evidence_by_relation=proof)
        resolved = {edge.id: edge for edge in prepared.relations}
        repository, written = CurrentCashRelationRepository(session, self._workspace_id), {}
        for original in additions:
            edge, group = resolved[original.id], group_by_id[original.id]
            anchor = part(group["anchors"][str(edge.id)])
            anchor_parent = edge.primary_transaction_id if anchor == edge.primary_component_id else edge.secondary_transaction_id
            row = repository.add({key: getattr(edge, key) for key in ("kind", "subtype", "state", "active",
                "primary_transaction_id", "secondary_transaction_id", "primary_component_id", "secondary_component_id", "primary_role", "secondary_role")}
                | {"anchor_transaction_id": anchor_parent, "anchor_component_id": anchor,
                    "decision_group_id": commit_id if len(additions) > 1 else None,
                    "applied_amount": edge.applied_amount or 0, "evidence": group["evidence"][str(edge.id)],
                    "created_by": self._user_id, "decision_source": "manual" if group["suggestion_id"] in manual else "rule",
                    "decided_by": self._user_id if group["action"] != "defer" else "", "decided_at": datetime.now(timezone.utc) if group["action"] != "defer" else None})
            written.setdefault(group["suggestion_id"], row["id"])
            if edge.state == "draft":
                repository.set_candidates(row["id"], self._candidate_values(session, group, edge, anchor_parent,
                    identity, components, unavailable=unavailable), expected_revision=row["row_revision"])
        return written

    def _candidate_values(self, session, group, edge, anchor_parent, identity, components, *, unavailable=()):
        candidates = {}
        for choice in group["candidates"]:
            variant = next((item for item in choice["edges"] if item["id"] == edge.id), None)
            if variant is None or any(item[role + "_transaction_id"] in unavailable
                or item[role + "_transaction_id"] is not None and int(identity.get(item[role + "_transaction_id"], item[role + "_transaction_id"])) >= 2 ** 63
                for item in choice["edges"] for role in ("primary", "secondary")):
                continue
            for role in ("primary", "secondary"):
                component = components.get(variant[role + "_component_id"], variant[role + "_component_id"])
                identifier = identity.get(variant[role + "_transaction_id"], variant[role + "_transaction_id"])
                if identifier is not None and identifier != anchor_parent:
                    target = session.get(CashTransactionModel, int(identifier))
                    if target and target.workspace_id == self._workspace_id and target.deleted_at is None:
                        candidates[(identifier, component)] = {"candidate_transaction_id": identifier,
                            "candidate_component_id": component, "expected_row_revision": target.row_revision,
                            "evidence": choice["evidence"][str(edge.id)]}
        return list(candidates.values())

    def _relation_problem(self, session, parents, reference, code):
        source = CashSourceRepository(session, self._workspace_id)
        for batch in _batches(parents):
            active = session.scalars(select(CashTransactionModel.id).where(CashTransactionModel.workspace_id == self._workspace_id,
                CashTransactionModel.id.in_(batch), CashTransactionModel.deleted_at.is_(None)))
            for identifier in active:
                source.save_issue(identifier, "relation:" + _digest(reference), code=code)
        session.flush()

    def _discovery_issues(self, session, plan, identity, failed_rows, affected):
        for failure in plan["relation_failures"]:
            if failure.get("suggestion_id"):
                continue
            parents = {int(identity.get(parent, parent)) for parent in failure["parent_ids"]
                if parent not in failed_rows and int(identity.get(parent, parent)) < 2 ** 63}
            self._relation_problem(session, parents, _digest("discovery", sorted(parents)), failure["code"])
            affected.update(parents)

    def _balances(self, uow, changes):
        def finances(row):
            return [(part["account_id"], part["amount"], part["currency"]) for part in row["components"]] if counts_toward_balance(row) else []
        relevant = [(before, after) for before, after in changes if finances(before) != finances(after)]
        if not relevant:
            return
        session = uow._state().session
        accounts = {str(row.id): row for row in session.scalars(select(AccountModel).where(AccountModel.workspace_id == self._workspace_id))}
        snapshot = uow.snapshot.load(lock=True)
        latest = None
        for before, after in relevant:
            for row, multiplier in ((before, -1), (after, 1)):
                if not counts_toward_balance(row):
                    continue
                value = deepcopy(row)
                for component in value["components"]:
                    account = accounts[component["account_id"]]
                    component.update(account_name=account.name, account_type=account.type)
                apply_snapshot_components(snapshot, uow, value, multiplier)
                latest = row["occurred_at"]
        snapshot["updated_at"] = latest
        uow.snapshot.save(snapshot)

    def _attention_count(self, session, affected):
        from ft.adapters.relational.cash_attention import read_cash_attention
        return len(read_cash_attention(session, self._workspace_id, affected).transaction_ids)

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
