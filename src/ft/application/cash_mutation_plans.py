"""库中流水维护计划的无文件发布、读取及消费校验边界。"""
from copy import deepcopy
from hashlib import sha256
import re
from secrets import token_urlsafe

from ft.adapters.relational.cash_mutations import CashMutationConflict, CashMutationRepository
from ft.application.cash_import_plans import (_choices, _objects, _page, _public_detail,
    _reference, _resolve_detail, _validate_readable)
from ft.application.cash_import_staging import ImportSessionExpired, ImportSessionNotFound
from ft.application.cash_record_maintenance import (CashRecordMaintenanceError,
    CashRecordMaintenanceService, _identifier, _request_digest)
from ft.application.parsed_import_staging import ImportSessionClosed, ImportSessionRevisionConflict


class CashMutationPlanError(CashRecordMaintenanceError):
    pass


_COMMON = {"intent", "transaction_id", "expected_revision", "patch", "relation_choices", "issue_resolutions"}
_META = {"edit_context_id", "previous_plan_id", "expected_plan_revision"}
_ISSUE = {"issue_id", "expected_issue_revision", "resolution", "target_transaction_id", "expected_target_revision"}
_INTENTS = {"edit_record", "finalize_draft", "resolve_issue", "maintain_relations"}
_SUMMARY_COUNTS = {"update_posted", "finalize_draft", "update_draft", "resolve_issue", "merge_draft",
    "create_independent", "duplicate", "skip", "relations_accepted", "relations_deferred",
    "relations_ignored", "relations_removed", "relations_failed"}


def _revision(value):
    return type(value) is int and 1 <= value < 2 ** 63


def _context_id(plan_id):
    match = re.fullmatch(r"mp_([A-Za-z0-9_-]{43})\.([A-Za-z0-9_-]{16})", plan_id) if isinstance(plan_id, str) else None
    if match is None:
        raise CashMutationPlanError("plan_not_found")
    return match[1]


def _issue_resolutions(value):
    if not isinstance(value, list) or len(value) > 100:
        raise CashMutationPlanError("plan_input_invalid")
    result, seen = [], set()
    for item in value:
        if not isinstance(item, dict) or set(item) - _ISSUE:
            raise CashMutationPlanError("plan_input_invalid")
        resolution = item.get("resolution")
        if (not isinstance(resolution, str) or resolution not in {"apply_source", "keep_existing", "merge_into", "create_independent"}
            or not _revision(item.get("expected_issue_revision"))):
            raise CashMutationPlanError("plan_input_invalid")
        identifier = str(_identifier(item.get("issue_id"), code="plan_input_invalid"))
        if identifier in seen:
            raise CashMutationPlanError("plan_input_invalid")
        seen.add(identifier)
        choice = {"issue_id": identifier, "expected_issue_revision": item["expected_issue_revision"], "resolution": resolution}
        if resolution == "merge_into":
            choice["target_transaction_id"] = str(_identifier(item.get("target_transaction_id"), code="plan_input_invalid"))
            if not _revision(item.get("expected_target_revision")):
                raise CashMutationPlanError("plan_input_invalid")
            choice["expected_target_revision"] = item["expected_target_revision"]
        elif {"target_transaction_id", "expected_target_revision"} & item.keys():
            raise CashMutationPlanError("plan_input_invalid")
        result.append(choice)
    return result


def _input(value):
    if not isinstance(value, dict):
        raise CashMutationPlanError("plan_input_invalid")
    _request_digest("cash-mutation-preview-v1", value)
    intent = value.get("intent")
    if (not isinstance(intent, str) or intent not in _INTENTS
        or value.keys() - _COMMON - _META
        or not _revision(value.get("expected_revision")) or not isinstance(value.get("patch", {}), dict)):
        raise CashMutationPlanError("plan_input_invalid")
    data = {key: deepcopy(item) for key, item in value.items() if key not in _META}
    data["transaction_id"] = str(_identifier(value.get("transaction_id"), code="plan_input_invalid"))
    data.setdefault("patch", {})
    data["relation_choices"] = _choices(value.get("relation_choices", []), key="suggestion_id",
        fields={"suggestion_id", "action", "candidate_id"}, actions={"accept", "defer", "ignore", "remove"})
    data["issue_resolutions"] = _issue_resolutions(value.get("issue_resolutions", []))
    merges = [choice for choice in data["issue_resolutions"] if choice["resolution"] == "merge_into"]
    if (intent == "resolve_issue" and not data["issue_resolutions"] or intent == "maintain_relations" and data["issue_resolutions"]
        or len(merges) > 1 or merges and (intent == "finalize_draft"
            or any(choice["resolution"] == "create_independent" for choice in data["issue_resolutions"]))):
        raise CashMutationPlanError("plan_input_invalid")
    metadata = {key: value.get(key) for key in _META}
    context = metadata["edit_context_id"]
    if context is None:
        if metadata["previous_plan_id"] is not None or metadata["expected_plan_revision"] is not None:
            raise CashMutationPlanError("plan_input_invalid")
    elif (not isinstance(context, str) or re.fullmatch(r"[A-Za-z0-9_-]{43}", context) is None
        or not _reference(metadata["previous_plan_id"], 128) or not _revision(metadata["expected_plan_revision"])):
        raise CashMutationPlanError("plan_input_invalid")
    return data, metadata


