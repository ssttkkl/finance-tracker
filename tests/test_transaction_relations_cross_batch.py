"""US9 cross-batch seed tests."""
from __future__ import annotations

from decimal import Decimal

import pytest

from ft.domain.relations import (
    RelationEvidence,
    RelationKind,
    RelationProposal,
    RelationStatus,
    evaluate_payment_mirror,
)


def test_cross_batch_seed_matches_prior_facts(relation_runtime):
    services = relation_runtime.services
    # Both rows belong to the same card account; platform bill_source remains alipay.
    assert services.accounts.create_account("建行储蓄", "cash", "CNY").ok
    # batch A: bank only
    services.cashflow.add_manual_transaction(
        amount=Decimal("-88.00"), counterparty="盒马", account_name="建行储蓄",
        currency="CNY", date="2026-06-10 12:00:00", note="快捷支付 尾号9999",
        category="expense", bill_source="icbc", source="icbc",
        record_type="consumption",
    )
    with services.uow as uow:
        bank_ids = [_component_id(r) for r in uow.cashflows.list_detailed()]
    # no platform yet
    services.relations.check(seed_fact_ids=bank_ids)
    # batch B: platform view of same card payment
    services.cashflow.add_manual_transaction(
        amount=Decimal("-88.00"), counterparty="盒马", account_name="建行储蓄",
        currency="CNY", date="2026-06-10 12:00:03", note="付款方式 尾号9999",
        category="expense", bill_source="alipay", source="alipay",
        record_type="consumption",
    )
    with services.uow as uow:
        all_ids = [_component_id(r) for r in uow.cashflows.list_detailed()]
        platform_ids = [i for i in all_ids if i not in bank_ids]
    result = services.relations.check(seed_fact_ids=platform_ids, trigger="import_batch")
    assert result.ok
    with services.uow as uow:
        mirrors = uow.relations.list_active(kind="payment_mirror")
    assert mirrors, "expected cross-batch payment_mirror"


def test_human_rejected_payment_mirror_blocks_future_reconciliation(relation_runtime):
    services = relation_runtime.services
    assert services.accounts.create_account("人工拒绝账户", "cash", "CNY").ok
    common = dict(account_name="人工拒绝账户", currency="CNY")
    services.cashflow.add_manual_transaction(
        amount=Decimal("-20.00"), counterparty="商户", note="消费",
        source="wechat", bill_source="wechat", date="2026-06-10 12:00:00",
        record_id="human-reject-platform", record_type="consumption", **common,
    )
    services.cashflow.add_manual_transaction(
        amount=Decimal("-20.00"), counterparty="商户", note="消费",
        source="icbc_debit", bill_source="icbc_debit", date="2026-06-10 12:00:03",
        record_id="human-reject-bank", record_type="consumption", **common,
    )
    ids = _ids_by_record(services)
    with services.uow as uow:
        fact_views = services.relations._list_active_cash_facts(uow)
        proposal = evaluate_payment_mirror(
            next(fact for fact in fact_views if str(fact.id) == str(ids["human-reject-platform"])),
            [next(fact for fact in fact_views if str(fact.id) == str(ids["human-reject-bank"]))],
        )
        assert proposal is not None
        auto_id = uow.relations.add({
            "kind": "payment_mirror", "status": "accepted",
            "primary_fact_id": proposal.primary_fact_id,
            "secondary_fact_id": proposal.secondary_fact_id,
            "primary_component_id": proposal.primary_fact_id,
            "secondary_component_id": proposal.secondary_fact_id,
            "anchor_fact_id": proposal.primary_fact_id,
            "anchor_component_id": proposal.primary_fact_id,
            "rule_id": proposal.rule_id, "created_by": "system",
        })
        rejected = services.relations._persist_rejected_proposal(uow, proposal)
        uow.commit()

    assert rejected["status"] == "rejected"
    assert rejected["decided_by"] == "web"
    with services.uow as uow:
        assert uow.relations.get(auto_id)["status"] == "rejected"
    assert services.relations.check(
        seed_fact_ids=[ids["human-reject-platform"], ids["human-reject-bank"]],
        trigger="import_batch",
    ).ok
    with services.uow as uow:
        assert not uow.relations.list_active(kind="payment_mirror", status="accepted")


def test_human_payment_mirror_supersedes_competing_system_edge(relation_runtime):
    services = relation_runtime.services
    assert services.accounts.create_account("人工选择账户", "cash", "CNY").ok
    common = dict(account_name="人工选择账户", currency="CNY")
    for record_id, source, timestamp in (
        ("human-select-platform", "wechat", "2026-06-10 12:00:00"),
        ("human-select-bank-old", "icbc_debit", "2026-06-10 12:00:03"),
        ("human-select-bank-new", "icbc_debit", "2026-06-10 12:00:04"),
    ):
        services.cashflow.add_manual_transaction(
            amount=Decimal("-20.00"), counterparty="商户", note="消费",
            source=source, bill_source=source, date=timestamp,
            record_id=record_id, record_type="consumption", **common,
        )
    ids = _ids_by_record(services)
    with services.uow as uow:
        old_id = uow.relations.add({
            "kind": "payment_mirror", "status": "accepted",
            "primary_fact_id": ids["human-select-platform"],
            "secondary_fact_id": ids["human-select-bank-old"],
            "primary_component_id": ids["human-select-platform"],
            "secondary_component_id": ids["human-select-bank-old"],
            "anchor_fact_id": ids["human-select-platform"],
            "anchor_component_id": ids["human-select-platform"],
            "rule_id": "test.system", "created_by": "system",
        })
        pending_id = uow.relations.add({
            "kind": "payment_mirror", "status": "pending_review",
            "primary_fact_id": ids["human-select-platform"],
            "secondary_fact_id": ids["human-select-bank-new"],
            "primary_component_id": ids["human-select-platform"],
            "secondary_component_id": ids["human-select-bank-new"],
            "anchor_fact_id": ids["human-select-platform"],
            "anchor_component_id": ids["human-select-platform"],
            "rule_id": "test.pending", "created_by": "system",
        })
        uow.commit()

    assert services.relations.accept(str(pending_id), actor="web").ok
    with services.uow as uow:
        assert uow.relations.get(old_id)["status"] == "superseded"
        selected = uow.relations.get(pending_id)
        assert selected["status"] == "accepted"
        assert selected["decided_by"] == "web"


