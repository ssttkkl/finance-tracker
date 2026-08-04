"""US2/US4 transfer_pair and credit_repayment tests."""
from __future__ import annotations

from decimal import Decimal

import pytest

from ft.domain.relations import (
    FactView,
    RelationEvidence,
    RelationStatus,
    SUBTYPE_CREDIT_REPAYMENT,
    evaluate_transfer_pair,
    match_transfer_pairs_phase_c,
)


def _fv(**kwargs):
    base = dict(currency="CNY", account_type="cash", fact_type="cash", deleted=False)
    base.update(kwargs)
    if "record_type" not in base:
        amount = Decimal(str(base.get("amount") or 0))
        note = str(base.get("note") or "")
        base["record_type"] = (
            "repayment" if amount < 0 and any(token in note for token in ("还款", "购汇")) else
            "transfer_out" if amount < 0 else
            "income" if base.get("account_type") == "loan" and amount > 0 else
            "transfer_in" if amount > 0 else "other"
        )
    return FactView(**base)


def test_relation_evidence_is_not_a_persisted_schema_contract():
    evidence = RelationEvidence()
    assert not hasattr(evidence, "to_json")
    assert not hasattr(RelationEvidence, "from_json")


def test_transfer_pair_auto_accept_exact():
    out_leg = _fv(
        id="a", amount=Decimal("-1000"), account_id="1", account_name="A",
        occurred_at="2026-01-01 10:00:00", note="转账支取",
    )
    in_leg = _fv(
        id="b", amount=Decimal("1000"), account_id="2", account_name="B",
        occurred_at="2026-01-01 10:00:05", note="转账存入",
    )
    proposal = evaluate_transfer_pair(out_leg, [in_leg])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.kind == "transfer_pair"
    assert proposal.subtype == ""


def test_transfer_pair_exact_counterparty_account_selects_only_registered_target():
    out_leg = _fv(
        id="out", amount=Decimal("-1000"), account_id="source", account_name="来源账户",
        occurred_at="2026-01-01 10:00:00", note="转账支取",
        counterparty_account="6222-0000-0000-1234",
    )
    wrong = _fv(
        id="wrong", amount=Decimal("1000"), account_id="wrong-account", account_name="错误候选",
        occurred_at="2026-01-01 10:00:01", note="转账存入",
    )
    target = _fv(
        id="target", amount=Decimal("1000"), account_id="target-account", account_name="目标账户",
        occurred_at="2026-01-01 10:00:02", note="转账存入",
    )

    proposal = evaluate_transfer_pair(
        out_leg, [wrong, target],
        account_identifiers_by_value={"6222000000001234": ["target-account"]},
    )

    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.secondary_fact_id == "target"


def test_transfer_pair_unique_counterparty_tail_selects_only_registered_target():
    out_leg = _fv(
        id="out", amount=Decimal("-1000"), account_id="source", account_name="来源账户",
        occurred_at="2026-01-01 10:00:00", note="转账支取",
        counterparty_account="示例银行储蓄卡(1234)",
    )
    wrong = _fv(
        id="wrong", amount=Decimal("1000"), account_id="wrong-account", account_name="错误候选",
        occurred_at="2026-01-01 10:00:01", note="转账存入",
    )
    target = _fv(
        id="target", amount=Decimal("1000"), account_id="target-account", account_name="目标账户",
        occurred_at="2026-01-01 10:00:02", note="转账存入",
    )

    proposal = evaluate_transfer_pair(
        out_leg, [wrong, target],
        card_tails_by_value={"1234": ["target-account"]},
    )

    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.secondary_fact_id == "target"


def test_transfer_pair_excludes_registered_candidates_with_mismatched_counterparty_account():
    out_leg = _fv(
        id="out", amount=Decimal("-1000"), account_id="source", account_name="来源账户",
        occurred_at="2026-01-01 10:00:00", note="转账支取",
        counterparty_account="示例银行储蓄卡(9999)",
    )
    first = _fv(
        id="first", amount=Decimal("1000"), account_id="first-account", account_name="候选一",
        occurred_at="2026-01-01 10:00:01", note="转账存入",
    )
    second = _fv(
        id="second", amount=Decimal("1000"), account_id="second-account", account_name="候选二",
        occurred_at="2026-01-01 10:00:02", note="转账存入",
    )

    proposal = evaluate_transfer_pair(
        out_leg, [first, second],
        card_tails_by_value={"1234": ["first-account"], "5678": ["second-account"]},
    )

    assert proposal is None


