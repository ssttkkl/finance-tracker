from copy import deepcopy


def _effects(request=None, **changes):
    from ft.application.cash_maintenance_effects import additional_effects
    result = {"operations": [], "relation_groups": [], "relation_failures": [], "type_effects": {}, "invalidated_choices": []}
    baseline = changes.pop("baseline_issues", [])
    result.update(changes)
    return additional_effects(request or {"intent": "edit_entry", "patch": {"note": ""}, "relation_choices": []}, result, baseline_issues=baseline)


def test_common_clear_and_existing_attention_do_not_request_another_confirmation():
    assert _effects(operations=[{"transaction_id": "11", "field_diffs": [{"field": "note", "incoming": "", "effective": ""}],
        "issues": [{"code": "account_missing", "field_path": "account_id"}],
        "expected_issues": [{"code": "account_missing", "field_path": "account_id"}]}],
        relation_groups=[{"suggestion_id": "sg_keep", "origin": "current", "changed": False, "action": "accept"}]) == []


def test_effective_value_deviating_from_user_choice_is_reported_without_values():
    effects = _effects(operations=[{"transaction_id": "11", "field_diffs": [{"field": "note", "incoming": "private request", "effective": "other private"}]}])
    assert effects == [{"kind": "field_value_changed", "reference": "11", "field": "note"}]
    assert "private" not in repr(effects)


def test_new_relation_and_new_attention_are_not_hidden_by_empty_first_page():
    effects = _effects(items=[], pages={"items": "next"}, relation_groups=[{"suggestion_id": "sg_new", "action": "accept", "changed": True}],
        operations=[{"transaction_id": "11", "issues": [{"code": "amount_unreadable", "field_path": "amount"}]}])
    assert {item["kind"] for item in effects} == {"relation_changed", "new_attention"}


def test_explicit_group_removal_is_not_confirmed_twice_but_unrequested_funding_removal_is():
    request = {"intent": "maintain_relations", "relation_choices": [{"suggestion_id": "sg_one", "action": "remove"}]}
    group = {"suggestion_id": "sg_one", "action": "remove", "changed": True, "expected_record_edges": [{"id": 31}]}
    effects = _effects(request, relation_groups=[group], type_effects={"removed_relation_ids": [31], "removed_funding_ids": [45]})
    assert effects == [{"kind": "funding_relation_removed", "reference": "45"}]


def test_unrequested_relation_removal_and_invalidated_choice_are_reported():
    effects = _effects(type_effects={"removed_relation_ids": [31]}, invalidated_choices=[{"kind": "relation", "reference": "sg_old", "code": "relation_context_changed"}])
    assert {item["kind"] for item in effects} == {"relation_changed", "choice_changed"}


def test_explicit_accept_that_becomes_deferred_is_an_additional_effect():
    assert _effects({"relation_choices": [{"suggestion_id": "sg_one", "action": "accept"}]},
        relation_groups=[{"suggestion_id": "sg_one", "action": "defer", "changed": True}]) == [{"kind": "choice_changed", "reference": "sg_one"}]


def test_transfer_derived_fields_and_explicit_relation_decisions_are_not_extra():
    request = {"intent": "edit_entry", "member_patches": [{"transaction_id": "11", "patch": {"amount": "10.00"}}],
        "relation_choices": [{"suggestion_id": "sg_one", "action": "accept"}]}
    assert _effects(request, operations=[{"transaction_id": "11", "field_diffs": [{"field": "components", "incoming": [], "effective": []}]}],
        relation_groups=[{"suggestion_id": "sg_one", "action": "accept", "changed": True}]) == []


def test_existing_discovery_issue_on_collateral_member_is_not_new_but_another_failure_is():
    from ft.domain.cash_source_matching import _digest
    parents = [11, 12]
    issue = {"transaction_id": "12", "code": "relation_discovery_failed", "issue_key": "relation:" + _digest(_digest("discovery", parents))}
    failure = {"code": "relation_discovery_failed", "parent_ids": ["11", "12"]}
    # 两个原成员均有同一问题，其中12没有直接维护操作。
    baseline = [dict(issue, transaction_id="11"), issue]
    assert _effects(relation_failures=[failure], baseline_issues=baseline) == []
    changed = dict(failure, parent_ids=["12", "13"])
    assert _effects(relation_failures=[changed], baseline_issues=baseline)


def test_skipped_current_group_has_one_whole_group_effect_instead_of_inaccurate_parent_issue_copies():
    group = {"suggestion_id": "sg_one", "origin": "current", "changed": False, "action": "skip"}
    failure = {"suggestion_id": "sg_one", "code": "some_failure", "parent_ids": ["11"]}
    assert _effects(relation_groups=[group], relation_failures=[failure]) == [{"kind": "relation_changed", "reference": "sg_one"}]
