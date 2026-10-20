"""银行表格的新解析边界：保留出现、隔离坏行，不在此建立来源绑定。"""
import csv
from decimal import Decimal
from io import BytesIO, StringIO

import pytest
import xlwt


CCB_HEADERS = ["序号", "摘要", "币别", "钞汇", "交易日期", "交易金额", "账户余额", "交易地点/附言", "对方账号与户名"]
ASIA_HEADERS = ["序號", "交易時間", "", "起息日期", "業務類型", "摘要", "收入金額", "支出金額",
    "餘額", "對方賬號", "對方戶名", "憑證號", "匯率", "備註", "交易場所"]


def _ccb_row(**changes):
    return dict({"序号": "1", "摘要": "消费", "币别": "人民币元", "钞汇": "钞", "交易日期": "20260128",
        "交易金额": "-4.23", "账户余额": "100.00", "交易地点/附言": "财付通-微信支付-测试商户",
        "对方账号与户名": "000123/测试商户"}, **changes)


def _ccb_bytes(*rows, account="6217000000000000001", end="20260131", headers=None, extra_metadata=(), title="中国建设银行个人活期账户全部交易明细"):
    workbook = xlwt.Workbook()
    sheet = workbook.add_sheet("明细")
    sheet.write(0, 0, title)
    for column, text in enumerate([f"卡号/账号:{account}", "起始日期:20260101", f"结束日期:{end}", *extra_metadata]):
        sheet.write(1, column, text)
    for column, value in enumerate(headers or CCB_HEADERS):
        sheet.write(3, column, value)
    for index, row in enumerate(rows, 4):
        for column, header in enumerate(CCB_HEADERS):
            sheet.write(index, column, row.get(header, ""))
    buffer = BytesIO()
    workbook.save(buffer)
    return buffer.getvalue()


def _asia_row(**changes):
    return dict({"序號": "1", "交易時間": "2026-07-01", "": "09:10:11", "起息日期": "2026-07-01",
        "業務類型": "轉賬", "摘要": "本地轉賬", "收入金額": "", "支出金額": "100.50", "餘額": "1000.50",
        "對方賬號": "99887766", "對方戶名": "测试收款人", "憑證號": "000777", "匯率": "", "備註": "  原始備註\n  ", "交易場所": "其他地点"}, **changes)


def _asia_bytes(*rows, currency="港幣", account="1234567890125678", extra_metadata=(), headers=None, include_count=True):
    buffer = StringIO(newline="")
    writer = csv.writer(buffer, delimiter="\t", lineterminator="\r\n")
    writer.writerows([["銀行名稱：", "工銀亞洲"], ["銀行賬號：", account], ["下掛賬戶：", "1234567890125679"],
        ["賬戶類型：", "活期戶"], ["幣種：", currency], ["日期：", "2026-07-01", "-", "2026-07-31"],
        *([["總筆數：", str(len(rows))]] if include_count else []), *extra_metadata, headers or ASIA_HEADERS,
        *[[row.get(header, "") for header in ASIA_HEADERS] if isinstance(row, dict) else row for row in rows]])
    return buffer.getvalue().encode("utf-16")


def _raw(content, source=None):
    from ft.adapters.bank_statement_document import read_bank_document
    return read_bank_document(content, source=source)


def _parse(content, source=None):
    from ft.adapters.cash_statement_rows import normalize_bank_document
    return normalize_bank_document(_raw(content, source))


@pytest.mark.parametrize("source,builder,row,sequence,position", [
    ("ccb_debit", _ccb_bytes, _ccb_row, "序号", 5),
    ("icbc_asia", _asia_bytes, _asia_row, "序號", 9),
])
def test_raw_occurrences_and_current_source_declarations(source, builder, row, sequence, position):
    first, second = row(), row(**{sequence: "2"})
    raw = _raw(builder(first, second))
    assert raw.source_type == source
    assert [item.position for item in raw.rows] == [position, position + 1]
    assert [item.payload for item in raw.rows] == [first, second]
    assert raw.observed_count == 2
    assert raw.declared_count == (None if source == "ccb_debit" else 2)
    assert raw.coverage.complete
    assert raw.source_account_identifier
    assert not any(hasattr(raw, field) for field in ("content", "path", "password", "workbook"))
    parsed = _parse(builder(first, second))
    assert len(parsed.rows) == 2
    assert all(item.provider_id is None for item in parsed.rows)
    assert parsed.rows[0].fields == dict(parsed.rows[1].fields, source_payload=first)


