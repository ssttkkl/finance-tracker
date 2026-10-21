"""从当前流水操作生成与导入同类的不可变维护预览。"""
from sqlalchemy import select

from ft.adapters.cash_statement_evidence import extract_source_explanation
from ft.adapters.relational.models import CashProjectionMemberModel, CashProjectionModel
from ft.adapters.relational.projections import RelationalCashProjectionRepository
from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
from ft.application.cash_record_operations import (CashRecordMaintenanceError, CashRecordOperationPlanner,
    _identifier)
from ft.domain.cash_record_policy import CashRecordPolicyError


class CashMutationBusinessPlanBuilder(CashImportBusinessPlanBuilder):
    def __call__(self, *, session, preview_input, previous_plan):
        intent = preview_input["intent"]
        if intent not in {"edit_record", "finalize_draft"}:
            raise CashRecordMaintenanceError("plan_input_invalid")
        planner = CashRecordOperationPlanner(session, self.workspace_id)
        row = planner.record(_identifier(preview_input["transaction_id"]))
        patch = preview_input["patch"]
        operation = (planner.prepare_finalize(row, patch) if intent == "finalize_draft" else planner.prepare_edit(row, patch))
        operations = [operation]
        category_members = self._category_members(session, row.id) if "category_id" in patch else []
        for identifier in category_members:
            if identifier != str(row.id):
                member = planner.record(int(identifier))
                operations.append(planner.prepare_edit(member, {"category_id": patch["category_id"]}))
        scopes = self._source_scopes(session, {int(op["transaction_id"]) for op in operations})
        for index, operation in enumerate(operations, 1):
            identifier, source = operation["transaction_id"], operation["values"]["source_type"]
            current = planner.record(int(identifier))
            operation.update(operation_ref=f"op_{index}", row_refs=[],
                source_scope_key=scopes.get((identifier, source), self._fallback_scope(source, identifier)),
                matching_record_id="occurrence:" + identifier,
                matching_source_explanation=extract_source_explanation(current.relation_metadata))
        rows = [{"transaction_id": op["transaction_id"], "operation_ref": op["operation_ref"],
            "planned_action": op["planned_action"]} for op in operations]
        expected_count = len(operations)
        result = self.build_operations(session=session, operations=operations, rows=rows,
            relation_choices=preview_input["relation_choices"], previous_plan=previous_plan, include_unchanged=True)
        if len(operations) != expected_count:
            # 一次维护意图不能只保存整笔分类的一部分。
            raise CashRecordPolicyError("relation_endpoint_incompatible", ("record_type", "record_subtype"))
        for detail in result["item_details"].values():
            for member in detail["members"]:
                member["editable_fields"] = [field for field in member["editable_fields"] if field != "account_name"]
                member["allowed_actions"] = ["edit_record"] if member["transaction_id"] not in {
                    op["transaction_id"] for op in operations if op["current"]["state"] == "draft"} else ["save_draft", "finalize_draft"]
        result.update(can_commit=bool(operations), category_member_ids=category_members,
            mutation_units=[[op["operation_ref"] for op in operations]])
        result["summary"]["relations_removed"] = len(result["type_effects"]["removed_relation_ids"]) + len(result["type_effects"]["removed_funding_ids"])
        return result

    @staticmethod
    def _ref(snapshot, identifier):
        return {"transaction_id": str(identifier)}

    def _category_members(self, session, identifier):
        projections = RelationalCashProjectionRepository(session, self.workspace_id)
        state = projections._state()
        if state is None or state.availability != "ready" or not state.active_dataset_id:
            raise CashRecordMaintenanceError("projection_unavailable")
        reference = projections.current_references({identifier}, state=state).get(str(identifier))
        projection = session.scalar(select(CashProjectionModel).where(CashProjectionModel.workspace_id == self.workspace_id,
            CashProjectionModel.dataset_id == state.active_dataset_id,
            CashProjectionModel.projection_id == reference["projection_id"])) if reference else None
        if projection is None:
            raise CashRecordMaintenanceError("projection_unavailable")
        members = list(session.scalars(select(CashProjectionMemberModel.cash_transaction_id).where(
            CashProjectionMemberModel.workspace_id == self.workspace_id, CashProjectionMemberModel.dataset_id == state.active_dataset_id,
            CashProjectionMemberModel.projection_row_id == projection.id).order_by(CashProjectionMemberModel.cash_transaction_id)))
        if len(members) != projection.member_count or identifier not in members:
            raise CashRecordMaintenanceError("projection_changed")
        return [str(member) for member in members]