def test_transfer_pair_conflicting_counterparty_tail_does_not_auto_accept():
    out_leg = _fv(
        id="out", amount=Decimal("-1000"), account_id="source", account_name="来源账户",
        occurred_at="2026-01-01 10:00:00", note="转账支取",
        counterparty_account="示例银行储蓄卡(1234)",
    )
    first = _fv(
        id="first", amount=Decimal("1000"), account_id="first-account", account_name="候选一",
        occurred_at="2026-01-01 10:00:01", note="转账存入",
    )
    second = _fv(
        id="second", amount=Decimal("1000"), account_id="second-account", account_name="候选二",
        occurred_at="2026-01-01 10:00:02", note="转账存入",
    )

    proposal = evaluate_transfer_pair(
        out_leg, [first, second],
        card_tails_by_value={"1234": ["first-account", "second-account"]},
    )

    assert proposal is not None
    assert proposal.status != RelationStatus.ACCEPTED.value


def test_transfer_pair_without_counterparty_account_keeps_existing_behavior():
    out_leg = _fv(
        id="out", amount=Decimal("-1000"), account_id="source", account_name="来源账户",
        occurred_at="2026-01-01 10:00:00", note="转账支取",
    )
    candidate = _fv(
        id="candidate", amount=Decimal("1000"), account_id="target", account_name="目标账户",
        occurred_at="2026-01-01 10:00:01", note="转账存入",
    )

    proposal = evaluate_transfer_pair(
        out_leg, [candidate],
        card_tails_by_value={"1234": ["target"]},
    )

    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_transfer_pair_without_numeric_counterparty_account_keeps_existing_behavior():
    out_leg = _fv(
        id="out", amount=Decimal("-1000"), account_id="source", account_name="来源账户",
        occurred_at="2026-01-01 10:00:00", note="转账支取",
        counterparty_account="未提供账号",
    )
    candidate = _fv(
        id="candidate", amount=Decimal("1000"), account_id="target", account_name="目标账户",
        occurred_at="2026-01-01 10:00:01", note="转账存入",
    )

    proposal = evaluate_transfer_pair(
        out_leg, [candidate],
        card_tails_by_value={"1234": ["target"]},
    )

    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_transfer_amount_delta_pending():
    out_leg = _fv(
        id="a", amount=Decimal("-100"), account_id="1", account_name="A",
        occurred_at="2026-01-01 10:00:00", note="转账支取",
    )
    in_leg = _fv(
        id="b", amount=Decimal("99.99"), account_id="2", account_name="B",
        occurred_at="2026-01-01 10:00:05", note="转账存入",
    )
    proposal = evaluate_transfer_pair(out_leg, [in_leg])
    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
    assert Decimal(proposal.evidence.amount_delta) == Decimal("0.01")