def test_cross_batch_refund_does_not_cross_match_same_amount_different_merchant(relation_runtime):
    services = relation_runtime.services
    assert services.accounts.create_account("重叠账户", "cash", "CNY").ok
    common = dict(account_name="重叠账户", currency="CNY")
    services.cashflow.add_manual_transaction(
        amount=Decimal("-10.00"), counterparty="高德", note="消费",
        source="alipay", bill_source="alipay", date="2024-04-18 10:00:00",
        record_id="alipay-gaode-expense", record_type="consumption", **common,
    )
    services.cashflow.add_manual_transaction(
        amount=Decimal("-10.00"), counterparty="北京市玉渊潭公园管理处", note="消费",
        source="wechat", bill_source="wechat", date="2024-04-20 10:00:00",
        record_id="wechat-yuyuantan-expense", record_type="consumption", **common,
    )
    services.cashflow.add_manual_transaction(
        amount=Decimal("-10.00"), counterparty="高德", note="消费",
        source="ccb_debit", bill_source="ccb_debit", date="2024-04-17 10:00:00",
        record_id="ccb-gaode-expense", record_type="consumption", **common,
    )
    with services.uow as uow:
        facts = uow.cashflows.list_detailed()
        by_record = {
            row["record_id"]: _component_id(row)
            for row in facts
        }
    with services.uow as uow:
        uow.relations.add({
            "kind": "payment_mirror", "status": "accepted",
            "primary_fact_id": by_record["alipay-gaode-expense"],
            "secondary_fact_id": by_record["ccb-gaode-expense"],
            "primary_component_id": by_record["alipay-gaode-expense"],
            "secondary_component_id": by_record["ccb-gaode-expense"],
            "anchor_fact_id": by_record["alipay-gaode-expense"],
            "anchor_component_id": by_record["alipay-gaode-expense"],
            "rule_id": "test.accepted-overlap", "created_by": "test",
        })
        uow.commit()
    services.cashflow.add_manual_transaction(
        amount=Decimal("10.00"), counterparty="高德", note="消费退货",
        source="ccb_debit", bill_source="ccb_debit", date="2024-04-20 11:00:00",
        record_id="ccb-gaode-refund", record_type="refund", **common,
    )
    with services.uow as uow:
        refund_id = next(
            _component_id(row)
            for row in uow.cashflows.list_detailed()
            if row["record_id"] == "ccb-gaode-refund"
        )
    result = services.relations.check(seed_fact_ids=[refund_id])
    assert result.ok
    with services.uow as uow:
        refunds = uow.relations.list_active(kind="refund_offset", status="accepted")
        facts = {
            int(component["id"]): row
            for row in uow.cashflows.list_detailed()
            for component in row.get("components") or ()
        }
    matching = [row for row in refunds if int(row["secondary_fact_id"] or 0) == int(refund_id)]
    assert matching
    assert all(facts[int(row["primary_fact_id"])]["counterparty"] == "高德" for row in matching)


def _ids_by_record(services):
    with services.uow as uow:
        return {
            row["record_id"]: _component_id(row)
            for row in uow.cashflows.list_detailed()
        }


def _component_id(row):
    components = row.get("components") or ()
    assert len(components) == 1, row
    return int(components[0]["id"])


def _accepted_refund_rows(services):
    with services.uow as uow:
        return uow.relations.list_active(kind="refund_offset", status="accepted")


def test_reverse_order_bank_refund_remains_on_its_merchant_after_platform_mirror_arrives(
    relation_runtime,
):
    services = relation_runtime.services
    assert services.accounts.create_account("逆序账户", "cash", "CNY").ok
    common = dict(account_name="逆序账户", currency="CNY")
    for row in (
        dict(
            amount=Decimal("-10.00"), counterparty="商家A", note="消费",
            source="ccb_debit", bill_source="ccb_debit", date="2024-04-17 10:00:00",
            record_id="reverse-bank-expense", record_type="consumption",
        ),
        dict(
            amount=Decimal("-10.00"), counterparty="商家B", note="消费",
            source="wechat", bill_source="wechat", date="2024-04-20 10:00:00",
            record_id="reverse-unrelated-expense", record_type="consumption",
        ),
        dict(
            amount=Decimal("10.00"), counterparty="商家A", note="消费退货",
            source="ccb_debit", bill_source="ccb_debit", date="2024-04-20 11:00:00",
            record_id="reverse-bank-refund", record_type="refund",
        ),
    ):
        services.cashflow.add_manual_transaction(**row, **common)
    ids = _ids_by_record(services)
    assert services.relations.check(
        seed_fact_ids=[ids["reverse-bank-refund"]], trigger="import_batch",
    ).ok

    services.cashflow.add_manual_transaction(
        amount=Decimal("-10.00"), counterparty="商家A", note="平台消费",
        source="alipay", bill_source="alipay", date="2024-04-18 10:00:00",
        record_id="reverse-platform-expense", record_type="consumption", **common,
    )
    ids = _ids_by_record(services)
    assert services.relations.check(
        seed_fact_ids=[ids["reverse-platform-expense"]], trigger="import_batch",
    ).ok

    with services.uow as uow:
        facts = {
            int(component["id"]): row
            for row in uow.cashflows.list_detailed()
            for component in row.get("components") or ()
        }
    matching = [
        row for row in _accepted_refund_rows(services)
        if int(row["secondary_fact_id"] or 0) == int(ids["reverse-bank-refund"])
    ]
    assert len(matching) == 1
    assert facts[int(matching[0]["primary_fact_id"])]["counterparty"] == "商家A"


