"""Formal record_type gates for relation matching."""
from __future__ import annotations

from decimal import Decimal

from ft.domain.relations import (
    FactCandidateIndex,
    FactView,
    RelationStatus,
    evaluate_payment_mirror,
    evaluate_refund_offset,
    evaluate_transfer_pair,
)
from ft.application.relations import _fact_view_from_row
from ft.domain.relations.refund.signals import DefaultRefundTextGates
from ft.domain.relations.core.routing import source_group


def _fv(**kwargs) -> FactView:
    base = dict(
        currency="CNY",
        account_type="cash",
        fact_type="cash",
        deleted=False,
        bill_source="alipay",
        source="alipay",
    )
    base.update(kwargs)
    return FactView(**base)


def test_row_to_fact_view_carries_persisted_record_type():
    fact = _fact_view_from_row({
        "id": "1",
        "amount": "-100",
        "currency": "CNY",
        "account_id": "cash",
        "account_type": "cash",
        "record_type": "repayment",
        "source_type": "icbc_debit",
        "occurred_at": "2026-01-01 10:00:00",
    })
    assert fact.record_type == "repayment"


def test_refund_record_type_is_authoritative_over_refund_text():
    expense = _fv(
        id="expense", amount=Decimal("-100"), account_id="a",
        record_type="consumption", occurred_at="2026-01-01 10:00:00",
        counterparty="商家",
    )
    false_refund = _fv(
        id="false", amount=Decimal("100"), account_id="a",
        record_type="income", occurred_at="2026-01-02 10:00:00",
        counterparty="商家", note="退款",
    )
    assert evaluate_refund_offset(false_refund, [expense]) is None

    formal_refund = _fv(
        id="refund", amount=Decimal("100"), account_id="a",
        record_type="refund", occurred_at="2026-01-02 10:00:00",
        counterparty="商家",
    )
    proposal = evaluate_refund_offset(formal_refund, [expense])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_consumption_with_transfer_text_cannot_be_transfer_seed():
    expense = _fv(
        id="expense", amount=Decimal("-100"), account_id="a",
        record_type="consumption", occurred_at="2026-01-01 10:00:00",
        note="转账支取",
    )
    incoming = _fv(
        id="incoming", amount=Decimal("100"), account_id="b",
        record_type="transfer_in", occurred_at="2026-01-01 10:00:05",
        note="转账存入",
    )
    assert evaluate_transfer_pair(expense, [incoming]) is None


def test_transfer_type_can_pair_without_transfer_text():
    outgoing = _fv(
        id="out", amount=Decimal("-100"), account_id="a",
        record_type="transfer_out", occurred_at="2026-01-01 10:00:00",
        note="",
    )
    incoming = _fv(
        id="in", amount=Decimal("100"), account_id="b",
        record_type="transfer_in", occurred_at="2026-01-01 10:00:05",
        note="",
    )
    proposal = evaluate_transfer_pair(outgoing, [incoming])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_repayment_type_can_pair_with_loan_income_without_repayment_text():
    outgoing = _fv(
        id="out", amount=Decimal("-500"), account_id="cash",
        record_type="repayment", occurred_at="2026-01-01 10:00:00",
    )
    incoming = _fv(
        id="in", amount=Decimal("500"), account_id="loan",
        account_type="loan", record_type="income",
        occurred_at="2026-01-01 10:00:05",
    )
    proposal = evaluate_transfer_pair(outgoing, [incoming])
    assert proposal is not None
    assert proposal.subtype == "credit_repayment"
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_income_rows_with_refund_text_cannot_form_refund_mirror():
    platform = _fv(
        id="platform", amount=Decimal("100"), account_id="a",
        record_type="income", bill_source="alipay", source="alipay",
        occurred_at="2026-01-01 10:00:00", counterparty="商家", note="退款",
    )
    bank = _fv(
        id="bank", amount=Decimal("100"), account_id="a",
        record_type="income", bill_source="icbc_credit", source="icbc_credit",
        occurred_at="2026-01-01 10:00:05", counterparty="商家", note="退货",
    )
    assert evaluate_payment_mirror(platform, [bank]) is None


