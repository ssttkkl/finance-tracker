from decimal import Decimal

import pytest

from ft.application.relations import (
    RelationService,
    RelationPlan,
    _fact_view_from_row,
    _filter_illegal_automatic_proposals,
    _log_illegal_automatic_proposal_once,
    _stable_fact_ref,
    _relation_context_digest,
    plan_relation_proposals,
    relation_proposal_key,
    serialize_import_relation_plan,
)
from ft.domain.relations import FactView, RelationEvidence, RelationKind, RelationProposal, RelationStatus


def _fact(
    fact_id: str,
    *,
    amount: str,
    counterparty: str,
    record_type: str,
    occurred_at: str,
    record_id: str | None = None,
    merchant_order_id: str = "",
    txn_id: str = "",
    relation_metadata: dict | None = None,
    include_offset_role: bool = True,
):
    payload = {
        "merchant_order_id": merchant_order_id,
        "txn_id": txn_id,
        "status": "退款成功" if record_type == "refund" else "支付成功",
        "txn_type": "商户退款" if record_type == "refund" else "消费",
    }
    if include_offset_role:
        payload["offset_role"] = (
            "refund" if record_type == "refund" and Decimal(amount) > 0 else "expense"
        )
    return FactView(
        id=fact_id,
        amount=Decimal(amount),
        currency="CNY",
        account_id="wechat-wallet",
        account_name="微信钱包",
        occurred_at=occurred_at,
        counterparty=counterparty,
        record_type=record_type,
        record_subtype="not_applicable",
        bill_source="wechat",
        source="wechat",
        record_id=record_id or fact_id,
        raw_payload=payload,
        relation_metadata=relation_metadata,
    )


def _row(fact: FactView) -> dict:
    return {
        "id": fact.id,
        "record_id": fact.record_id,
        "amount": fact.amount,
        "occurred_at": fact.occurred_at,
        "counterparty": fact.counterparty,
        "record_type": fact.record_type,
        "record_subtype": fact.record_subtype,
        "account_id": fact.account_id,
        "account_name": fact.account_name,
        "bill_source": fact.bill_source,
        "source_type": fact.bill_source,
        "source_payload": fact.raw_payload,
    }


def _proposal(kind: str, primary: str, secondary: str, *, rule_id: str = "test.rule", refund_amount: str = "0") -> RelationProposal:
    return RelationProposal(
        kind=kind,
        primary_fact_id=primary,
        secondary_fact_id=secondary,
        status=RelationStatus.ACCEPTED.value,
        rule_id=rule_id,
        evidence=RelationEvidence(
            rule_id=rule_id,
            source_pair=("alipay", "icbc_credit"),
            extras={"refund_amount": refund_amount},
        ),
    )


def _diamond_facts() -> list[FactView]:
    return [
        _fact("expense-platform", amount="-100.00", counterparty="商户", record_type="consumption", occurred_at="2024-01-01T10:00:00+08:00"),
        _fact("expense-bank", amount="-100.00", counterparty="商户", record_type="consumption", occurred_at="2024-01-01T10:01:00+08:00"),
        _fact("refund-platform", amount="100.00", counterparty="商户", record_type="refund", occurred_at="2024-01-02T10:00:00+08:00"),
        _fact("refund-bank", amount="100.00", counterparty="商户", record_type="refund", occurred_at="2024-01-02T10:01:00+08:00"),
    ]


