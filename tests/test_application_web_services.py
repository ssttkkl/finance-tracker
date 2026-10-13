from contextvars import ContextVar

from ft.application.cash_import_staging import InMemoryImportStagingStore
from ft.web import app as web_app


class _BatchImportBackend:
    def __init__(self):
        self.calls = []
        self.preview_calls = []
        self.commit_calls = []

    def scan_import_batch(self, files, *, passwords=None, **_kwargs):
        passwords = passwords or {}
        self.calls.append(([(item["filename"], item["content"]) for item in files], passwords))
        statuses = []
        for index, item in enumerate(files):
            protected = item["filename"].endswith(".pdf")
            ready = not protected or bool(passwords.get(str(index)))
            statuses.append({
                "index": index,
                "name": item["filename"],
                "status": "ready" if ready else "password_required",
                **({"channel": "alipay"} if ready else {}),
            })
        return {
            "files": statuses,
            "ready": all(item["status"] == "ready" for item in statuses),
            "groups": [],
        }

    def preview_import_batch(self, files, **kwargs):
        from ft.application.cash_import_session import CashImportSessionService

        self.preview_calls.append((files, kwargs))
        return {
            "batch_digest": CashImportSessionService._batch_digest(files),
            "channel": "mixed",
            "relation_digest": "",
            "relations": [],
        }

    def commit_import_batch(self, files, **kwargs):
        self.commit_calls.append((files, kwargs))
        return {"message": "导入完成", "new_rows": 0}


def test_workspace_batch_scan_and_password_retry_use_same_import_session(monkeypatch):
    import ft.adapters.relational.uow as uow_module
    import ft.application.relations as relations_module

    backend = _BatchImportBackend()
    monkeypatch.setattr(web_app, "CashLedgerQueryService", lambda *_args: object())
    monkeypatch.setattr(web_app, "CashLedgerCommandService", lambda *_args, **_kwargs: backend)
    monkeypatch.setattr(relations_module, "RelationService", lambda *_args, **_kwargs: object())
    monkeypatch.setattr(uow_module, "RelationalUnitOfWork", lambda *_args, **_kwargs: object())

    staging = InMemoryImportStagingStore()
    service = web_app.WorkspaceServices(
        sessions=None,
        workspace_var=ContextVar("workspace_id", default="workspace-a"),
        user_var=ContextVar("user_id", default="user-a"),
        import_staging_store=staging,
    )

    scan = service.scan_import_batch([
        {"filename": "alipay.csv", "content": b"csv-bytes"},
        {"filename": "wechat.pdf", "content": b"pdf-bytes"},
    ])

    assert scan["import_token"]
    assert scan["ready"] is False
    assert scan["files"][1]["status"] == "password_required"

    ready = service.scan_import_batch_session(scan["import_token"], passwords={"1": "secret"})

    assert ready["import_token"] == scan["import_token"]
    assert ready["ready"] is True
    assert backend.calls == [
        ([
            ("alipay.csv", b"csv-bytes"),
            ("wechat.pdf", b"pdf-bytes"),
        ], {}),
        ([
            ("alipay.csv", b"csv-bytes"),
            ("wechat.pdf", b"pdf-bytes"),
        ], {"1": "secret"}),
    ]

    preview = service.preview_import_batch_session(scan["import_token"], mapping=[])
    assert preview["import_token"] == scan["import_token"]
    assert preview["batch_digest"]
    assert backend.preview_calls[0][1]["mapping"] == []

    result = service.commit_import_batch_session(
        scan["import_token"],
        preview_digest=preview["batch_digest"],
        preview_channel=preview["channel"],
        relation_decisions=[],
        mapping=[],
        idempotency_key="batch-commit-1",
    )
    assert result["new_rows"] == 0
    assert backend.commit_calls[0][1]["relation_decisions"] == []
