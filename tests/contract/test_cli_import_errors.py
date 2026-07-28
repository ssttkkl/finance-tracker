"""CLI 导入命令的错误处理测试。

Constitution III：通过测试锁定用户可见的错误信息。
"""
import pytest


@pytest.mark.skip(reason="T029：实现尚未完成")
def test_cli_import_account_not_found(cli_runner, relational_uow):
    """账户不存在时，CLI 应显示明确的错误。"""
    from ft.cli.import_cmd import import_cmd

    result = cli_runner.invoke(
        import_cmd,
        ["--source", "dfzq", "--account", "NonExistent", "tests/fixtures/dfzq/sample_statement.txt"],
    )

    assert result.exit_code != 0
    assert "找不到账户" in result.output
    assert "NonExistent" in result.output


@pytest.mark.skip(reason="T029：实现尚未完成")
def test_cli_import_file_not_found(cli_runner, relational_uow):
    """文件不存在时，CLI 应显示明确的错误。"""
    from ft.cli.import_cmd import import_cmd

    result = cli_runner.invoke(
        import_cmd,
        ["--source", "dfzq", "--account", "东方证券", "/nonexistent/file.txt"],
    )

    assert result.exit_code != 0
    assert "not found" in result.output.lower() or "no such file" in result.output.lower()


@pytest.mark.skip(reason="T029：实现尚未完成")
def test_cli_import_parse_failure(cli_runner, relational_uow, tmp_path):
    """解析失败时，CLI 应显示包含页码或行号上下文的错误。"""
    from ft.adapters.relational.uow import ensure_workspace
    from ft.domain.accounts import AccountDTO

    workspace_id = "test_workspace"
    ensure_workspace(relational_uow._session_factory, workspace_id, name="Test")

    with relational_uow as uow:
        uow.accounts.add(AccountDTO("东方证券", "security", active=True))
        uow.commit()

    # 构造无法解析的账单文件。
    corrupted = tmp_path / "corrupted.txt"
    corrupted.write_text("资金流水明细\n\nCorrupted data\n")

    from ft.cli.import_cmd import import_cmd

    result = cli_runner.invoke(
        import_cmd,
        ["--source", "dfzq", "--account", "东方证券", str(corrupted)],
    )

    assert result.exit_code != 0
    # 错误应包含便于定位问题的上下文。
    assert "导入失败" in result.output


@pytest.mark.skip(reason="T029：实现尚未完成")
def test_cli_import_validation_failure(cli_runner, relational_uow, tmp_path):
    """数据校验失败时，CLI 应指出具体字段。"""
    from ft.adapters.relational.uow import ensure_workspace
    from ft.domain.accounts import AccountDTO

    workspace_id = "test_workspace"
    ensure_workspace(relational_uow._session_factory, workspace_id, name="Test")

    with relational_uow as uow:
        uow.accounts.add(AccountDTO("东方证券", "security", active=True))
        uow.commit()

    # TODO：构造一份会在账户快照中产生 NaN 的夹具。
    # 错误应指出具体位置或字段。


@pytest.mark.skip(reason="T029：实现尚未完成")
def test_cli_import_wrong_account_type(cli_runner, relational_uow):
    """账户类型不符时，CLI 应说明允许的类型。"""
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


@pytest.mark.skip(reason="T029：实现尚未完成")
def test_cli_import_external_tool_missing(cli_runner, relational_uow, monkeypatch):
    """缺少 qpdf 或 mutool 时，CLI 应显示安装方法。"""
    # 模拟外部工具不存在。
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
    # 提示应包含可执行的安装命令。
    assert "安装命令" in result.output
    assert "brew" in result.output.lower()


@pytest.mark.skip(reason="T029：实现尚未完成")
def test_cli_import_success_message(cli_runner, relational_uow):
    """导入成功时，CLI 应显示批次 ID 和账本记录数。"""
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
    assert "已导入" in result.output
    # 应显示新增账本记录数。
    assert "6" in result.output  # 共导入 6 条账本记录。
    assert "批次 ID" in result.output


@pytest.mark.skip(reason="T029：实现尚未完成")
def test_cli_import_duplicate_message(cli_runner, relational_uow):
    """重复导入时，CLI 应明确说明没有新增账本记录。"""
    from ft.adapters.relational.uow import ensure_workspace
    from ft.domain.accounts import AccountDTO

    workspace_id = "test_workspace"
    ensure_workspace(relational_uow._session_factory, workspace_id, name="Test")

    with relational_uow as uow:
        uow.accounts.add(AccountDTO("东方证券", "security", active=True))
        uow.commit()

    from ft.cli.import_cmd import import_cmd

    # 首次导入。
    result1 = cli_runner.invoke(
        import_cmd,
        ["--source", "dfzq", "--account", "东方证券", "tests/fixtures/dfzq/sample_statement.txt"],
    )

    assert result1.exit_code == 0

    # 再次导入同一账单。
    result2 = cli_runner.invoke(
        import_cmd,
        ["--source", "dfzq", "--account", "东方证券", "tests/fixtures/dfzq/sample_statement.txt"],
    )

    assert result2.exit_code == 0
    assert "已经导入" in result2.output
    assert "没有新增账本记录" in result2.output
    assert "0" in result2.output  # 没有新增账本记录。


# Pytest 夹具。
@pytest.fixture
def cli_runner():
    """Click CLI 测试运行器。"""
    from click.testing import CliRunner

    return CliRunner()
