from dataclasses import replace
from decimal import Decimal

import pytest


PDF = b"%PDF-1.7\nstartxref\n123\n%%EOF\n"
CREDIT = ("入账日期", "交易卡号", "收\n支", "交易币种", "交易金额", "入账币种", "入账金额", "账户余额", "对方户名", "对方账号", "摘要", "交易场所")
DEBIT = ("交易日期", "账号", "储种", "序号", "币种", "钞汇", "摘要", "地区", "收入/支出金额", "余额", "对方户名", "对方账号", "渠道")
OWNER = "6212000000000001234"


def _credit(**changes):
    row = dict(zip(CREDIT, ("2026-08-15\n10:00:00", "6222000000001234", "借", "人民币", "20.00", "人民币", "20.00", "1000.00", "美团支付-测试商户", "", "消费", "门店")))
    row.update(changes)
    return [row[key] for key in CREDIT]


def _debit(**changes):
    row = dict(zip(DEBIT, ("2026-08-15\n10:00:00", "1614000000001234", "活期", "00000", "人民币", "钞", "无卡支付", "0200", "-20.00", "1000.00", "测试商户", "6222****1234", "快捷支付")))
    row.update(changes)
    return [row[key] for key in DEBIT]


class Page:
    def __init__(self, *rows, kind="credit", number=1, total=1, count=None, metadata=None, bank="中国工商银行"):
        self.header = CREDIT if kind == "credit" else DEBIT
        self.rows = list(rows)
        self.text = (bank + "\n卡号：" + OWNER + " 户名：测试用户\n起止日期：2026-08-01 - 2026-08-31\n"
                     + " ".join(self.header) + "\n"
                     + f"本页交易笔数：{len(rows) if count is None else count}\n共 {total} 页 第 {number} 页") if metadata is None else metadata
        self.chars = [{"text": char, "object_type": "char", "size": 10} for char in self.text]
        self.closed = False
        self.table_calls = self.text_calls = 0

    def filter(self, predicate):
        assert not predicate({"object_type": "char", "size": 30})
        assert predicate({"object_type": "char", "size": 10})
        return self

    def extract_text(self):
        self.text_calls += 1
        return self.text

    def extract_tables(self):
        self.table_calls += 1
        return [[list(self.header), *self.rows]]

    def close(self):
        self.closed = True


def _install(monkeypatch, pages):
    import ft.adapters.icbc_pdf_document as reader
    import pdfplumber
    class Document:
        closed = False
        def close(self):
            self.closed = True
    document = Document()
    calls = []
    def open_(stream, *, password=None):
        assert stream.read() == PDF
        stream.seek(0)
        calls.append((stream, password))
        return document
    monkeypatch.setattr(pdfplumber, "open", open_)
    monkeypatch.setattr(reader, "_pdf_pages", lambda _pdf: iter(pages))
    return document, calls


def _parse(monkeypatch, pages, **kwargs):
    from ft.adapters.cash_statement_rows import parse_cash_statement
    document, calls = _install(monkeypatch, pages)
    parsed = parse_cash_statement(PDF, **kwargs)
    assert len(calls) == 1 and calls[0][0].closed and document.closed
    assert all(page.closed and page.text_calls == page.table_calls == 1 for page in pages)
    return parsed


@pytest.mark.parametrize("kind", ["credit", "debit"])
def test_unreadable_amount_retains_trusted_owner_and_raw_summary(monkeypatch, kind):
    page = Page(_credit(交易金额="bad", 摘要="退货") if kind == "credit" else _debit(**{"收入/支出金额": "bad", "摘要": "退款"}), kind=kind)
    parsed = _parse(monkeypatch, [page])
    row, = parsed.rows
    assert row.fields["amount"] is None and row.error_code is None
    assert row.source_explanation["_source_account_identifier"] == (row.source_account_identifier or parsed.source_account_identifier)
    assert row.source_explanation["summary"] in {"退货", "退款"}


def test_credit_uses_transaction_columns_preserves_occurrences_and_exact_raw_note(monkeypatch):
    note = "  原始摘要" + "详" * 120 + "  "
    page = Page(_credit(摘要=note), _credit(摘要=note))
    parsed = _parse(monkeypatch, [page])
    assert parsed.source_type == "icbc_credit" and len(parsed.rows) == 2
    assert len({row.position for row in parsed.rows}) == 2
    assert all(row.fields["amount"] == "-20.00" and row.fields["currency"] == "CNY" for row in parsed.rows)
    assert all(row.fields["note"] == note and row.provider_id is None for row in parsed.rows)
    assert parsed.rows[0].fields["source_payload"] == dict(zip(CREDIT, page.rows[0]))
    assert parsed.rows[0].source_explanation["source_display_name"] == "工商银行信用卡(1234)"
    assert parsed.coverage.complete and parsed.coverage.contains(parsed.rows[0].fields["occurred_at"])


