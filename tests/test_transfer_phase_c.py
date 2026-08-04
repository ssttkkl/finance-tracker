"""转账扫描阶段的标准化字段契约。"""
from __future__ import annotations

from decimal import Decimal

from ft.domain.relations import FactView, RelationStatus, match_transfer_pairs_phase_c


def _fact(**kwargs) -> FactView:
    return FactView(
        id=kwargs.pop("id"), amount=Decimal(kwargs.pop("amount")),
        currency=kwargs.pop("currency", "CNY"), account_id=kwargs.pop("account_id"),
        occurred_at=kwargs.pop("occurred_at"), record_type=kwargs.pop("record_type"),
        record_subtype=kwargs.pop("record_subtype"), fact_type="cash", **kwargs,
    )


def test_cross_border_same_currency_is_ordinary_transfer():
    outgoing = _fact(
        id="out", amount="-500.00", account_id="icbc", occurred_at="2026-05-24 13:47:00",
        record_type="transfer_out", record_subtype="cross_border_remittance",
        counterparty_account="123456780", counterparty_account_attrs=("full",),
    )
    incoming = _fact(
        id="in", amount="500.00", account_id="asia", occurred_at="2026-05-24 13:47:07",
        record_type="transfer_in", record_subtype="ordinary_transfer",
    )

    proposals = match_transfer_pairs_phase_c(
        [outgoing, incoming], account_identifiers_by_value={"123456780": ["asia"]},
    )

    assert len(proposals) == 1
    assert proposals[0].status == RelationStatus.ACCEPTED.value
    assert proposals[0].subtype == "ordinary_transfer"


def test_cross_border_unique_target_chooses_nearest_arrival_once():
    outgoing = _fact(
        id="out", amount="-500.00", account_id="icbc", occurred_at="2026-05-24 13:47:00",
        record_type="transfer_out", record_subtype="cross_border_remittance",
        counterparty_account="123456780", counterparty_account_attrs=("full",),
    )
    first = _fact(
        id="first", amount="54.00", currency="HKD", account_id="asia", occurred_at="2026-05-24 13:47:20",
        record_type="transfer_in", record_subtype="ordinary_transfer",
    )
    second = _fact(
        id="second", amount="54.10", currency="HKD", account_id="asia", occurred_at="2026-05-24 13:47:21",
        record_type="transfer_in", record_subtype="ordinary_transfer",
    )

    proposals = match_transfer_pairs_phase_c(
        [outgoing, first, second], account_identifiers_by_value={"123456780": ["asia"]},
    )

    assert len(proposals) == 1
    assert proposals[0].status == RelationStatus.ACCEPTED.value
    assert proposals[0].secondary_fact_id == "first"
    assert proposals[0].subtype == "cross_currency_remittance"


def test_internal_same_account_cross_currency_is_currency_exchange():
    outgoing = _fact(
        id="out", amount="-100.00", currency="CNY", account_id="asia", occurred_at="2026-05-24 13:47:00",
        record_type="transfer_out", record_subtype="internal_account_transfer",
        counterparty_account="123456780", counterparty_account_attrs=("full",),
    )
    incoming = _fact(
        id="in", amount="108.00", currency="HKD", account_id="asia", occurred_at="2026-05-24 13:47:07",
        record_type="transfer_in", record_subtype="internal_account_transfer",
    )

    proposals = match_transfer_pairs_phase_c(
        [outgoing, incoming], account_identifiers_by_value={"123456780": ["asia"]},
    )

    assert len(proposals) == 1
    assert proposals[0].status == RelationStatus.ACCEPTED.value
    assert proposals[0].subtype == "currency_exchange"


def test_phase_c_only_scans_selected_seed_but_allows_its_counterpart():
    outgoing = _fact(
        id="out", amount="-100.00", account_id="source", occurred_at="2026-01-01 10:00:00",
        record_type="transfer_out", record_subtype="ordinary_transfer",
    )
    incoming = _fact(
        id="in", amount="100.00", account_id="target", occurred_at="2026-01-01 10:00:05",
        record_type="transfer_in", record_subtype="ordinary_transfer",
    )

    proposals = match_transfer_pairs_phase_c([outgoing, incoming], seed_ids=["out"])

    assert len(proposals) == 1
    assert proposals[0].secondary_fact_id == "in"
