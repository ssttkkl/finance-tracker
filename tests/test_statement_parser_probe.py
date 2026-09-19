"""现金账单解析器的快速格式探测契约。"""

from ft.domain.imports import StatementImportCommand


def test_statement_parser_can_parse_distinguishes_icbc_pdf_formats(monkeypatch, tmp_path):
    from ft.adapters.statement_import import StatementParser
    from ft.importers import pdf_tools

    source = tmp_path / "statement.pdf"
    source.write_bytes(b"%PDF-1.7")
    calls = []

    def first_page_words(path, *, password=None):
        calls.append((path, password))
        return "中国工商银行信用卡历史明细（电子版）"

    monkeypatch.setattr(pdf_tools, "extract_pdf_first_page_words", first_page_words, raising=False)

    parser = StatementParser()

    assert parser.can_parse(StatementImportCommand(str(source), source="icbc")) is True
    assert parser.can_parse(StatementImportCommand(str(source), source="icbc-debit")) is False
    assert len(calls) == 2
    assert all(str(path) == str(source) for path, _password in calls)


def test_statement_parser_can_parse_returns_false_for_unrelated_cash_format(tmp_path):
    from ft.adapters.statement_import import StatementParser

    source = tmp_path / "statement.csv"
    source.write_text("交易时间,收/支,金额\n", encoding="utf-8")

    assert StatementParser().can_parse(
        StatementImportCommand(str(source), source="icbc")
    ) is False


def test_statement_parser_can_parse_preserves_pdf_password_error(monkeypatch, tmp_path):
    from ft.adapters.statement_import import StatementParser
    from ft.importers import pdf_tools
    from ft.importers.pdf_tools import PDFPasswordRequiredError

    source = tmp_path / "statement.pdf"
    source.write_bytes(b"%PDF-1.7")

    def password_required(_path, *, password=None):
        raise PDFPasswordRequiredError("PDF password required")

    monkeypatch.setattr(pdf_tools, "extract_pdf_first_page_words", password_required)

    try:
        StatementParser().can_parse(
            StatementImportCommand(str(source), source="icbc", password=None)
        )
    except PDFPasswordRequiredError:
        pass
    else:
        raise AssertionError("encrypted PDF probe must request a password")


def test_pdf_probe_reads_only_first_page_word_stream(monkeypatch):
    from ft.importers import pdf_tools

    class FirstPage:
        def extract_words(self, *, use_text_flow):
            assert use_text_flow is True
            return [{"text": "中国工商银行"}, {"text": "信用卡"}]

    class UnreadPage:
        def extract_words(self, **_kwargs):
            raise AssertionError("format probe must not read later pages")

    class FakePdf:
        pages = [FirstPage(), UnreadPage()]

        def close(self):
            return None

    monkeypatch.setattr(pdf_tools, "open_pdf", lambda *_args, **_kwargs: FakePdf())

    assert pdf_tools.extract_pdf_first_page_words("statement.pdf") == "中国工商银行\n信用卡"
