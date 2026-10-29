"""Export platform row normalization oracle cases."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from ft.adapters.cash_statement_document import CashStatementDocument, RawCashRow
from ft.adapters.cash_statement_rows import normalize_platform_document
from ft.domain.cash_source_matching import SourceCoverage

ALIPAY = {"交易时间": "2026-08-15 10:00:00", "交易分类": "日用百货", "交易对方": "测试商户",
    "对方账号": "acct@example.test", "商品说明": "  原始描述\n第二行  ", "收/支": "支出", "金额": "20.00",
    "收/付款方式": "余额宝", "交易状态": "交易成功", "交易订单号": "0000123", "商家订单号": "merchant-1"}
WECHAT = {"交易时间": "2026-08-15 10:00:00", "交易类型": "商户消费", "交易对方": "测试商户", "商品": "商品",
    "收/支": "支出", "金额(元)": "30.00", "支付方式": "零钱", "当前状态": "支付成功", "交易单号": "wx-1",
    "商户单号": "m-1", "备注": ""}


def coverage(*, complete=True):
    return SourceCoverage(start="2026-07-31T16:00:00+00:00", end="2026-08-31T16:00:00+00:00",
        complete=complete, evidence="platform-declared-range-filter-count-v1" if complete else "",
        filters={"export_type": "all"}, start_inclusive=True, end_inclusive=True,
        strategy_version="platform-coverage-v1")


def document(source, payloads, *, error_rows=(), complete=True, declared=None):
    rows = []
    position = 1
    for payload in payloads:
        rows.append(RawCashRow(position, dict(payload) if payload is not None else None))
        position += 1
    for code in error_rows:
        rows.append(RawCashRow(position, None, code))
        position += 1
    return CashStatementDocument(source, "csv:0", "platform-document-v1", tuple(rows), None, None,
        coverage(complete=complete), declared if declared is not None else len(payloads), len(rows))


def cases():
    alipay = []
    for direction in ("支出", "收入", "不计收支"):
        alipay.append({**ALIPAY, "收/支": direction})
    alipay += [
        {**ALIPAY, "金额": "0.00"},
        {**ALIPAY, "金额": "bad"},
        {**ALIPAY, "交易时间": "bad"},
        {**ALIPAY, "收/支": "unknown"},
        {**ALIPAY, "交易状态": "交易关闭", "收/支": "收入", "收/付款方式": ""},
        {**ALIPAY, "交易状态": "还款失败", "收/支": "不计收支", "收/付款方式": ""},
        {**ALIPAY, "交易状态": "退款成功"},
        {**ALIPAY, "交易分类": "退款", "收/支": "不计收支"},
        {**ALIPAY, "商品说明": "退款说明"},
        {**ALIPAY, "交易分类": "账户提现", "收/支": "不计收支"},
        {**ALIPAY, "商品说明": "转出到银行卡 余额宝"},
        {**ALIPAY, "商品说明": "收益发放 余额宝"},
        {**ALIPAY, "商品说明": "卖出至余额宝"},
        {**ALIPAY, "商品说明": "余额宝-单次转入"},
        {**ALIPAY, "交易订单号": "/"},
        {**ALIPAY, "交易订单号": "x" * 513},
        {**ALIPAY, "交易订单号": "x" * 512},
        {**ALIPAY, "金额": "1e-19"},
        {**ALIPAY, "金额": "123456789012345678901"},
        {**ALIPAY, "金额": "1_000"},
        {**ALIPAY, "金额": "２０"},
        {**ALIPAY, "收/支": "unknown", "商品说明": "转出到银行卡 余额宝", "收/付款方式": "银行卡"},
        {**ALIPAY, "交易时间": "2026-08-15"},
        {**ALIPAY, "交易时间": "2026-08-15T10:00:00+0800"},
        {**ALIPAY, "交易时间": "20260815T100000"},
        {**ALIPAY, "交易时间": "2026-08-15T10:00:00,5"},
        {**ALIPAY, "收/付款方式": "分期"},
    ]
    wechat = []
    for direction in ("支出", "收入"):
        wechat.append({**WECHAT, "收/支": direction})
    for txn_type in ("零钱提现", "充值", "零钱充值", "购买理财通", "信用卡还款", "零钱通存取", "理财通", "其他"):
        wechat.append({**WECHAT, "收/支": "/", "交易类型": txn_type})
    wechat += [
        {**WECHAT, "收/支": "/", "交易类型": "理财通", "商品": "赎回"},
        {**WECHAT, "收/支": "", "交易类型": "商户消费"},
        {**WECHAT, "收/支": "unknown"},
        {**WECHAT, "金额(元)": "bad"},
        {**WECHAT, "交易时间": "bad"},
        {**WECHAT, "当前状态": "已全额退款"},
        {**WECHAT, "交易单号": "-"},
        {**WECHAT, "交易单号": "x" * 512},
        {**WECHAT, "金额(元)": "1e-19"},
    ]
    return [
        ("alipay", document("alipay", alipay)),
        ("wechat", document("wechat", wechat)),
        ("alipay-partial", document("alipay", alipay[:3], complete=False)),
        ("error-rows", document("alipay", [ALIPAY], error_rows=("row_shape_invalid",))),
        ("alipay-null-payload", CashStatementDocument("alipay", "csv:0", "platform-document-v1",
            (RawCashRow(1, None), RawCashRow(2, dict(ALIPAY))), None, None, coverage(), 1, 2)),
        ("unsupported", CashStatementDocument("ccb_debit", "csv:0", "platform-document-v1", (), None, None,
            coverage(), 0, 0)),
    ]


def export(output: Path) -> None:
    result = []
    for name, document_value in cases():
        try:
            parsed = normalize_platform_document(document_value)
        except ValueError as failure:
            result.append({"name": name, "ok": False, "error": str(failure)})
            continue
        result.append({"name": name, "ok": True, "input": {
            "source_type": document_value.source_type,
            "rows": [{"position": row.position, "payload": row.payload, "error_code": row.error_code}
                for row in document_value.rows],
            "coverage": {"complete": document_value.coverage.complete,
                "evidence": document_value.coverage.evidence,
                "start": document_value.coverage.start, "end": document_value.coverage.end,
                "filters": document_value.coverage.filters,
                "strategy_version": document_value.coverage.strategy_version,
                "time_precision": document_value.coverage.time_precision,
                "start_inclusive": document_value.coverage.start_inclusive,
                "end_inclusive": document_value.coverage.end_inclusive},
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
                "source_account_identifier": row.source_account_identifier,
                "document_position": row.document_position, "source_explanation": row.source_explanation}
                for row in parsed.rows],
        }})
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    export(parser.parse_args().output)
