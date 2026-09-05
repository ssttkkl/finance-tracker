"""US1 payment_mirror tests."""
from __future__ import annotations

from decimal import Decimal

from ft.domain.relations import (
    FactView,
    RelationStatus,
    evaluate_payment_mirror,
    match_canonical_payment_mirrors,
    match_payment_mirrors_greedy,
    source_group,
    project_balances_and_pnl,
)


def _fv(**kwargs):
    fid = str(kwargs.get("id") or "")
    # Fixture heuristic: bank-side rows often use b* IDs, bank* accounts, or 工行 note markers.
    default_src = "alipay"
    if fid.startswith("b") or "bank" in str(kwargs.get("account_id") or "").lower():
        default_src = "icbc"
    note = str(kwargs.get("note") or "") + str(kwargs.get("counterparty") or "")
    if any(tok in note for tok in ("工行", "建行", "银行", "借记", "信用卡", "银联", "1614")) and "支付宝" not in note and "微信" not in note:
        # weak; prefer id heuristic
        pass
    base = dict(
        currency="CNY",
        account_type="cash",
        fact_type="cash",
        deleted=False,
        bill_source=default_src,
        source=default_src,
    )
    base.update(kwargs)
    base.pop("category", None)
    if "record_type" not in base:
        amount = Decimal(str(base.get("amount") or 0))
        note_text = str(base.get("note") or "")
        base["record_type"] = (
            "consumption" if amount < 0 else
            "refund" if any(token in note_text for token in ("退款", "退货", "冲正")) else
            "income" if amount > 0 else "other"
        )
    return FactView(**base)


def test_source_group_platform_bank():
    assert source_group(_fv(id="1", amount=Decimal("-1"), account_id="a", bill_source="alipay", source="alipay")) == "platform"
    assert source_group(_fv(id="2", amount=Decimal("-1"), account_id="b", bill_source="icbc", source="icbc")) == "bank"


