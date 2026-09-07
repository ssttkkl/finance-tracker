"""Contract checks for the HTTP surface replacing user-facing CLI commands."""
from contextvars import ContextVar
from decimal import Decimal

from fastapi.testclient import TestClient
import pytest


class _Access:
    def __init__(self, role="admin"):
        self.role = role

    def require_context(self, _token, _roles):
        return "workspace-api", self.role, "user-api"

    def logout(self, _token):
        return None


class _Accounts:
    def __init__(self, calls):
        self.calls = calls

    def create_account(self, name, type_, currency=None):
        from ft.domain.accounts import AccountDTO, AccountResult

        self.calls.append(("account.create", name, type_, currency))
        return AccountResult.success(AccountDTO(name, type_, True, (currency,) if currency else ()))

    def rename_account(self, old_name, new_name):
        from ft.domain.accounts import AccountDTO, AccountResult

        self.calls.append(("account.rename", old_name, new_name))
        return AccountResult.success(AccountDTO(new_name, "cash"))

    def set_active(self, name, active):
        from ft.domain.accounts import AccountDTO, AccountResult

        self.calls.append(("account.active", name, active))
        return AccountResult.success(AccountDTO(name, "cash", active))

    def delete_account(self, name):
        from ft.domain.accounts import AccountDTO, AccountResult

        self.calls.append(("account.delete", name))
        return AccountResult.success(AccountDTO(name, "cash", False))


class _Cashflow:
    def __init__(self, calls):
        self.calls = calls

    def add_manual_transaction(self, **kwargs):
        from ft.domain.cashflow import CashflowResult

        self.calls.append(("cashflow.add", kwargs))
        return CashflowResult.success(row={"amount": format(kwargs["amount"], "f")})

    def checkin_balance(self, **kwargs):
        from ft.domain.cashflow import CashflowResult

        self.calls.append(("cashflow.checkin", kwargs))
        return CashflowResult.success(row={"amount": "0"})


class _Transfers:
    def __init__(self, calls):
        self.calls = calls

    def transfer(self, **kwargs):
        from ft.domain.cashflow import CashflowResult

        self.calls.append(("cashflow.transfer", kwargs))
        return CashflowResult.success(rows=[])


class _Operations:
    def __init__(self):
        self.calls = []
        self.accounts = _Accounts(self.calls)
        self.cashflow = _Cashflow(self.calls)
        self.transfers = _Transfers(self.calls)
        self.queries = _Queries(self.calls)
        self.relations = _Relations(self.calls)
        self.funding = _Funding(self.calls)
        self.investments = _Investments(self.calls)
        self.sync_service = _Sync(self.calls)
        self.projection = _Projection(self.calls)

    def legacy_accounts(self):
        return self.accounts

    def legacy_cashflow(self):
        return self.cashflow

    def legacy_transfers(self):
        return self.transfers

    def legacy_queries(self):
        return self.queries

    def legacy_relations(self):
        return self.relations

    def legacy_funding_relations(self):
        return self.funding

    def legacy_investments(self):
        return self.investments

    def legacy_sync(self):
        return self.sync_service

    def legacy_projection(self):
        return self.projection


class _Queries:
    def __init__(self, calls):
        self.calls = calls

    def list_accounts(self):
        self.calls.append(("query.accounts",))
        return {"items": []}

    def list_transactions(self, **kwargs):
        self.calls.append(("query.transactions", kwargs))
        return {"items": []}

    def report(self, **kwargs):
        self.calls.append(("query.report", kwargs))
        return {"accounts": []}


class _Relations:
    def __init__(self, calls):
        self.calls = calls

    def list_pending(self, **kwargs):
        self.calls.append(("relations.pending", kwargs))
        return []

    def check(self, **kwargs):
        from ft.domain.application import OperationResult

        self.calls.append(("relations.check", kwargs))
        return OperationResult(ok=True, message="checked")

    def accept(self, relation_id, **kwargs):
        from ft.domain.application import OperationResult

        self.calls.append(("relations.accept", relation_id, kwargs))
        return OperationResult(ok=True, message="accepted")

    def reject(self, relation_id, **kwargs):
        from ft.domain.application import OperationResult

        self.calls.append(("relations.reject", relation_id, kwargs))
        return OperationResult(ok=True, message="rejected")

    def logical_delete_cash(self, fact_id, **kwargs):
        from ft.domain.application import OperationResult

        self.calls.append(("fact.delete", fact_id, kwargs))
        return OperationResult(ok=True, message="deleted")


