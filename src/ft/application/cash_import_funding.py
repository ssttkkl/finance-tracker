"""已解析行的账户组和资金组成项；不访问数据库，也不创建账户。"""
from __future__ import annotations

from ft.cash_diagnostics import cash_diagnostic_scope, row_locations

from collections import OrderedDict
from collections.abc import Mapping
from ft.copying import copy_value
from dataclasses import dataclass, field
from decimal import Decimal, localcontext
import re

from ft.application.statement_account_mapping import parse_alipay_payment_components, _normalize_alipay_payment_component, _normalize_wechat_payment_identity
from ft.domain.cash_record_policy import CashRecordPolicyError, validate_cash_record_for_posting
from ft.domain.cash_source_matching import _digest
from ft.domain.cash_source_scope import source_account_key
from ft.domain.decimal import exact_decimal


_BANK_SOURCES = frozenset({"icbc_credit", "icbc_debit", "ccb_debit", "icbc_asia"})
_MONEY = re.compile(r"[¥￥]\s*([+-]?[0-9][0-9,]*(?:\.[0-9]+)?)(?![0-9A-Za-z.,+-])|(?<![0-9A-Za-z.,+-])([+-]?[0-9][0-9,]*(?:\.[0-9]+)?)\s*元")
_DECIMAL = re.compile(r"[+]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\.[0-9]{1,18})?")


class CashFundingInputError(ValueError):
    def __init__(self):
        super().__init__("funding_selection_invalid")


@dataclass(frozen=True)
class CashFundingGroup:
    source_account_ref: str
    source_type: str
    identity_kind: str
    origin_key: str = field(repr=False)
    identity_key: str = field(repr=False)
    display_name: str
    currencies: tuple[str, ...]
    row_refs: tuple[str, ...]


@dataclass(frozen=True)
class _FundingPart:
    group_ref: str | None
    amount: str | None


@dataclass(frozen=True)
class CashFundingRow:
    row_ref: str
    fields: dict = field(repr=False)
    parts: tuple[_FundingPart, ...]
    issue_codes: tuple[str, ...] = ()
    error_code: str | None = None

    @property
    def group_refs(self):
        return tuple(dict.fromkeys(part.group_ref for part in self.parts if part.group_ref is not None))


@dataclass(frozen=True)
class CashFundingScan:
    groups: tuple[CashFundingGroup, ...]
    rows: tuple[CashFundingRow, ...]


@dataclass(frozen=True)
class CashFundingResolution:
    row_ref: str
    fields: dict = field(repr=False)
    group_refs: tuple[str, ...]
    issue_codes: tuple[str, ...]
    ready: bool
    error_code: str | None = None
    selected_groups: tuple[tuple[str, str], ...] = field(default=(), repr=False)


def _sum(values):
    with localcontext() as context:
        context.prec = 80
        return sum(values, Decimal(0))


def _magnitude(value):
    return exact_decimal(value).copy_abs() if value is not None else None


def _label(value):
    return re.sub(r"[0-9](?:[\s\-－—·]*[0-9]){7,}",
        lambda match: "尾号 " + re.sub(r"[^0-9]", "", match[0])[-4:], value.replace("\n", " ").strip())[:128]


def _alipay_parts(method, amount, product_description=""):
    if method.count("&") >= 64:
        raise ValueError("funding_parts_too_large")
    parts = parse_alipay_payment_components({"source_type": "alipay", "payment_method": method, "amount": amount})
    # Payment-only text such as "30元" and promotion tokens do not identify
    # an account. Do not turn those labels into mapping groups.
    fallback = not any(_normalize_alipay_payment_component(_MONEY.sub("", token)) for token in method.split("&"))
    if fallback:
        fallback_account = "余额宝" if isinstance(product_description, str) and "余额宝" in product_description else "支付宝余额"
        return [("payment_method", fallback_account, fallback_account, None)], []
    result, issues = [], []
    for index, part in enumerate(parts):
        token, value = part["source_label"], None
        matches = list(_MONEY.finditer(token))
        if any(marker in token for marker in ("¥", "￥", "元")):
            try:
                if len(matches) != 1:
                    raise ValueError
                raw = next(value for value in matches[0].groups() if value is not None)
                if not _DECIMAL.fullmatch(raw):
                    raise ValueError
                value = format(exact_decimal(raw.replace(",", "")), "f")
            except (ValueError, ArithmeticError):
                issues.append("allocation_amount_invalid")
        key = _MONEY.sub("", part["account_key"])
        key = _normalize_alipay_payment_component(key)
        if key:
            result.append(("payment_method", key, _label(key), value))
        else:
            result.append(("unresolved", f"unknown:{index}", "", value))
            issues.append("funding_unrecognized")
    return result, issues


