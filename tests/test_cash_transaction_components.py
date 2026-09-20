from __future__ import annotations

from decimal import Decimal


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
        snapshot = uow.snapshot.load()
        uow.commit()
    assert snapshot["accounts"]["cash"]["日常账户"]["CNY"] == "-60.00"
    assert snapshot["accounts"]["cash"]["工商银行储蓄卡"]["CNY"] == "-80.00"
