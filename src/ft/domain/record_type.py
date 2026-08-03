"""Source-native classification for imported cash transactions."""
from __future__ import annotations

from decimal import Decimal
from enum import Enum
from typing import Mapping


class CashRecordType(str, Enum):
    CONSUMPTION = "consumption"
    REFUND = "refund"
    REVERSAL = "reversal"
    TRANSFER_REVERSAL = "transfer_reversal"
    WITHDRAWAL_IN = "withdrawal_in"
    WITHDRAWAL_OUT = "withdrawal_out"
    TRANSFER_IN = "transfer_in"
    TRANSFER_OUT = "transfer_out"
    REPAYMENT = "repayment"
    INCOME = "income"
    INVESTMENT_IN = "investment_in"
    INVESTMENT_OUT = "investment_out"
    INTEREST = "interest"
    FEE = "fee"
    FX_IN = "fx_in"
    FX_OUT = "fx_out"
    OTHER = "other"


VALID_CASH_RECORD_TYPES = frozenset(item.value for item in CashRecordType)


def _text(row: Mapping[str, object], *keys: str) -> str:
    return " ".join(
        str(row.get(key) or "").strip()
        for key in keys
        if str(row.get(key) or "").strip()
    )


def _is_income(row: Mapping[str, object]) -> bool:
    direction = str(
        row.get("_wechat_direction")
        or row.get("_alipay_direction")
        or row.get("direction")
        or ""
    ).strip()
    if direction in {"收入", "贷", "in", "IN"}:
        return True
    if direction in {"支出", "借", "out", "OUT"}:
        return False
    category = str(row.get("category") or "").strip().lower()
    if category == "income":
        return True
    try:
        return Decimal(str(row.get("amount") or "0")) >= 0
    except Exception:  # pragma: no cover - parsers validate amounts before this point
        return False


def _is_reversal(row: Mapping[str, object], txn_type: str, summary: str) -> bool:
    if bool(row.get("_is_reversal")):
        return True
    if str(row.get("_debit_offset_type") or "").strip() == "reversal":
        return True
    if summary in {"撤销", "撤销交易", "冲正", "撤销冲正"}:
        return True
    return any(token in txn_type for token in ("撤销", "撤销交易", "撤销冲正", "冲正"))


def _is_p2p_transfer_return(
    source: str,
    row: Mapping[str, object],
    txn_type: str,
    note: str,
) -> bool:
    if source not in {"wechat", "alipay"}:
        return False
    if source == "wechat":
        is_p2p = any(token in txn_type for token in ("转账", "红包", "群收款"))
        is_p2p = is_p2p or "qq红包" in note.lower()
    else:
        is_p2p = "转账红包" in txn_type
    if not is_p2p:
        return False
    status = _text(row, "status", "platform_status")
    if any(token in f"{txn_type} {status}" for token in ("退款", "退回", "退还")):
        return True
    return bool(row.get("_refund_signal") or row.get("refund_signal"))


def _is_refund(row: Mapping[str, object], txn_type: str, summary: str) -> bool:
    if _is_reversal(row, txn_type, summary):
        return False
    if any(bool(row.get(key)) for key in (
        "_is_refund", "_refund_signal", "refund_signal",
        "_ccb_refund_signal",
    )):
        return True
    if str(row.get("_debit_offset_type") or "").strip() == "refund":
        return True
    if summary in {"退货", "退款", "消费退货"}:
        return True
    return "退款" in txn_type or txn_type.endswith("-退货")


def _is_repayment(source: str, row: Mapping[str, object], txn_type: str, summary: str) -> bool:
    if source == "ccb_debit" and summary == "代理收款":
        return True
    if txn_type in {"信用借还", "信用卡还款"}:
        return True
    if any(token in summary for token in ("还款", "购汇还款", "自动还款", "主动还款")):
        return True
    return False


def _directional_transfer(row: Mapping[str, object]) -> str:
    return CashRecordType.TRANSFER_IN.value if _is_income(row) else CashRecordType.TRANSFER_OUT.value


def _directional_withdrawal(row: Mapping[str, object]) -> str:
    return (
        CashRecordType.WITHDRAWAL_IN.value
        if _is_income(row)
        else CashRecordType.WITHDRAWAL_OUT.value
    )