def test_reverse_order_platform_refund_supersedes_prior_system_bank_refund(
    relation_runtime,
):
    services = relation_runtime.services
    assert services.accounts.create_account("逆序退款账户", "cash", "CNY").ok
    common = dict(account_name="逆序退款账户", currency="CNY")
    services.cashflow.add_manual_transaction(
        amount=Decimal("-10.00"), counterparty="商家A", note="消费",
        source="ccb_debit", bill_source="ccb_debit", date="2024-04-17 10:00:00",
        record_id="reverse-system-bank-expense", record_type="consumption", **common,
    )
    services.cashflow.add_manual_transaction(
        amount=Decimal("10.00"), counterparty="商家A", note="消费退货",
        source="ccb_debit", bill_source="ccb_debit", date="2024-04-20 11:00:00",
        record_id="reverse-system-bank-refund", record_type="refund", **common,
    )
    ids = _ids_by_record(services)
    assert services.relations.check(
        seed_fact_ids=[ids["reverse-system-bank-refund"]], trigger="import_batch",
    ).ok
    with services.uow as uow:
        old_bank_refund = next(
            row for row in uow.relations.list_active(
                kind="refund_offset", status="accepted",
            )
            if int(row["secondary_fact_id"] or 0) == int(ids["reverse-system-bank-refund"])
        )

    services.cashflow.add_manual_transaction(
        amount=Decimal("-10.00"), counterparty="商家A", note="平台消费",
        source="alipay", bill_source="alipay", date="2024-04-17 10:00:03",
        record_id="reverse-platform-order", record_type="consumption", **common,
    )
    services.cashflow.add_manual_transaction(
        amount=Decimal("10.00"), counterparty="商家A", note="退款",
        source="alipay", bill_source="alipay", date="2024-04-20 11:00:03",
        record_id="reverse-platform-order_refund", record_type="refund", **common,
    )
    ids = _ids_by_record(services)
    assert services.relations.check(
        seed_fact_ids=[ids["reverse-platform-order"], ids["reverse-platform-order_refund"]],
        trigger="import_batch",
    ).ok

    with services.uow as uow:
        active_refunds = uow.relations.list_active(
            kind="refund_offset", status="accepted",
        )
        old_bank_refund = uow.relations.get(old_bank_refund["id"])

    assert not [
        row for row in active_refunds
        if int(row["secondary_fact_id"] or 0) == int(ids["reverse-system-bank-refund"])
    ]
    assert [
        row for row in active_refunds
        if int(row["secondary_fact_id"] or 0) == int(ids["reverse-platform-order_refund"])
    ]
    assert old_bank_refund["status"] == "superseded"
    replacement = next(
        row for row in active_refunds
        if int(row["secondary_fact_id"] or 0) == int(ids["reverse-platform-order_refund"])
    )
    assert int(old_bank_refund["superseded_by_id"]) == int(replacement["id"])
    assert int(old_bank_refund["secondary_fact_id"] or 0) == int(ids["reverse-system-bank-refund"])