def test_unionpay_same_day_auto_accept():
    out_leg = _fv(
        id="a", amount=Decimal("-200"), account_id="1", account_name="A",
        occurred_at="2026-01-01 12:00:00", note="无卡付转账支取",
    )
    in_leg = _fv(
        id="b", amount=Decimal("200"), account_id="2", account_name="B",
        occurred_at="2026-01-01 00:00:00", note="银联入账电子汇入",
    )
    proposal = evaluate_transfer_pair(out_leg, [in_leg])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_unionpay_ccb_date_only_uses_raw_business_day_auto():
    """CCB date-only raw date aligns with ICBC local business day despite formal 16:00 UTC."""
    from ft.domain.relations import (
        RULE_TRANSFER_PAIR_UNIONPAY_V1,
        RULE_TRANSFER_PAIR_STRONG_V1,
        FactType,
        FactView,
    )

    out_leg = FactView(
        id="icbc_out",
        amount=Decimal("-5000"),
        currency="CNY",
        account_id="icbc",
        account_name="工行借记卡",
        account_type="cash",
        occurred_at="2024-05-05 17:48:03",
        counterparty="银联转账（云闪付）",
        note="无卡支付",
        record_type="transfer_out",
        fact_type=FactType.CASH.value,
        raw_payload={"occurred_at": "2024-05-06 01:48:03"},
    )
    in_leg = FactView(
        id="ccb_in",
        amount=Decimal("5000"),
        currency="CNY",
        account_id="ccb",
        account_name="建行储蓄卡(2820)",
        account_type="cash",
        occurred_at="2024-05-05 16:00:00",
        counterparty="微信",
        note="银联入账",
        record_type="transfer_in",
        fact_type=FactType.CASH.value,
        raw_payload={"occurred_at": "2024-05-06"},
    )
    proposal = evaluate_transfer_pair(out_leg, [in_leg])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.rule_id in {
        RULE_TRANSFER_PAIR_UNIONPAY_V1,
        RULE_TRANSFER_PAIR_STRONG_V1,
    }
    # Fake multi-hour formal Δt must not apply when date-only raw day matches
    assert proposal.evidence.time_delta_seconds == 0


def test_credit_repayment_subtype():
    cash = _fv(
        id="c", amount=Decimal("-5000"), account_id="1", account_name="储蓄",
        account_type="cash", occurred_at="2026-01-01 10:00:00", note="信用卡还款",
    )
    loan = _fv(
        id="l", amount=Decimal("5000"), account_id="2", account_name="信用卡",
        account_type="loan", occurred_at="2026-01-01 10:05:00", note="还款入账",
    )
    proposal = evaluate_transfer_pair(cash, [loan])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.subtype == SUBTYPE_CREDIT_REPAYMENT


def test_credit_repayment_fx_unique_high_confidence_auto():
    """Exactly one FX candidate within rate_error threshold → accepted."""
    cash = _fv(
        id="c", amount=Decimal("-144.61"), account_id="1", account_name="储蓄",
        account_type="cash", currency="CNY",
        occurred_at="2025-12-19 06:30:57", note="购汇还款",
    )
    loan = _fv(
        id="l", amount=Decimal("159.40"), account_id="2", account_name="信用卡",
        account_type="loan", currency="HKD",
        occurred_at="2025-12-19 06:30:58", note="手机银行",
    )
    # market: HKD per 1 CNY ≈ 159.40/144.61 ≈ 1.1023
    proposal = evaluate_transfer_pair(
        cash, [loan],
        fx_rate_provider=lambda day, base, quote: Decimal("1.1051"),
    )
    assert proposal is not None
    assert proposal.subtype == SUBTYPE_CREDIT_REPAYMENT
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.secondary_fact_id == "l"
    assert proposal.evidence.extras.get("market_rate")
    assert proposal.evidence.extras.get("rate_error")


def test_credit_repayment_fx_multi_candidate_rate_separates():
    """Two FX inflows (HKD vs JPY): each outflow picks its unique high-confidence match."""
    cash_hkd = _fv(
        id="c1", amount=Decimal("-144.61"), account_id="cash", account_name="工行借记卡",
        account_type="cash", currency="CNY",
        occurred_at="2025-12-19 06:30:57", note="购汇还款",
    )
    cash_jpy = _fv(
        id="c2", amount=Decimal("-101.58"), account_id="cash", account_name="工行借记卡",
        account_type="cash", currency="CNY",
        occurred_at="2025-12-19 06:31:00", note="购汇还款",
    )
    loan_hkd = _fv(
        id="l_hkd", amount=Decimal("159.40"), account_id="loan", account_name="工行信用卡(0851)",
        account_type="loan", currency="HKD",
        occurred_at="2025-12-19 06:30:58", note="手机银行",
    )
    loan_jpy = _fv(
        id="l_jpy", amount=Decimal("2240.00"), account_id="loan", account_name="工行信用卡(0851)",
        account_type="loan", currency="JPY",
        occurred_at="2025-12-19 06:31:01", note="手机银行",
    )

    def rates(day, base, quote):
        # frankfurter-like CNY base → quote
        table = {"HKD": Decimal("1.1051"), "JPY": Decimal("22.331"), "USD": Decimal("0.14202")}
        if base == "CNY":
            return table.get(quote)
        return None

    p1 = evaluate_transfer_pair(cash_hkd, [loan_hkd, loan_jpy], fx_rate_provider=rates)
    assert p1 is not None
    assert p1.status == RelationStatus.ACCEPTED.value
    assert p1.secondary_fact_id == "l_hkd"

    p2 = evaluate_transfer_pair(cash_jpy, [loan_hkd, loan_jpy], fx_rate_provider=rates)
    assert p2 is not None
    assert p2.status == RelationStatus.ACCEPTED.value
    assert p2.secondary_fact_id == "l_jpy"


