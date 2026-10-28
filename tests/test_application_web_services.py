from contextvars import ContextVar

import pytest

from ft.web import app as web_app


def test_workspace_cash_dispatch_preserves_explicit_current_commands(monkeypatch):
    class ManualBackend:
        def create_record(self, payload):
            return {"record": payload}
        def update_record(self, *_args):
            raise AssertionError("retired command must not be dispatched")
    backend = ManualBackend()
    monkeypatch.setattr(web_app, "CashLedgerQueryService", lambda *_args: object())
    monkeypatch.setattr(web_app, "CashLedgerCommandService", lambda *_args, **_kwargs: backend)
    service = web_app.WorkspaceServices(None, ContextVar("workspace_id", default="workspace-a"))
    assert service.create_record({"note": "人工输入"}) == {"record": {"note": "人工输入"}}
    with pytest.raises(AttributeError):
        service.update_record


def test_parsed_import_factory_requires_parsed_store_instead_of_original_file_session():
    from ft.application.cash_import_errors import ImportSessionStorageUnavailable
    service = web_app.WorkspaceServices(None, ContextVar("workspace_id", default="workspace-a"))
    with pytest.raises(ImportSessionStorageUnavailable):
        service.parsed_import_context()