def test_illegal_automatic_refund_is_filtered_and_warned(monkeypatch):
    _log_illegal_automatic_proposal_once.cache_clear()
    warnings = []
    monkeypatch.setattr(
        "ft.application.relations.logger.warning",
        lambda _message, *, extra: warnings.append(extra),
    )
    facts = _diamond_facts()[:3]
    active_relations = [{
        "kind": RelationKind.REFUND_OFFSET.value,
        "status": RelationStatus.ACCEPTED.value,
        "primary_fact_id": "expense-platform",
        "secondary_fact_id": "refund-platform",
        "primary_component_id": "expense-platform",
        "secondary_component_id": "refund-platform",
        "applied_amount": Decimal("100.00"),
    }]
    illegal = _proposal(
        RelationKind.REFUND_OFFSET.value,
        "expense-platform",
        "refund-bank",
        refund_amount="100.00",
    )

    filtered = _filter_illegal_automatic_proposals(
        facts, active_relations, (illegal,), workspace_id="test-workspace",
    )

    assert filtered == ()
    assert any(
        item["reason_code"] == "projection_invalid"
        for item in warnings
    )
    assert all("refund-bank" not in str(item) for item in warnings)


def test_complete_refund_diamond_candidate_is_accepted_without_changing_matching_semantics():
    facts = _diamond_facts()
    active_relations = [
        {
            "kind": RelationKind.PAYMENT_MIRROR.value,
            "status": RelationStatus.ACCEPTED.value,
            "primary_fact_id": "expense-platform",
            "secondary_fact_id": "expense-bank",
            "primary_component_id": "expense-platform",
            "secondary_component_id": "expense-bank",
        },
        {
            "kind": RelationKind.REFUND_OFFSET.value,
            "status": RelationStatus.ACCEPTED.value,
            "primary_fact_id": "expense-platform",
            "secondary_fact_id": "refund-platform",
            "primary_component_id": "expense-platform",
            "secondary_component_id": "refund-platform",
            "applied_amount": Decimal("100.00"),
        },
        {
            "kind": RelationKind.REFUND_OFFSET.value,
            "status": RelationStatus.ACCEPTED.value,
            "primary_fact_id": "expense-bank",
            "secondary_fact_id": "refund-bank",
            "primary_component_id": "expense-bank",
            "secondary_component_id": "refund-bank",
            "applied_amount": Decimal("100.00"),
        },
    ]
    refund_mirror = _proposal(
        RelationKind.PAYMENT_MIRROR.value,
        "refund-platform",
        "refund-bank",
        rule_id="payment_mirror.refund_dual_source.v1",
    )

    filtered = _filter_illegal_automatic_proposals(
        facts, active_relations, (refund_mirror,), workspace_id="test-workspace",
    )

    assert filtered == (refund_mirror,)


def test_wechat_hard_refund_occupies_pair_before_same_amount_merchant_matching():
    origin = _fact(
        "wechat-origin",
        amount="-9.90",
        counterparty="瑞幸",
        record_type="consumption",
        occurred_at="2023-10-09T13:51:23+08:00",
        merchant_order_id="M-100",
        txn_id="T-100",
    )
    refund = _fact(
        "wechat-refund",
        amount="9.90",
        counterparty="瑞幸",
        record_type="refund",
        occurred_at="2023-10-09T13:51:43+08:00",
        merchant_order_id="M-100",
        txn_id="M-100",
    )
    naixue = _fact(
        "naixue",
        amount="-9.90",
        counterparty="奈雪",
        record_type="consumption",
        occurred_at="2023-10-08T18:40:36+08:00",
        merchant_order_id="N-1",
        txn_id="N-1",
    )
    duodianbao = _fact(
        "duodianbao",
        amount="-9.90",
        counterparty="多店宝网络",
        record_type="consumption",
        occurred_at="2023-10-09T20:15:11+08:00",
        merchant_order_id="D-1",
        txn_id="D-1",
    )
    facts = [origin, refund, naixue, duodianbao]

    plan = plan_relation_proposals(
        facts,
        detailed_rows=[_row(fact) for fact in facts],
        seed_ids=[fact.id for fact in facts],
    )

    refund_pairs = [
        proposal
        for proposal in plan.proposals
        if proposal.kind == RelationKind.REFUND_OFFSET.value
    ]
    assert len(refund_pairs) == 1
    assert (refund_pairs[0].primary_fact_id, refund_pairs[0].secondary_fact_id) == (
        origin.id,
        refund.id,
    )
    assert refund_pairs[0].status == RelationStatus.ACCEPTED.value
    assert refund_pairs[0].rule_id.startswith("scan.wechat")


