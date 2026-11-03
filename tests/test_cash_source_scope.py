"""平台流水身份范围与资金账户分组范围分别计算。"""
from dataclasses import replace
from hashlib import sha256
import pytest


def _file(*, nickname="昵称甲", owner=None, digest=None):
    from ft.adapters.cash_statement_rows import ParsedCashDocument, ParsedCashRow
    from ft.application.parsed_import_staging import ParsedImportFile
    from ft.domain.cash_source_matching import SourceCoverage
    document = ParsedCashDocument("wechat", "sheet:0", "platform-rows-v1+native-v1", (ParsedCashRow(18, {
        "amount": "-20.00", "currency": "CNY", "occurred_at": "2026-08-15T02:00:00+00:00",
        "counterparty": "商户", "note": "原始商品", "record_type": "consumption", "record_subtype": "not_applicable",
        "source_payload": {"交易单号": "000123", "备注": "原始商品"}}, "零钱", "000123"),),
        owner, nickname, SourceCoverage(), 1, 1, source_nickname=nickname)
    return ParsedImportFile("file_1", "client_1", "synthetic.xlsx", digest or sha256(nickname.encode()).hexdigest(), 100,
        "wechat", "parsed", document)


def _rows(file, **kwargs):
    from ft.application.cash_import_source_rows import source_rows
    return source_rows(file, **kwargs)


def _alipay_file(owner, *, ref, digest, provider_id="000123", amount="-20.00"):
    from ft.adapters.cash_statement_rows import ParsedCashDocument, ParsedCashRow
    from ft.application.parsed_import_staging import ParsedImportFile
    from ft.domain.cash_source_matching import SourceCoverage
    document = ParsedCashDocument("alipay", "csv:0", "platform-rows-v1+native-v1", (ParsedCashRow(18, {
        "amount": amount, "currency": "CNY", "occurred_at": "2026-08-15T02:00:00+00:00",
        "counterparty": "商户", "note": "原始说明", "record_type": "consumption", "record_subtype": "not_applicable",
        "source_payload": {"交易订单号": provider_id}}, "银行卡", provider_id),),
        owner, owner, SourceCoverage(), 1, 1)
    return ParsedImportFile(ref, f"client-{ref}", f"{ref}.csv", digest, 100,
        "alipay", "parsed", document)


def test_wechat_identity_is_global_across_nickname_raw_owner_and_account_mapping():
    from ft.domain.cash_source_scope import WECHAT_DEFAULT_SCOPE
    first, = _rows(_file()).values()
    second, = _rows(_file(nickname="昵称乙", owner="actual-account")).values()
    mapped, = _rows(_file(), resolved_fields={18: {"account_id": "other-account"}}).values()
    assert first.source_account_key == second.source_account_key == mapped.source_account_key == WECHAT_DEFAULT_SCOPE
    assert first.provider_binding_key == second.provider_binding_key == mapped.provider_binding_key
    assert second.fields["source_payload"] == first.fields["source_payload"]


def test_same_transaction_number_from_another_wechat_account_matches_existing_record():
    from ft.domain.cash_source_matching import SourceRecord, resolve_source_file
    first, = _rows(_file(nickname="家庭号", owner="masked-1")).values()
    second, = _rows(_file(nickname="工作号", owner="masked-2", digest="b" * 64)).values()
    old = SourceRecord("1001", "wechat", first.source_account_key, {"state": "posted", **first.fields},
        binding_keys=frozenset(first.binding_keys))
    result, = resolve_source_file([second], [old])
    assert result.action == "duplicate" and result.transaction_id == "1001"
    changed = replace(second, fields={**second.fields, "counterparty": "新名称"})
    result, = resolve_source_file([changed], [old])
    assert result.action == "match" and result.transaction_id == "1001"


