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


def test_cash_import_batch_preserves_idempotency_and_calibration(cash_web_runtime):
    _enable_cny(cash_web_runtime, "日常账户")
    from ft.adapters.relational.uow import RelationalUnitOfWork

    rows = [
        _payload(
            amount="-10.00", counterparty="来源一", note="备注一",
            source_type="fixture", record_id="batch-1",
            source_payload={"merchant": "来源一", "amount": "-10.00"},
        ),
        _payload(
            amount="-20.00", counterparty="来源二", note="备注二",
            source_type="fixture", record_id="batch-2",
            source_payload={"merchant": "来源二", "amount": "-20.00"},
        ),
    ]
    with RelationalUnitOfWork(cash_web_runtime.sessions, cash_web_runtime.workspace_id) as uow:
        first = uow.cashflows.merge_import_batch([("cash", row) for row in rows])
        uow.commit()

    assert [item["created"] for item in first] == [True, True]
    ids = [item["fact_id"] for item in first]

    service = _service(cash_web_runtime)
    service.update_record(ids[0], _payload(
        amount="-10.00", counterparty="人工校准", note="备注一",
    ))
    rows = [
        {**rows[0], "amount": "-11.00", "counterparty": "来源一新",
         "source_payload": {"merchant": "来源一新", "amount": "-11.00"}},
        rows[1],
    ]
    with RelationalUnitOfWork(cash_web_runtime.sessions, cash_web_runtime.workspace_id) as uow:
        second = uow.cashflows.merge_import_batch([("cash", row) for row in rows])
        uow.commit()

    assert [item["created"] for item in second] == [False, False]
    assert [item["source_changed"] for item in second] == [True, False]
    current = service.get_record(ids[0])["record"]
    assert current["amount"] == "-11.00"
    assert current["counterparty"] == "人工校准"


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

    result = service.delete_record(outgoing["record"]["id"], mode="delete_current_dissolve")
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


def test_import_key_change_does_not_silently_break_accepted_relation(cash_web_runtime, tmp_path):
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.application.statement_import import StatementImportService
    from ft.domain.application import RelationImpactRequired
    from ft.domain.imports import StatementImportCommand

    _enable_cny(cash_web_runtime, "日常账户")
    rows = [
        _payload(
            amount="-10.00", counterparty="渠道一", source_type="fixture", bill_source="fixture",
            record_id="relation-row-1", source_payload={"merchant": "渠道一", "amount": "-10.00"},
        ),
        _payload(
            amount="-10.00", counterparty="渠道二", source_type="fixture", bill_source="fixture",
            record_id="relation-row-2", source_payload={"merchant": "渠道二", "amount": "-10.00"},
        ),
    ]
    parser = _RowsParser(rows)
    path = Path(tmp_path) / "relation.csv"
    path.write_bytes(b"fixture")
    importer = StatementImportService(
        RelationalUnitOfWork(cash_web_runtime.sessions, cash_web_runtime.workspace_id),
        parser,
        enforce_account_currencies=True,
    )
    command = StatementImportCommand(source_path=str(path), source="fixture", currency="CNY")
    importer.import_statement(command)
    command_service = _service(cash_web_runtime)
    with RelationalUnitOfWork(cash_web_runtime.sessions, cash_web_runtime.workspace_id) as uow:
        first = uow.cashflows.find_active_by_source_identity("fixture", "relation-row-1")
        second = uow.cashflows.find_active_by_source_identity("fixture", "relation-row-2")
        uow.commit()
    relation = command_service.add_relation({
        "primary_fact_id": first["id"], "secondary_fact_id": second["id"],
        "kind": "payment_mirror", "status": "accepted",
    })
    assert relation["relations"]

    rows[1] = {
        **rows[1], "amount": "-11.00",
        "source_payload": {"merchant": "渠道二", "amount": "-11.00"},
    }
    with pytest.raises(RelationImpactRequired):
        importer.import_statement(command)

    with RelationalUnitOfWork(cash_web_runtime.sessions, cash_web_runtime.workspace_id) as uow:
        current = uow.cashflows.find_active_by_source_identity("fixture", "relation-row-2")
        relation_rows = uow.relations.list_for_facts([first["id"], second["id"]], active_only=True)
        uow.commit()
    assert current["amount"] == Decimal("-10.00")
    assert relation_rows[0]["status"] == "accepted"


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
    with service._uow as uow:
        pending_id = str(uow.relations.list_for_facts([outgoing["record"]["id"]], active_only=True)[0]["id"])
        uow.commit()
    cancelled = service.cancel_relation(pending_id)
    assert cancelled["status"] == "rejected"
    relinked = service.add_relation({
        "primary_fact_id": outgoing["record"]["id"], "secondary_fact_id": incoming["record"]["id"],
        "kind": "transfer_pair", "subtype": "ordinary_transfer", "status": "pending_review",
    })
    with service._uow as uow:
        relinked_row = uow.relations.list_for_facts([outgoing["record"]["id"]], active_only=True)[0]
        uow.commit()
    assert relinked_row["status"] == "pending_review"


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
    with service._uow as uow:
        relation_id = str(uow.relations.list_for_facts([first["record"]["id"]], active_only=True)[0]["id"])
        uow.commit()

    updated = service.update_relation(relation_id, {"kind": "refund_offset"})

    with service._uow as uow:
        current = uow.relations.get(relation_id)
        uow.commit()
    assert current["kind"] == "refund_offset"
    assert current["status"] == "pending_review"


