"""工银亚洲活期账户 UTF-16 制表符 CSV 解析器。"""
from __future__ import annotations

import csv
from datetime import datetime
from decimal import Decimal, InvalidOperation
import hashlib
import json
from pathlib import Path
import re


SOURCE_TYPE = "icbc_asia_current_account"
HEADERS = (
    "序號", "交易時間", "", "起息日期", "業務類型", "摘要", "收入金額", "支出金額",
    "餘額", "對方賬號", "對方戶名", "憑證號", "匯率", "備註", "交易場所",
)
CURRENCY_MAP = {
    "HKD": "HKD", "港幣": "HKD", "港币": "HKD",
    "USD": "USD", "美元": "USD", "美金": "USD",
    "CNY": "CNY", "RMB": "CNY", "人民幣": "CNY", "人民币": "CNY",
    "JPY": "JPY", "日圓": "JPY", "日元": "JPY",
    "EUR": "EUR", "歐元": "EUR", "欧元": "EUR",
}


def _metadata_value(rows: list[list[str]], label: str) -> str:
    for index, row in enumerate(rows):
        text = " ".join(row)
        if label not in text:
            continue
        values = [cell.strip() for cell in row if cell.strip() and label not in cell]
        if values:
            return " ".join(values)
        if index + 1 < len(rows):
            return " ".join(cell.strip() for cell in rows[index + 1] if cell.strip())
    return ""


def _account_identifier(metadata_rows: list[list[str]]) -> tuple[str, str]:
    for label in ("銀行賬號", "银行账号", "下掛賬戶", "下挂账户"):
        value = _metadata_value(metadata_rows, label)
        digits = re.findall(r"\d{4,}", value)
        if digits:
            account = max(digits, key=len)
            return account, account[-4:]
    return "", ""


def _currency(metadata_rows: list[list[str]]) -> str:
    value = _metadata_value(metadata_rows, "幣種") or _metadata_value(metadata_rows, "币种")
    normalized = value.upper().replace(" ", "")
    for token, currency in CURRENCY_MAP.items():
        if token.upper() in normalized:
            return currency
    raise ValueError("工银亚洲账单币种无法识别")


def _decimal(value: str, field: str) -> Decimal:
    try:
        amount = Decimal(value.strip().replace(",", ""))
    except (InvalidOperation, ValueError) as exc:
        raise ValueError(f"工银亚洲账单{field}无法解析") from exc
    if not amount.is_finite():
        raise ValueError(f"工银亚洲账单{field}必须是有限数值")
    if max(0, -amount.normalize().as_tuple().exponent) > 18:
        raise ValueError(f"工银亚洲账单{field}最多保留 18 位小数")
    return amount


def _record_id(
    account_identifier: str, currency: str, source_payload: dict[str, str],
) -> str:
    identity_payload = {
        key: value for key, value in source_payload.items() if key != "序號"
    }
    canonical = json.dumps(
        {
            "source_account": account_identifier,
            "currency": currency,
            "row": identity_payload,
        },
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return f"{SOURCE_TYPE}_{hashlib.sha256(canonical.encode('utf-8')).hexdigest()}"


def _note(source_payload: dict[str, str]) -> str:
    parts = (
        source_payload["摘要"].strip(),
        source_payload["備註"].strip(),
        source_payload["交易場所"].strip(),
    )
    return " ".join(dict.fromkeys(part for part in parts if part))


def read_icbc_asia_current_account(path: str) -> tuple[list[dict], str, list[dict]]:
    """读取受支持的工银亚洲活期账户 CSV，不写入运行时存储。"""
    try:
        with Path(path).open("r", encoding="utf-16", newline="") as stream:
            rows = list(csv.reader(stream, delimiter="\t"))
    except UnicodeError as exc:
        raise ValueError("工银亚洲账单必须使用 UTF-16 编码") from exc

    try:
        header_index = next(
            index for index, row in enumerate(rows) if row and row[0].strip() == "序號"
        )
    except StopIteration as exc:
        raise ValueError("工银亚洲账单缺少交易表头") from exc
    headers = rows[header_index]
    if tuple(headers) != HEADERS or len(set(headers)) != len(headers):
        raise ValueError("工银亚洲账单交易表头不符合支持格式")

    account_identifier, card_number = _account_identifier(rows[:header_index])
    currency = _currency(rows[:header_index])
    records: list[dict] = []
    seen_record_ids: set[str] = set()
    for row in rows[header_index + 1:]:
        if not any(row):
            continue
        if not row or not row[0].strip().isdigit():
            continue
        if len(row) != len(headers):
            raise ValueError("工银亚洲账单交易行列数不匹配")
        source_payload = dict(zip(headers, row, strict=True))
        try:
            occurred_at = datetime.strptime(
                f"{source_payload['交易時間'].strip()} {source_payload[''].strip()}",
                "%Y-%m-%d %H:%M:%S",
            )
        except ValueError as exc:
            raise ValueError("工银亚洲账单交易时间无法解析") from exc
        income = source_payload["收入金額"].strip()
        expense = source_payload["支出金額"].strip()
        if bool(income) == bool(expense):
            raise ValueError("工银亚洲账单收入金額和支出金額必须恰有一个非空")
        amount = _decimal(income or expense, "金额")
        if expense:
            amount = -abs(amount)
        else:
            amount = abs(amount)
        if amount == 0 and source_payload["業務類型"].strip() in {"新開戶", "新开户"}:
            continue
        fact_id = _record_id(account_identifier, currency, source_payload)
        if fact_id in seen_record_ids:
            raise ValueError("工银亚洲账单存在无法安全区分的重复业务行")
        seen_record_ids.add(fact_id)
        records.append({
            "date": occurred_at.strftime("%Y-%m-%d %H:%M:%S"),
            "amount": amount,
            "currency": currency,
            "card_number": card_number,
            "counterparty": source_payload["對方戶名"].strip(),
            "counterparty_account": source_payload["對方賬號"],
            "note": _note(source_payload),
            "category": "income" if amount >= 0 else "expense",
            "payment_method": (
                f"工银亚洲活期账户({card_number})" if card_number else "工银亚洲活期账户"
            ),
            "summary": source_payload["摘要"].strip(),
            "txn_type": source_payload["業務類型"].strip(),
            "location": source_payload["交易場所"].strip(),
            "_raw_cp": source_payload["對方戶名"].strip(),
            "_fact_id": fact_id,
            "_source_payload": source_payload,
        })
    if not records:
        raise ValueError("工银亚洲账单中没有可导入的交易行")
    return records, SOURCE_TYPE, []
