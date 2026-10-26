"""逐条归一已读取的平台来源行；不访问账户、不去重、不执行关系配对。"""
from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass, field, replace
from datetime import datetime
import re
from typing import Any

from ft.adapters.cash_statement_document import CashStatementDocument
from ft.adapters.cash_statement_evidence import extract_source_explanation
from ft.adapters.cash_statement_effects import cash_source_note, derive_cash_source_explanation
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
    source_account_identifier: str | None = None
    document_position: str | None = None
    source_explanation: dict[str, str | bool] = field(default_factory=dict, repr=False)


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
        "counterparty_account_attrs": [], "note": cash_source_note(source, payload),
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
    method, explanation = "", {}
    if "amount_unreadable" not in issues and "direction_unrecognized" not in issues:
        native = parser(payload, position=position)
        if native is None:
            issues.append("direction_unrecognized")
        elif native.get("_skipped"):
            return ParsedCashRow(position, None, error_code=native["_skip_reason"], disposition="skipped")
        else:
            fields["amount"] = format(exact_decimal(native["amount"]), "f")
            fields["record_type"], fields["record_subtype"] = classify_cash_record(source, native)
            fields["counterparty"] = payload.get("交易对方", "") if source == "wechat" else native["counterparty"]
            counterparty_account = normalize_counterparty_account(native.get("counterparty_account"), source=source, source_payload=payload)
            fields["counterparty_account"] = counterparty_account.value
            fields["counterparty_account_attrs"] = list(counterparty_account.attrs)
            method = native.get("payment_method", "")
            explanation = extract_source_explanation(native)
    identifier = payload.get("交易订单号" if source == "alipay" else "交易单号", "").strip()
    provider_id = identifier if identifier not in {"", "/", "-", "--"} and len(identifier) <= 512 else None
    explanation = derive_cash_source_explanation(source, payload, amount=exact_decimal(fields["amount"]) if fields["amount"] is not None else None,
        source_explanation=explanation)
    method = explanation.get("payment_method", method)
    return ParsedCashRow(position, fields, method, provider_id, tuple(issues), source_explanation=explanation)


def _normalize_alipay_payment_method(value):
    """支付宝转换器自己的支付方式标签清洗；原始支付方式留在 source_payload。"""
    if not isinstance(value, str):
        return ""
    result = []
    for raw in value.split("&"):
        token = raw.strip()
        if token.startswith("云闪付-"):
            token = token[len("云闪付-"):]
        token = re.sub(
            r"[（(\[【]\s*((?:[¥￥]\s*[+-]?[0-9][0-9,]*(?:\.[0-9]+)?|[+-]?[0-9][0-9,]*(?:\.[0-9]+)?\s*元))\s*[）)\]】]",
            r"\1",
            token,
        )
        token = re.sub(r"分期\s*[（(]\s*\d+\s*期\s*[）)]", "", token)
        token = token.replace("分期", "")
        token = re.sub(r"\s*[（(]?\d+\s*期[）)]?", "", token).strip()
        amount = re.search(r"(?:[¥￥]\s*[+-]?[0-9][0-9,]*(?:\.[0-9]+)?|[+-]?[0-9][0-9,]*(?:\.[0-9]+)?\s*元)$", token)
        suffix = token[amount.start():] if amount else ""
        label = token[:amount.start()] if amount else token
        compact = re.sub(r"\s+", "", label)
        tail = re.search(r"(?:[（(](?:尾号)?([0-9]{4})[）)]|尾号([0-9]{4})|([0-9]{4}))$", compact)
        if tail:
            digits = next(group for group in tail.groups() if group is not None)
            body = compact[:tail.start()]
        else:
            digits, body = "", compact
        bank = re.fullmatch(r"([\u4e00-\u9fff]+银行)([\u4e00-\u9fff]*卡)?", body)
        if bank:
            bank_name = bank.group(1)
            if bank_name.startswith("中国") and bank_name != "中国银行":
                bank_name = bank_name[2:]
            normalized = bank_name + (bank.group(2) or "") + (f"({digits})" if digits else "")
            result.append(normalized + suffix)
        else:
            result.append(token)
    return "&".join(result)


