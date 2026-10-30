"""Export standardized transfer/FX pairing results from Python."""
from __future__ import annotations
import argparse
from dataclasses import asdict, replace
from datetime import datetime, timezone, timedelta
import json
from pathlib import Path

from ft.application.cash_import_operations import _wire
from ft.domain.relations.core.types import FactCandidateIndex, FactView
from ft.domain.relations.transfer.match import (
    evaluate_transfer_pair, match_normalized_subtype_transfers, match_personal_fx_exchange,
    match_transfer_pairs_phase_c,
)


def fact(**changes):
    base = FactView(id="1", amount="-10.00", currency="CNY", account_id="a", account_name="现金",
        occurred_at=datetime(2026, 10, 1, 10, 0, tzinfo=timezone.utc), counterparty="", note="",
        record_type="transfer_out", record_subtype="ordinary_transfer", bill_source="ccb_debit", source="",
        record_id="T-1")
    return replace(base, **changes)


def proposal(value):
    return None if value is None else _wire(asdict(value))


def export():
    out = fact(id="out", amount="-100.00", record_id="O1")
    inc = fact(id="inc", amount="100.00", record_type="transfer_in", account_id="b", record_id="I1",
        occurred_at=out.occurred_at + timedelta(seconds=5))
    late = fact(id="late", amount="100.00", record_type="transfer_in", account_id="b", record_id="I2",
        occurred_at=out.occurred_at + timedelta(seconds=400))
    same = fact(id="same", amount="100.00", record_type="transfer_in", account_id="a", record_id="I3",
        occurred_at=out.occurred_at + timedelta(seconds=5))
    fx_out = fact(id="fx_out", amount="-100.00", currency="CNY", record_type="fx_out",
        record_subtype="currency_exchange", account_id="a", record_id="F1")
    fx_in = fact(id="fx_in", amount="100.00", currency="USD", record_type="fx_in",
        record_subtype="currency_exchange", account_id="b", record_id="F2",
        occurred_at=fx_out.occurred_at + timedelta(seconds=30))
    aliases = {"622212345678": ["b"]}
    with_alias = replace(out, counterparty_account="622212345678", counterparty_account_attrs=("full",))
    cases = {
        "accepted": proposal(evaluate_transfer_pair(out, [inc], account_identifiers_by_value=aliases)),
        "ambiguous": proposal(evaluate_transfer_pair(out, [inc, replace(inc, id="inc2", record_id="I3",
            occurred_at=out.occurred_at + timedelta(seconds=5))])),
        "same_account": proposal(evaluate_transfer_pair(out, [same])),
        "late": proposal(evaluate_transfer_pair(out, [late])),
        "credit": proposal(evaluate_transfer_pair(
            replace(out, record_subtype="credit_repayment"), [replace(inc, record_subtype="credit_repayment")])),
        "fx": proposal(match_personal_fx_exchange(fx_out, [fx_in])),
        "fx_same_currency": proposal(match_personal_fx_exchange(fx_out, [replace(fx_in, currency="CNY")])),
        "targeted": _wire([asdict(item) for item in match_normalized_subtype_transfers([with_alias, inc],
            account_identifiers_by_value=aliases)]),
        "targeted_no_alias": _wire([asdict(item) for item in match_normalized_subtype_transfers([out, inc])]),
        "phase_c": _wire([asdict(item) for item in match_transfer_pairs_phase_c([out, inc, fx_out, fx_in],
            account_identifiers_by_value=aliases)]),
        "phase_c_seed": _wire([asdict(item) for item in match_transfer_pairs_phase_c([out, inc, fx_out, fx_in],
            seed_ids=["inc"], account_identifiers_by_value=aliases)]),
        "counterparty_invalid": proposal(evaluate_transfer_pair(
            replace(out, counterparty_account="1234", counterparty_account_attrs=("full",)), [inc], account_identifiers_by_value=aliases)),
    }
    return {"cases": cases}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = json.dumps(export(), ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if args.output.read_text() != content:
            raise SystemExit("transfer match golden mismatch")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content)
