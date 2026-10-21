"""微信来源是用户确认的稳定范围，不是昵称或记账账户。"""
from dataclasses import replace
from hashlib import sha256

import pytest


def _file(*, label=None, nickname="昵称甲", owner=None, digest=None):
    from ft.adapters.cash_statement_rows import ParsedCashDocument, ParsedCashRow
    from ft.application.parsed_import_staging import ParsedImportFile
    from ft.domain.cash_source_matching import SourceCoverage
    document = ParsedCashDocument("wechat", "sheet:0", "platform-rows-v1+native-v1", (ParsedCashRow(18, {
        "amount": "-20.00", "currency": "CNY", "occurred_at": "2026-08-15T02:00:00+00:00",
        "counterparty": "商户", "note": "原始商品", "record_type": "consumption", "record_subtype": "not_applicable",
        "source_payload": {"交易单号": "000123", "备注": "原始商品"}}, "零钱", "000123"),),
        owner, nickname, SourceCoverage(), 1, 1)
    return ParsedImportFile("file_1", "client_1", "synthetic.xlsx", digest or sha256(nickname.encode()).hexdigest(), 100,
        "wechat", "parsed", document, source_scope_label=label)


def _rows(file, **kwargs):
    from ft.application.cash_import_source_rows import source_rows
    return source_rows(file, **kwargs)


def test_default_scope_is_stable_across_nickname_raw_owner_and_internal_mapping():
    from ft.domain.cash_source_scope import WECHAT_DEFAULT_SCOPE
    first, = _rows(_file()).values()
    second, = _rows(_file(nickname="昵称乙", owner="actual-account")).values()
    mapped, = _rows(_file(), resolved_fields={18: {"account_id": "other-account"}}).values()
    assert first.source_account_key == second.source_account_key == mapped.source_account_key == WECHAT_DEFAULT_SCOPE
    assert first.provider_binding_key == second.provider_binding_key == mapped.provider_binding_key
    assert second.fields["source_payload"] == first.fields["source_payload"]


def test_named_scope_isolation_includes_same_file_occurrence_alias():
    first, = _rows(_file(label="工作微信")).values()
    second, = _rows(_file(label="个人微信")).values()
    default, = _rows(_file()).values()
    assert len({first.provider_binding_key, second.provider_binding_key, default.provider_binding_key}) == 3
    assert not set(first.binding_keys) & set(second.binding_keys)
    assert first.occurrence_key != second.occurrence_key


def test_scope_names_are_normalized_and_cannot_collide_with_default_namespace():
    from ft.domain.cash_source_scope import normalize_scope_label, source_account_key, WECHAT_DEFAULT_SCOPE
    assert normalize_scope_label("  cafe\u0301  ") == "café"
    assert source_account_key("wechat", scope_label="  cafe\u0301  ") == source_account_key("wechat", scope_label="café")
    assert source_account_key("wechat", scope_label="default") != WECHAT_DEFAULT_SCOPE
    assert source_account_key("wechat", scope_label="  ") == WECHAT_DEFAULT_SCOPE
    assert source_account_key("alipay", "fixture@example.test") == "fixture@example.test"
    assert source_account_key("ccb_debit") is None


@pytest.mark.parametrize("label", ["x" * 65, "\n工作", "工作\t微信", "\x00", "\ud800", 1, []])
def test_scope_label_rejects_invalid_input_with_safe_reason(label):
    from ft.domain.cash_source_scope import CashSourceScopeError, normalize_scope_label
    with pytest.raises(CashSourceScopeError, match="source_scope_invalid"):
        normalize_scope_label(label)


def test_setting_other_channel_scope_is_rejected():
    from ft.domain.cash_source_scope import CashSourceScopeError, source_account_key
    with pytest.raises(CashSourceScopeError, match="source_scope_unsupported"):
        source_account_key("alipay", "fixture@example.test", scope_label="工作")


def test_bridge_preserves_original_rows_and_ignores_unreadable_or_skipped_rows():
    from ft.adapters.cash_statement_rows import ParsedCashRow
    file = _file()
    before = file.document.rows[0].fields.copy()
    file = replace(file, document=replace(file.document, rows=(*file.document.rows,
        ParsedCashRow(19, None, error_code="row_unreadable", disposition="skipped"))))
    result = _rows(file, resolved_fields={18: {"account_id": "101", "note": "本次选择"}})
    assert list(result) == ["file_1:r18"]
    assert result["file_1:r18"].fields["note"] == "本次选择"
    assert file.document.rows[0].fields == before


def test_same_number_reimports_update_and_fixed_conflict_under_default_scope():
    from ft.domain.cash_source_matching import SourceRecord, resolve_source_file
    first, = _rows(_file()).values()
    old = SourceRecord("1001", "wechat", first.source_account_key, {"state": "posted", **first.fields},
        binding_keys=frozenset(first.binding_keys))
    assert resolve_source_file([first], [old])[0].action == "duplicate"
    changed = replace(first, file_digest="b" * 64, fields={**first.fields, "counterparty": "新名称"})
    assert resolve_source_file([changed], [old])[0].action == "match"
    for patch in ({"amount": "-21"}, {"amount": "20"}, {"occurred_at": "2026-08-16T02:00:00Z"}):
        result, = resolve_source_file([replace(changed, fields={**changed.fields, **patch})], [old])
        assert result.action == "conflict" and result.transaction_id == "1001"
    other, = _rows(_file(label="另一个微信")).values()
    assert resolve_source_file([other], [old])[0].action == "new"
