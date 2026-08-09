from decimal import Decimal
from pathlib import Path

import pytest


def _service(runtime):
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.application.relations import RelationService
    from ft.adapters.relational.uow import RelationalUnitOfWork

    return CashLedgerCommandService(
        runtime.sessions,
        runtime.workspace_id,
        relation_service=RelationService(RelationalUnitOfWork(runtime.sessions, runtime.workspace_id)),
    )


def _enable_cny(runtime, *names):
    from ft.adapters.relational.models import AccountModel

    with runtime.sessions.begin() as session:
        for name in names:
            account = session.query(AccountModel).filter(
                AccountModel.workspace_id == runtime.workspace_id,
                AccountModel.name == name,
            ).one()
            account.currencies = ["CNY"]


def _payload(**overrides):
    return {
        "account_name": "日常账户",
        "amount": "-12.50",
        "currency": "CNY",
        "occurred_at": "2026-07-05T09:00",
        "counterparty": "咖啡店",
        "category": "餐饮",
        "record_type": "consumption",
        "record_subtype": "not_applicable",
        "note": "早餐",
        **overrides,
    }


def test_web_service_creates_negative_and_zero_records_without_direction_control(cash_web_runtime):
    _enable_cny(cash_web_runtime, "日常账户")
    service = _service(cash_web_runtime)

    expense = service.create_record(_payload())
    zero = service.create_record(_payload(amount="0", record_type="income", counterparty="余额校准"))

    assert expense["record"]["amount"] == "-12.50"
    assert zero["record"]["amount"] == "0"
    assert zero["record"]["record_type"] == "income"


@pytest.mark.parametrize("runtime_name", ["cash_web_runtime", "postgres_cash_web_runtime"])
def test_cash_write_contract_is_shared_by_sqlite_and_postgres(request, runtime_name):
    runtime = request.getfixturevalue(runtime_name)
    _enable_cny(runtime, "日常账户")
    service = _service(runtime)

    created = service.create_record(_payload(amount="0", record_type="income"))

    assert created["record"]["amount"] == "0"
    assert created["record"]["currency"] == "CNY"
    assert created["record"]["record_type"] == "income"


def test_web_service_rejects_empty_account_currency_configuration(cash_web_runtime):
    service = _service(cash_web_runtime)

    with pytest.raises(ValueError, match="暂未配置|暂不支持"):
        service.create_record(_payload())


def test_import_merge_keeps_manual_field_and_refreshes_other_fields(cash_web_runtime):
    _enable_cny(cash_web_runtime, "日常账户")
    service = _service(cash_web_runtime)
    with service._uow as uow:
        fact_id = uow.cashflows.add("cash", _payload(
            source_type="fixture", record_id="source-1", source_payload={"merchant": "原商户"},
        ))
        uow.commit()
    service.update_record(fact_id, _payload(
        amount="-12.50", counterparty="人工校准", source_payload=None,
    ))

    with service._uow as uow:  # application-level contract test
        fact_id2, created_new = uow.cashflows.merge_import("cash", {
            **_payload(
                amount="-13.00", counterparty="来源新商户", note="来源新备注",
                source_type="fixture", record_id="source-1",
                source_payload={"merchant": "来源新商户"},
            ),
        })
        uow.commit()

    assert fact_id2 == fact_id
    assert created_new is False
    current = service.get_record(fact_id)["record"]
    assert current["counterparty"] == "人工校准"
    assert current["note"] == "来源新备注"


def test_delete_record_removes_relation_and_keeps_other_endpoint(cash_web_runtime):
    _enable_cny(cash_web_runtime, "日常账户", "信用账户")
    service = _service(cash_web_runtime)
    outgoing = service.create_record(_payload(
        account_name="日常账户", amount="-100", record_type="transfer_out",
        counterparty="信用账户",
    ))
    incoming = service.create_record(_payload(
        account_name="信用账户", amount="100", record_type="transfer_in",
        counterparty="日常账户",
    ))
    relation = service.add_relation({
        "primary_fact_id": outgoing["record"]["id"],
        "secondary_fact_id": incoming["record"]["id"],
        "kind": "transfer_pair",
        "subtype": "ordinary_transfer",
        "status": "accepted",
    })
    assert relation["relations"]

    result = service.delete_record(outgoing["record"]["id"])
    assert result["deleted"] is True
    assert result["related_count"] == 1
    assert service.get_record(incoming["record"]["id"])["record"]["id"] == incoming["record"]["id"]
    with pytest.raises(ValueError, match="找不到"):
        service.get_record(outgoing["record"]["id"])