def test_alipay_hard_refund_is_filtered_when_mirror_event_already_refunded(monkeypatch):
    """An illegal reverse-import refund is warned and never exposed as pending."""
    _log_illegal_automatic_proposal_once.cache_clear()
    warnings = []
    monkeypatch.setattr(
        "ft.application.relations.logger.warning",
        lambda _message, *, extra: warnings.append(extra),
    )
    account = "shared-cash"
    alipay_origin_id = "2024041822001112651418895633"
    facts = [
        FactView(
            id="ccb-expense",
            amount=Decimal("-10.00"),
            currency="CNY",
            account_id=account,
            account_name="建设银行系统账户",
            occurred_at="2024-04-18T10:00:00+08:00",
            counterparty="高德地图",
            note="消费",
            record_type="consumption",
            bill_source="ccb_debit",
            source="ccb_debit",
            record_id="ccb-expense",
        ),
        FactView(
            id="ccb-refund",
            amount=Decimal("10.00"),
            currency="CNY",
            account_id=account,
            account_name="建设银行系统账户",
            occurred_at="2024-04-20T10:00:00+08:00",
            counterparty="高德地图",
            note="消费退货",
            record_type="refund",
            bill_source="ccb_debit",
            source="ccb_debit",
            record_id="ccb-refund",
            raw_payload={"summary": "退货", "refund_signal": "ccb_return"},
        ),
        FactView(
            id="alipay-expense",
            amount=Decimal("-10.00"),
            currency="CNY",
            account_id=account,
            account_name="建设银行系统账户",
            occurred_at="2024-04-18T09:00:00+08:00",
            counterparty="高德地图",
            note="消费",
            record_type="consumption",
            bill_source="alipay",
            source="alipay",
            record_id=alipay_origin_id,
            raw_payload={"status": "交易成功", "txn_type": "消费", "txn_id": alipay_origin_id},
        ),
        FactView(
            id="alipay-refund",
            amount=Decimal("10.00"),
            currency="CNY",
            account_id=account,
            account_name="建设银行系统账户",
            occurred_at="2024-04-21T10:00:00+08:00",
            counterparty="高德地图",
            note="退款",
            record_type="refund",
            bill_source="alipay",
            source="alipay",
            record_id=f"{alipay_origin_id}_036648971000000542204235",
            raw_payload={
                "status": "退款成功",
                "txn_type": "退款",
                "txn_id": f"{alipay_origin_id}_036648971000000542204235",
            },
        ),
    ]
    accepted_relations = [{
        "kind": RelationKind.REFUND_OFFSET.value,
        "status": RelationStatus.ACCEPTED.value,
        "primary_fact_id": "ccb-expense",
        "secondary_fact_id": "ccb-refund",
    }]

    plan = plan_relation_proposals(
        facts,
        detailed_rows=[_row(fact) for fact in facts],
        seed_ids=[fact.id for fact in facts],
        accepted_relations=accepted_relations,
    )

    assert not any(
        proposal.kind == RelationKind.REFUND_OFFSET.value
        and proposal.primary_fact_id == "alipay-expense"
        and proposal.secondary_fact_id == "alipay-refund"
        for proposal in plan.proposals
    )
    assert any(
        item["reason_code"] == "projection_invalid"
        for item in warnings
    )
    assert any(
        proposal.kind == RelationKind.PAYMENT_MIRROR.value
        and proposal.primary_fact_id == "alipay-expense"
        and proposal.secondary_fact_id == "ccb-expense"
        and proposal.status == RelationStatus.ACCEPTED.value
        for proposal in plan.proposals
    )


