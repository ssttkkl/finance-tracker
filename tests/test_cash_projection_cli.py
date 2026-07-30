"""收支投影维护命令合同。"""
from __future__ import annotations

import pytest


def test_projection_status_and_rebuild_are_explicit_and_safe(cash_web_runtime, monkeypatch, capsys):
    from ft import cli

    monkeypatch.setenv("FT_DATABASE_URL", cash_web_runtime.database_url)
    monkeypatch.setenv("FT_WORKSPACE_ID", cash_web_runtime.workspace_id)
    cli.main(["projections", "status"])
    before = capsys.readouterr().out
    assert "可用性：uninitialized" in before
    assert "咖啡店" not in before
    cli.main(["projections", "rebuild"])
    after = capsys.readouterr().out
    assert "可用性：ready" in after
    assert "投影条目数：3" in after


def test_projection_status_missing_sqlite_is_strictly_readonly_and_redacted(tmp_path, monkeypatch, capsys):
    from ft import cli

    database = tmp_path / "missing-status.db"
    monkeypatch.setenv("FT_DATABASE_URL", f"sqlite+pysqlite:///{database}")
    monkeypatch.setenv("FT_WORKSPACE_ID", "status-workspace")

    with pytest.raises(SystemExit) as exit_status:
        cli.main(["projections", "status"])

    output = capsys.readouterr()
    assert exit_status.value.code == 1
    assert "storage.connect" in output.err
    assert "Traceback" not in output.out + output.err
    assert str(database) not in output.out + output.err
    assert not database.exists()
    assert not (tmp_path / "missing-status.db-wal").exists()
    assert not (tmp_path / "missing-status.db-shm").exists()


def test_projection_status_redacts_projection_error(cash_web_runtime, monkeypatch, capsys):
    from ft import cli
    from ft.application.cash_projections import CashProjectionService

    monkeypatch.setenv("FT_DATABASE_URL", cash_web_runtime.database_url)
    monkeypatch.setenv("FT_WORKSPACE_ID", cash_web_runtime.workspace_id)
    monkeypatch.setattr(
        CashProjectionService,
        "status",
        lambda _self: (_ for _ in ()).throw(RuntimeError("projection.concurrent_update")),
    )

    with pytest.raises(SystemExit) as exit_status:
        cli.main(["projections", "status"])

    output = capsys.readouterr()
    assert exit_status.value.code == 1
    assert "projection.concurrent_update" in output.err
    assert "Traceback" not in output.out + output.err
    assert cash_web_runtime.database_url not in output.out + output.err


def test_projection_status_redacts_domain_projection_error(cash_web_runtime, monkeypatch, capsys):
    from ft import cli
    from ft.application.cash_projections import CashProjectionService
    from ft.domain.cash_projection import CashProjectionError

    monkeypatch.setenv("FT_DATABASE_URL", cash_web_runtime.database_url)
    monkeypatch.setenv("FT_WORKSPACE_ID", cash_web_runtime.workspace_id)
    monkeypatch.setattr(
        CashProjectionService,
        "status",
        lambda _self: (_ for _ in ()).throw(CashProjectionError("projection.invalid_relation")),
    )

    with pytest.raises(SystemExit) as exit_status:
        cli.main(["projections", "status"])

    output = capsys.readouterr()
    assert exit_status.value.code == 1
    assert "projection.invalid_relation" in output.err
    assert "Traceback" not in output.out + output.err
    assert cash_web_runtime.database_url not in output.out + output.err


def test_projection_status_postgres_transaction_rejects_write(postgres_cash_web_runtime, monkeypatch, capsys):
    from sqlalchemy import text

    from ft import cli
    from ft.adapters.relational.models import WorkspaceModel
    from ft.application.cash_projections import CashProjectionService

    monkeypatch.setenv("FT_DATABASE_URL", postgres_cash_web_runtime.database_url)
    monkeypatch.setenv("FT_WORKSPACE_ID", postgres_cash_web_runtime.workspace_id)

    def attempt_write(service):
        with service._session_factory.begin() as session:
            session.execute(
                text("UPDATE workspaces SET name = 'must-not-persist' WHERE id = :workspace_id"),
                {"workspace_id": service._workspace_id},
            )
        return {
            "availability": "ready",
            "projection_version": 1,
            "rules_version": "test",
            "projection_count": 0,
            "member_count": 0,
        }

    monkeypatch.setattr(CashProjectionService, "status", attempt_write)

    with pytest.raises(SystemExit) as exit_status:
        cli.main(["projections", "status"])

    output = capsys.readouterr()
    assert exit_status.value.code == 1
    assert "storage.readonly" in output.err
    assert "Traceback" not in output.out + output.err
    assert postgres_cash_web_runtime.database_url not in output.out + output.err
    with postgres_cash_web_runtime.sessions() as session:
        assert session.get(WorkspaceModel, postgres_cash_web_runtime.workspace_id).name != "must-not-persist"


def test_projection_status_redacts_sqlite_snapshot_cleanup_error(cash_web_runtime, monkeypatch, capsys):
    from ft import cli
    from ft.adapters.relational.dialect import RelationalEngineError, _WebSQLiteReadConnectionFactory

    monkeypatch.setenv("FT_DATABASE_URL", cash_web_runtime.database_url)
    monkeypatch.setenv("FT_WORKSPACE_ID", cash_web_runtime.workspace_id)
    monkeypatch.setattr(
        _WebSQLiteReadConnectionFactory,
        "cleanup",
        lambda _self: (_ for _ in ()).throw(RelationalEngineError("storage.connect: cleanup failed")),
    )

    with pytest.raises(SystemExit) as exit_status:
        cli.main(["projections", "status"])

    output = capsys.readouterr()
    assert exit_status.value.code == 1
    assert "storage.connect" in output.err
    assert "Traceback" not in output.out + output.err
    assert cash_web_runtime.database_url not in output.out + output.err