def test_open_relation_cannot_be_changed_to_bilateral_payment_mirror(cash_web_runtime):
    from ft.domain.relations import RelationKind, RelationStatus

    _enable_cny(cash_web_runtime, "日常账户")
    service = _service(cash_web_runtime)
    record = service.create_record(_payload())
    with service._uow as uow:
        relation_id = uow.relations.add({
            "kind": RelationKind.REFUND_OFFSET.value,
            "primary_fact_id": int(record["record"]["id"]),
            "secondary_fact_id": None,
            "primary_fact_type": "cash",
            "secondary_fact_type": None,
            "anchor_fact_id": int(record["record"]["id"]),
            "status": RelationStatus.PENDING_REVIEW.value,
            "rule_id": "refund_offset.open_leg",
            "created_by": "system",
        })
        uow.commit()

    with pytest.raises(ValueError, match="两条流水"):
        service.update_relation(relation_id, {"kind": RelationKind.PAYMENT_MIRROR.value})

    with service._uow as uow:
        current = uow.relations.get(relation_id)
        uow.commit()
    assert current["kind"] == RelationKind.REFUND_OFFSET.value


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


def test_cash_web_routes_keep_relation_actions_user_safe(cash_web_runtime):
    from fastapi.testclient import TestClient
    from ft.application.web_queries import CashLedgerQueryService
    from ft.web.app import create_app

    _enable_cny(cash_web_runtime, "日常账户", "信用账户")
    mutation = _service(cash_web_runtime)
    client = TestClient(create_app(
        CashLedgerQueryService(cash_web_runtime.sessions, cash_web_runtime.workspace_id),
        mutation_service=mutation,
    ))

    first = mutation.create_record(_payload(
        account_name="日常账户", amount="-100", record_type="transfer_out",
        record_subtype="ordinary_transfer", counterparty="信用账户",
    ))["record"]
    second = mutation.create_record(_payload(
        account_name="信用账户", amount="100", record_type="transfer_in",
        record_subtype="ordinary_transfer", counterparty="日常账户",
    ))["record"]
    relation_body = {
        "primary_fact_id": first["id"], "secondary_fact_id": second["id"],
        "kind": "transfer_pair", "subtype": "ordinary_transfer", "status": "pending_review",
    }
    created = client.post("/api/v1/cash-relations", json=relation_body)
    assert created.status_code == 200
    assert created.json()["relations"][0]["status"] == "accepted"

    dissolved = client.post("/api/v1/cash-relations/dissolve", json={"fact_id": first["id"]})
    assert dissolved.status_code == 200
    assert dissolved.json()["relations"] == []

    third = mutation.create_record(_payload(
        account_name="日常账户", amount="-100", record_type="transfer_out",
        record_subtype="ordinary_transfer", counterparty="信用账户",
    ))["record"]
    fourth = mutation.create_record(_payload(
        account_name="信用账户", amount="100", record_type="transfer_in",
        record_subtype="ordinary_transfer", counterparty="日常账户",
    ))["record"]
    client.post("/api/v1/cash-relations", json={
        **relation_body, "primary_fact_id": third["id"], "secondary_fact_id": fourth["id"],
    })
    blocked = client.put(
        f"/api/v1/cash-records/{third['id']}",
        json=_payload(
            account_name="日常账户", amount="-101", record_type="transfer_out",
            record_subtype="ordinary_transfer", counterparty="信用账户",
        ),
    )
    assert blocked.status_code == 409
    assert blocked.json()["error"]["code"] == "relation_impact_required"
    confirmed = client.put(
        f"/api/v1/cash-records/{third['id']}",
        json=_payload(
            account_name="日常账户", amount="-101", record_type="transfer_out",
            record_subtype="ordinary_transfer", counterparty="信用账户",
            confirm_relation_impact=True,
        ),
    )
    assert confirmed.status_code == 200
    assert confirmed.json()["relations"] == []

    fifth = mutation.create_record(_payload(counterparty="删除一"))["record"]
    sixth = mutation.create_record(_payload(counterparty="删除二"))["record"]
    client.post("/api/v1/cash-relations", json={
        "primary_fact_id": fifth["id"], "secondary_fact_id": sixth["id"],
        "kind": "payment_mirror", "status": "accepted",
    })
    deleted = client.request(
        "DELETE", f"/api/v1/cash-records/{fifth['id']}", json={"mode": "delete_all"},
    )
    assert deleted.status_code == 200
    assert set(deleted.json()["deleted_fact_ids"]) == {str(fifth["id"]), str(sixth["id"])}