def test_refund_index_excludes_other_accounts_at_candidate_generation():
    refund = _fv(
        id="refund", amount=Decimal("100"), account_id="a",
        record_type="refund", occurred_at="2026-01-02 10:00:00",
    )
    same_account = _fv(
        id="same", amount=Decimal("-100"), account_id="a",
        record_type="consumption", occurred_at="2026-01-01 10:00:00",
    )
    other_account = _fv(
        id="other", amount=Decimal("-100"), account_id="b",
        record_type="consumption", occurred_at="2026-01-01 10:00:00",
    )
    index = FactCandidateIndex(
        [refund, same_account, other_account],
        source_group=source_group,
        refund_gates=DefaultRefundTextGates(),
    )
    assert [fact.id for fact in index.refund_candidates(refund)] == ["same"]


def test_reversal_cannot_enter_consumption_refund_relation():
    expense = _fv(
        id="expense", amount=Decimal("-100"), account_id="a",
        record_type="consumption", occurred_at="2026-01-01 10:00:00",
    )
    reversal = _fv(
        id="reversal", amount=Decimal("100"), account_id="a",
        record_type="reversal", occurred_at="2026-01-02 10:00:00",
        note="退款",
    )
    assert evaluate_refund_offset(reversal, [expense]) is None


def test_withdrawal_is_not_ordinary_transfer_out_but_keeps_withdrawal_pair_path():
    withdrawal = _fv(
        id="withdrawal", amount=Decimal("-100"), account_id="a",
        record_type="withdrawal_out", bill_source="alipay", source="alipay",
        occurred_at="2026-01-01 10:00:00", note="提现-实时提现",
    )
    bank = _fv(
        id="bank", amount=Decimal("100"), account_id="b",
        record_type="withdrawal_in", bill_source="ccb_debit", source="ccb_debit",
        occurred_at="2026-01-01 10:00:05", note="支付机构提现",
    )
    from ft.domain.relations.core.record_types import is_transfer_out_record
    from ft.domain.relations.core.record_types import is_transfer_in_record

    assert not is_transfer_out_record(withdrawal)
    assert not is_transfer_in_record(bank)
    proposal = evaluate_transfer_pair(withdrawal, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_withdrawal_in_cannot_be_consumed_by_ordinary_transfer_seed():
    transfer = _fv(
        id="transfer", amount=Decimal("-100"), account_id="a",
        record_type="transfer_out", bill_source="wechat", source="wechat",
        occurred_at="2026-01-01 10:00:00", note="转账",
    )
    withdrawal_in = _fv(
        id="withdrawal-in", amount=Decimal("100"), account_id="b",
        record_type="withdrawal_in", bill_source="ccb_debit", source="ccb_debit",
        occurred_at="2026-01-01 10:00:05", note="支付机构提现",
    )
    assert evaluate_transfer_pair(transfer, [withdrawal_in]) is None


def test_ordinary_transfer_index_excludes_credit_account_income():
    outgoing = _fv(
        id="out", amount=Decimal("-20000"), account_id="ccb-debit",
        record_type="transfer_out", bill_source="ccb_debit", source="ccb_debit",
        occurred_at="2026-05-29 23:52:00",
    )
    credit_income = _fv(
        id="credit", amount=Decimal("20000"), account_id="icbc-credit",
        account_type="loan", record_type="income",
        bill_source="icbc_credit", source="icbc_credit",
        occurred_at="2026-05-29 23:52:10",
    )
    index = FactCandidateIndex(
        [outgoing, credit_income],
        source_group=source_group,
        refund_gates=DefaultRefundTextGates(),
    )

    assert index.transfer_candidates(outgoing) == []


def test_withdrawal_out_cannot_pair_with_platform_balance_income():
    withdrawal = _fv(
        id="withdrawal", amount=Decimal("-11250"), account_id="yulibao",
        record_type="withdrawal_out", bill_source="alipay", source="alipay",
        occurred_at="2026-05-01 12:00:00", note="余利宝-转出到银行卡",
    )
    platform_income = _fv(
        id="platform-income", amount=Decimal("11250"), account_id="alipay-balance",
        record_type="transfer_in", bill_source="alipay", source="alipay",
        occurred_at="2026-05-01 12:47:22", note="房租",
    )

    assert evaluate_transfer_pair(withdrawal, [platform_income]) is None
