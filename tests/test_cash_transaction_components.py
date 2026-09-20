from __future__ import annotations

from decimal import Decimal

import pytest


def _enable_cny(runtime, *names):
    from ft.adapters.relational.models import AccountModel

    with runtime.sessions.begin() as session:
        for name in names:
            account = session.query(AccountModel).filter(
                AccountModel.workspace_id == runtime.workspace_id,
                AccountModel.name == name,
            ).one()
            account.currencies = ["CNY"]


def _service(runtime):
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.application.relations import RelationService

    uow = RelationalUnitOfWork(runtime.sessions, runtime.workspace_id)
    return CashLedgerCommandService(runtime.sessions, runtime.workspace_id, relation_service=RelationService(uow))


def _payload(**overrides):
    return {
        "account_name": "支付宝余额",
        "amount": "-100.00",
        "currency": "CNY",
        "occurred_at": "2026-09-20T09:00:00+00:00",
        "counterparty": "商户",
        "record_type": "consumption",
        "record_subtype": "not_applicable",
        "note": "组合支付",
        **overrides,
    }


def test_aggregate_component_can_mirror_one_bank_component_without_parent_double_count(cash_web_runtime):
    from ft.adapters.relational.models import AccountModel, CashTransactionModel

    with cash_web_runtime.sessions.begin() as session:
        session.add(AccountModel(
            workspace_id=cash_web_runtime.workspace_id,
            name="工商银行储蓄卡", type="cash", currencies=["CNY"],
        ))
    _enable_cny(cash_web_runtime, "日常账户", "工商银行储蓄卡")
    service = _service(cash_web_runtime)

    aggregate = service.create_record(_payload(
        account_name="日常账户",
        components=[
            {"account_name": "日常账户", "amount": "-60.00", "label": "余额"},
            {"account_name": "工商银行储蓄卡", "amount": "-40.00", "label": "银行卡"},
        ],
    ))["record"]
    bank = service.create_record(_payload(
        account_name="工商银行储蓄卡", amount="-40.00", counterparty="银行扣款",
    ))["record"]

    assert aggregate["cash_granularity"] == "aggregate"
    with cash_web_runtime.sessions() as session:
        parent = session.get(CashTransactionModel, aggregate["id"])
        assert parent.account_id is None
    assert [item["amount"] for item in aggregate["components"]] == ["-60.00", "-40.00"]

    bank_component = bank["components"][0]["id"]
    relation = service.add_relation({
        "primary_fact_id": aggregate["id"],
        "primary_component_id": aggregate["components"][1]["id"],
        "secondary_fact_id": bank["id"],
        "secondary_component_id": bank_component,
        "kind": "payment_mirror",
        "status": "accepted",
    })["relations"][0]

    assert relation["primary_component_id"] == aggregate["components"][1]["id"]
    assert relation["secondary_component_id"] == bank_component
    assert relation["applied_amount"] == Decimal("40.00")

    with service._uow as uow:
        with pytest.raises(ValueError, match="组合支付关系必须指定组成项"):
            uow.relations.find_by_business_key(
                kind="payment_mirror",
                fact_a=aggregate["id"],
                fact_b=bank["id"],
            )
        with pytest.raises(ValueError, match="不属于指定流水"):
            uow.relations.add({
                "kind": "payment_mirror",
                "primary_fact_id": aggregate["id"],
                "primary_component_id": bank_component,
                "secondary_fact_id": bank["id"],
                "secondary_component_id": bank_component,
                "status": "pending_review",
            })
        uow.commit()

    with service._uow as uow:
        snapshot = uow.snapshot.load()
        uow.commit()
    assert snapshot["accounts"]["cash"]["日常账户"]["CNY"] == "-60.00"
    assert snapshot["accounts"]["cash"]["工商银行储蓄卡"]["CNY"] == "-80.00"


