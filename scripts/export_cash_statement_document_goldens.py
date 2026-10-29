"""Export platform CSV/TSV statement document oracle cases."""
from __future__ import annotations

import argparse
import base64
from io import BytesIO
import re
from zipfile import ZipFile, ZIP_DEFLATED
import csv
from io import StringIO
import json
from pathlib import Path

from ft.adapters.cash_statement_document import StatementDocumentError, read_platform_document

ALIPAY_HEADER = ["交易时间", "交易分类", "交易对方", "对方账号", "商品说明", "收/支", "金额", "收/付款方式", "交易状态", "交易订单号", "商家订单号"]
WECHAT_HEADER = ["交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)", "支付方式", "当前状态", "交易单号", "商户单号", "备注"]


def alipay_csv(rows, *, count=1, export_type="全部", account="fixture-owner@example.test", delimiter=","):
    output = StringIO()
    writer = csv.writer(output, delimiter=delimiter)
    writer.writerow(["支付宝交易记录明细查询"])
    writer.writerow([f"支付宝账户：[{account}]"])
    writer.writerow(["起始时间：[2026-08-01 00:00:00] 终止时间：[2026-08-31 23:59:59]"])
    if export_type is not None:
        writer.writerow([f"交易类型：[{export_type}]"])
    if count is not None:
        writer.writerow([f"共{count}笔记录"])
    writer.writerow(ALIPAY_HEADER)
    writer.writerows(rows)
    return output.getvalue().encode("utf-8-sig")


def wechat_csv(rows, *, count=1, export_type="全部", nickname="测试昵称"):
    output = StringIO()
    writer = csv.writer(output)
    for row in [["微信支付账单明细"], [f"微信昵称：[{nickname}]"], ["起始时间：[2026-08-01 00:00:00]"],
                ["终止时间：[2026-08-31 23:59:59]"], [f"导出类型：[{export_type}]"], [f"共{count}笔记录"],
                ["说明：请核对申请导出账单的微信号，不代表本人微信号字段"], WECHAT_HEADER, *rows]:
        writer.writerow(row)
    return output.getvalue().encode("utf-8-sig")


def row(header, **changes):
    fields = dict(zip(header, ["2026-08-15 10:00:00", "日用百货", "测试商户", "account", "  原始描述\n第二行  ",
        "支出", "20.00", "测试银行卡", "交易成功", "0000123", "merchant-1"]))
    fields.update(changes)
    return [fields[key] for key in header]


def xlsx_bytes(rows, *, count=1, export_type="全部", nickname="测试昵称"):
    import openpyxl
    workbook = openpyxl.Workbook()
    sheet = workbook.active
    for row in [["微信支付账单明细"], [f"微信昵称：[{nickname}]"], ["起始时间：[2026-08-01 00:00:00]"],
                ["终止时间：[2026-08-31 23:59:59]"], [f"导出类型：[{export_type}]"], [f"共{count}笔记录"],
                ["说明：请核对申请导出账单的微信号，不代表本人微信号字段"], WECHAT_HEADER, *rows]:
        sheet.append(row)
    stream = BytesIO(); workbook.save(stream); workbook.close()
    return stream.getvalue()


def rewrite_xlsx(content, change):
    source = BytesIO(content); target = BytesIO()
    with ZipFile(source) as archive, ZipFile(target, "w", ZIP_DEFLATED) as output:
        for member in archive.infolist():
            output.writestr(member, change(member.filename, archive.read(member.filename)))
    return target.getvalue()


def cases():
    alipay = row(ALIPAY_HEADER)
    wechat = row(WECHAT_HEADER, **{"金额(元)": "30.00", "交易单号": "wx-1"})
    return [
        (alipay_csv([alipay, alipay], count=2), "alipay"),
        (wechat_csv([wechat], count=1), "wechat"),
        (alipay_csv([alipay], count=1, delimiter="\t"), "alipay"),
        (alipay_csv([alipay], count=1, export_type=None), "alipay"),
        (alipay_csv([row(ALIPAY_HEADER, **{"金额": "invalid"})], count=1), "alipay"),
        (alipay_csv([alipay], count=2), "alipay"),
        (alipay_csv([alipay[:-1]], count=1), "alipay"),
        (alipay_csv([alipay], count=1).decode("utf-8-sig").encode("gb18030"), "alipay"),
        (b"", "alipay"),
        (b"not a statement", "alipay"),
        (alipay_csv([alipay], count=1), "unknown"),
        (xlsx_bytes([wechat], count=1), "wechat"),
        (xlsx_bytes([wechat[:-1]], count=1), "wechat"),
        (xlsx_bytes([wechat], count=2), "wechat"),
        (rewrite_xlsx(xlsx_bytes([wechat]), lambda name, body: body.replace(b"</sheetData>", b'<row r="200001"></row></sheetData>') if name == "xl/worksheets/sheet1.xml" else body), "wechat"),
        (rewrite_xlsx(xlsx_bytes([wechat]), lambda name, body: body.replace(b"</sheetData>", b'<row r="10"><c r="ZZZZZZ10" t="n"><v>1</v></c></row></sheetData>') if name == "xl/worksheets/sheet1.xml" else body), "wechat"),
        (rewrite_xlsx(xlsx_bytes([wechat]), lambda name, body: b'<!DOCTYPE worksheet [<!ENTITY x "unsafe">]>' + body if name == "xl/worksheets/sheet1.xml" else body), "wechat"),
        (rewrite_xlsx(xlsx_bytes([wechat]), lambda name, body: b"not xml" if name == "xl/worksheets/sheet1.xml" else body), "wechat"),
        (rewrite_xlsx(xlsx_bytes([wechat]), lambda name, body: body.replace(b'<dimension ref="A1:L10"', b'<dimension ref="A1:A1"') if name == "xl/worksheets/sheet1.xml" else body), "wechat"),
    ]


def export(output: Path) -> None:
    result = []
    for content, source in cases():
        try:
            document = read_platform_document(content, source=source)
            result.append({"content": base64.b64encode(content).decode(), "source": source, "ok": True,
                "document": {"source_type": document.source_type, "table_key": document.table_key,
                    "parser_version": document.parser_version,
                    "rows": [{"position": row.position, "payload": row.payload, "error_code": row.error_code}
                        for row in document.rows],
                    "account": document.source_account_identifier, "label": document.source_account_label,
                    "declared": document.declared_count, "observed": document.observed_count,
                    "coverage": {"start": document.coverage.start, "end": document.coverage.end,
                        "complete": document.coverage.complete, "evidence": document.coverage.evidence,
                        "filters": document.coverage.filters, "strategy_version": document.coverage.strategy_version,
                        "start_inclusive": document.coverage.start_inclusive,
                        "end_inclusive": document.coverage.end_inclusive}}})
        except StatementDocumentError as error:
            result.append({"content": base64.b64encode(content).decode(), "source": source, "ok": False,
                "error": error.code})
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    export(parser.parse_args().output)