def test_cash_detail_contract_hides_import_and_delete_internals(cash_web_runtime):
    _enable_cny(cash_web_runtime, "日常账户")
    service = _service(cash_web_runtime)
    created = service.create_record(_payload(
        source_type="fixture", record_id="private-row",
        source_payload={"merchant": "来源商户"},
    ))

    record = created["record"]
    assert {"id", "amount", "currency", "occurred_at", "account_name", "counterparty",
            "counterparty_account", "record_type", "record_subtype", "category", "note",
            "source_type"}.issubset(record)
    assert not {
        "record_id", "created_at", "source_payload", "source_fingerprint", "manual_overrides",
        "deleted_at", "deleted_by", "delete_reason", "counterparty_account_attrs",
    } & record.keys()


def test_key_field_change_requires_explicit_split_and_keeps_relation_on_cancel(cash_web_runtime):
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
        "kind": "transfer_pair", "subtype": "ordinary_transfer", "status": "accepted",
    })
    relation_id = relation["relations"][0]["id"]

    from ft.domain.application import RelationImpactRequired

    with pytest.raises(RelationImpactRequired):
        service.update_record(outgoing["record"]["id"], _payload(
            amount="-101", record_type="transfer_out", record_subtype="ordinary_transfer", counterparty="信用账户",
        ))

    assert service.get_record(outgoing["record"]["id"])["record"]["amount"] == "-100"
    assert service.get_record(outgoing["record"]["id"])["relations"][0]["id"] == relation_id

    updated = service.update_record(outgoing["record"]["id"], _payload(
        amount="-101", record_type="transfer_out", record_subtype="ordinary_transfer", counterparty="信用账户",
        confirm_relation_impact=True,
    ))
    assert updated["record"]["amount"] == "-101"
    assert updated["relations"] == []
    assert service.get_record(incoming["record"]["id"])["record"]["amount"] == "100"


def test_dissolve_relation_group_keeps_all_member_records_independent(cash_web_runtime):
    _enable_cny(cash_web_runtime, "日常账户")
    service = _service(cash_web_runtime)
    records = [service.create_record(_payload(
        amount="-20", counterparty=f"同笔渠道 {index}", source_type="fixture", record_id=f"group-{index}",
    ))["record"] for index in range(3)]
    for left, right in zip(records, records[1:]):
        service.add_relation({
            "primary_fact_id": left["id"], "secondary_fact_id": right["id"],
            "kind": "payment_mirror", "status": "accepted",
        })

    dissolved = service.dissolve_relations(records[1]["id"])

    assert dissolved["record"]["id"] == records[1]["id"]
    assert dissolved["relations"] == []
    assert service.get_record(records[0]["id"])["relations"] == []
    assert service.get_record(records[2]["id"])["relations"] == []


def test_delete_related_record_supports_delete_all_or_current_and_dissolve(cash_web_runtime):
    _enable_cny(cash_web_runtime, "日常账户")
    service = _service(cash_web_runtime)
    first = service.create_record(_payload(amount="-20", counterparty="渠道一"))["record"]
    second = service.create_record(_payload(amount="-20", counterparty="渠道二"))["record"]
    service.add_relation({
        "primary_fact_id": first["id"], "secondary_fact_id": second["id"],
        "kind": "payment_mirror", "status": "accepted",
    })

    result = service.delete_record(first["id"], mode="delete_current_dissolve")
    assert result["deleted"] is True
    assert result["deleted_fact_ids"] == [str(first["id"])]
    assert service.get_record(second["id"])["relations"] == []

    third = service.create_record(_payload(amount="-20", counterparty="渠道三"))["record"]
    fourth = service.create_record(_payload(amount="-20", counterparty="渠道四"))["record"]
    service.add_relation({
        "primary_fact_id": third["id"], "secondary_fact_id": fourth["id"],
        "kind": "payment_mirror", "status": "accepted",
    })
    result = service.delete_record(third["id"], mode="delete_all")
    assert set(result["deleted_fact_ids"]) == {str(third["id"]), str(fourth["id"])}
    with pytest.raises(ValueError, match="找不到"):
        service.get_record(fourth["id"])


def test_delete_transfer_related_record_clears_projection_relation_before_fact(cash_web_runtime):
    _enable_cny(cash_web_runtime, "日常账户", "信用账户")
    service = _service(cash_web_runtime)
    outgoing = service.create_record(_payload(
        account_name="日常账户", amount="-20", record_type="transfer_out",
        record_subtype="ordinary_transfer", counterparty="信用账户",
    ))["record"]
    incoming = service.create_record(_payload(
        account_name="信用账户", amount="20", record_type="transfer_in",
        record_subtype="ordinary_transfer", counterparty="日常账户",
    ))["record"]
    service.add_relation({
        "primary_fact_id": outgoing["id"], "secondary_fact_id": incoming["id"],
        "kind": "transfer_pair", "subtype": "ordinary_transfer", "status": "accepted",
    })

    deleted = service.delete_record(outgoing["id"], mode="delete_current_dissolve")

    assert deleted["deleted"] is True
    assert service.get_record(incoming["id"])["relations"] == []


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