def test_wechat_hard_refund_reads_persisted_relation_metadata_after_source_snapshot_round_trip():
    origin = _fact(
        "wechat-origin-metadata",
        amount="-9.90",
        counterparty="瑞幸",
        record_type="refund",
        occurred_at="2023-10-09T05:51:23+00:00",
        merchant_order_id="M-200",
        txn_id="T-200",
        relation_metadata={"offset_role": "expense", "offset_group": "M-200"},
        include_offset_role=False,
    )
    refund = _fact(
        "wechat-refund-metadata",
        amount="9.90",
        counterparty="瑞幸",
        record_type="refund",
        occurred_at="2023-10-09T05:51:43+00:00",
        merchant_order_id="M-200",
        txn_id="M-200",
        relation_metadata={"offset_role": "refund", "offset_group": "M-200"},
        include_offset_role=False,
    )
    decoy = _fact(
        "same-amount-decoy",
        amount="-9.90",
        counterparty="奈雪",
        record_type="consumption",
        occurred_at="2023-10-09T06:30:00+00:00",
        merchant_order_id="N-200",
        txn_id="N-200",
    )
    facts = [origin, refund, decoy]

    plan = plan_relation_proposals(
        facts,
        detailed_rows=[_row(fact) | {"relation_metadata": fact.relation_metadata} for fact in facts],
        seed_ids=[fact.id for fact in facts],
    )

    refund_pairs = [
        proposal for proposal in plan.proposals
        if proposal.kind == RelationKind.REFUND_OFFSET.value
    ]
    assert len(refund_pairs) == 1
    assert (refund_pairs[0].primary_fact_id, refund_pairs[0].secondary_fact_id) == (
        origin.id,
        refund.id,
    )


def test_relation_context_digest_treats_naive_import_time_as_persisted_utc():
    naive = _fact(
        "fact-1",
        amount="-9.90",
        counterparty="商户",
        record_type="consumption",
        occurred_at="2023-06-14T13:06:11",
        record_id="row-1",
    )
    aware = _fact(
        "fact-1",
        amount="-9.90",
        counterparty="商户",
        record_type="consumption",
        occurred_at="2023-06-14T13:06:11+00:00",
        record_id="row-1",
    )

    assert _relation_context_digest([naive], (), ()) == _relation_context_digest([aware], (), ())


def test_relation_plan_uses_business_row_order_when_preview_ids_become_database_ids():
    def payment(fact_id: str, record_id: str, source: str) -> FactView:
        return FactView(
            id=fact_id,
            amount=Decimal("-10.00"),
            currency="CNY",
            account_id="shared-card",
            account_name="共享卡",
            occurred_at="2023-06-14T13:06:11+00:00",
            counterparty="商户",
            counterparty_account="",
            payment_method="尾号1234",
            note="消费 尾号1234",
            record_type="consumption",
            record_subtype="not_applicable",
            bill_source=source,
            source=source,
            record_id=record_id,
            raw_payload={"date": "2023-06-14 13:06:11"},
        )

    preview_facts = [
        payment("preview:p1", "p1", "alipay"),
        payment("preview:p2", "p2", "alipay"),
        payment("preview:b1", "b1", "ccb_debit"),
        payment("preview:b2", "b2", "ccb_debit"),
    ]
    actual_facts = [
        payment("22", "p1", "alipay"),
        payment("21", "p2", "alipay"),
        payment("12", "b1", "ccb_debit"),
        payment("11", "b2", "ccb_debit"),
    ]

    def plan(facts):
        return plan_relation_proposals(
            facts,
            detailed_rows=[_row(fact) for fact in facts],
            seed_ids=[fact.id for fact in facts],
        )

    preview = plan(preview_facts)
    actual = plan(actual_facts)

    assert preview.context_digest == actual.context_digest
    def stable_pairs(plan):
        by_id = {str(fact.id): fact for fact in plan.facts}
        return [
            (
                item.kind,
                by_id[str(item.primary_fact_id)].record_id,
                by_id[str(item.secondary_fact_id)].record_id,
            )
            for item in plan.proposals
        ]

    assert stable_pairs(preview) == stable_pairs(actual)


