"""对方账号规范表示、属性与匹配消费契约。"""
from __future__ import annotations

from decimal import Decimal
import csv

import pytest


@pytest.mark.parametrize(
    ("raw", "source", "source_account", "value", "attrs"),
    [
        ("", "alipay", "", "", ()),
        ("/", "alipay", "", "", ()),
        ("6222-0000-0000-1234", "ccb_debit", "", "6222000000001234", ("full",)),
        ("示例银行储蓄卡(4321)", "wechat", "", "4321", ("tail",)),
        ("6222****4321", "icbc_debit", "", "6222****4321", ("masked",)),
        ("demo***@example.com", "alipay", "", "demo***@example.com", ("masked",)),
        ("counterparty@example.com", "icbc_asia", "", "counterparty@example.com", ("full",)),
        (
            "879825****47", "icbc_asia", "879825074240", "879825074247",
            ("masked", "reconstructed"),
        ),
        ("879825****47", "icbc_asia", "8798250742400", "879825****47", ("masked",)),
    ],
)
def test_normalize_counterparty_account_returns_value_and_explicit_attrs(
    raw, source, source_account, value, attrs,
):
    from ft.domain.record_type import normalize_counterparty_account

    normalized = normalize_counterparty_account(
        raw, source=source, source_account_identifier=source_account,
    )

    assert normalized.value == value
    assert normalized.attrs == attrs


@pytest.mark.parametrize(
    ("value", "attrs"),
    [
        ("6222000000001234", ()),
        ("", ("full",)),
        ("12345", ("tail",)),
        ("6222000000001234", ("masked",)),
        ("6222****1234", ("reconstructed", "masked")),
        ("6222****1234", ("masked", "masked")),
        ("6222****1234", ("unknown",)),
    ],
)
def test_new_writes_reject_missing_unknown_or_inconsistent_attrs(value, attrs):
    from ft.domain.record_type import validate_counterparty_account

    with pytest.raises(ValueError, match="counterparty_account_attrs"):
        validate_counterparty_account(value, attrs)


def test_counterparty_account_attrs_rejects_mapping_instead_of_json_array():
    from ft.domain.record_type import validate_counterparty_account

    with pytest.raises(ValueError, match="JSON array"):
        validate_counterparty_account("6222000000001234", {"full": True})


def _fact(**kwargs):
    from ft.domain.relations import FactView

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


def test_masked_counterparty_account_matches_one_explicit_full_identifier():
    from ft.domain.relations import RelationStatus, match_transfer_pairs_phase_c

    outgoing = _fact(
        id="out", amount="-100", account_id="source",
        occurred_at="2026-01-01 10:00:00", record_type="transfer_out",
        record_subtype="ordinary_transfer", counterparty_account="6222****4321",
        counterparty_account_attrs=("masked",),
    )
    incoming = _fact(
        id="in", amount="100", account_id="target",
        occurred_at="2026-01-04 10:00:00", record_type="transfer_in",
        record_subtype="ordinary_transfer",
    )

    proposals = match_transfer_pairs_phase_c(
        [outgoing, incoming],
        account_identifiers_by_value={"6222000000004321": ["target"]},
    )

    assert len(proposals) == 1
    assert proposals[0].status == RelationStatus.ACCEPTED.value
    assert proposals[0].secondary_fact_id == "in"


def test_broad_mask_without_four_visible_tail_does_not_enable_seven_day_window():
    from ft.domain.relations import match_transfer_pairs_phase_c

    outgoing = _fact(
        id="out", amount="-100", account_id="source",
        occurred_at="2026-01-01 10:00:00", record_type="transfer_out",
        record_subtype="ordinary_transfer", counterparty_account="1*2",
        counterparty_account_attrs=("masked",),
    )
    incoming = _fact(
        id="in", amount="100", account_id="target",
        occurred_at="2026-01-04 10:00:00", record_type="transfer_in",
        record_subtype="ordinary_transfer",
    )

    assert match_transfer_pairs_phase_c(
        [outgoing, incoming],
        account_identifiers_by_value={"123456789012": ["target"]},
    ) == []


def test_reconstruction_proof_does_not_change_generated_record_identity():
    from ft.application.statement_import import _row_record_id
    from ft.convert import _build_output_row

    row = _build_output_row({
        "date": "2026-01-01 10:00:00",
        "amount": Decimal("-100.00"),
        "currency": "HKD",
        "counterparty": "示例对手方",
        "counterparty_account": "879825****47",
        "_source_account_identifier": "879825074240",
        "note": "转账",
        "category": "transfer",
        "txn_type": "转账",
    }, bill_type="icbc_asia", account="来源账户")
    without_proof = dict(row)
    without_proof.pop("_counterparty_account_reconstruction_proof")

    assert _row_record_id(row, {}) == _row_record_id(without_proof, {})


@pytest.mark.parametrize(
    "attrs",
    [(), ("unknown",), ("tail",), ("masked", "masked")],
)
def test_missing_or_invalid_attrs_do_not_enable_the_seven_day_window(attrs):
    from ft.domain.relations import match_transfer_pairs_phase_c

    outgoing = _fact(
        id="out", amount="-100", account_id="source",
        occurred_at="2026-01-01 10:00:00", record_type="transfer_out",
        record_subtype="ordinary_transfer", counterparty_account="6222000000004321",
        counterparty_account_attrs=attrs,
    )
    incoming = _fact(
        id="in", amount="100", account_id="target",
        occurred_at="2026-01-04 10:00:00", record_type="transfer_in",
        record_subtype="ordinary_transfer",
    )

    assert match_transfer_pairs_phase_c(
        [outgoing, incoming],
        account_identifiers_by_value={"6222000000004321": ["target"]},
    ) == []


def test_missing_attrs_preserve_existing_short_window_candidate_behavior():
    from ft.domain.relations import RelationStatus, match_transfer_pairs_phase_c

    outgoing = _fact(
        id="out", amount="-100", account_id="source",
        occurred_at="2026-01-01 10:00:00", record_type="transfer_out",
        record_subtype="ordinary_transfer", counterparty_account="6222000000004321",
        counterparty_account_attrs=(),
    )
    incoming = _fact(
        id="in", amount="100", account_id="target",
        occurred_at="2026-01-01 10:00:05", record_type="transfer_in",
        record_subtype="ordinary_transfer",
    )

    proposals = match_transfer_pairs_phase_c(
        [outgoing, incoming],
        account_identifiers_by_value={"6222000000004321": ["target"]},
    )

    assert len(proposals) == 1
    assert proposals[0].status == RelationStatus.ACCEPTED.value


def test_csv_export_serializes_counterparty_account_attrs_as_json(tmp_path):
    from ft.adapters.export_csv import write_csv_export
    from ft.domain.application import ExportPayload

    output = tmp_path / "cash.csv"
    write_csv_export(ExportPayload(
        ({
            "counterparty_account": "6222****4321",
            "counterparty_account_attrs": ["masked"],
        },),
        fieldnames=("counterparty_account", "counterparty_account_attrs"),
    ), output)

    with output.open(encoding="utf-8", newline="") as stream:
        row = next(csv.DictReader(stream))
    assert row["counterparty_account_attrs"] == '["masked"]'