def test_credit_keeps_each_full_card_as_its_own_source_scope(monkeypatch):
    from ft.application.cash_import_source_rows import source_rows
    from ft.application.parsed_import_staging import ParsedImportFile
    parsed = _parse(monkeypatch, [Page(_credit(), _credit(交易卡号="6222000000005678"))])
    assert parsed.source_account_identifier is None
    file = ParsedImportFile("f1", "c1", "fixture.pdf", "a" * 64, len(PDF), parsed.source_type, "parsed", parsed)
    rows = list(source_rows(file).values())
    assert [row.source_account_key for row in rows] == ["6222000000001234", "6222000000005678"]


def test_debit_owner_comes_from_file_card_not_the_settlement_account_column(monkeypatch):
    parsed = _parse(monkeypatch, [Page(_debit(), kind="debit")])
    assert parsed.source_type == "icbc_debit" and parsed.source_account_identifier == OWNER
    assert parsed.rows[0].fields["amount"] == "-20.00"
    assert parsed.rows[0].fields["source_payload"]["账号"] != OWNER
    assert parsed.rows[0].fields["record_type"] == "consumption"
    assert parsed.rows[0].source_explanation["source_display_name"] == "工商银行借记卡(1234)"


@pytest.mark.parametrize("kind", ["credit", "debit"])
def test_pdf_single_row_source_explanation_keeps_refund_evidence(monkeypatch, kind):
    cells = _credit(摘要="退货", 对方户名="测试商户", **{"收\n支": "贷"}) if kind == "credit" else _debit(摘要="退款", **{"收入/支出金额": "20.00"})
    row = _parse(monkeypatch, [Page(cells, kind=kind)]).rows[0]
    assert row.source_explanation["summary"] in {"退货", "退款"}
    assert row.source_explanation["_is_refund"] is True
    assert not {"amount", "note", "category", "offset_group", "offset_role", "_fact_id", "_source_payload"} & row.source_explanation.keys()


def test_a_counterparty_card_in_the_body_cannot_replace_a_missing_file_owner(monkeypatch):
    page = Page(_debit(), kind="debit")
    page.text = page.text.replace("卡号：" + OWNER + " 户名：测试用户\n", "")
    page.text += "\n交易日期 账号 储种\n业务摘要：转入他人卡号：6222000000009999 户名：对方"
    parsed = _parse(monkeypatch, [page])
    assert parsed.source_account_identifier is None
    assert parsed.rows[0].fields["amount"] == "-20.00"


@pytest.mark.parametrize("amount", ["20", "20.1", "20.12", "12345678901234567890.123456789012345678"])
def test_debit_reuses_the_exact_validated_transaction_amount(monkeypatch, amount):
    parsed = _parse(monkeypatch, [Page(_debit(**{"收入/支出金额": amount}), kind="debit")])
    assert parsed.rows[0].disposition == "candidate" and not parsed.rows[0].issue_codes
    assert Decimal(parsed.rows[0].fields["amount"]) == Decimal(amount)


def test_wrapped_note_header_preserves_the_original_value_and_header(monkeypatch):
    page = Page(_credit(摘要="  原始说明  "))
    page.header = tuple("摘\n要" if key == "摘要" else key for key in page.header)
    parsed = _parse(monkeypatch, [page])
    assert parsed.rows[0].fields["note"] == "  原始说明  "
    assert parsed.rows[0].fields["source_payload"]["摘\n要"] == "  原始说明  "


@pytest.mark.parametrize("kind", ["credit", "debit"])
def test_explicit_euro_currency_is_preserved_without_legacy_cny_default(monkeypatch, kind):
    row = _credit(交易币种="欧元") if kind == "credit" else _debit(币种="欧元")
    parsed = _parse(monkeypatch, [Page(row, kind=kind)])
    assert parsed.rows[0].fields["currency"] == "EUR" and not parsed.rows[0].issue_codes


@pytest.mark.parametrize("kind,patch,field,issue", [
    ("credit", {"交易金额": "bad"}, "amount", "amount_unreadable"),
    ("credit", {"交易币种": "unknown"}, "currency", "currency_unrecognized"),
    ("credit", {"收\n支": "unknown", "交易金额": "20.00"}, "amount", "direction_unrecognized"),
    ("credit", {"入账日期": "2026-99-01\n10:00:00"}, "occurred_at", "time_unreadable"),
    ("debit", {"收入/支出金额": "bad"}, "amount", "amount_unreadable"),
    ("debit", {"币种": "unknown"}, "currency", "currency_unrecognized"),
    ("debit", {"交易日期": "2026-08-15"}, "occurred_at", "time_unreadable"),
])
def test_incomplete_rows_keep_unknown_financial_values_and_do_not_block_good_rows(monkeypatch, kind, patch, field, issue):
    factory = _credit if kind == "credit" else _debit
    parsed = _parse(monkeypatch, [Page(factory(**patch), factory(), kind=kind)])
    first, second = parsed.rows
    assert first.disposition == "candidate" and first.fields[field] is None and issue in first.issue_codes
    assert second.fields["amount"] == "-20.00" and not second.issue_codes
    assert not parsed.coverage.complete


