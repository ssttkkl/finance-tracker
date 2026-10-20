from copy import deepcopy
from datetime import datetime, timezone
from decimal import Decimal

import pytest


def _record(**changes):
    return {
        "state": "posted", "amount": Decimal("-20"), "currency": "CNY",
        "occurred_at": datetime(2026, 8, 1, tzinfo=timezone.utc),
        "account_id": "41", "record_id": "source-1", "source_type": "wechat",
        "source_scope_key": "source-owner", "cash_granularity": "atomic",
        "components": [{"component_id": "501", "account_id": "41", "amount": "-20", "currency": "CNY", "ordinal": 0}],
        "counterparty": "商户", "counterparty_account": "1234",
        "counterparty_account_attrs": ["tail"], "note": "来源备注",
        "category_id": None, "record_type": "consumption", "record_subtype": "not_applicable",
        "source_payload": {"备注": "来源备注"}, **changes,
    }


@pytest.mark.parametrize("field,value", [
    ("amount", "-21"), ("currency", "USD"),
    ("occurred_at", "2026-08-02T00:00:00Z"), ("account_id", "42"),
    ("cash_granularity", "aggregate"),
    ("components", [{"component_id": "501", "account_id": "41", "amount": "-21", "currency": "CNY", "ordinal": 0}]),
])
def test_posted_financial_fields_cannot_change(field, value):
    from ft.domain.cash_record_policy import CashRecordPolicyError, prepare_cash_record_update
    current = _record()
    before = deepcopy(current)
    with pytest.raises(CashRecordPolicyError) as error:
        prepare_cash_record_update(current, {"counterparty": "新名称", field: value})
    assert error.value.code == "fixed_field_conflict"
    assert error.value.fields == (field,)
    assert current == before


def test_fixed_fields_accept_equal_values_without_rewriting_them():
    from ft.domain.cash_record_policy import prepare_cash_record_update
    change = prepare_cash_record_update(_record(), {
        "amount": "-20.000", "account_id": 41,
        "occurred_at": "2026-08-01T08:00:00+08:00", "currency": "CNY",
        "components": [{"component_id": 501, "account_id": 41, "amount": Decimal("-20.0"), "currency": "CNY", "ordinal": 0}],
        "counterparty": "新名称",
    })
    assert change.values == {"counterparty": "新名称"}
    assert not change.type_changed


@pytest.mark.parametrize("state", ["draft", "posted"])
@pytest.mark.parametrize("field", ["record_id", "source_type", "source_scope_key"])
def test_source_identity_is_fixed_even_for_drafts(state, field):
    from ft.domain.cash_record_policy import CashRecordPolicyError, prepare_cash_record_update
    with pytest.raises(CashRecordPolicyError, match="fixed_field_conflict"):
        prepare_cash_record_update(_record(state=state), {field: "another"})


@pytest.mark.parametrize("field", ["state", "row_revision", "workspace_id", "manual_overrides", "unexpected"])
def test_patch_cannot_change_system_fields(field):
    from ft.domain.cash_record_policy import CashRecordPolicyError, prepare_cash_record_update
    with pytest.raises(CashRecordPolicyError) as error:
        prepare_cash_record_update(_record(), {field: "posted"})
    assert error.value.code == "field_not_editable"


def test_account_and_attributes_are_directly_overwritable():
    from ft.domain.cash_record_policy import prepare_cash_record_update
    result = prepare_cash_record_update(_record(), {
        "counterparty_account": "a different unverified account",
        "counterparty_account_attrs": ["tail", "reconstructed"],
    })
    assert result.values["counterparty_account"] == "a different unverified account"
    assert result.values["counterparty_account_attrs"] == ["tail", "reconstructed"]
    assert not result.type_changed


