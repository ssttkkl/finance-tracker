"""Export an edit_record commit receipt and resulting state against an isolated oracle DB."""
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
    AccountModel, CashCategoryModel, CashCategoryStateModel, CashEntryMemberModel, CashEntryModel,
    CashProjectionDatasetModel, CashProjectionStateModel, CashTransactionComponentModel, CashTransactionModel,
    UserModel, UserSessionModel, WorkspaceMembershipModel, WorkspaceModel,
)
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.cash_mutation_commit import CashMutationCommandService
from ft.application.cash_mutation_plans import _input
from ft.application.parsed_import_staging import InMemoryParsedImportStore

NOW = datetime(2026, 1, 1, tzinfo=timezone.utc)
CONTEXT = "c" * 43


def seed(sessions):
    with sessions.begin() as session:
        session.add(WorkspaceModel(id="commit-a", name="提交工作区", created_at=NOW))
        session.add(UserModel(id="admin", email="admin@example.invalid", password_hash="unused-anonymous-fixture",
            created_at=NOW))
        session.flush()
        session.add(WorkspaceMembershipModel(workspace_id="commit-a", user_id="admin", role="admin", created_at=NOW))
        session.add(UserSessionModel(id="session-admin", user_id="admin",
            token_digest=sha256(b"synthetic-admin").hexdigest(), active_workspace_id="commit-a",
            expires_at=datetime(2099, 1, 1, tzinfo=timezone.utc), created_at=NOW))
        session.add(CashCategoryStateModel(workspace_id="commit-a", revision=1, updated_at=NOW))
        session.add(CashCategoryModel(id="food", workspace_id="commit-a", parent_id=None, parent_scope_key="__root__",
            name="餐饮", normalized_name="餐饮", description=None, category_path="/food/", depth=1, sort_order=1,
            revision=1, created_at=NOW, updated_at=NOW))
        session.add(AccountModel(id=301, workspace_id="commit-a", name="日常账户", type="cash", active=True,
            currencies=["CNY"], revision=1, created_at=NOW, updated_at=NOW))
        session.add(CashTransactionModel(id=2001, workspace_id="commit-a", state="posted", record_origin="import",
            account_id=301, row_revision=1, cash_granularity="atomic",
            occurred_at=datetime(2026, 7, 2, 9, tzinfo=timezone.utc), amount="15.00", currency="CNY",
            counterparty="超市", counterparty_account="", counterparty_account_attrs=[], note="已确认",
            category_id="food", record_type="consumption", record_subtype="not_applicable", record_id="w2",
            source_type="wechat", source_payload={"备注": "已确认"}, relation_metadata={}, manual_overrides={},
            created_at=NOW))
        session.flush()
        session.add(CashTransactionComponentModel(id=3001, workspace_id="commit-a", cash_transaction_id=2001,
            account_id=301, amount="15.00", currency="CNY", ordinal=0, label="", source_key="", metadata_json={},
            created_at=NOW))
        session.add(CashEntryModel(id="entry-2001", workspace_id="commit-a", root_transaction_id=2001, revision=1,
            category_id="food", note="已确认", counterparty="超市", counterparty_account="",
            counterparty_account_attrs=[], manual_fields=[], split_inherit=None))
        session.flush()
        session.add(CashEntryMemberModel(workspace_id="commit-a", transaction_id=2001, entry_id="entry-2001",
            standalone_values={}, standalone_manual_fields=[]))
        session.add(CashProjectionDatasetModel(id="ds1", workspace_id="commit-a", state="active", source_revision=0,
            source_digest="0" * 64, rules_version="projection-v1", created_at=NOW, published_at=NOW))
        session.flush()
        session.add(CashProjectionStateModel(workspace_id="commit-a", active_dataset_id="ds1", projection_version=1,
            source_revision=0, rules_version="projection-v1", availability="ready", last_build_status="succeeded",
            projection_count=0, member_count=0, updated_at=NOW))


def connect(url: str):
    root = Path(__file__).resolve().parents[1]
    if url.startswith("sqlite+pysqlite:///"):
        path = Path(url.removeprefix("sqlite+pysqlite:///"))
        if not path.is_absolute() or path.exists():
            raise ValueError("use a new absolute SQLite test path")
        path.parent.mkdir(parents=True, exist_ok=True)
        jdbc = "jdbc:sqlite:" + str(path)
    else:
        parsed = urlparse(url)
        if parsed.scheme != "postgresql+psycopg" or not parsed.path.endswith("_test"):
            raise ValueError("PostgreSQL must target a dedicated _test database")
        jdbc = f"jdbc:postgresql://{parsed.hostname}:{parsed.port or 5432}{parsed.path}"
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    engine = create_relational_engine(url)
    if inspect(engine).get_table_names():
        raise ValueError("test database must be empty")
    with engine.begin() as connection:
        config.attributes["connection"] = connection
        command.upgrade(config, "head")
    sessions = create_session_factory(engine)
    seed(sessions)
    return engine, sessions, jdbc


def export(target_url: str, oracle_url: str, output: Path) -> None:
    target_engine, _, jdbc = connect(target_url)
    oracle_engine, sessions, _ = connect(oracle_url)
    try:
        store = InMemoryParsedImportStore()
        uow = RelationalUnitOfWork(sessions, "commit-a")
        service = CashMutationCommandService(uow, store, user_id="admin")
        preview_input, _ = _input({"intent": "edit_record", "transaction_id": "2001", "expected_revision": 1,
            "patch": {"note": "提交备注"}})
        view = service.preview(preview_input)
        receipt = service.commit(view["plan_id"], expected_plan_revision=1, idempotency_key="commit-edit-record")
        with sessions() as session:
            entry = session.get(CashEntryModel, "entry-2001")
            transaction = session.get(CashTransactionModel, 2001)
            state = session.scalar(select(CashProjectionStateModel).where(
                CashProjectionStateModel.workspace_id == "commit-a"))
            result = {
                "receipt": {key: value for key, value in receipt.items()
                    if key not in {"receipt_id", "created_at", "plan_id", "request_digest", "session_digest",
                        "edit_context_digest"}},
                "entry": {"note": entry.note, "revision": entry.revision, "manual_fields": list(entry.manual_fields)},
                "transaction": {"note": transaction.note, "row_revision": transaction.row_revision},
                "projection": {"version": state.projection_version, "availability": state.availability,
                    "member_count": state.member_count},
            }
        output.mkdir(parents=True, exist_ok=True)
        (output / "commit.json").write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
        (output / "database.json").write_text(json.dumps({"jdbc_url": jdbc, "schema_revision": "20261028_51"}) + "\n")
    finally:
        target_engine.dispose()
        oracle_engine.dispose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--oracle-database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    export(args.database_url, args.oracle_database_url, args.output)
