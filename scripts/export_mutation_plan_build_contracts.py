"""Export a real save_draft maintenance plan build (view + item detail) against an isolated DB."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
from urllib.parse import urlparse

from alembic import command
from alembic.config import Config
from sqlalchemy import inspect, select

from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import (
    AccountModel, CashCategoryModel, CashCategoryStateModel, CashTransactionModel,
    UserModel, UserSessionModel, WorkspaceMembershipModel, WorkspaceModel,
)
from ft.application.cash_import_plans import _public_detail, _resolve_detail
from ft.application.cash_mutation_business_plan import CashMutationBusinessPlanBuilder
from ft.application.cash_mutation_plans import CashMutationPlanService, _input, _validate_build

NOW = datetime(2026, 1, 1, tzinfo=timezone.utc)
CONTEXT = "c" * 43
PLAN_ID = "mp_" + CONTEXT + "." + "t" * 16


def seed(sessions):
    with sessions.begin() as session:
        session.add(WorkspaceModel(id="plan-a", name="计划工作区", created_at=NOW))
        session.add_all([UserModel(id=name, email=name + "@example.invalid", password_hash="unused-anonymous-fixture",
            created_at=NOW) for name in ("admin", "viewer")])
        session.flush()
        session.add_all([WorkspaceMembershipModel(workspace_id="plan-a", user_id="admin", role="admin", created_at=NOW),
            WorkspaceMembershipModel(workspace_id="plan-a", user_id="viewer", role="viewer", created_at=NOW)])
        for token, user in (("admin", "admin"), ("viewer", "viewer")):
            session.add(UserSessionModel(id="session-" + token, user_id=user,
                token_digest=sha256(("synthetic-" + token).encode()).hexdigest(), active_workspace_id="plan-a",
                expires_at=datetime(2099, 1, 1, tzinfo=timezone.utc), created_at=NOW))
        session.add(CashCategoryStateModel(workspace_id="plan-a", revision=1, updated_at=NOW))
        session.add(CashCategoryModel(id="food", workspace_id="plan-a", parent_id=None, parent_scope_key="__root__",
            name="餐饮", normalized_name="餐饮", description=None, category_path="/food/", depth=1, sort_order=1,
            revision=1, created_at=NOW, updated_at=NOW))
        session.add(AccountModel(id=101, workspace_id="plan-a", name="日常账户", type="cash", active=True,
            currencies=["CNY"], revision=1, created_at=NOW, updated_at=NOW))
        session.add(CashTransactionModel(id=1001, workspace_id="plan-a", state="draft", record_origin="import",
            row_revision=1, cash_granularity="atomic", occurred_at=None, amount=None, currency="CNY",
            counterparty="咖啡店", counterparty_account="", counterparty_account_attrs=[], note="旧备注",
            category_id="food", record_type=None, record_subtype=None, record_id="w1", source_type="wechat",
            source_payload={"备注": "旧备注"}, relation_metadata={}, manual_overrides={}, created_at=NOW))


CASES = [
    {"note": "新备注"},
    {"amount": "20"},
    {},
    {"category_id": None, "counterparty": "新店"},
    {"unknown_field": "x"},
]


def export(database_url: str, output: Path) -> None:
    root = Path(__file__).resolve().parents[1]
    if database_url.startswith("sqlite+pysqlite:///"):
        database = Path(database_url.removeprefix("sqlite+pysqlite:///"))
        if not database.is_absolute() or database.exists():
            raise ValueError("use a new absolute SQLite test path; existing files are never overwritten")
        database.parent.mkdir(parents=True, exist_ok=True)
        jdbc = "jdbc:sqlite:" + str(database)
    else:
        parsed = urlparse(database_url)
        if parsed.scheme != "postgresql+psycopg" or not parsed.path.endswith("_test"):
            raise ValueError("PostgreSQL must explicitly target a dedicated _test database")
        jdbc = f"jdbc:postgresql://{parsed.hostname}:{parsed.port or 5432}{parsed.path}"
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    engine = create_relational_engine(database_url)
    if inspect(engine).get_table_names():
        raise ValueError("test database must be empty")
    with engine.begin() as connection:
        config.attributes["connection"] = connection
        command.upgrade(config, "head")
    sessions = create_session_factory(engine)
    seed(sessions)
    builder = CashMutationBusinessPlanBuilder("plan-a")
    cases = []
    with sessions() as session:
        for patch in CASES:
            data, _metadata = _input({"intent": "save_draft", "transaction_id": "1001", "expected_revision": 1,
                "patch": patch})
            try:
                row = session.get(CashTransactionModel, 1001)
                result = builder(session=session, preview_input=data, previous_plan=None, current_record=row)
                _validate_build(result)
                item_counts = {status: sum(item["confirmation_status"] == status for item in result["items"])
                    for status in ("confirmed", "pending")}
                plan = dict(result, plan_id=PLAN_ID, edit_context_id=CONTEXT, plan_revision=1, context_revision=1,
                    contract_version=1, expires_at="2026-10-29T12:30:00+00:00", item_counts=item_counts)
                view = CashMutationPlanService._view(plan)
                items = {}
                for item in result["items"]:
                    identifier = item["preview_item_id"]
                    items[identifier] = _public_detail(_resolve_detail(plan, plan["item_details"][identifier])) | {
                        "preview_item_id": identifier}
                cases.append({"patch": patch, "ok": True, "view": view, "items": items})
            except ValueError as error:
                cases.append({"patch": patch, "ok": False, "error": getattr(error, "code", "record_input_invalid")})
    output.mkdir(parents=True, exist_ok=True)
    (output / "builds.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    (output / "database.json").write_text(json.dumps({"jdbc_url": jdbc, "schema_revision": "20261028_51"}) + "\n")
    engine.dispose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    export(args.database_url, args.output)
