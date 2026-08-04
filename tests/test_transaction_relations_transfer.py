"""标准化资金移动的关系匹配契约。"""
from __future__ import annotations

from decimal import Decimal

from ft.domain.relations import (
    FactView,
    RelationStatus,
    evaluate_transfer_pair,
    match_personal_fx_exchange,
)


def _fact(**kwargs) -> FactView:
    return FactView(
        id=kwargs.pop("id"),
        amount=Decimal(kwargs.pop("amount")),
        currency=kwargs.pop("currency", "CNY"),
        account_id=kwargs.pop("account_id"),
        occurred_at=kwargs.pop("occurred_at", "2026-01-01 10:00:00"),
        record_type=kwargs.pop("record_type"),
        record_subtype=kwargs.pop("record_subtype"),
        fact_type="cash",
        **kwargs,
    )


def test_ordinary_transfer_auto_accepts_without_source_or_text():
    outgoing = _fact(
        id="out", amount="-1000.00", account_id="source",
        record_type="transfer_out", record_subtype="ordinary_transfer",
        bill_source="arbitrary-source-a", note="任意账单文本",
    )
    incoming = _fact(
        id="in", amount="1000.00", account_id="target",
        occurred_at="2026-01-01 10:00:05", record_type="transfer_in",
        record_subtype="ordinary_transfer", bill_source="arbitrary-source-b",
        note="完全不同的文本",
    )

    proposal = evaluate_transfer_pair(outgoing, [incoming])

    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.secondary_fact_id == "in"
    assert proposal.subtype == "ordinary_transfer"


def test_registered_counterparty_account_selects_only_its_target():
    outgoing = _fact(
        id="out", amount="-1000.00", account_id="source",
        record_type="transfer_out", record_subtype="ordinary_transfer",
        counterparty_account="6222000000001234",
        counterparty_account_attrs=("full",),
    )
    wrong = _fact(
        id="wrong", amount="1000.00", account_id="wrong",
        occurred_at="2026-01-01 10:00:01", record_type="transfer_in",
        record_subtype="ordinary_transfer",
    )
    target = _fact(
        id="target", amount="1000.00", account_id="target",
        occurred_at="2026-01-01 10:00:02", record_type="transfer_in",
        record_subtype="ordinary_transfer",
    )

    proposal = evaluate_transfer_pair(
        outgoing, [wrong, target],
        account_identifiers_by_value={"6222000000001234": ["target"]},
    )

    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.secondary_fact_id == "target"


def test_conflicting_counterparty_alias_never_auto_accepts():
    outgoing = _fact(
        id="out", amount="-1000.00", account_id="source",
        record_type="transfer_out", record_subtype="ordinary_transfer",
        counterparty_account="1234",
        counterparty_account_attrs=("tail",),
    )
    incoming = _fact(
        id="in", amount="1000.00", account_id="target-a",
        occurred_at="2026-01-01 10:00:01", record_type="transfer_in",
        record_subtype="ordinary_transfer",
    )

    proposal = evaluate_transfer_pair(
        outgoing, [incoming], card_tails_by_value={"1234": ["target-a", "target-b"]},
    )

    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
    assert proposal.open_leg is True


def test_standard_transfer_rejects_mismatched_currency_or_amount():
    outgoing = _fact(
        id="out", amount="-1000.00", account_id="source",
        record_type="transfer_out", record_subtype="ordinary_transfer",
    )
    foreign = _fact(
        id="foreign", amount="108.00", currency="HKD", account_id="target",
        record_type="transfer_in", record_subtype="ordinary_transfer",
    )
    uneven = _fact(
        id="uneven", amount="999.99", account_id="target",
        record_type="transfer_in", record_subtype="ordinary_transfer",
    )

    assert evaluate_transfer_pair(outgoing, [foreign]) is None
    assert evaluate_transfer_pair(outgoing, [uneven]) is None


def test_credit_repayment_and_withdrawal_require_their_formal_subtype():
    repayment_out = _fact(
        id="repay-out", amount="-100.00", account_id="cash",
        record_type="repayment", record_subtype="credit_repayment",
    )
    repayment_in = _fact(
        id="repay-in", amount="100.00", account_id="credit",
        occurred_at="2026-01-01 10:00:05", record_type="repayment",
        record_subtype="credit_repayment",
    )
    withdrawal_out = _fact(
        id="withdraw-out", amount="-200.00", account_id="wallet",
        record_type="withdrawal_out", record_subtype="withdraw_to_bank",
    )
    withdrawal_in = _fact(
        id="withdraw-in", amount="200.00", account_id="bank",
        occurred_at="2026-01-01 10:00:05", record_type="withdrawal_in",
        record_subtype="withdraw_to_bank",
    )

    repayment = evaluate_transfer_pair(repayment_out, [repayment_in])
    withdrawal = evaluate_transfer_pair(withdrawal_out, [withdrawal_in])

    assert repayment is not None and repayment.subtype == "credit_repayment"
    assert repayment.status == RelationStatus.ACCEPTED.value
    assert withdrawal is not None and withdrawal.subtype == "ordinary_transfer"
    assert withdrawal.status == RelationStatus.ACCEPTED.value


def test_explicit_currency_exchange_is_source_agnostic_and_ambiguous_is_open_leg():
    outgoing = _fact(
        id="out", amount="-100.00", currency="CNY", account_id="asia",
        record_type="fx_out", record_subtype="currency_exchange",
        bill_source="source-a", note="文本甲",
    )
    incoming = _fact(
        id="in", amount="108.00", currency="HKD", account_id="asia",
        occurred_at="2026-01-01 10:00:05", record_type="fx_in",
        record_subtype="currency_exchange", bill_source="source-b", note="文本乙",
    )
    second = _fact(
        id="second", amount="109.00", currency="HKD", account_id="asia",
        occurred_at="2026-01-01 10:00:06", record_type="fx_in",
        record_subtype="currency_exchange",
    )

    accepted = match_personal_fx_exchange(outgoing, [incoming])
    pending = match_personal_fx_exchange(outgoing, [incoming, second])

    assert accepted is not None and accepted.status == RelationStatus.ACCEPTED.value
    assert pending is not None and pending.open_leg is True
    assert set(pending.evidence.candidate_fact_ids) == {"in", "second"}
