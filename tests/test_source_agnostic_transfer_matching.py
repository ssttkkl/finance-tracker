"""转账关系只依赖规范字段的领域契约。"""
from __future__ import annotations

from decimal import Decimal

from ft.domain.relations import FactCandidateIndex, FactView, RelationStatus, match_transfer_pairs_phase_c


def _fact(**kwargs) -> FactView:
    return FactView(
        id=kwargs.pop("id"),
        amount=Decimal(kwargs.pop("amount")),
        currency=kwargs.pop("currency", "CNY"),
        account_id=kwargs.pop("account_id"),
        occurred_at=kwargs.pop("occurred_at"),
        record_type=kwargs.pop("record_type"),
        record_subtype=kwargs.pop("record_subtype"),
        fact_type="cash",
        **kwargs,
    )


def test_cross_currency_remittance_matches_unique_tail_target_after_days_without_source_or_text_rules():
    outgoing = _fact(
        id="out", amount="-100.00", currency="CNY", account_id="icbc",
        occurred_at="2026-05-02 13:47:00", record_type="transfer_out",
        record_subtype="cross_border_remittance", counterparty_account="123456784245",
        counterparty_account_attrs=("full",),
        bill_source="unrelated_a", source="unrelated_a", note="任意文本",
        account_type="cash",
    )
    incoming = _fact(
        id="in", amount="108.00", currency="HKD", account_id="asia",
        occurred_at="2026-05-06 13:47:07", record_type="transfer_in",
        record_subtype="ordinary_transfer", bill_source="unrelated_b",
        source="unrelated_b", note="另一段任意文本", account_type="cash",
    )

    proposals = match_transfer_pairs_phase_c(
        [outgoing, incoming],
        card_tails_by_value={"4245": ["asia"]},
    )

    assert len(proposals) == 1
    assert proposals[0].status == RelationStatus.ACCEPTED.value
    assert proposals[0].secondary_fact_id == "in"
    assert proposals[0].subtype == "cross_currency_remittance"


def test_indexed_cross_border_remittance_matches_across_three_calendar_days():
    outgoing = _fact(
        id="out", amount="-4000.00", currency="USD", account_id="icbc",
        occurred_at="2026-01-23 11:38:22", record_type="transfer_out",
        record_subtype="cross_border_remittance", counterparty_account="123456784245",
        counterparty_account_attrs=("full",),
    )
    incoming = _fact(
        id="in", amount="4000.00", currency="USD", account_id="asia",
        occurred_at="2026-01-26 02:45:44", record_type="transfer_in",
        record_subtype="ordinary_transfer",
    )
    facts = [outgoing, incoming]

    proposals = match_transfer_pairs_phase_c(
        facts,
        index=FactCandidateIndex(facts),
        card_tails_by_value={"4245": ["asia"]},
    )

    assert len(proposals) == 1
    assert proposals[0].status == RelationStatus.ACCEPTED.value
    assert proposals[0].secondary_fact_id == "in"
    assert proposals[0].subtype == "ordinary_transfer"


def test_internal_cross_currency_transfer_matches_same_account_by_unique_alias_without_source_rule():
    outgoing = _fact(
        id="out", amount="-100.00", currency="CNY", account_id="asia",
        occurred_at="2026-05-24 13:47:00", record_type="transfer_out",
        record_subtype="ordinary_transfer", counterparty_account="123456784242",
        counterparty_account_attrs=("full",),
        bill_source="source_a", source="source_a", note="文本甲", account_type="cash",
    )
    incoming = _fact(
        id="in", amount="108.00", currency="HKD", account_id="asia",
        occurred_at="2026-05-24 13:47:07", record_type="transfer_in",
        record_subtype="ordinary_transfer", bill_source="source_b",
        source="source_b", note="文本乙", account_type="loan",
    )

    proposals = match_transfer_pairs_phase_c(
        [outgoing, incoming],
        card_tails_by_value={"4242": ["asia"]},
    )

    assert len(proposals) == 1
    assert proposals[0].status == RelationStatus.ACCEPTED.value
    assert proposals[0].subtype == "currency_exchange"


def test_unique_alias_target_uses_global_nearest_one_to_one_assignment():
    first_out = _fact(
        id="out-1", amount="-87.19", currency="CNY", account_id="asia",
        occurred_at="2026-05-24 13:47:09", record_type="transfer_out",
        record_subtype="ordinary_transfer", counterparty_account="123456784242",
        counterparty_account_attrs=("full",),
    )
    second_out = _fact(
        id="out-2", amount="-87.19", currency="CNY", account_id="asia",
        occurred_at="2026-05-24 14:12:30", record_type="transfer_out",
        record_subtype="ordinary_transfer", counterparty_account="123456784247",
        counterparty_account_attrs=("full",),
    )
    first_in = _fact(
        id="in-1", amount="100.00", currency="HKD", account_id="asia",
        occurred_at="2026-05-24 13:47:12", record_type="transfer_in",
        record_subtype="ordinary_transfer",
    )
    second_in = _fact(
        id="in-2", amount="100.00", currency="HKD", account_id="asia",
        occurred_at="2026-05-24 14:12:24", record_type="transfer_in",
        record_subtype="ordinary_transfer",
    )

    proposals = match_transfer_pairs_phase_c(
        [first_out, second_out, first_in, second_in],
        card_tails_by_value={"4242": ["asia"], "4247": ["asia"]},
    )

    assert [(proposal.primary_fact_id, proposal.secondary_fact_id) for proposal in proposals] == [
        ("out-1", "in-1"), ("out-2", "in-2"),
    ]
    assert all(proposal.status == RelationStatus.ACCEPTED.value for proposal in proposals)
    assert all(proposal.subtype == "currency_exchange" for proposal in proposals)


def test_conflicting_tail_does_not_expand_ordinary_transfer_window():
    outgoing = _fact(
        id="out", amount="-100.00", currency="USD", account_id="icbc",
        occurred_at="2026-05-02 13:47:00", record_type="transfer_out",
        record_subtype="cross_border_remittance", counterparty_account="123456784245",
        counterparty_account_attrs=("full",),
    )
    incoming = _fact(
        id="in", amount="100.00", currency="USD", account_id="asia",
        occurred_at="2026-05-06 13:47:00", record_type="transfer_in",
        record_subtype="ordinary_transfer",
    )

    assert match_transfer_pairs_phase_c(
        [outgoing, incoming], card_tails_by_value={"4245": ["asia", "other"]},
    ) == []
