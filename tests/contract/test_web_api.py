from fastapi.testclient import TestClient
import pytest

def _client(runtime):
    from ft.application.cash_projections import CashProjectionService
    from ft.application.web_queries import CashLedgerQueryService
    from ft.web.app import create_app
    CashProjectionService(runtime.sessions,runtime.workspace_id).rebuild()
    return TestClient(create_app(CashLedgerQueryService(runtime.sessions,runtime.workspace_id)))

def test_projection_api_contract_and_old_routes_are_absent(cash_web_runtime):
    client=_client(cash_web_runtime)
    page=client.get("/api/v1/cash-projections?limit=2"); accounts=client.get("/api/v1/accounts?view=cash")
    assert page.status_code==200 and page.json()["projection_version"]==1
    assert page.json()["items"][0]["projection_id"]=="cash:1003" and isinstance(page.json()["items"][0]["amount"],str)
    assert page.json()["items"][0]["source_types"] == ["fixture"]
    assert page.json()["filter_options"] == {"categories": sorted(["餐饮", "日用", "收入"]), "currencies": ["CNY"]}
    assert page.json()["monthly_summaries"] == [{"month": "2026-07", "currencies": [{"currency": "CNY", "income": "2000", "expense": "-112.5"}]}]
    assert [x["name"] for x in accounts.json()["items"]]==["日常账户","信用账户"]
    assert client.get("/api/v1/cash-transactions").status_code==404
    assert client.get("/api/v1/evidence/cash/1003").status_code==404


@pytest.mark.parametrize("runtime_name", ["cash_web_runtime", "postgres_cash_web_runtime"])
def test_projection_api_returns_member_sources_in_member_order_without_duplicates(request, runtime_name):
    from datetime import datetime
    from decimal import Decimal
    from zoneinfo import ZoneInfo

    from ft.adapters.relational.models import CashTransactionModel, TransactionRelationModel

    runtime = request.getfixturevalue(runtime_name)
    with runtime.sessions.begin() as session:
        session.add(CashTransactionModel(
            id=1004, workspace_id=runtime.workspace_id, account_id=101,
            occurred_at=datetime(2026, 7, 4, tzinfo=ZoneInfo("Asia/Shanghai")), amount=Decimal("3"),
            currency="CNY", counterparty="咖啡店", category="退款", source_type="bank", record_id="cash-004",
        ))
        session.add(TransactionRelationModel(
            workspace_id=runtime.workspace_id, kind="refund_offset", subtype="",
            primary_fact_id=1003, secondary_fact_id=1004, primary_fact_type="cash", secondary_fact_type="cash",
            ordered_fact_a=1003, ordered_fact_b=1004, anchor_fact_id=1004, status="accepted",
        ))

    payload = _client(runtime).get("/api/v1/cash-projections", params={"limit": 50}).json()
    projection = next(item for item in payload["items"] if item["projection_id"] == "cash:1003")

    assert projection["source_type"] == "fixture"
    assert projection["source_types"] == ["fixture", "bank"]


def test_projection_api_returns_visible_internal_transfer(cash_web_runtime):
    from decimal import Decimal
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.application.cashflow import TransferService

    client = _client(cash_web_runtime)
    result = TransferService(RelationalUnitOfWork(cash_web_runtime.sessions, cash_web_runtime.workspace_id)).transfer(
        from_name="日常账户", to_name="信用账户", amount=Decimal("20"),
        from_currency="CNY", to_currency="USD", to_amount=Decimal("14"), date="2026-07-05", time_str="10:00:00",
    )
    assert result.ok is True

    response = client.get("/api/v1/cash-projections", params={"economic_type": "internal_transfer"})
    payload = response.json()

    assert response.status_code == 200
    assert len(payload["items"]) == 1
    item = payload["items"][0]
    assert item["economic_type"] == "internal_transfer"
    assert item["amount"] == "0"
    assert item["transfer"] == {
        "from_account": {"id": 101, "name": "日常账户", "type": "cash", "active": True},
        "from_amount": "-20", "from_currency": "CNY",
        "to_account": {"id": 102, "name": "信用账户", "type": "loan", "active": True},
        "to_amount": "14", "to_currency": "USD",
    }