class _RowsParser:
    def __init__(self, rows):
        self.rows = rows

    def parse(self, _command):
        return [dict(row) for row in self.rows]


def test_import_is_row_idempotent_updates_snapshot_and_allows_republish_after_delete(cash_web_runtime, tmp_path):
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.application.statement_import import StatementImportService
    from ft.domain.imports import StatementImportCommand

    _enable_cny(cash_web_runtime, "日常账户")
    rows = [_payload(
        amount="-10.00", counterparty="来源商户", note="来源备注",
        source_type="fixture", bill_source="fixture", record_id="row-1",
        source_payload={"merchant": "来源商户", "amount": "-10.00"},
    )]
    parser = _RowsParser(rows)
    path = Path(tmp_path) / "statement.csv"
    path.write_bytes(b"fixture")
    service = StatementImportService(
        RelationalUnitOfWork(cash_web_runtime.sessions, cash_web_runtime.workspace_id),
        parser,
        enforce_account_currencies=True,
    )
    command = StatementImportCommand(source_path=str(path), source="fixture", currency="CNY")

    first = service.import_statement(command)
    second = service.import_statement(command)
    assert first.count == 1
    assert second.count == 0
    assert second.details["updated_rows"] == 0

    rows[0] = {**rows[0], "amount": "-12.00", "counterparty": "来源新商户", "note": "新备注",
               "source_payload": {"merchant": "来源新商户", "amount": "-12.00"}}
    updated = service.import_statement(command)
    assert updated.count == 0
    assert updated.details["updated_rows"] == 1
    with RelationalUnitOfWork(cash_web_runtime.sessions, cash_web_runtime.workspace_id) as uow:
        current = uow.cashflows.find_active_by_source_identity("fixture", "row-1")
        assert current["amount"] == Decimal("-12.00")
        assert uow.snapshot.load()["accounts"]["cash"]["日常账户"]["CNY"] == "-12.00"
        fact_id = current["id"]
        uow.commit()

    command_service = _service(cash_web_runtime)
    command_service.update_record(fact_id, _payload(
        amount="-12.00", counterparty="人工校准", note="新备注",
    ))
    with RelationalUnitOfWork(cash_web_runtime.sessions, cash_web_runtime.workspace_id) as uow:
        initial_overrides = uow.cashflows.get(fact_id)["manual_overrides"]
        assert initial_overrides["counterparty"]["value"] == "人工校准"
        uow.commit()
    rows[0] = {**rows[0], "amount": "-13.00", "note": "再次导入",
               "source_payload": {"merchant": "来源新商户", "amount": "-13.00"}}
    service.import_statement(command)
    current = command_service.get_record(fact_id)["record"]
    assert current["counterparty"] == "人工校准"
    assert current["amount"] == "-13.00"
    with RelationalUnitOfWork(cash_web_runtime.sessions, cash_web_runtime.workspace_id) as uow:
        debug_overrides = uow.cashflows.get(fact_id)["manual_overrides"]
        assert debug_overrides["counterparty"]["value"] == "人工校准"
        assert debug_overrides["counterparty"]["source_value"] == "来源新商户"
        uow.commit()
    command_service.update_record(fact_id, _payload(
        amount="-13.00", counterparty="来源新商户", note="再次导入",
    ))
    with RelationalUnitOfWork(cash_web_runtime.sessions, cash_web_runtime.workspace_id) as uow:
        assert uow.cashflows.get(fact_id)["manual_overrides"] == {}
        uow.commit()

    command_service.delete_record(fact_id)
    with RelationalUnitOfWork(cash_web_runtime.sessions, cash_web_runtime.workspace_id) as uow:
        republished, created = uow.cashflows.merge_import("cash", rows[0])
        republished_row = uow.cashflows.get(republished)
        uow.commit()
    assert created is True
    assert republished_row["record_id"] == "row-1"


