import pytest

from test_postgres_adapter import _database


def test_import_batch_is_idempotent_by_workspace_kind_and_digest():
    sessions, unit_of_work = _database()

    with unit_of_work(sessions, "workspace-a") as uow:
        uow.accounts.add_raw({"name": "Cash", "type": "cash", "currency": "CNY"})
        first = uow.imports.start_batch(
            source_kind="alipay",
            source_digest="sha256:statement-a",
            source_ref="alipay.csv",
            target_account_name="Cash",
            target_account_currency="CNY",
        )
        second = uow.imports.start_batch(
            source_kind="alipay",
            source_digest="sha256:statement-a",
            source_ref="renamed.csv",
            target_account_name="Cash",
            target_account_currency="CNY",
        )
        uow.imports.complete_batch(first)
        uow.commit()

    assert first == second
    with unit_of_work(sessions, "workspace-a") as uow:
        batch = uow.imports.get_batch(first)
        uow.commit()
    assert batch["status"] == "completed"
    assert batch["source_ref"] == "alipay.csv"


def test_raw_files_records_and_revisions_are_append_only_and_scoped():
    sessions, unit_of_work = _database()

    with unit_of_work(sessions, "workspace-a") as uow:
        uow.accounts.add_raw({"name": "Cash", "type": "cash", "currency": "CNY"})
        batch_id = uow.imports.start_batch(
            source_kind="alipay",
            source_digest="sha256:statement-a",
            source_ref="alipay.csv",
            target_account_name="Cash",
            target_account_currency="CNY",
        )
        raw_file_id = uow.imports.add_raw_file(
            batch_id=batch_id,
            source_path="records/cash/2026-07.csv",
            content_digest="sha256:file-a",
            size_bytes=128,
            media_type="text/csv",
        )
        record_ids = uow.imports.add_raw_records(
            batch_id=batch_id,
            raw_file_id=raw_file_id,
            source_type="cash",
            records=[
                {"source_identity": "cash:2026-07.csv:2", "source_line": 2, "payload": {"amount": "-1.20"}},
                {"source_identity": "cash:2026-07.csv:3", "source_line": 3, "payload": {"amount": "2.00"}},
            ],
        )
        transaction_id = uow.cashflows.add("cash", {
            "occurred_at": "2026-07-17 09:00:00", "amount": "-1.20", "currency": "CNY",
            "account_name": "Cash", "raw_record_id": record_ids[0],
        })
        revision_id = uow.imports.append_revision(
            cash_transaction_id=transaction_id,
            before={"category": "expense"},
            after={"category": "dining"},
            actor_type="statement_import",
            reason="canonicalize category",
        )
        uow.commit()

    with unit_of_work(sessions, "workspace-a") as uow:
        assert uow.imports.list_raw_records(batch_id) == [
            {"id": record_ids[0], "source_identity": "cash:2026-07.csv:2", "source_line": 2, "payload": {"amount": "-1.20"}},
            {"id": record_ids[1], "source_identity": "cash:2026-07.csv:3", "source_line": 3, "payload": {"amount": "2.00"}},
        ]
        assert uow.imports.list_revisions(cash_transaction_id=transaction_id) == [{
            "id": revision_id,
            "before": {"category": "expense"},
            "after": {"category": "dining"},
            "actor_type": "statement_import",
            "reason": "canonicalize category",
        }]
        with pytest.raises(ValueError, match="immutable"):
            uow.imports.replace_raw_record(record_ids[0], {"amount": "999"})
        uow.commit()

    with unit_of_work(sessions, "workspace-b") as uow:
        assert uow.imports.list_raw_records(batch_id) == []
        assert uow.imports.list_revisions(cash_transaction_id=transaction_id) == []
        uow.commit()


def test_raw_record_identity_is_idempotent_within_workspace():
    sessions, unit_of_work = _database()
    record = {"source_identity": "cash:file:2", "source_line": 2, "payload": {"amount": "1"}}

    with unit_of_work(sessions, "workspace-a") as uow:
        uow.accounts.add_raw({"name": "Cash", "type": "cash", "currency": "CNY"})
        batch_id = uow.imports.start_batch(
            source_kind="alipay", source_digest="sha256:a", source_ref="alipay.csv",
            target_account_name="Cash", target_account_currency="CNY",
        )
        first = uow.imports.add_raw_records(
            batch_id=batch_id, raw_file_id=None, source_type="cash", records=[record]
        )
        second = uow.imports.add_raw_records(
            batch_id=batch_id, raw_file_id=None, source_type="cash", records=[record]
        )
        uow.commit()

    assert first == second


def test_import_provenance_rolls_back_with_unit_of_work():
    sessions, unit_of_work = _database()
    with pytest.raises(RuntimeError, match="boom"):
        with unit_of_work(sessions, "workspace-a") as uow:
            uow.accounts.add_raw({"name": "Cash", "type": "cash", "currency": "CNY"})
            uow.imports.start_batch(
                source_kind="alipay", source_digest="sha256:a", source_ref="alipay.csv",
                target_account_name="Cash", target_account_currency="CNY",
            )
            raise RuntimeError("boom")

    with unit_of_work(sessions, "workspace-a") as uow:
        assert uow.imports.list_batches() == []
        uow.commit()
