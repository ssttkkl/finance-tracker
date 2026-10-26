"""工行 PDF 固定列逐行归一；未知值保留为空，不向余额列查找替代值。"""
from __future__ import annotations

from copy import deepcopy
from dataclasses import replace
from datetime import datetime
import re

from ft.adapters.cash_statement_rows import ParsedCashDocument, ParsedCashRow
from ft.adapters.cash_statement_evidence import extract_source_explanation
from ft.adapters.cash_statement_effects import cash_source_note, derive_cash_source_explanation
from ft.adapters.icbc_pdf_document import DEBIT_HEADERS, full_card
from ft.domain.decimal import exact_decimal
from ft.domain.import_time import normalize_statement_timestamp
from ft.domain.record_type import classify_cash_record, normalize_counterparty_account


_CURRENCIES = {"人民币": "CNY", "CNY": "CNY", "美元": "USD", "USD": "USD", "港币": "HKD", "HKD": "HKD", "日元": "JPY", "JPY": "JPY", "欧元": "EUR", "EUR": "EUR"}
_AMOUNT = re.compile(r"[+-]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\.[0-9]{1,18})?")


def _icbc_credit_source_display_name(cells, owner):
    """工行信用卡转换器从本方卡号组装并清洗信用卡账户显示名。"""
    source_card = re.sub(r"\s+", "", cells.get("交易卡号", ""))
    masked = re.fullmatch(r"[0-9]{4,6}[*＊]{2,}([0-9]{4})", source_card) if not owner else None
    tail = owner[-4:] if owner else (masked.group(1) if masked else "")
    raw = "中国工商银行信用卡" + (f"（尾号 {tail}）" if tail else "")
    compact = re.sub(r"\s+", "", raw)
    tail_match = re.search(r"[（(](?:尾号)?([0-9]{4})[）)]$", compact)
    body = compact[:tail_match.start()] if tail_match else compact
    bank = re.fullmatch(r"([\u4e00-\u9fff]+银行)([\u4e00-\u9fff]*卡)?", body)
    if not bank:
        return raw
    bank_name = bank.group(1)
    if bank_name.startswith("中国") and bank_name != "中国银行":
        bank_name = bank_name[2:]
    return bank_name + (bank.group(2) or "") + (f"({tail_match.group(1)})" if tail_match else "")


def _icbc_debit_source_display_name(owner):
    """工行借记卡转换器从文件本方卡号组装并清洗借记卡账户显示名。"""
    tail = owner[-4:] if owner else ""
    raw = "中国工商银行借记卡" + (f"（尾号 {tail}）" if tail else "")
    compact = re.sub(r"\s+", "", raw)
    tail_match = re.search(r"[（(](?:尾号)?([0-9]{4})[）)]$", compact)
    body = compact[:tail_match.start()] if tail_match else compact
    bank = re.fullmatch(r"([\u4e00-\u9fff]+银行)([\u4e00-\u9fff]*卡)?", body)
    if not bank:
        return raw
    bank_name = bank.group(1)
    if bank_name.startswith("中国") and bank_name != "中国银行":
        bank_name = bank_name[2:]
    return bank_name + (bank.group(2) or "") + (f"({tail_match.group(1)})" if tail_match else "")


