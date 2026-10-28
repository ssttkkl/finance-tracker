"""现金导入仅支持当前解析会话，不再保留原文件会话或旧提交能力。"""
import importlib.util


def test_cash_commands_do_not_keep_legacy_import_execution():
    from ft.application.cash_ledger import CashLedgerCommandService
    for name in ("detect_import", "scan_import", "scan_import_batch", "preview_import",
        "preview_import_batch", "commit_import", "commit_import_batch", "get_import_commit_result"):
        assert not hasattr(CashLedgerCommandService, name), name


def test_original_file_sessions_are_removed_and_shared_errors_remain_available():
    from ft.application import cash_import_errors as errors
    assert importlib.util.find_spec("ft.application.cash_import_session") is None
    assert importlib.util.find_spec("ft.application.cash_import_staging") is None
    for name, code in (("ImportSessionNotFound", "import_session_not_found"),
        ("ImportSessionForbidden", "import_session_forbidden"), ("ImportSessionExpired", "import_session_expired"),
        ("ImportSessionStorageUnavailable", "import_session_storage_unavailable")):
        error = getattr(errors, name)()
        assert isinstance(error, errors.ImportSessionError) and str(error) == code