def test_bad_credit_amount_is_not_replaced_with_the_valid_booking_amount_or_balance(monkeypatch):
    row = _parse(monkeypatch, [Page(_credit(交易金额="missing", 入账金额="888.00", 账户余额="999.00"))]).rows[0]
    assert row.fields["amount"] is None
    assert row.fields["source_payload"]["入账金额"] == "888.00"


def test_a_shape_error_is_skipped_but_other_rows_survive(monkeypatch):
    parsed = _parse(monkeypatch, [Page(_credit()[:-1], _credit())])
    assert parsed.rows[0].disposition == "skipped" and parsed.rows[0].error_code == "row_shape_invalid"
    assert parsed.rows[1].fields["amount"] == "-20.00"
    assert not parsed.coverage.complete


@pytest.mark.parametrize("kind", ["credit", "debit"])
def test_masked_or_conflicting_source_identity_does_not_become_a_full_scope(monkeypatch, kind):
    if kind == "credit":
        parsed = _parse(monkeypatch, [Page(_credit(交易卡号="622200******1234"))])
        assert parsed.rows[0].source_account_identifier is None
    else:
        pages = [Page(_debit(), kind="debit", number=1, total=2), Page(_debit(), kind="debit", number=2, total=2)]
        pages[1].text = pages[1].text.replace(OWNER, "6212000000000005678")
        parsed = _parse(monkeypatch, pages)
        assert parsed.source_account_identifier is None
    assert parsed.rows[0].fields["amount"] == "-20.00"


@pytest.mark.parametrize("failure", ["count", "page_count", "page_order", "range", "filter"])
def test_coverage_requires_consistent_explicit_range_pages_and_counts(monkeypatch, failure):
    page = Page(_credit())
    if failure == "count":
        page.text = page.text.replace("本页交易笔数：1", "本页交易笔数：2")
    elif failure == "page_count":
        page.text = page.text.replace("共 1 页", "共 2 页")
    elif failure == "page_order":
        page.text = page.text.replace("第 1 页", "第 2 页")
    elif failure == "range":
        page.text = page.text.replace("起止日期：2026-08-01 - 2026-08-31", "")
    else:
        page.text += "\n交易类型：消费"
    assert not _parse(monkeypatch, [page]).coverage.complete


def test_all_pages_are_checked_for_conflicting_ranges_and_released(monkeypatch):
    first = Page(_credit(), number=1, total=2)
    second = Page(_credit(), number=2, total=2)
    second.text = second.text.replace("2026-08-31", "2026-09-30")
    parsed = _parse(monkeypatch, [first, second])
    assert len(parsed.rows) == 2 and not parsed.coverage.complete


def test_source_hint_cannot_relabel_another_bank_or_the_other_native_format(monkeypatch):
    from ft.adapters.cash_statement_document import StatementDocumentError
    for page, hint in ((Page(_credit(), bank="其他银行"), "icbc_credit"), (Page(_debit(), kind="debit"), "icbc_credit")):
        document, calls = _install(monkeypatch, [page])
        from ft.adapters.cash_statement_rows import parse_cash_statement
        with pytest.raises(StatementDocumentError, match="statement_source_unsupported"):
            parse_cash_statement(PDF, source=hint)
        assert document.closed and calls[0][0].closed and page.closed


def test_parser_text_budget_closes_resources(monkeypatch):
    import ft.adapters.icbc_pdf_document as reader
    from ft.adapters.cash_statement_rows import parse_cash_statement
    from ft.adapters.cash_statement_document import StatementDocumentError
    page = Page(_credit())
    document, calls = _install(monkeypatch, [page])
    monkeypatch.setattr(reader, "_MAX_PARSED_BYTES", 20)
    with pytest.raises(StatementDocumentError, match="statement_rows_too_large"):
        parse_cash_statement(PDF)
    assert page.closed and document.closed and calls[0][0].closed


def test_unexpected_table_error_is_redacted_and_all_resources_close(monkeypatch):
    from ft.adapters.cash_statement_rows import parse_cash_statement
    from ft.adapters.cash_statement_document import StatementDocumentError
    page = Page(_credit())
    document, calls = _install(monkeypatch, [page])
    def fail():
        raise RuntimeError("private parser details")
    monkeypatch.setattr(page, "extract_tables", fail)
    with pytest.raises(StatementDocumentError, match="statement_unreadable") as error:
        parse_cash_statement(PDF)
    assert "private" not in str(error.value)
    assert page.closed and document.closed and calls[0][0].closed


