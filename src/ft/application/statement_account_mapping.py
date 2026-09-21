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


def _normalize_icbc_account_identifier(value: object) -> str:
    """Return a complete or PDF-stable ICBC identifier, never for display."""
    text = re.sub(r"[\s\-－—()（）]", "", _text(value))
    if re.fullmatch(r"\d{12,19}", text):
        return text
    masked = re.fullmatch(r"\d{4,6}[*＊]{2,}\d{4}", text)
    if masked:
        return text.replace("＊", "*")
    return ""


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


def parse_alipay_payment_components(row: dict) -> list[dict]:
    """Return stable component drafts for an Alipay source row.

    Alipay's payment-method column is evidence, not an allocation ledger.  In
    particular, values such as ``银行卡(1200)`` contain a card tail rather than
    an amount.  We therefore only accept an amount when the source explicitly
    labels it with ``元`` or a currency sign; all other funding components are
    returned with ``amount=None`` so the import confirmation can require the
    user to complete the allocation.

    Discount/non-funding tokens remain in ``source_label`` for audit display but
    do not become balance components.  The raw source row remains untouched in
    ``_source_payload``/``source_payload``.
    """
    if str(row.get("bill_source") or row.get("source_type") or "").strip() != "alipay":
        return []
    raw = _text(row.get("payment_method"))
    if not raw:
        raw = "支付宝余额"
    result: list[dict] = []
    for ordinal, token in enumerate(raw.split("&")):
        source_label = _text(token)
        normalized = _normalize_alipay_payment_component(source_label)
        if not normalized:
            # Non-funding promotions are deliberately evidence-only.
            continue
        explicit_amount = None
        match = re.search(r"(?:¥|￥)\s*(\d+(?:\.\d+)?)|\b(\d+(?:\.\d+)?)\s*元", source_label)
        if match:
            explicit_amount = format(Decimal(match.group(1) or match.group(2)), "f")
        result.append({
            "ordinal": len(result),
            "source_label": source_label,
            "account_key": normalized,
            "amount": explicit_amount,
            "amount_required": explicit_amount is None,
        })
    if not result:
        result.append({
            "ordinal": 0,
            "source_label": raw,
            "account_key": "支付宝余额",
            "amount": None,
            "amount_required": True,
        })
    aggregate = len(result) > 1
    for component in result:
        component["kind"] = "aggregate" if aggregate else "atomic"
    return result


def alipay_component_allocation_status(row: dict) -> str:
    """Return ``atomic``, ``ready`` or ``requires_allocation`` for a row."""
    components = parse_alipay_payment_components(row)
    if len(components) == 1:
        return "atomic"
    return "ready" if all(not item["amount_required"] for item in components) else "requires_allocation"


def build_component_allocation_draft(row: dict, *, allocations=None) -> dict:
    """Build the import wire contract for a row's account allocations.

    ``allocations`` is an optional confirmation payload keyed by account key or
    ordinal.  The helper validates only decimal syntax and exact conservation;
    account existence and persistence remain application-service concerns.
    """
    components = parse_alipay_payment_components(row)
    total = Decimal(str(row.get("amount") or "0"))
    signed_total = total
    if len(components) == 1 and components[0].get("amount") is None:
        components[0]["amount"] = format(signed_total, "f")
        components[0]["amount_required"] = False
    if isinstance(allocations, dict):
        supplied = allocations.get("components") or allocations.get("allocations")
    else:
        supplied = allocations
    supplied = supplied if isinstance(supplied, (list, tuple)) else None
    if supplied is not None:
        if len(supplied) != len(components):
            raise ValueError("import_component_allocation_incomplete")
        for component, value in zip(components, supplied, strict=True):
            raw_amount = value.get("amount") if isinstance(value, dict) else value
            try:
                amount = Decimal(str(raw_amount))
            except (InvalidOperation, ValueError, TypeError) as exc:
                raise ValueError("import_component_amount_invalid") from exc
            if not amount.is_finite() or amount < 0:
                raise ValueError("import_component_amount_invalid")
            # The wire form accepts non-negative allocation magnitudes; the
            # ledger stores the parent's direction on every component.
            component["amount"] = format(amount if total >= 0 else -amount, "f")
            component["amount_required"] = False
            if isinstance(value, dict) and value.get("account_id") not in (None, ""):
                component["account_id"] = int(value["account_id"])
    elif total < 0 and all(
        item.get("amount") is None or Decimal(str(item["amount"])) >= 0
        for item in components
    ):
        for component in components:
            if component.get("amount") is not None:
                component["amount"] = format(-Decimal(str(component["amount"])), "f")
    amounts = [Decimal(str(item["amount"])) for item in components if item.get("amount") is not None]
    complete = len(amounts) == len(components)
    conserved = complete and sum(amounts, Decimal("0")) == signed_total
    return {
        "record_id": str(row.get("record_id") or row.get("_fact_id") or ""),
        "cash_granularity": "aggregate" if len(components) > 1 else "atomic",
        "status": "ready" if conserved else ("requires_allocation" if len(components) > 1 else "ready"),
        "total_amount": format(signed_total, "f"),
        "components": components,
        "conserved": conserved,
    }


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


