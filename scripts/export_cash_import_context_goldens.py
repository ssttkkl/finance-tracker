"""Export upload display-name and selection-invalidation results from the Python implementation."""
from __future__ import annotations
import argparse
from copy import deepcopy
from dataclasses import dataclass
import json
from pathlib import Path

from ft.application.cash_import_context import CashImportContextService as Service, _display_filename
from types import SimpleNamespace

from ft.application.cash_import_choice_context import (
    CONTEXT_KEY, CashImportChoiceContextError, _from_plan, _validated, previous_choice_context,
    retained_choice_context,
)


@dataclass(frozen=True)
class _Current:
    preview_input: dict
    revision: int


def _filename_cases():
    cases = [
        ("synthetic.csv", None),
        ("dir/sub/report.xlsx", None),
        ("dir\\sub\\report.xlsx", None),
        ("statement", None),
        ("statement.", None),
        ("bill.weird", None),
        ("账单.csv", "账单"),
        ("工行流水密码（fixture-secret）.pdf", None),
        ("工行流水（密码：fixture-secret）.pdf", None),
        ("工行（password: fixture-secret）.pdf", None),
        ("工行（pwd=fixture-secret）.pdf", None),
        ("account 6222 0212 3456 7890.csv", None),
        ("account 6222-0212-3456-7890.pdf", None),
        ("   .pdf", None),
        ("（）【】.csv", None),
        ("账" * 250 + "fixture-secret.pdf", "fixture-secret"),
        ("emoji😀" * 100 + ".pdf", None),
        ("/", None),
        ("file.PDF", None),
        ("订单（密码 ）.xlsx", None),
    ]
    return [{"filename": name, "password": password, "expected": _display_filename(name, password)}
            for name, password in cases]


def _invalidation_cases():
    cases = [
        ({"entry_choices": [{"x": 1}], "row_choices": [{"row_ref": "f_old:r8", "action": "apply"},
            {"row_ref": "f_keep:r8", "action": "defer"}], "previous_plan_id": "previous",
            "expected_session_revision": 3, "other": {"nested": ["keep"]}}, 3, ("f_old",)),
        ({"row_choices": "not-a-list", "previous_plan_id": None}, 1, ()),
        ({"row_choices": [{"row_ref": 5}, {"no_ref": True}], "keep": 1}, 2, ("f_old",)),
        ({}, 1, ("f_old",)),
        ({"entry_choices": {}, "expected_session_revision": 9}, 9, ()),
    ]
    return [{"preview_input": item, "revision": revision, "obsolete_refs": list(refs),
             "expected": Service._invalidate_input(_Current(deepcopy(item), revision), refs)}
            for item, revision, refs in cases]


def _member(ref):
    return {"row_ref": ref}


def _candidate(identifier):
    return {"candidate_id": identifier}


def _group(identifier="sg1", source=None, generation=0, members=None, candidates=None):
    return {"suggestion_id": identifier, "source_suggestion_id": source if source is not None else identifier,
            "choice_generation": generation, "member_refs": [_member("f_1:r1")] if members is None else members,
            "candidates": [_candidate("c1")] if candidates is None else candidates}


def _choice_context(operations=None, groups=None, stale=None, generations=None, version=1):
    return {"version": version,
            "operations": [{"occurrence_key": "f_1:r1", "values": {"record_id": "t1"}}] if operations is None else operations,
            "relation_groups": [_group()] if groups is None else groups,
            "invalidated_relation_choices": {} if stale is None else stale,
            "relation_choice_generations": {} if generations is None else generations}


