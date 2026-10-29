"""Export CCB XLS and ICBC Asia TSV bank document oracle cases."""
from __future__ import annotations

import argparse
import base64
from io import BytesIO
import json
from pathlib import Path

from ft.adapters.bank_statement_document import StatementDocumentError, read_bank_document

CCB_HEADER = ["序号", "交易日期", "交易时间", "交易金额", "账户余额", "对方户名", "对方账号", "摘要"]
ASIA_HEADER = ["序號", "交易時間", "", "起息日期", "業務類型", "摘要", "收入金額", "支出金額", "餘額", "對方賬號", "對方戶名", "憑證號", "匯率", "備註", "交易場所"]


def ccb_xls(rows, *, title="中国建设银行个人活期账户全部交易明细", owner="6222000011112222", count=1):
    import xlwt
    workbook = xlwt.Workbook()
    sheet = workbook.add_sheet("明细")
    for index, row in enumerate([
        ["交易明细"], [title], [f"卡号/账号：{owner}"], ["起始日期：20260801 结束日期：20260831"],
        [f"共{count}笔记录"], CCB_HEADER, *rows]):
        for column, value in enumerate(row):
            sheet.write(index, column, value)
    stream = BytesIO(); workbook.save(stream)
    return stream.getvalue()


def asia_tsv(rows, *, bank="工銀亞洲", owner="1234567890", currency="HKD", count=1, dates=("2026-08-01", "-", "2026-08-31")):
    lines = [["銀行名稱", bank], ["銀行賬號", owner], ["幣種", currency], ["總筆數", str(count)],
             ["日期", *dates], ASIA_HEADER, *rows]
    return "\r\n".join("\t".join(row) for row in lines).encode("utf-16")


def ccb_row(sequence=1, **changes):
    fields = dict(zip(CCB_HEADER, [str(sequence), "20260815", "10:00:00", "-20.00", "1000.00", "商户", "6222", "消费"]))
    fields.update(changes)
    return [fields[key] for key in CCB_HEADER]


def asia_row(sequence=1, **changes):
    fields = dict(zip(ASIA_HEADER, [str(sequence), "2026-08-15 10:00:00", "", "2026-08-15", "消费", "消费",
        "", "-20.00", "1000.00", "6222", "商户", "v1", "", "", ""]))
    fields.update(changes)
    return [fields[key] for key in ASIA_HEADER]


def cases():
    return [
        (ccb_xls([ccb_row()]), None),
        (ccb_xls([ccb_row()], title="中国建设银行其他明细"), None),
        (ccb_xls([ccb_row()], count=2), None),
        (ccb_xls([ccb_row(sequence=2)]), None),
        (ccb_xls([ccb_row()[:-1]]), None),
        (ccb_xls([ccb_row()])[:-1], None),
        (asia_tsv([asia_row()]), None),
        (asia_tsv([asia_row()], count=2), None),
        (asia_tsv([asia_row()], bank="其他银行"), None),
        (asia_tsv([asia_row()], owner="****1234"), None),
        (asia_tsv([asia_row()]), "ccb_debit"),
    ]


def export(output: Path) -> None:
    result = []
    for content, source in cases():
        try:
            document = read_bank_document(content, source=source)
            result.append({"content": base64.b64encode(content).decode(), "source": source, "ok": True,
                "document": {"source_type": document.source_type, "table_key": document.table_key,
                    "parser_version": document.parser_version,
                    "rows": [{"position": row.position, "payload": row.payload, "error_code": row.error_code}
                        for row in document.rows],
                    "account": document.source_account_identifier, "label": document.source_account_label,
                    "declared": document.declared_count, "observed": document.observed_count,
                    "currency": document.source_currency,
                    "coverage": {"start": document.coverage.start, "end": document.coverage.end,
                        "complete": document.coverage.complete, "evidence": document.coverage.evidence,
                        "filters": document.coverage.filters, "strategy_version": document.coverage.strategy_version,
                        "start_inclusive": document.coverage.start_inclusive,
                        "end_inclusive": document.coverage.end_inclusive,
                        "time_precision": document.coverage.time_precision}}})
        except StatementDocumentError as error:
            result.append({"content": base64.b64encode(content).decode(), "source": source, "ok": False,
                "error": error.code})
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    export(parser.parse_args().output)
