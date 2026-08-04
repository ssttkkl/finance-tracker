"""投资事件的经济事实类型与记录子类型。"""
from __future__ import annotations


INVESTMENT_RECORD_TYPES = frozenset({
    "funding",
    "trade",
    "income",
    "expense",
    "reversal",
    "subscription",
    "adjustment",
    "snapshot",
})

_SUBTYPES_BY_RECORD_TYPE = {
    "funding": frozenset({"external", "subaccount"}),
    "trade": frozenset({"security", "fx", "repo"}),
    "income": frozenset({"dividend_cash", "dividend_stock", "interest", "reward"}),
    "expense": frozenset({"commission", "tax", "interest", "handling_fee", "penalty"}),
    "reversal": frozenset({
        "expense_tax", "expense_interest", "expense_commission", "funding_withdrawal",
    }),
    "subscription": frozenset({"ipo_debit", "ipo_refund"}),
    "adjustment": frozenset({"fx_net", "manual", "unclassified"}),
    "snapshot": frozenset({"cash", "position"}),
}

_DEFAULT_SUBTYPE_BY_RECORD_TYPE = {
    "funding": "external",
    "trade": "security",
    "income": "dividend_cash",
    "expense": "commission",
    "reversal": "expense_commission",
    "subscription": "ipo_debit",
    "adjustment": "manual",
    "snapshot": "cash",
}


def default_investment_record_subtype(record_type: str) -> str:
    normalized = str(record_type or "").strip().lower()
    try:
        return _DEFAULT_SUBTYPE_BY_RECORD_TYPE[normalized]
    except KeyError as exc:
        raise ValueError(f"unsupported investment record_type: {record_type}") from exc


def validate_investment_record_subtype(record_type: str, record_subtype: str) -> tuple[str, str]:
    normalized_type = str(record_type or "").strip().lower()
    normalized_subtype = str(record_subtype or "").strip().lower()
    if normalized_type not in INVESTMENT_RECORD_TYPES:
        raise ValueError(f"unsupported investment record_type: {record_type}")
    if normalized_subtype not in _SUBTYPES_BY_RECORD_TYPE[normalized_type]:
        raise ValueError(
            f"invalid investment record_subtype {record_subtype!r} for record_type {record_type!r}"
        )
    return normalized_type, normalized_subtype


def normalize_investment_record_semantics(
    record_type: str,
    record_subtype: str | None = None,
) -> tuple[str, str]:
    normalized_type = str(record_type or "").strip().lower()
    normalized_subtype = (
        str(record_subtype).strip().lower()
        if record_subtype not in (None, "")
        else default_investment_record_subtype(normalized_type)
    )
    return validate_investment_record_subtype(normalized_type, normalized_subtype)
