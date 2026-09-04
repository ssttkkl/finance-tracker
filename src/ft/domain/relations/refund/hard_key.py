"""Phase A: platform hard-key refund proposals (007/008).

Pure matching over cash rows that already carry txn/status fields (from raw_payload
or columns). Persistence stays in Application via RelationProposal → persist.
"""
from __future__ import annotations

from collections import defaultdict
from decimal import Decimal
from typing import Any, Mapping, Sequence

from ft.domain.platform_refund import (
    alipay_is_auth_hold,
    alipay_is_unfreeze,
    alipay_order_match,
    pair_wechat_transfer_returns,
    pair_wechat_refunds,
)
from ft.domain.relations.core.types import (
    CONFIDENCE_STRONG,
    FactView,
    RelationEvidence,
    RelationKind,
    RelationProposal,
    RelationStatus,
    SUBTYPE_P2P_RETURN,
    SUBTYPE_NONE,
)
from ft.domain.relations.core.record_types import (
    is_refund_expense_candidate,
    is_refund_in,
)


def _normalize_scan_rule_id(rule_id: str) -> str:
    rid = rule_id or "scan.platform.refund.v1"
    if rid.startswith("import."):
        rid = "scan." + rid[len("import.") :]
    if not rid.startswith("scan."):
        rid = f"scan.{rid}"
    return rid


def _row_source_blob(row: Mapping[str, Any]) -> str:
    return f"{row.get('bill_source') or ''} {row.get('source') or ''}".lower()


def _is_alipay_row(row: Mapping[str, Any]) -> bool:
    src = _row_source_blob(row)
    return "alipay" in src or "支付宝" in src


def _is_wechat_row(row: Mapping[str, Any]) -> bool:
    src = _row_source_blob(row)
    return "wechat" in src or "微信" in src


def _proposal_pair(
    exp_id: str,
    ref_id: str,
    *,
    exp: FactView | None,
    ref: FactView | None,
    rule_id: str,
    extras: dict | None = None,
    allow_investment_out: bool = False,
    allow_transfer_reversal: bool = False,
    subtype: str = SUBTYPE_NONE,
) -> RelationProposal | None:
    if not exp_id or not ref_id or exp_id == ref_id:
        return None
    if exp is None or ref is None:
        return None
    if str(exp.account_id) != str(ref.account_id):
        return None
    if str(exp.currency).upper() != str(ref.currency).upper():
        return None
    expense_is_valid = is_refund_expense_candidate(exp)
    if (
        not expense_is_valid
        and allow_investment_out
        and exp.signed_amount < 0
        and exp.record_type == "investment_out"
    ):
        expense_is_valid = True
    if (
        not expense_is_valid
        and allow_transfer_reversal
        and exp.record_type == "transfer_reversal"
        and exp.signed_amount < 0
    ):
        expense_is_valid = True
    refund_is_valid = is_refund_in(ref)
    if (
        not refund_is_valid
        and allow_transfer_reversal
        and ref.record_type == "transfer_reversal"
        and ref.signed_amount > 0
    ):
        refund_is_valid = True
    if (
        not expense_is_valid
        or not refund_is_valid
        or (
            not allow_transfer_reversal
            and exp.record_type == "transfer_reversal"
        )
    ):
        return None
    rid = _normalize_scan_rule_id(rule_id)
    refund_amt = abs(ref.signed_amount)
    if exp.signed_amount < 0 or (exp.signed_amount <= 0 and ref.signed_amount >= 0):
        primary, secondary = exp_id, ref_id
    else:
        primary, secondary = ref_id, exp_id
    evidence = RelationEvidence(
        amount_delta="0",
        time_delta_seconds=0,
        same_currency=True,
        rule_id=rid,
        signals=("phase_a", "platform_hard_key"),
        extras={
            "phase": "A",
            "refund_amount": format(refund_amt, "f"),
            **(extras or {}),
        },
    )
    return RelationProposal(
        kind=RelationKind.REFUND_OFFSET.value,
        primary_fact_id=primary,
        secondary_fact_id=secondary,
        primary_fact_type=exp.fact_type if primary == exp_id else ref.fact_type,
        secondary_fact_type=ref.fact_type if secondary == ref_id else exp.fact_type,
        subtype=subtype,
        status=RelationStatus.ACCEPTED.value,
        rule_id=rid,
        confidence=CONFIDENCE_STRONG,
        evidence=evidence,
        created_by="system",
        anchor_fact_id=primary,
        open_leg=False,
    )