def test_platform_refund_replacement_retires_bank_edge_before_persisting(relation_runtime):
    services = relation_runtime.services
    assert services.accounts.create_account("退款替换容量账户", "cash", "CNY").ok
    common = dict(account_name="退款替换容量账户", currency="CNY")
    services.cashflow.add_manual_transaction(
        amount=Decimal("-10.00"), counterparty="商家", note="银行消费",
        source="ccb_debit", bill_source="ccb_debit", date="2024-04-17 10:00:00",
        record_id="capacity-bank-expense", record_type="consumption", **common,
    )
    services.cashflow.add_manual_transaction(
        amount=Decimal("10.00"), counterparty="商家", note="银行退款",
        source="ccb_debit", bill_source="ccb_debit", date="2024-04-20 11:00:00",
        record_id="capacity-bank-refund", record_type="refund", **common,
    )
    services.cashflow.add_manual_transaction(
        amount=Decimal("-10.00"), counterparty="商家", note="平台消费",
        source="alipay", bill_source="alipay", date="2024-04-17 10:00:03",
        record_id="capacity-platform-order", record_type="consumption", **common,
    )
    ids = _ids_by_record(services)
    with services.uow as uow:
        old_id = uow.relations.add({
            "kind": RelationKind.REFUND_OFFSET.value,
            "status": RelationStatus.ACCEPTED.value,
            "primary_fact_id": ids["capacity-platform-order"],
            "secondary_fact_id": ids["capacity-bank-refund"],
            "primary_component_id": ids["capacity-platform-order"],
            "secondary_component_id": ids["capacity-bank-refund"],
            "anchor_fact_id": ids["capacity-platform-order"],
            "anchor_component_id": ids["capacity-platform-order"],
            "created_by": "system",
            "applied_amount": Decimal("10.00"),
        })
        uow.commit()
    services.cashflow.add_manual_transaction(
        amount=Decimal("10.00"), counterparty="商家", note="平台退款",
        source="alipay", bill_source="alipay", date="2024-04-20 11:00:03",
        record_id="capacity-platform-order_refund", record_type="refund", **common,
    )
    ids = _ids_by_record(services)

    proposal = RelationProposal(
        kind=RelationKind.REFUND_OFFSET.value,
        primary_fact_id=str(ids["capacity-platform-order"]),
        secondary_fact_id=str(ids["capacity-platform-order_refund"]),
        status=RelationStatus.ACCEPTED.value,
        rule_id="scan.test.platform_refund",
        evidence=RelationEvidence(
            rule_id="scan.test.platform_refund",
            extras={"refund_amount": "10.00"},
        ),
    )
    with services.uow as uow:
        facts = services.relations._list_active_cash_facts(uow)
        old = uow.relations.get(old_id)
        assert old["status"] == RelationStatus.ACCEPTED.value
        accepted = [dict(old)]
        superseded = services.relations._supersede_redundant_bank_refunds(
            uow, facts, [proposal], accepted,
        )
        replacement = next(
            item for item in uow.relations.list_active(
                kind=RelationKind.REFUND_OFFSET.value,
                status=RelationStatus.ACCEPTED.value,
            )
            if str(item["secondary_fact_id"]) == str(ids["capacity-platform-order_refund"])
        )
        old_after = uow.relations.get(old["id"])
        uow.commit()

    assert superseded == {
        (str(ids["capacity-platform-order"]), str(ids["capacity-bank-refund"])),
    }
    assert old_after["status"] == RelationStatus.SUPERSEDED.value
    assert str(old_after["superseded_by_id"]) == str(replacement["id"])


def test_platform_refund_does_not_replace_multiple_equally_matching_bank_refunds(
    relation_runtime,
):
    services = relation_runtime.services
    assert services.accounts.create_account("歧义退款账户", "cash", "CNY").ok
    common = dict(account_name="歧义退款账户", currency="CNY")
    services.cashflow.add_manual_transaction(
        amount=Decimal("-30.00"), counterparty="商家", note="平台消费",
        source="alipay", bill_source="alipay", date="2024-09-10 10:00:00",
        record_id="ambiguous-platform-expense", record_type="consumption", **common,
    )
    for record_id, date in (
        ("ambiguous-bank-refund-one", "2024-09-12 11:00:00"),
        ("ambiguous-bank-refund-two", "2024-09-13 11:00:00"),
    ):
        services.cashflow.add_manual_transaction(
            amount=Decimal("10.00"), counterparty="商家", note="银行退款",
            source="ccb_debit", bill_source="ccb_debit", date=date,
            record_id=record_id, record_type="refund", **common,
        )
    services.cashflow.add_manual_transaction(
        amount=Decimal("10.00"), counterparty="商家", note="平台退款",
        source="alipay", bill_source="alipay", date="2024-09-12 11:00:03",
        record_id="ambiguous-platform-refund", record_type="refund", **common,
    )
    ids = _ids_by_record(services)
    with services.uow as uow:
        old_ids = []
        for bank_refund_key in (
            "ambiguous-bank-refund-one",
            "ambiguous-bank-refund-two",
        ):
            old_ids.append(uow.relations.add({
                "kind": RelationKind.REFUND_OFFSET.value,
                "status": RelationStatus.ACCEPTED.value,
                "primary_fact_id": ids["ambiguous-platform-expense"],
                "secondary_fact_id": ids[bank_refund_key],
                "primary_component_id": ids["ambiguous-platform-expense"],
                "secondary_component_id": ids[bank_refund_key],
                "anchor_fact_id": ids["ambiguous-platform-expense"],
                "anchor_component_id": ids["ambiguous-platform-expense"],
                "created_by": "system",
                "applied_amount": Decimal("10.00"),
            }))
        uow.commit()

    proposal = RelationProposal(
        kind=RelationKind.REFUND_OFFSET.value,
        primary_fact_id=str(ids["ambiguous-platform-expense"]),
        secondary_fact_id=str(ids["ambiguous-platform-refund"]),
        status=RelationStatus.ACCEPTED.value,
        rule_id="scan.test.platform_refund",
        evidence=RelationEvidence(
            rule_id="scan.test.platform_refund",
            extras={"refund_amount": "10.00"},
        ),
    )
    with services.uow as uow:
        facts = services.relations._list_active_cash_facts(uow)
        accepted = [
            dict(item) for item in uow.relations.list_active(
                kind=RelationKind.REFUND_OFFSET.value,
                status=RelationStatus.ACCEPTED.value,
            )
        ]
        superseded = services.relations._supersede_redundant_bank_refunds(
            uow, facts, [proposal], accepted,
        )
        old_after = [uow.relations.get(item_id) for item_id in old_ids]
        uow.commit()

    assert superseded == set()
    assert [item["status"] for item in old_after] == [
        RelationStatus.ACCEPTED.value,
        RelationStatus.ACCEPTED.value,
    ]


