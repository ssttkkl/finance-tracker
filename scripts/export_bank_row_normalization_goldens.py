"""Export bank statement row normalization oracle cases."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from ft.adapters.cash_statement_document import CashStatementDocument, RawCashRow
from ft.adapters.cash_statement_rows import normalize_bank_document
from ft.domain.cash_source_matching import SourceCoverage

CCB = {"序号": "1", "交易日期": "20260815", "交易时间": "10:00:00", "交易金额": "-20.00",
    "账户余额": "1000.00", "对方户名": "对方", "对方账号": "6222", "摘要": "消费",
    "交易地点/附言": "支付宝-淘宝-测试商户", "对方账号与户名": "6222000011111234/测试商户",
    "币别": "人民币"}
ASIA = {"序號": "1", "交易時間": "2026-08-15", "": "10:00:00", "起息日期": "2026-08-15",
    "業務類型": "消费", "摘要": "消费", "收入金額": "", "支出金額": "20.00", "餘額": "1000.00",
    "對方賬號": "6222****1234", "對方戶名": "测试商户", "憑證號": "v1", "匯率": "", "備註": "备注",
    "交易場所": "门店"}


def coverage(*, complete=True, start="2026-07-31T16:00:00+00:00", end="2026-08-31T16:00:00+00:00"):
    return SourceCoverage(start=start, end=end, complete=complete,
        evidence="bank-declared-range-sequence-v1" if complete else "", filters={"export_type": "all"},
        start_inclusive=True, end_inclusive=False, strategy_version="bank-table-coverage-v1")


def document(source, payloads, *, owner="6222000011112222", currency=None, cov=None, error_rows=()):
    rows = [RawCashRow(index + 1, dict(payload)) for index, payload in enumerate(payloads)]
    for code in error_rows:
        rows.append(RawCashRow(len(rows) + 1, None, code))
    return CashStatementDocument(source, "sheet:0", "bank-table-rows-v1", tuple(rows), owner,
        owner, cov or coverage(), len(payloads), len(rows), source_currency=currency)


def cases():
    ccb = [
        dict(CCB),
        {**CCB, "交易金额": "20.00", "摘要": "退款", "交易地点/附言": "财付通-微信支付-商户"},
        {**CCB, "交易地点/附言": "美团支付-商户", "币别": "美元"},
        {**CCB, "交易地点/附言": "***"},
        {**CCB, "交易地点/附言": "银行转证券8888086011314150转入086"},
        {**CCB, "交易金额": "bad"},
        {**CCB, "交易日期": "2026-08-15"},
        {**CCB, "币别": "unknown"},
        {**CCB, "对方账号与户名": "无账号户名"},
        {**CCB, "交易金额": "0.00"},
        {**CCB, "交易地点/附言": "PAYPAL_SHOP"},
        {**CCB, "交易日期": "20261301"},
        {**CCB, "交易日期": "20260230"},
        {**CCB, "币别": "人民币元"},
        {**CCB, "币别": "港币"},
        {**CCB, "币别": "日元"},
        {**CCB, "交易地点/附言": "消费-支付宝-消费-商户"},
        {**CCB, "交易地点/附言": "支付宝-"},
        {**CCB, "交易金额": "20.00", "摘要": "退货"},
        {**CCB, "交易金额": "-20.00", "摘要": "退款"},
        {**CCB, "交易金额": "12345678901234567890.123456789012345678"},
        {**CCB, "交易金额": "-0.00"},
    ]
    asia = [
        dict(ASIA),
        {**ASIA, "收入金額": "20.00", "支出金額": ""},
        {**ASIA, "收入金額": "20.00"},
        {**ASIA, "收入金額": "", "支出金額": ""},
        {**ASIA, "支出金額": "bad"},
        {**ASIA, "交易時間": "bad"},
        {**ASIA, "支出金額": "0.00", "業務類型": "新開戶"},
        {**ASIA, "對方賬號": "6222********1234"},
        {**ASIA, "交易時間": "2026-13-01"},
        {**ASIA, "業務類型": "转账"},
        {**ASIA, "業務類型": "提现"},
        {**ASIA, "支出金額": "0.00"},
    ]
    return [
        ("ccb", document("ccb_debit", ccb)),
        ("asia", document("icbc_asia", asia, owner="1234567890", currency="HKD")),
        ("asia-no-currency", document("icbc_asia", [ASIA], owner="1234567890", currency=None)),
        ("asia-outside-coverage", document("icbc_asia", [ASIA], owner="1234567890", currency="HKD",
            cov=coverage(start="2026-09-01T00:00:00+00:00", end="2026-09-30T00:00:00+00:00"))),
        ("error-rows", document("ccb_debit", [CCB], error_rows=("row_shape_invalid", ""))),
        ("ccb-short-owner", document("ccb_debit", [CCB], owner="12")),
        ("asia-short-owner", document("icbc_asia", [ASIA], owner="12", currency="HKD")),
        ("asia-reconstructed", document("icbc_asia", [{**ASIA, "對方賬號": "879825****47"}],
            owner="879825074240", currency="HKD")),
        ("ccb-no-owner", document("ccb_debit", [CCB], owner="")),
        ("asia-no-owner", document("icbc_asia", [ASIA], owner="", currency="HKD")),
        ("coverage-start-inclusive", document("icbc_asia", [ASIA], owner="1234567890", currency="HKD",
            cov=coverage(start="2026-08-15T02:00:00+00:00", end="2026-08-15T03:00:00+00:00"))),
        ("coverage-end-exclusive", document("icbc_asia", [ASIA], owner="1234567890", currency="HKD",
            cov=coverage(start="2026-08-15T01:00:00+00:00", end="2026-08-15T02:00:00+00:00"))),
        ("coverage-empty-evidence", document("icbc_asia", [ASIA], owner="1234567890", currency="HKD",
            cov=SourceCoverage(start="2026-07-31T16:00:00+00:00", end="2026-08-31T16:00:00+00:00",
                complete=True, evidence="", filters={"export_type": "all"}, start_inclusive=True,
                end_inclusive=False, strategy_version="bank-table-coverage-v1"))),
        ("unsupported", document("alipay", [])),
    ]


def export(output: Path) -> None:
    result = []
    for name, document_value in cases():
        try:
            parsed = normalize_bank_document(document_value)
        except ValueError as failure:
            result.append({"name": name, "ok": False, "error": str(failure)})
            continue
        result.append({"name": name, "ok": True, "input": {
            "source_type": document_value.source_type,
            "table_key": document_value.table_key, "parser_version": document_value.parser_version,
            "rows": [{"position": row.position, "payload": row.payload, "error_code": row.error_code}
                for row in document_value.rows],
            "coverage": {"complete": document_value.coverage.complete, "evidence": document_value.coverage.evidence,
                "start": document_value.coverage.start, "end": document_value.coverage.end,
                "filters": document_value.coverage.filters, "strategy_version": document_value.coverage.strategy_version,
                "start_inclusive": document_value.coverage.start_inclusive,
                "end_inclusive": document_value.coverage.end_inclusive},
            "owner": document_value.source_account_identifier,
            "label": document_value.source_account_label,
            "currency": document_value.source_currency,
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
                "end_inclusive": parsed.coverage.end_inclusive},
            "rows": [{"position": row.position, "fields": row.fields, "payment_method": row.payment_method,
                "provider_id": row.provider_id, "issue_codes": list(row.issue_codes),
                "error_code": row.error_code, "disposition": row.disposition,
                "source_explanation": row.source_explanation} for row in parsed.rows],
        }})
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    export(parser.parse_args().output)
