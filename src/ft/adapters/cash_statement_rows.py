"""逐条归一已读取的平台来源行；不访问账户、不去重、不执行关系配对。"""
from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass, replace
from typing import Any

from ft.adapters.cash_statement_document import CashStatementDocument
from ft.domain.cash_record_policy import source_note
from ft.domain.cash_source_matching import SourceCoverage
from ft.domain.decimal import exact_decimal
from ft.domain.import_time import normalize_statement_timestamp
from ft.domain.record_type import classify_cash_record, normalize_counterparty_account


@dataclass(frozen=True)
class ParsedCashRow:
    position: int
    fields: dict[str, Any] | None
    payment_method: str = ""
    provider_id: str | None = None
    issue_codes: tuple[str, ...] = ()
    error_code: str | None = None
    disposition: str = "candidate"


@dataclass(frozen=True)
class ParsedCashDocument:
    source_type: str
    table_key: str
    parser_version: str
    rows: tuple[ParsedCashRow, ...]
    source_account_identifier: str | None
    source_account_label: str | None
    coverage: SourceCoverage
    declared_count: int | None
    observed_count: int


def _normalize_row(payload, source, position):
    # 共用既有来源规则，保留其资金归属和类型判定；新路径不调用跨行配对。
    from ft import convert
    parser = convert._parse_alipay_source_row if source == "alipay" else convert._parse_wechat_source_row
    issues = []
    fields = {
        "amount": None, "currency": "CNY", "occurred_at": None,
        "record_type": None, "record_subtype": None,
        "counterparty": payload.get("交易对方", ""),
        "counterparty_account": payload.get("对方账号", "") if source == "alipay" else "",
        "counterparty_account_attrs": [], "note": source_note(payload),
        "source_payload": deepcopy(payload),
    }
    try:
        exact_decimal(payload.get("金额" if source == "alipay" else "金额(元)", ""))
    except (ValueError, TypeError, ArithmeticError):
        issues.append("amount_unreadable")
    try:
        fields["occurred_at"] = normalize_statement_timestamp(payload.get("交易时间", "").replace("/", "-"), source=source)
    except (ValueError, TypeError, ArithmeticError):
        issues.append("time_unreadable")
    direction = payload.get("收/支", "").strip()
    if source == "alipay" and direction not in {"支出", "收入", "不计收支"}:
        issues.append("direction_unrecognized")
    elif source == "wechat" and direction not in {"支出", "收入", "/", "", "不计收支"}:
        issues.append("direction_unrecognized")
    method = ""
    if "amount_unreadable" not in issues and "direction_unrecognized" not in issues:
        native = parser(payload, position=position)
        if native is None:
            issues.append("direction_unrecognized")
        elif native.get("_skipped"):
            return ParsedCashRow(position, None, error_code=native["_skip_reason"], disposition="skipped")
        else:
            fields["amount"] = format(exact_decimal(native["amount"]), "f")
            fields["record_type"], fields["record_subtype"] = classify_cash_record(source, native)
            fields["counterparty"] = native["counterparty"]
            counterparty_account = normalize_counterparty_account(native.get("counterparty_account"), source=source, source_payload=payload)
            fields["counterparty_account"] = counterparty_account.value
            fields["counterparty_account_attrs"] = list(counterparty_account.attrs)
            method = native.get("payment_method", "")
    identifier = payload.get("交易订单号" if source == "alipay" else "交易单号", "").strip()
    provider_id = identifier if identifier not in {"", "/", "-", "--"} and len(identifier) <= 512 else None
    return ParsedCashRow(position, fields, method, provider_id, tuple(issues))


def normalize_platform_document(document: CashStatementDocument) -> ParsedCashDocument:
    if document.source_type not in {"alipay", "wechat"}:
        raise ValueError("statement_source_unsupported")
    rows = []
    for row in document.rows:
        if row.error_code or row.payload is None:
            rows.append(ParsedCashRow(row.position, None, error_code=row.error_code or "row_unreadable", disposition="skipped"))
            continue
        try:
            rows.append(_normalize_row(row.payload, document.source_type, row.position))
        except Exception:
            # 适配器的单条异常不能泄露技术信息或阻塞其余来源行。
            rows.append(ParsedCashRow(row.position, None, error_code="row_unreadable", disposition="skipped"))
    usable = all(not row.issue_codes and row.error_code in {None, "unpaid_closed", "failed_repay"} for row in rows)
    coverage = document.coverage if usable else replace(document.coverage, complete=False, evidence="")
    return ParsedCashDocument(
        document.source_type, document.table_key, document.parser_version + "+native-v1", tuple(rows),
        document.source_account_identifier, document.source_account_label, coverage,
        document.declared_count, document.observed_count,
    )
