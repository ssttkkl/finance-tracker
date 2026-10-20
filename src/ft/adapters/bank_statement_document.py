"""一次读取银行表格，保留每次来源出现和可证明的文件声明。"""
from __future__ import annotations

import csv
from datetime import datetime, timedelta
from io import StringIO
import re

from ft.adapters.cash_statement_document import (
    CashStatementDocument, RawCashRow, StatementDocumentError,
    _MAX_COLUMNS, _MAX_INPUT_BYTES, _MAX_PARSED_BYTES, _MAX_PHYSICAL_ROWS, _MAX_ROWS,
)
from ft.domain.cash_source_matching import SourceCoverage
from ft.domain.import_time import normalize_statement_timestamp


_OLE = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"


def _one(values):
    values = set(values)
    return next(iter(values)) if len(values) == 1 else None


def _identity(values):
    value = _one(value.strip() for value in values)
    label = value if value and re.fullmatch(r"[0-9*＊]{4,128}", value) else None
    # 这两种表格没有认证遮蔽账号的全局唯一性；尾号和掩码仅供展示。
    owner = value if value and re.fullmatch(r"[0-9]{8,128}", value) else None
    return owner, label


def _date(value):
    for pattern, format_ in ((r"[0-9]{8}", "%Y%m%d"), (r"[0-9]{4}-[0-9]{2}-[0-9]{2}", "%Y-%m-%d")):
        if re.fullmatch(pattern, value):
            try:
                return datetime.strptime(value, format_)
            except ValueError:
                return None
    return None


def _coverage(source, dates, rows, *, owner, complete, currency=None):
    start, end = (_one(values) for values in dates)
    start, end = _date(start or ""), _date(end or "")
    try:
        end = end + timedelta(days=1) if end else None
    except OverflowError:
        end = None
    sequence_key = "序号" if source == "ccb_debit" else "序號"
    complete = bool(complete and owner and start and end and start < end and all(
        row.error_code is None and row.payload.get(sequence_key, "").strip() == str(index)
        for index, row in enumerate(rows, 1)))
    return SourceCoverage(
        start=normalize_statement_timestamp(start, source=source) if start else None,
        end=normalize_statement_timestamp(end, source=source) if end else None,
        complete=complete, evidence="bank-declared-range-sequence-v1" if complete else "",
        filters={"export_type": "all", **({"currency": currency} if source == "icbc_asia" else {})},
        time_precision="day" if source == "ccb_debit" else "second", strategy_version="bank-table-coverage-v1",
    )


def _collect(records, source, *, pad_trailing=False):
    from ft.importers.ccb_debit import REQUIRED_HEADERS
    from ft.importers.icbc_asia_current_account import HEADERS
    header, metadata, rows, parsed_bytes = None, [], [], 0
    for position, values in enumerate(records, 1):
        if position > _MAX_PHYSICAL_ROWS:
            raise StatementDocumentError("statement_rows_too_large")
        cells = list(values)
        if len(cells) > _MAX_COLUMNS:
            raise StatementDocumentError("statement_rows_too_large")
        parsed_bytes += sum(len(cell.encode("utf-8")) for cell in cells)
        if parsed_bytes > _MAX_PARSED_BYTES:
            raise StatementDocumentError("statement_rows_too_large")
        if header is None:
            if position > 50 or parsed_bytes > 65_536:
                raise StatementDocumentError("statement_header_not_found")
            is_header = REQUIRED_HEADERS.issubset(cells) if source == "ccb_debit" else cells[:1] == ["序號"]
            if is_header:
                if (len(set(cells)) != len(cells) or source == "ccb_debit" and not all(cells)
                    or source == "icbc_asia" and tuple(cells) != HEADERS):
                    raise StatementDocumentError("statement_header_invalid")
                header = cells
            else:
                metadata.append(cells)
            continue
        if not any(cell.strip() for cell in cells):
            continue
        if len(rows) >= _MAX_ROWS:
            raise StatementDocumentError("statement_rows_too_large")
        if pad_trailing and len(cells) < len(header):
            cells.extend([""] * (len(header) - len(cells)))
        rows.append(RawCashRow(position, dict(zip(header, cells)) if len(header) == len(cells) else None,
            None if len(header) == len(cells) else "row_shape_invalid"))
    if header is None:
        raise StatementDocumentError("statement_header_not_found")
    return metadata, tuple(rows)


