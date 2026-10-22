"""从当前流水操作生成与导入同类的不可变维护预览。"""
from sqlalchemy import select

from ft.adapters.cash_statement_evidence import extract_source_explanation
from ft.adapters.relational.models import CashProjectionMemberModel, CashProjectionModel
from ft.adapters.relational.projections import RelationalCashProjectionRepository
from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
from ft.application.cash_draft_merge import CashDraftMergePlanner
from ft.application.cash_record_operations import (CashRecordMaintenanceError, CashRecordOperationPlanner,
    _identifier)
from ft.domain.cash_record_policy import CashRecordPolicyError


class CashMutationBusinessPlanBuilder(CashImportBusinessPlanBuilder):
    def __call__(self, *, session, preview_input, previous_plan, current_record=None):
        intent = preview_input["intent"]
        if intent not in {"edit_record", "finalize_draft", "resolve_issue"}:
            raise CashRecordMaintenanceError("plan_input_invalid")
        planner = CashRecordOperationPlanner(session, self.workspace_id)
        row = planner.record(_identifier(preview_input["transaction_id"]), current_record=current_record)
        current_records = {str(row.id): row}
        patch = preview_input["patch"]
        if intent == "edit_record" and row.state != "posted":
            raise CashRecordMaintenanceError("posted_required")
        resolutions = preview_input.get("issue_resolutions", [])
        merging = any(choice["resolution"] == "merge_into" for choice in resolutions)
        additional_seeds, decision_groups = set(), set()
        if merging:
            source = row
            row, operation = CashDraftMergePlanner(planner).prepare(source, resolutions, patch)
            current_records[str(row.id)] = row
            additional_seeds.add(source.id)
            for relation in operation["merge_draft"]["references"]["relations"]:
                additional_seeds.update(relation[key] for key in ("primary_transaction_id", "secondary_transaction_id") if relation[key] is not None)
                if relation["decision_group_id"] is not None:
                    decision_groups.add(relation["decision_group_id"])
        elif resolutions:
            operation = planner.prepare_resolving(row, patch, resolutions, finalize=intent == "finalize_draft")
        else:
            operation = (planner.prepare_finalize(row, patch) if intent == "finalize_draft" else planner.prepare_edit(row, patch))
        operations = [operation]
        category_origin = operation.get("merge_draft", {}).get("category_origin")
        category_members = self._category_members(session, row.id) if "category_id" in patch or category_origin else []
        category_value = operation["values"]["category_id"]
        for identifier in category_members:
            if identifier != str(row.id):
                member = planner.record(int(identifier))
                current_records[identifier] = member
                protected = bool((member.manual_overrides or {}).get("category_id"))
                if "category_id" not in patch and protected:
                    if category_origin == "human" and member.category_id != category_value:
                        raise CashRecordPolicyError("source_resolution_conflict", ("category_id",))
                    continue
                operations.append(planner._prepare(member, {}, source_patch={"category_id": category_value})
                    if "category_id" not in patch and category_origin == "source"
                    else planner.prepare_edit(member, {"category_id": category_value}))
        scopes = self._source_scopes(session, {int(identifier) for identifier in current_records})
        for index, operation in enumerate(operations, 1):
            identifier, source = operation["transaction_id"], operation["values"]["source_type"]
            current = current_records[identifier]
            operation.update(operation_ref=f"op_{index}", row_refs=[],
                source_scope_key=scopes.get((identifier, source), self._fallback_scope(source, identifier)),
                matching_record_id="occurrence:" + identifier,
                matching_source_explanation=extract_source_explanation(current.relation_metadata))
        rows = [{"transaction_id": op["transaction_id"], "operation_ref": op["operation_ref"],
            "planned_action": op["planned_action"]} for op in operations]
        expected_count = len(operations)
        result = self.build_operations(session=session, operations=operations, rows=rows,
            relation_choices=preview_input["relation_choices"], previous_plan=previous_plan, include_unchanged=True,
            current_records=current_records, source_scope_snapshot=(frozenset(int(key) for key in current_records), scopes),
            additional_seed_ids=additional_seeds, decision_group_ids=decision_groups,
            snapshot_transform=CashDraftMergePlanner.overlay if merging else None)
        if len(operations) != expected_count:
            # 一次维护意图不能只保存整笔分类的一部分。
            raise CashRecordPolicyError("relation_endpoint_incompatible", ("record_type", "record_subtype"))
        for detail in result["item_details"].values():
            for member in detail["members"]:
                member["editable_fields"] = [field for field in member["editable_fields"] if field != "account_name"]
                member["allowed_actions"] = ["edit_record"] if member["transaction_id"] not in {
                    op["transaction_id"] for op in operations if op["current"]["state"] == "draft"} else ["save_draft", "finalize_draft"]
                if merging and member["transaction_id"] == str(row.id) and row.state == "draft":
                    member["allowed_actions"] = ["save_draft"]
        # 联合写入器接入前，合并只能核对预计结果，不允许走通用单行保存。
        result.update(can_commit=bool(operations) and not merging, category_member_ids=category_members,
            mutation_units=[[op["operation_ref"] for op in operations]])
        result["summary"]["relations_removed"] = len(result["type_effects"]["removed_relation_ids"]) + len(result["type_effects"]["removed_funding_ids"])
        resolved_count = sum(len(op.get("resolved_issue_ids", [])) for op in operations)
        if resolved_count:
            result["summary"]["resolve_issue"] = resolved_count
        independent = sum(any(choice["resolution"] == "create_independent" for choice in op.get("issue_resolutions", [])) for op in operations)
        if independent:
            result["summary"]["create_independent"] = independent
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