def test_component_fact_references_are_equivalent_between_preview_and_persistence():
    preview_parent = {
        "id": "preview:composite-1",
        "record_id": "composite-1",
        "source_type": "alipay",
        "bill_source": "alipay",
        "amount": "-30.00",
        "currency": "CNY",
        "cash_granularity": "aggregate",
        "occurred_at": "2026-08-12T17:24:00+08:00",
        "record_type": "consumption",
        "components": [
            {"id": None, "ordinal": 0, "amount": "-10.10", "account_name": "支付宝余额"},
            {"id": None, "ordinal": 1, "amount": "-19.90", "account_name": "工商银行储蓄卡"},
        ],
    }
    persisted_parent = {**preview_parent, "id": 501}
    persisted_parent["components"] = [
        {"id": 701, "ordinal": 0, "amount": "-10.10", "account_name": "支付宝余额"},
        {"id": 702, "ordinal": 1, "amount": "-19.90", "account_name": "工商银行储蓄卡"},
    ]

    preview_rows = RelationService._expand_component_rows([preview_parent])
    for ordinal, row in enumerate(preview_rows):
        row["id"] = f"preview:composite-1:{ordinal}"
    persisted_rows = RelationService._expand_component_rows([persisted_parent])
    preview_facts = [_fact_view_from_row(row) for row in preview_rows]
    persisted_facts = [_fact_view_from_row(row) for row in persisted_rows]

    assert [
        _stable_fact_ref(str(fact.id), {str(item.id): item for item in preview_facts})
        for fact in preview_facts
    ] == ["alipay:composite-1#component:0", "alipay:composite-1#component:1"]
    assert [
        _stable_fact_ref(str(fact.id), {str(item.id): item for item in persisted_facts})
        for fact in persisted_facts
    ] == ["alipay:composite-1#component:0", "alipay:composite-1#component:1"]
    assert len(RelationService._facts_by_cached_reference(persisted_facts)) == 4


def test_cached_relation_plan_normalizes_database_fact_ids_to_strings(monkeypatch):
    facts = (
        _fact(
            11,
            amount="-10.00",
            counterparty="商户",
            record_type="consumption",
            occurred_at="2026-08-12T09:24:00+08:00",
            record_id="cash-11",
        ),
        _fact(
            12,
            amount="-10.00",
            counterparty="商户",
            record_type="consumption",
            occurred_at="2026-08-12T09:24:05+08:00",
            record_id="cash-12",
        ),
    )
    proposal = RelationProposal(
        kind=RelationKind.PAYMENT_MIRROR.value,
        primary_fact_id="11",
        secondary_fact_id="12",
        status=RelationStatus.ACCEPTED.value,
        rule_id="test.cached",
        evidence=RelationEvidence(
            source_pair=("alipay", "ccb_debit"),
            rule_id="test.cached",
        ),
    )
    plan = RelationPlan(
        facts=facts,
        proposals=(proposal,),
        context_digest="context",
        external_context_digest="external",
    )
    cached = serialize_import_relation_plan(plan)
    service = RelationService(None)
    monkeypatch.setattr(service, "_list_active_cash_facts", lambda _uow: facts)

    resolved_facts, resolved_proposals = service._cached_proposals_in_uow(None, cached)

    assert {type(fact.id) for fact in resolved_facts} == {str}
    assert resolved_proposals[0].primary_fact_id == "11"
    assert resolved_proposals[0].secondary_fact_id == "12"