def _read_ccb(content):
    import xlrd
    from ft.importers.ccb_debit import _cell_text
    if len(content) % 512:
        raise StatementDocumentError("statement_unreadable")
    diagnostics, workbook = StringIO(), None
    try:
        workbook = xlrd.open_workbook(file_contents=content, on_demand=True, logfile=diagnostics)
        sheet = workbook.sheet_by_index(0)
        if sheet.nrows > _MAX_PHYSICAL_ROWS or sheet.ncols > _MAX_COLUMNS:
            raise StatementDocumentError("statement_rows_too_large")
        metadata, rows = _collect(([_cell_text(sheet.cell_value(row, col)) for col in range(sheet.ncols)]
            for row in range(sheet.nrows)), "ccb_debit", pad_trailing=True)
        if diagnostics.getvalue():
            raise StatementDocumentError("statement_unreadable")
        cells = [cell for row in metadata for cell in row]
        titles = [re.sub(r"\s+", "", cell) for cell in cells
            if re.fullmatch(r"中国建设银行.*交易明细", re.sub(r"\s+", "", cell))]
        if not titles:
            raise StatementDocumentError("statement_source_unsupported")
        def declared(label):
            return [match.group(1).strip() for cell in cells
                for match in re.finditer(r"(?:^|\s)" + re.escape(label) + r"\s*[:：]\s*(\S*)", cell)]
        owner, label = _identity(declared("卡号/账号"))
        coverage = _coverage("ccb_debit", (declared("起始日期"), declared("结束日期")), rows,
            owner=owner, complete=_one(titles) == "中国建设银行个人活期账户全部交易明细")
        return CashStatementDocument("ccb_debit", "sheet:0", "bank-table-rows-v1", rows,
            owner, label, coverage, None, len(rows))
    finally:
        if workbook is not None:
            workbook.release_resources()
        diagnostics.close()


def _read_asia(content):
    from ft.importers.icbc_asia_current_account import CURRENCY_MAP
    try:
        text = content.decode("utf-16")
    except UnicodeError:
        raise StatementDocumentError("statement_encoding_unsupported") from None
    with StringIO(text, newline="") as stream:
        metadata, rows = _collect(csv.reader(stream, delimiter="\t", strict=True), "icbc_asia")
    declarations = {}
    for row in metadata:
        if not row:
            continue
        label = row[0].strip().rstrip(":：")
        values = [cell.strip() for cell in row[1:] if cell.strip()]
        # 只接受精确元数据标签，不能从描述文字中找一个看似账号的数字。
        declarations.setdefault(label, []).append(values)
    def scalar(*labels):
        return [" ".join(values) for label in labels for values in declarations.get(label, [])]
    if _one(scalar("銀行名稱", "银行名称")) not in {"工銀亞洲", "工银亚洲"}:
        raise StatementDocumentError("statement_source_unsupported")
    owner_values = scalar("銀行賬號", "银行账号")
    if not any(owner_values):
        owner_values = scalar("下掛賬戶", "下挂账户")
    owner, label = _identity(owner_values)
    currency = _one(CURRENCY_MAP.get(value.upper()) for value in scalar("幣種", "币种"))
    counts = scalar("總筆數", "总笔数", "交易筆數", "交易笔数")
    count = _one(int(value) if re.fullmatch(r"[0-9]{1,6}", value) else None for value in counts)
    ranges = declarations.get("日期", [])
    valid_ranges = [values for values in ranges if len(values) == 3 and values[1] in {"-", "至", "到"}]
    dates = ([values[0] for values in valid_ranges], [values[2] for values in valid_ranges])
    coverage = _coverage("icbc_asia", dates, rows, owner=owner,
        complete=bool(currency and count is not None and count == len(rows) and ranges and len(valid_ranges) == len(ranges)), currency=currency)
    return CashStatementDocument("icbc_asia", "csv:0", "bank-table-rows-v1", rows,
        owner, label, coverage, count, len(rows), source_currency=currency)


def read_bank_document(content: bytes, *, source: str | None = None) -> CashStatementDocument:
    if not isinstance(content, bytes) or not content or len(content) > _MAX_INPUT_BYTES:
        raise StatementDocumentError("statement_input_invalid")
    detected = "ccb_debit" if content.startswith(_OLE) else "icbc_asia" if content.startswith((b"\xff\xfe", b"\xfe\xff")) else None
    if detected is None or source is not None and source != detected:
        raise StatementDocumentError("statement_source_unsupported")
    try:
        return _read_ccb(content) if detected == "ccb_debit" else _read_asia(content)
    except StatementDocumentError:
        raise
    except Exception:
        raise StatementDocumentError("statement_unreadable") from None
