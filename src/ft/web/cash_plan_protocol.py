"""不可变计划和当前处理结果的公开 HTTP 结构。"""
import re
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field

from ft.web.cash_import_protocol import CashImportIssueView, CashImportProtocolError, Revision, StrictInput


Reference = Annotated[str, Field(pattern=r"^[A-Za-z0-9_:.~-]{1,255}$")]
ShortReference = Annotated[str, Field(pattern=r"^[A-Za-z0-9_:.~-]{1,128}$")]
Count = Annotated[int, Field(strict=True, ge=0)]


class AccountSelectionInput(StrictInput):
    source_account_ref: Reference
    account_id: ShortReference | None = None
    expected_account_revision: Revision | None = None


class RowChoiceInput(StrictInput):
    row_ref: Reference
    action: Literal["apply", "keep_existing", "defer"]
    patch: dict[str, Any] = Field(default_factory=dict)


class RelationChoiceInput(StrictInput):
    suggestion_id: Reference
    action: Literal["accept", "defer", "ignore"]
    candidate_id: ShortReference | None = None


class ImportPreviewInput(StrictInput):
    expected_session_revision: Revision
    previous_plan_id: ShortReference | None
    account_selections: list[AccountSelectionInput] = Field(max_length=100_000)
    row_choices: list[RowChoiceInput] = Field(max_length=100_000)
    relation_choices: list[RelationChoiceInput] = Field(max_length=100_000)


class ImportCommitInput(StrictInput):
    expected_session_revision: Revision


class RetrySessionInput(StrictInput):
    row_refs: list[Reference] | None = Field(default=None, min_length=1, max_length=100_000)


class ResolveInput(StrictInput):
    plan_id: ShortReference
    idempotency_key: Reference


class Output(BaseModel):
    model_config = ConfigDict(strict=True, extra="ignore")


class IssueView(CashImportIssueView):
    after_import_action: Literal["open_home_item"] | None = None


class MemberRef(Output):
    row_ref: str | None = None
    transaction_id: str | None = None


class DisplayView(Output):
    occurred_at: str | None
    amount: str | None
    currency: str | None
    counterparty: str | None
    note: str | None
    accounts: list[str]


class PreviewItemView(Output):
    preview_item_id: str
    confirmation_status: Literal["confirmed", "pending"]
    anticipated_state: Literal["posted", "draft"]
    display: DisplayView
    statistics_eligible: bool
    member_refs: list[MemberRef]
    attention_reasons: list[IssueView]
    detail_available: bool


class PlanFileView(Output):
    file_ref: str
    display_name: str
    status: Literal["ready", "partial", "password_required", "failed"]
    recognized_row_count: int | None = None
    issues: list[IssueView] = Field(default_factory=list)
    code: str | None = None
    can_retry: bool


class InvalidatedChoiceView(Output):
    kind: str
    reference: str
    code: str


class PlanPages(Output):
    items: str | None
    processing_details: str | None


class ImportPlanView(Output):
    plan_id: str
    session_id: str
    session_revision: Revision
    expires_at: str
    summary: dict[str, Count]
    item_counts: dict[str, Count]
    can_commit: bool
    items: list[PreviewItemView]
    file_results: list[PlanFileView]
    pages: PlanPages
    invalidated_choices: list[InvalidatedChoiceView]


class ItemPageView(Output):
    items: list[PreviewItemView]
    next_cursor: str | None


class ProcessingItemView(MemberRef):
    file_ref: str | None = None
    planned_action: str
    operation_ref: str | None = None
    duplicate_of: str | None = None
    code: str | None = None
    message: str | None = None
    field_path: str | None = None
    blocking_scope: str | None = None
    next_action: str | None = None


class ProcessingPageView(Output):
    items: list[ProcessingItemView]
    next_cursor: str | None


class MemberDetailView(MemberRef):
    planned_action: str
    fields: dict[str, Any]
    editable_fields: list[str]
    allowed_actions: list[str]
    row_revision: int | None = None


class FieldDiffView(MemberRef):
    field: str
    current: Any
    incoming: Any
    effective: Any
    resolution: str


class RelationCandidateView(Output):
    candidate_id: str
    display: DisplayView | None = None
    member_refs: list[MemberRef] = Field(default_factory=list)
    code: str | None = None


class RelationChoiceView(Output):
    suggestion_id: str
    kind: str | None = None
    subtype: str | None = None
    action: str
    candidate_id: str | None = None
    candidates: list[RelationCandidateView]
    member_refs: list[MemberRef]
    allowed_actions: list[str]
    code: str | None = None


class RelationEffectView(Output):
    relation_id: str
    kind: str
    subtype: str | None = None
    effect: str
    member_refs: list[MemberRef]
    code: str | None = None


