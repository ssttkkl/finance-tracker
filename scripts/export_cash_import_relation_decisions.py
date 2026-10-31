"""Anonymous cases for the frozen relation decisions and final preview graph."""
from copy import deepcopy
from dataclasses import asdict, replace
from decimal import Decimal

from ft.adapters.relational.cash_entries import CashEntryRepository
from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.application.cash_import_operations import _wire
from ft.application.cash_import_plans import CashImportPlanError
from ft.domain.cash_record_policy import CashRecordPolicyError


def graph_state(snapshot):
    return {
        "facts": {key: _wire(asdict(value)) for key, value in snapshot.facts.items()},
        "edges": {str(key): _wire(asdict(value)) for key, value in snapshot.edges.items()},
        "entry_public": _wire(snapshot.entry_public),
        "projections": [{"projection_id": item.projection_id, "net_amount": _wire(item.net_amount),
            "economic_type": item.economic_type.value if item.economic_type else None,
            "member_ids": list(item.member_ids), "relation_ids": [edge.id for edge in item.relations],
            "compositions": list(item.compositions), "funding_relation_id": item.funding_relation_id,
            "state": item.state, "attention_count": item.attention_count}
            for item in snapshot.build().projections],
    }


def decision_cases(builder, snapshot, strong, weak, opened, session, workspace):
    identifier = strong[0]["suggestion_id"]
    weak_id = weak[0]["suggestion_id"]
    candidate = weak[0]["candidates"][0]["candidate_id"]
    current = deepcopy(strong)
    current[0].update(origin="current", changed=False, allowed_actions=["accept", "defer", "ignore", "remove"])
    current[0]["direct_confirmation"] = {key: deepcopy(current[0][key]) for key in ("edges", "evidence", "parent_ids", "member_refs")}
    disappeared = deepcopy(weak)
    disappeared[0]["candidates"] = []
    changed = deepcopy(strong)
    changed[0]["suggestion_id"] = "sg_changed"
    no_confirmation = deepcopy(current)
    no_confirmation[0].pop("direct_confirmation")
    stale_choice = {"suggestion_id": "sg_stale", "action": "accept"}
    stale = {"invalidated_relation_choices": {"sg_stale": {"choice": stale_choice,
        "member_refs": strong[0]["member_refs"]}}, "relation_choice_generations": {"sg_stale": 4}}
    inputs = [
        ("defaults", strong, [], None),
        ("accept_common", strong, [{"suggestion_id": identifier, "action": "accept", "common_fields": {"note": "合并备注"}}], {"relation_groups": strong}),
        ("select_candidate", weak, [{"suggestion_id": weak_id, "action": "accept", "candidate_id": candidate}], {"relation_groups": weak}),
        ("ignore", strong, [{"suggestion_id": identifier, "action": "ignore"}], {"relation_groups": strong}),
        ("unknown_reference", strong, [{"suggestion_id": "missing", "action": "accept"}], {"relation_groups": strong}),
        ("unknown_candidate", weak, [{"suggestion_id": weak_id, "action": "accept", "candidate_id": "missing"}], {"relation_groups": weak}),
        ("invalid_action", strong, [{"suggestion_id": identifier, "action": "remove"}], {"relation_groups": strong}),
        ("common_on_ignore", strong, [{"suggestion_id": identifier, "action": "ignore", "common_fields": {"note": "无效"}}], {"relation_groups": strong}),
        ("inherit_on_accept", strong, [{"suggestion_id": identifier, "action": "accept", "inherit_common_fields": False}], {"relation_groups": strong}),
        ("open_accept_without_candidate", opened, [{"suggestion_id": opened[0]["suggestion_id"], "action": "accept"}], {"relation_groups": opened}),
        ("current_confirmation", current, [{"suggestion_id": identifier, "action": "accept"}], {"relation_groups": current}),
        ("missing_current_confirmation", no_confirmation, [{"suggestion_id": identifier, "action": "accept"}], {"relation_groups": no_confirmation}),
        ("current_remove", current, [{"suggestion_id": identifier, "action": "remove", "inherit_common_fields": False}], {"relation_groups": current}),
        ("candidate_disappeared", disappeared, [{"suggestion_id": weak_id, "action": "accept", "candidate_id": candidate}], {"relation_groups": weak}),
        ("suggestion_changed", changed, [{"suggestion_id": identifier, "action": "accept"}], {"relation_groups": strong}),
        ("retained_stale", changed, [stale_choice], stale),
        ("modified_stale", changed, [{"suggestion_id": "sg_stale", "action": "ignore"}], stale),
        ("huge_generation", strong, [], {"relation_choice_generations": {identifier: 2**80}}),
    ]
    choices = []
    for name, groups, selected, previous in inputs:
        row = {"name": name, "groups": deepcopy(groups), "choices": deepcopy(selected), "previous": deepcopy(previous)}
        groups = deepcopy(groups)
        try:
            invalidated, sources, generations = builder._apply_choices(groups, selected, previous)
            row["expected"] = {"groups": _wire(groups), "invalidated": invalidated,
                "sources": _wire(sources), "generations": generations}
        except CashImportPlanError as error:
            row["error"] = error.code
        choices.append(row)

    default = deepcopy(strong)
    builder._apply_choices(default, [], None)
    applications = []

    def application(name, groups, *, entry_overrides=None, amount_overrides=None, restore_split=False):
        case = {"name": name, "snapshot_case": "normal", "groups": deepcopy(groups),
            "entry_overrides": deepcopy(entry_overrides or {}), "amount_overrides": amount_overrides or {},
            "restore_split": restore_split}
        current_snapshot, groups = deepcopy(snapshot), deepcopy(groups)
        current_snapshot.entry_public.update(deepcopy(entry_overrides or {}))
        for parent, amount in (amount_overrides or {}).items():
            amount = Decimal(amount)
            current_snapshot.values[parent]["amount"] = amount
            current_snapshot.facts[parent] = replace(current_snapshot.facts[parent], amount=amount)
            for part in current_snapshot.values[parent]["components"]:
                part["amount"] = amount
                current_snapshot.components[part["component_id"]] = replace(
                    current_snapshot.components[part["component_id"]], amount=amount)
        try:
            if restore_split:
                _restore_split(current_snapshot, groups, session, workspace)
            failures = builder._apply_groups(current_snapshot, groups)
            case["expected"] = {"groups": _wire(groups), "failures": _wire(failures), "state": graph_state(current_snapshot)}
        except CashRecordPolicyError as error:
            case["error"] = error.code
            case["error_fields"] = list(error.fields)
            case["state"] = graph_state(current_snapshot)
        applications.append(case)

    application("accept", default)
    for action in ("defer", "ignore"):
        groups = deepcopy(default); groups[0]["action"] = action
        application(action, groups)
    inactive = deepcopy(default); inactive[0]["edges"][0]["active"] = False
    application("inactive_edge", inactive)
    explicit = deepcopy(default); explicit[0]["common_fields"] = {"note": "明确合并备注"}
    application("explicit_common", explicit)
    joint = deepcopy(default)
    joint[0]["common_fields"] = {"note": "第一个决定"}
    extra = deepcopy(default[0])
    extra["suggestion_id"] = "sg_second"
    extra["common_fields"] = {"note": "第二个决定"}
    wechat_part = snapshot.values["7502"]["components"][0]["component_id"]
    extra["edges"][0].update(id=999983, primary_transaction_id="7507", primary_component_id="9508",
        secondary_transaction_id="7502", secondary_component_id=wechat_part)
    extra["parent_ids"] = ["7507", "7502"]
    extra["member_refs"] = [builder._ref(snapshot, "7507"), builder._ref(snapshot, "7502")]
    joint.append(extra)
    application("joint_decision_conflict", joint, amount_overrides={"7502": "-5.00"})
    conflict = {"7507": {"entry_id": "bank-entry", "manual_fields": ["note"], "values": {"note": "银行人工备注"}}}
    application("manual_field_conflict", default, entry_overrides=conflict)
    application("explicit_resolves_conflict", explicit, entry_overrides=conflict)
    broken = deepcopy(default); broken[0]["edges"][0]["applied_amount"] = Decimal("999")
    application("automatic_graph_failure", broken)
    manual = deepcopy(broken); manual[0]["origin"] = "manual"
    application("manual_graph_failure", manual)
    unchanged = deepcopy(default); unchanged[0].update(origin="current", changed=False)
    application("unchanged_current", unchanged)
    split = [{"suggestion_id": "sg_current_remove", "origin": "current", "changed": True,
        "action": "remove", "edges": [asdict(edge) for edge in snapshot.context.relations],
        "parent_ids": ["7501", "7503", "7504"]}]
    application("remove_restores_standalone", split, restore_split=True)
    inherited = deepcopy(split); inherited[0]["inherit_common_fields"] = True
    application("remove_inherits", inherited, restore_split=True)
    diffs = deepcopy(split); diffs[0]["split_field_diffs"] = [{"field": "note"}]
    application("remove_requires_choice", diffs, restore_split=True)
    return choices, applications


def _restore_split(snapshot, groups, session, workspace):
    for group in groups:
        if group.get("origin") == "current" and group["action"] == "remove" and not group.get("split_field_diffs"):
            group.setdefault("inherit_common_fields", False)
    selected = [group for group in groups if group["action"] == "remove" and group.get("inherit_common_fields") is False]
    if not selected:
        return
    recovery, _ = CashEntryRepository(session, workspace).for_transactions(snapshot.context.transaction_ids)
    for group in selected:
        old = {parent for identifier in group.get("old_parent_ids", group["parent_ids"])
            for parent in CurrentCashRelationRepository(session, workspace).confirmed_closure({int(identifier)})}
        for identifier in old:
            member = recovery.get(identifier)
            if member is not None and str(identifier) in snapshot.facts:
                snapshot.entry_public[str(identifier)]["values"] = deepcopy(member.standalone_values)
                snapshot.facts[str(identifier)] = replace(snapshot.facts[str(identifier)], **{
                    field: member.standalone_values[field] for field in ("note", "counterparty", "category_id")})