def test_cancelled_relation_blocks_auto_slot_but_manual_relink_reuses_it(cash_web_runtime):
    _enable_cny(cash_web_runtime, "日常账户", "信用账户")
    service = _service(cash_web_runtime)
    outgoing = service.create_record(_payload(
        account_name="日常账户", amount="-100", record_type="transfer_out", counterparty="信用账户",
    ))
    incoming = service.create_record(_payload(
        account_name="信用账户", amount="100", record_type="transfer_in", counterparty="日常账户",
    ))
    relation = service.add_relation({
        "primary_fact_id": outgoing["record"]["id"], "secondary_fact_id": incoming["record"]["id"],
        "kind": "transfer_pair", "subtype": "ordinary_transfer", "status": "pending_review",
    })
    assert relation["relations"][0]["status"] == "pending_review"
    cancelled = service.cancel_relation(relation["relations"][0]["id"])
    assert cancelled["status"] == "rejected"
    relinked = service.add_relation({
        "primary_fact_id": outgoing["record"]["id"], "secondary_fact_id": incoming["record"]["id"],
        "kind": "transfer_pair", "subtype": "ordinary_transfer", "status": "pending_review",
    })
    assert relinked["relations"][0]["status"] == "pending_review"


def test_pending_relation_type_can_be_updated_without_creating_history(cash_web_runtime):
    _enable_cny(cash_web_runtime, "日常账户", "信用账户")
    service = _service(cash_web_runtime)
    first = service.create_record(_payload(counterparty="第一笔"))
    second = service.create_record(_payload(counterparty="第二笔"))
    relation = service.add_relation({
        "primary_fact_id": first["record"]["id"],
        "secondary_fact_id": second["record"]["id"],
        "kind": "payment_mirror",
        "status": "pending_review",
    })
    relation_id = relation["relations"][0]["id"]

    updated = service.update_relation(relation_id, {"kind": "refund_offset"})

    current = next(item for item in updated["relations"] if item["id"] == relation_id)
    assert current["kind"] == "refund_offset"
    assert current["status"] == "pending_review"


def test_cash_write_api_uses_fact_ids_and_keeps_internal_calibration_private(cash_web_runtime):
    from fastapi.testclient import TestClient
    from ft.application.web_queries import CashLedgerQueryService
    from ft.web.app import create_app

    _enable_cny(cash_web_runtime, "日常账户")
    mutation = _service(cash_web_runtime)
    client = TestClient(create_app(
        CashLedgerQueryService(cash_web_runtime.sessions, cash_web_runtime.workspace_id),
        mutation_service=mutation,
    ))
    response = client.post("/api/v1/cash-records", json=_payload(amount="0", record_type="income"))
    assert response.status_code == 201
    body = response.json()
    fact_id = body["record"]["id"]
    assert "manual_overrides" not in body["record"]
    assert "source_fingerprint" not in body["record"]
    assert client.get("/api/v1/cash-records", params={"exclude_id": fact_id}).status_code == 200
    invalid = client.put(
        f"/api/v1/cash-records/{fact_id}",
        json=_payload(currency="JPY", amount="1", record_type="income"),
    )
    assert invalid.status_code == 400
    assert "暂不支持" in invalid.json()["error"]["message"]


@pytest.mark.parametrize("runtime_name", ["cash_web_runtime", "postgres_cash_web_runtime"])
def test_relation_candidate_search_is_filtered_bounded_and_stably_paginated(request, runtime_name):
    runtime = request.getfixturevalue(runtime_name)
    _enable_cny(runtime, "日常账户")
    service = _service(runtime)
    created = [
        service.create_record(_payload(
            occurred_at=f"2026-07-{day:02d}T09:00",
            counterparty=f"候选商户 {day}",
            note="关联检索",
        ))["record"]
        for day in range(1, 5)
    ]
    service.create_record(_payload(
        occurred_at="2026-07-05T09:00",
        counterparty="不相关商户",
        note="普通流水",
    ))

    first = service.list_records(query="候选", exclude_id=created[3]["id"], limit=2)
    second = service.list_records(
        query="候选",
        exclude_id=created[3]["id"],
        limit=2,
        cursor=first["next_cursor"],
    )

    assert [item["counterparty"] for item in first["items"]] == ["候选商户 3", "候选商户 2"]
    assert [item["counterparty"] for item in second["items"]] == ["候选商户 1"]
    assert first["next_cursor"]
    assert second["next_cursor"] is None
    assert {item["id"] for item in first["items"]}.isdisjoint({item["id"] for item in second["items"]})