def test_proposal_key_does_not_bypass_cached_primary_or_candidate_validation():
    primary = _fact(
        "expense", amount="-10.00", counterparty="咖啡店",
        record_type="consumption", occurred_at="2026-08-12T09:24:00+08:00",
    )
    candidate = _fact(
        "candidate", amount="10.00", counterparty="咖啡店",
        record_type="refund", occurred_at="2026-08-13T09:24:00+08:00",
    )
    outside_candidate = _fact(
        "outside", amount="10.00", counterparty="其他商户",
        record_type="refund", occurred_at="2026-08-13T09:24:00+08:00",
    )
    facts = [primary, candidate, outside_candidate]
    proposal = RelationProposal(
        kind=RelationKind.REFUND_OFFSET.value,
        primary_fact_id=primary.id,
        secondary_fact_id=None,
        evidence=RelationEvidence(candidate_fact_ids=(candidate.id,)),
    )
    decision = {
        "proposal_key": relation_proposal_key(proposal, facts),
        "primary_record_id": outside_candidate.record_id,
        "secondary_record_id": outside_candidate.record_id,
        "status": "accepted",
    }

    assert not RelationService._decision_primary_matches(proposal, decision, facts)
    assert not RelationService._decision_matches(proposal, decision, facts)


def test_existing_and_new_refund_pairs_align_their_payment_mirrors():
    def fact(
        fact_id: str,
        amount: str,
        source: str,
        occurred_at: str,
        *,
        record_type: str,
        record_id: str,
        payload: dict | None = None,
    ) -> FactView:
        return FactView(
            id=fact_id,
            amount=Decimal(amount),
            currency="CNY",
            account_id="shared-cash",
            account_name="共享账户",
            occurred_at=occurred_at,
            counterparty="商家A",
            note="消费退货" if record_type == "refund" and source == "icbc_debit" else (
                "退款" if record_type == "refund" else "消费"
            ),
            record_type=record_type,
            record_subtype="not_applicable",
            bill_source=source,
            source=source,
            record_id=record_id,
            raw_payload=payload or {},
        )

    facts = [
        fact(
            "bank-expense-1", "-10.00", "icbc_debit", "2024-04-18 10:00:00",
            record_type="consumption", record_id="bank-expense-1",
        ),
        fact(
            "bank-refund-1", "10.00", "icbc_debit", "2024-04-20 11:00:00",
            record_type="refund", record_id="bank-refund-1",
            payload={"summary": "退货", "refund_signal": "icbc_debit_return"},
        ),
        fact(
            "bank-expense-2", "-10.00", "icbc_debit", "2024-04-18 10:01:00",
            record_type="consumption", record_id="bank-expense-2",
        ),
        fact(
            "platform-expense-1", "-10.00", "alipay", "2024-04-18 09:00:00",
            record_type="consumption", record_id="platform-expense-1",
            payload={"status": "交易成功", "txn_type": "消费", "txn_id": "platform-expense-1"},
        ),
        fact(
            "platform-expense-2", "-10.00", "alipay", "2024-04-18 10:02:00",
            record_type="consumption", record_id="platform-expense-2",
            payload={"status": "交易成功", "txn_type": "消费", "txn_id": "platform-expense-2"},
        ),
        fact(
            "platform-refund-2", "10.00", "alipay", "2024-04-20 10:00:00",
            record_type="refund", record_id="platform-refund-2",
            payload={
                "status": "退款成功",
                "txn_type": "退款",
                "txn_id": "platform-expense-2_refund",
            },
        ),
    ]
    rows = [_row(item) | {"txn_id": (item.raw_payload or {}).get("txn_id", "")} for item in facts]

    plan = plan_relation_proposals(
        facts,
        detailed_rows=rows,
        seed_ids=[item.id for item in facts],
        accepted_relations=[{
            "kind": RelationKind.REFUND_OFFSET.value,
            "status": RelationStatus.ACCEPTED.value,
            "primary_fact_id": "bank-expense-1",
            "secondary_fact_id": "bank-refund-1",
        }],
    )

    mirrors = {
        (item.primary_fact_id, item.secondary_fact_id)
        for item in plan.proposals
        if item.kind == RelationKind.PAYMENT_MIRROR.value
        and item.status == RelationStatus.ACCEPTED.value
    }
    assert ("platform-expense-2", "bank-expense-1") in mirrors
    assert ("platform-refund-2", "bank-refund-1") in mirrors
    assert ("platform-expense-2", "bank-expense-2") not in mirrors
