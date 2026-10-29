"""Export ICBC credit/debit PDF document oracle cases."""
from __future__ import annotations

import argparse
import base64
from io import BytesIO
import json
from pathlib import Path

from ft.adapters.cash_statement_document import StatementDocumentError
from ft.adapters.icbc_pdf_document import read_icbc_document
from ft.importers.pdf_tools import PDFPasswordInvalidError, PDFPasswordRequiredError

CREDIT = ("入账日期", "交易卡号", "收支", "交易币种", "交易金额", "入账币种", "入账金额", "账户余额", "对方户名", "对方账号", "摘要", "交易场所")
DEBIT = ("交易日期", "账号", "储种", "序号", "币种", "钞汇", "摘要", "地区", "收入/支出金额", "余额", "对方户名", "对方账号", "渠道")
OWNER = "6212000000000001234"
STAMP = "2026-09-01 10:20:30"


CREDIT_WIDTHS = (105, 95, 30, 45, 55, 45, 55, 55, 95, 70, 45, 45)
DEBIT_WIDTHS = (90, 80, 35, 35, 40, 25, 70, 40, 70, 55, 80, 70, 50)


def _font(canvas):
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.cidfonts import UnicodeCIDFont
    name = "STSong-Light"
    try:
        pdfmetrics.getFont(name)
    except Exception:
        pdfmetrics.registerFont(UnicodeCIDFont(name))
    return name


def _widths(header):
    return CREDIT_WIDTHS if len(header) == len(CREDIT) else DEBIT_WIDTHS


def _line(canvas, x0, y0, x1, y1, curve):
    if not curve:
        canvas.line(x0, y0, x1, y1)
        return
    path = canvas.beginPath()
    path.moveTo(x0, y0)
    path.curveTo(x0 + (x1 - x0) / 3, y0 + (y1 - y0) / 3,
        x0 + (x1 - x0) * 2 / 3, y0 + (y1 - y0) * 2 / 3, x1, y1)
    canvas.drawPath(path)


def _table(canvas, font, left, top, header, rows, widths, merged=(), merged_h=(), curve=False):
    x_positions = [left]
    for width in widths:
        x_positions.append(x_positions[-1] + width)
    for row_index in range(len(rows) + 1):
        y_top = top - 24 * row_index
        for boundary, x in enumerate(x_positions):
            if (row_index, boundary) not in merged:
                _line(canvas, x, y_top, x, y_top - 24, curve)
    for boundary in range(len(rows) + 2):
        y = top - 24 * boundary
        for column in range(len(widths)):
            if (boundary, column) not in merged_h:
                _line(canvas, x_positions[column], y, x_positions[column + 1], y, curve)
    x = left
    for column, value in enumerate(header):
        for index, line in enumerate(str(value).split("\n")) if value else ():
            canvas.setFont(font, 7)
            canvas.drawString(x + 2, top - 7 - index * 8, line)
        x += widths[column]
    for row_index, values in enumerate(rows, 1):
        x = left
        for column, value in enumerate(values):
            if value:
                for index, line in enumerate(str(value).split("\n")):
                    canvas.setFont(font, 7)
                    canvas.drawString(x + 2, top - 24 * row_index - 7 - index * 8, line)
            x += widths[column]


def _page(canvas, header, rows, *, kind="credit", number=1, total=1, count=None, bank="中国工商银行",
          owner=OWNER, dates=("2026-08-01", "2026-08-31"), stamp=STAMP, filters=None, card_line=True,
          table=True, header_override=None, width_override=None, second_table=None, scale=None,
          merged=(), merged_h=(), box=None, curve=False):
    font = _font(canvas)
    canvas.setFont(font, 10)
    canvas.drawString(30, 570, bank)
    if card_line:
        canvas.drawString(30, 556, f"卡号：{owner} 户名：测试用户")
    range_text = "" if dates is None else f"起止日期：{dates[0]} - {dates[1]} "
    declared = len(rows) if count is None else count
    canvas.drawString(30, 542, f"{range_text}本页交易笔数：{declared} 共 {total} 页 第 {number} 页")
    if stamp:
        canvas.drawString(200, 30, f"下单时间：{stamp}")
    if filters is not None:
        canvas.drawString(500, 30, f"交易类型：{filters}")
    if not table:
        return
    headers = header_override or header
    widths = width_override or _widths(headers)
    if scale is not None:
        canvas.saveState()
        canvas.translate(*scale[1])
        canvas.scale(scale[0], scale[0])
    _table(canvas, font, 30, 500, headers, rows, widths, merged, merged_h, curve)
    if box is not None:
        canvas.rect(box[0], box[1], box[2], box[3], stroke=1, fill=0)
        canvas.setFont(font, 10)
        canvas.drawString(box[0] + 10, box[1] + 12, "结单")
    if second_table:
        _table(canvas, font, 30, 400, second_table["header"], second_table["rows"],
            second_table.get("widths") or _widths(second_table["header"]))
    if scale is not None:
        canvas.restoreState()