def match_phase_a_platform_refunds(
    detailed_rows: Sequence[Mapping[str, Any]],
    *,
    facts_by_id: Mapping[str, FactView],
    linked_pairs: set[tuple[str, str]] | None = None,
    remaining_by_expense: Mapping[str, Decimal] | None = None,
) -> list[RelationProposal]:
    """Return hard-key refund_offset proposals (no DB side effects)."""
    linked = linked_pairs or set()
    linked_fs: set[frozenset[str]] = set()
    for item in linked:
        if isinstance(item, frozenset):
            linked_fs.add(item)
        elif isinstance(item, tuple) and len(item) == 2:
            linked_fs.add(frozenset((item[0], item[1])))

    def already(a: str, b: str) -> bool:
        return frozenset((a, b)) in linked_fs

    proposals: list[RelationProposal] = []
    detailed = [dict(r) for r in detailed_rows if r.get("id")]
    remaining = dict(remaining_by_expense or {})
    if not remaining:
        remaining = {
            str(fact.id): abs(fact.signed_amount)
            for fact in facts_by_id.values()
            if fact.signed_amount < 0
        }

    # --- Alipay order-key ---
    alipay_rows = [r for r in detailed if _is_alipay_row(r)]
    origins: list[dict] = []
    refunds: list[dict] = []
    for r in alipay_rows:
        payload = r.get("raw_payload") if isinstance(r.get("raw_payload"), dict) else {}
        amt = Decimal(str(r.get("amount") or 0))
        status = str(
            r.get("platform_status")
            or r.get("status")
            or payload.get("status")
            or payload.get("platform_status")
            or ""
        )
        fact = facts_by_id.get(str(r.get("id") or ""))
        is_ref = bool(fact and is_refund_in(fact))
        is_exp = bool(
            fact
            and (
                is_refund_expense_candidate(fact)
                or (
                    fact.record_type == "investment_out"
                    and fact.signed_amount < 0
                )
            )
        )
        if alipay_is_auth_hold(status):
            origins.append(r)
            continue
        if alipay_is_unfreeze(status):
            refunds.append(r)
            continue
        if is_ref:
            refunds.append(r)
        elif is_exp:
            origins.append(r)

    origin_txns = [str(o.get("txn_id") or o.get("record_id") or "") for o in origins]

    def candidate_origins(ref: dict) -> list[int]:
        rtxn = str(ref.get("txn_id") or ref.get("record_id") or "")
        hits = [
            i for i, otxn in enumerate(origin_txns)
            if alipay_order_match(rtxn, otxn)
        ]
        merchant_order_id = str(ref.get("merchant_order_id") or "").strip()
        if merchant_order_id:
            exact_order_hits = [
                i for i in hits
                if str(origins[i].get("merchant_order_id") or "").strip()
                == merchant_order_id
            ]
            if exact_order_hits:
                hits = exact_order_hits
        return hits

    for ref in sorted(
        refunds,
        key=lambda row: (
            str(row.get("txn_id") or row.get("record_id") or ""),
            str(row.get("id") or ""),
        ),
    ):
        status = str(
            ref.get("platform_status")
            or ref.get("status")
            or (ref.get("raw_payload") or {}).get("status")
            or ""
        )
        if alipay_is_unfreeze(status):
            continue
        ref_amount = abs(Decimal(str(ref.get("amount") or 0)))
        hits = [
            i for i in candidate_origins(ref)
            if ref_amount > 0
            and ref_amount <= remaining.get(
                str(origins[i].get("id")),
                abs(facts_by_id[str(origins[i]["id"])].signed_amount),
            )
        ]
        if len(hits) != 1:
            continue
        oi = hits[0]
        exp_id = str(origins[oi]["id"])
        ref_id = str(ref["id"])
        if already(exp_id, ref_id):
            continue
        prop = _proposal_pair(
            exp_id,
            ref_id,
            exp=facts_by_id.get(exp_id),
            ref=facts_by_id.get(ref_id),
            rule_id="scan.alipay.order_prefix.v1",
            allow_investment_out=True,
        )
        if prop is not None:
            proposals.append(prop)
            remaining[exp_id] = remaining.get(
                exp_id, abs(facts_by_id[exp_id].signed_amount)
            ) - ref_amount

    # Auth hold → unfreeze same calendar day unique
    holds = [
        r
        for r in alipay_rows
        if alipay_is_auth_hold(
            str(
                r.get("platform_status")
                or r.get("status")
                or (r.get("raw_payload") or {}).get("status")
                or ""
            )
        )
    ]
    unfreezes = [
        r
        for r in alipay_rows
        if alipay_is_unfreeze(
            str(
                r.get("platform_status")
                or r.get("status")
                or (r.get("raw_payload") or {}).get("status")
                or ""
            )
        )
    ]
    holds_by_day: dict[str, list] = defaultdict(list)
    unfreezes_by_day: dict[str, list] = defaultdict(list)
    for r in holds:
        day = str(r.get("date") or r.get("occurred_at") or "")[:10]
        holds_by_day[day].append(r)
    for r in unfreezes:
        day = str(r.get("date") or r.get("occurred_at") or "")[:10]
        unfreezes_by_day[day].append(r)
    for day, hs in holds_by_day.items():
        us = unfreezes_by_day.get(day) or []
        if len(hs) == 1 and len(us) == 1:
            exp_id, ref_id = str(hs[0]["id"]), str(us[0]["id"])
            if already(exp_id, ref_id):
                continue
            prop = _proposal_pair(
                exp_id,
                ref_id,
                exp=facts_by_id.get(exp_id),
                ref=facts_by_id.get(ref_id),
                rule_id="scan.alipay.auth_unfreeze.v1",
            )
            if prop is not None:
                proposals.append(prop)

    # --- WeChat dual-row ---
    wechat_rows: list[dict] = []
    for r in detailed:
        if not _is_wechat_row(r):
            continue
        row = dict(r)
        payload = r.get("raw_payload") if isinstance(r.get("raw_payload"), dict) else {}
        row["status"] = (
            row.get("status") or payload.get("status") or row.get("platform_status") or ""
        )
        row["txn_type"] = (
            row.get("txn_type") or payload.get("txn_type") or payload.get("type") or ""
        )
        row["type"] = row.get("type") or row["txn_type"]
        row["payment_method"] = (
            row.get("payment_method")
            or payload.get("payment_method")
            or payload.get("pay")
            or ""
        )
        row["pay"] = row["payment_method"]
        row["txn_id"] = (
            row.get("txn_id") or payload.get("txn_id") or row.get("record_id") or ""
        )
        row["txn"] = row["txn_id"]
        row["merchant_order_id"] = (
            row.get("merchant_order_id") or payload.get("merchant_order_id") or ""
        )
        row["mer"] = row["merchant_order_id"]
        row["record_type"] = str(row.get("record_type") or "")
        fact = facts_by_id.get(str(row.get("id") or ""))
        if fact is not None:
            if not row.get("account_id"):
                row["account_id"] = fact.account_id
            if not row.get("currency"):
                row["currency"] = fact.currency
            row["record_type"] = row["record_type"] or fact.record_type
            if not row.get("occurred_at") and not row.get("date"):
                row["occurred_at"] = fact.occurred_at
        row["direction"] = row.get("direction") or payload.get("direction") or (
            "支出" if Decimal(str(row.get("amount") or 0)) < 0 else "收入"
        )
        row["date"] = row.get("date") or str(row.get("occurred_at") or "")
        wechat_rows.append(row)

    if wechat_rows:
        pairs = [
            *pair_wechat_transfer_returns(wechat_rows),
            *pair_wechat_refunds(wechat_rows),
        ]
        for ei, ii, rule_id in pairs:
            exp_id = str(wechat_rows[ei]["id"])
            ref_id = str(wechat_rows[ii]["id"])
            if already(exp_id, ref_id):
                continue
            prop = _proposal_pair(
                exp_id,
                ref_id,
                exp=facts_by_id.get(exp_id),
                ref=facts_by_id.get(ref_id),
                rule_id=rule_id,
                allow_transfer_reversal=rule_id.endswith("transfer_return.v1"),
                subtype=(
                    SUBTYPE_P2P_RETURN
                    if rule_id.endswith("transfer_return.v1")
                    else SUBTYPE_NONE
                ),
            )
            if prop is not None:
                proposals.append(prop)

    return proposals
