"""不直接写入运行时存储的原始账单解析器。"""
from __future__ import annotations

from decimal import Decimal
from pathlib import Path
import tempfile


CASH_SOURCES = {
    "alipay", "wechat", "icbc", "icbc-debit", "ccb-debit", "icbc-asia",
}


def _decimal_text(value) -> str:
    decimal = Decimal(str(value))
    if not decimal.is_finite():
        raise ValueError("账单金额必须是有限数值")
    normalized = decimal.normalize() if decimal else decimal
    if max(0, -normalized.as_tuple().exponent) > 18:
        raise ValueError("账单金额最多保留 18 位小数")
    return format(decimal, "f")


def _parse_cash_statement(command):
    from ft.convert import _build_output_row, _prepare_convert_rows
    from ft.mapping import load_rules

    rows, bill_type, tracking = _prepare_convert_rows(
        command.source_path, command.source, command.password
    )
    rules, default_action = load_rules()
    # 007 FR-004：mapping 未命中时必须失败关闭，不能通过默认 skip 静默跳过。
    action = (default_action or "error").lower()
    if action == "skip":
        # 账单导入不能继承文件级的默认 skip，必须按错误处理。
        action = "error"
        default_action = "error"
    output = []
    skipped = 0
    for row in rows:
        item = _build_output_row(
            row,
            bill_type=bill_type,
            currency=command.currency,
            rules=rules,
            default_action=default_action,
        )
        if item is None:
            skipped += 1
            continue
        item["amount"] = _decimal_text(item["amount"])
        source_payload = row.get("_source_payload")
        if not isinstance(source_payload, dict) or not source_payload:
            raise ValueError("账单行缺少完整来源行快照")
        item["source_payload"] = source_payload
        # 保留平台元数据，供导入时建立退款关系。
        for key in (
            "platform_status", "status", "txn_id", "merchant_order_id",
            "txn_type", "payment_method", "offset_group", "offset_role",
            "offset_rule_hint", "offset_match_type", "offset_strength",
            "proposed_action", "record_id", "summary", "refund_signal", "_raw_cp",
            "record_type", "record_subtype", "direction", "_wechat_direction", "_alipay_direction",
            "_refund_signal", "_ccb_refund_signal", "_is_refund", "_is_reversal",
            "_debit_offset_type", "offset_type", "location", "acct_name_raw",
        ):
            if key in row and key not in item:
                item[key] = row[key]
            elif key in row and not item.get(key):
                item[key] = row[key]
        output.append(item)
    if skipped:
        raise ValueError(
            f"有 {skipped} 条账单记录未匹配账户映射规则；"
            f"请在 ~/.ft/mapping.yaml 中补充规则（失败关闭，FR-004）"
        )
    # 将验收统计和跟踪信息随解析结果交给 StatementImportService。
    acceptance = {
        "source_lines": 0,
        "skipped_unpaid_closed": 0,
        "skipped_failed_repay": 0,
        "fact_lines": len(output),
    }
    refund_pairs = []
    for pair in tracking or []:
        if isinstance(pair, dict) and pair.get("_acceptance"):
            acceptance.update(pair["_acceptance"])
        elif isinstance(pair, dict) and pair.get("expense") and pair.get("refund"):
            refund_pairs.append(pair)
    output_meta = {
        "acceptance": acceptance,
        "refund_tracking_pairs": refund_pairs,
    }
    # 将元数据挂到首条记录的内部字段，避免为此引入新的列表类型。
    if output:
        output[0] = dict(output[0])
        output[0]["_import_meta"] = output_meta
    elif acceptance.get("source_lines"):
        # 全部记录都按白名单跳过时，当前仍返回空列表；服务层负责解释验收统计。
        pass
    return output