def credit_row(**changes):
    fields = dict(zip(CREDIT, ("2026-08-15\n10:00:00", "6222000000001234", "借", "人民币", "20.00", "人民币", "20.00",
        "1000.00", "美团支付-测试商户", "", "消费", "门店")))
    fields.update(changes)
    return [fields[key] for key in CREDIT]


def debit_row(**changes):
    fields = dict(zip(DEBIT, ("2026-08-15\n10:00:00", "1614000000001234", "活期", "00000", "人民币", "钞", "无卡支付", "0200",
        "-20.00", "1000.00", "测试商户", "6222****1234", "快捷支付")))
    fields.update(changes)
    return [fields[key] for key in DEBIT]


def pdf(pages, *, password=None):
    from reportlab.lib.pagesizes import A4, landscape
    from reportlab.pdfgen import canvas as pdfcanvas
    encrypt = None
    if password is not None:
        from reportlab.lib import pdfencrypt
        encrypt = pdfencrypt.StandardEncryption(password, None, strength=128)
    stream = BytesIO()
    painter = pdfcanvas.Canvas(stream, pagesize=landscape(A4), invariant=1, encrypt=encrypt)
    for page in pages:
        _page(painter, **page)
        painter.showPage()
    painter.save()
    return stream.getvalue()


def encrypted(content, password):
    import os
    from pypdf import PdfReader, PdfWriter
    reader = PdfReader(BytesIO(content))
    writer = PdfWriter()
    for page in reader.pages:
        writer.add_page(page)
    writer.encrypt(password)
    original = os.urandom
    os.urandom = lambda size: bytes(size)
    try:
        stream = BytesIO()
        writer.write(stream)
    finally:
        os.urandom = original
    return stream.getvalue()