def test_ccb_native_financial_fields_and_raw_note():
    row = _ccb_row(摘要="  消费  ")
    parsed = _parse(_ccb_bytes(row))
    fields = parsed.rows[0].fields
    assert fields["amount"] == "-4.23" and fields["currency"] == "CNY"
    assert fields["occurred_at"] == "2026-01-27T16:00:00+00:00"
    assert fields["record_type"] == "consumption"
    assert fields["counterparty"] == "测试商户" and fields["counterparty_account"] == "000123"
    assert fields["note"] == "  消费  "
    assert parsed.rows[0].payment_method == "微信支付"
    assert parsed.coverage.time_precision == "day"


@pytest.mark.parametrize("changes,expected_type", [
    ({"摘要": "消费退货", "交易金额": "4.23"}, "refund"),
    ({"摘要": "代理收款", "交易金额": "4.23"}, "repayment"),
    ({"摘要": "银转证"}, "investment_out"),
    ({"摘要": "支付机构提现", "交易金额": "4.23"}, "withdrawal_in"),
])
def test_ccb_reuses_existing_source_classification(changes, expected_type):
    assert _parse(_ccb_bytes(_ccb_row(**changes))).rows[0].fields["record_type"] == expected_type


def test_asia_native_financial_fields_and_raw_note():
    row = _parse(_asia_bytes(_asia_row())).rows[0]
    assert row.fields["amount"] == "-100.50" and row.fields["currency"] == "HKD"
    assert row.fields["occurred_at"] == "2026-07-01T01:10:11+00:00"
    assert row.fields["record_type"] == "transfer_out"
    assert row.fields["note"] == "  原始備註\n  "
    assert row.payment_method == "工银亚洲活期账户(5670)"
    assert row.fields["source_payload"][""] == "09:10:11"


def test_asia_masked_counterparty_uses_existing_normalization():
    row = _parse(_asia_bytes(_asia_row(對方賬號="879825****47"), account="879825074240")).rows[0]
    assert row.fields["counterparty_account"] == "879825074247"
    assert row.fields["counterparty_account_attrs"] == ["masked", "reconstructed"]
    assert row.fields["source_payload"]["對方賬號"] == "879825****47"


@pytest.mark.parametrize("builder,first,second,field,issue", [
    (_ccb_bytes, _ccb_row(交易金额="bad"), _ccb_row(序号="2"), "amount", "amount_unreadable"),
    (_ccb_bytes, _ccb_row(交易日期="20260230"), _ccb_row(序号="2"), "occurred_at", "time_unreadable"),
    (_ccb_bytes, _ccb_row(币别="未识别"), _ccb_row(序号="2"), "currency", "currency_unrecognized"),
    (_asia_bytes, _asia_row(支出金額="bad"), _asia_row(序號="2"), "amount", "amount_unreadable"),
    (_asia_bytes, _asia_row(交易時間="2026-07-99"), _asia_row(序號="2"), "occurred_at", "time_unreadable"),
    (_asia_bytes, _asia_row(收入金額="1"), _asia_row(序號="2"), "amount", "direction_unrecognized"),
])
def test_incomplete_row_is_preserved_without_blocking_other_occurrences(builder, first, second, field, issue):
    parsed = _parse(builder(first, second))
    assert len(parsed.rows) == 2 and parsed.rows[0].fields[field] is None
    assert issue in parsed.rows[0].issue_codes
    assert parsed.rows[0].disposition == parsed.rows[1].disposition == "candidate"
    assert not parsed.rows[1].issue_codes
    assert not parsed.coverage.complete


def test_unknown_file_currency_is_not_defaulted():
    parsed = _parse(_asia_bytes(_asia_row(), currency="bad-HKD-currency"))
    assert parsed.rows[0].fields["currency"] is None
    assert parsed.rows[0].issue_codes == ("currency_unrecognized",)
    assert not parsed.coverage.complete


