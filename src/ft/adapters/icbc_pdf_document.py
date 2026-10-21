"""一次打开工行原生 PDF 表格；仅返回来源行和经过核对的声明。"""
from __future__ import annotations

from datetime import datetime, timedelta
from io import BytesIO
import re

from ft.adapters.cash_statement_document import (
    CashStatementDocument, RawCashRow, StatementDocumentError,
    _MAX_COLUMNS, _MAX_INPUT_BYTES, _MAX_PARSED_BYTES, _MAX_PHYSICAL_ROWS, _MAX_ROWS,
)
from ft.domain.cash_source_matching import SourceCoverage
from ft.domain.import_time import normalize_statement_timestamp
from ft.importers.pdf_tools import _raise_pdf_password_error


CREDIT_HEADERS = ("入账日期", "交易卡号", "收支", "交易币种", "交易金额", "入账币种", "入账金额", "账户余额", "对方户名", "对方账号", "摘要", "交易场所")
DEBIT_HEADERS = ("交易日期", "账号", "储种", "序号", "币种", "钞汇", "摘要", "地区", "收入/支出金额", "余额", "对方户名", "对方账号", "渠道")
_MAX_PDF_PAGES = 2000
_MAX_PDF_CHARS = 5_000_000
_MAX_TABLES_PER_PAGE = 16


def _compact(value):
    return re.sub(r"\s+", "", value)


def _one(values):
    values = set(values)
    return next(iter(values)) if len(values) == 1 else None


def full_card(value):
    from ft.convert import _normalize_icbc_account_identifier
    value = _normalize_icbc_account_identifier(value)
    return value if re.fullmatch(r"[0-9]{12,19}", value) else None


def _pdf_pages(pdf):
    # PDF.pages 会先构造全部 Page；在构造前按实际页流实施上限。
    from pdfminer.pdfpage import PDFPage
    from pdfplumber.page import Page
    doctop = 0
    for number, source in enumerate(PDFPage.create_pages(pdf.doc), 1):
        if number > _MAX_PDF_PAGES:
            raise StatementDocumentError("statement_rows_too_large")
        page = Page(pdf, source, page_number=number, initial_doctop=doctop)
        doctop += page.height
        yield page


def _coverage(texts, page_counts, page_numbers, rows, *, source, complete):
    ranges, counts, totals, orders, filters = [], [], [], [], []
    date = r"(?:[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{8})"
    for text in texts:
        text = _compact(text)
        ranges.append(_one(re.findall(r"起止(?:交易)?日期[:：]?(" + date + r")[至—–~～-]+(" + date + r")", text)))
        counts.append(_one(int(value.replace(",", "")) for value in re.findall(r"本页交易笔数[:：]([0-9][0-9,]{0,11})", text)))
        pages = re.findall(r"共([0-9]{1,6})页第([0-9]{1,6})页", text)
        totals.append(_one(int(value[0]) for value in pages))
        orders.append(_one(int(value[1]) for value in pages))
        filters.extend(re.findall(r"(?:交易类型|收支类型|交易筛选)[:：]([^，,;；\n]+)", text))
    interval = _one(ranges)
    start = end = None
    if interval:
        try:
            start, end = (datetime.strptime(value.replace("-", ""), "%Y%m%d") for value in interval)
            end += timedelta(days=1)
        except (ValueError, OverflowError):
            start = end = None
    declared = sum(counts) if counts and all(count is not None for count in counts) else None
    complete = bool(complete and start and end and start < end and counts == page_counts
        and orders == page_numbers and all(total == len(texts) for total in totals)
        and declared == len(rows) and all(value in {"全部", "全部交易", "全部类型", "所有"} for value in filters))
    return declared, SourceCoverage(
        start=normalize_statement_timestamp(start, source=source) if start else None,
        end=normalize_statement_timestamp(end, source=source) if end else None,
        complete=complete, evidence="icbc-pdf-range-pages-count-v1" if complete else "",
        filters={"export_type": "all"}, strategy_version="icbc-pdf-coverage-v1",
    )