def test_alipay_matching_scope_is_global_while_funding_account_scope_stays_separate():
    from ft.domain.cash_source_scope import source_account_key, source_transaction_scope_key
    assert source_account_key("alipay", "fixture@example.test") == "fixture@example.test"
    assert source_account_key("alipay", "other@example.test") == "other@example.test"
    assert source_account_key("ccb_debit") is None
    assert source_transaction_scope_key("ccb_debit", "bank-account-1") == "bank-account-1"
    assert source_transaction_scope_key("ccb_debit", "bank-account-2") == "bank-account-2"
    first, = _rows(_alipay_file("fixture@example.test", ref="alipay-1", digest="a" * 64)).values()
    second, = _rows(_alipay_file("other@example.test", ref="alipay-2", digest="b" * 64)).values()
    assert first.source_account_key == second.source_account_key
    assert first.provider_binding_key == second.provider_binding_key


def test_alipay_order_number_matches_across_source_accounts_and_conflicts_on_fixed_fields():
    from ft.domain.cash_source_batch import resolve_source_batch
    from ft.domain.cash_source_matching import SourceRecord, resolve_source_file
    first, = _rows(_alipay_file("masked-account-1", ref="file-1", digest="a" * 64)).values()
    second, = _rows(_alipay_file("masked-account-2", ref="file-2", digest="b" * 64)).values()
    old = SourceRecord("1001", "alipay", first.source_account_key, {"state": "posted", **first.fields},
        binding_keys=frozenset(first.binding_keys), provider_id=first.provider_id)
    result, = resolve_source_file([second], [old])
    assert result.action == "duplicate" and result.transaction_id == "1001"

    changed = replace(second, fields={**second.fields, "amount": "-21.00"})
    result, = resolve_source_file([changed], [old])
    assert result.action == "conflict" and result.transaction_id == "1001"

    identical_batch = resolve_source_batch({"file-1:r18": first, "file-2:r18": second}, []).rows
    assert [row.action for row in identical_batch] == ["new", "batch_duplicate"]
    conflict_batch = resolve_source_batch({"file-1:r18": first, "file-2:r18": changed}, []).rows
    assert [row.action for row in conflict_batch] == ["conflict", "conflict"]


def test_duplicate_alipay_order_number_within_one_file_uses_existing_conflict_rule():
    from ft.domain.cash_source_batch import resolve_source_batch
    first, = _rows(_alipay_file("masked-account", ref="file-1", digest="a" * 64)).values()
    repeated = replace(first, position=19)
    same = resolve_source_batch({"file-1:r18": first, "file-1:r19": repeated}, []).rows
    assert [row.action for row in same] == ["new", "batch_duplicate"]

    conflicting = replace(repeated, fields={**repeated.fields, "amount": "-21.00"})
    conflict = resolve_source_batch({"file-1:r18": first, "file-1:r19": conflicting}, []).rows
    assert all(row.action == "conflict" and row.reason == "batch_identity_conflict" for row in conflict)


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


def test_multi_file_bridge_preserves_session_refs_and_resolved_fields_without_rereading():
    from ft.application.cash_import_source_rows import source_batch_rows
    from ft.domain.cash_source_batch import resolve_source_batch
    first = _file()
    second = replace(_file(nickname="昵称乙"), file_ref="file_2", client_file_ref="client_2")
    failed = replace(first, file_ref="failed", status="failed", document=None, error_code="file_unreadable")
    rows = source_batch_rows([first, second, failed], resolved_fields={
        "file_1": {18: {"account_id": "101"}}, "file_2": {18: {"account_id": "101"}}})
    assert list(rows) == ["file_1:r18", "file_2:r18"]
    assert all(row.fields["account_id"] == "101" for row in rows.values())
    assert "account_id" not in first.document.rows[0].fields
    plan = resolve_source_batch(rows, [])
    assert [row.action for row in plan.rows] == ["new", "batch_duplicate"]
    with pytest.raises(ValueError, match="duplicate_source_file_ref"):
        source_batch_rows([first, first])
