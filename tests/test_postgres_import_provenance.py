"""015 formal identity replaces batch/raw provenance APIs."""
from test_postgres_adapter import _database


def test_existing_fact_targets_by_source_type_and_record_id():
    sessions, unit_of_work = _database()
    with unit_of_work(sessions, "workspace-a") as uow:
        uow.accounts.add_raw({"name": "Cash", "type": "cash", "currency": "CNY"})
        uow.cashflows.add("cash", {
            "occurred_at": "2026-07-17 09:00:00",
            "amount": "-1.20",
            "currency": "CNY",
            "account_name": "Cash",
            "source_type": "alipay",
            "record_id": "TXN-9",
            "source_payload": {"amount": "-1.20"},
            "category": "expense",
        })
        uow.commit()
    with unit_of_work(sessions, "workspace-a") as uow:
        found = uow.imports.existing_fact_targets(
            source_type="alipay", record_ids=["TXN-9", "missing"],
        )
        assert found["TXN-9"] == ("Cash", "CNY")
        assert "missing" not in found
        uow.commit()


def test_soft_deleted_identity_not_in_existing_targets():
    sessions, unit_of_work = _database()
    with unit_of_work(sessions, "workspace-a") as uow:
        uow.accounts.add_raw({"name": "Cash", "type": "cash", "currency": "CNY"})
        fid = uow.cashflows.add("cash", {
            "occurred_at": "2026-07-17 09:00:00",
            "amount": "-1.20",
            "currency": "CNY",
            "account_name": "Cash",
            "source_type": "alipay",
            "record_id": "TXN-DEL",
            "category": "expense",
        })
        uow.fact_deletions.logical_delete_cash(fid, actor="t", reason="x")
        uow.commit()
    with unit_of_work(sessions, "workspace-a") as uow:
        found = uow.imports.existing_fact_targets(
            source_type="alipay", record_ids=["TXN-DEL"],
        )
        assert found == {}
        uow.commit()