def test_cached_refund_replacement_requires_a_cached_platform_pair(relation_runtime):
    services = relation_runtime.services
    assert services.accounts.create_account("缓存退款边界账户", "cash", "CNY").ok
    common = dict(account_name="缓存退款边界账户", currency="CNY")
    for row in (
        dict(
            amount=Decimal("-20.00"), counterparty="商家", note="平台消费",
            source="alipay", bill_source="alipay", date="2024-09-20 10:00:00",
            record_id="cached-boundary-expense", record_type="consumption",
        ),
        dict(
            amount=Decimal("10.00"), counterparty="商家", note="银行退款",
            source="ccb_debit", bill_source="ccb_debit", date="2024-09-21 11:00:00",
            record_id="cached-boundary-bank-refund", record_type="refund",
        ),
        dict(
            amount=Decimal("10.00"), counterparty="商家", note="平台退款",
            source="alipay", bill_source="alipay", date="2024-09-21 11:00:03",
            record_id="cached-boundary-platform-refund", record_type="refund",
        ),
    ):
        services.cashflow.add_manual_transaction(**row, **common)
    ids = _ids_by_record(services)
    with services.uow as uow:
        bank_relation_id = uow.relations.add({
            "kind": RelationKind.REFUND_OFFSET.value,
            "status": RelationStatus.ACCEPTED.value,
            "primary_fact_id": ids["cached-boundary-expense"],
            "secondary_fact_id": ids["cached-boundary-bank-refund"],
            "primary_component_id": ids["cached-boundary-expense"],
            "secondary_component_id": ids["cached-boundary-bank-refund"],
            "anchor_fact_id": ids["cached-boundary-expense"],
            "anchor_component_id": ids["cached-boundary-expense"],
            "created_by": "system",
            "applied_amount": Decimal("10.00"),
        })
        platform_relation_id = uow.relations.add({
            "kind": RelationKind.REFUND_OFFSET.value,
            "status": RelationStatus.ACCEPTED.value,
            "primary_fact_id": ids["cached-boundary-expense"],
            "secondary_fact_id": ids["cached-boundary-platform-refund"],
            "primary_component_id": ids["cached-boundary-expense"],
            "secondary_component_id": ids["cached-boundary-platform-refund"],
            "anchor_fact_id": ids["cached-boundary-expense"],
            "anchor_component_id": ids["cached-boundary-expense"],
            "created_by": "system",
            "applied_amount": Decimal("10.00"),
        })
        facts = services.relations._list_active_cash_facts(uow)
        accepted = [
            dict(item) for item in uow.relations.list_active(
                kind=RelationKind.REFUND_OFFSET.value,
                status=RelationStatus.ACCEPTED.value,
            )
        ]
        superseded = services.relations._supersede_redundant_bank_refunds(
            uow, facts, [], accepted, proposed_pairs_only=True,
        )
        statuses = [
            uow.relations.get(item_id)["status"]
            for item_id in (bank_relation_id, platform_relation_id)
        ]
        uow.commit()

    assert superseded == set()
    assert statuses == [
        RelationStatus.ACCEPTED.value,
        RelationStatus.ACCEPTED.value,
    ]


def test_pending_platform_refund_does_not_replace_automatic_bank_refund(
    relation_runtime,
):
    services = relation_runtime.services
    assert services.accounts.create_account("待审核退款保护账户", "cash", "CNY").ok
    common = dict(account_name="待审核退款保护账户", currency="CNY")
    services.cashflow.add_manual_transaction(
        amount=Decimal("-20.00"), counterparty="商家", note="平台消费",
        source="alipay", bill_source="alipay", date="2024-10-01 10:00:00",
        record_id="pending-platform-expense", record_type="consumption", **common,
    )
    services.cashflow.add_manual_transaction(
        amount=Decimal("10.00"), counterparty="商家", note="银行退款",
        source="ccb_debit", bill_source="ccb_debit", date="2024-10-02 11:00:00",
        record_id="pending-bank-refund", record_type="refund", **common,
    )
    services.cashflow.add_manual_transaction(
        amount=Decimal("10.00"), counterparty="商家", note="平台退款",
        source="alipay", bill_source="alipay", date="2024-10-02 11:00:03",
        record_id="pending-platform-refund", record_type="refund", **common,
    )
    ids = _ids_by_record(services)
    with services.uow as uow:
        old_id = uow.relations.add({
            "kind": RelationKind.REFUND_OFFSET.value,
            "status": RelationStatus.ACCEPTED.value,
            "primary_fact_id": ids["pending-platform-expense"],
            "secondary_fact_id": ids["pending-bank-refund"],
            "primary_component_id": ids["pending-platform-expense"],
            "secondary_component_id": ids["pending-bank-refund"],
            "anchor_fact_id": ids["pending-platform-expense"],
            "anchor_component_id": ids["pending-platform-expense"],
            "created_by": "system",
            "applied_amount": Decimal("10.00"),
        })
        uow.commit()

    proposal = RelationProposal(
        kind=RelationKind.REFUND_OFFSET.value,
        primary_fact_id=str(ids["pending-platform-expense"]),
        secondary_fact_id=str(ids["pending-platform-refund"]),
        status=RelationStatus.PENDING_REVIEW.value,
        rule_id="scan.test.platform_refund",
        evidence=RelationEvidence(
            rule_id="scan.test.platform_refund",
            extras={"refund_amount": "10.00"},
        ),
    )
    with services.uow as uow:
        facts = services.relations._list_active_cash_facts(uow)
        old = uow.relations.get(old_id)
        superseded = services.relations._supersede_redundant_bank_refunds(
            uow, facts, [proposal], [dict(old)], proposed_pairs_only=True,
        )
        old_after = uow.relations.get(old_id)
        created_platform_edges = [
            item for item in uow.relations.list_active(
                kind=RelationKind.REFUND_OFFSET.value,
                status=RelationStatus.ACCEPTED.value,
            )
            if str(item["secondary_fact_id"]) == str(ids["pending-platform-refund"])
        ]
        uow.commit()

    assert superseded == set()
    assert old_after["status"] == RelationStatus.ACCEPTED.value
    assert created_platform_edges == []


