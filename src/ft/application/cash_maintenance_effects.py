"""当前维护计划中，本次明确请求以外的安全影响摘要。"""


def additional_effects(request, result, *, baseline_issues=()):
    effects = set()

    def add(kind, reference, field=None):
        effects.add((kind, str(reference), field or ""))

    choices = {choice["suggestion_id"]: choice for choice in request.get("relation_choices", [])}
    explicit_removed = set()
    for group in result.get("relation_groups", []):
        reference = group["suggestion_id"]
        choice = choices.get(reference)
        if group["action"] == "skip":
            add("choice_changed" if choice is not None else "relation_changed", reference)
        elif choice is not None:
            if group["action"] != choice["action"]:
                add("choice_changed", reference)
            elif choice["action"] == "remove":
                explicit_removed.update(str(edge["id"]) for edge in group.get("expected_record_edges", []) if edge.get("id") is not None)
        elif group.get("changed", group.get("origin") != "current") and group["action"] != "keep":
            add("relation_changed", reference)
    for reference in result.get("type_effects", {}).get("removed_relation_ids", []):
        if str(reference) not in explicit_removed:
            add("relation_changed", reference)
    for reference in result.get("type_effects", {}).get("removed_funding_ids", []):
        add("funding_relation_removed", reference)
    for choice in result.get("invalidated_choices", []):
        add("choice_changed", choice["reference"])
    for operation in result.get("operations", []):
        reference = operation["transaction_id"]
        for diff in operation.get("field_diffs", []):
            if diff.get("incoming") != diff.get("effective"):
                add("field_value_changed", reference, diff["field"])
        old = {(issue["code"], issue.get("field_path")) for issue in operation.get("expected_issues", [])}
        for issue in operation.get("issues", []):
            if (issue["code"], issue.get("field_path")) not in old:
                add("new_attention", reference, issue.get("field_path"))
    from ft.domain.cash_source_matching import _digest
    baseline = {(str(issue["transaction_id"]), issue["issue_key"], issue["code"]) for issue in baseline_issues}
    groups = {group["suggestion_id"]: group for group in result.get("relation_groups", [])}
    for failure in result.get("relation_failures", []):
        if failure.get("suggestion_id"):
            # 已由完整关系组表达；writer 的问题覆盖范围不等同于旧 failure.parent_ids。
            continue
        parents = sorted({int(parent) for parent in failure.get("parent_ids", [])})
        suggestion = failure.get("suggestion_id")
        reference = groups.get(suggestion, {}).get("commit_group_id", suggestion) if suggestion else _digest("discovery", parents)
        issue_key = "relation:" + _digest(reference)
        for parent in parents:
            if (str(parent), issue_key, failure["code"]) not in baseline:
                add("new_attention", parent)
    return [{"kind": kind, "reference": reference, **({"field": field} if field else {})}
        for kind, reference, field in sorted(effects)]