def cases():
    result = []
    credit = {"header": CREDIT, "rows": [credit_row()]}
    debit = {"header": DEBIT, "rows": [debit_row()], "kind": "debit"}
    result += [
        ("credit", pdf([credit]), None, None),
        ("debit", pdf([debit]), None, None),
        ("debit-no-owner", pdf([{**debit, "card_line": False}]), None, None),
        ("debit-hint", pdf([debit]), "icbc", None),
        ("credit-hint", pdf([credit]), "icbc_credit", None),
        ("debit-hint-mismatch", pdf([debit]), "icbc_credit", None),
        ("credit-hint-mismatch", pdf([credit]), "icbc_debit", None),
        ("source-unknown", pdf([credit]), "other", None),
        ("two-pages", pdf([{**debit, "number": 1, "total": 2}, {**debit, "number": 2, "total": 2}]), None, None),
        ("two-pages-owner-conflict", pdf([{**debit, "number": 1, "total": 2},
            {**debit, "number": 2, "total": 2, "owner": "6212000000000005678"}]), None, None),
        ("two-pages-range-conflict", pdf([{**debit, "number": 1, "total": 2},
            {**debit, "number": 2, "total": 2, "dates": ("2026-08-01", "2026-09-30")}]), None, None),
        ("count-mismatch", pdf([{**credit, "count": 2}]), None, None),
        ("page-count-mismatch", pdf([{**credit, "total": 2}]), None, None),
        ("page-order-mismatch", pdf([{**credit, "number": 2}]), None, None),
        ("missing-range", pdf([{**credit, "dates": None}]), None, None),
        ("filter-mismatch", pdf([{**credit, "filters": "消费"}]), None, None),
        ("filter-all", pdf([{**credit, "filters": "全部"}]), None, None),
        ("header-invalid", pdf([{**credit, "header_override": tuple("列" + str(index) for index in range(12))}]), None, None),
        ("empty-header", pdf([{**credit, "header_override": ("",) * 12}]), None, None),
        ("no-table", pdf([{**credit, "table": False}]), None, None),
        ("other-bank", pdf([{**credit, "bank": "其他银行"}]), None, None),
        ("wrapped-header", pdf([{**credit,
            "header_override": tuple("收\n支" if value == "收支" else value for value in CREDIT),
            "rows": [credit_row(**{"收\n支": "借", "摘要": "  原始说明  "})]}]), None, None),
        ("multi-table", pdf([{**credit, "rows": [credit_row()], "count": 2,
            "second_table": {"header": CREDIT, "rows": [credit_row(摘要="第二表")]}}]), None, None),
        ("credit-euro", pdf([{**credit, "rows": [credit_row(交易币种="欧元")]}]), None, None),
        ("special-chars", pdf([{**credit, "rows": [credit_row(摘要='quote"slash\\ line')]}]), None, None),
        ("scaled-table", pdf([{**credit, "scale": (0.8, (20, 10))}]), None, None),
        ("decorative-box", pdf([{**credit, "box": (600, 150, 120, 60)}]), None, None),
        ("merged-cell", pdf([{**credit, "rows": [credit_row(对方户名="合并单元格")], "merged": [(1, 9)]}]), None, None),
        ("curve-table", pdf([{**credit, "curve": True}]), None, None),
        ("merged-rows", pdf([{**credit, "rows": [credit_row(钞汇="合并"), credit_row(摘要="第二行")],
            "merged_h": [(2, 5)]}]), None, None),
        ("empty", b"", None, None),
        ("not-pdf", b"not a statement", None, None),
        ("pdf-no-pages", pdf([]), None, None),
        ("trailer-missing", pdf([credit]).replace(b"%%EOF\n", b""), None, None),
        ("startxref-broken", pdf([credit]).replace(b"startxref", b"xstartxref"), None, None),
        ("password-required", encrypted(pdf([credit]), "secret"), None, None),
        ("password-invalid", encrypted(pdf([credit]), "secret"), None, "wrong"),
        ("password-ok", encrypted(pdf([credit]), "secret"), None, "secret"),
    ]
    return result


def export(output: Path) -> None:
    result = []
    for name, content, source, password in cases():
        entry = {"name": name, "content": base64.b64encode(content).decode(), "source": source, "password": password}
        try:
            document = read_icbc_document(content, source=source, password=password)
            entry.update(ok=True, document={
                "source_type": document.source_type, "table_key": document.table_key,
                "parser_version": document.parser_version,
                "rows": [{"position": row.position, "payload": row.payload, "error_code": row.error_code,
                    "document_position": row.document_position} for row in document.rows],
                "account": document.source_account_identifier, "label": document.source_account_label,
                "declared": document.declared_count, "observed": document.observed_count,
                "currency": document.source_currency,
                "coverage": {"start": document.coverage.start, "end": document.coverage.end,
                    "complete": document.coverage.complete, "evidence": document.coverage.evidence,
                    "filters": document.coverage.filters, "strategy_version": document.coverage.strategy_version,
                    "start_inclusive": document.coverage.start_inclusive,
                    "end_inclusive": document.coverage.end_inclusive,
                    "time_precision": document.coverage.time_precision,
                    "document_key": document.coverage.document_key,
                    "page_total": document.coverage.page_total,
                    "page_fingerprints": document.coverage.page_fingerprints}})
        except (PDFPasswordRequiredError, PDFPasswordInvalidError) as error:
            entry.update(ok=False, password_error="required" if isinstance(error, PDFPasswordRequiredError) else "invalid")
        except StatementDocumentError as error:
            entry.update(ok=False, error=error.code)
        result.append(entry)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    export(parser.parse_args().output)