@pytest.mark.parametrize("budget", ["_MAX_PDF_PAGES", "_MAX_ROWS", "_MAX_COLUMNS", "_MAX_TABLES_PER_PAGE", "_MAX_PDF_CHARS"])
def test_pdf_resource_limits_fail_safely_and_release_active_pages(monkeypatch, budget):
    import ft.adapters.icbc_pdf_document as reader
    from ft.adapters.cash_statement_rows import parse_cash_statement
    from ft.adapters.cash_statement_document import StatementDocumentError
    pages = [Page(_credit(), _credit())]
    if budget == "_MAX_PDF_PAGES":
        pages = [Page(_credit(), number=1, total=2), Page(_credit(), number=2, total=2)]
    document, calls = _install(monkeypatch, pages)
    monkeypatch.setattr(reader, budget, 0 if budget == "_MAX_TABLES_PER_PAGE" else 1)
    with pytest.raises(StatementDocumentError):
        parse_cash_statement(PDF)
    assert all(page.closed for page in pages)
    assert document.closed and calls[0][0].closed


def test_page_iteration_is_bounded_before_constructing_all_pages(monkeypatch):
    import ft.adapters.icbc_pdf_document as reader
    from pdfminer.pdfpage import PDFPage
    import pdfplumber.page
    from ft.adapters.cash_statement_document import StatementDocumentError
    created = []
    monkeypatch.setattr(reader, "_MAX_PDF_PAGES", 2)
    monkeypatch.setattr(PDFPage, "create_pages", lambda _doc: iter(range(100)))
    class Pdf:
        doc = object()
    class Extracted:
        height = 100
    def build(_pdf, source, **kwargs):
        created.append(source)
        return Extracted()
    monkeypatch.setattr(pdfplumber.page, "Page", build)
    with pytest.raises(StatementDocumentError, match="statement_rows_too_large"):
        list(reader._pdf_pages(Pdf()))
    assert created == [0, 1]


@pytest.mark.parametrize("password", [None, "wrong"])
def test_pdf_password_errors_are_specific_and_do_not_probe_or_reopen(monkeypatch, password):
    from pdfminer.pdfdocument import PDFPasswordIncorrect
    from ft.adapters.cash_statement_rows import parse_cash_statement
    from ft.importers.pdf_tools import PDFPasswordInvalidError, PDFPasswordRequiredError
    import pdfplumber
    streams = []
    def open_(stream, *, password=None):
        streams.append(stream)
        raise PDFPasswordIncorrect("private provider details")
    monkeypatch.setattr(pdfplumber, "open", open_)
    expected = PDFPasswordRequiredError if password is None else PDFPasswordInvalidError
    with pytest.raises(expected):
        parse_cash_statement(PDF, password=password)
    assert len(streams) == 1 and streams[0].closed


def test_exact_large_amounts_do_not_depend_on_the_default_decimal_context(monkeypatch):
    amount = "12345678901234567890.123456789012345678"
    parsed = _parse(monkeypatch, [Page(_credit(交易金额=amount))])
    assert Decimal(parsed.rows[0].fields["amount"]) == Decimal(amount).copy_negate()


def test_native_refund_and_transfer_classification_are_reused_without_pairing(monkeypatch):
    from ft import convert
    monkeypatch.setattr(convert, "_pair_refunds", lambda *a, **kw: pytest.fail("parsing must not pair"))
    parsed = _parse(monkeypatch, [Page(_credit(), _credit(**{"收\n支": "贷", "摘要": "退货"}),
                                    _credit(摘要="转账", 对方账号="6222****9999"))])
    assert len(parsed.rows) == 3
    assert [row.fields["record_type"] for row in parsed.rows] == ["consumption", "refund", "transfer_out"]
    assert parsed.rows[2].fields["counterparty_account"] == "6222****9999"


def test_an_unexpected_native_row_error_does_not_block_later_rows(monkeypatch):
    from ft import convert
    original = convert._parse_icbc_credit_source_row
    def parse(payload, **kwargs):
        if payload["交易场所"] == "bad":
            raise RuntimeError("private details")
        return original(payload, **kwargs)
    monkeypatch.setattr(convert, "_parse_icbc_credit_source_row", parse)
    parsed = _parse(monkeypatch, [Page(_credit(交易场所="bad"), _credit())])
    assert parsed.rows[0].disposition == "skipped" and parsed.rows[0].error_code == "row_unreadable"
    assert parsed.rows[1].fields["amount"] == "-20.00"