@pytest.mark.parametrize("builder,row,extra", [
    (_ccb_bytes, _ccb_row(), ("卡号/账号:6217000000000000002",)),
    (_asia_bytes, _asia_row(), (["銀行賬號：", "1234567890129999"],)),
])
def test_conflicting_owner_declarations_never_use_first_or_fallback(builder, row, extra):
    raw = _raw(builder(row, extra_metadata=extra))
    assert raw.source_account_identifier is None
    assert not raw.coverage.complete


@pytest.mark.parametrize("builder,row", [(_ccb_bytes, _ccb_row()), (_asia_bytes, _asia_row())])
@pytest.mark.parametrize("account", ["6217********0001", "0001"])
def test_masked_or_tail_only_owner_is_display_evidence_not_cross_file_identity(builder, row, account):
    raw = _raw(builder(row, account=account))
    assert raw.source_account_identifier is None
    assert raw.source_account_label == account
    assert not raw.coverage.complete


@pytest.mark.parametrize("builder,row", [(_ccb_bytes, _ccb_row(序号="2")), (_asia_bytes, _asia_row(序號="2"))])
def test_missing_occurrence_sequence_degrades_coverage_but_keeps_row(builder, row):
    parsed = _parse(builder(row))
    assert len(parsed.rows) == 1 and parsed.rows[0].fields["amount"] is not None
    assert not parsed.coverage.complete


def test_asia_sequence_without_independent_total_never_proves_complete_file():
    parsed = _parse(_asia_bytes(_asia_row(), include_count=False))
    assert parsed.declared_count is None
    assert not parsed.coverage.complete
    assert parsed.rows[0].fields["amount"] == "-100.50"


def test_asia_truncated_prefix_and_conflicting_count_cannot_authorize_updates():
    complete = _asia_bytes(_asia_row(), _asia_row(序號="2", 對方戶名="另一商户"))
    # 完整第一行末尾正好是合法 UTF-16 字节边界，序号仍连续。
    text = complete.decode("utf-16")
    truncated = text[:text.index("2\t2026-07-01")].encode("utf-16")
    assert complete.startswith(truncated)
    assert _parse(complete).coverage.complete
    assert not _parse(truncated).coverage.complete
    parsed = _parse(_asia_bytes(_asia_row(), extra_metadata=(["總筆數：", "2"],)))
    assert parsed.declared_count is None and not parsed.coverage.complete


def test_shape_error_is_explicit_and_does_not_block_other_rows():
    parsed = _parse(_asia_bytes(["1", "broken"], _asia_row(序號="2")))
    assert parsed.rows[0].fields is None and parsed.rows[0].error_code == "row_shape_invalid"
    assert parsed.rows[1].fields["amount"] == "-100.50"
    assert not parsed.coverage.complete


def test_zero_opening_row_has_explicit_skip_reason():
    parsed = _parse(_asia_bytes(_asia_row(業務類型="新開戶", 支出金額="", 收入金額="0.00"), _asia_row(序號="2")))
    assert parsed.rows[0].disposition == "skipped" and parsed.rows[0].error_code == "account_opening"
    assert parsed.rows[1].fields["amount"] == "-100.50"
    assert parsed.coverage.complete


@pytest.mark.parametrize("source,builder,row,field", [
    ("ccb_debit", _ccb_bytes, _ccb_row, "交易金额"), ("icbc_asia", _asia_bytes, _asia_row, "支出金額"),
])
def test_decimal_sign_never_rounds_long_valid_value(source, builder, row, field):
    value = "12345678901234567890.123456789012345678"
    parsed = _parse(builder(row(**{field: value})), source)
    expected = Decimal(value) if source == "ccb_debit" else Decimal(value).copy_negate()
    assert Decimal(parsed.rows[0].fields["amount"]) == expected


def test_unexpected_native_conversion_error_only_skips_that_row(monkeypatch):
    from ft.importers import ccb_debit
    original = ccb_debit._parse_ccb_source_row
    def failing(payload, **kwargs):
        if payload["摘要"] == "bad":
            raise RuntimeError("private technical detail")
        return original(payload, **kwargs)
    monkeypatch.setattr(ccb_debit, "_parse_ccb_source_row", failing)
    parsed = _parse(_ccb_bytes(_ccb_row(摘要="bad"), _ccb_row(序号="2")))
    assert parsed.rows[0].error_code == "row_unreadable"
    assert parsed.rows[1].fields["amount"] == "-4.23"
    assert "private technical detail" not in repr(parsed)