def _row(row, document):
    from ft import convert
    payload = row.payload
    cells = {re.sub(r"\s+", "", key): value for key, value in payload.items()}
    credit = document.source_type == "icbc_credit"
    owner = full_card(cells.get("交易卡号", "")) if credit else document.source_account_identifier
    issues = []
    fields = {"amount": None, "currency": None, "occurred_at": None, "record_type": None, "record_subtype": None,
        "counterparty": cells.get("对方户名", "").replace("\n", "").strip(),
        "counterparty_account": "" if credit else cells.get("对方账号", "").replace("\n", "").strip(),
        "counterparty_account_attrs": [], "note": cash_source_note(document.source_type, payload), "source_payload": deepcopy(payload)}
    date = cells.get("入账日期" if credit else "交易日期", "").replace("\n", " ").strip()
    try:
        if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}", date):
            raise ValueError
        datetime.strptime(date, "%Y-%m-%d %H:%M:%S")
        fields["occurred_at"] = normalize_statement_timestamp(date, source=document.source_type)
    except (ValueError, TypeError, ArithmeticError):
        issues.append("time_unreadable")
        date = ""
    fields["currency"] = _CURRENCIES.get(cells.get("交易币种" if credit else "币种", "").replace("\n", "").strip())
    if fields["currency"] is None:
        issues.append("currency_unrecognized")
    value = cells.get("交易金额" if credit else "收入/支出金额", "").replace("\n", "").strip()
    amount = None
    try:
        if not _AMOUNT.fullmatch(value):
            raise ValueError
        amount = exact_decimal(value.replace(",", ""))
    except (ValueError, TypeError, ArithmeticError):
        issues.append("amount_unreadable")
    if credit:
        direction = cells.get("收支", "").strip()
        if direction in {"借", "支", "支出"}:
            amount = amount.copy_abs().copy_negate() if amount is not None else None
        elif direction in {"贷", "收", "收入"} and not value.startswith("-"):
            amount = amount.copy_abs() if amount is not None else None
        elif direction or not value.startswith(("+", "-")):
            issues.append("direction_unrecognized")
            amount = None
    if amount is not None:
        fields["amount"] = format(amount, "f")
    method = "工商银行信用卡" if credit else cells.get("渠道", "").replace("\n", "").strip()
    explanation = {}
    if amount is not None:
        if credit:
            account = cells.get("对方账号", "").replace("\n", "").strip()
            candidates = (account,) if re.fullmatch(r"[0-9]+[*＊]+[0-9]+", account) else ()
            native = convert._parse_icbc_credit_source_row(payload, date=date, amount=amount,
                currency=fields["currency"] or "", source_account_identifier=cells.get("交易卡号"),
                counterparty=fields["counterparty"], counterparty_account_candidates=candidates,
                summary=cells.get("摘要", "").replace("\n", "").strip(), description=cells.get("交易场所", "").replace("\n", "").strip())
        elif date:
            native = convert._parse_icbc_debit_row([cells[key] for key in DEBIT_HEADERS],
                source_account_identifier=owner, source_payload=payload, allow_missing_source_account=True, parsed_amount=amount)
        else:
            summary = convert._match_debit_summary(cells.get("摘要", "").replace("\n", "").strip())
            native = {"amount": amount, "counterparty": fields["counterparty"], "counterparty_account": fields["counterparty_account"],
                "summary": summary, "_is_refund": summary in {"退货", "退款"}, "payment_method": method}
        fields["counterparty"], fields["counterparty_account"] = native["counterparty"], native["counterparty_account"]
        fields["record_type"], fields["record_subtype"] = classify_cash_record(document.source_type, native)
        method = native["payment_method"]
        explanation = extract_source_explanation(native)
    explanation = derive_cash_source_explanation(document.source_type, payload, amount=amount,
        source_explanation={**explanation, "_source_account_identifier": owner or "", "file_account_key": owner or "",
            "card_number": owner[-4:] if owner else "", "source_display_name":
            _icbc_credit_source_display_name(cells, owner) if credit else _icbc_debit_source_display_name(owner)})
    account = normalize_counterparty_account(fields["counterparty_account"], source=document.source_type,
        source_account_identifier=owner, source_payload=payload)
    fields["counterparty_account"], fields["counterparty_account_attrs"] = account.value, list(account.attrs)
    return ParsedCashRow(row.position, fields, method, issue_codes=tuple(issues), source_account_identifier=owner if credit else None,
        document_position=row.document_position, source_explanation=explanation)


def normalize_icbc_document(document):
    rows = []
    for row in document.rows:
        if row.error_code or row.payload is None:
            rows.append(ParsedCashRow(row.position, None, error_code=row.error_code or "row_unreadable", disposition="skipped"))
            continue
        try:
            rows.append(_row(row, document))
        except (ValueError, TypeError, ArithmeticError):
            rows.append(ParsedCashRow(row.position, None, error_code="row_unreadable", disposition="skipped"))
    declared_range = replace(document.coverage, complete=True, evidence="declared-range")
    usable = all(not row.issue_codes and not row.error_code and declared_range.contains(row.fields.get("occurred_at")) for row in rows)
    coverage = document.coverage if usable else replace(document.coverage, complete=False, evidence="", document_key=None, page_total=None, page_fingerprints={})
    return ParsedCashDocument(document.source_type, document.table_key, document.parser_version + "+native-v1", tuple(rows),
        document.source_account_identifier, document.source_account_label, coverage, document.declared_count, document.observed_count)