def test_aggregate_refund_components_can_offset_and_mirror_bank_refund(cash_web_runtime):
    from ft.adapters.relational.models import AccountModel
    from ft.adapters.relational.projections import RelationalCashProjectionRepository
    from ft.domain.cash_projection import build_cash_projections

    with cash_web_runtime.sessions.begin() as session:
        session.add(AccountModel(
            workspace_id=cash_web_runtime.workspace_id,
            name="工商银行储蓄卡", type="cash", currencies=["CNY"],
        ))
    _enable_cny(cash_web_runtime, "日常账户", "工商银行储蓄卡")
    service = _service(cash_web_runtime)

    expense = service.create_record(_payload(
        account_name="日常账户",
        amount="-100.00",
        components=[
            {"account_name": "日常账户", "amount": "-60.00", "label": "余额"},
            {"account_name": "工商银行储蓄卡", "amount": "-40.00", "label": "银行卡"},
        ],
    ))["record"]
    bank_expense = service.create_record(_payload(
        account_name="工商银行储蓄卡", amount="-40.00", counterparty="银行扣款",
    ))["record"]
    refund = service.create_record(_payload(
        account_name="日常账户",
        amount="50.00", record_type="refund", counterparty="商户退款",
        components=[
            {"account_name": "日常账户", "amount": "30.00", "label": "余额"},
            {"account_name": "工商银行储蓄卡", "amount": "20.00", "label": "银行卡"},
        ],
    ))["record"]
    bank_refund = service.create_record(_payload(
        account_name="工商银行储蓄卡", amount="20.00", record_type="refund",
        counterparty="银行退款",
    ))["record"]

    service.add_relation({
        "primary_fact_id": expense["id"],
        "primary_component_id": expense["components"][1]["id"],
        "secondary_fact_id": bank_expense["id"],
        "secondary_component_id": bank_expense["components"][0]["id"],
        "kind": "payment_mirror", "status": "accepted",
    })
    first_refund = next(item for item in service.add_relation({
        "primary_fact_id": expense["id"],
        "primary_component_id": expense["components"][0]["id"],
        "secondary_fact_id": refund["id"],
        "secondary_component_id": refund["components"][0]["id"],
        "kind": "refund_offset", "applied_amount": "30.00", "status": "accepted",
    })["relations"] if item["kind"] == "refund_offset" and item["secondary_component_id"] == refund["components"][0]["id"])
    second_refund = next(item for item in service.add_relation({
        "primary_fact_id": expense["id"],
        "primary_component_id": expense["components"][1]["id"],
        "secondary_fact_id": refund["id"],
        "secondary_component_id": refund["components"][1]["id"],
        "kind": "refund_offset", "applied_amount": "20.00", "status": "accepted",
    })["relations"] if item["kind"] == "refund_offset" and item["secondary_component_id"] == refund["components"][1]["id"])
    bank_refund_mirror = next(item for item in service.add_relation({
        "primary_fact_id": refund["id"],
        "primary_component_id": refund["components"][1]["id"],
        "secondary_fact_id": bank_refund["id"],
        "secondary_component_id": bank_refund["components"][0]["id"],
        "kind": "payment_mirror", "status": "accepted",
    })["relations"] if item["kind"] == "payment_mirror" and item["secondary_component_id"] == bank_refund["components"][0]["id"])

    assert first_refund["applied_amount"] == Decimal("30.00")
    assert second_refund["applied_amount"] == Decimal("20.00")
    assert bank_refund_mirror["applied_amount"] == Decimal("20.00")
    extra_refund = service.create_record(_payload(
        account_name="日常账户", amount="31.00", record_type="refund",
        counterparty="超额退款",
    ))["record"]
    with pytest.raises(ValueError, match="组成项退款分摊金额超过可用金额"):
        service.add_relation({
            "primary_fact_id": expense["id"],
            "primary_component_id": expense["components"][0]["id"],
            "secondary_fact_id": extra_refund["id"],
            "secondary_component_id": extra_refund["components"][0]["id"],
            "kind": "refund_offset", "applied_amount": "31.00", "status": "accepted",
        })
    with service._uow as uow:
        facts, relations = RelationalCashProjectionRepository(
            uow._state().session, uow.workspace_id,
        ).read_sources()
        build = build_cash_projections(facts, relations)
        uow.commit()
    projection = next(item for item in build.projections if set(item.member_ids) >= {
        expense["id"], bank_expense["id"], refund["id"], bank_refund["id"],
    })
    assert projection.net_amount == Decimal("-50.00")


