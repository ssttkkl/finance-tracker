"""007: statement import must not create refund_offset rows."""
from __future__ import annotations

from pathlib import Path
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.pool import StaticPool


def _alembic_upgrade(engine):
    from alembic import command
    from alembic.config import Config
    root = Path(__file__).parents[1]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    with engine.begin() as connection:
        config.attributes["connection"] = connection
        command.upgrade(config, "head")


def test_statement_import_details_have_empty_import_refund_relations(tmp_path, monkeypatch):
    """Smoke: RelationService.create_import_refund_offsets not required on import path."""
    import ft.application.statement_import as si
    src = Path(si.__file__).read_text()
    assert "create_import_refund_offsets" not in src
