from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor

from sqlalchemy import func, select
import pytest


class _SourceParser:
    def parse_source_rows(self, _command):
        return [{
            "record_id": "dual-row-1", "bill_source": "alipay", "source_type": "alipay",
            "payment_method": "账户余额", "currency": "CNY", "amount": "-12.50",
            "date": "2026-08-12", "counterparty": "咖啡店", "counterparty_account": "",
            "category": "expense", "record_type": "consumption", "record_subtype": "not_applicable",
            "note": "消费", "_source_payload": {"原始": "expense"},
        }]


@pytest.mark.parametrize("runtime_name", ["cash_web_runtime", "postgres_cash_web_runtime"])
def test_cash_import_idempotency_is_equivalent_on_sqlite_and_postgres(request, runtime_name):
    from ft.adapters.relational import ensure_workspace
    from ft.adapters.relational.models import AccountModel, CashTransactionModel
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.application.cash_projections import CashProjectionService

    runtime = request.getfixturevalue(runtime_name)
    workspace_id = f"cash-import-dual-{runtime_name}"
    ensure_workspace(runtime.sessions, workspace_id)
    with runtime.sessions.begin() as session:
        CashProjectionService.initialize_in_session(session, workspace_id)
        session.add(AccountModel(
            workspace_id=workspace_id,
            name="支付宝余额",
            type="cash",
            currencies=["CNY"],
            active=True,
            metadata_json={},
        ))
    service = CashLedgerCommandService(
        runtime.sessions,
        workspace_id,
        parser=_SourceParser(),
    )

    scan = service.scan_import(b"dual-backend-statement", filename="statement.csv")
    mapping = [{
        "group_id": scan["groups"][0]["group_id"],
        "account_id": scan["accounts"][0]["id"],
    }]
    kwargs = {
        "source": "",
        "currency": None,
        "filename": "statement.csv",
        "preview_digest": scan["digest"],
        "preview_channel": scan["channel"],
        "mapping": mapping,
        "idempotency_key": f"dual-backend-{runtime_name}",
    }
    first = service.commit_import(b"dual-backend-statement", **kwargs)
    second = service.commit_import(b"dual-backend-statement", **kwargs)

    assert second == first
    with runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel).where(
            CashTransactionModel.workspace_id == workspace_id,
        )) == 1


def test_postgres_cash_import_confirmation_serializes_same_idempotency_key(postgres_cash_web_runtime):
    from ft.adapters.relational import ensure_workspace
    from ft.adapters.relational.models import AccountModel, CashTransactionModel
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.application.cash_projections import CashProjectionService

    runtime = postgres_cash_web_runtime
    workspace_id = "cash-import-postgres-concurrent"
    ensure_workspace(runtime.sessions, workspace_id)
    with runtime.sessions.begin() as session:
        CashProjectionService.initialize_in_session(session, workspace_id)
        session.add(AccountModel(
            workspace_id=workspace_id,
            name="支付宝余额",
            type="cash",
            currencies=["CNY"],
            active=True,
            metadata_json={},
        ))

    content = b"dual-backend-concurrent-statement"
    parser = _SourceParser()
    scanner = CashLedgerCommandService(runtime.sessions, workspace_id, parser=parser)
    scan = scanner.scan_import(content, filename="statement.csv")
    mapping = [{
        "group_id": scan["groups"][0]["group_id"],
        "account_id": scan["accounts"][0]["id"],
    }]
    kwargs = {
        "source": "",
        "currency": None,
        "filename": "statement.csv",
        "preview_digest": scan["digest"],
        "preview_channel": scan["channel"],
        "mapping": mapping,
        "idempotency_key": "postgres-concurrent-commit",
    }

    def confirm():
        return CashLedgerCommandService(
            runtime.sessions, workspace_id, parser=_SourceParser(),
        ).commit_import(content, **kwargs)

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = [future.result() for future in [pool.submit(confirm), pool.submit(confirm)]]

    assert results[0] == results[1]
    with runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel).where(
            CashTransactionModel.workspace_id == workspace_id,
        )) == 1