def test_payment_mirror_auto_accept_strong_unique():
    seed = _fv(
        id="p1", amount=Decimal("-30.00"), account_id="card",
        occurred_at="2026-06-13 23:15:00", counterparty="麦当劳",
        note="付款方式 尾号1234",
    )
    bank = _fv(
        id="b1", amount=Decimal("-30.00"), account_id="card",
        occurred_at="2026-06-13 23:15:05", counterparty="支付宝-麦当劳",
        note="快捷支付 尾号1234",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_canonical_mirror_matcher_keeps_human_endpoints_occupied():
    platform = _fv(
        id="p1", amount=Decimal("-30.00"), account_id="card",
        occurred_at="2026-06-13 12:00:00", counterparty="麦当劳",
        note="付款方式 尾号1234",
    )
    human_bank = _fv(
        id="b1", amount=Decimal("-30.00"), account_id="card",
        occurred_at="2026-06-13 12:00:05", counterparty="麦当劳",
        note="快捷支付 尾号1234",
    )
    competing_bank = _fv(
        id="b2", amount=Decimal("-30.00"), account_id="card",
        occurred_at="2026-06-13 12:00:06", counterparty="麦当劳",
        note="快捷支付 尾号1234",
    )

    assert match_canonical_payment_mirrors([platform, human_bank, competing_bank])
    assert match_canonical_payment_mirrors(
        [platform, human_bank, competing_bank],
        occupied_fact_ids={"p1", "b1"},
    ) == []


def test_payment_mirror_same_account_exact2_no_text_within_60s():
    seed = _fv(
        id="p1", amount=Decimal("-20.00"), account_id="card",
        occurred_at="2023-07-04 01:35:31", counterparty="世纪村项目部",
        note="世纪村项目部一部门",
    )
    bank = _fv(
        id="b1", amount=Decimal("-20.00"), account_id="card",
        occurred_at="2023-07-04 01:35:43", counterparty="支付宝（中国）网络技术有限公司",
        note="1614020101021984636",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.evidence.extras.get("lag_bank_minus_platform") == 12


def test_payment_mirror_same_account_long_lag_same_day_is_pending_high_recall():
    """FR-056: same-account exact same business day auto-accepts (no text required)."""
    seed = _fv(
        id="p1", amount=Decimal("-20.00"), account_id="card",
        occurred_at="2023-07-04 01:00:00", counterparty="商户A",
        note="明细",
    )
    bank = _fv(
        id="b1", amount=Decimal("-20.00"), account_id="card",
        occurred_at="2023-07-04 03:00:00", counterparty="支付宝（中国）网络技术有限公司",
        note="1614",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert "same_account" in proposal.rule_id or "business_day" in proposal.rule_id


def test_bank_date_only_without_identity_evidence_is_pending_review():
    platform = _fv(
        id="p1", amount=Decimal("-100.00"), account_id="card",
        occurred_at="2026-06-13 08:00:00", counterparty="金色提香",
        note="收钱码收款",
    )
    bank = _fv(
        id="b1", amount=Decimal("-100.00"), account_id="card",
        occurred_at="2026-06-13 16:00:00", counterparty="蚂蚁基金销售",
        note="消费", raw_payload={"date": "2026-06-13"},
    )

    proposal = evaluate_payment_mirror(platform, [bank])

    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value


def test_payment_method_full_account_identifier_verifies_same_account_mirror():
    platform = _fv(
        id="p1", amount=Decimal("-100.00"), account_id="card",
        occurred_at="2026-06-13 08:00:00", counterparty="商户", note="消费",
        payment_method="招商银行储蓄卡（6222 0000 0000 1234）",
    )
    bank = _fv(
        id="b1", amount=Decimal("-100.00"), account_id="card",
        occurred_at="2026-06-13 16:00:00", counterparty="平台扣款", note="消费",
        raw_payload={"date": "2026-06-13"},
    )

    proposal = evaluate_payment_mirror(
        platform,
        [bank],
        account_identifiers_by_value={"6222000000001234": ["card"]},
    )

    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_payment_method_tail_uses_registered_full_account_identifier():
    platform = _fv(
        id="p1", amount=Decimal("-100.00"), account_id="card",
        occurred_at="2026-06-13 08:00:00", counterparty="商户", note="消费",
        payment_method="招商银行储蓄卡（1234）",
    )
    bank = _fv(
        id="b1", amount=Decimal("-100.00"), account_id="card",
        occurred_at="2026-06-13 16:00:00", counterparty="平台扣款", note="消费",
        raw_payload={"date": "2026-06-13"},
    )

    proposal = evaluate_payment_mirror(
        platform,
        [bank],
        account_identifiers_by_value={"6222000000001234": ["card"]},
    )

    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_payment_method_tail_collision_does_not_verify_mirror():
    platform = _fv(
        id="p1", amount=Decimal("-100.00"), account_id="card-one",
        occurred_at="2026-06-13 08:00:00", counterparty="商户", note="消费",
        payment_method="招商银行储蓄卡（1234）",
    )
    bank = _fv(
        id="b1", amount=Decimal("-100.00"), account_id="card-one",
        occurred_at="2026-06-13 16:00:00", counterparty="平台扣款", note="消费",
        raw_payload={"date": "2026-06-13"},
    )

    proposal = evaluate_payment_mirror(
        platform,
        [bank],
        account_identifiers_by_value={
            "6222000000001234": ["card-one"],
            "4333000000001234": ["card-two"],
        },
    )

    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value


def test_payment_method_account_identifier_never_creates_cross_account_mirror():
    platform = _fv(
        id="p1", amount=Decimal("-100.00"), account_id="platform-card",
        occurred_at="2026-06-13 08:00:00", counterparty="商户", note="消费",
        payment_method="招商银行储蓄卡（6222000000001234）",
    )
    bank = _fv(
        id="b1", amount=Decimal("-100.00"), account_id="bank-card",
        occurred_at="2026-06-13 16:00:00", counterparty="平台扣款", note="消费",
        raw_payload={"date": "2026-06-13"},
    )

    assert evaluate_payment_mirror(
        platform,
        [bank],
        account_identifiers_by_value={"6222000000001234": ["bank-card"]},
    ) is None


def test_payment_mirror_same_account_platform_after_bank_is_pending_not_auto():
    """FR-056: 10s bank-before-platform skew on same account is accepted."""
    seed = _fv(
        id="p1", amount=Decimal("-20.00"), account_id="card",
        occurred_at="2023-07-04 12:00:10", counterparty="商户A",
        note="明细",
    )
    bank = _fv(
        id="b1", amount=Decimal("-20.00"), account_id="card",
        occurred_at="2023-07-04 12:00:00", counterparty="支付宝（中国）网络技术有限公司",
        note="1614",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_payment_mirror_rejects_bank_bank():
    a = _fv(
        id="b1", amount=Decimal("-100.00"), account_id="ccb1",
        occurred_at="2026-06-13 12:00:00", counterparty="微信", note="转账支取",
    )
    b = _fv(
        id="b2", amount=Decimal("-100.00"), account_id="ccb2",
        occurred_at="2026-06-13 12:00:00", counterparty="银行转证券", note="银转证",
    )
    assert evaluate_payment_mirror(a, [b]) is None


def test_payment_mirror_amount_delta_not_auto_accepted():
    seed = _fv(
        id="p1", amount=Decimal("-30.00"), account_id="card",
        occurred_at="2026-06-13 23:15:00", counterparty="麦当劳",
        note="尾号1234",
    )
    bank = _fv(
        id="b1", amount=Decimal("-30.01"), account_id="card",
        occurred_at="2026-06-13 23:15:05", counterparty="麦当劳",
        note="尾号1234",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
    assert Decimal(proposal.evidence.amount_delta) == Decimal("0.01")


def test_payment_mirror_bare_same_day_without_short_window_is_silent():
    seed = _fv(
        id="p1", amount=Decimal("-50.00"), account_id="a1",
        occurred_at="2026-06-13 10:00:00", counterparty="甲",
        note="订单A",
    )
    bank = _fv(
        id="b1", amount=Decimal("-50.00"), account_id="a2",
        occurred_at="2026-06-13 18:00:00", counterparty="乙",
        note="订单B",
    )
    assert evaluate_payment_mirror(seed, [bank]) is None


def test_payment_mirror_cross_account_never_mirrors():
    """Cross-account platform×bank is never a payment_mirror (even strong signals)."""
    seed = _fv(
        id="p1", amount=Decimal("-50.00"), account_id="wechat_wallet",
        occurred_at="2026-06-13 10:00:00", counterparty="星巴克",
        note="消费",
    )
    bank = _fv(
        id="b1", amount=Decimal("-50.00"), account_id="ccb",
        occurred_at="2026-06-13 10:00:03", counterparty="星巴克咖啡",
        note="快捷支付",
    )
    assert evaluate_payment_mirror(seed, [bank]) is None


def test_payment_mirror_short_window_text_unique_auto_accept():
    seed = _fv(
        id="p1", amount=Decimal("-50.00"), account_id="card",
        occurred_at="2026-06-13 10:00:00", counterparty="星巴克",
        note="消费",
    )
    bank = _fv(
        id="b1", amount=Decimal("-50.00"), account_id="card",
        occurred_at="2026-06-13 10:00:30", counterparty="星巴克咖啡",
        note="快捷支付",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_payment_mirror_multi_candidate_same_account_picks_nearest():
    """Same-account multi bank candidates: nearest accepted (not cross-account pending)."""
    seed = _fv(
        id="p1", amount=Decimal("-20.00"), account_id="card",
        occurred_at="2026-06-13 12:00:00", counterparty="商家",
        note="尾号1234",
    )
    cands = [
        _fv(
            id="b1", amount=Decimal("-20.00"), account_id="card",
            occurred_at="2026-06-13 12:00:03", counterparty="商家",
            note="尾号1234",
        ),
        _fv(
            id="b2", amount=Decimal("-20.00"), account_id="card",
            occurred_at="2026-06-13 12:00:04", counterparty="商家",
            note="尾号1234",
        ),
    ]
    proposal = evaluate_payment_mirror(seed, cands)
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.evidence.candidate_count == 2
    assert proposal.secondary_fact_id == "b1" or proposal.primary_fact_id == "b1"


def test_match_payment_mirrors_greedy_one_to_one():
    p1 = _fv(
        id="p1", amount=Decimal("-10.00"), account_id="card",
        occurred_at="2026-06-13 12:00:00", counterparty="店A", note="x",
    )
    p2 = _fv(
        id="p2", amount=Decimal("-10.00"), account_id="card",
        occurred_at="2026-06-13 12:00:01", counterparty="店A", note="x",
    )
    b1 = _fv(
        id="b1", amount=Decimal("-10.00"), account_id="card",
        occurred_at="2026-06-13 12:00:02", counterparty="店A", note="x",
    )
    props = match_payment_mirrors_greedy([p1, p2, b1])
    assert len(props) == 1
    assert {item.status for item in props} == {RelationStatus.PENDING_REVIEW.value}
    assert {
        frozenset((item.primary_fact_id, item.secondary_fact_id))
        for item in props
    } == {frozenset(("p1", "b1"))}


def test_payment_mirror_equal_candidate_group_pairs_by_time_then_id():
    p1 = _fv(
        id="p1", amount=Decimal("-10"), account_id="card",
        occurred_at="2026-06-13 10:00:00", counterparty="同一商户", note="消费",
    )
    p2 = _fv(
        id="p2", amount=Decimal("-10"), account_id="card",
        occurred_at="2026-06-13 10:01:00", counterparty="同一商户", note="消费",
    )
    b1 = _fv(
        id="b1", amount=Decimal("-10"), account_id="card",
        occurred_at="2026-06-13 09:00:00", counterparty="同一商户", note="扣款",
    )
    b2 = _fv(
        id="b2", amount=Decimal("-10"), account_id="card",
        occurred_at="2026-06-13 10:01:00", counterparty="同一商户", note="扣款",
    )

    proposals = match_payment_mirrors_greedy([b2, p2, b1, p1])

    assert {frozenset((item.primary_fact_id, item.secondary_fact_id)) for item in proposals} == {
        frozenset(("p1", "b1")),
        frozenset(("p2", "b2")),
    }
    assert {item.status for item in proposals} == {RelationStatus.ACCEPTED.value}


def test_icbc_mirror_matching_is_symmetric_when_seed_direction_changes():
    platform_early = _fv(
        id="p-early", amount=Decimal("-9"), account_id="card",
        occurred_at="2023-06-13 17:27:33", counterparty="商户A",
        bill_source="wechat", source="wechat",
    )
    platform_late = _fv(
        id="p-late", amount=Decimal("-9"), account_id="card",
        occurred_at="2023-06-13 21:32:37", counterparty="商户B",
        bill_source="wechat", source="wechat",
    )
    bank = _fv(
        id="b", amount=Decimal("-9"), account_id="card",
        occurred_at="2023-06-13 21:32:37", counterparty="支付机构",
        bill_source="icbc_debit", source="icbc_debit",
    )
    facts = [platform_early, platform_late, bank]

    reverse = match_payment_mirrors_greedy(facts, seed_ids=[platform_early.id, platform_late.id])
    forward = match_payment_mirrors_greedy(facts, seed_ids=[bank.id])

    assert {
        frozenset((item.primary_fact_id, item.secondary_fact_id))
        for item in reverse
    } == {frozenset((platform_late.id, bank.id))}
    assert {
        frozenset((item.primary_fact_id, item.secondary_fact_id))
        for item in forward
    } == {frozenset((platform_late.id, bank.id))}


def test_icbc_equal_best_candidates_stay_pending():
    platform = _fv(
        id="platform", amount=Decimal("-20"), account_id="card",
        occurred_at="2026-06-13 12:00:00", counterparty="商户",
        bill_source="wechat", source="wechat",
    )
    bank_rows = [
        _fv(
            id=f"bank-{suffix}", amount=Decimal("-20"), account_id="card",
            occurred_at="2026-06-13 12:00:00", counterparty="支付机构",
            bill_source="icbc_debit", source="icbc_debit",
        )
        for suffix in ("a", "b")
    ]

    proposal = evaluate_payment_mirror(platform, bank_rows)

    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value


def test_rejected_mirror_pair_is_excluded_but_other_candidate_can_match():
    platform = _fv(
        id="platform", amount=Decimal("-20"), account_id="card",
        occurred_at="2026-06-13 12:00:00", counterparty="商户",
        bill_source="wechat", source="wechat",
    )
    rejected = _fv(
        id="bank-rejected", amount=Decimal("-20"), account_id="card",
        occurred_at="2026-06-13 12:00:00", counterparty="商户",
        bill_source="icbc_debit", source="icbc_debit",
    )
    replacement = _fv(
        id="bank-replacement", amount=Decimal("-20"), account_id="card",
        occurred_at="2026-06-13 12:00:03", counterparty="商户",
        bill_source="icbc_debit", source="icbc_debit",
    )

    proposals = match_payment_mirrors_greedy(
        [platform, rejected, replacement],
        blocked_pairs={frozenset((platform.id, rejected.id))},
    )

    assert {
        frozenset((item.primary_fact_id, item.secondary_fact_id))
        for item in proposals
    } == {frozenset((platform.id, replacement.id))}


def test_deterministic_mirror_group_ignores_non_payment_record_types():
    platform = _fv(
        id="p1", amount=Decimal("-10"), account_id="card",
        occurred_at="2026-06-13 10:00:00", counterparty="同一商户", note="消费",
        record_type="consumption",
    )
    non_payment_bank_row = _fv(
        id="b0", amount=Decimal("-10"), account_id="card",
        occurred_at="2026-06-13 09:00:00", counterparty="同一商户", note="转账支取",
        record_type="transfer_out",
    )
    payment_bank_row = _fv(
        id="b1", amount=Decimal("-10"), account_id="card",
        occurred_at="2026-06-13 10:00:05", counterparty="同一商户", note="扣款",
        record_type="consumption",
    )

    proposals = match_payment_mirrors_greedy(
        [platform, non_payment_bank_row, payment_bank_row]
    )

    assert {frozenset((item.primary_fact_id, item.secondary_fact_id)) for item in proposals} == {
        frozenset(("p1", "b1")),
    }
    assert proposals[0].status == RelationStatus.ACCEPTED.value


def test_payment_mirror_unequal_candidate_group_stays_pending_review():
    p1 = _fv(
        id="p1", amount=Decimal("-10"), account_id="card",
        occurred_at="2026-06-13 10:00:00", counterparty="同一商户", note="消费",
    )
    p2 = _fv(
        id="p2", amount=Decimal("-10"), account_id="card",
        occurred_at="2026-06-13 10:01:00", counterparty="同一商户", note="消费",
    )
    b1 = _fv(
        id="b1", amount=Decimal("-10"), account_id="card",
        occurred_at="2026-06-13 10:02:00", counterparty="同一商户", note="扣款",
    )

    proposals = match_payment_mirrors_greedy([p1, p2, b1])

    assert len(proposals) == 1
    assert {item.status for item in proposals} == {RelationStatus.PENDING_REVIEW.value}
    assert {frozenset((item.primary_fact_id, item.secondary_fact_id)) for item in proposals} == {
        frozenset(("p1", "b1")),
    }


def test_payment_mirror_incomplete_candidate_group_stays_pending_review():
    platform = _fv(
        id="p1", amount=Decimal("-10"), account_id="card",
        occurred_at="2026-06-13 10:00:00", counterparty="", note="消费",
    )
    bank = _fv(
        id="b1", amount=Decimal("-10"), account_id="card",
        occurred_at="2026-06-13 10:01:00", counterparty="", note="扣款",
    )

    proposals = match_payment_mirrors_greedy([platform, bank])

    assert len(proposals) == 1
    assert proposals[0].status == RelationStatus.PENDING_REVIEW.value


def test_payment_mirror_rescan_does_not_cross_existing_accepted_pair():
    p1 = _fv(
        id="p1", amount=Decimal("-10"), account_id="card",
        occurred_at="2026-06-13 10:00:00", counterparty="同一商户", note="消费",
    )
    p2 = _fv(
        id="p2", amount=Decimal("-10"), account_id="card",
        occurred_at="2026-06-13 10:01:00", counterparty="同一商户", note="消费",
    )
    b1 = _fv(
        id="b1", amount=Decimal("-10"), account_id="card",
        occurred_at="2026-06-13 10:02:00", counterparty="同一商户", note="扣款",
    )
    b2 = _fv(
        id="b2", amount=Decimal("-10"), account_id="card",
        occurred_at="2026-06-13 10:03:00", counterparty="同一商户", note="扣款",
    )

    proposals = match_payment_mirrors_greedy(
        [p1, p2, b1, b2],
        occupied_fact_ids={"p1", "b1"},
    )

    assert len(proposals) == 1
    assert frozenset((proposals[0].primary_fact_id, proposals[0].secondary_fact_id)) == frozenset(("p2", "b2"))
    assert proposals[0].status == RelationStatus.ACCEPTED.value


def test_payment_mirror_uses_stable_channel_pair_order_and_global_endpoint_occupancy():
    platform = _fv(
        id="p1", amount=Decimal("-10"), account_id="card",
        occurred_at="2026-06-13 10:00:00", counterparty="同一商户", note="消费",
        bill_source="alipay", source="alipay",
    )
    ccb = _fv(
        id="b-ccb", amount=Decimal("-10"), account_id="card",
        occurred_at="2026-06-13 10:01:00", counterparty="同一商户", note="扣款",
        bill_source="ccb", source="ccb",
    )
    icbc = _fv(
        id="b-icbc", amount=Decimal("-10"), account_id="card",
        occurred_at="2026-06-13 10:02:00", counterparty="同一商户", note="扣款",
        bill_source="icbc", source="icbc",
    )

    proposals = match_payment_mirrors_greedy([icbc, platform, ccb])

    assert len(proposals) == 1
    assert frozenset((proposals[0].primary_fact_id, proposals[0].secondary_fact_id)) == frozenset(("p1", "b-ccb"))
    endpoint_ids = [fact_id for proposal in proposals for fact_id in (proposal.primary_fact_id, proposal.secondary_fact_id)]
    assert len(endpoint_ids) == len(set(endpoint_ids))


def test_payment_mirror_service_does_not_persist_shared_endpoint_across_channel_pairs(relation_runtime):
    from tests.test_transaction_relations_support import add_cash_fact, ensure_accounts

    services = relation_runtime.services
    ensure_accounts(services, [("结算账户", "cash")])
    platform_id = add_cash_fact(
        services,
        account_name="结算账户",
        amount="-10",
        date="2026-06-13 10:00:00",
        counterparty="同一商户",
            source="alipay",
            record_id="channel-pair-platform",
            record_type="consumption",
    )
    ccb_id = add_cash_fact(
        services,
        account_name="结算账户",
        amount="-10",
        date="2026-06-13 10:01:00",
        counterparty="同一商户",
            source="ccb",
            record_id="channel-pair-ccb",
            record_type="consumption",
    )
    icbc_id = add_cash_fact(
        services,
        account_name="结算账户",
        amount="-10",
        date="2026-06-13 10:02:00",
        counterparty="同一商户",
            source="icbc",
            record_id="channel-pair-icbc",
            record_type="consumption",
    )

    result = services.relations.check(
        seed_fact_ids=[platform_id, ccb_id, icbc_id],
        trigger="manual_range",
        seed_ref="channel-pair-order",
    )

    assert result.ok
    with services.uow as uow:
        accepted = uow.relations.list_active(kind="payment_mirror", status="accepted")
    assert len(accepted) == 1
    assert {accepted[0]["primary_fact_id"], accepted[0]["secondary_fact_id"]} == {platform_id, ccb_id}


def test_projection_mirror_counts_once_balances_both():
    facts = [
        _fv(id="p1", amount=Decimal("-30.00"), account_id="a", account_name="支付宝",
            occurred_at="2026-06-13 23:15:00", counterparty="麦当劳", category="expense"),
        _fv(id="b1", amount=Decimal("-30.00"), account_id="b", account_name="建行",
            occurred_at="2026-06-13 23:15:05", counterparty="麦当劳", category="expense"),
    ]
    relations = [{
        "kind": "payment_mirror",
        "primary_fact_id": "p1",
        "secondary_fact_id": "b1",
        "status": "accepted",
    }]
    result = project_balances_and_pnl(facts, relations)
    assert result.expenses["CNY"] == Decimal("30.00")
    assert result.balances[("支付宝", "CNY")] == Decimal("-30.00")
    assert result.balances[("建行", "CNY")] == Decimal("-30.00")


def test_payment_mirror_persisted_via_service(relation_runtime):
    services = relation_runtime.services
    # Both rows belong to the same card account, as required for payment_mirror.
    assert services.accounts.create_account("建行储蓄", "cash", "CNY").ok
    services.cashflow.add_manual_transaction(
        amount=Decimal("-30.00"), counterparty="麦当劳", account_name="建行储蓄",
        currency="CNY", date="2026-06-13 23:15:00", note="付款方式 尾号1234",
            category="expense", bill_source="alipay", source="alipay",
            record_type="consumption",
    )
    services.cashflow.add_manual_transaction(
        amount=Decimal("-30.00"), counterparty="支付宝-麦当劳", account_name="建行储蓄",
        currency="CNY", date="2026-06-13 23:15:05", note="快捷支付 尾号1234",
            category="expense", bill_source="icbc", source="icbc",
            record_type="consumption",
    )
    with services.uow as uow:
        ids = [r["id"] for r in uow.cashflows.list_detailed()]
    result = services.relations.check(seed_fact_ids=ids, trigger="manual_range", seed_ref="test")
    assert result.ok, result.message
    with services.uow as uow:
        all_rel = uow.relations.list_active(kind="payment_mirror")
    assert all_rel
    assert all_rel[0]["status"] in {"accepted", "pending_review"}


def test_payment_mirror_pending_same_account_lag_between_60s_and_5min():
    """FR-056: same-account exact same day accepts even beyond 60s lag."""
    seed = _fv(
        id="p1", amount=Decimal("-20.00"), account_id="card",
        occurred_at="2023-07-04 01:00:00", counterparty="商户A",
        note="明细",
    )
    bank = _fv(
        id="b1", amount=Decimal("-20.00"), account_id="card",
        occurred_at="2023-07-04 01:02:00", counterparty="支付宝（中国）网络技术有限公司",
        note="1614",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_payment_mirror_pending_platform_slightly_after_bank_same_account():
    seed = _fv(
        id="p1", amount=Decimal("-20.00"), account_id="card",
        occurred_at="2023-07-04 12:00:10", counterparty="商户A",
        note="明细",
    )
    bank = _fv(
        id="b1", amount=Decimal("-20.00"), account_id="card",
        occurred_at="2023-07-04 12:00:00", counterparty="支付宝（中国）网络技术有限公司",
        note="1614",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_payment_mirror_same_account_text_outside_60s_accepts_business_day():
    """Same-account exact same business day accepts even when lag is 3min with text."""
    seed = _fv(
        id="p1", amount=Decimal("-50.00"), account_id="card",
        occurred_at="2026-06-13 10:00:00", counterparty="星巴克",
        note="消费",
    )
    bank = _fv(
        id="b1", amount=Decimal("-50.00"), account_id="card",
        occurred_at="2026-06-13 10:03:00", counterparty="星巴克咖啡",
        note="快捷支付",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_payment_mirror_pending_same_account_same_day_long_lag_high_recall():
    """FR-056: same-account exact same business day accepts long lag (no text)."""
    seed = _fv(
        id="p1", amount=Decimal("-40.00"), account_id="card",
        occurred_at="2023-07-27 01:00:00", counterparty="北京市自来水集团有限责任公司",
        note="水费",
    )
    bank = _fv(
        id="b1", amount=Decimal("-40.00"), account_id="card",
        occurred_at="2023-07-27 12:00:00", counterparty="支付宝（中国）网络技术有限公司",
        note="1614",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_payment_mirror_same_account_text_same_day_long_lag_accepts():
    seed = _fv(
        id="p1", amount=Decimal("-50.00"), account_id="card",
        occurred_at="2026-06-13 10:00:00", counterparty="星巴克",
        note="消费",
    )
    bank = _fv(
        id="b1", amount=Decimal("-50.00"), account_id="card",
        occurred_at="2026-06-13 18:00:00", counterparty="星巴克咖啡",
        note="快捷支付",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
