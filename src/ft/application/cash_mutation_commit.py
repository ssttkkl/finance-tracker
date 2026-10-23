"""在同一工作区事务中确认库中流水维护，并永久消费编辑上下文。"""
from collections import Counter
from hashlib import sha256

from ft.adapters.relational.cash_mutations import CashMutationConflict, CashMutationRepository
from ft.adapters.relational.projections import RelationalCashProjectionRepository
from ft.application.cash_import_issues import cash_import_issue
from ft.application.cash_commit_outcome import CashCommitBoundary
from ft.application.access import PermissionDenied
from ft.application.cash_record_operations import CashRecordMaintenanceError
from ft.application.cash_import_errors import ImportSessionExpired, ImportSessionForbidden, ImportSessionNotFound
from ft.application.cash_mutation_plans import CashMutationPlanError, CashMutationPlanService, _context_id, _revision
from ft.application.cash_draft_merge_writer import CashDraftMergeWriter
from ft.application.cash_current_relation_writer import CashCurrentRelationWriter
from ft.application.cash_plan_execution import (CashPlanWriteOperations, _operation_fingerprint,
    _reason, _receipt_view, _recoverable)
from ft.application.cash_projections import CashProjectionService
from ft.domain.cash_source_matching import _digest
from ft.domain.cash_record_policy import CashRecordPolicyError