def _summary(value):
    if (not isinstance(value, dict) or value.keys() - _SUMMARY_COUNTS
        or any(type(count) is not int or not 0 <= count < 2 ** 63 for count in value.values())):
        raise CashMutationPlanError("plan_build_failed")
    return dict(value)


def _validate_build(result):
    if (not isinstance(result, dict) or not isinstance(result.get("items"), list)
        or not isinstance(result.get("summary"), dict) or type(result.get("can_commit")) is not bool):
        raise CashMutationPlanError("plan_build_failed")
    _summary(result["summary"])
    items = result["items"]
    if (any(not isinstance(item, dict) or not _reference(item.get("preview_item_id"), 128)
        or not isinstance(item.get("confirmation_status"), str)
        or item["confirmation_status"] not in {"confirmed", "pending"} for item in items)
        or len({item["preview_item_id"] for item in items}) != len(items)):
        raise CashMutationPlanError("plan_build_failed")
    _validate_readable(result)


class CashMutationPlanService(CashRecordMaintenanceService):
    def __init__(self, uow, staging_store, *, user_id, builder=None):
        super().__init__(uow, user_id=user_id)
        if builder is None:
            from ft.application.cash_mutation_business_plan import CashMutationBusinessPlanBuilder
            builder = CashMutationBusinessPlanBuilder(self._workspace_id)
        self._staging, self._builder = staging_store, builder

    def _context(self, session, identifier):
        if not isinstance(identifier, str) or re.fullmatch(r"[A-Za-z0-9_-]{43}", identifier) is None:
            raise ImportSessionNotFound
        receipt = CashMutationRepository(session, self._workspace_id).for_context(self._user_id,
            edit_context_digest=sha256(identifier.encode()).hexdigest())
        if receipt is not None:
            raise CashMutationConflict("edit_context_committed", receipt["receipt_id"])
        return self._stored_context(identifier)

    def _stored_context(self, identifier):
        """只加载临时内容；调用方已在当前事务授权并检查永久消费。"""
        current = self._staging.get(identifier, workspace_id=self._workspace_id, user_id=self._user_id)
        if current.workflow_kind != "mutation" or current.files:
            raise ImportSessionNotFound
        if current.cancelled:
            raise CashMutationPlanError("plan_changed")
        data = current.context_data
        if (set(data) != {"transaction_id", "version"} or type(data["version"]) is not int or data["version"] != 1
            or not isinstance(data["transaction_id"], str)
            or str(_identifier(data["transaction_id"], code="plan_changed")) != data["transaction_id"]):
            raise CashMutationPlanError("plan_changed")
        return current

    @staticmethod
    def _checked_plan(current, plan_id=None, *, expected_plan_revision=None):
        plan = current.plan
        if (plan is None or plan_id is not None and plan.get("plan_id") != plan_id
            or plan.get("edit_context_id") != current.session_id
            or plan.get("context_revision") != current.revision or not _revision(plan.get("plan_revision"))
            or expected_plan_revision is not None and (not _revision(expected_plan_revision)
                or plan["plan_revision"] != expected_plan_revision)):
            raise CashMutationPlanError("plan_changed")
        return plan

    @staticmethod
    def _view(plan):
        page = _page(plan, "items")
        return {key: deepcopy(plan[key]) for key in ("plan_id", "edit_context_id", "plan_revision",
            "expires_at", "item_counts", "can_commit")} | {"summary": _summary(plan["summary"]), "items": page["items"],
                "pages": {"items": page["next_cursor"]},
                "invalidated_choices": _objects(plan.get("invalidated_choices", []), {"kind", "reference", "code"})}

    def preview(self, value):
        data, metadata = _input(value)
        try:
            with self._uow as uow:
                session = uow._state().session
                self._authorize(session, write=True)
                current, previous = None, None
                if metadata["edit_context_id"] is not None:
                    current = self._context(session, metadata["edit_context_id"])
                    previous = self._checked_plan(current, metadata["previous_plan_id"],
                        expected_plan_revision=metadata["expected_plan_revision"])
                    if current.context_data["transaction_id"] != data["transaction_id"]:
                        raise CashMutationPlanError("plan_changed")
                row = self._record(session, int(data["transaction_id"]))
                if row.row_revision != data["expected_revision"]:
                    raise CashMutationConflict("record_changed")
                result = self._builder(session=session, preview_input=deepcopy(data), previous_plan=deepcopy(previous),
                    current_record=row)
                _validate_build(result)
                if current is None:
                    current = self._staging.create(workspace_id=self._workspace_id, user_id=self._user_id,
                        workflow_kind="mutation", files=(), context_data={"transaction_id": data["transaction_id"], "version": 1})
                plan = dict(deepcopy(result), plan_id=f"mp_{current.session_id}.{token_urlsafe(12)}",
                    edit_context_id=current.session_id, plan_revision=previous["plan_revision"] + 1 if previous else 1,
                    context_revision=current.revision + 1, contract_version=1, expires_at=current.expires_at.isoformat(),
                    item_counts={status: sum(item["confirmation_status"] == status for item in result["items"])
                        for status in ("confirmed", "pending")})
                view = self._view(plan)
                self._staging.replace(current.session_id, workspace_id=self._workspace_id, user_id=self._user_id,
                    expected_revision=current.revision, files=(), preview_input=data, plan=plan)
                # 临时计划独立发布；退出只读数据库事务，不提交构建器的任何业务写入。
                return view
        except (ImportSessionRevisionConflict, ImportSessionClosed):
            raise CashMutationPlanError("plan_changed") from None
        except (ImportSessionExpired, ImportSessionNotFound):
            raise CashMutationPlanError("plan_expired") from None

    def _read_plan(self, plan_id=None, *, edit_context_id=None):
        identifier = _context_id(plan_id) if edit_context_id is None else edit_context_id
        try:
            with self._uow as uow:
                session = uow._state().session
                self._authorize(session, write=False)
                current = self._context(session, identifier)
                return deepcopy(self._checked_plan(current, plan_id))
        except (ImportSessionExpired, ImportSessionNotFound):
            raise CashMutationPlanError("plan_expired") from None

    def read_plan(self, plan_id):
        return self._view(self._read_plan(plan_id))

    def current_plan(self, edit_context_id):
        return self._view(self._read_plan(edit_context_id=edit_context_id))

    def items(self, plan_id, *, cursor=None, limit=50, confirmation_status=None):
        return _page(self._read_plan(plan_id), "items", cursor=cursor, limit=limit, confirmation_status=confirmation_status)

    def item(self, plan_id, preview_item_id):
        plan = self._read_plan(plan_id)
        detail = plan.get("item_details", {}).get(preview_item_id) if isinstance(preview_item_id, str) else None
        if detail is None:
            raise CashMutationPlanError("plan_item_not_found")
        return _public_detail(_resolve_detail(plan, detail)) | {"preview_item_id": preview_item_id}

    def current_for_commit(self, uow, plan_id, *, expected_plan_revision):
        try:
            session = uow._state().session
            self._authorize(session, write=True)
            current = self._context(session, _context_id(plan_id))
            if not _revision(expected_plan_revision):
                raise CashMutationPlanError("plan_changed")
            self._checked_plan(current, plan_id, expected_plan_revision=expected_plan_revision)
            return current
        except (ImportSessionExpired, ImportSessionNotFound):
            raise CashMutationPlanError("plan_expired") from None

    def cancel(self, edit_context_id, *, expected_plan_revision):
        try:
            with self._uow as uow:
                session = uow._state().session
                self._authorize(session, write=True)
                current = self._context(session, edit_context_id)
                if not _revision(expected_plan_revision):
                    raise CashMutationPlanError("plan_changed")
                self._checked_plan(current, expected_plan_revision=expected_plan_revision)
                self._staging.cancel(edit_context_id, workspace_id=self._workspace_id, user_id=self._user_id,
                    expected_revision=current.revision)
        except (ImportSessionRevisionConflict, ImportSessionClosed):
            raise CashMutationPlanError("plan_changed") from None
        except (ImportSessionExpired, ImportSessionNotFound):
            raise CashMutationPlanError("plan_expired") from None