def test_projection_api_has_stable_errors(cash_web_runtime):
    import base64
    import json
    from ft.application.web_queries import CashLedgerQueryService
    from ft.web.app import create_app
    unbuilt=TestClient(create_app(CashLedgerQueryService(cash_web_runtime.sessions,cash_web_runtime.workspace_id)))
    assert unbuilt.get("/api/v1/cash-projections").json()["error"]["code"]=="projection.unavailable"
    client=_client(cash_web_runtime)
    assert client.get("/api/v1/cash-projections?cursor=broken").json()["error"]["code"]=="invalid_cursor"
    for payload in (b"[]", b'"cursor"', b"0", b"true", b"null"):
        cursor = base64.urlsafe_b64encode(payload).decode().rstrip("=")
        response = client.get("/api/v1/cash-projections", params={"cursor": cursor})
        assert response.status_code == 400
        assert response.json()["error"]["code"] == "invalid_cursor"
    valid_cursor = client.get("/api/v1/cash-projections", params={"limit": "1"}).json()["next_cursor"]
    payload = json.loads(base64.urlsafe_b64decode(valid_cursor + "=" * (-len(valid_cursor) % 4)))
    for field, value in (("v", True), ("version", True), ("occurred_at", 1), ("occurred_at", "2026-07-03"), ("projection_id", [])):
        invalid_payload = {**payload, field: value}
        cursor = base64.urlsafe_b64encode(json.dumps(invalid_payload).encode()).decode().rstrip("=")
        response = client.get("/api/v1/cash-projections", params={"cursor": cursor})
        assert response.status_code == 400
        assert response.json()["error"]["code"] == "invalid_cursor"
    assert client.get("/api/v1/evidence/cash-projections/cash:999").status_code==404


def test_projection_evidence_api_returns_the_projection_envelope(cash_web_runtime):
    client = _client(cash_web_runtime)

    response = client.get("/api/v1/evidence/cash-projections/cash:1003")

    assert response.status_code == 200
    payload = response.json()
    assert payload["projection"]["projection_id"] == "cash:1003"
    assert payload["root_record"]["record_id"] == "cash-003"
    assert payload["root_record"]["source_snapshot"] == {"merchant": "咖啡店"}
    assert payload["members"][0]["roles"] == ["root"]
    assert payload["accepted_relations"] == []
    assert payload["inactive_relation_hints"] == []
    assert payload["refund_timeline"] == []


def test_projection_evidence_api_has_not_found_unavailable_and_missing_snapshot_contracts(cash_web_runtime):
    from ft.application.web_queries import CashLedgerQueryService
    from ft.web.app import create_app

    unbuilt = TestClient(create_app(CashLedgerQueryService(cash_web_runtime.sessions, cash_web_runtime.workspace_id)))
    assert unbuilt.get("/api/v1/evidence/cash-projections/cash:1003").json()["error"]["code"] == "projection.unavailable"

    client = _client(cash_web_runtime)
    assert client.get("/api/v1/evidence/cash-projections/cash:999").json()["error"]["code"] == "not_found"
    payload = client.get("/api/v1/evidence/cash-projections/cash:1002").json()
    assert payload["root_record"]["source_snapshot"] is None


def _add_projection_rows(runtime, count=3):
    from datetime import datetime, timedelta
    from decimal import Decimal
    from zoneinfo import ZoneInfo

    from ft.adapters.relational.models import CashTransactionModel

    with runtime.sessions.begin() as session:
        for offset in range(count):
            identifier = 1100 + offset
            session.add(CashTransactionModel(
                id=identifier,
                workspace_id=runtime.workspace_id,
                account_id=101,
                occurred_at=datetime(2026, 7, 4, 9, tzinfo=ZoneInfo("Asia/Shanghai")) + timedelta(days=offset),
                amount=Decimal("-10.00") - offset,
                currency="CNY",
                counterparty=f"分页商户{offset}",
                category="餐饮",
                source_type="fixture",
                record_id=f"cash-page-{offset}",
            ))