def test_credit_repayment_fx_no_rate_pending():
    cash = _fv(
        id="c", amount=Decimal("-100"), account_id="1", account_name="储蓄",
        account_type="cash", currency="CNY",
        occurred_at="2026-01-01 10:00:00", note="购汇还款",
    )
    loan = _fv(
        id="l", amount=Decimal("14"), account_id="2", account_name="信用卡",
        account_type="loan", currency="USD",
        occurred_at="2026-01-01 10:00:05", note="手机银行",
    )
    proposal = evaluate_transfer_pair(
        cash, [loan],
        fx_rate_provider=lambda *a, **k: None,
    )
    assert proposal is not None
    assert proposal.subtype == SUBTYPE_CREDIT_REPAYMENT
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
    assert proposal.open_leg is False  # unique candidate → bilateral pending


def test_credit_repayment_fx_two_high_confidence_open_leg_pending():
    """Two candidates within the threshold and too close produce an unpaired pending relation."""
    cash = _fv(
        id="c", amount=Decimal("-100"), account_id="1", account_name="储蓄",
        account_type="cash", currency="CNY",
        occurred_at="2026-01-01 10:00:00", note="购汇还款",
    )
    a = _fv(
        id="la", amount=Decimal("110"), account_id="2", account_name="卡A",
        account_type="loan", currency="HKD",
        occurred_at="2026-01-01 10:00:01", note="手机银行",
    )
    b = _fv(
        id="lb", amount=Decimal("110.3"), account_id="3", account_name="卡B",
        account_type="loan", currency="HKD",
        occurred_at="2026-01-01 10:00:02", note="手机银行",
    )
    # market 1.10 → errors ~0 and ~0.0027 both under 1.5% and margin < 0.5pp
    proposal = evaluate_transfer_pair(
        cash, [a, b],
        fx_rate_provider=lambda day, base, quote: Decimal("1.10"),
    )
    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
    assert proposal.open_leg is True
    assert proposal.secondary_fact_id is None
    assert proposal.evidence.candidate_count == 2


def test_bare_unionpay_merchant_refund_not_transfer_signal():
    """「中国银联无卡…退货」must not pair with merchant spend via bare 银联 token."""
    out_leg = _fv(
        id="a", amount=Decimal("-100"), account_id="1", account_name="微信零钱",
        occurred_at="2023-06-19 08:54:52", counterparty="美团",
        note="美团订单-23061911100400000024247213555312",
        record_type="consumption",
    )
    in_leg = _fv(
        id="b", amount=Decimal("100"), account_id="2", account_name="工行借记卡",
        occurred_at="2023-06-19 10:06:50", counterparty="中国银联无卡快捷支付业务专户",
        note="退货",
        record_type="income",
    )
    assert evaluate_transfer_pair(out_leg, [in_leg]) is None


def test_unionpay_compound_signals_still_match():
    from ft.domain.relations import has_transfer_signal
    assert has_transfer_signal("银联入账") is True
    assert has_transfer_signal("银联转账（云闪付）") is True
    assert has_transfer_signal("无卡支付") is True
    assert has_transfer_signal("云闪付") is True
    # bare rail name alone is not enough
    assert has_transfer_signal("中国银联无卡快捷支付业务专户 退货") is False
    assert has_transfer_signal("银联") is False