def test_later_platform_evidence_reselects_system_bank_refund(
    relation_runtime,
):
    services = relation_runtime.services
    assert services.accounts.create_account("退款证据账户", "cash", "CNY").ok
    common = dict(account_name="退款证据账户", currency="CNY")
    services.cashflow.add_manual_transaction(
        amount=Decimal("-10.00"), counterparty="商家A", note="消费",
        source="ccb_debit", bill_source="ccb_debit", date="2024-04-17 10:00:00",
        record_id="evidence-bank-expense", record_type="consumption", **common,
    )
    services.cashflow.add_manual_transaction(
        amount=Decimal("10.00"), counterparty="商家A", note="消费退货",
        source="ccb_debit", bill_source="ccb_debit", date="2024-04-20 11:00:00",
        record_id="evidence-bank-refund", record_type="refund", **common,
    )
    ids = _ids_by_record(services)
    assert services.relations.check(
        seed_fact_ids=[ids["evidence-bank-refund"]], trigger="import_batch",
    ).ok
    with services.uow as uow:
        old_relation = next(
            row for row in uow.relations.list_active(
                kind="refund_offset", status="accepted",
            )
            if int(row["secondary_fact_id"] or 0) == int(ids["evidence-bank-refund"])
        )

    services.cashflow.add_manual_transaction(
        amount=Decimal("-10.00"), counterparty="商家A", note="平台消费",
        source="alipay", bill_source="alipay", date="2024-04-20 10:59:00",
        record_id="evidence-platform-expense", record_type="consumption", **common,
    )
    ids = _ids_by_record(services)
    assert services.relations.check(
        seed_fact_ids=[ids["evidence-platform-expense"]], trigger="import_batch",
    ).ok

    with services.uow as uow:
        active = next(
            row for row in uow.relations.list_active(
                kind="refund_offset", status="accepted",
            )
            if int(row["secondary_fact_id"] or 0) == int(ids["evidence-bank-refund"])
        )
        historical = uow.relations.get(old_relation["id"])
    assert int(active["primary_fact_id"] or 0) == int(ids["evidence-platform-expense"])
    assert historical["status"] == "superseded"


def test_single_source_refund_pairing_is_unchanged(relation_runtime):
    services = relation_runtime.services
    assert services.accounts.create_account("单来源账户", "cash", "CNY").ok
    common = dict(account_name="单来源账户", currency="CNY", source="ccb_debit", bill_source="ccb_debit")
    services.cashflow.add_manual_transaction(
        amount=Decimal("-28.00"), counterparty="商家C", note="消费",
        date="2024-05-01 10:00:00", record_id="single-expense",
        record_type="consumption", **common,
    )
    services.cashflow.add_manual_transaction(
        amount=Decimal("28.00"), counterparty="商家C", note="消费退货",
        date="2024-05-02 10:00:00", record_id="single-refund",
        record_type="refund", **common,
    )
    ids = _ids_by_record(services)
    assert services.relations.check(
        seed_fact_ids=[ids["single-refund"]], trigger="import_batch",
    ).ok
    matching = [
        row for row in _accepted_refund_rows(services)
        if int(row["secondary_fact_id"] or 0) == int(ids["single-refund"])
    ]
    assert [(int(row["primary_fact_id"]), int(row["secondary_fact_id"])) for row in matching] == [
        (int(ids["single-expense"]), int(ids["single-refund"])),
    ]


def test_refund_rescan_is_idempotent_with_an_accepted_expense_mirror(relation_runtime):
    services = relation_runtime.services
    assert services.accounts.create_account("重扫账户", "cash", "CNY").ok
    common = dict(account_name="重扫账户", currency="CNY")
    for row in (
        dict(
            amount=Decimal("-16.00"), counterparty="商家D", note="平台消费",
            source="alipay", bill_source="alipay", date="2024-06-01 10:00:00",
            record_id="rescan-platform-expense", record_type="consumption",
        ),
        dict(
            amount=Decimal("-16.00"), counterparty="商家D", note="消费",
            source="ccb_debit", bill_source="ccb_debit", date="2024-05-31 10:00:00",
            record_id="rescan-bank-expense", record_type="consumption",
        ),
        dict(
            amount=Decimal("16.00"), counterparty="商家D", note="消费退货",
            source="ccb_debit", bill_source="ccb_debit", date="2024-06-02 10:00:00",
            record_id="rescan-bank-refund", record_type="refund",
        ),
    ):
        services.cashflow.add_manual_transaction(**row, **common)
    ids = _ids_by_record(services)
    with services.uow as uow:
        uow.relations.add({
            "kind": "payment_mirror", "status": "accepted",
            "primary_fact_id": ids["rescan-platform-expense"],
            "secondary_fact_id": ids["rescan-bank-expense"],
            "primary_component_id": ids["rescan-platform-expense"],
            "secondary_component_id": ids["rescan-bank-expense"],
            "anchor_fact_id": ids["rescan-platform-expense"],
            "anchor_component_id": ids["rescan-platform-expense"],
            "rule_id": "test.accepted-overlap", "created_by": "test",
        })
        uow.commit()

    for _ in range(2):
        assert services.relations.check(
            seed_fact_ids=[ids["rescan-bank-refund"]], trigger="import_batch",
        ).ok
    matching = [
        row for row in _accepted_refund_rows(services)
        if int(row["secondary_fact_id"] or 0) == int(ids["rescan-bank-refund"])
    ]
    assert len(matching) == 1