def _normalize_wechat_payment_method(value):
    """微信转换器清洗支付方式开头的云闪付前缀及完整银行式账户名。"""
    if not isinstance(value, str):
        return ""
    token = value.strip()
    if token.startswith("云闪付-"):
        token = token[len("云闪付-"):]
    compact = re.sub(r"\s+", "", token)
    tail = re.search(r"(?:[（(](?:尾号)?([0-9]{4})[）)]|尾号([0-9]{4})|([0-9]{4}))$", compact)
    if tail:
        digits = next(group for group in tail.groups() if group is not None)
        body = compact[:tail.start()]
    else:
        digits, body = "", compact
    bank = re.fullmatch(r"([\u4e00-\u9fff]+银行)([\u4e00-\u9fff]*卡)?", body)
    if not bank:
        return token
    bank_name = bank.group(1)
    if bank_name.startswith("中国") and bank_name != "中国银行":
        bank_name = bank_name[2:]
    return bank_name + (bank.group(2) or "") + (f"({digits})" if digits else "")


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
        except (ValueError, TypeError, ArithmeticError):
            # 可识别的源字段错误按行处理；内部异常交给文件解析边界。
            rows.append(ParsedCashRow(row.position, None, error_code="row_unreadable", disposition="skipped"))
    usable = all(not row.issue_codes and row.error_code in {None, "unpaid_closed", "failed_repay"} for row in rows)
    coverage = document.coverage if usable else replace(document.coverage, complete=False, evidence="")
    return ParsedCashDocument(
        document.source_type, document.table_key, document.parser_version + "+native-v1", tuple(rows),
        document.source_account_identifier, document.source_account_label, coverage,
        document.declared_count, document.observed_count,
    )


def _bank_row(payload, document, position):
    from ft.importers.ccb_debit import (
        _extract_ccb_acct_name_counterparty, _extract_ccb_counterparty_account,
        _infer_ccb_payment_source, _parse_ccb_source_row,
    )
    from ft.importers.icbc_asia_current_account import _parse_icbc_asia_source_row
    source, owner = document.source_type, document.source_account_identifier or ""
    is_ccb = source == "ccb_debit"
    card = (owner[-4:] if is_ccb else (owner[:-1] + "0")[-4:]) if owner.isascii() and owner.isdigit() else ""
    issues = []
    fields = {"amount": None, "currency": None, "occurred_at": None, "record_type": None, "record_subtype": None,
        "counterparty": _extract_ccb_acct_name_counterparty(payload.get("对方账号与户名", "")) if is_ccb else payload.get("對方戶名", "").strip(),
        "counterparty_account": _extract_ccb_counterparty_account(payload.get("对方账号与户名", "")) if is_ccb else payload.get("對方賬號", ""),
        "counterparty_account_attrs": [], "note": source_note(payload), "source_payload": deepcopy(payload)}
    method = _infer_ccb_payment_source(payload.get("交易地点/附言", "").strip(), card) if is_ccb else (
        f"工银亚洲活期账户({card})" if card else "工银亚洲活期账户")
    amount, date, explanation = None, "", {}
    if is_ccb:
        value = payload.get("交易金额", "")
    else:
        income, expense = (payload.get(field, "").strip() for field in ("收入金額", "支出金額"))
        value = income or expense
        if bool(income) == bool(expense):
            issues.append("direction_unrecognized")
    if "direction_unrecognized" not in issues:
        try:
            amount = exact_decimal(value.replace(",", ""))
            if not is_ccb:
                amount = amount.copy_abs().copy_negate() if expense else amount.copy_abs()
            fields["amount"] = format(amount, "f")
        except (ValueError, TypeError, ArithmeticError):
            issues.append("amount_unreadable")
    try:
        if is_ccb:
            value = payload.get("交易日期", "").strip()
            if not re.fullmatch(r"[0-9]{8}", value):
                raise ValueError
            date = datetime.strptime(value, "%Y%m%d").strftime("%Y-%m-%d")
        else:
            value = payload.get("交易時間", "").strip() + " " + payload.get("", "").strip()
            if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}", value):
                raise ValueError
            date = datetime.strptime(value, "%Y-%m-%d %H:%M:%S").strftime("%Y-%m-%d %H:%M:%S")
        fields["occurred_at"] = normalize_statement_timestamp(date, source=source)
    except (ValueError, TypeError, ArithmeticError):
        issues.append("time_unreadable")
    fields["currency"] = ({"人民币元": "CNY", "人民币": "CNY", "美元": "USD", "港币": "HKD", "日元": "JPY"}.get(
        payload.get("币别", "").strip()) if is_ccb else document.source_currency)
    if fields["currency"] is None:
        issues.append("currency_unrecognized")
    if amount is not None:
        native = (_parse_ccb_source_row(payload, amount=amount, date=date, currency=fields["currency"] or "", card_last4=card) if is_ccb else
            _parse_icbc_asia_source_row(payload, amount=amount, date=date, currency=fields["currency"] or "", account_identifier=owner, card_number=card))
        fields["counterparty"] = native["counterparty"]
        fields["counterparty_account"] = native["counterparty_account"]
        fields["record_type"], fields["record_subtype"] = classify_cash_record(source, native)
        method = native["payment_method"]
        explanation = extract_source_explanation(native)
        if not is_ccb and amount == 0 and payload.get("業務類型", "").strip() in {"新開戶", "新开户"} and not issues:
            return ParsedCashRow(position, None, error_code="account_opening", disposition="skipped")
    if is_ccb:
        display_tail = owner[-4:] if owner and owner.isascii() and owner.isdigit() else ""
        raw_display_name = "中国建设银行" + (f"（尾号 {display_tail}）" if display_tail else "")
        compact = re.sub(r"\s+", "", raw_display_name)
        tail_match = re.search(r"[（(](?:尾号)?([0-9]{4})[）)]$", compact)
        display_name = "建设银行" + (f"({tail_match.group(1)})" if tail_match else "")
    else:
        display_tail = owner[-4:] if owner and owner.isascii() and owner.isdigit() else ""
        display_name = "工银亚洲" + (f"（尾号 {display_tail}）" if display_tail else "")
    explanation = derive_cash_source_explanation(source, payload, amount=amount,
        source_explanation={**explanation, "_source_account_identifier": owner or "", "card_number": card or "",
            "source_display_name": display_name})
    counterparty_account = normalize_counterparty_account(fields["counterparty_account"], source=source,
        source_account_identifier=owner, source_payload=payload)
    fields["counterparty_account"] = counterparty_account.value
    fields["counterparty_account_attrs"] = list(counterparty_account.attrs)
    return ParsedCashRow(position, fields, method, issue_codes=tuple(issues), source_explanation=explanation)