def test_transfer_without_source_out_signal_is_not_a_seed():
    out_leg = _fv(
        id="a", amount=Decimal("-100"), account_id="1", account_name="A",
        occurred_at="2026-01-01 10:00:00", note="支出",
        record_type="consumption",
    )
    in_leg = _fv(
        id="b", amount=Decimal("100"), account_id="2", account_name="B",
        occurred_at="2026-01-01 10:00:05", note="收入",
        record_type="income",
    )
    assert evaluate_transfer_pair(out_leg, [in_leg]) is None


def test_transfer_signal_exact_beyond_10s_within_5min_pending():
    out_leg = _fv(
        id="a", amount=Decimal("-100"), account_id="1", account_name="A",
        occurred_at="2026-01-01 10:00:00", note="转账支取",
    )
    in_leg = _fv(
        id="b", amount=Decimal("100"), account_id="2", account_name="B",
        occurred_at="2026-01-01 10:02:00", note="转账存入",
    )
    proposal = evaluate_transfer_pair(out_leg, [in_leg])
    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value


def test_personal_fx_exchange_same_account_auto_accepts():
    out_leg = _fv(
        id="out", amount=Decimal("-47952.10"), currency="CNY",
        account_id="icbc", account_name="工行借记卡", bill_source="icbc_debit",
        source="icbc_debit", record_type="fx_out",
        occurred_at="2026-05-02 09:36:56", note="个人购汇",
    )
    in_leg = _fv(
        id="in", amount=Decimal("7000"), currency="USD",
        account_id="icbc", account_name="工行借记卡", bill_source="icbc_debit",
        source="icbc_debit", record_type="fx_in",
        occurred_at="2026-05-02 09:36:56", note="个人购汇",
    )

    proposals = match_transfer_pairs_phase_c([out_leg, in_leg])

    assert len(proposals) == 1
    proposal = proposals[0]
    assert proposal.kind == "transfer_pair"
    assert proposal.subtype == "currency_exchange"
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.primary_fact_id == "out"
    assert proposal.secondary_fact_id == "in"
    assert proposal.evidence.source_pair == ("icbc_debit", "icbc_debit")
    assert proposal.evidence.candidate_count == 1


def test_personal_fx_exchange_multiple_candidates_creates_open_pending():
    out_leg = _fv(
        id="out", amount=Decimal("-100"), currency="CNY", account_id="icbc",
        bill_source="icbc_debit", source="icbc_debit", record_type="fx_out",
        occurred_at="2026-05-02 09:36:56", note="个人购汇",
    )
    usd_leg = _fv(
        id="usd", amount=Decimal("14"), currency="USD", account_id="icbc",
        bill_source="icbc_debit", source="icbc_debit", record_type="fx_in",
        occurred_at="2026-05-02 09:36:56", note="个人购汇",
    )
    hkd_leg = _fv(
        id="hkd", amount=Decimal("110"), currency="HKD", account_id="icbc",
        bill_source="icbc_debit", source="icbc_debit", record_type="fx_in",
        occurred_at="2026-05-02 09:36:56", note="个人购汇",
    )

    proposals = match_transfer_pairs_phase_c([out_leg, usd_leg, hkd_leg])

    assert len(proposals) == 1
    proposal = proposals[0]
    assert proposal.subtype == "currency_exchange"
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
    assert proposal.open_leg is True
    assert proposal.primary_fact_id == "out"
    assert proposal.secondary_fact_id is None
    assert proposal.evidence.candidate_count == 2
    assert proposal.evidence.candidate_fact_ids == ("hkd", "usd")


def test_personal_fx_exchange_date_only_stays_pending_with_null_delta():
    out_leg = _fv(
        id="out", amount=Decimal("-100"), currency="CNY", account_id="icbc",
        bill_source="icbc_debit", source="icbc_debit", record_type="fx_out",
        occurred_at="2026-05-02 16:00:00", raw_payload={"date": "2026-05-02"},
    )
    in_leg = _fv(
        id="in", amount=Decimal("14"), currency="USD", account_id="icbc",
        bill_source="icbc_debit", source="icbc_debit", record_type="fx_in",
        occurred_at="2026-05-02 16:00:00", raw_payload={"date": "2026-05-02"},
    )

    proposals = match_transfer_pairs_phase_c([out_leg, in_leg])

    assert len(proposals) == 1
    proposal = proposals[0]
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
    assert proposal.open_leg is True
    assert proposal.secondary_fact_id is None
    assert proposal.evidence.time_delta_seconds is None
    assert proposal.evidence.extras["temporal_precision"] == "business_day_only"


