"""CLI import command error handling tests.

Constitution III: Test-first - verify user-facing error messages.
"""
import pytest


@pytest.mark.skip(reason="T029: Implementation not yet complete")
def test_cli_import_account_not_found(cli_runner, relational_uow):
    """CLI should show clear error when account doesn't exist."""
    from ft.cli.import_cmd import import_cmd

    result = cli_runner.invoke(
        import_cmd,
        ["--source", "dfzq", "--account", "NonExistent", "tests/fixtures/dfzq/sample_statement.txt"],
    )

    assert result.exit_code != 0
    assert "not found" in result.output.lower()
    assert "NonExistent" in result.output


@pytest.mark.skip(reason="T029: Implementation not yet complete")
def test_cli_import_file_not_found(cli_runner, relational_uow):
    """CLI should show clear error when file doesn't exist."""
    from ft.cli.import_cmd import import_cmd

    result = cli_runner.invoke(
        import_cmd,
        ["--source", "dfzq", "--account", "东方证券", "/nonexistent/file.txt"],
    )

    assert result.exit_code != 0
    assert "not found" in result.output.lower() or "no such file" in result.output.lower()


@pytest.mark.skip(reason="T029: Implementation not yet complete")
def test_cli_import_parse_failure(cli_runner, relational_uow, tmp_path):
    """CLI should show parse error with context (page/line reference)."""
    from ft.adapters.relational.uow import ensure_workspace
    from ft.domain.accounts import AccountDTO

    workspace_id = "test_workspace"
    ensure_workspace(relational_uow._session_factory, workspace_id, name="Test")

    with relational_uow as uow:
        uow.accounts.add(AccountDTO("东方证券", "security", active=True))
        uow.commit()

    # Create corrupted file
    corrupted = tmp_path / "corrupted.txt"
    corrupted.write_text("资金流水明细\n\nCorrupted data\n")

    from ft.cli.import_cmd import import_cmd

    result = cli_runner.invoke(
        import_cmd,
        ["--source", "dfzq", "--account", "东方证券", str(corrupted)],
    )

    assert result.exit_code != 0
    # Should include helpful context
    assert "parse" in result.output.lower() or "invalid" in result.output.lower()


@pytest.mark.skip(reason="T029: Implementation not yet complete")
def test_cli_import_validation_failure(cli_runner, relational_uow, tmp_path):
    """CLI should show validation error with specific field reference."""
    from ft.adapters.relational.uow import ensure_workspace
    from ft.domain.accounts import AccountDTO

    workspace_id = "test_workspace"
    ensure_workspace(relational_uow._session_factory, workspace_id, name="Test")

    with relational_uow as uow:
        uow.accounts.add(AccountDTO("东方证券", "security", active=True))
        uow.commit()

    # TODO: Create fixture that produces NaN in snapshot
    # Should show error mentioning the specific position/field


@pytest.mark.skip(reason="T029: Implementation not yet complete")
def test_cli_import_wrong_account_type(cli_runner, relational_uow):
    """CLI should explain account type requirement."""
    from ft.adapters.relational.uow import ensure_workspace
    from ft.domain.accounts import AccountDTO

    workspace_id = "test_workspace"
    ensure_workspace(relational_uow._session_factory, workspace_id, name="Test")

    with relational_uow as uow:
        uow.accounts.add(AccountDTO("现金账户", "cash", active=True))
        uow.commit()

    from ft.cli.import_cmd import import_cmd

    result = cli_runner.invoke(
        import_cmd,
        ["--source", "dfzq", "--account", "现金账户", "tests/fixtures/dfzq/sample_statement.txt"],
    )

    assert result.exit_code != 0
    assert "security" in result.output.lower() or "crypto" in result.output.lower()


@pytest.mark.skip(reason="T029: Implementation not yet complete")
def test_cli_import_external_tool_missing(cli_runner, relational_uow, monkeypatch):
    """CLI should show installation instructions when qpdf/mutool missing."""
    # Mock tool check to return None
    def mock_check_tools():
        return {"qpdf": None, "mutool": "1.20.0"}

    import ft.importers.dfzq as dfzq_module
    monkeypatch.setattr(dfzq_module, "check_external_tools", mock_check_tools)

    from ft.cli.import_cmd import import_cmd

    result = cli_runner.invoke(
        import_cmd,
        ["--source", "dfzq", "--account", "东方证券", "tests/fixtures/dfzq/sample_statement.txt"],
    )

    assert result.exit_code != 0
    assert "qpdf" in result.output.lower()
    # Should include installation hint
    assert "install" in result.output.lower() or "brew" in result.output.lower()


@pytest.mark.skip(reason="T029: Implementation not yet complete")
def test_cli_import_success_message(cli_runner, relational_uow):
    """CLI should show success summary with batch_id and event count."""
    from ft.adapters.relational.uow import ensure_workspace
    from ft.domain.accounts import AccountDTO

    workspace_id = "test_workspace"
    ensure_workspace(relational_uow._session_factory, workspace_id, name="Test")

    with relational_uow as uow:
        uow.accounts.add(AccountDTO("东方证券", "security", active=True))
        uow.commit()

    from ft.cli.import_cmd import import_cmd

    result = cli_runner.invoke(
        import_cmd,
        ["--source", "dfzq", "--account", "东方证券", "tests/fixtures/dfzq/sample_statement.txt"],
    )

    assert result.exit_code == 0
    assert "success" in result.output.lower() or "imported" in result.output.lower()
    # Should show count
    assert "6" in result.output  # 6 events
    # Should show batch_id or reference


@pytest.mark.skip(reason="T029: Implementation not yet complete")
def test_cli_import_duplicate_message(cli_runner, relational_uow):
    """CLI should clearly indicate duplicate import with no action taken."""
    from ft.adapters.relational.uow import ensure_workspace
    from ft.domain.accounts import AccountDTO

    workspace_id = "test_workspace"
    ensure_workspace(relational_uow._session_factory, workspace_id, name="Test")

    with relational_uow as uow:
        uow.accounts.add(AccountDTO("东方证券", "security", active=True))
        uow.commit()

    from ft.cli.import_cmd import import_cmd

    # First import
    result1 = cli_runner.invoke(
        import_cmd,
        ["--source", "dfzq", "--account", "东方证券", "tests/fixtures/dfzq/sample_statement.txt"],
    )

    assert result1.exit_code == 0

    # Second import
    result2 = cli_runner.invoke(
        import_cmd,
        ["--source", "dfzq", "--account", "东方证券", "tests/fixtures/dfzq/sample_statement.txt"],
    )

    assert result2.exit_code == 0
    assert "duplicate" in result2.output.lower() or "already imported" in result2.output.lower()
    assert "0" in result2.output  # 0 new events


# Pytest fixtures
@pytest.fixture
def cli_runner():
    """Click CLI test runner."""
    from click.testing import CliRunner

    return CliRunner()