def source_component_identity_keys(row: dict) -> tuple[tuple[str, str, str], ...]:
    """Return one mapping identity for every real funding component.

    A composite Alipay row belongs to several source-account groups.  The
    parent row remains a single import item; these identities only drive the
    account mapping UI and are later attached to the component allocation.
    """
    source_type = _text(row.get("bill_source") or row.get("source_type"))
    if source_type == "alipay":
        # A missing payment-method value is not evidence for the default
        # wallet.  Only zero-value informational rows may fall back to the
        # wallet label; financial rows must stop for explicit mapping.
        if not _text(row.get("payment_method")) and not _alipay_amount_is_zero(row):
            return ()
        components = parse_alipay_payment_components(row)
        keys: list[tuple[str, str, str]] = []
        for component in components:
            key = _text(component.get("account_key"))
            if not key:
                continue
            identity = (source_type, "payment_method", key)
            if identity not in keys:
                keys.append(identity)
        return tuple(keys)
    if source_type == "wechat":
        key = _normalize_wechat_payment_identity(row.get("payment_method"))
        return ((source_type, "payment_method", key),) if key else ()
    try:
        source, identity_kind, source_key, _display, _evidence = _identity_for_row(row)
    except ValueError:
        return ()
    return ((source, identity_kind, source_key),)