def test_source_hint_cannot_reinterpret_other_bank_file():
    from ft.adapters.cash_statement_document import StatementDocumentError
    with pytest.raises(StatementDocumentError, match="statement_source_unsupported"):
        _raw(_ccb_bytes(_ccb_row()), "icbc_asia")


@pytest.mark.parametrize("content", [
    _ccb_bytes(_ccb_row(), title="其他银行个人活期账户全部交易明细"),
    _asia_bytes(_asia_row()).decode("utf-16").replace("工銀亞洲", "其他银行").encode("utf-16"),
], ids=["ccb", "asia"])
def test_matching_columns_do_not_relabel_another_bank_as_a_supported_source(content):
    from ft.adapters.cash_statement_document import StatementDocumentError
    with pytest.raises(StatementDocumentError, match="statement_source_unsupported"):
        _raw(content)


def test_ccb_partial_export_title_cannot_authorize_full_coverage():
    raw = _raw(_ccb_bytes(_ccb_row(), title="中国建设银行个人活期账户非全部交易明细"))
    assert len(raw.rows) == 1
    assert not raw.coverage.complete


def test_table_reading_uses_one_workbook_and_releases_it(monkeypatch):
    import xlrd
    original = xlrd.open_workbook
    opened, closed = [], []
    def open_once(*args, **kwargs):
        workbook = original(*args, **kwargs)
        opened.append(workbook)
        release = workbook.release_resources
        def close():
            closed.append(True)
            return release()
        workbook.release_resources = close
        return workbook
    monkeypatch.setattr(xlrd, "open_workbook", open_once)
    _parse(_ccb_bytes(_ccb_row()))
    assert len(opened) == len(closed) == 1
    with pytest.raises(Exception):
        _raw(_ccb_bytes(_ccb_row(), headers=["序号", "交易日期", "交易金额", "账户余额", "duplicate", "duplicate"]))
    assert len(opened) == len(closed) == 2


def test_truncated_xls_container_never_certifies_complete_coverage():
    from ft.adapters.cash_statement_document import StatementDocumentError
    content = _ccb_bytes(_ccb_row(), _ccb_row(序号="2"))
    for size in (len(content) - 1, len(content) - 512, len(content) // 2):
        with pytest.raises(StatementDocumentError):
            _raw(content[:size])


def test_xls_parser_diagnostics_are_private_and_invalidate_the_file(monkeypatch, capsys):
    import xlrd
    from ft.adapters.cash_statement_document import StatementDocumentError
    original = xlrd.open_workbook
    def warning(*args, **kwargs):
        if "logfile" in kwargs:
            kwargs["logfile"].write("private parser diagnostic")
        else:
            print("private parser diagnostic")
        return original(*args, **kwargs)
    monkeypatch.setattr(xlrd, "open_workbook", warning)
    with pytest.raises(StatementDocumentError) as error:
        _raw(_ccb_bytes(_ccb_row()))
    assert str(error.value) == "statement_unreadable"
    assert "private" not in capsys.readouterr().out


def test_shared_dispatch_does_not_reopen_bank_workbook(monkeypatch):
    import xlrd
    from ft.adapters.cash_statement_rows import parse_cash_statement
    original, opened = xlrd.open_workbook, []
    def tracked(*args, **kwargs):
        opened.append(True)
        return original(*args, **kwargs)
    monkeypatch.setattr(xlrd, "open_workbook", tracked)
    assert parse_cash_statement(_ccb_bytes(_ccb_row())).source_type == "ccb_debit"
    assert len(opened) == 1


@pytest.mark.parametrize("limit,value", [("_MAX_ROWS", 1), ("_MAX_PHYSICAL_ROWS", 9), ("_MAX_PARSED_BYTES", 64), ("_MAX_COLUMNS", 14)])
def test_bank_table_budgets_include_metadata_and_raw_occurrences(monkeypatch, limit, value):
    from ft.adapters import bank_statement_document
    from ft.adapters.cash_statement_document import StatementDocumentError
    monkeypatch.setattr(bank_statement_document, limit, value)
    with pytest.raises(StatementDocumentError, match="statement_rows_too_large"):
        _raw(_asia_bytes(_asia_row(), _asia_row(序號="2")))