def test_relation_candidate_api_rejects_invalid_cursor_and_caps_page_size(cash_web_runtime):
    from fastapi.testclient import TestClient
    from ft.application.web_queries import CashLedgerQueryService
    from ft.web.app import create_app

    _enable_cny(cash_web_runtime, "日常账户")
    mutation = _service(cash_web_runtime)
    for day in range(1, 23):
        mutation.create_record(_payload(
            occurred_at=f"2026-06-{day:02d}T09:00",
            counterparty=f"分页候选 {day}",
        ))
    client = TestClient(create_app(
        CashLedgerQueryService(cash_web_runtime.sessions, cash_web_runtime.workspace_id),
        mutation_service=mutation,
    ))

    response = client.get("/api/v1/cash-records", params={"query": "分页候选", "limit": 200})

    assert response.status_code == 200
    assert len(response.json()["items"]) == 20
    assert response.json()["next_cursor"]
    invalid = client.get("/api/v1/cash-records", params={"cursor": "not-a-cursor"})
    assert invalid.status_code == 400
    assert invalid.json()["error"]["code"] == "invalid_cursor"


@pytest.mark.parametrize("runtime_name", ["cash_web_runtime", "postgres_cash_web_runtime"])
def test_relation_candidate_api_filters_inclusive_date_range(request, runtime_name):
    from fastapi.testclient import TestClient
    from ft.application.web_queries import CashLedgerQueryService
    from ft.web.app import create_app

    runtime = request.getfixturevalue(runtime_name)
    _enable_cny(runtime, "日常账户")
    mutation = _service(runtime)
    for day in range(1, 9):
        mutation.create_record(_payload(
            occurred_at=f"2026-07-{day:02d}T09:00",
            counterparty=f"日期候选 {day}",
        ))
    client = TestClient(create_app(
        CashLedgerQueryService(runtime.sessions, runtime.workspace_id),
        mutation_service=mutation,
    ))

    response = client.get("/api/v1/cash-records", params={
        "query": "日期候选",
        "date_from": "2026-07-03",
        "date_to": "2026-07-05",
    })

    assert response.status_code == 200
    assert [item["counterparty"] for item in response.json()["items"]] == [
        "日期候选 5", "日期候选 4", "日期候选 3",
    ]
    invalid = client.get("/api/v1/cash-records", params={
        "date_from": "2026-07-06",
        "date_to": "2026-07-05",
    })
    assert invalid.status_code == 400
    assert invalid.json()["error"]["code"] == "invalid_filter"


@pytest.mark.parametrize("runtime_name", ["cash_web_runtime", "postgres_cash_web_runtime"])
def test_relation_candidate_api_applies_date_range_in_requested_timezone(request, runtime_name):
    from fastapi.testclient import TestClient
    from ft.application.web_queries import CashLedgerQueryService
    from ft.web.app import create_app
    runtime = request.getfixturevalue(runtime_name)
    _enable_cny(runtime, "日常账户")
    mutation = _service(runtime)
    mutation.create_record(_payload(
        occurred_at="2026-07-02T16:30",
        counterparty="本地日期 3 日",
    ))
    mutation.create_record(_payload(
        occurred_at="2026-07-03T16:00",
        counterparty="本地日期 4 日",
    ))
    client = TestClient(create_app(
        CashLedgerQueryService(runtime.sessions, runtime.workspace_id),
        mutation_service=mutation,
    ))

    response = client.get("/api/v1/cash-records", params={
        "query": "本地日期",
        "date_from": "2026-07-03",
        "date_to": "2026-07-03",
        "timezone": "Asia/Shanghai",
    })

    assert response.status_code == 200
    assert [item["counterparty"] for item in response.json()["items"]] == ["本地日期 3 日"]