class PlanItemDetailView(Output):
    preview_item_id: str
    members: list[MemberDetailView]
    field_diffs: list[FieldDiffView]
    issues: list[IssueView]
    relation_choices: list[RelationChoiceView]
    relation_effects: list[RelationEffectView]


class ReceiptAccountView(Output):
    account_id: str
    name: str
    type: str
    active: bool
    currencies: list[str]
    account_revision: Revision
    deleted: bool


class MutationReceiptView(Output):
    receipt_id: str
    plan_id: str | None
    operation_kind: str
    committed: Literal[True]
    created_at: str
    status: Literal["completed", "partial", "no_changes", "failed"] | None = None
    summary: dict[str, Count] = Field(default_factory=dict)
    needs_attention_count: Count | None = None
    pending_item_count: Count | None = None
    projection_version: Count | None = None
    retry_context_status: Literal["available", "reselect_file"] | None = None
    retry_context_expires_at: str | None = None
    retained_transaction_id: str | None = None
    account_id: str | None = None
    account_revision: Revision | None = None
    account: ReceiptAccountView | None = None


class MutationResultView(Output):
    result_id: str
    result_kind: Literal["row", "relation", "file", "account"]
    item_ref: str
    outcome: str
    code: str | None = None
    message: str | None = None
    operation_ref: str | None = None
    transaction_id: str | None = None
    relation_id: str | None = None
    account_id: str | None = None


class ProjectionRef(Output):
    transaction_id: str
    projection_id: str


class MutationResultPageView(Output):
    items: list[MutationResultView]
    next_after: str | None
    projection_refs: list[ProjectionRef]
    projection_version: Count


class ResolveView(Output):
    status: Literal["committed", "retry_original", "not_committed", "pending"]
    receipt: MutationReceiptView | None = None


class ErrorDetailView(Output):
    code: str
    message: str
    field_errors: list[dict[str, str]]
    retryable: bool


class CashPlanErrorView(Output):
    error: ErrorDetailView
    receipt_id: str | None = None
    commit_state: Literal["unknown", "not_committed"] | None = None


def idempotency_key(request):
    key = request.headers.get("Idempotency-Key", "")
    if not re.fullmatch(r"[A-Za-z0-9_:.~-]{1,255}", key):
        raise CashImportProtocolError("idempotency_key_required")
    return key


def page_input(request, *, results=False, processing=False):
    allowed = {"limit", "after"} if results else {"limit", "cursor"} | (set() if processing else {"confirmation_status"})
    items = request.query_params.multi_items()
    if any(key not in allowed for key, _ in items) or len(dict(items)) != len(items):
        raise CashImportProtocolError
    value = dict(items)
    numbers = {"limit": "50", **({"after": "0"} if results else {})}
    for field, default in numbers.items():
        text = value.get(field, default)
        if not re.fullmatch(r"0|[1-9][0-9]{0,18}", text):
            raise CashImportProtocolError
        number = int(text)
        if not (0 <= number < 2 ** 63) or field == "limit" and not 1 <= number <= 200:
            raise CashImportProtocolError
        value[field] = number
    return value


def json_contract(model, *, keyed=False):
    schema = model.model_json_schema()
    definitions = schema.pop("$defs", {})
    def inline(value):
        if isinstance(value, dict):
            if "$ref" in value:
                return inline({**definitions[value["$ref"].removeprefix("#/$defs/")],
                    **{key: item for key, item in value.items() if key != "$ref"}})
            if "propertyName" in value and "mapping" in value:
                # 判别联合的分支已内联，不能留下指向已移除 $defs 的映射。
                value = {key: item for key, item in value.items() if key != "mapping"}
            return {key: inline(item) for key, item in value.items()}
        if isinstance(value, list):
            return [inline(item) for item in value]
        return value
    contract = {"requestBody": {"required": True, "content": {"application/json": {"schema": inline(schema)}}}}
    if keyed:
        contract["parameters"] = [{"in": "header", "name": "Idempotency-Key", "required": True,
            "schema": {"type": "string", "pattern": r"^[A-Za-z0-9_:.~-]{1,255}$"}}]
    return contract


def page_contract(*, results=False, processing=False):
    properties = {"limit": {"type": "integer", "minimum": 1, "maximum": 200, "default": 50}}
    if results:
        properties["after"] = {"type": "string", "pattern": r"^(0|[1-9][0-9]{0,18})$", "default": "0"}
    else:
        properties["cursor"] = {"type": "string", "maxLength": 4096}
        if not processing:
            properties["confirmation_status"] = {"type": "string", "enum": ["all", "confirmed", "pending"], "default": "all"}
    return {"parameters": [{"in": "query", "name": name, "required": False, "schema": schema}
        for name, schema in properties.items()]}