def _identity_for_row(row: dict) -> tuple[str, str, str, str, str]:
    source_type = _text(row.get("bill_source") or row.get("source_type"))
    if source_type not in _CASH_SOURCES:
        raise ValueError("账单记录缺少受支持的来源账户身份")

    display_name = _text(row.get("source_display_name"))
    if source_type in {"alipay", "wechat"}:
        keys = source_component_identity_keys(row)
        if len(keys) != 1:
            raise ValueError("import_composite_payment_unresolved")
        source_key = keys[0][2]
        identity_kind = "payment_method"
        if not source_key:
            raise ValueError("业务行无法识别来源账户")
        display_name = display_name or source_key
        evidence = display_name
    elif source_type == "icbc_credit":
        source_key = _normalize_icbc_account_identifier(
            row.get("_source_account_identifier") or row.get("file_account_key")
        )
        identity_kind = "file_account"
        if not source_key:
            raise ValueError("业务行无法识别来源账户")
        display_name = display_name or "信用卡"
        evidence = f"{display_name}（尾号 {_card_tail(source_key)}）"
    elif source_type == "ccb_debit":
        source_key = _card_tail(row.get("card_number"))
        identity_kind = "card_tail"
        if not source_key:
            raise ValueError("业务行无法识别来源账户")
        display_name = display_name or "建设银行"
        evidence = f"{display_name}（尾号 {source_key}）"
    elif source_type == "icbc_asia":
        source_key = _text(row.get("_source_account_identifier"))
        identity_kind = "account_identifier"
        if not source_key:
            raise ValueError("业务行无法识别来源账户")
        display_name = display_name or "工银亚洲活期账户"
        evidence = f"{display_name}（尾号 {_card_tail(row.get('card_number')) or '未知'}）"
    else:
        # ICBC debit parser has a file-level card-number contract.
        stable_source_key = _normalize_icbc_account_identifier(
            row.get("_source_account_identifier") or row.get("file_account_key")
        )
        source_key = stable_source_key
        identity_kind = "file_account"
        if not source_key:
            raise ValueError("业务行无法识别来源账户")
        display_name = display_name or "工商银行借记卡"
        evidence = f"{display_name}（尾号 {_card_tail(stable_source_key) or '未知'}）"

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
            identities = source_component_identity_keys(row)
            if not identities:
                raise ValueError("业务行无法识别来源账户")
        except ValueError as exc:
            if str(exc) != "import_composite_payment_unresolved":
                raise
            issues.append(SourceRowIssue(row_index=row_index, code=str(exc)))
            continue
        for source_type, identity_kind, source_key in identities:
            if source_type == "alipay":
                display_name = source_key
                evidence = source_key
            else:
                _source, _kind, _key, display_name, evidence = _identity_for_row({**row, "payment_method": source_key}) if source_type in {"wechat"} else _identity_for_row(row)
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
    lookups = [
        (group.identity_kind, group.source_account_key),
        *((group.identity_kind, key) for key in group.legacy_source_account_keys),
    ]
    found = []
    seen = set()
    for identity_kind, source_key in lookups:
        if (identity_kind, source_key) in seen:
            continue
        seen.add((identity_kind, source_key))
        mapping = uow.statement_account_mappings.get(
            source_type=group.source_type,
            identity_kind=identity_kind,
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

    if group.source_type == "icbc_credit" and group.identity_kind == "file_account":
        alias_lookups = [("account_identifier", group.source_account_key)]
    else:
        alias_lookups = [(group.identity_kind, group.source_account_key)]
    if group.identity_kind not in {"card_tail", "account_identifier", "file_account"}:
        return _account_choice(None, group.currencies)
    aliases = []
    for alias_type, alias_value in alias_lookups:
        if not alias_value:
            continue
        aliases.extend(uow.account_aliases.find_by_value(alias_type, alias_value))
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

    This is intentionally read-only.  Import and preview flows can use
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
            identities = source_component_identity_keys(row)
            target_key = (group.source_type, group.identity_kind, group.source_account_key)
            if target_key not in identities:
                continue
            if len(identities) == 1:
                row["account_name"] = account["name"]
            else:
                mappings = dict(row.get("component_account_names") or {})
                mappings[group.source_account_key] = account["name"]
                row["component_account_names"] = mappings
                row["component_account_ids"] = {
                    **dict(row.get("component_account_ids") or {}),
                    group.source_account_key: int(account["id"]),
                }

    # Saved mappings identify component accounts but cannot infer an amount
    # absent from the source row. Materialize the same draft used by the
    # interactive preview so non-interactive import fails before any write
    # with the actionable allocation error.
    for row in rows:
        identities = source_component_identity_keys(row)
        if len(identities) <= 1:
            continue
        draft = build_component_allocation_draft(row)
        names = dict(row.get("component_account_names") or {})
        ids = dict(row.get("component_account_ids") or {})
        for component in draft["components"]:
            key = component.get("account_key")
            component["account_id"] = ids.get(key)
            component["account_name"] = names.get(key, "")
        row["account_name"] = ""
        row["component_allocation"] = draft
        row["components"] = draft["components"]
    return rows


class DatabaseMappedStatementParser:
    """Adapter for import/preview paths that consume database mappings."""

    def __init__(self, source_parser, uow):
        self._source_parser = source_parser
        self._uow = uow

    def parse(self, command):
        rows = [dict(row) for row in self._source_parser.parse_source_rows(command)]
        _, issues = scan_source_rows_with_issues(rows)
        issue_indexes = {issue.row_index for issue in issues}
        importable_rows = [
            row for index, row in enumerate(rows) if index not in issue_indexes
        ]
        if issues:
            skipped_rows = [
                {
                    "row_index": issue.row_index,
                    "record_id": str(rows[issue.row_index].get("record_id") or ""),
                    "code": issue.code,
                }
                for issue in issues
            ]
            source_meta = {}
            for row in rows:
                if isinstance(row.get("_import_meta"), dict):
                    source_meta = dict(row["_import_meta"])
                    break
            acceptance = dict(source_meta.get("acceptance") or {})
            acceptance["source_lines"] = max(
                int(acceptance.get("source_lines") or 0), len(rows),
            )
            acceptance["fact_lines"] = len(importable_rows)
            acceptance["skipped_composite_payment"] = sum(
                issue.code == "import_composite_payment_unresolved" for issue in issues
            )
            source_meta.update({
                "acceptance": acceptance,
                "skipped_rows": skipped_rows,
                "skipped_composite_payment": acceptance["skipped_composite_payment"],
            })
        else:
            source_meta = {}
        with self._uow as uow:
            mapped = apply_saved_mappings(uow, importable_rows)
            uow.rollback()
        if issues:
            if mapped:
                mapped[0] = dict(mapped[0])
                mapped[0]["_import_meta"] = source_meta
            else:
                # StatementImportService recognizes this metadata-only result as
                # a successful zero-row import when every source row is whitelisted.
                mapped = [{"_import_meta": source_meta}]
        return mapped
