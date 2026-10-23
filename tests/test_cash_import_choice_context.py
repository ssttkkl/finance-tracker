"""重验证明是单份有界资料，不保留执行边或来源正文。"""
from copy import deepcopy
from types import SimpleNamespace
import json

import pytest

from ft.application.cash_import_choice_context import (
    CONTEXT_KEY, CashImportChoiceContextError, previous_choice_context,
    retained_choice_context, without_choice_context,
)


def _context():
    plan = {"plan_id": "old-not-executable", "operations": [
        {"occurrence_key": "one", "values": {"record_id": "import-v1:one", "amount": "123.45", "source_payload": "private-source"}},
        {"occurrence_key": "two", "transaction_id": "t2", "values": {"record_id": "posted-existing"}}],
        "relation_groups": [{"suggestion_id": "sg_one", "source_suggestion_id": "sg_root", "choice_generation": 1,
            "member_refs": [{"row_ref": "f:r1"}, {"transaction_id": "t2"}],
            "candidates": [{"candidate_id": "candidate_one", "display": "private-display", "edges": ["private-edge"]}],
            "edges": ["not-an-operation"], "evidence": "private-proof", "action": "accept"}],
        "invalidated_relation_choices": {"sg_stale": {"choice": {"suggestion_id": "sg_stale", "action": "accept", "candidate_id": None},
            "member_refs": [{"row_ref": "f:r1"}]}}, "relation_choice_generations": {"sg_root": 1}}
    return SimpleNamespace(plan=plan, context_data={"retry_other_data": {"keep": True}})


def test_only_revalidation_fields_survive_and_current_plan_is_not_retained():
    current = _context()
    original = deepcopy(current)
    data = retained_choice_context(current)
    assert current == original
    proof = data[CONTEXT_KEY]
    assert data["retry_other_data"] == current.context_data["retry_other_data"]
    assert proof["operations"] == [{"occurrence_key": "one", "values": {"record_id": "import-v1:one"}}]
    assert proof["relation_groups"][0]["candidates"] == [{"candidate_id": "candidate_one"}]
    assert all(private not in json.dumps(proof) for private in ("private", "amount", "edges", "plan_id", "posted-existing"))
    intermediate = SimpleNamespace(plan=None, context_data=data)
    assert previous_choice_context(intermediate) == proof
    assert retained_choice_context(intermediate) == data
    assert without_choice_context(intermediate) == current.context_data
    assert data[CONTEXT_KEY] == proof


def test_latest_plan_replaces_the_single_proof_instead_of_building_history():
    current = _context()
    prior = retained_choice_context(current)
    current.context_data = prior
    current.plan["relation_groups"] = []
    current.plan["operations"] = []
    current.plan["invalidated_relation_choices"] = {}
    current.plan["relation_choice_generations"] = {}
    latest = retained_choice_context(current)
    assert len([key for key in latest if key == CONTEXT_KEY]) == 1
    assert latest[CONTEXT_KEY]["relation_groups"] == []
    assert "sg_one" not in json.dumps(latest)
    assert prior[CONTEXT_KEY]["relation_groups"]


@pytest.mark.parametrize("change", [
    lambda proof: proof.update(version=True),
    lambda proof: proof.update(version=2),
    lambda proof: proof.update(edges=["private"]),
    lambda proof: proof["operations"][0]["values"].update(amount="1"),
    lambda proof: proof["relation_groups"][0].update(member_refs=[{"row_ref": "a", "transaction_id": "b"}]),
    lambda proof: proof["relation_groups"][0]["candidates"][0].update(candidate_id=None),
    lambda proof: proof["invalidated_relation_choices"]["sg_stale"]["choice"].update(action=[]),
    lambda proof: proof["invalidated_relation_choices"]["sg_stale"]["choice"].update(suggestion_id="foreign"),
    lambda proof: proof["relation_groups"][0].update(choice_generation=True),
    lambda proof: proof["relation_groups"][0].update(choice_generation=-1),
    lambda proof: proof["relation_groups"][0].update(choice_generation=0),
    lambda proof: proof["relation_choice_generations"].update(sg_root=True),
    lambda proof: proof["relation_choice_generations"].update(sg_root=-1),
])
def test_malformed_private_proof_fails_closed_without_repair(change):
    data = retained_choice_context(_context())
    change(data[CONTEXT_KEY])
    with pytest.raises(CashImportChoiceContextError, match="^choice_context_invalid$"):
        previous_choice_context(SimpleNamespace(context_data=data))


@pytest.mark.parametrize("action", ["accept", "defer", "ignore"])
def test_explicit_exemption_does_not_extend_to_adjacent_unselected_groups(action):
    from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
    def group(identifier, members):
        return {"suggestion_id": identifier, "member_refs": [{"transaction_id": member} for member in members],
            "candidates": [], "allowed_actions": ["accept", "defer", "ignore"], "action": "accept",
            "edges": [{"secondary_transaction_id": members[-1]}]}
    groups = [group("new-a", ["t1", "t2"]), group("new-b", ["t1", "t3"]), group("unrelated", ["t4", "t5"])]
    stale = {"suggestion_id": "old", "action": "accept"}
    previous = {"relation_groups": deepcopy(groups), "invalidated_relation_choices": {
        "old": {"choice": stale, "member_refs": [{"transaction_id": "t1"}]}}}
    CashImportBusinessPlanBuilder._apply_choices(groups, [stale, {"suggestion_id": "new-a", "action": action}], previous)
    assert [group["action"] for group in groups] == [action, "defer", "accept"]
