from __future__ import annotations

import hashlib
import pytest

from ft.application.cash_import_session import CashImportSessionService
from ft.application.cash_import_staging import ImportSessionPasswordRequired, InMemoryImportStagingStore


class _RecordingCashImport:
    def __init__(self):
        self.calls: list[tuple[str, bytes]] = []

    def scan_import(self, content, **kwargs):
        self.calls.append(("scan", content))
        digest = hashlib.sha256(content).hexdigest()
        return {"channel": "alipay", "digest": digest, "file": {"name": kwargs["filename"], "digest": digest}, "groups": [], "accounts": []}

    def preview_import(self, content, **kwargs):
        self.calls.append(("preview", content))
        digest = hashlib.sha256(content).hexdigest()
        return {"channel": "alipay", "file": {"name": kwargs["filename"], "digest": digest}, "items": [], "relations": [], "summary": {"total": 0, "new": 0, "existing": 0, "unsupported": 0}}

    def commit_import(self, content, **kwargs):
        self.calls.append(("commit", content))
        return {"message": "导入完成", "new_rows": 1, "updated_rows": 0, "channel": "alipay", "digest": hashlib.sha256(content).hexdigest()}


class _PasswordCashImport(_RecordingCashImport):
    def scan_import(self, content, **kwargs):
        from ft.importers.pdf_tools import PDFPasswordRequiredError

        self.calls.append(("scan", content))
        if not kwargs.get("password"):
            raise PDFPasswordRequiredError("statement.pdf")
        digest = hashlib.sha256(content).hexdigest()
        return {"channel": "alipay", "digest": digest, "file": {"name": kwargs["filename"], "digest": digest}, "groups": [], "accounts": []}


class _IdempotentCashImport(_RecordingCashImport):
    def __init__(self):
        super().__init__()
        self.results = {}

    def get_import_commit_result(self, idempotency_key, *, idempotency_scope, user_id):
        return self.results.get((idempotency_key, idempotency_scope))

    def commit_import(self, content, **kwargs):
        result = super().commit_import(content, **kwargs)
        self.results[(kwargs["idempotency_key"], kwargs["idempotency_scope"])] = result
        return result


class _MismatchedChannelCashImport(_RecordingCashImport):
    def preview_import(self, content, **kwargs):
        result = super().preview_import(content, **kwargs)
        result["channel"] = "wechat"
        return result


def test_session_service_reuses_one_staged_source_for_preview_and_commit():
    backend = _RecordingCashImport()
    service = CashImportSessionService(
        backend,
        InMemoryImportStagingStore(),
        workspace_id="workspace-a",
        user_id="user-a",
    )

    scan = service.scan_import(b"statement-bytes", filename="statement.csv")
    token = scan["import_token"]
    preview = service.preview_import_session(token, mapping=[], password=None)
    result = service.commit_import_session(
        token,
        mapping=[],
        relation_decisions=[],
        idempotency_key="commit-1",
    )

    assert preview["import_token"] == token
    assert result["message"] == "导入完成"
    assert backend.calls == [
        ("scan", b"statement-bytes"),
        ("preview", b"statement-bytes"),
        ("commit", b"statement-bytes"),
    ]


def test_password_required_scan_keeps_session_for_password_retry_without_reupload():
    backend = _PasswordCashImport()
    service = CashImportSessionService(
        backend,
        InMemoryImportStagingStore(),
        workspace_id="workspace-a",
        user_id="user-a",
    )

    with pytest.raises(ImportSessionPasswordRequired) as failure:
        service.scan_import(b"encrypted-pdf", filename="statement.pdf")

    result = service.scan_import_session(failure.value.token, password="correct")
    assert result["import_token"] == failure.value.token
    assert [kind for kind, _content in backend.calls] == ["scan", "scan"]


def test_idempotent_retry_returns_saved_result_after_session_cleanup():
    backend = _IdempotentCashImport()
    service = CashImportSessionService(
        backend,
        InMemoryImportStagingStore(),
        workspace_id="workspace-a",
        user_id="user-a",
    )

    scan = service.scan_import(b"statement-bytes", filename="statement.csv")
    token = scan["import_token"]
    first = service.commit_import_session(token, mapping=[], relation_decisions=[], idempotency_key="commit-1")
    second = service.commit_import_session(token, mapping=[], relation_decisions=[], idempotency_key="commit-1")

    assert second == first
    assert [kind for kind, _content in backend.calls] == ["scan", "commit"]


def test_preview_rejects_a_channel_change_within_the_same_session():
    backend = _MismatchedChannelCashImport()
    service = CashImportSessionService(
        backend,
        InMemoryImportStagingStore(),
        workspace_id="workspace-a",
        user_id="user-a",
    )

    scan = service.scan_import(b"statement-bytes", filename="statement.csv")
    with pytest.raises(ValueError, match="import_preview_stale"):
        service.preview_import_session(scan["import_token"], mapping=[])