@pytest.mark.parametrize("runtime_name", ["cash_web_runtime", "postgres_cash_web_runtime"])
def test_projection_api_binds_all_filters_and_paginates_three_pages_without_gaps(
    request, runtime_name,
):
    runtime = request.getfixturevalue(runtime_name)
    _add_projection_rows(runtime)
    client = _client(runtime)

    params = {
        "date_from": "2026-07-04",
        "date_to": "2026-07-06",
        "account_id": "101",
        "counterparty": "分页商户",
        "category": "餐饮",
        "currency": "CNY",
        "amount_min": "-12.00",
        "amount_max": "-10.00",
        "economic_type": "expense",
        "composition": "single",
        "limit": "1",
    }
    pages = []
    page = client.get("/api/v1/cash-projections", params=params)
    for _ in range(3):
        assert page.status_code == 200
        pages.extend(page.json()["items"])
        cursor = page.json()["next_cursor"]
        page = client.get("/api/v1/cash-projections", params={**params, "cursor": cursor})

    assert [item["projection_id"] for item in pages] == [
        "cash:1102", "cash:1101", "cash:1100",
    ]
    assert len({item["projection_id"] for item in pages}) == 3
    assert all(isinstance(item["amount"], str) for item in pages)
    assert client.get(
        "/api/v1/cash-projections",
        params={**params, "cursor": client.get("/api/v1/cash-projections", params=params).json()["next_cursor"], "category": "日用"},
    ).json()["error"]["code"] == "invalid_cursor"


@pytest.mark.parametrize("runtime_name", ["cash_web_runtime", "postgres_cash_web_runtime"])
def test_projection_api_counterparty_filter_matches_note(request, runtime_name):
    from datetime import datetime
    from decimal import Decimal
    from zoneinfo import ZoneInfo

    from ft.adapters.relational.models import CashTransactionModel
    from ft.application.cash_projections import CashProjectionService

    runtime = request.getfixturevalue(runtime_name)
    with runtime.sessions.begin() as session:
        session.add(CashTransactionModel(
            id=1300, workspace_id=runtime.workspace_id, account_id=101,
            occurred_at=datetime(2026, 7, 6, 9, tzinfo=ZoneInfo("Asia/Shanghai")), amount=Decimal("-8"),
            currency="CNY", counterparty="其他商户", note="备注命中交易信息筛选", category="餐饮",
            source_type="fixture", record_id="cash-note-filter",
        ))
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()

    response = _client(runtime).get("/api/v1/cash-projections", params={"counterparty": "备注命中", "limit": "50"})

    assert response.status_code == 200
    assert [item["record_id"] for item in response.json()["items"]] == ["cash-note-filter"]


