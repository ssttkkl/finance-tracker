"""Export ICBC PDF row normalization oracle cases."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from ft.adapters.cash_statement_document import CashStatementDocument, RawCashRow
from ft.adapters.icbc_pdf_rows import normalize_icbc_document
from ft.domain.cash_source_matching import SourceCoverage

CREDIT = ("入账日期", "交易卡号", "收支", "交易币种", "交易金额", "入账币种", "入账金额", "账户余额", "对方户名", "对方账号", "摘要", "交易场所")
DEBIT = ("交易日期", "账号", "储种", "序号", "币种", "钞汇", "摘要", "地区", "收入/支出金额", "余额", "对方户名", "对方账号", "渠道")


def credit(**changes):
    values = {"入账日期": "2026-08-15\n10:00:00", "交易卡号": "6222000000001234", "收支": "借", "交易币种": "人民币",
        "交易金额": "20.00", "入账币种": "人民币", "入账金额": "20.00", "账户余额": "1000.00",
        "对方户名": "美团支付-测试商户", "对方账号": "", "摘要": "消费", "交易场所": "门店"}
    values.update(changes)
    return {key: values[key] for key in CREDIT}


def debit(**changes):
    values = {"交易日期": "2026-08-15\n10:00:00", "账号": "1614000000001234", "储种": "活期", "序号": "00000",
        "币种": "人民币", "钞汇": "钞", "摘要": "无卡支付", "地区": "0200", "收入/支出金额": "-20.00",
        "余额": "1000.00", "对方户名": "测试商户", "对方账号": "6222****1234", "渠道": "快捷支付"}
    values.update(changes)
    return {key: values[key] for key in DEBIT}


def coverage(*, complete=True, start="2026-07-31T16:00:00+00:00", end="2026-08-31T16:00:00+00:00"):
    return SourceCoverage(start=start, end=end,
        complete=complete, evidence="icbc-pdf-range-pages-count-v1" if complete else "",
        filters={"export_type": "all"}, end_inclusive=False, strategy_version="icbc-pdf-coverage-v1",
        document_key="a" * 64, page_total=1, page_fingerprints={"1": "b" * 64})


def document(source, payloads, *, owner=None, cov=None, error_rows=()):
    rows = [RawCashRow(index + 1, dict(payload), document_position=f"p1:t0:r{index + 1}")
        for index, payload in enumerate(payloads)]
    for index, code in enumerate(error_rows, len(rows) + 1):
        rows.append(RawCashRow(index, None, code, f"p1:t0:r{index}"))
    return CashStatementDocument(source, "pdf:0", "icbc-pdf-tables-v1", tuple(rows), owner, owner,
        cov or coverage(), len(payloads), len(rows))


def cases():
    credit_cases = [
        credit(),
        credit(收支="贷", 交易金额="20.00"),
        credit(收支="unknown"),
        credit(交易金额="bad"),
        credit(入账日期="bad"),
        credit(交易币种="unknown"),
        credit(摘要="退货"),
        credit(摘要="退货", 对方户名="退货", 交易场所="原商户"),
        credit(摘要="退款", 对方户名="退款"),
        credit(摘要="减免年费"),
        credit(摘要="刷卡金入账"),
        credit(摘要="返现"),
        credit(摘要="转账", 对方账号="6222****1234"),
        credit(交易卡号="6222******1234"),
        credit(交易金额="12345678901234567890.123456789012345678"),
        credit(收支="", 交易金额="+20.00"),
        credit(交易金额="0.00"),
    ]
    debit_cases = [
        debit(),
        debit(摘要="退款", **{"收入/支出金额": "20.00"}),
        debit(摘要="退货", **{"收入/支出金额": "20.00"}),
        debit(摘要="撤销交易", **{"收入/支出金额": "20.00"}),
        debit(摘要="基金赎回", 对方户名="基金公司"),
        debit(交易日期="bad"),
        debit(**{"收入/支出金额": "bad"}),
        debit(币种="unknown"),
        debit(**{"收入/支出金额": "0.00"}),
        debit(渠道="手机银行"),
        debit(**{"收入/支出金额": "-0.00"}),
    ]
    return [
        ("credit", document("icbc_credit", credit_cases)),
        ("debit", document("icbc_debit", debit_cases, owner="6212000000000001234")),
        ("debit-no-owner", document("icbc_debit", [debit()], owner=None)),
        ("outside-coverage", document("icbc_debit", [debit()], owner="6212000000000001234",
            cov=coverage(start="2026-09-01T00:00:00+00:00", end="2026-09-30T00:00:00+00:00"))),
        ("error-rows", document("icbc_debit", [debit()], owner="6212000000000001234",
            error_rows=("row_shape_invalid", ""))),
        ("unsupported", document("ccb_debit", [])),
    ]


def export(output: Path) -> None:
    result = []
    for name, document_value in cases():
        try:
            parsed = normalize_icbc_document(document_value)
        except ValueError as failure:
            result.append({"name": name, "ok": False, "error": str(failure)})
            continue
        result.append({"name": name, "ok": True, "input": {
            "source_type": document_value.source_type,
            "table_key": document_value.table_key, "parser_version": document_value.parser_version,
            "rows": [{"position": row.position, "payload": row.payload, "error_code": row.error_code,
                "document_position": row.document_position} for row in document_value.rows],
            "coverage": {"complete": document_value.coverage.complete, "evidence": document_value.coverage.evidence,
                "start": document_value.coverage.start, "end": document_value.coverage.end,
                "filters": document_value.coverage.filters, "strategy_version": document_value.coverage.strategy_version,
                "start_inclusive": document_value.coverage.start_inclusive,
                "end_inclusive": document_value.coverage.end_inclusive,
                "time_precision": document_value.coverage.time_precision,
                "document_key": document_value.coverage.document_key,
                "page_total": document_value.coverage.page_total,
                "page_fingerprints": document_value.coverage.page_fingerprints},
            "owner": document_value.source_account_identifier,
            "label": document_value.source_account_label,
            "declared": document_value.declared_count, "observed": document_value.observed_count,
        }, "document": {
            "source_type": parsed.source_type, "table_key": parsed.table_key,
            "parser_version": parsed.parser_version,
            "account": parsed.source_account_identifier, "label": parsed.source_account_label,
            "declared": parsed.declared_count, "observed": parsed.observed_count,
            "coverage": {"start": parsed.coverage.start, "end": parsed.coverage.end,
                "complete": parsed.coverage.complete, "evidence": parsed.coverage.evidence,
                "filters": parsed.coverage.filters, "strategy_version": parsed.coverage.strategy_version,
                "start_inclusive": parsed.coverage.start_inclusive,
                "end_inclusive": parsed.coverage.end_inclusive,
                "document_key": parsed.coverage.document_key,
                "page_total": parsed.coverage.page_total,
                "page_fingerprints": parsed.coverage.page_fingerprints},
            "rows": [{"position": row.position, "fields": row.fields, "payment_method": row.payment_method,
                "issue_codes": list(row.issue_codes), "error_code": row.error_code,
                "disposition": row.disposition, "source_account_identifier": row.source_account_identifier,
                "document_position": row.document_position, "source_explanation": row.source_explanation}
                for row in parsed.rows],
        }})
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    export(parser.parse_args().output)
