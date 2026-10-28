"""从当前流水操作生成与导入同类的不可变维护预览。"""
from sqlalchemy import select

from ft.adapters.cash_statement_evidence import extract_source_explanation
from ft.adapters.relational.models import CashProjectionMemberModel, CashProjectionModel
from ft.adapters.relational.projections import RelationalCashProjectionRepository
from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
from ft.application.cash_draft_merge import CashDraftMergePlanner
from ft.application.cash_current_relation_plan import CashCurrentRelationPlanner
from ft.application.cash_record_operations import (CashRecordMaintenanceError, CashRecordOperationPlanner,
    _identifier)
from ft.domain.cash_record_policy import CashRecordPolicyError


class CashMutationBusinessPlanBuilder(CashImportBusinessPlanBuilder):
    def __call__(self, *, session, preview_input, previous_plan, current_record=None):
        intent = preview_input["intent"]
        if intent not in {"save_draft", "edit_entry", "edit_record", "finalize_draft", "resolve_issue", "maintain_relations"}:
            raise CashRecordMaintenanceError("plan_input_invalid")
        planner = CashRecordOperationPlanner(session, self.workspace_id)
        row = planner.record(_identifier(preview_input["transaction_id"]), current_record=current_record)
        current_records = {str(row.id): row}
        patch = preview_input["patch"]
        from ft.adapters.relational.cash_entries import CashEntryRepository
        entry = CashEntryRepository(session, self.workspace_id).current(row.id)
        if intent == "edit_entry" and (entry is None or entry.id != preview_input["entry_id"]
            or entry.revision != preview_input["expected_entry_revision"]):
            raise CashRecordPolicyError("entry_changed", ())
        if intent in {"edit_entry", "edit_record"} and row.state != "posted":
            raise CashRecordMaintenanceError("posted_required")
        resolutions = preview_input.get("issue_resolutions", [])
        merging = any(choice["resolution"] == "merge_into" for choice in resolutions)
        additional_seeds, decision_groups = set(), set()
        current_relations = None
        if intent == "maintain_relations":
            current_relations = CashCurrentRelationPlanner(session, self.workspace_id, row.id, business_builder=self)
            additional_seeds.update(current_relations.seed_ids)
            decision_groups.update(current_relations.decision_group_ids)
            for addition in preview_input.get("relation_additions", []):
                target = planner.record(_identifier(addition["target_transaction_id"]))
                if target.record_origin not in {"import", "legacy"} or row.record_origin not in {"import", "legacy"}:
                    raise CashRecordPolicyError("relation_endpoint_incompatible", ())
                additional_seeds.add(target.id)
        def relation_groups(snapshot, accounts):
            groups = current_relations(snapshot, accounts)
            from ft.application.cash_manual_associations import manual_groups
            return groups + manual_groups(snapshot, current_relations, row.id, preview_input.get("relation_additions", []))
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
            operation = (planner.prepare_finalize(row, patch) if intent == "finalize_draft" else
                planner.prepare_draft(row, patch) if intent == "save_draft" or intent == "maintain_relations" and row.state == "draft" else planner.prepare_edit(row, patch))
        operations = [operation]
        transfer_relations = []
        member_patches = preview_input.get("member_patches", [])
        if member_patches:
            from ft.adapters.relational.models import CashEntryMemberModel
            member_ids = set(session.scalars(select(CashEntryMemberModel.transaction_id).where(
                CashEntryMemberModel.workspace_id == self.workspace_id, CashEntryMemberModel.entry_id == entry.id)))
            if (row.record_origin != "manual_transfer" or len(member_ids) != 2
                or len(member_patches) != 2 or {_identifier(item["transaction_id"]) for item in member_patches} != member_ids):
                raise CashRecordPolicyError("field_not_editable", ())
            from ft.application.cash_manual_transfer_edit import prepare_transfer_members
            members, member_operations, relation_update = prepare_transfer_members(planner, row, member_patches)
            transfer_relations.append(relation_update)
            for member, extra in zip(members, member_operations):
                if member.id == row.id:
                    operation.update({key: value for key, value in extra.items() if key != "entry_edit"})
                else:
                    operations.append(extra)
                    current_records[str(member.id)] = member
        category_members = []
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
        def transform(snapshot, prepared):
            if merging:
                CashDraftMergePlanner.overlay(snapshot, prepared)
            if transfer_relations:
                from ft.application.cash_manual_transfer_edit import overlay_transfer
                overlay_transfer(snapshot, transfer_relations)
        result = self.build_operations(session=session, operations=operations, rows=rows,
            relation_choices=preview_input["relation_choices"], previous_plan=previous_plan, include_unchanged=True,
            current_records=current_records, source_scope_snapshot=(frozenset(int(key) for key in current_records), scopes),
            additional_seed_ids=additional_seeds, decision_group_ids=decision_groups,
            snapshot_transform=transform if merging or transfer_relations else None,
            current_relation_builder=relation_groups if current_relations is not None else None)
        from ft.domain.cash_record_policy import COMMON_CASH_FIELDS
        for group in result["relation_groups"]:
            if group["action"] == "remove" and group.get("split_field_diffs") and "inherit_common_fields" not in group:
                raise CashRecordPolicyError("inheritance_required", ())
        if len(operations) != expected_count:
            # 一次维护意图不能只保存整笔分类的一部分。
            raise CashRecordPolicyError("relation_endpoint_incompatible", ("record_type", "record_subtype"))
        for detail in result["item_details"].values():
            for member in detail["members"]:
                member["editable_fields"] = [] if len(detail["members"]) > 1 else [field for field in member["editable_fields"] if field != "account_name"]
                member["allowed_actions"] = ["edit_record"] if member["transaction_id"] not in {
                    op["transaction_id"] for op in operations if op["current"]["state"] == "draft"} else ["save_draft", "finalize_draft"]
                if merging and member["transaction_id"] == str(row.id) and row.state == "draft":
                    member["allowed_actions"] = ["save_draft"]
        result.update(can_commit=bool(operations), category_member_ids=category_members,
            manual_transfer_relations=transfer_relations,
            mutation_units=[[op["operation_ref"] for op in operations]])
        result["summary"]["relations_removed"] = result["summary"].get("relations_removed", 0) + len(result["type_effects"]["removed_relation_ids"]) + len(result["type_effects"]["removed_funding_ids"])
        resolved_count = sum(len(op.get("resolved_issue_ids", [])) for op in operations)
        if resolved_count:
            result["summary"]["resolve_issue"] = resolved_count
        independent = sum(any(choice["resolution"] == "create_independent" for choice in op.get("issue_resolutions", [])) for op in operations)
        if independent:
            result["summary"]["create_independent"] = independent
        from ft.application.cash_maintenance_effects import additional_effects
        from ft.adapters.relational.models import CashTransactionIssueModel
        from ft.application.cash_import_operations import _batches
        affected = {int(parent) for failure in result.get("relation_failures", []) for parent in failure.get("parent_ids", []) if 0 < int(parent) < 2 ** 63}
        baseline = []
        for batch in _batches(affected):
            baseline.extend({"transaction_id": str(issue.transaction_id), "issue_key": issue.issue_key, "code": issue.code}
                for issue in session.scalars(select(CashTransactionIssueModel).where(
                    CashTransactionIssueModel.workspace_id == self.workspace_id, CashTransactionIssueModel.transaction_id.in_(batch))))
        result["additional_effects"] = additional_effects(preview_input, result, baseline_issues=baseline)
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