def test_partial_component_refund_uses_applied_amount_for_later_matching(cash_web_runtime):
    _enable_cny(cash_web_runtime, "日常账户")
    service = _service(cash_web_runtime)

    expense = service.create_record(_payload(
        account_name="日常账户",
        amount="-100.00",
        occurred_at="2026-09-18T09:00:00+00:00",
    ))["record"]
    first_refund = service.create_record(_payload(
        account_name="日常账户",
        amount="100.00",
        record_type="refund",
        occurred_at="2026-09-19T09:00:00+00:00",
    ))["record"]
    service.add_relation({
        "primary_fact_id": expense["id"],
        "primary_component_id": expense["components"][0]["id"],
        "secondary_fact_id": first_refund["id"],
        "secondary_component_id": first_refund["components"][0]["id"],
        "kind": "refund_offset",
        "applied_amount": "30.00",
        "status": "accepted",
    })
    with service._uow as uow:
        facts = service._relation_service._list_active_cash_facts(uow)
        remaining = service._relation_service._refund_remaining(uow, facts)
        uow.commit()
    assert remaining[str(expense["components"][0]["id"])] == Decimal("70.00")

    second_refund = service.create_record(_payload(
        account_name="日常账户",
        amount="50.00",
        record_type="refund",
        occurred_at="2026-09-20T09:00:00+00:00",
    ))["record"]
    checked = service._relation_service.check(
        seed_fact_ids=[second_refund["id"]],
        trigger="manual_range",
    )
    assert checked.ok, (checked.message, checked.details)

    with service._uow as uow:
        accepted = uow.relations.list_active(
            kind="refund_offset",
            status="accepted",
        )
        uow.commit()
    assert any(
        item["primary_component_id"] == expense["components"][0]["id"]
        and item["secondary_component_id"] == second_refund["components"][0]["id"]
        and item["applied_amount"] == Decimal("50.00")
        for item in accepted
    )


def test_aggregate_parent_can_be_deleted_without_a_single_account(cash_web_runtime):
    from ft.adapters.relational.models import CashTransactionModel, AccountModel

    with cash_web_runtime.sessions.begin() as session:
        session.add(AccountModel(
            workspace_id=cash_web_runtime.workspace_id,
            name="工商银行储蓄卡", type="cash", currencies=["CNY"],
        ))
    _enable_cny(cash_web_runtime, "日常账户", "工商银行储蓄卡")
    service = _service(cash_web_runtime)
    record = service.create_record(_payload(
        account_name="日常账户",
        components=[
            {"account_name": "日常账户", "amount": "-60.00"},
            {"account_name": "工商银行储蓄卡", "amount": "-40.00"},
        ],
    ))["record"]

    deleted = service.delete_record(str(record["id"]))
    assert deleted["deleted"] is True
    with cash_web_runtime.sessions() as session:
        assert session.get(CashTransactionModel, record["id"]) is None


def test_aggregate_refund_open_leg_is_anchored_to_the_refund_component(cash_web_runtime):
    from ft.adapters.relational.models import AccountModel
    from ft.domain.relations import RelationKind

    with cash_web_runtime.sessions.begin() as session:
        session.add(AccountModel(
            workspace_id=cash_web_runtime.workspace_id,
            name="备用现金账户", type="cash", currencies=["CNY"],
        ))
    _enable_cny(cash_web_runtime, "日常账户", "备用现金账户")
    service = _service(cash_web_runtime)

    first = service.create_record(_payload(
        account_name="日常账户", amount="-100.00", counterparty="京东",
    ))["record"]
    second = service.create_record(_payload(
        account_name="日常账户", amount="-100.00", counterparty="京东",
    ))["record"]
    refund = service.create_record(_payload(
        account_name="日常账户", amount="120.00", record_type="refund",
        counterparty="京东",
        components=[
            {"account_name": "日常账户", "amount": "100.00", "label": "余额"},
            {"account_name": "备用现金账户", "amount": "20.00", "label": "银行卡"},
        ],
    ))["record"]

    checked = service._relation_service.check(
        seed_fact_ids=[first["id"], second["id"], refund["id"]],
        trigger="manual_range",
    )
    assert checked.ok is True
    pending = service._relation_service.list_pending(kind=RelationKind.REFUND_OFFSET.value)
    open_rows = [item for item in pending if item.get("secondary_component_id") in (None, "")]
    refund_open = next(
        item for item in open_rows
        if item["anchor_component_id"] == refund["components"][0]["id"]
    )
    assert refund_open["anchor_record_id"] == refund["id"]