def read_icbc_document(content: bytes, *, source: str | None = None, password: str | None = None) -> CashStatementDocument:
    if not isinstance(content, bytes) or not content.startswith(b"%PDF-") or len(content) > _MAX_INPUT_BYTES:
        raise StatementDocumentError("statement_input_invalid")
    if source not in {None, "icbc", "icbc_credit", "icbc_debit"}:
        raise StatementDocumentError("statement_source_unsupported")
    import pdfplumber
    rows, texts, page_counts, page_numbers = [], [], [], []
    detected, physical, parsed_bytes, chars, complete = None, 0, 0, 0, True
    with BytesIO(content) as stream:
        pdf = None
        try:
            pdf = pdfplumber.open(stream, password=password)
            for page_number, page in enumerate(_pdf_pages(pdf), 1):
                filtered = None
                try:
                    if page_number > _MAX_PDF_PAGES:
                        raise StatementDocumentError("statement_rows_too_large")
                    page_chars = page.chars
                    chars += len(page_chars)
                    if chars > _MAX_PDF_CHARS:
                        raise StatementDocumentError("statement_rows_too_large")
                    brand = _compact("".join(str(char.get("text") or "") for char in page_chars))
                    if "中国工商银行" not in brand:
                        raise StatementDocumentError("statement_source_unsupported")
                    filtered = page.filter(lambda obj: obj.get("object_type") != "char" or obj.get("size", 99) < 15)
                    text = filtered.extract_text() or ""
                    parsed_bytes += len(text.encode("utf-8"))
                    if parsed_bytes > _MAX_PARSED_BYTES:
                        raise StatementDocumentError("statement_rows_too_large")
                    texts.append(text)
                    page_numbers.append(page_number)
                    tables = filtered.extract_tables()
                    if not tables:
                        raise StatementDocumentError("statement_header_not_found")
                    if len(tables) > _MAX_TABLES_PER_PAGE:
                        raise StatementDocumentError("statement_rows_too_large")
                    before = len(rows)
                    for table in tables:
                        if not table or not table[0] or len(table[0]) > _MAX_COLUMNS:
                            raise StatementDocumentError("statement_header_invalid")
                        header = tuple(str(cell or "") for cell in table[0])
                        keys = tuple(_compact(cell) for cell in header)
                        kind = "icbc_credit" if keys == CREDIT_HEADERS else "icbc_debit" if keys == DEBIT_HEADERS else None
                        if kind is None:
                            raise StatementDocumentError("statement_header_invalid")
                        if detected and detected != kind or source not in {None, "icbc", kind}:
                            raise StatementDocumentError("statement_source_unsupported")
                        detected = kind
                        physical += 1
                        for values in table[1:]:
                            physical += 1
                            if physical > _MAX_PHYSICAL_ROWS:
                                raise StatementDocumentError("statement_rows_too_large")
                            values = [str(cell) if cell is not None else "" for cell in values] if values else []
                            if not any(cell.strip() for cell in values):
                                continue
                            if len(rows) >= _MAX_ROWS:
                                raise StatementDocumentError("statement_rows_too_large")
                            payload = dict(zip(header, values, strict=True)) if len(values) == len(header) else None
                            parsed_bytes += sum(len(value.encode("utf-8")) for value in values)
                            if parsed_bytes > _MAX_PARSED_BYTES:
                                raise StatementDocumentError("statement_rows_too_large")
                            rows.append(RawCashRow(physical, payload, None if payload is not None else "row_shape_invalid"))
                            complete = complete and payload is not None
                    page_counts.append(len(rows) - before)
                finally:
                    if filtered is not None and filtered is not page:
                        filtered.close()
                    page.close()
            if detected is None:
                raise StatementDocumentError("statement_header_not_found")
        except StatementDocumentError:
            raise
        except Exception as error:
            _raise_pdf_password_error(error, password)
            raise StatementDocumentError("statement_unreadable") from None
        finally:
            if pdf is not None:
                pdf.close()
    owner = None
    if detected == "icbc_debit":
        from ft.convert import _extract_icbc_debit_card_identifier
        headers = []
        for text in texts:
            boundary = re.search(r"交\s*易\s*日\s*期", text)
            if boundary:
                headers.extend(text[:boundary.start()].splitlines())
        try:
            owner = full_card(_extract_icbc_debit_card_identifier(headers))
        except ValueError:
            pass
    complete = complete and bool(re.search(rb"startxref\s+[0-9]+\s*%%EOF\s*$", content))
    declared, coverage = _coverage(texts, page_counts, page_numbers, rows, source=detected, complete=complete)
    return CashStatementDocument(detected, "pdf:0", "icbc-pdf-tables-v1", tuple(rows), owner, owner, coverage, declared, len(rows))