def test_personal_fx_exchange_reverse_ambiguity_stays_pending():
    out_a = _fv(
        id="out-a", amount=Decimal("-100"), currency="CNY", account_id="icbc",
        bill_source="icbc_debit", source="icbc_debit", record_type="fx_out",
        occurred_at="2026-05-02 09:36:50", note="个人购汇",
    )
    out_b = _fv(
        id="out-b", amount=Decimal("-200"), currency="CNY", account_id="icbc",
        bill_source="icbc_debit", source="icbc_debit", record_type="fx_out",
        occurred_at="2026-05-02 09:36:55", note="个人购汇",
    )
    in_leg = _fv(
        id="in", amount=Decimal("14"), currency="USD", account_id="icbc",
        bill_source="icbc_debit", source="icbc_debit", record_type="fx_in",
        occurred_at="2026-05-02 09:36:56", note="个人购汇",
    )

    proposals = match_transfer_pairs_phase_c([out_a, out_b, in_leg])

    assert {proposal.primary_fact_id for proposal in proposals} == {"out-a", "out-b"}
    assert all(proposal.status == RelationStatus.PENDING_REVIEW.value for proposal in proposals)
    assert all(proposal.secondary_fact_id is None for proposal in proposals)


def test_personal_fx_exchange_incoming_seed_rechecks_existing_outgoing_leg():
    out_leg = _fv(
        id="out", amount=Decimal("-47952.10"), currency="CNY", account_id="icbc",
        bill_source="icbc_debit", source="icbc_debit", record_type="fx_out",
        occurred_at="2026-05-02 09:36:56", note="个人购汇",
    )
    in_leg = _fv(
        id="in", amount=Decimal("7000"), currency="USD", account_id="icbc",
        bill_source="icbc_debit", source="icbc_debit", record_type="fx_in",
        occurred_at="2026-05-02 09:36:56", note="个人购汇",
    )
    from ft.domain.relations import FactCandidateIndex

    index = FactCandidateIndex([out_leg, in_leg])
    proposals = match_transfer_pairs_phase_c([out_leg, in_leg], seed_ids=["in"], index=index)

    assert len(proposals) == 1
    assert proposals[0].status == RelationStatus.ACCEPTED.value
    assert proposals[0].primary_fact_id == "out"
    assert proposals[0].secondary_fact_id == "in"


def test_personal_fx_exchange_rejects_empty_or_conflicting_source():
    out_leg = _fv(
        id="out", amount=Decimal("-100"), currency="CNY", account_id="icbc",
        occurred_at="2026-05-02 09:36:56", record_type="fx_out",
    )
    in_leg = _fv(
        id="in", amount=Decimal("14"), currency="USD", account_id="icbc",
        occurred_at="2026-05-02 09:36:56", record_type="fx_in",
    )
    conflicting = _fv(
        id="conflicting", amount=Decimal("14"), currency="USD", account_id="icbc",
        bill_source="icbc_debit", source="ccb_debit",
        occurred_at="2026-05-02 09:36:56", record_type="fx_in",
    )

    assert match_transfer_pairs_phase_c([out_leg, in_leg]) == []
    assert match_transfer_pairs_phase_c([
        _fv(
            id="out-source", amount=Decimal("-100"), currency="CNY", account_id="icbc",
            bill_source="icbc_debit", source="icbc_debit",
            occurred_at="2026-05-02 09:36:56", record_type="fx_out",
        ),
        conflicting,
    ]) == []


