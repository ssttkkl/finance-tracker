"""Plan-authorized public edits target the complete anticipated entry."""
from copy import deepcopy
from dataclasses import asdict

from ft.application.cash_import_plans import CashImportPlanError
from ft.domain.cash_record_policy import COMMON_CASH_FIELDS, _normalize
from ft.domain.cash_source_matching import _digest


def entry_target_index(snapshot, groups):
    from collections import defaultdict
    old_edges, dependencies = defaultdict(set), defaultdict(dict)
    for edge in snapshot.context.relations:
        for parent in (edge.primary_transaction_id, edge.secondary_transaction_id):
            if parent is not None:
                old_edges[parent].add(str(edge.id))
    for order, group in enumerate(groups):
        if group["action"] in {"accept", "defer", "ignore"}:
            for parent in group["parent_ids"]:
                dependencies[parent][group["suggestion_id"]] = (order, {"suggestion_id": group["suggestion_id"], "action": group["action"]})
    return {"rows": {str(item[0]): (order, item) for order, item in enumerate(snapshot.context.transaction_revisions)},
        "entries": {str(item[0]): (order, item) for order, item in enumerate(snapshot.context.entry_versions)},
        "relations": {str(item[0]): (order, item) for order, item in enumerate(snapshot.context.relation_revisions)},
        "old_edges": old_edges, "dependencies": dependencies}


def entry_target(snapshot, parents, index):
    parents = sorted(parents)
    old_edges = {key for parent in parents for key in index["old_edges"][parent]}
    dependencies = {key: value for parent in parents for key, value in index["dependencies"][parent].items()}
    def selected(kind, keys):
        return [value for _order, value in sorted(index[kind][key] for key in keys if key in index[kind])]
    target = {
        "parent_ids": parents,
        "members": [{"row_refs": list(snapshot.parent_ops[parent]["row_refs"])}
            if parent in snapshot.parent_ops else {"transaction_id": parent} for parent in parents],
        "rows": selected("rows", parents),
        "entries": selected("entries", parents),
        "relations": selected("relations", old_edges),
        "relation_dependencies": [value for _order, value in sorted(dependencies.values())],
        "final_edges": [asdict(snapshot.edges[key]) for key in sorted(
            {key for parent in parents for key in snapshot.incident[parent]})],
    }
    return "ie_" + _digest("cash-import-entry-v1", target), target


def apply_entry_choices(result, choices, previous, session, workspace_id):
    targets = result.get("entry_targets", {})
    previous_targets = (previous or {}).get("entry_targets", {})
    edits = []
    items_by_entry = {result["item_details"][item["preview_item_id"]]["entry"]["edit_ref"]:
        (item, result["item_details"][item["preview_item_id"]]) for item in result["items"]}
    for choice in choices:
        reference = choice["edit_ref"]
        target = targets.get(reference)
        if target is None or _digest(target) != _digest(previous_targets.get(reference)):
            raise CashImportPlanError("plan_changed")
        try:
            patch = {field: _normalize(field, value) for field, value in choice["patch"].items()}
        except (ValueError, TypeError, ArithmeticError):
            raise CashImportPlanError("plan_input_invalid") from None
        if patch.keys() - COMMON_CASH_FIELDS:
            raise CashImportPlanError("plan_input_invalid")
        if patch.get("category_id") is not None:
            from sqlalchemy import select
            from ft.adapters.relational.models import CashCategoryModel
            if session.scalar(select(CashCategoryModel.id).where(CashCategoryModel.workspace_id == workspace_id,
                CashCategoryModel.id == patch["category_id"])) is None:
                raise CashImportPlanError("category_unavailable")
        item, detail = items_by_entry[reference]
        detail["entry"]["fields"].update(deepcopy(patch))
        item["display"].update({field: deepcopy(value) for field, value in patch.items() if field in item["display"]})
        if patch:
            edits.append({"edit_ref": reference, "target": target, "patch": patch})
    result["entry_edits"] = edits


def save_entry_choices(session, workspace_id, edits, identity, relation_results):
    from sqlalchemy import select
    from ft.adapters.relational.cash_entries import CashEntryRepository
    from ft.adapters.relational.models import CashEntryMemberModel
    from ft.application.cash_projections import CashProjectionService
    repository = CashEntryRepository(session, workspace_id)
    affected = set()
    outcomes = {item["item_ref"]: item["outcome"] for item in relation_results}
    for edit in edits:
        target = edit["target"]
        for dependency in target["relation_dependencies"]:
            expected = {"accept": "confirmed", "defer": "deferred", "ignore": "ignored"}[dependency["action"]]
            if outcomes.get(dependency["suggestion_id"]) not in {expected, "duplicate"}:
                raise CashImportPlanError("plan_changed")
        parents = target["parent_ids"]
        identifiers = {int(identity.get(parent, parent)) for parent in parents}
        if any(identifier >= 2 ** 63 for identifier in identifiers):
            raise CashImportPlanError("plan_changed")
        members, entries = repository.for_transactions(identifiers)
        if set(members) != identifiers or len(entries) != 1:
            raise CashImportPlanError("plan_changed")
        entry = next(iter(entries.values()))
        complete = set(session.scalars(select(CashEntryMemberModel.transaction_id).where(
            CashEntryMemberModel.workspace_id == workspace_id, CashEntryMemberModel.entry_id == entry.id)))
        if complete != identifiers:
            raise CashImportPlanError("plan_changed")
        repository.patch(entry.root_transaction_id, edit["patch"], expected_revision=entry.revision)
        affected.update(identifiers)
    if affected:
        CashProjectionService.maintain_in_session(session, workspace_id, affected)
    return len(edits)