def _route_dfzq_account(command) -> tuple[str, str]:
    """通过账户映射规则（source=dfzq、match=*）解析东方证券账户，未命中则失败。"""
    from ft.mapping import load_rules, match_payment_method

    rules, default_action = load_rules()
    match = match_payment_method(rules, "dfzq", "*")
    if match:
        currency = match.get("currency") or command.currency or "CNY"
        return match["account"], str(currency).upper()
    action = (default_action or "error").lower()
    if action in {"error", "fail"}:
        raise ValueError(
            "未匹配账户映射规则：source=dfzq match='*'\n"
            "  请在 ~/.ft/mapping.yaml 中添加 dfzq 映射规则后重试"
        )
    raise ValueError("东方证券账单被账户映射规则的 default=skip 跳过")


def _dfzq_rows(records, command):
    account_name, currency = _route_dfzq_account(command)
    mapped = []
    for record in records:
        action = record["action"]
        common = {
            "occurred_at": record["date"], "currency": currency,
            "account_name": account_name, "note": record.get("note", ""),
            "commission_asset": "", "commission": "0",
        }
        if action == "BUY":
            amount = abs(Decimal(str(record["amount"])))
            row = {**common, "action": "swap", "from_ticker": currency.lower(),
                   "to_ticker": record["ticker"], "from_amount": amount,
                   "to_amount": record["shares"], "price": record["price"],
                   "commission": record["fee"], "commission_asset": currency.lower()}
        elif action == "SELL":
            amount = abs(Decimal(str(record["amount"])))
            row = {**common, "action": "swap", "from_ticker": record["ticker"],
                   "to_ticker": currency.lower(), "from_amount": record["shares"],
                   "to_amount": amount, "price": record["price"],
                   "commission": record["fee"], "commission_asset": currency.lower()}
        elif action == "DIVIDEND":
            ticker = record.get("ticker", "")
            row = {**common, "action": "dividend", "from_ticker": ticker,
                   "to_ticker": ticker or currency.lower(), "from_amount": "0",
                   "to_amount": record["shares"] if ticker else abs(Decimal(str(record["amount"]))),
                   "price": "0" if ticker else "1"}
        elif action in {"DEPOSIT", "WITHDRAW"}:
            incoming = action == "DEPOSIT"
            amount = abs(Decimal(str(record["amount"])))
            row = {**common, "action": action.lower(),
                   "from_ticker": "" if incoming else currency.lower(),
                   "to_ticker": currency.lower() if incoming else "",
                   "from_amount": "0" if incoming else amount,
                   "to_amount": amount if incoming else "0", "price": "1"}
        elif action == "CHECKIN":
            row = {**common, "action": "checkin", "from_ticker": currency.lower(),
                   "to_ticker": "", "from_amount": "0",
                   "to_amount": abs(Decimal(str(record["amount"]))), "price": "1"}
        else:
            raise ValueError(f"不支持的东方证券业务动作：{action}")
        for key in ("from_amount", "to_amount", "price", "commission"):
            row[key] = _decimal_text(row.get(key, 0))
        mapped.append(row)
    return mapped


def _parse_dfzq_statement(command):
    from ft.importers.dfzq import parse_dfzq_text
    from ft.importers.pdf_tools import decrypt_pdf, extract_pdf_text

    source = Path(command.source_path)
    with tempfile.TemporaryDirectory(prefix="ft-dfzq-") as temp_dir:
        pdf_path = source
        if command.password is not None:
            pdf_path = Path(temp_dir) / "statement.pdf"
            decrypt_pdf(source, pdf_path, command.password, timeout=30)
        records = parse_dfzq_text(extract_pdf_text(pdf_path).splitlines())
    if not records:
        raise ValueError("东方证券账单中没有可导入的记录")
    return _dfzq_rows(records, command)


class StatementParser:
    def parse(self, command):
        path = Path(command.source_path)
        if not path.is_file():
            raise FileNotFoundError(f"找不到账单文件：{path}")
        if command.source in CASH_SOURCES:
            return _parse_cash_statement(command)
        if command.source == "dfzq":
            return _parse_dfzq_statement(command)
        raise ValueError(f"不支持的账单数据源：{command.source}")