def test_personal_fx_exchange_rejects_unrelated_or_invalid_legs():
    out_leg = _fv(
        id="out", amount=Decimal("-100"), currency="CNY", account_id="icbc",
        bill_source="icbc_debit", source="icbc_debit", record_type="fx_out",
        occurred_at="2026-05-02 09:36:56", note="个人购汇",
    )
    same_currency = _fv(
        id="same-currency", amount=Decimal("100"), currency="CNY", account_id="icbc",
        bill_source="icbc_debit", source="icbc_debit", record_type="fx_in",
        occurred_at="2026-05-02 09:36:56", note="个人购汇",
    )
    other_source = _fv(
        id="other-source", amount=Decimal("14"), currency="USD", account_id="icbc",
        bill_source="ccb_debit", source="ccb_debit", record_type="fx_in",
        occurred_at="2026-05-02 09:36:56", note="个人购汇",
    )
    stale = _fv(
        id="stale", amount=Decimal("14"), currency="USD", account_id="icbc",
        bill_source="icbc_debit", source="icbc_debit", record_type="fx_in",
        occurred_at="2026-05-02 09:38:00", note="个人购汇",
    )

    assert match_transfer_pairs_phase_c([out_leg, same_currency]) == []
    assert match_transfer_pairs_phase_c([out_leg, other_source]) == []
    assert match_transfer_pairs_phase_c([out_leg, stale]) == []


def test_icbc_asia_internal_cross_currency_transfer_matches_same_account():
    out_leg = _fv(
        id="asia-out", amount=Decimal("-87.19"), currency="CNY",
        account_id="asia", account_name="工银亚洲账户", bill_source="icbc_asia_current_account",
        source="icbc_asia_current_account", record_type="transfer_out",
        counterparty_account="123456781", occurred_at="2026-05-24 13:47:09",
        note="手机转账提款",
    )
    in_leg = _fv(
        id="asia-in", amount=Decimal("100"), currency="HKD",
        account_id="asia", account_name="工银亚洲账户", bill_source="icbc_asia_current_account",
        source="icbc_asia_current_account", record_type="transfer_in",
        occurred_at="2026-05-24 13:47:12", note="手机转账存款",
    )

    proposals = match_transfer_pairs_phase_c(
        [out_leg, in_leg], card_tails_by_value={"6780": ["asia"]},
    )

    assert len(proposals) == 1
    proposal = proposals[0]
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.subtype == "currency_exchange"
    assert {proposal.primary_fact_id, proposal.secondary_fact_id} == {"asia-out", "asia-in"}


def test_icbc_asia_internal_same_currency_transfer_requires_exact_amount():
    out_leg = _fv(
        id="asia-out", amount=Decimal("-100"), currency="HKD", account_id="asia",
        bill_source="icbc_asia_current_account", source="icbc_asia_current_account",
        record_type="transfer_out", counterparty_account="123456781",
        occurred_at="2026-05-24 13:47:09", note="手机转账提款",
    )
    in_leg = _fv(
        id="asia-in", amount=Decimal("99.99"), currency="HKD", account_id="asia",
        bill_source="icbc_asia_current_account", source="icbc_asia_current_account",
        record_type="transfer_in", occurred_at="2026-05-24 13:47:12", note="手机转账存款",
    )

    assert match_transfer_pairs_phase_c(
        [out_leg, in_leg], account_identifiers_by_value={"123456780": ["asia"]},
    ) == []


def test_icbc_asia_internal_transfer_ambiguity_creates_open_pending():
    out_leg = _fv(
        id="asia-out", amount=Decimal("-100"), currency="CNY", account_id="asia",
        bill_source="icbc_asia_current_account", source="icbc_asia_current_account",
        record_type="transfer_out", counterparty_account="123456781",
        occurred_at="2026-05-24 13:47:09", note="手机转账提款",
    )
    first = _fv(
        id="first", amount=Decimal("107"), currency="HKD", account_id="asia",
        bill_source="icbc_asia_current_account", source="icbc_asia_current_account",
        record_type="transfer_in", occurred_at="2026-05-24 13:47:12", note="手机转账存款",
    )
    second = _fv(
        id="second", amount=Decimal("108"), currency="HKD", account_id="asia",
        bill_source="icbc_asia_current_account", source="icbc_asia_current_account",
        record_type="transfer_in", occurred_at="2026-05-24 13:47:15", note="手机转账存款",
    )

    proposals = match_transfer_pairs_phase_c(
        [out_leg, first, second], account_identifiers_by_value={"123456780": ["asia"]},
    )

    assert len(proposals) == 1
    proposal = proposals[0]
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
    assert proposal.secondary_fact_id is None
    assert proposal.evidence.candidate_fact_ids == ("first", "second")


