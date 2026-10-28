"""来源事项决定是严格有界数组，不接受平铺或客户端执行清单。"""
import pytest

from ft.application.cash_mutation_plans import _input


def _value(**changes):
    return {"intent": "resolve_issue", "transaction_id": "1", "expected_revision": 1,
        "issue_resolutions": [{"issue_id": "2", "expected_issue_revision": 1, "resolution": "keep_existing"}], **changes}


@pytest.mark.parametrize("intent", ["edit_record", "resolve_issue", "finalize_draft"])
def test_source_choices_can_share_a_record_intent(intent):
    data, _ = _input(_value(intent=intent))
    assert data["issue_resolutions"] == [{"issue_id": "2", "expected_issue_revision": 1, "resolution": "keep_existing"}]


@pytest.mark.parametrize("changes", [{"issue_resolutions": None}, {"issue_resolutions": {}}, {"issue_resolutions": []},
    {"issue_resolutions": [True]}, {"issue_resolutions": [{"issue_id": "2", "expected_issue_revision": True, "resolution": "keep_existing"}]},
    {"issue_resolutions": [{"issue_id": "2", "expected_issue_revision": 1.0, "resolution": "keep_existing"}]},
    {"issue_resolutions": [{"issue_id": True, "expected_issue_revision": 1, "resolution": "keep_existing"}]},
    {"issue_resolutions": [{"issue_id": "2", "expected_issue_revision": 0, "resolution": "keep_existing"}]},
    {"issue_resolutions": [{"issue_id": "2", "expected_issue_revision": 2 ** 63, "resolution": "keep_existing"}]},
    {"issue_resolutions": [{"issue_id": "2", "expected_issue_revision": 1, "resolution": "merge_into"}]},
    {"issue_resolutions": [{"issue_id": "2", "expected_issue_revision": 1, "resolution": []}]},
    {"issue_resolutions": [{"issue_id": "2", "expected_issue_revision": 1, "resolution": "keep_existing", "target_transaction_id": None}]},
    {"issue_resolutions": [{"issue_id": "2", "expected_issue_revision": 1, "resolution": "keep_existing", "pending_fields": {}}]},
    {"issue_resolutions": [{"issue_id": "2", "expected_issue_revision": 1, "resolution": "keep_existing"}] * 101},
    {"intent": "maintain_relations"}, {"issue_id": "2", "expected_issue_revision": 1, "resolution": "keep_existing"}])
def test_source_choices_reject_invalid_shapes_before_database_access(changes):
    with pytest.raises(ValueError):
        _input(_value(**changes))


def test_normalized_duplicate_issue_ids_are_not_ordered_decisions():
    with pytest.raises(ValueError):
        _input(_value(issue_resolutions=[{"issue_id": identifier, "expected_issue_revision": 1, "resolution": "keep_existing"}
            for identifier in ("2", "02")]))


@pytest.mark.parametrize("intent,independent", [("finalize_draft", False), ("resolve_issue", True)])
def test_merge_is_a_terminal_identity_choice(intent, independent):
    choices = [{"issue_id": "2", "expected_issue_revision": 1, "resolution": "merge_into",
        "target_transaction_id": "3", "expected_target_revision": 1}]
    if independent:
        choices.append({"issue_id": "4", "expected_issue_revision": 1, "resolution": "create_independent"})
    with pytest.raises(ValueError):
        _input(_value(intent=intent, issue_resolutions=choices))