class _Funding:
    def __init__(self, calls):
        self.calls = calls

    def list_pending(self):
        self.calls.append(("funding.pending",))
        return []

    def scan(self):
        self.calls.append(("funding.scan",))
        return []

    def confirm(self, relation_id, **kwargs):
        self.calls.append(("funding.confirm", relation_id, kwargs))
        return {"id": relation_id, "status": "accepted"}

    def reject(self, relation_id, **kwargs):
        self.calls.append(("funding.reject", relation_id, kwargs))
        return {"id": relation_id, "status": "rejected"}


class _Investments:
    def __init__(self, calls):
        self.calls = calls

    def buy(self, *args):
        from ft.domain.application import OperationResult

        self.calls.append(("investment.buy", args))
        return OperationResult(ok=True, message="bought")


class _Sync:
    def __init__(self, calls):
        self.calls = calls

    def sync(self, **kwargs):
        from ft.domain.application import OperationResult

        self.calls.append(("sync", kwargs))
        return OperationResult(ok=True, message="synced")


class _Projection:
    def __init__(self, calls):
        self.calls = calls

    def status(self):
        self.calls.append(("projection.status",))
        return {"availability": "ready"}

    def rebuild(self):
        self.calls.append(("projection.rebuild",))
        return {"availability": "ready"}


def _client(operations, role="admin"):
    from ft.web.app import create_app

    return TestClient(create_app(
        object(),
        access_service=_Access(role),
        workspace_context=ContextVar("test_workspace", default=None),
        operations_service=operations,
    ))


def test_account_and_cashflow_commands_have_http_replacements(monkeypatch):
    operations = _Operations()
    client = _client(operations)

    created = client.post("/api/v1/accounts", json={"name": "现金", "type": "cash", "currency": "CNY"})
    assert created.status_code == 201
    assert created.json()["account"]["name"] == "现金"

    added = client.post("/api/v1/cashflow/add", json={
        "amount": "00032.50",
        "counterparty": "早餐",
        "account": "现金",
        "currency": "CNY",
    })
    assert added.status_code == 201
    assert operations.calls[-1][1]["amount"] == Decimal("32.50")

    transferred = client.post("/api/v1/cashflow/transfer", json={
        "from_account": "现金",
        "to_account": "现金",
        "amount": "1.00",
        "from_currency": "CNY",
        "to_currency": "CNY",
    })
    assert transferred.status_code == 201
    assert operations.calls[-1][0] == "cashflow.transfer"


def test_viewer_is_denied_before_http_replacement_mutations_run():
    operations = _Operations()
    response = _client(operations, role="viewer").post("/api/v1/cashflow/add", json={
        "amount": "1.00",
        "counterparty": "不可写入",
        "account": "现金",
        "currency": "CNY",
    })

    assert response.status_code == 403
    assert response.json()["error"]["code"] == "workspace_forbidden"
    assert operations.calls == []


def test_cashflow_replacement_rejects_numeric_amounts_to_preserve_decimal_contract():
    operations = _Operations()
    response = _client(operations).post("/api/v1/cashflow/add", json={
        "amount": 1.2,
        "counterparty": "浮点数",
        "account": "现金",
        "currency": "CNY",
    })

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "amount_must_be_decimal_string"


