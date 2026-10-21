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


def test_reimport_of_equal_draft_financial_values_is_not_an_update():
    from ft.domain.cash_record_policy import prepare_cash_record_update
    current = _record(state="draft", amount="-20.00", occurred_at="2026-08-01T00:00:00Z")
    assert prepare_cash_record_update(current, {"amount": "-20", "occurred_at": "2026-08-01T08:00:00+08:00"}).values == {}


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


def test_ccb_description_uses_full_raw_location_remark_before_classification_summary():
    from ft.domain.cash_record_policy import source_note
    original = "  原始交易地点／附言\n" + "原文" * 120 + "  "
    assert source_note({"摘要": "消费", "交易地点/附言": original}) == original
    assert source_note({"备注": "其他原备注", "交易地点/附言": original}) == "其他原备注"
    assert source_note({"摘要": "转账", "交易地点/附言": "  "}) == "转账"


@pytest.mark.parametrize("total,amounts", [("-20", ["-30", "10"]), ("20", ["30", "-10"]), ("0", ["-10", "10"])])
def test_posting_rejects_offsetting_components_even_when_the_sum_balances(total, amounts):
    from ft.domain.cash_record_policy import CashRecordPolicyError, validate_cash_record_for_posting
    record = _record(state="draft", amount=total, account_id=None, cash_granularity="aggregate", components=[
        {"ordinal": index, "account_id": str(41 + index), "amount": amount, "currency": "CNY"}
        for index, amount in enumerate(amounts)
    ])
    with pytest.raises(CashRecordPolicyError, match="component_direction_mismatch"):
        validate_cash_record_for_posting(record)
    assert record["state"] == "draft"


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


def test_component_ordinal_is_portable_to_both_database_integer_columns():
    from ft.domain.cash_record_policy import CashRecordPolicyError, prepare_cash_record_update
    current = _record(state="draft")
    part = current["components"][0]
    with pytest.raises(CashRecordPolicyError, match="invalid_field"):
        prepare_cash_record_update(current, {"components": [{**part, "ordinal": 2 ** 31}]})
    accepted = prepare_cash_record_update(current, {"components": [{**part, "ordinal": 2 ** 31 - 1}]})
    assert accepted.values["components"][0]["ordinal"] == 2 ** 31 - 1


def test_posting_requires_complete_parent_and_balanced_components():
    from ft.domain.cash_record_policy import CashRecordPolicyError, validate_cash_record_for_posting
    validate_cash_record_for_posting(_record())
    for missing in ("amount", "occurred_at", "currency", "record_type", "record_subtype", "account_id"):
        with pytest.raises(CashRecordPolicyError, match="incomplete_record"):
            validate_cash_record_for_posting(_record(**{missing: None}))
    combined = _record(account_id=None, cash_granularity="aggregate", amount="-100", components=[
        {"account_id": "41", "amount": "-30", "currency": "CNY", "ordinal": 0},
        {"account_id": "42", "amount": "-70", "currency": "CNY", "ordinal": 1},
    ])
    validate_cash_record_for_posting(combined)
    combined["components"][1]["amount"] = "-69"
    with pytest.raises(CashRecordPolicyError, match="component_total_mismatch"):
        validate_cash_record_for_posting(combined)


@pytest.mark.parametrize("change", [
    {"components": []},
    {"components": [{"account_id": None, "amount": "-20", "currency": "CNY", "ordinal": 0}]},
    {"components": [{"account_id": "41", "amount": None, "currency": "CNY", "ordinal": 0}]},
    {"components": [{"account_id": "41", "amount": "-20", "currency": "USD", "ordinal": 0}]},
    {"components": [{"account_id": "42", "amount": "-20", "currency": "CNY", "ordinal": 0}]},
])
def test_posting_rejects_missing_or_inconsistent_component_fields(change):
    from ft.domain.cash_record_policy import CashRecordPolicyError, validate_cash_record_for_posting
    with pytest.raises(CashRecordPolicyError):
        validate_cash_record_for_posting(_record(**change))


def test_validation_does_not_implicitly_promote_draft():
    from ft.domain.cash_record_policy import validate_cash_record_for_posting
    record = _record(state="draft")
    validate_cash_record_for_posting(record)
    assert record["state"] == "draft"


def test_component_total_uses_full_supported_decimal_precision():
    from ft.domain.cash_record_policy import validate_cash_record_for_posting
    validate_cash_record_for_posting(_record(
        account_id=None, cash_granularity="aggregate", amount="-12345678901234567890.123456789012345678",
        components=[
            {"account_id": "41", "amount": "-10000000000000000000", "currency": "CNY", "ordinal": 0},
            {"account_id": "42", "amount": "-2345678901234567890.123456789012345678", "currency": "CNY", "ordinal": 1},
        ],
    ))


@pytest.mark.parametrize("ordinals", [[None, 1], [0, 0]])
def test_posting_requires_unique_present_component_ordinals(ordinals):
    from ft.domain.cash_record_policy import CashRecordPolicyError, validate_cash_record_for_posting
    with pytest.raises(CashRecordPolicyError):
        validate_cash_record_for_posting(_record(account_id=None, cash_granularity="aggregate", amount="-20", components=[
            {"account_id": "41", "amount": "-10", "currency": "CNY", **({"ordinal": ordinals[0]} if ordinals[0] is not None else {})},
            {"account_id": "42", "amount": "-10", "currency": "CNY", "ordinal": ordinals[1]},
        ]))


@pytest.mark.parametrize("account_id", ["41", []])
def test_posting_aggregate_requires_empty_parent_account(account_id):
    from ft.domain.cash_record_policy import CashRecordPolicyError, validate_cash_record_for_posting
    with pytest.raises(CashRecordPolicyError):
        validate_cash_record_for_posting(_record(account_id=account_id, cash_granularity="aggregate", amount="-20", components=[
            {"account_id": "41", "amount": "-10", "currency": "CNY", "ordinal": 0},
            {"account_id": "42", "amount": "-10", "currency": "CNY", "ordinal": 1},
        ]))