def test_accepted_refund_mirror_does_not_create_a_second_refund_offset(relation_runtime):
    services = relation_runtime.services
    assert services.accounts.create_account("退款镜像账户", "cash", "CNY").ok
    common = dict(account_name="退款镜像账户", currency="CNY")
    for row in (
        dict(
            amount=Decimal("-20.00"), counterparty="商家E", note="消费",
            source="alipay", bill_source="alipay", date="2024-07-01 10:00:00",
            record_id="mirror-expense", record_type="consumption",
        ),
        dict(
            amount=Decimal("20.00"), counterparty="商家E", note="退款",
            source="alipay", bill_source="alipay", date="2024-07-02 10:00:00",
            record_id="mirror-platform-refund", record_type="refund",
        ),
        dict(
            amount=Decimal("20.00"), counterparty="商家E", note="消费退货",
            source="ccb_debit", bill_source="ccb_debit", date="2024-07-02 11:00:00",
            record_id="mirror-bank-refund", record_type="refund",
        ),
    ):
        services.cashflow.add_manual_transaction(**row, **common)
    ids = _ids_by_record(services)
    with services.uow as uow:
        uow.relations.add({
            "kind": "refund_offset", "status": "accepted",
            "primary_fact_id": ids["mirror-expense"],
            "secondary_fact_id": ids["mirror-platform-refund"],
            "primary_component_id": ids["mirror-expense"],
            "secondary_component_id": ids["mirror-platform-refund"],
            "anchor_fact_id": ids["mirror-platform-refund"],
            "anchor_component_id": ids["mirror-platform-refund"],
            "rule_id": "test.accepted-refund", "created_by": "test",
        })
        uow.relations.add({
            "kind": "payment_mirror", "status": "accepted",
            "primary_fact_id": ids["mirror-platform-refund"],
            "secondary_fact_id": ids["mirror-bank-refund"],
            "primary_component_id": ids["mirror-platform-refund"],
            "secondary_component_id": ids["mirror-bank-refund"],
            "anchor_fact_id": ids["mirror-platform-refund"],
            "anchor_component_id": ids["mirror-platform-refund"],
            "rule_id": "test.accepted-refund-mirror", "created_by": "test",
        })
        uow.commit()

    assert services.relations.check(
        seed_fact_ids=[ids["mirror-bank-refund"]], trigger="import_batch",
    ).ok
    accepted = _accepted_refund_rows(services)
    assert len(accepted) == 1
    assert int(accepted[0]["secondary_fact_id"] or 0) == int(ids["mirror-platform-refund"])


def test_invalid_payment_mirror_replacement_keeps_old_relation_untouched(
    relation_runtime, monkeypatch,
):
    services = relation_runtime.services
    assert services.accounts.create_account("镜像重配原子账户", "cash", "CNY").ok
    common = dict(account_name="镜像重配原子账户", currency="CNY")
    for row in (
        dict(
            amount=Decimal("-12.00"), counterparty="商家F", note="平台消费",
            source="alipay", bill_source="alipay", date="2024-08-01 10:00:00",
            record_id="atomic-platform-expense", record_type="consumption",
        ),
        dict(
            amount=Decimal("-12.00"), counterparty="商家F", note="旧银行卡消费",
            source="ccb_debit", bill_source="ccb_debit", date="2024-08-01 10:00:01",
            record_id="atomic-old-bank-expense", record_type="consumption",
        ),
        dict(
            amount=Decimal("-12.00"), counterparty="商家F", note="新银行卡消费",
            source="ccb_debit", bill_source="ccb_debit", date="2024-08-01 10:00:02",
            record_id="atomic-new-bank-expense", record_type="consumption",
        ),
    ):
        services.cashflow.add_manual_transaction(**row, **common)
    ids = _ids_by_record(services)
    with services.uow as uow:
        old_id = uow.relations.add({
            "kind": RelationKind.PAYMENT_MIRROR.value,
            "status": RelationStatus.ACCEPTED.value,
            "primary_fact_id": ids["atomic-platform-expense"],
            "secondary_fact_id": ids["atomic-old-bank-expense"],
            "primary_component_id": ids["atomic-platform-expense"],
            "secondary_component_id": ids["atomic-old-bank-expense"],
            "anchor_fact_id": ids["atomic-platform-expense"],
            "anchor_component_id": ids["atomic-platform-expense"],
            "created_by": "system",
        })
        facts = services.relations._list_active_cash_facts(uow)
        by_id = {str(fact.id): fact for fact in facts}
        replacement = evaluate_payment_mirror(
            by_id[str(ids["atomic-platform-expense"])],
            [by_id[str(ids["atomic-new-bank-expense"])]],
        )
        assert replacement is not None

        monkeypatch.setattr(
            "ft.application.relations.match_canonical_payment_mirrors",
            lambda *_args, **_kwargs: [replacement],
        )
        monkeypatch.setattr(
            "ft.application.relations._projection_validation_reason",
            lambda *_args, **_kwargs: "projection_invalid",
        )
        services.relations._reconcile_system_payment_mirrors(uow, facts)

        assert uow.relations.get(old_id)["status"] == RelationStatus.ACCEPTED.value
        assert uow.relations.list_active(kind=RelationKind.PAYMENT_MIRROR.value) == [
            uow.relations.get(old_id)
        ]