def test_http_replacement_matrix_covers_queries_relations_investments_and_fact_delete():
    operations = _Operations()
    client = _client(operations)

    assert client.get("/api/v1/queries/accounts").status_code == 200
    assert client.get("/api/v1/queries/transactions?limit=10").status_code == 200
    assert client.get("/api/v1/reports/finance?month=2026-08").status_code == 200
    assert client.get("/api/v1/relations/pending").status_code == 200
    assert client.post("/api/v1/relations/check", json={"fact_ids": ["cash-1"]}).status_code == 200
    assert client.post("/api/v1/relations/relation-1/accept", json={"reason": "receipt"}).status_code == 200
    assert client.post("/api/v1/relations/relation-1/reject", json={"reason": "not-a-match"}).status_code == 200
    assert client.request("DELETE", "/api/v1/cash-facts/cash-1", json={"reason": "duplicate"}).status_code == 200
    assert client.get("/api/v1/funding-relations/pending").status_code == 200
    assert client.post("/api/v1/funding-relations/scan").status_code == 200
    assert client.post("/api/v1/funding-relations/8/confirm", json={"reason": "receipt"}).status_code == 200
    assert client.post("/api/v1/funding-relations/8/reject", json={"reason": "not-a-match"}).status_code == 200
    assert client.post("/api/v1/investment-events", json={
        "operation": "buy", "ticker": "AAPL.US", "shares": "1", "price": "2",
        "commission": "0", "account": "IBKR", "currency": "USD",
    }).status_code == 201

    assert ("query.accounts",) in operations.calls
    assert ("relations.check", {"seed_fact_ids": ["cash-1"], "seed_batch_id": None, "trigger": "manual_range"}) in operations.calls
    assert any(call[0] == "investment.buy" for call in operations.calls)


def test_admin_only_operations_are_available_without_client_credentials():
    operations = _Operations()
    client = _client(operations)

    assert client.get("/api/v1/operations/projections").status_code == 200
    assert client.post("/api/v1/operations/projections/rebuild").status_code == 200
    sync = client.post("/api/v1/operations/sync", json={
        "source": "unsupported", "account": "Broker",
    })
    assert sync.status_code == 400
    assert sync.json()["error"]["code"] == "sync_configuration_invalid"
    assert not any(call[0] == "sync" for call in operations.calls)


@pytest.mark.parametrize("runtime_name", ["cash_web_runtime", "postgres_cash_web_runtime"])
def test_api_mutations_preserve_decimal_and_transaction_boundary(request, runtime_name):
    from contextvars import ContextVar

    from ft.web.app import WorkspaceServices, create_app

    cash_web_runtime = request.getfixturevalue(runtime_name)
    sessions = cash_web_runtime.sessions
    workspace_id = cash_web_runtime.workspace_id
    workspace_context = ContextVar("api_workspace", default=None)
    user_context = ContextVar("api_user", default=None)

    class Access:
        def require_context(self, _token, _roles):
            return workspace_id, "admin", "api-user"

    service = WorkspaceServices(
        sessions, workspace_context, user_var=user_context,
    )
    app = create_app(
        service,
        access_service=Access(),
        workspace_context=workspace_context,
        user_context=user_context,
        mutation_service=service,
        category_service=service,
        classification_service=service,
        operations_service=service,
    )

    client = TestClient(app)
    created = client.post("/api/v1/accounts", json={
        "name": "API现金", "type": "cash", "currency": "CNY",
    })
    assert created.status_code == 201

    invalid = client.post("/api/v1/cashflow/add", json={
        "amount": 1.2, "counterparty": "浮点数", "account": "API现金", "currency": "CNY",
    })
    assert invalid.status_code == 400

    written = client.post("/api/v1/cashflow/add", json={
        "amount": "-12.340000000000000001", "counterparty": "API测试",
        "account": "API现金", "currency": "CNY", "date": "2026-08-01 09:00:00",
    })
    assert written.status_code == 201
    assert written.json()["row"]["amount"] == "-12.340000000000000001"

    from sqlalchemy import select
    from ft.adapters.relational.models import CashTransactionModel

    with sessions() as session:
        rows = list(session.scalars(select(CashTransactionModel).where(
            CashTransactionModel.workspace_id == workspace_id,
            CashTransactionModel.counterparty == "API测试",
        )))
    assert len(rows) == 1
    assert str(rows[0].amount) == "-12.340000000000000001"