def classify_cash_record_type(
    source: str,
    row: Mapping[str, object],
) -> str:
    """Classify one parsed cash row from its source-native fields.

    ``category`` and the numeric amount are only used to resolve a source field's
    direction. They never turn an otherwise ordinary negative row into a transfer.
    """
    source = str(source or row.get("bill_source") or row.get("source_type") or "").strip()
    txn_type = str(row.get("txn_type") or "").strip()
    summary = str(row.get("summary") or "").strip()
    note = _text(row, "note", "counterparty", "location", "acct_name_raw")
    text = f"{txn_type} {summary} {note}"

    if _is_reversal(row, txn_type, summary):
        return CashRecordType.REVERSAL.value
    if _is_p2p_transfer_return(source, row, txn_type, note):
        return CashRecordType.TRANSFER_REVERSAL.value
    if _is_refund(row, txn_type, summary):
        return CashRecordType.REFUND.value
    if _is_repayment(source, row, txn_type, summary):
        return CashRecordType.REPAYMENT.value

    offset_type = str(
        row.get("_debit_offset_type") or row.get("offset_type") or ""
    ).strip()
    if offset_type in {"benefit_rebate", "campaign_cashback"}:
        return CashRecordType.INCOME.value
    if offset_type == "fee_reversal":
        return CashRecordType.FEE.value

    if source == "wechat":
        if txn_type == "零钱提现":
            return _directional_withdrawal(row)
        if txn_type in {"转账", "群收款", "微信红包", "微信红包（单发）", "微信红包（群红包）", "二维码收款"}:
            return _directional_transfer(row)
        if txn_type in {"购买理财通", "理财通", "零钱通存取"}:
            if any(token in text for token in ("转出", "取出", "赎回", "到账")) or _is_income(row):
                return CashRecordType.INVESTMENT_IN.value
            return CashRecordType.INVESTMENT_OUT.value
        if txn_type in {"商户消费", "扫二维码付款", "二维码付款", "充值", "零钱充值", "缴费", "充值缴费"}:
            return CashRecordType.CONSUMPTION.value
        if not _is_income(row):
            return CashRecordType.CONSUMPTION.value
        return CashRecordType.INCOME.value

    if source == "alipay":
        if txn_type in {"账户存取", "账户提现"} or any(token in text for token in ("提现-", "提现到银行卡", "转出到银行卡")):
            return _directional_withdrawal(row)
        if txn_type == "转账红包":
            return _directional_transfer(row)
        if txn_type == "投资理财":
            if any(token in text for token in ("收益发放", "账户结息", "结息")):
                return CashRecordType.INTEREST.value
            if any(token in text for token in ("卖出", "赎回")) or _is_income(row):
                return CashRecordType.INVESTMENT_IN.value
            return CashRecordType.INVESTMENT_OUT.value
        if txn_type == "充值缴费" or not _is_income(row):
            return CashRecordType.CONSUMPTION.value
        return CashRecordType.INCOME.value

    if source == "icbc_asia_current_account":
        source_text = f"{txn_type} {summary}"
        if any(token in source_text for token in ("轉賬", "轉帳", "转账", "转帐", "匯款", "汇款")):
            return _directional_transfer(row)
        if any(token in source_text for token in ("提現", "提现", "取現", "取现")):
            return _directional_withdrawal(row)
        if any(token in source_text for token in ("消費", "消费")):
            return CashRecordType.CONSUMPTION.value

    bank_summary = summary
    if bank_summary in {"基金购买", "买入基金", "理财"}:
        return CashRecordType.INVESTMENT_OUT.value
    if bank_summary in {"基金赎回", "卖出基金"}:
        return CashRecordType.INVESTMENT_IN.value
    if bank_summary in {"利息", "结息", "利息存入"} or "利息" in bank_summary:
        return CashRecordType.INTEREST.value
    if any(token in bank_summary for token in ("手续费", "管理费")):
        return CashRecordType.FEE.value
    if any(token in bank_summary for token in ("购汇", "个人购汇", "预约购汇", "跨境汇款", "外汇", "汇兑")):
        return CashRecordType.FX_OUT.value if not _is_income(row) else CashRecordType.FX_IN.value
    if bank_summary in {"银转证", "银行转证券", "基金购买"}:
        return CashRecordType.INVESTMENT_OUT.value
    if bank_summary in {"证转银", "证券转银行", "基金赎回"}:
        return CashRecordType.INVESTMENT_IN.value
    if bank_summary in {
        "转账", "转帐", "转账支取", "支付宝转账", "跨行汇款", "转账存入", "网转",
        "他行汇入", "银联入账", "电子汇入", "存款", "ATM存款",
    }:
        return _directional_transfer(row)
    if bank_summary in {"提现", "取现", "预约取现", "支付机构提现", "ATM取款"}:
        return _directional_withdrawal(row)
    if bank_summary in {
        "消费", "银联消费", "有卡自助消费", "无卡自助交易", "无卡支付",
        "跨行其他渠道消费", "充值", "缴费",
    }:
        return CashRecordType.CONSUMPTION.value
    if bank_summary in {"工资", "奖金", "年终奖", "年度奖金", "月日约定提取", "机构"} or "工资" in bank_summary:
        return CashRecordType.INCOME.value
    if _is_income(row):
        return CashRecordType.INCOME.value
    return CashRecordType.OTHER.value