def test_valid_payment_mirror_replacement_persists_after_one_batch_validation(
    relation_runtime, monkeypatch,
):
    services = relation_runtime.services
    assert services.accounts.create_account("镜像批量校验原子账户", "cash", "CNY").ok
    common = dict(account_name="镜像批量校验原子账户", currency="CNY")
    for row in (
        dict(
            amount=Decimal("-12.00"), counterparty="商家F", note="平台消费",
            source="alipay", bill_source="alipay", date="2024-08-04 10:00:00",
            record_id="batch-platform-expense", record_type="consumption",
        ),
        dict(
            amount=Decimal("-12.00"), counterparty="商家F", note="旧银行卡消费",
            source="ccb_debit", bill_source="ccb_debit", date="2024-08-04 10:00:01",
            record_id="batch-old-bank-expense", record_type="consumption",
        ),
        dict(
            amount=Decimal("-12.00"), counterparty="商家F", note="新银行卡消费",
            source="ccb_debit", bill_source="ccb_debit", date="2024-08-04 10:00:02",
            record_id="batch-new-bank-expense", record_type="consumption",
        ),
    ):
        services.cashflow.add_manual_transaction(**row, **common)
    ids = _ids_by_record(services)
    with services.uow as uow:
        old_id = uow.relations.add({
            "kind": RelationKind.PAYMENT_MIRROR.value,
            "status": RelationStatus.ACCEPTED.value,
            "primary_fact_id": ids["batch-platform-expense"],
            "secondary_fact_id": ids["batch-old-bank-expense"],
            "primary_component_id": ids["batch-platform-expense"],
            "secondary_component_id": ids["batch-old-bank-expense"],
            "anchor_fact_id": ids["batch-platform-expense"],
            "anchor_component_id": ids["batch-platform-expense"],
            "created_by": "system",
        })
        facts = services.relations._list_active_cash_facts(uow)
        by_id = {str(fact.id): fact for fact in facts}
        replacement = evaluate_payment_mirror(
            by_id[str(ids["batch-platform-expense"])],
            [by_id[str(ids["batch-new-bank-expense"])]],
        )
        assert replacement is not None
        calls = []

        monkeypatch.setattr(
            "ft.application.relations.match_canonical_payment_mirrors",
            lambda *_args, **_kwargs: [replacement],
        )

        def validate(*_args, **_kwargs):
            calls.append(1)
            return None

        monkeypatch.setattr("ft.application.relations._projection_validation_reason", validate)
        services.relations._reconcile_system_payment_mirrors(uow, facts)

        active = uow.relations.list_active(kind=RelationKind.PAYMENT_MIRROR.value)
        assert len(calls) == 1
        assert uow.relations.get(old_id)["status"] == RelationStatus.SUPERSEDED.value
        assert len(active) == 1
        assert active[0]["secondary_fact_id"] == ids["batch-new-bank-expense"]


def test_invalid_refund_replacement_keeps_old_relation_untouched(
    relation_runtime, monkeypatch,
):
    services = relation_runtime.services
    assert services.accounts.create_account("退款重配原子账户", "cash", "CNY").ok
    common = dict(account_name="退款重配原子账户", currency="CNY")
    for row in (
        dict(
            amount=Decimal("-12.00"), counterparty="商家G", note="银行卡消费",
            source="ccb_debit", bill_source="ccb_debit", date="2024-08-02 10:00:00",
            record_id="atomic-refund-bank-expense", record_type="consumption",
        ),
        dict(
            amount=Decimal("12.00"), counterparty="商家G", note="银行卡退款",
            source="ccb_debit", bill_source="ccb_debit", date="2024-08-03 10:00:00",
            record_id="atomic-refund-bank-refund", record_type="refund",
        ),
        dict(
            amount=Decimal("-12.00"), counterparty="商家G", note="平台消费",
            source="alipay", bill_source="alipay", date="2024-08-02 10:00:01",
            record_id="atomic-refund-platform-expense", record_type="consumption",
        ),
    ):
        services.cashflow.add_manual_transaction(**row, **common)
    ids = _ids_by_record(services)
    with services.uow as uow:
        old_id = uow.relations.add({
            "kind": RelationKind.REFUND_OFFSET.value,
            "status": RelationStatus.ACCEPTED.value,
            "primary_fact_id": ids["atomic-refund-bank-expense"],
            "secondary_fact_id": ids["atomic-refund-bank-refund"],
            "primary_component_id": ids["atomic-refund-bank-expense"],
            "secondary_component_id": ids["atomic-refund-bank-refund"],
            "anchor_fact_id": ids["atomic-refund-bank-expense"],
            "anchor_component_id": ids["atomic-refund-bank-expense"],
            "created_by": "system",
            "applied_amount": Decimal("12.00"),
        })
        facts = services.relations._list_active_cash_facts(uow)
        replacement = RelationProposal(
            kind=RelationKind.REFUND_OFFSET.value,
            primary_fact_id=str(ids["atomic-refund-platform-expense"]),
            secondary_fact_id=str(ids["atomic-refund-bank-refund"]),
            status=RelationStatus.ACCEPTED.value,
            rule_id="test.atomic.refund",
            evidence=RelationEvidence(
                extras={"refund_amount": "12.00"},
            ),
        )
        monkeypatch.setattr(
            "ft.application.relations.evaluate_refund_offset",
            lambda *_args, **_kwargs: replacement,
        )
        monkeypatch.setattr(
            "ft.application.relations._projection_validation_reason",
            lambda *_args, **_kwargs: "projection_invalid",
        )
        services.relations._reconcile_system_refund_offsets(uow, facts)

        assert uow.relations.get(old_id)["status"] == RelationStatus.ACCEPTED.value
        assert uow.relations.list_active(kind=RelationKind.REFUND_OFFSET.value) == [
            uow.relations.get(old_id)
        ]
