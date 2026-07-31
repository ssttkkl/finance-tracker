from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone, timedelta, date
from zoneinfo import ZoneInfo
from decimal import Decimal
from enum import Enum
from typing import Any, Callable, Iterable, Mapping, Sequence
import bisect
import re

from collections import defaultdict
from ft.domain.relations.core.geometry import _as_decimal, _text_blob, _time_delta_seconds
from ft.domain.relations.core.routing import source_group
from ft.domain.relations.core.types import (
    CONFIDENCE_STRONG, CONFIDENCE_WEAK, FactView, RelationEvidence, RelationKind,
    RelationProposal, RelationStatus, RULE_REFUND_DIAMOND_V1, SUBTYPE_NONE,
)
from ft.domain.relations.refund.signals import has_refund_signal_for_fact
def match_diamond_bank_refunds(
    facts: Sequence[FactView],
    *,
    accepted_mirrors: Sequence[tuple[str, str]] | None = None,
    accepted_platform_refunds: Sequence[tuple[str, str]] | None = None,
    open_or_pending_bank_refund_ids: Sequence[str] | None = None,
) -> list[RelationProposal]:
    """Phase D FR-055: bank_ref via platform refund chain → bank_pay.

    bank_ref --mirror-- plat_ref --refund-- plat_pay --mirror-- bank_pay
    """
    by_id = {f.id: f for f in facts if not f.deleted}
    mirror_adj: dict[str, set[str]] = defaultdict(set)
    for a, b in (accepted_mirrors or ()):
        if a and b:
            mirror_adj[a].add(b)
            mirror_adj[b].add(a)
    # expense -> refund list; refund -> expense
    exp_to_ref: dict[str, list[str]] = defaultdict(list)
    ref_to_exp: dict[str, str] = {}
    for a, b in (accepted_platform_refunds or ()):
        fa, fb = by_id.get(a), by_id.get(b)
        if not fa or not fb:
            continue
        if fa.signed_amount < 0 and fb.signed_amount > 0:
            exp_to_ref[a].append(b)
            ref_to_exp[b] = a
        elif fb.signed_amount < 0 and fa.signed_amount > 0:
            exp_to_ref[b].append(a)
            ref_to_exp[a] = b
        else:
            # zero-amount auth etc: keep a as primary
            exp_to_ref[a].append(b)
            ref_to_exp[b] = a

    seeds: list[FactView] = []
    if open_or_pending_bank_refund_ids:
        for fid in open_or_pending_bank_refund_ids:
            f = by_id.get(fid)
            if f is not None:
                seeds.append(f)
    else:
        for f in by_id.values():
            if source_group(f) != "bank":
                continue
            if f.signed_amount <= 0:
                continue
            if not has_refund_signal_for_fact(f):
                continue
            seeds.append(f)

    used: set[str] = set()
    out: list[RelationProposal] = []
    for bank_ref in seeds:
        if bank_ref.id in used:
            continue
        plat_refs = [
            pid for pid in mirror_adj.get(bank_ref.id, ())
            if pid in by_id and source_group(by_id[pid]) == "platform"
        ]
        bank_pays: list[str] = []
        for pref in plat_refs:
            # pref should be refund credit
            exp_id = ref_to_exp.get(pref)
            if not exp_id:
                continue
            for bpay in mirror_adj.get(exp_id, ()):
                bf = by_id.get(bpay)
                if not bf or source_group(bf) != "bank":
                    continue
                if bf.signed_amount >= 0:
                    continue
                # same account preferred but not required if amount residual-compatible
                if abs(bf.signed_amount) + Decimal("0.0001") < abs(bank_ref.signed_amount):
                    continue
                bank_pays.append(bpay)
        bank_pays = list(dict.fromkeys(bank_pays))
        if len(bank_pays) != 1:
            continue
        bank_pay_id = bank_pays[0]
        if bank_pay_id in used or bank_ref.id in used:
            continue
        evidence = RelationEvidence(
            amount_delta="0",
            time_delta_seconds=_time_delta_seconds(
                by_id[bank_pay_id].occurred_at, bank_ref.occurred_at
            ),
            same_currency=True,
            source_pair=(
                by_id[bank_pay_id].bill_source or by_id[bank_pay_id].source,
                bank_ref.bill_source or bank_ref.source,
            ),
            rule_id=RULE_REFUND_DIAMOND_V1,
            candidate_count=1,
            signals=("diamond", "platform_chain", "exact_or_residual"),
            extras={"via": "platform_refund_mirror"},
        )
        out.append(RelationProposal(
            kind=RelationKind.REFUND_OFFSET.value,
            primary_fact_id=bank_pay_id,
            secondary_fact_id=bank_ref.id,
            status=RelationStatus.ACCEPTED.value,
            rule_id=RULE_REFUND_DIAMOND_V1,
            confidence=CONFIDENCE_STRONG,
            evidence=evidence,
            anchor_fact_id=bank_pay_id,
            open_leg=False,
        ))
        used.add(bank_pay_id)
        used.add(bank_ref.id)
    return out