class CashMutationCommandService(CashPlanWriteOperations, CashMutationPlanService):
    def commit(self, plan_id, *, expected_plan_revision, idempotency_key):
        boundary = CashCommitBoundary()
        with boundary.guard((CashRecordMaintenanceError, CashRecordPolicyError, CashMutationConflict, PermissionDenied,
            ImportSessionExpired, ImportSessionForbidden, ImportSessionNotFound)):
            return self._commit(plan_id, expected_plan_revision=expected_plan_revision,
                idempotency_key=idempotency_key, boundary=boundary)

    def _commit(self, plan_id, *, expected_plan_revision, idempotency_key, boundary):
        if not _revision(expected_plan_revision):
            raise CashMutationPlanError("plan_input_invalid")
        identifier = _context_id(plan_id)
        digest = _digest("cash-mutation-commit-v1", plan_id, expected_plan_revision)
        context_digest = sha256(identifier.encode()).hexdigest()
        with self._uow as uow:
            session = uow._state().session
            self._authorize(session, write=True)
            receipts = CashMutationRepository(session, self._workspace_id)
            replay = receipts.replay(self._user_id, idempotency_key, request_digest=digest,
                operation_kind="mutation", plan_id=plan_id)
            if replay is not None:
                return _receipt_view(replay)
            consumed = receipts.for_context(self._user_id, edit_context_digest=context_digest)
            if consumed is not None:
                raise CashMutationConflict("edit_context_committed", consumed["receipt_id"])
            boundary.no_prior_result()
            try:
                current = self._stored_context(identifier)
                self._checked_plan(current, plan_id, expected_plan_revision=expected_plan_revision)
            except (ImportSessionExpired, ImportSessionNotFound):
                raise CashMutationPlanError("plan_expired") from None
            plan = current.plan
            if not plan.get("can_commit"):
                raise CashMutationPlanError("plan_input_invalid")
            row = self._record(session, int(current.preview_input["transaction_id"]))
            if row.row_revision != current.preview_input["expected_revision"]:
                raise CashMutationConflict("record_changed")
            fresh = self._builder(session=session, preview_input=current.preview_input, previous_plan=plan, current_record=row)
            self._validate_current(plan, fresh)
            state = RelationalCashProjectionRepository(session, self._workspace_id).require_ready_state_lock()
            actual, identity, components, changes, affected, failures = {}, {}, {}, [], set(), set()
            early_groups, excluded_groups = [], set()
            operations = {op["operation_ref"]: op for op in plan["operations"]}
            for references in plan["mutation_units"]:
                unit = [operations[ref] for ref in references]
                relation_writer = CashCurrentRelationWriter(session, self._workspace_id, self._user_id)
                coupled = relation_writer.coupled_groups(plan, unit)
                excluded_groups.update(group["suggestion_id"] for group in coupled)
                try:
                    saved = []
                    with session.begin_nested():
                        relation_results = relation_writer.before_records(coupled, plan) if coupled else []
                        for operation in unit:
                            if operation.get("merge_draft"):
                                CashDraftMergeWriter(session, self._workspace_id).prepare_record_updates(operation)
                        for operation in unit:
                            saved.append((operation, self._save_row(session, operation, {})))
                        for operation, result in saved:
                            if operation.get("merge_draft"):
                                result[4].update(CashDraftMergeWriter(session, self._workspace_id).save(operation))
                        session.flush()
                except Exception as error:
                    if not _recoverable(error):
                        raise
                    session.expire_all()
                    if coupled:
                        failed_relations = relation_writer.failed_results(coupled, error)
                        early_groups.extend(failed_relations)
                        touched = relation_writer.parent_ids(coupled)
                        affected.update(touched)
                        for group, result in zip(coupled, failed_relations):
                            self._relation_problem(session, touched, group.get("commit_group_id", group["suggestion_id"]), result["code"])
                    for operation in unit:
                        failures.add(operation["preview_transaction_id"])
                        actual[operation["operation_ref"]] = {"outcome": "skipped", "transaction_id": operation.get("merge_draft", {}).get(
                            "source_transaction_id", operation["transaction_id"]),
                            "code": _reason(error, "row_save_failed")}
                else:
                    # 保存点成功之后的执行错误必须回滚外层，不能误报为已回滚单元。
                    early_groups.extend(relation_results)
                    if coupled:
                        affected.update(relation_writer.parent_ids(coupled))
                    for operation, result in saved:
                        parent, mapping, before, after, touched = result
                        identity[operation["preview_transaction_id"]] = str(parent)
                        components.update(mapping)
                        actual[operation["operation_ref"]] = {"outcome": self._row_outcome(operation), "transaction_id": str(parent)}
                        affected.update(touched)
                        if operation["planned_action"] != "duplicate":
                            affected.add(parent)
                        changes.append((before, after))
            rows = [{"result_kind": "row", "item_ref": operation.get("merge_draft", {}).get("source_transaction_id", operation["transaction_id"]),
                "operation_ref": operation["operation_ref"], **actual[operation["operation_ref"]]} for operation in plan["operations"]]
            groups = self._save_groups(session, plan, fresh, identity, components, failures, affected,
                manual={choice["suggestion_id"] for choice in current.preview_input["relation_choices"]}, excluded=excluded_groups)
            groups = [*early_groups, *groups]
            self._discovery_issues(session, plan, identity, failures, affected)
            groups.extend(self._discovery_results(plan, identity, failures))
            self._balances(uow, changes)
            if affected:
                CashProjectionService.maintain_in_session(session, self._workspace_id, affected, locked_state=state)
            session.flush()
            summary = Counter({key: 0 for key in ("updated_posted", "finalized_draft", "saved_draft", "merged_draft", "duplicate", "skipped",
                "relations_confirmed", "relations_deferred", "relations_ignored", "relations_removed", "relations_failed")})
            summary.update(item["outcome"] for item in actual.values())
            summary["resolved_issues"] = sum(len(operation.get("resolved_issue_ids", [])) for operation in plan["operations"]
                if actual[operation["operation_ref"]]["outcome"] != "skipped")
            for item in groups:
                summary["relations_" + item["outcome"]] += 1
            if not failures:
                summary["relations_removed"] += len(plan["type_effects"]["removed_relation_ids"]) + len(plan["type_effects"]["removed_funding_ids"])
            successful = affected | {int(item["transaction_id"]) for item in actual.values() if item["outcome"] != "skipped"}
            locations = RelationalCashProjectionRepository(session, self._workspace_id).current_references(successful, state=state)
            pending_items = len({value["projection_id"] for value in locations.values() if value["attention_count"] > 0})
            pending = self._attention_count(session, successful)
            problem = bool(failures) or any(item["outcome"] == "failed" for item in groups)
            succeeded = any(item["outcome"] not in {"skipped", "failed"} for item in (*rows, *groups))
            written = summary["resolved_issues"] > 0 or any(item["outcome"] not in {"skipped", "duplicate"} for item in (*rows, *groups))
            status = "partial" if problem and succeeded else "failed" if problem else "completed" if written else "no_changes"
            retained = next((actual[operation["operation_ref"]]["transaction_id"] for operation in plan["operations"]
                if operation.get("merge_draft")), str(row.id))
            receipt = receipts.create(user_id=self._user_id, idempotency_key=idempotency_key, request_digest=digest,
                operation_kind="mutation", plan_id=plan_id, edit_context_digest=context_digest,
                result_summary={"status": status, "summary": dict(summary), "retained_transaction_id": retained,
                    "projection_version": state.projection_version, "needs_attention_count": pending, "pending_item_count": pending_items},
                results=[*rows, *groups])
            boundary.commit(uow)
            try:
                self._staging.cancel(identifier, workspace_id=self._workspace_id, user_id=self._user_id, expected_revision=current.revision)
            except Exception:
                # 永久消费先阻断重写，固定 TTL 清理不影响成功结果。
                pass
            return _receipt_view(receipt)

    def _save_groups(self, session, plan, fresh, identity, components, failed_rows, affected, *, manual, excluded=()):
        if any(group.get("origin") == "current" for group in plan["relation_groups"]):
            return CashCurrentRelationWriter(session, self._workspace_id, self._user_id).save_groups(
                plan, fresh, failed_rows, affected, on_failure=self._relation_problem, excluded=excluded)
        return super()._save_groups(session, plan, fresh, identity, components, failed_rows, affected, manual=manual)

    @staticmethod
    def _validate_current(plan, fresh):
        old, new = plan["operations"], fresh["operations"]
        if (len(old) != len(new) or any(left["operation_ref"] != right["operation_ref"]
            or _operation_fingerprint(left) != _operation_fingerprint(right) for left, right in zip(old, new))):
            raise CashMutationConflict("record_changed")
        if any(plan.get(key) != fresh.get(key) for key in ("category_member_ids", "mutation_units", "dependencies",
            "type_effects", "relation_groups", "relation_failures")):
            raise CashMutationConflict("relation_context_changed")

    def receipt(self, receipt_id):
        with self._uow as uow:
            session = uow._state().session
            self._authorize(session, write=False)
            receipt = CashMutationRepository(session, self._workspace_id).get(self._user_id, receipt_id)
            if receipt is None:
                raise CashMutationPlanError("receipt_not_found")
            return _receipt_view(receipt)

    def receipt_by_key(self, idempotency_key):
        with self._uow as uow:
            session = uow._state().session
            self._authorize(session, write=False)
            receipt = CashMutationRepository(session, self._workspace_id).by_key(self._user_id, idempotency_key)
            if receipt is None:
                raise CashMutationPlanError("receipt_not_found")
            return _receipt_view(receipt)

    def results(self, receipt_id, *, result_kind=None, after=0, limit=50):
        with self._uow as uow:
            session = uow._state().session
            self._authorize(session, write=False)
            value = CashMutationRepository(session, self._workspace_id).results(self._user_id, receipt_id,
                result_kind=result_kind, after=after, limit=limit)
            if value is None:
                raise CashMutationPlanError("receipt_not_found")
            projections = RelationalCashProjectionRepository(session, self._workspace_id)
            state = projections.ready_state_lock_or_none()
            identifiers = {item["transaction_id"] for item in value["items"]
                if isinstance(item.get("transaction_id"), str) and item["transaction_id"].isascii()
                and item["transaction_id"].isdecimal() and 0 < int(item["transaction_id"]) < 2 ** 63}
            locations = projections.current_references(identifiers, state=state)
            value["projection_refs"] = [{key: item[key] for key in ("transaction_id", "projection_id")} for item in locations.values()]
            value["projection_version"] = state.projection_version if state is not None else 0
            for item in value["items"]:
                if item.get("code"):
                    item["message"] = cash_import_issue(item["code"])["message"]
            return value

    def resolve(self, plan_id, *, idempotency_key):
        identifier = _context_id(plan_id)
        from ft.adapters.relational.runtime import StorageError
        try:
            with self._uow.nonblocking() as uow:
                session = uow._state().session
                self._authorize(session, write=True)
                repository = CashMutationRepository(session, self._workspace_id)
                receipt = repository.by_key(self._user_id, idempotency_key)
                if receipt is not None and (receipt["operation_kind"] != "mutation" or receipt["plan_id"] != plan_id):
                    raise CashMutationConflict("idempotency_conflict", receipt["receipt_id"])
                receipt = receipt or repository.for_context(self._user_id, edit_context_digest=sha256(identifier.encode()).hexdigest())
                if receipt is not None:
                    return {"status": "committed", "receipt": _receipt_view(receipt)}
                try:
                    current = self._context(session, identifier)
                except (ImportSessionExpired, ImportSessionNotFound):
                    return {"status": "not_committed"}
                except CashMutationPlanError as error:
                    if error.code != "plan_changed":
                        raise
                    return {"status": "not_committed"}
                if current.plan is None or current.plan.get("plan_id") != plan_id:
                    return {"status": "not_committed"}
                return {"status": "retry_original"}
        except StorageError as error:
            if error.code != "storage.busy":
                raise
            return {"status": "pending"}