def test_icbc_cross_border_remittance_matches_icbc_asia_credit():
    out_leg = _fv(
        id="debit-out", amount=Decimal("-100"), currency="CNY", account_id="debit",
        account_name="工行借记卡", bill_source="icbc_debit", source="icbc_debit",
        record_type="transfer_out", counterparty_account="123456781",
        occurred_at="2026-05-24 13:44:06", note="跨境汇款",
    )
    in_leg = _fv(
        id="asia-in", amount=Decimal("100"), currency="CNY", account_id="asia",
        account_name="工银亚洲账户", bill_source="icbc_asia_current_account",
        source="icbc_asia_current_account", record_type="transfer_in",
        occurred_at="2026-05-24 13:44:13", note="FPS Transfer",
    )

    proposals = match_transfer_pairs_phase_c(
        [out_leg, in_leg], card_tails_by_value={"6780": ["asia"]},
    )

    assert len(proposals) == 1
    proposal = proposals[0]
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.subtype == ""
    assert {proposal.primary_fact_id, proposal.secondary_fact_id} == {"debit-out", "asia-in"}


def test_icbc_cross_border_remittance_next_day_and_non_cross_border_gate():
    out_leg = _fv(
        id="debit-out", amount=Decimal("-2000"), currency="USD", account_id="debit",
        bill_source="icbc_debit", source="icbc_debit", record_type="transfer_out",
        counterparty_account="123456781", occurred_at="2026-05-24 13:44:06", note="跨境汇款",
    )
    in_leg = _fv(
        id="asia-in", amount=Decimal("2000"), currency="USD", account_id="asia",
        bill_source="icbc_asia_current_account", source="icbc_asia_current_account",
        record_type="transfer_in", occurred_at="2026-05-25 08:44:06", note="汇款存入",
    )
    unrelated = _fv(
        id="not-cross-border", amount=Decimal("-2000"), currency="USD", account_id="other",
        bill_source="icbc_debit", source="icbc_debit", record_type="fx_out",
        counterparty_account="123456781", occurred_at="2026-05-24 13:44:06", note="个人购汇",
    )

    proposals = match_transfer_pairs_phase_c(
        [out_leg, in_leg, unrelated], account_identifiers_by_value={"123456780": ["asia"]},
    )

    assert len(proposals) == 1
    assert proposals[0].status == RelationStatus.ACCEPTED.value
    assert proposals[0].primary_fact_id == "debit-out"


def test_icbc_cross_border_remittance_multiple_candidates_creates_open_pending():
    out_leg = _fv(
        id="debit-out", amount=Decimal("-100"), currency="CNY", account_id="debit",
        bill_source="icbc_debit", source="icbc_debit", record_type="transfer_out",
        counterparty_account="123456781", occurred_at="2026-05-24 13:44:06", note="跨境汇款",
    )
    first = _fv(
        id="first", amount=Decimal("100"), currency="CNY", account_id="asia",
        bill_source="icbc_asia_current_account", source="icbc_asia_current_account",
        record_type="transfer_in", occurred_at="2026-05-24 13:54:06", note="FPS Transfer",
    )
    second = _fv(
        id="second", amount=Decimal("100"), currency="CNY", account_id="asia",
        bill_source="icbc_asia_current_account", source="icbc_asia_current_account",
        record_type="transfer_in", occurred_at="2026-05-24 14:04:06", note="FPS Transfer",
    )

    proposals = match_transfer_pairs_phase_c(
        [out_leg, first, second], account_identifiers_by_value={"123456780": ["asia"]},
    )

    assert len(proposals) == 1
    proposal = proposals[0]
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
    assert proposal.secondary_fact_id is None
    assert proposal.evidence.candidate_fact_ids == ("first", "second")