def _choice_cases():
    valid_stale = {"sg2": {"choice": {"suggestion_id": "sg2", "action": "accept", "candidate_id": "c9",
                                      "common_fields": {"note": "x"}}, "member_refs": [_member("f_1:r3")]}}
    invalid = [
        _choice_context(version=2), _choice_context(version=True), {**_choice_context(), "extra": 1},
        _choice_context(operations="not-a-list"),
        _choice_context(operations=[{"occurrence_key": "f:r1"}]),
        _choice_context(groups=[_group(members=[])]),
        _choice_context(groups=[_group(), _group()]),
        _choice_context(groups=[_group(candidates=[{"candidate_id": "c1", "extra": 1}])]),
        _choice_context(groups=[_group(generation=1)]),
        _choice_context(stale={"sg2": {"choice": {"suggestion_id": "sg2", "action": "defer",
                                                  "common_fields": {"note": "x"}}, "member_refs": [_member("f:r1")]}}),
        _choice_context(stale={"sg2": {"choice": {"suggestion_id": "sg3", "action": "defer"},
                                       "member_refs": [_member("f:r1")]}}),
        _choice_context(stale={"sg2": {"choice": {"suggestion_id": "sg2", "action": "accept",
                                                  "common_fields": {"amount": "-1.00"}}, "member_refs": [_member("f:r1")]}}),
        _choice_context(generations={"sg1": -1}),
    ]
    plan = {"operations": [
        {"occurrence_key": "f_1:r1", "values": {"record_id": "t1"}},
        {"occurrence_key": "f_2:r2", "transaction_id": "t2", "values": {"record_id": "t3"}}],
        "relation_groups": [{"suggestion_id": "sg1", "member_refs": [_member("f_1:r1")],
                             "candidates": [{"candidate_id": "c1", "amount": "-1.00"}]}],
        "invalidated_relation_choices": {"sg2": {"choice": {"suggestion_id": "sg2", "action": "defer"},
                                                 "member_refs": [_member("f_1:r3")]}},
        "relation_choice_generations": {"sg1": 0}}
    class Current:
        def __init__(self, context_data, plan_value=None):
            self.context_data = context_data
            self.plan = plan_value
    valid = _choice_context(stale=valid_stale)
    cases = [{"kind": "validated", "input": valid, "expected": _validated(valid)}]
    for value in invalid:
        try:
            _validated(value)
        except CashImportChoiceContextError:
            cases.append({"kind": "validated", "input": value, "ok": False})
        else:
            raise AssertionError("case unexpectedly valid")
    cases.append({"kind": "from_plan", "input": plan, "expected": _from_plan(plan)})
    cases.append({"kind": "retained", "context": {**valid, "other": "keep"}, "plan": None,
                  "expected": retained_choice_context(Current({**valid, "other": "keep"}))})
    cases.append({"kind": "retained", "context": _choice_context(version=2), "plan": plan,
                  "expected": retained_choice_context(Current(_choice_context(version=2), plan))})
    rich_plan = {"plan_id": "old-not-executable", "operations": [
        {"occurrence_key": "one", "values": {"record_id": "import-v1:one", "amount": "123.45", "source_payload": "private-source"}},
        {"occurrence_key": "two", "transaction_id": "t2", "values": {"record_id": "posted-existing"}}],
        "relation_groups": [{"suggestion_id": "sg_one", "source_suggestion_id": "sg_root", "choice_generation": 1,
            "member_refs": [{"row_ref": "f:r1"}, {"transaction_id": "t2"}],
            "candidates": [{"candidate_id": "candidate_one", "display": "private-display", "edges": ["private-edge"]}],
            "edges": ["not-an-operation"], "evidence": "private-proof", "action": "accept"}],
        "invalidated_relation_choices": {"sg_stale": {"choice": {"suggestion_id": "sg_stale", "action": "accept",
                                                                 "candidate_id": None}, "member_refs": [{"row_ref": "f:r1"}]}},
        "relation_choice_generations": {"sg_root": 1}}
    rich_context = {"retry_other_data": {"keep": True}}
    rich_proof = retained_choice_context(SimpleNamespace(context_data={"retry_other_data": {"keep": True}}, plan=rich_plan))
    cases.append({"kind": "retained", "context": rich_context, "plan": deepcopy(rich_plan), "expected": rich_proof})
    cases.append({"kind": "previous", "context": rich_proof,
                  "expected": previous_choice_context(SimpleNamespace(context_data=rich_proof))})
    def mutate(change):
        proof = deepcopy(rich_proof)
        change(proof[CONTEXT_KEY])
        return proof
    mutations = [
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
    ]
    for mutation in mutations:
        cases.append({"kind": "previous_invalid", "context": mutate(mutation)})
    common = {"note": "", "counterparty": "共同对方", "category_id": None}
    common_plan = deepcopy(rich_plan)
    common_plan["invalidated_relation_choices"]["sg_stale"]["choice"]["common_fields"] = common
    common_proof = retained_choice_context(SimpleNamespace(context_data={}, plan=common_plan))
    for _ in range(3):
        assert previous_choice_context(SimpleNamespace(context_data=common_proof)) == common_proof[CONTEXT_KEY]
        common_proof = retained_choice_context(SimpleNamespace(context_data=common_proof, plan=None))
    cases.append({"kind": "retained", "context": {}, "plan": common_plan, "expected": common_proof})
    for patch in ({"amount": "-1"}, {"note": 1}, {"counterparty_account_attrs": [1]}):
        broken = deepcopy(rich_plan)
        broken["invalidated_relation_choices"]["sg_stale"]["choice"]["common_fields"] = patch
        try:
            retained_choice_context(SimpleNamespace(context_data={}, plan=broken))
        except CashImportChoiceContextError:
            cases.append({"kind": "retained_invalid", "context": {}, "plan": broken})
        else:
            raise AssertionError(f"common fields unexpectedly valid: {patch}")
    return cases


def export():
    return {"filenames": _filename_cases(), "invalidations": _invalidation_cases(), "choices": _choice_cases()}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    content = json.dumps(export(), ensure_ascii=False, indent=2) + '\n'
    if args.check:
        if args.output.read_text() != content:
            raise SystemExit('import context golden mismatch')
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content)