@pytest.mark.parametrize("runtime_name", ["cash_web_runtime", "postgres_cash_web_runtime"])
def test_projection_api_uses_shanghai_day_boundaries_and_rejects_old_cursor(request, runtime_name):
    from datetime import datetime
    from decimal import Decimal
    from zoneinfo import ZoneInfo

    from ft.adapters.relational.models import CashTransactionModel
    from ft.application.cash_projections import CashProjectionService

    runtime = request.getfixturevalue(runtime_name)
    with runtime.sessions.begin() as session:
        session.add_all((
            CashTransactionModel(
                id=1200, workspace_id=runtime.workspace_id, account_id=101,
                occurred_at=datetime(2026, 7, 1, 0, 0, tzinfo=ZoneInfo("Asia/Shanghai")),
                amount=Decimal("-1.20"), currency="CNY", counterparty="边界开始",
                category="餐饮", source_type="fixture", record_id="cash-boundary-start",
            ),
            CashTransactionModel(
                id=1201, workspace_id=runtime.workspace_id, account_id=101,
                occurred_at=datetime(2026, 7, 1, 23, 59, 59, tzinfo=ZoneInfo("Asia/Shanghai")),
                amount=Decimal("-1.30"), currency="CNY", counterparty="边界结束",
                category="餐饮", source_type="fixture", record_id="cash-boundary-end",
            ),
        ))
    client = _client(runtime)
    first = client.get("/api/v1/cash-projections", params={"limit": "1"})
    old_cursor = first.json()["next_cursor"]
    day = client.get("/api/v1/cash-projections", params={"date_from": "2026-07-01", "date_to": "2026-07-01"})
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    stale = client.get("/api/v1/cash-projections", params={"limit": "1", "cursor": old_cursor})

    assert [item["projection_id"] for item in day.json()["items"]] == ["cash:1201", "cash:1001", "cash:1200"]
    assert {
        item["projection_id"]: item["amount"] for item in day.json()["items"]
    } == {"cash:1201": "-1.3", "cash:1001": "2000", "cash:1200": "-1.2"}
    assert stale.status_code == 409
    assert stale.json() == {"error": {"code": "projection.updated", "message": "账本已更新，请刷新列表。"}}


class _StorageFailureService:
    def __init__(self, code):
        from ft.adapters.relational.runtime import StorageError

        self._error = StorageError(code, "sqlite+pysqlite:////private/secret.db?token=hidden")

    def list_accounts(self):
        raise self._error

    def list_cash_projections(self, **_values):
        raise self._error

    def get_projection_evidence(self, _projection_id):
        raise self._error


class _DBAPIFailureService:
    def _raise(self):
        from sqlalchemy.exc import OperationalError

        raise OperationalError(
            "SELECT private_ledger WHERE token = :token",
            {"token": "secret-token"},
            RuntimeError("database is locked"),
        )

    def list_accounts(self):
        self._raise()

    def list_cash_projections(self, **_values):
        self._raise()

    def get_projection_evidence(self, _projection_id):
        self._raise()


@pytest.mark.parametrize("code", [
    "storage.schema", "storage.workspace", "storage.readonly", "storage.busy", "storage.connect", "storage.config",
])
def test_projection_api_storage_failures_are_stable_redacted_json(code):
    from ft.web.app import _STORAGE_ERROR_MESSAGES, create_app

    client = TestClient(create_app(_StorageFailureService(code)))
    response = client.get("/api/v1/cash-projections")

    assert response.status_code == 503
    assert response.json() == {"error": {"code": code, "message": _STORAGE_ERROR_MESSAGES[code]}}
    assert "secret" not in response.text
    assert "token" not in response.text


@pytest.mark.parametrize("path", [
    "/api/v1/accounts?view=cash",
    "/api/v1/cash-projections",
    "/api/v1/evidence/cash-projections/cash:1003",
])
def test_projection_api_maps_runtime_dbapi_failures_for_every_read_endpoint(path):
    from ft.web.app import _STORAGE_ERROR_MESSAGES, create_app

    response = TestClient(create_app(_DBAPIFailureService())).get(path)

    assert response.status_code == 503
    assert response.json() == {
        "error": {"code": "storage.busy", "message": _STORAGE_ERROR_MESSAGES["storage.busy"]},
    }
    assert "private_ledger" not in response.text
    assert "secret-token" not in response.text


@pytest.mark.parametrize("params", [
    {"limit": "invalid"},
    {"limit": "0"},
    {"date_from": "2026-07-32"},
    {"amount_min": "Infinity"},
    {"economic_type": "transfer"},
])
def test_projection_api_invalid_parameters_never_expose_fastapi_validation_payload(cash_web_runtime, params):
    response = _client(cash_web_runtime).get("/api/v1/cash-projections", params=params)

    assert response.status_code == 400
    assert response.json() == {
        "error": {
            "code": "invalid_filter",
            "message": "筛选条件或分页位置无效，请返回第一页重试。",
        },
    }
