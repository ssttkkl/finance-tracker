"""投资事件规范记录类型与记录子类型。"""
from __future__ import annotations


INVESTMENT_RECORD_TYPES = frozenset({
    "swap",
    "buy",
    "sell",
    "deposit",
    "withdraw",
    "dividend",
    "fee",
    "ipo",
    "checkin",
    "transfer",
    "fx_adjustment",
    "reward",
    "withdrawal_reversal",
    "cash_adjustment",
})

_SUBTYPES_BY_RECORD_TYPE = {
    "deposit": frozenset({"external_funding", "subaccount_transfer"}),
    "withdraw": frozenset({"external_funding", "subaccount_transfer"}),
    "fee": frozenset({
        "commission",
        "interest",
        "tax",
        "handling_fee",
        "fee_refund",
        "interest_refund",
        "tax_refund",
    }),
    "ipo": frozenset({"subscription_debit", "subscription_refund"}),
    "fx_adjustment": frozenset({"net_cash_adjustment"}),
    "reward": frozenset({"cash_reward"}),
    "withdrawal_reversal": frozenset({"withdrawal_refund"}),
    "cash_adjustment": frozenset({"unclassified"}),
}


def default_investment_record_subtype(record_type: str) -> str:
    normalized = str(record_type or "").strip().lower()
    if normalized in {"deposit", "withdraw"}:
        return "external_funding"
    if normalized == "fee":
        return "commission"
    if normalized == "fx_adjustment":
        return "net_cash_adjustment"
    if normalized == "reward":
        return "cash_reward"
    if normalized == "withdrawal_reversal":
        return "withdrawal_refund"
    if normalized == "cash_adjustment":
        return "unclassified"
    return "not_applicable"


def validate_investment_record_subtype(record_type: str, record_subtype: str) -> tuple[str, str]:
    normalized_type = str(record_type or "").strip().lower()
    normalized_subtype = str(record_subtype or "").strip().lower()
    if normalized_type not in INVESTMENT_RECORD_TYPES:
        raise ValueError(f"unsupported investment record_type: {record_type}")
    allowed = _SUBTYPES_BY_RECORD_TYPE.get(normalized_type, frozenset({"not_applicable"}))
    if normalized_subtype not in allowed:
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
