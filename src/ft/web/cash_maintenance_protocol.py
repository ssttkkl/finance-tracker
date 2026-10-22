"""当前流水维护的公开字段、意图及计划结构。"""
from typing import Annotated, Any, Literal

from pydantic import Field, RootModel, model_validator

from ft.web.cash_import_protocol import Revision, StrictInput
from ft.web.cash_plan_protocol import (Count, DisplayView, InvalidatedChoiceView, IssueView,
    MutationReceiptView, Output, PreviewItemView, RelationChoiceInput, ShortReference)


Identifier = Annotated[str, Field(pattern=r"^[1-9][0-9]{0,18}$")]
EditContextReference = Annotated[str, Field(pattern=r"^[A-Za-z0-9_-]{43}$")]


class SourceResolutionInput(StrictInput):
    issue_id: Identifier
    expected_issue_revision: Revision
    resolution: Literal["apply_source", "keep_existing", "create_independent"]


class MergeResolutionInput(StrictInput):
    issue_id: Identifier
    expected_issue_revision: Revision
    resolution: Literal["merge_into"]
    target_transaction_id: Identifier
    expected_target_revision: Revision


IssueResolutionInput = Annotated[SourceResolutionInput | MergeResolutionInput, Field(discriminator="resolution")]


class MaintenanceRelationChoiceInput(RelationChoiceInput):
    action: Literal["accept", "defer", "ignore", "remove"]


class MaintenanceInput(StrictInput):
    transaction_id: Identifier
    expected_revision: Revision
    patch: dict[str, Any] = Field(default_factory=dict)
    relation_choices: list[MaintenanceRelationChoiceInput] = Field(default_factory=list, max_length=100_000)
    edit_context_id: EditContextReference | None = None
    previous_plan_id: ShortReference | None = None
    expected_plan_revision: Revision | None = None

    @model_validator(mode="after")
    def complete_context(self):
        if (self.edit_context_id is None and (self.previous_plan_id is not None or self.expected_plan_revision is not None)
            or self.edit_context_id is not None and (self.previous_plan_id is None or self.expected_plan_revision is None)):
            raise ValueError("maintenance_context_invalid")
        return self


class IssueMaintenanceInput(MaintenanceInput):
    issue_resolutions: list[IssueResolutionInput] = Field(default_factory=list, max_length=100)


class EditRecordInput(IssueMaintenanceInput):
    intent: Literal["edit_record"]


class FinalizeDraftInput(IssueMaintenanceInput):
    intent: Literal["finalize_draft"]


class ResolveIssueInput(IssueMaintenanceInput):
    intent: Literal["resolve_issue"]
    issue_resolutions: list[IssueResolutionInput] = Field(min_length=1, max_length=100)


class MaintainRelationsInput(MaintenanceInput):
    intent: Literal["maintain_relations"]


class MutationPreviewInput(RootModel[Annotated[
    EditRecordInput | FinalizeDraftInput | ResolveIssueInput | MaintainRelationsInput, Field(discriminator="intent")]]):
    pass


class DraftSaveInput(StrictInput):
    expected_revision: Revision
    patch: dict[str, Any]


class MutationCommitInput(StrictInput):
    expected_plan_revision: Revision


class IdentityCandidateView(Output):
    transaction_id: str
    row_revision: Revision
    state: Literal["draft", "posted"]
    display: DisplayView


class RecordIssueView(IssueView):
    issue_id: str
    revision: Revision
    allowed_resolutions: list[str]
    pending_fields: dict[str, Any]
    pending_source_payload: dict[str, Any] | None
    candidates: list[IdentityCandidateView] = Field(default_factory=list)


class RecordRelationView(Output):
    relation_id: str
    kind: str
    subtype: str | None
    state: Literal["draft", "confirmed", "ignored"]
    active: bool
    row_revision: Revision
    decision_group_id: str | None


class CurrentProjectionRef(Output):
    transaction_id: str
    projection_id: str
    attention_count: Count
    projection_version: Count


class CashRecordView(Output):
    transaction_id: str
    state: Literal["draft", "posted"]
    row_revision: Revision
    confirmation_status: Literal["confirmed", "pending"]
    fields: dict[str, Any]
    editable_fields: list[str]
    issues: list[RecordIssueView]
    relations: list[RecordRelationView]
    projection_ref: CurrentProjectionRef | None


class DraftSaveView(Output):
    receipt: MutationReceiptView
    current_record_status: Literal["available", "deleted", "unavailable"]
    current_record: CashRecordView | None


class MutationPages(Output):
    items: str | None


class MutationPlanView(Output):
    plan_id: str
    edit_context_id: str
    plan_revision: Revision
    expires_at: str
    summary: dict[str, Count]
    item_counts: dict[str, Count]
    can_commit: bool
    items: list[PreviewItemView]
    pages: MutationPages
    invalidated_choices: list[InvalidatedChoiceView]
