import json

from test_storage_migration import _ledger_fixture


def _database(tmp_path):
    from sqlalchemy import create_engine
    from ft.adapters.postgres import create_schema

    url = f"sqlite+pysqlite:///{tmp_path / 'migration.db'}"
    engine = create_engine(url)
    create_schema(engine)
    return url


def test_migrate_cli_inspect_import_verify_and_export(tmp_path, capsys):
    from ft import cli

    ledger = _ledger_fixture(tmp_path / "ledger")
    database_url = _database(tmp_path)

    cli.main(["migrate", "inspect", "--from", str(ledger)])
    inspected = json.loads(capsys.readouterr().out)
    assert inspected["account_count"] == 2

    cli.main([
        "migrate", "import", "--from", str(ledger),
        "--database-url", database_url, "--workspace", "workspace-a",
    ])
    imported = json.loads(capsys.readouterr().out)
    assert imported["imported"] is True

    cli.main([
        "migrate", "import", "--from", str(ledger),
        "--database-url", database_url, "--workspace", "workspace-a",
    ])
    assert json.loads(capsys.readouterr().out)["imported"] is False

    cli.main([
        "migrate", "verify", "--from", str(ledger),
        "--database-url", database_url, "--workspace", "workspace-a",
    ])
    verified = json.loads(capsys.readouterr().out)
    assert verified["ok"] is True

    destination = tmp_path / "export"
    cli.main([
        "migrate", "export", "--to", str(destination),
        "--database-url", database_url, "--workspace", "workspace-a",
    ])
    exported = json.loads(capsys.readouterr().out)
    assert exported["account_count"] == 2
    assert (destination / "accounts.yaml").exists()