def _parts(file, row):
    document = file.document
    source = document.source_type
    owner = row.source_account_identifier or document.source_account_identifier
    scope = source_account_key(source, owner, scope_label=file.source_scope_label)
    local = _digest("funding-file-v1", source, file.digest, document.parser_version)
    origin = scope or local
    if source == "alipay":
        source_payload = row.fields.get("source_payload")
        product_description = source_payload.get("商品说明", "") if isinstance(source_payload, Mapping) else ""
        parts, issues = _alipay_parts(row.payment_method.strip(), row.fields.get("amount"), product_description)
    elif source == "wechat":
        key = _normalize_wechat_payment_identity(row.payment_method)
        parts, issues = [("payment_method", key, _label(key), None)], []
        if not key:
            parts, issues = [("unresolved", "unknown", "", None)], ["funding_unrecognized"]
    elif source in _BANK_SOURCES:
        masked = None
        if source == "icbc_credit" and owner is None:
            cells = {re.sub(r"\s+", "", key): value for key, value in row.fields.get("source_payload", {}).items()}
            value = re.sub(r"\s+", "", cells.get("交易卡号", ""))
            if re.fullmatch(r"[0-9]{4,6}[*＊]{2,}[0-9]{4}", value):
                masked = value.replace("＊", "*")
        label = row.source_explanation.get("source_display_name")
        if not isinstance(label, str) or not label:
            raise ValueError("funding_source_display_name_missing")
        parts, issues = [("file_account" if owner else "file_local", owner or masked or "unknown", label, None)], []
    else:
        raise ValueError("funding_source_unsupported")
    return [(kind, local if kind == "file_local" else origin, key, label, value) for kind, key, label, value in parts], issues


def scan_cash_funding(files) -> CashFundingScan:
    from ft.adapters.cash_statement_rows import ParsedCashDocument, ParsedCashRow
    from ft.application.parsed_import_staging import ParsedImportFile
    groups, rows, refs, group_keys = OrderedDict(), [], set(), {}
    for file in files:
        if file.file_ref in refs:
            raise CashFundingInputError
        refs.add(file.file_ref)
        if file.status != "parsed" or file.document is None:
            continue
        parts_cache = {}
        for row in file.document.rows:
            if row.disposition == "skipped" or row.fields is None:
                continue
            ref, fields = f"{file.file_ref}:r{row.position}", copy_value(row.fields)
            try:
                with cash_diagnostic_scope("row_planning", row_locations((ref,)), scope="row", files=(file,)):
                    if fields.get("currency") is not None and (not isinstance(fields["currency"], str) or not re.fullmatch(r"[A-Z]{3}", fields["currency"])):
                        raise ValueError("funding_currency_invalid")
                    parameters = None
                    document = file.document
                    if (type(file) is ParsedImportFile and type(document) is ParsedCashDocument
                        and type(row) is ParsedCashRow and type(document.source_type) is str and document.source_type == "alipay"
                        and type(file.digest) is str and type(document.parser_version) is str
                        and type(row.fields) is dict and type(row.fields.get("source_payload")) is dict):
                        raw = (row.source_account_identifier, document.source_account_identifier, file.source_scope_label,
                            row.payment_method, row.fields.get("amount"), row.fields["source_payload"].get("商品说明", ""))
                        if all(value is None or type(value) is str for value in raw):
                            parameters = raw
                    if parameters is not None and parameters in parts_cache:
                        parts, issues = parts_cache[parameters]
                    else:
                        parts, issues = _parts(file, row)
                        if parameters is not None and len(parts_cache) < 128:
                            parts_cache[parameters] = parts, issues
                    values = []
                    for kind, origin, key, label, amount in parts:
                        if kind == "unresolved":
                            values.append(_FundingPart(None, amount))
                            continue
                        identity = (file.document.source_type, kind, origin, key)
                        if all(type(value) is str for value in identity):
                            if identity not in group_keys:
                                group_keys[identity] = _digest("funding-group-v1", *identity)
                            group_ref = group_keys[identity]
                        else:
                            group_ref = _digest("funding-group-v1", *identity)
                        if group_ref not in groups:
                            groups[group_ref] = {"source_account_ref": group_ref, "source_type": file.document.source_type,
                                "identity_kind": kind, "origin_key": origin, "identity_key": key,
                                "display_name": label, "currencies": set(), "row_refs": dict()}
                        group = groups[group_ref]
                        if fields.get("currency"):
                            group["currencies"].add(fields["currency"])
                        group["row_refs"][ref] = None
                        values.append(_FundingPart(group_ref, amount))
                    rows.append(CashFundingRow(ref, fields, tuple(values), tuple(dict.fromkeys((*row.issue_codes, *issues)))))
            except (ValueError, ArithmeticError):
                rows.append(CashFundingRow(ref, fields, (), tuple(row.issue_codes), "funding_unreadable"))
    return CashFundingScan(tuple(CashFundingGroup(**{**group, "currencies": tuple(sorted(group["currencies"])), "row_refs": tuple(group["row_refs"])}) for group in groups.values()), tuple(rows))


