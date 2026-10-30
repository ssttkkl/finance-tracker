from __future__ import annotations

from cash_entry_fixtures import create_import_record

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

    aggregate = create_import_record(service, _payload(
        account_name="日常账户",
        components=[
            {"account_name": "日常账户", "amount": "-60.00", "label": "余额"},
            {"account_name": "工商银行储蓄卡", "amount": "-40.00", "label": "银行卡"},
        ],
    ))["record"]
    bank = create_import_record(service, _payload(
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

    expense = create_import_record(service, _payload(
        account_name="日常账户",
        amount="-100.00",
        components=[
            {"account_name": "日常账户", "amount": "-60.00", "label": "余额"},
            {"account_name": "工商银行储蓄卡", "amount": "-40.00", "label": "银行卡"},
        ],
    ))["record"]
    bank_expense = create_import_record(service, _payload(
        account_name="工商银行储蓄卡", amount="-40.00", counterparty="银行扣款",
    ))["record"]
    refund = create_import_record(service, _payload(
        account_name="日常账户",
        amount="50.00", record_type="refund", counterparty="商户退款",
        components=[
            {"account_name": "日常账户", "amount": "30.00", "label": "余额"},
            {"account_name": "工商银行储蓄卡", "amount": "20.00", "label": "银行卡"},
        ],
    ))["record"]
    bank_refund = create_import_record(service, _payload(
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
    first_values = {
        "primary_fact_id": expense["id"],
        "primary_component_id": expense["components"][0]["id"],
        "secondary_fact_id": refund["id"],
        "secondary_component_id": refund["components"][0]["id"],
        "kind": "refund_offset", "applied_amount": "30.00", "status": "accepted",
    }
    with pytest.raises(ValueError, match="该关系无法形成有效收支投影"):
        service.add_relation(first_values)
    with service._uow as uow:
        assert not any(row["kind"] == "refund_offset" for row in uow.relations.list_active())
        uow.commit()
    second_values = {
        "primary_fact_id": expense["id"],
        "primary_component_id": expense["components"][1]["id"],
        "secondary_fact_id": refund["id"],
        "secondary_component_id": refund["components"][1]["id"],
        "kind": "refund_offset", "applied_amount": "20.00", "status": "accepted",
    }
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    from ft.application.cash_relation_context import CashRelationGraphService
    from ft.application.cash_projections import CashProjectionService
    from ft.domain.cash_relation_graph import CashRelationGraphEdge

    with service._uow as uow:
        session = uow._state().session
        graph = CashRelationGraphService(session, uow.workspace_id)
        context = graph.load({expense["id"], refund["id"]})
        first_id = max(edge.id for edge in context.relations) + 1
        additions = tuple(CashRelationGraphEdge(
            id=first_id + index, kind="refund_offset",
            primary_transaction_id=str(values["primary_fact_id"]),
            secondary_transaction_id=str(values["secondary_fact_id"]),
            primary_component_id=str(values["primary_component_id"]),
            secondary_component_id=str(values["secondary_component_id"]),
            primary_role="expense", secondary_role="refund",
            applied_amount=Decimal(values["applied_amount"]),
        ) for index, values in enumerate((first_values, second_values)))
        graph.validate(context, additions=additions)
        repository = CurrentCashRelationRepository(session, uow.workspace_id)
        saved = [repository.add({key: getattr(edge, key) for key in (
            "kind", "state", "active", "primary_transaction_id", "secondary_transaction_id",
            "primary_component_id", "secondary_component_id", "primary_role", "secondary_role", "applied_amount",
        )} | {"anchor_transaction_id": edge.primary_transaction_id,
            "anchor_component_id": edge.primary_component_id, "decision_source": "manual"}) for edge in additions]
        CashProjectionService.maintain_if_ready_in_session(session, uow.workspace_id, context.transaction_ids)
        uow.commit()
    first_refund, second_refund = saved
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
    extra_refund = create_import_record(service, _payload(
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
        graph = CashRelationGraphService(uow._state().session, uow.workspace_id)
        build = graph.validate(graph.load({expense["id"]})).build
        uow.commit()
    projection = next(item for item in build.projections if set(item.member_ids) >= {
        expense["id"], bank_expense["id"], refund["id"], bank_refund["id"],
    })
    assert projection.net_amount == Decimal("-50.00")


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
        parent = session.get(CashTransactionModel, record["id"])
        assert parent is not None and parent.deleted_at is not None and parent.account_id is None
        assert parent.amount == Decimal("-100.00")


def test_component_count_derives_cash_granularity_and_rejects_client_value(cash_web_runtime):
    from ft.adapters.relational.models import CashTransactionModel

    _enable_cny(cash_web_runtime, "日常账户")
    service = _service(cash_web_runtime)

    with pytest.raises(ValueError):
        service.create_record(_payload(account_name="日常账户", cash_granularity="aggregate"))
    atomic = service.create_record(_payload(
        account_name="日常账户",
        components=[{"account_name": "日常账户", "amount": "-100.00"}],
    ))["record"]
    assert atomic["cash_granularity"] == "atomic"

    with cash_web_runtime.sessions() as session:
        parent = session.get(CashTransactionModel, atomic["id"])
        assert parent.cash_granularity == "atomic"
        assert parent.account_id is not None


def test_component_validation_rejects_invalid_shape_atomically(cash_web_runtime):
    from ft.adapters.relational.models import CashTransactionComponentModel, CashTransactionModel

    with cash_web_runtime.sessions.begin() as session:
        from ft.adapters.relational.models import AccountModel

        session.add(AccountModel(
            workspace_id=cash_web_runtime.workspace_id,
            name="第二账户", type="cash", currencies=["CNY"],
        ))
    _enable_cny(cash_web_runtime, "日常账户", "第二账户")
    service = _service(cash_web_runtime)
    with cash_web_runtime.sessions() as session:
        initial_component_count = session.query(CashTransactionComponentModel).filter(
            CashTransactionComponentModel.workspace_id == cash_web_runtime.workspace_id,
        ).count()

    invalid_cases = (
        (
            "组成项金额之和必须等于父流水金额",
            [{"account_name": "日常账户", "amount": "-60.00"}, {"account_name": "第二账户", "amount": "-30.00"}],
            {},
        ),
        (
            "组成项币种必须与父流水一致",
            [{"account_name": "日常账户", "amount": "-100.00", "currency": "USD"}],
            {},
        ),
        (
            "组成项金额方向必须与父流水一致",
            [{"account_name": "日常账户", "amount": "-100.00"}],
            {"amount": "100.00", "record_type": "income"},
        ),
        (
            "cash transaction requires at least one component",
            [],
            {},
        ),
    )

    for error, components, payload in invalid_cases:
        with pytest.raises(ValueError, match=error):
            service.create_record(_payload(account_name="日常账户", components=components, **payload))

        with cash_web_runtime.sessions() as session:
            assert session.query(CashTransactionModel).filter(
                CashTransactionModel.workspace_id == cash_web_runtime.workspace_id,
                CashTransactionModel.note == "组合支付",
            ).count() == 0
            assert session.query(CashTransactionComponentModel).filter(
                CashTransactionComponentModel.workspace_id == cash_web_runtime.workspace_id,
            ).count() == initial_component_count


def test_component_update_preserves_endpoint_identity_and_rechecks_conservation(cash_web_runtime):
    from ft.adapters.relational.models import CashTransactionComponentModel

    with cash_web_runtime.sessions.begin() as session:
        from ft.adapters.relational.models import AccountModel

        session.add(AccountModel(
            workspace_id=cash_web_runtime.workspace_id,
            name="第二账户", type="cash", currencies=["CNY"],
        ))
    _enable_cny(cash_web_runtime, "日常账户", "第二账户")
    service = _service(cash_web_runtime)
    record = service.create_record(_payload(
        account_name="日常账户",
        components=[
            {"account_name": "日常账户", "amount": "-60.00"},
            {"account_name": "第二账户", "amount": "-40.00"},
        ],
    ))["record"]
    component_ids = [item["id"] for item in record["components"]]

    updated = service.update_record(record["id"], {
        "account_name": "日常账户",
        "amount": "-100.00",
        "currency": "CNY",
        "components": [
            {"account_name": "日常账户", "amount": "-70.00"},
            {"account_name": "第二账户", "amount": "-30.00"},
        ],
    })["record"]
    assert [item["id"] for item in updated["components"]] == component_ids
    assert [item["amount"] for item in updated["components"]] == ["-70.00", "-30.00"]

    with pytest.raises(ValueError, match="组成项金额之和必须等于父流水金额"):
        service.update_record(record["id"], {
            "account_name": "日常账户",
            "amount": "-100.00",
            "currency": "CNY",
            "components": [
                {"account_name": "日常账户", "amount": "-71.00"},
                {"account_name": "第二账户", "amount": "-30.00"},
            ],
        })

    with cash_web_runtime.sessions() as session:
        rows = session.query(CashTransactionComponentModel).filter(
            CashTransactionComponentModel.workspace_id == cash_web_runtime.workspace_id,
            CashTransactionComponentModel.cash_transaction_id == record["id"],
        ).order_by(CashTransactionComponentModel.ordinal).all()
        assert [row.id for row in rows] == component_ids
        assert [str(row.amount) for row in rows] == ["-70.00", "-30.00"]


def test_component_update_rebuilds_each_account_balance_without_parent_double_count(cash_web_runtime):
    from ft.adapters.relational.models import AccountModel

    with cash_web_runtime.sessions.begin() as session:
        session.add(AccountModel(
            workspace_id=cash_web_runtime.workspace_id,
            name="第二账户", type="cash", currencies=["CNY"],
        ))
    _enable_cny(cash_web_runtime, "日常账户", "第二账户")
    service = _service(cash_web_runtime)
    record = service.create_record(_payload(
        account_name="日常账户",
        components=[
            {"account_name": "日常账户", "amount": "-60.00"},
            {"account_name": "第二账户", "amount": "-40.00"},
        ],
    ))["record"]

    with service._uow as uow:
        initial = uow.snapshot.load()
        uow.commit()
    assert initial["accounts"]["cash"]["日常账户"]["CNY"] == "-60.00"
    assert initial["accounts"]["cash"]["第二账户"]["CNY"] == "-40.00"

    service.update_record(record["id"], {
        "account_name": "日常账户",
        "amount": "-100.00",
        "currency": "CNY",
        "components": [
            {"account_name": "日常账户", "amount": "-75.00"},
            {"account_name": "第二账户", "amount": "-25.00"},
        ],
    })

    with service._uow as uow:
        updated = uow.snapshot.load()
        uow.commit()
    assert updated["accounts"]["cash"]["日常账户"]["CNY"] == "-75.00"
    assert updated["accounts"]["cash"]["第二账户"]["CNY"] == "-25.00"


def test_aggregate_projection_is_parent_row_with_component_detail_and_account_filter(cash_web_runtime):
    from sqlalchemy import select

    from ft.adapters.relational.models import AccountModel
    from ft.application.cash_projections import CashProjectionService
    from ft.application.web_queries import CashLedgerQueryService

    with cash_web_runtime.sessions.begin() as session:
        session.add(AccountModel(
            workspace_id=cash_web_runtime.workspace_id,
            name="第二账户", type="cash", currencies=["CNY"],
        ))
    _enable_cny(cash_web_runtime, "日常账户", "第二账户")
    service = _service(cash_web_runtime)
    record = service.create_record(_payload(
        account_name="日常账户",
        components=[
            {"account_name": "日常账户", "amount": "-60.00", "label": "余额"},
            {"account_name": "第二账户", "amount": "-40.00", "label": "银行卡"},
        ],
    ))["record"]
    with cash_web_runtime.sessions() as session:
        second_account_id = session.scalar(select(AccountModel.id).where(
            AccountModel.workspace_id == cash_web_runtime.workspace_id,
            AccountModel.name == "第二账户",
        ))

    CashProjectionService(
        cash_web_runtime.sessions, cash_web_runtime.workspace_id,
    ).rebuild()
    query = CashLedgerQueryService(
        cash_web_runtime.sessions, cash_web_runtime.workspace_id,
    )
    page = query.list_cash_projections(account_id=second_account_id, limit=50)
    item = next(item for item in page.items if item.transaction_id == str(record['id']))
    assert item.account is None
    assert item.amount == "-100"
    assert item.member_count == 1

    evidence = query.get_projection_evidence(f"cash:{record['id']}")
    assert [component["account_name"] for component in evidence["root_record"]["components"]] == [
        "日常账户", "第二账户",
    ]
    assert [component["amount"] for component in evidence["root_record"]["components"]] == [
        "-60", "-40",
    ]
