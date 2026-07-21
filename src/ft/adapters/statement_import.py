"""Raw statement parsers without runtime persistence."""
from __future__ import annotations

from decimal import Decimal
from pathlib import Path
import tempfile


CASH_SOURCES = {"alipay", "wechat", "icbc", "icbc-debit", "ccb-debit"}


def _decimal_text(value) -> str:
    decimal = Decimal(str(value))
    if not decimal.is_finite():
        raise ValueError("statement amount must be finite")
    normalized = decimal.normalize() if decimal else decimal
    if max(0, -normalized.as_tuple().exponent) > 18:
        raise ValueError("statement amount must have at most 18 decimal places")
    return format(decimal, "f")


def _parse_cash_statement(command):
    from ft.convert import _build_output_row, _prepare_convert_rows
    from ft.mapping import load_rules

    rows, bill_type, tracking = _prepare_convert_rows(
        command.source_path, command.source, command.password
    )
    rules, default_action = load_rules()
    # 007 FR-004: mapping miss must fail closed — never silent default=skip
    action = (default_action or "error").lower()
    if action == "skip":
        # Treat file-level default skip as error for statement import path
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
        # Preserve platform metadata for import-time refund relations
        for key in (
            "platform_status", "status", "txn_id", "merchant_order_id",
            "txn_type", "payment_method", "offset_group", "offset_role",
            "offset_rule_hint", "offset_match_type", "offset_strength",
            "proposed_action", "record_id",
        ):
            if key in row and key not in item:
                item[key] = row[key]
            elif key in row and not item.get(key):
                item[key] = row[key]
        output.append(item)
    if skipped:
        raise ValueError(
            f"{skipped} statement row(s) unmatched by mapping; "
            f"add rules to ~/.ft/mapping.yaml (fail-closed, FR-004)"
        )
    # Stash acceptance + tracking on list for StatementImportService via attribute
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
    # Use a list subclass? Simpler: attach to first row private key
    if output:
        output[0] = dict(output[0])
        output[0]["_import_meta"] = output_meta
    elif acceptance.get("source_lines"):
        # all whitelist-skipped: still return empty with meta via custom exception? 
        # Represent as empty list; StatementImportService treats empty as error today.
        # For pure-skip files, raise with counters in message is ok; better return sentinel.
        pass
    return output


def _route_dfzq_account(command) -> tuple[str, str]:
    """Resolve DFZQ account via mapping (source=dfzq, match=*) or fail."""
    from ft.mapping import load_rules, match_payment_method

    rules, default_action = load_rules()
    match = match_payment_method(rules, "dfzq", "*")
    if match:
        currency = match.get("currency") or command.currency or "CNY"
        return match["account"], str(currency).upper()
    action = (default_action or "error").lower()
    if action in {"error", "fail"}:
        raise ValueError(
            "未匹配 mapping 规则: source=dfzq match='*'\n"
            "  请在 ~/.ft/mapping.yaml 中添加 dfzq 映射规则后重试"
        )
    raise ValueError("dfzq statement skipped by mapping default=skip")


def _dfzq_rows(records, command):
    account_name, currency = _route_dfzq_account(command)
    mapped = []
    for record in records:
        action = record["action"]
        common = {
            "date": record["date"], "currency": currency,
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
            raise ValueError(f"unsupported DFZQ action: {action}")
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
        raise ValueError("DFZQ statement contains no supported records")
    return _dfzq_rows(records, command)


class StatementParser:
    def parse(self, command):
        path = Path(command.source_path)
        if not path.is_file():
            raise FileNotFoundError(f"statement file not found: {path}")
        if command.source in CASH_SOURCES:
            return _parse_cash_statement(command)
        if command.source == "dfzq":
            return _parse_dfzq_statement(command)
        raise ValueError(f"unsupported statement source: {command.source}")
