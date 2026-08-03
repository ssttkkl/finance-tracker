"""Formal record-type gates used by relation matching."""
from __future__ import annotations

from typing import Any, Mapping


REFUND_RECORD_TYPE = "refund"
CONSUMPTION_RECORD_TYPE = "consumption"
TRANSFER_IN_RECORD_TYPE = "transfer_in"
TRANSFER_OUT_RECORD_TYPE = "transfer_out"
TRANSFER_REVERSAL_RECORD_TYPE = "transfer_reversal"
WITHDRAWAL_IN_RECORD_TYPE = "withdrawal_in"
WITHDRAWAL_OUT_RECORD_TYPE = "withdrawal_out"
REPAYMENT_RECORD_TYPE = "repayment"
INCOME_RECORD_TYPE = "income"
FX_OUT_RECORD_TYPE = "fx_out"
FX_IN_RECORD_TYPE = "fx_in"


def _payload(fact: Any) -> Mapping[str, Any]:
    value = getattr(fact, "raw_payload", None)
    return value if isinstance(value, Mapping) else {}


def is_refund_in(fact: Any) -> bool:
    """正式退款入账角色；不从文本或来源摘要推断。"""
    return getattr(fact, "record_type", "") == REFUND_RECORD_TYPE and fact.signed_amount > 0


def is_original_refund_expense(fact: Any) -> bool:
    """微信双行导入中已退款状态对应的原消费行。"""
    return (
        getattr(fact, "record_type", "") == REFUND_RECORD_TYPE
        and fact.signed_amount < 0
        and str(_payload(fact).get("offset_role") or "") == "expense"
    )


def is_refund_expense_candidate(fact: Any) -> bool:
    """退款关系允许的负向消费对侧。"""
    return (
        fact.signed_amount < 0
        and getattr(fact, "record_type", "") == CONSUMPTION_RECORD_TYPE
    ) or is_original_refund_expense(fact)


def is_payment_mirror_expense(fact: Any) -> bool:
    return (
        fact.signed_amount < 0
        and getattr(fact, "record_type", "") == CONSUMPTION_RECORD_TYPE
    ) or is_original_refund_expense(fact)


def is_payment_mirror_refund(fact: Any) -> bool:
    return is_refund_in(fact)


def is_fx_out_record(fact: Any) -> bool:
    """来源已明确分类的个人购汇转出。"""
    return (
        getattr(fact, "record_type", "") == FX_OUT_RECORD_TYPE
        and fact.signed_amount < 0
    )


def is_fx_in_record(fact: Any) -> bool:
    """来源已明确分类的个人购汇转入。"""
    return (
        getattr(fact, "record_type", "") == FX_IN_RECORD_TYPE
        and fact.signed_amount > 0
    )


def is_transfer_out_record(fact: Any) -> bool:
    return (
        getattr(fact, "record_type", "") == TRANSFER_OUT_RECORD_TYPE
        and fact.signed_amount < 0
    )


def is_withdrawal_in_record(fact: Any) -> bool:
    return (
        getattr(fact, "record_type", "") == WITHDRAWAL_IN_RECORD_TYPE
        and fact.signed_amount > 0
    )


def is_withdrawal_out_record(fact: Any) -> bool:
    return (
        getattr(fact, "record_type", "") == WITHDRAWAL_OUT_RECORD_TYPE
        and fact.signed_amount < 0
    )


def is_repayment_out_record(fact: Any) -> bool:
    return (
        getattr(fact, "record_type", "") == REPAYMENT_RECORD_TYPE
        and fact.signed_amount < 0
    )


def is_transfer_in_record(fact: Any) -> bool:
    return (
        getattr(fact, "record_type", "") == TRANSFER_IN_RECORD_TYPE
        and fact.signed_amount > 0
    )


def is_loan_repayment_in(fact: Any) -> bool:
    """信用账户入账的正式类型边界。

    工行信用卡账单中的手机银行还款入账会被导入分类为 income；只有贷款账户
    的正向 income/transfer_in/repayment 才能进入该特殊对侧池。
    """
    return (
        getattr(fact, "account_type", "") == "loan"
        and fact.signed_amount > 0
        and getattr(fact, "record_type", "") in {
            INCOME_RECORD_TYPE,
            TRANSFER_IN_RECORD_TYPE,
            REPAYMENT_RECORD_TYPE,
        }
    )


def is_transfer_candidate_in(fact: Any) -> bool:
    return (
        is_transfer_in_record(fact)
        or is_withdrawal_in_record(fact)
        or is_loan_repayment_in(fact)
    )


def is_transfer_candidate_out(fact: Any) -> bool:
    return (
        is_transfer_out_record(fact)
        or is_withdrawal_out_record(fact)
        or is_repayment_out_record(fact)
    )
