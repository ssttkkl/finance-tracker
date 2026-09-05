"""不直接写入运行时存储的原始账单解析器。"""
from __future__ import annotations

from decimal import Decimal
from pathlib import Path
import tempfile

from ft.domain.import_time import normalize_statement_timestamp


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


def _normalize_cash_source_account(row: dict, *, source: str) -> None:
    """Normalize platform placeholders that are not real source accounts."""
    if source == "alipay":
        payment_method = str(row.get("payment_method") or "").strip()
        if payment_method in {"账户余额", "余额"}:
            row["payment_method"] = "支付宝余额"
            return
        if payment_method:
            return
        # Alipay exports some valid income / expense rows without a funding
        # method.  Keep them in a real wallet group so the import wizard can
        # ask for an account instead of failing the whole file.
        row["payment_method"] = "支付宝余额"
        return
    if source != "wechat":
        return
    payment_method = str(row.get("payment_method") or "").strip()
    if payment_method in {"零钱", "微信零钱"}:
        row["payment_method"] = "微信零钱"
        return
    if payment_method != "/":
        return
    status = str(row.get("status") or row.get("platform_status") or "").strip()
    if row.get("record_type") == "transfer_in" and status == "已存入零钱":
        row["payment_method"] = "微信零钱"


def _parse_cash_statement(command, *, resolve_accounts: bool = True):
    from ft.convert import _build_output_row, _prepare_convert_rows

    rows, bill_type, tracking = _prepare_convert_rows(
        command.source_path, command.source, command.password
    )
    rules = default_action = None
    if resolve_accounts:
        from ft.mapping import load_rules

        rules, default_action = load_rules()
        # 007 FR-004：mapping 未命中时必须失败关闭，不能通过默认 skip 静默跳过。
        action = (default_action or "error").lower()
        if action == "skip":
            # 账单导入不能继承文件级的默认 skip，必须按错误处理。
            default_action = "error"
    output = []
    skipped = 0
    for row in rows:
        _normalize_cash_source_account(row, source=bill_type)
        item = _build_output_row(
            row,
            bill_type=bill_type,
            currency=command.currency,
            rules=rules,
            default_action=default_action,
            resolve_account=resolve_accounts,
        )
        if item is None:
            skipped += 1
            continue
        if not resolve_accounts:
            item["account_name"] = ""
        item["occurred_at"] = normalize_statement_timestamp(
            item.get("occurred_at") or item.get("date"),
            source=bill_type,
        )
        item["amount"] = _decimal_text(item["amount"])
        source_payload = row.get("_source_payload")
        if not isinstance(source_payload, dict) or not source_payload:
            raise ValueError("账单行缺少完整来源行快照")
        item["source_payload"] = source_payload
        relation_metadata = {
            key: row[key]
            for key in (
                "offset_group", "offset_role", "offset_strength", "offset_source",
                "offset_rule_hint", "offset_match_type",
            )
            if row.get(key) not in (None, "")
        }
        if relation_metadata:
            item["relation_metadata"] = relation_metadata
        # 保留平台元数据，供导入时建立退款关系。
        for key in (
            "platform_status", "status", "txn_id", "merchant_order_id",
            "txn_type", "payment_method", "offset_group", "offset_role",
            "offset_rule_hint", "offset_match_type", "offset_strength",
            "proposed_action", "record_id", "summary", "refund_signal", "_raw_cp",
            "record_type", "record_subtype", "direction", "_wechat_direction", "_alipay_direction",
            "_refund_signal", "_ccb_refund_signal", "_is_refund", "_is_reversal",
            "_debit_offset_type", "offset_type", "location", "acct_name_raw",
            "card_number", "_source_account_identifier", "file_account_key", "source_display_name",
        ):
            if key in row and key not in item:
                item[key] = row[key]
            elif key in row and not item.get(key):
                item[key] = row[key]
        output.append(item)
    if skipped and resolve_accounts:
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
    from ft.importers.dfzq import map_dfzq_to_investment_event

    account_name, currency = _route_dfzq_account(command)
    mapped = []
    for record in records:
        row = map_dfzq_to_investment_event(record, account_name, currency)
        row["occurred_at"] = row.pop("date")
        # 来源行快照只保存解析到的原生字段，不混入映射后的正式字段。
        row["source_payload"] = record
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

    def parse_source_rows(self, command):
        """Parse cash facts without choosing a system account.

        This is the source-account scanning entry point.  It is deliberately
        separate from ``parse`` so investment compatibility paths retain their
        existing routing semantics while cash import can be database-driven.
        """
        path = Path(command.source_path)
        if not path.is_file():
            raise FileNotFoundError(f"找不到账单文件：{path}")
        if command.source in CASH_SOURCES:
            return _parse_cash_statement(command, resolve_accounts=False)
        raise ValueError(f"不支持扫描来源账户的数据源：{command.source}")