def test_user_cleared_note_and_category_can_be_protected_while_source_updates():
    from ft.domain.cash_record_policy import prepare_cash_record_update
    result = prepare_cash_record_update(
        _record(note="", category_id="cat-user"),
        {"note": "新来源", "category_id": None, "source_payload": {"备注": "新来源"}, "counterparty": "新商户"},
        protected_fields={"note", "category_id"},
    )
    assert result.values == {"source_payload": {"备注": "新来源"}, "counterparty": "新商户"}
    assert result.protected_fields == ("category_id", "note")


def test_same_rules_allow_explicit_manual_change_of_protected_values():
    from ft.domain.cash_record_policy import prepare_cash_record_update
    assert prepare_cash_record_update(_record(note=""), {"note": "手动备注"}).values == {"note": "手动备注"}


def test_type_change_requires_legal_pair_and_signals_relation_revalidation():
    from ft.domain.cash_record_policy import CashRecordPolicyError, prepare_cash_record_update
    with pytest.raises(CashRecordPolicyError) as error:
        prepare_cash_record_update(_record(), {"record_type": "transfer_out"})
    assert error.value.code == "invalid_record_type"
    result = prepare_cash_record_update(_record(), {"record_type": "transfer_out", "record_subtype": "ordinary_transfer"})
    assert result.type_changed
    assert result.values == {"record_type": "transfer_out", "record_subtype": "ordinary_transfer"}


def test_draft_can_be_completed_without_implicit_promotion():
    from ft.domain.cash_record_policy import prepare_cash_record_update
    result = prepare_cash_record_update(_record(state="draft", amount=None, account_id=None), {"amount": "-20", "account_id": "41"})
    assert result.values == {"amount": Decimal("-20"), "account_id": "41"}
    assert "state" not in result.values


@pytest.mark.parametrize("bad", [0.1, True, "NaN", "1e-19"])
def test_draft_amount_still_requires_exact_representable_decimal(bad):
    from ft.domain.cash_record_policy import CashRecordPolicyError, prepare_cash_record_update
    with pytest.raises(CashRecordPolicyError) as error:
        prepare_cash_record_update(_record(state="draft", amount=None), {"amount": bad})
    assert error.value.code == "invalid_field"


def test_patch_and_source_payload_are_detached_from_caller():
    from ft.domain.cash_record_policy import prepare_cash_record_update
    incoming = {"source_payload": {"nested": ["source"]}}
    result = prepare_cash_record_update(_record(), incoming)
    incoming["source_payload"]["nested"].append("changed")
    assert result.values["source_payload"] == {"nested": ["source"]}


def test_note_uses_only_raw_source_comment_and_keeps_full_text():
    from ft.domain.cash_record_policy import source_note
    original = "来源原文" * 200
    assert source_note({"备注": original}) == original
    assert source_note({"商品说明": "商品原文"}) == "商品原文"
    assert source_note({"交易对方": "商户", "交易类型": "支付"}) == ""
    assert source_note({"备注": "", "描述": "来源描述"}) == "来源描述"
    assert source_note({"備註": "原文備註"}) == "原文備註"


@pytest.mark.parametrize("payload", [{"x": {1, 2}}, {"x": float("nan")}, {1: "value"}])
def test_source_payload_must_be_valid_json(payload):
    from ft.domain.cash_record_policy import CashRecordPolicyError, prepare_cash_record_update
    with pytest.raises(CashRecordPolicyError, match="invalid_field"):
        prepare_cash_record_update(_record(), {"source_payload": payload})


@pytest.mark.parametrize("patch", [
    {"account_id": []}, {"cash_granularity": {}}, {"currency": "Y"},
    {"components": [{"ordinal": -1}]}, {"components": [{"ordinal": True}]},
    {1: "bad", "x": "bad"},
])
def test_incomplete_draft_still_validates_supplied_structure(patch):
    from ft.domain.cash_record_policy import CashRecordPolicyError, prepare_cash_record_update
    with pytest.raises(CashRecordPolicyError, match="invalid_field"):
        prepare_cash_record_update(_record(state="draft"), patch)
