"""现金账单来源账户扫描与映射领域值。

This module deliberately knows nothing about YAML or account names.  Parser rows
carry source evidence; this boundary turns that evidence into stable, internal
source-account groups before an application service applies a user decision.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation
import hashlib
import json
import re


_CASH_SOURCES = {
    "alipay", "wechat", "icbc_credit", "icbc_debit", "ccb_debit", "icbc_asia",
}


@dataclass(frozen=True)
class SourceAccountGroup:
    """A source-account group safe to expose through the import API.

    ``source_account_key`` is intentionally an internal field.  API serializers
    must use ``group_id`` and ``masked_evidence`` instead of returning it.
    """

    group_id: str
    source_type: str
    identity_kind: str
    source_account_key: str = field(repr=False)
    display_name: str
    masked_evidence: str
    currencies: tuple[str, ...]
    row_count: int
    legacy_source_account_keys: tuple[str, ...] = field(default=(), repr=False, compare=False)


@dataclass(frozen=True)
class SourceRowIssue:
    """A source row that cannot safely participate in account mapping."""

    row_index: int
    code: str


def _text(value) -> str:
    return str(value or "").strip()


def _card_tail(value: object) -> str:
    digits = re.sub(r"[^0-9]", "", _text(value))
    if len(digits) < 4:
        return ""
    return digits[-4:]


_ALIPAY_NON_FUNDING_MARKERS = (
    "红包", "立减", "优惠", "抵扣", "福利金", "券", "骑行卡", "天天减", "每日必减",
)


def _alipay_amount_is_zero(row: dict) -> bool:
    try:
        return Decimal(str(row.get("amount") or "0")) == 0
    except (InvalidOperation, ValueError):
        return False


def _normalize_alipay_payment_component(value: str) -> str:
    component = _text(value)
    if not component or any(marker in component for marker in _ALIPAY_NON_FUNDING_MARKERS):
        return ""
    # Keep the card tail in e.g. "工商银行信用卡分期(1200)" while removing
    # the installment product marker and the optional plan count.
    component = re.sub(r"分期\s*[（(]\s*\d+\s*期\s*[）)]", "", component)
    component = component.replace("分期", "")
    component = re.sub(r"\s*[（(]?\d+\s*期[）)]?", "", component)
    component = component.strip()
    if component in {"账户余额", "余额"}:
        return "支付宝余额"
    return component


def _normalize_wechat_payment_identity(value: object) -> str:
    payment_method = _text(value)
    return "微信零钱" if payment_method in {"零钱", "微信零钱"} else payment_method


def _alipay_payment_identity(row: dict) -> str:
    raw = _text(row.get("payment_method"))
    if not raw:
        raise ValueError("业务行无法识别来源账户")
    components = [
        _normalize_alipay_payment_component(item)
        for item in raw.split("&")
    ]
    funding_accounts = []
    for component in components:
        if component and component not in funding_accounts:
            funding_accounts.append(component)
    if len(funding_accounts) > 1:
        raise ValueError("import_composite_payment_unresolved")
    if funding_accounts:
        return funding_accounts[0]
    if _alipay_amount_is_zero(row):
        return "支付宝余额"
    raise ValueError("import_composite_payment_unresolved")


def _identity_for_row(row: dict) -> tuple[str, str, str, str, str]:
    source_type = _text(row.get("bill_source") or row.get("source_type"))
    if source_type not in _CASH_SOURCES:
        raise ValueError("账单记录缺少受支持的来源账户身份")

    display_name = _text(row.get("source_display_name"))
    if source_type in {"alipay", "wechat"}:
        source_key = (
            _alipay_payment_identity(row)
            if source_type == "alipay"
            else _normalize_wechat_payment_identity(row.get("payment_method"))
        )
        identity_kind = "payment_method"
        if not source_key:
            raise ValueError("业务行无法识别来源账户")
        display_name = display_name or source_key
        evidence = display_name
    elif source_type in {"icbc_credit", "ccb_debit"}:
        source_key = _card_tail(row.get("card_number"))
        identity_kind = "card_tail"
        if not source_key:
            raise ValueError("业务行无法识别来源账户")
        display_name = display_name or ("信用卡" if source_type == "icbc_credit" else "建设银行")
        evidence = f"{display_name}（尾号 {source_key}）"
    elif source_type == "icbc_asia":
        source_key = _text(row.get("_source_account_identifier"))
        identity_kind = "account_identifier"
        if not source_key:
            raise ValueError("业务行无法识别来源账户")
        display_name = display_name or "工银亚洲活期账户"
        evidence = f"{display_name}（尾号 {_card_tail(row.get('card_number')) or '未知'}）"
    else:
        # ICBC debit parser has a file-level account contract.  Older parser
        # rows only carry its declared payment/file label, which is still safer
        # than deriving an identity from a counterparty or free text.
        source_key = _text(
            row.get("_source_account_identifier")
            or row.get("file_account_key")
            or row.get("payment_method")
        )
        identity_kind = "file_account"
        if not source_key:
            raise ValueError("业务行无法识别来源账户")
        display_name = display_name or source_key
        evidence = display_name

    return source_type, identity_kind, source_key, display_name, evidence


def _legacy_source_account_keys(row: dict, source_key: str) -> tuple[str, ...]:
    source_type = str(row.get("bill_source") or row.get("source_type") or "").strip()
    if source_type not in {"alipay", "wechat"}:
        return ()
    raw = _text(row.get("payment_method"))
    if not raw:
        return ()
    if source_type == "alipay":
        normalized_components = [
            _normalize_alipay_payment_component(item)
            for item in raw.split("&")
        ]
        if not any(normalized_components):
            return ()
    else:
        if _normalize_wechat_payment_identity(raw) == raw:
            return ()
    return (raw,) if raw != source_key else ()


def source_identity_key(row: dict) -> tuple[str, str, str]:
    """Return the internal grouping key for an already parsed source row."""
    return _identity_for_row(row)[:3]


def _group_id(source_type: str, identity_kind: str, source_key: str) -> str:
    # The client only receives this opaque locator.  The source key is never
    # interpolated into it and therefore cannot leak through the URL/UI.
    payload = json.dumps(
        [source_type, identity_kind, source_key],
        ensure_ascii=False, separators=(",", ":"),
    ).encode("utf-8")
    return f"group_{hashlib.sha256(payload).hexdigest()[:24]}"


def scan_source_rows_with_issues(
    rows: list[dict],
) -> tuple[list[SourceAccountGroup], tuple[SourceRowIssue, ...]]:
    """Group rows while isolating known row-level source identity problems."""
    groups: dict[tuple[str, str, str], dict] = {}
    issues: list[SourceRowIssue] = []
    for row_index, row in enumerate(rows):
        try:
            source_type, identity_kind, source_key, display_name, evidence = _identity_for_row(row)
        except ValueError as exc:
            if str(exc) != "import_composite_payment_unresolved":
                raise
            issues.append(SourceRowIssue(row_index=row_index, code=str(exc)))
            continue
        key = (source_type, identity_kind, source_key)
        entry = groups.setdefault(
            key,
            {
                "display_name": display_name,
                "evidence": evidence,
                "currencies": set(),
                "row_count": 0,
                "legacy_source_account_keys": set(),
            },
        )
        entry["currencies"].add(_text(row.get("currency") or "CNY").upper())
        entry["row_count"] += 1
        entry["legacy_source_account_keys"].update(
            _legacy_source_account_keys(row, source_key)
        )

    return [
        SourceAccountGroup(
            group_id=_group_id(*key),
            source_type=key[0],
            identity_kind=key[1],
            source_account_key=key[2],
            display_name=value["display_name"],
            masked_evidence=value["evidence"],
            currencies=tuple(sorted(value["currencies"])),
            row_count=value["row_count"],
            legacy_source_account_keys=tuple(sorted(value["legacy_source_account_keys"])),
        )
        for key, value in groups.items()
    ], tuple(issues)


def scan_source_rows(rows: list[dict]) -> list[SourceAccountGroup]:
    """Group parsed cash rows, retaining the strict non-interactive contract."""
    groups, issues = scan_source_rows_with_issues(rows)
    if issues:
        raise ValueError(issues[0].code)
    return groups


def _account_choice(account: dict | None, currencies: tuple[str, ...], *, revision=None) -> dict:
    if account is None or not account.get("active"):
        return {
            "account_id": None,
            "account": None,
            "missing_currencies": (),
            "mapping_revision": revision,
        }
    supported = {str(value).upper() for value in account.get("currencies", ()) if value}
    missing = tuple(sorted(set(currencies) - supported))
    return {
        "account_id": int(account["id"]),
        "account": account,
        "missing_currencies": missing,
        "mapping_revision": revision,
    }


def historical_mapping_for_group(uow, group: SourceAccountGroup) -> dict | None:
    """Find the canonical mapping, with a deterministic legacy fallback."""
    keys = (group.source_account_key, *group.legacy_source_account_keys)
    found = []
    for source_key in keys:
        mapping = uow.statement_account_mappings.get(
            source_type=group.source_type,
            identity_kind=group.identity_kind,
            source_account_key=source_key,
        )
        if mapping is not None:
            found.append(mapping)
    if not found:
        return None
    if found[0]["source_account_key"] == group.source_account_key:
        return found[0]
    if len({int(item["account_id"]) for item in found}) != 1:
        return None
    return found[0]


def suggest_mapping(uow, group: SourceAccountGroup) -> dict:
    """Return a silent preselection for one group; never write a decision."""
    historical = historical_mapping_for_group(uow, group)
    if historical is not None:
        account = uow.accounts.get_by_id(historical["account_id"])
        if account is not None and account.get("active"):
            return _account_choice(account, group.currencies, revision=historical["revision"])

    if group.identity_kind not in {"card_tail", "account_identifier"}:
        return _account_choice(None, group.currencies)
    aliases = uow.account_aliases.find_by_value(group.identity_kind, group.source_account_key)
    active_ids = set()
    active_accounts = {}
    for alias in aliases:
        account = uow.accounts.get_by_id(alias["account_id"])
        if account is not None and account.get("active"):
            active_ids.add(int(account["id"]))
            active_accounts[int(account["id"])] = account
    if len(active_ids) != 1:
        return _account_choice(None, group.currencies)
    return _account_choice(active_accounts[next(iter(active_ids))], group.currencies)


def new_account_draft(group: SourceAccountGroup) -> dict:
    """Build a session-only draft after an explicit create-new selection."""
    name = group.display_name.strip() or "新账户"
    account_type = "loan" if (group.source_type == "icbc_credit" or "花呗" in name) else "cash"
    return {"name": name, "type": account_type, "currencies": list(group.currencies)}


def apply_saved_mappings(uow, rows: list[dict]) -> list[dict]:
    """Apply confirmed workspace mappings for non-interactive cash paths.

    This is intentionally read-only.  CLI import and cash conversion can use
    the same database fact as the Web flow, but cannot silently create or
    change an account when a mapping is missing.
    """
    groups = scan_source_rows(rows)
    for group in groups:
        suggestion = suggest_mapping(uow, group)
        account_id = suggestion["account_id"]
        if account_id is None:
            raise ValueError(
                f"来源账户尚未完成映射：{group.masked_evidence}；请先在导入页面确认账户映射"
            )
        account = uow.accounts.get_by_id(account_id)
        if account is None or not account.get("active") or account.get("type") not in {"cash", "loan", "lend"}:
            raise ValueError("账单账户映射目标不可用，请先在导入页面重新确认账户映射")
        for row in rows:
            if source_identity_key(row) != (group.source_type, group.identity_kind, group.source_account_key):
                continue
            row["account_name"] = account["name"]
    return rows


class DatabaseMappedStatementParser:
    """Adapter for CLI/export paths that must consume database mappings."""

    def __init__(self, source_parser, uow):
        self._source_parser = source_parser
        self._uow = uow

    def parse(self, command):
        rows = self._source_parser.parse_source_rows(command)
        with self._uow as uow:
            mapped = apply_saved_mappings(uow, [dict(row) for row in rows])
            uow.rollback()
        return mapped
