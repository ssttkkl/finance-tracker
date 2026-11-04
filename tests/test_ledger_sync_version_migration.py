"""Schema-only tests: Python owns Alembic, not the production write path."""
from datetime import datetime, timezone
import os
from pathlib import Path
from urllib.parse import urlparse

from alembic import command
from alembic.config import Config
import pytest
import sqlalchemy as sa

from ft.adapters.relational.dialect import create_relational_engine


@pytest.mark.parametrize("backend", ["sqlite", "postgresql"])
def test_version_upgrade_preserves_referenced_facts_and_downgrade(backend, tmp_path, monkeypatch):
    if backend == "sqlite":
        url = f"sqlite+pysqlite:///{tmp_path / 'ledger-version.db'}"
    else:
        url = os.getenv("FT_TEST_POSTGRES_URL")
        if not url:
            pytest.skip("explicit empty PostgreSQL _test database required")
        parsed = urlparse(url)
        assert parsed.scheme == "postgresql+psycopg" and parsed.path.endswith("_test")
    monkeypatch.delenv("FT_DATABASE_URL", raising=False)
    root = Path(__file__).parents[1]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", url.replace("%", "%%"))
    engine = create_relational_engine(url)
    try:
        assert not sa.inspect(engine).get_table_names(), "prepare an empty test database; never reset an existing schema"
        command.upgrade(config, "20261030_53")
        tables = sa.MetaData()
        workspaces = sa.Table("workspaces", tables, autoload_with=engine)
        accounts = sa.Table("accounts", tables, autoload_with=engine)
        now = datetime(2026, 11, 4, tzinfo=timezone.utc)
        with engine.begin() as c:
            c.execute(workspaces.insert().values(id="version-existing", name="Existing", created_at=now))
            c.execute(accounts.insert().values(workspace_id="version-existing", name="Referenced",
                type="cash", currencies=["CNY"], active=True, revision=7, metadata_json={}, created_at=now, updated_at=now))
        def facts():
            with engine.connect() as c:
                return (list(c.execute(sa.select(workspaces)).mappings()), list(c.execute(sa.select(accounts)).mappings()))
        original = facts()
        command.upgrade(config, "20261104_54")
        assert facts() == original
        with engine.connect() as c:
            assert c.scalar(sa.text("SELECT ledger_seq FROM workspaces WHERE id='version-existing'")) == 0
        with pytest.raises(sa.exc.IntegrityError):
            with engine.begin() as c:
                c.execute(sa.text("UPDATE workspaces SET ledger_seq=-1 WHERE id='version-existing'"))
        with engine.begin() as c:
            c.execute(sa.text("UPDATE workspaces SET ledger_seq=:version WHERE id='version-existing'"), {"version": 2**63 - 1})
        with engine.connect() as c:
            assert c.scalar(sa.text("SELECT ledger_seq FROM workspaces WHERE id='version-existing'")) == 2**63 - 1
        command.downgrade(config, "20261030_53")
        assert "ledger_seq" not in {column["name"] for column in sa.inspect(engine).get_columns("workspaces")}
        assert facts() == original
        command.upgrade(config, "20261104_54")
        assert facts() == original
        with engine.connect() as c:
            assert c.scalar(sa.text("SELECT ledger_seq FROM workspaces WHERE id='version-existing'")) == 0
    finally:
        engine.dispose()