def normalize_bank_document(document: CashStatementDocument) -> ParsedCashDocument:
    if document.source_type not in {"ccb_debit", "icbc_asia"}:
        raise ValueError("statement_source_unsupported")
    rows = []
    for row in document.rows:
        if row.error_code or row.payload is None:
            rows.append(ParsedCashRow(row.position, None, error_code=row.error_code or "row_unreadable", disposition="skipped"))
            continue
        try:
            rows.append(_bank_row(row.payload, document, row.position))
        except (ValueError, TypeError, ArithmeticError):
            rows.append(ParsedCashRow(row.position, None, error_code="row_unreadable", disposition="skipped"))
    usable = all(not row.issue_codes and row.error_code in {None, "account_opening"}
        and (row.fields is None or document.coverage.contains(row.fields.get("occurred_at"))) for row in rows)
    coverage = document.coverage if usable else replace(document.coverage, complete=False, evidence="")
    return ParsedCashDocument(document.source_type, document.table_key, document.parser_version + "+native-v1", tuple(rows),
        document.source_account_identifier, document.source_account_label, coverage, document.declared_count, document.observed_count)


def parse_cash_statement(content: bytes, *, source: str | None = None, password: str | None = None) -> ParsedCashDocument:
    from ft.adapters.bank_statement_document import read_bank_document
    from ft.adapters.cash_statement_document import StatementDocumentError, read_platform_document
    if not isinstance(content, bytes) or not content:
        raise StatementDocumentError("statement_input_invalid")
    source = source.replace("-", "_") if isinstance(source, str) else source
    if content.startswith(b"%PDF-") or source in {"icbc", "icbc_credit", "icbc_debit"}:
        from ft.adapters.icbc_pdf_document import read_icbc_document
        from ft.adapters.icbc_pdf_rows import normalize_icbc_document
        return normalize_icbc_document(read_icbc_document(content, source=source, password=password))
    if source in {"ccb_debit", "icbc_asia"} or source is None and content.startswith((b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1", b"\xff\xfe", b"\xfe\xff")):
        return normalize_bank_document(read_bank_document(content, source=source))
    return normalize_platform_document(read_platform_document(content, source=source))