def _selection_accounts(scan, selections, accounts):
    refs = {group.source_account_ref for group in scan.groups}
    if not isinstance(selections, Mapping) or not isinstance(accounts, Mapping) or set(selections) - refs:
        raise CashFundingInputError
    result = {}
    for ref in refs:
        choice = selections.get(ref, {})
        if not isinstance(choice, Mapping) or set(choice) - {"account_id", "expected_account_revision"}:
            raise CashFundingInputError
        selected, revision = choice.get("account_id"), choice.get("expected_account_revision")
        if selected is None:
            if revision is not None:
                raise CashFundingInputError
            result[ref] = (None, "account_missing")
            continue
        if not isinstance(selected, str) or not selected or type(revision) is not int or revision < 1:
            raise CashFundingInputError
        account = accounts.get(selected)
        if (not isinstance(account, Mapping) or str(account.get("id", account.get("account_id"))) != selected
            or account.get("active") is not True or account.get("type") not in {"cash", "loan", "lend"}):
            result[ref] = (None, "account_unavailable")
        else:
            result[ref] = (account, None)
    return result


def resolve_cash_funding(scan: CashFundingScan, *, selections, accounts) -> tuple[CashFundingResolution, ...]:
    selected = _selection_accounts(scan, selections, accounts)
    result = []
    for row in scan.rows:
        fields = copy_value(row.fields)
        has_unresolved_part = any(part.group_ref is None for part in row.parts)
        issues = [code for code in row.issue_codes if code != "funding_unrecognized"
            or has_unresolved_part or any(selected[ref][0] is None for ref in row.group_refs)]
        merged, used = OrderedDict(), set()
        try:
            with cash_diagnostic_scope("row_planning", row_locations((row.row_ref,)), scope="row"):
                total = exact_decimal(fields["amount"]) if fields.get("amount") is not None else None
                for index, part in enumerate(row.parts):
                    account, issue = selected[part.group_ref] if part.group_ref is not None else (None, None)
                    if issue:
                        issues.append(issue)
                    if account is not None and fields.get("currency") is not None and fields["currency"] not in account.get("currencies", ()):
                        issues.append("account_currency_missing")
                    account_id = str(account.get("id", account.get("account_id"))) if account is not None else None
                    if account_id is not None and part.group_ref is not None:
                        used.add((part.group_ref, account_id))
                    key = (("account", account_id) if account_id else
                        ("source", part.group_ref) if part.group_ref is not None else ("unresolved", index))
                    values = merged.setdefault(key, {"account_id": account_id, "amounts": []})
                    values["amounts"].append(_magnitude(part.amount))
                components, magnitudes = [], []
                for index, value in enumerate(merged.values()):
                    known = [amount for amount in value["amounts"] if amount is not None]
                    magnitude = _sum(known) if len(known) == len(value["amounts"]) else None
                    if len(merged) == 1 and magnitude is None and total is not None:
                        if _sum(known) > total.copy_abs():
                            issues.append("component_total_mismatch")
                        else:
                            magnitude = total.copy_abs()
                    magnitudes.append(magnitude)
                    amount = magnitude if total is not None and total >= 0 else magnitude.copy_negate() if total is not None and magnitude is not None else None
                    components.append({"ordinal": index, "account_id": value["account_id"], "amount": format(amount, "f") if amount is not None else None, "currency": fields.get("currency")})
                known = [value for value in magnitudes if value is not None]
                if total is not None and (_sum(known) > total.copy_abs() or len(known) == len(magnitudes) and _sum(known) != total.copy_abs()):
                    issues.append("component_total_mismatch")
                if any(value is None for value in magnitudes):
                    issues.append("allocation_missing")
                fields.update(cash_granularity="aggregate" if len(components) > 1 else "atomic", components=components,
                    account_id=components[0]["account_id"] if len(components) == 1 else None)
                try:
                    validate_cash_record_for_posting(fields)
                except CashRecordPolicyError as error:
                    issues.append(error.code)
                result.append(CashFundingResolution(row.row_ref, fields, row.group_refs, tuple(dict.fromkeys(issues)),
                    not issues and row.error_code is None, row.error_code, tuple(sorted(used))))
        except (ValueError, ArithmeticError):
            result.append(CashFundingResolution(row.row_ref, fields, row.group_refs, tuple(dict.fromkeys(issues)), False, "funding_unreadable"))
    return tuple(result)
