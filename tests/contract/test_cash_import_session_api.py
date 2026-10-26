"""逐文件会话 HTTP 合同；不通过旧原文件会话兜底。"""
from contextvars import ContextVar
from dataclasses import replace
from datetime import timedelta
import json

from fastapi.testclient import TestClient
import pytest

from ft.application.cash_import_context import CashImportContextService, CashImportPasswordRequired
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from tests.contract.test_parsed_cash_import_context import USER, _consume, _document, context_runtime


def _client(runtime, monkeypatch, *, parser=None, store=None, user_id=USER, role="editor"):
    from ft.application.access import AuthenticationRequired
    from ft.web.app import WorkspaceServices, create_app
    workspace, user = ContextVar("cash_session_workspace", default=None), ContextVar("cash_session_user", default=None)
    class Access:
        def require_context(self, token, _roles):
            if token != "fixture-token":
                raise AuthenticationRequired("authentication_required")
            return runtime.workspace_id, role, user_id
    store = store or InMemoryParsedImportStore()
    service = WorkspaceServices(runtime.sessions, workspace, user_var=user, parsed_import_store=store)
    def context(self):
        return CashImportContextService(self._uow(), self._parsed_import_store, user_id=self._user(),
            parser=parser or (lambda _upload: _document()))
    monkeypatch.setattr(WorkspaceServices, "parsed_import_context", context)
    app = create_app(service, access_service=Access(), workspace_context=workspace, user_context=user,
        operations_service=service)
    client = TestClient(app)
    client.headers["Authorization"] = "Bearer fixture-token"
    return client, store


def _upload(client, *, metadata=None, files=None, path="/api/v1/cash-import/sessions"):
    metadata = metadata if metadata is not None else {"files": [{"client_file_ref": "client-a"}]}
    parts = [("metadata", (None, json.dumps(metadata, ensure_ascii=False)))]
    parts.extend(files if files is not None else [("file:client-a", ("synthetic.csv", b"synthetic-original", "text/csv"))])
    return client.post(path, files=parts)


def _spools(monkeypatch):
    import starlette.formparsers as forms
    original, opened = forms.SpooledTemporaryFile, []
    def tracked(*args, **kwargs):
        file = original(*args, **kwargs)
        opened.append(file)
        return file
    monkeypatch.setattr(forms, "SpooledTemporaryFile", tracked)
    return opened


def test_upload_isolates_bad_file_and_exposes_only_safe_session_view(context_runtime, monkeypatch):
    calls = []
    def parser(upload):
        calls.append(upload.client_file_ref)
        assert type(upload.content) is bytes
        if upload.client_file_ref == "client-b":
            raise RuntimeError("private SQL /tmp/private.csv private-password")
        return _document()
    opened = _spools(monkeypatch)
    client, store = _client(context_runtime, monkeypatch, parser=parser)
    result = _upload(client, metadata={"files": [{"client_file_ref": "client-a", "password": "private-password"},
        {"client_file_ref": "client-b"}]}, files=[
            ("file:client-a", ("synthetic-pwd=private-password.csv", b"synthetic-original")),
            ("file:client-b", ("synthetic-b.csv", b"bad-original"))])
    assert result.status_code == 201
    view = result.json()
    assert view["session_revision"] == 1 and view["state"] == "open" and view["can_preview"] is True
    assert calls == ["client-a", "client-b"]
    assert [file["status"] for file in view["files"]] == ["ready", "failed"]
    assert [file["recognized_row_count"] for file in view["files"]] == [1, None]
    assert view["files"][1]["issues"][0]["code"] == "statement_unreadable"
    assert len(opened) == 2 and all(file.closed for file in opened)
    read = client.get("/api/v1/cash-import/sessions/" + view["session_id"])
    assert read.status_code == 200 and read.json() == view and calls == ["client-a", "client-b"]
    for secret in ("private-password", "synthetic-original", "bad-original", "fixture-owner", "source_payload", "digest", "SQL", "parser_version"):
        assert secret not in read.text
    raw = store._entries[view["session_id"]]
    assert b"private-password" not in raw and b"synthetic-original" not in raw


def test_missing_alipay_funding_exposes_wallet_fallback_group(context_runtime, monkeypatch):
    original = _document()
    row = original.rows[0]
    fields = {**row.fields, "source_payload": {**row.fields["source_payload"], "商品说明": ""}}
    document = replace(original, rows=(replace(row, payment_method="", fields=fields),))
    client, _ = _client(context_runtime, monkeypatch, parser=lambda _upload: document)
    view = _upload(client).json()
    assert view["can_preview"] is True
    account, = view["source_accounts"]
    assert account["display_name"] == "支付宝余额"


@pytest.mark.parametrize("code", ["statement_header_not_found", "statement_header_invalid", "statement_encoding_unsupported"])
def test_known_file_format_failure_provides_safe_reselect_action(context_runtime, monkeypatch, code):
    from ft.adapters.cash_statement_document import StatementDocumentError
    def parser(_upload):
        raise StatementDocumentError(code)
    client, _ = _client(context_runtime, monkeypatch, parser=parser)
    response = _upload(client)
    assert response.status_code == 201
    issue, = response.json()["files"][0]["issues"]
    assert issue["code"] == code and issue["next_action"] == "reselect_file"
    assert "重新导出" in issue["message"] and "表头" not in issue["message"] and "编码" not in issue["message"]


def test_password_retry_parses_only_replaced_file_and_keeps_client_slot(context_runtime, monkeypatch):
    calls = []
    def parser(upload):
        calls.append(upload.client_file_ref)
        if upload.client_file_ref == "client-a" and upload.password != "synthetic-correct":
            raise CashImportPasswordRequired()
        return _document()
    client, _ = _client(context_runtime, monkeypatch, parser=parser)
    view = _upload(client, metadata={"files": [{"client_file_ref": "client-a"}, {"client_file_ref": "client-b"}]}, files=[
        ("file:client-a", ("synthetic.pdf", b"encrypted")), ("file:client-b", ("synthetic.csv", b"okay"))]).json()
    assert view["files"][0]["status"] == "password_required"
    path = "/api/v1/cash-import/sessions/" + view["session_id"] + "/files"
    body = {"expected_session_revision": 1, "file_ref": view["files"][0]["file_ref"],
        "files": [{"client_file_ref": "new-picker-ref", "password": "synthetic-correct"}]}
    response = _upload(client, metadata=body, path=path,
        files=[("file:new-picker-ref", ("synthetic.pdf", b"encrypted"))])
    assert response.status_code == 200
    updated = response.json()
    assert calls == ["client-a", "client-b", "client-a"]
    assert updated["session_revision"] == 2
    assert updated["files"][0]["client_file_ref"] == "client-a"
    assert updated["files"][0]["file_ref"] != view["files"][0]["file_ref"]
    assert updated["files"][1] == view["files"][1]
    assert _upload(client, metadata=body, path=path,
        files=[("file:new-picker-ref", ("synthetic.pdf", b"encrypted"))]).status_code == 409
    assert calls == ["client-a", "client-b", "client-a"]


@pytest.mark.parametrize("metadata", [
    '{"files":[],"files":[]}', '{"files":[{"client_file_ref":"client-a","password":123}]}',
    '{"files":[{"client_file_ref":"client-a"}],"workspace_id":"private-workspace"}',
    '{"files":[{"client_file_ref":"client-a"}],"expected_session_revision":1.0}',
    '{"files":[{"client_file_ref":"client-a"}],"expected_session_revision":true}',
    '{"files":[{"client_file_ref":"client-a"}],"anything":NaN}', '[]', 'not json',
])
def test_bad_upload_protocol_is_safe_and_closes_files(context_runtime, monkeypatch, metadata):
    opened = _spools(monkeypatch)
    client, _ = _client(context_runtime, monkeypatch)
    result = client.post("/api/v1/cash-import/sessions", files=[("metadata", (None, metadata)),
        ("file:client-a", ("synthetic.csv", b"synthetic-original"))])
    assert result.status_code == 422 and result.json()["error"]["code"] == "import_payload_invalid"
    assert "private-workspace" not in result.text and "synthetic-original" not in result.text
    assert all(file.closed for file in opened)


def test_file_scope_and_remove_use_session_cas_and_keep_ttl(context_runtime, monkeypatch):
    document = replace(_document(), source_type="wechat", source_account_identifier=None)
    calls = []
    def parser(_upload):
        calls.append(1)
        return document
    client, _ = _client(context_runtime, monkeypatch, parser=parser)
    view = _upload(client).json()
    path = "/api/v1/cash-import/sessions/" + view["session_id"]
    file_path = path + "/files/" + view["files"][0]["file_ref"]
    changed = client.patch(file_path, json={"expected_session_revision": 1, "source_scope_label": "另一来源"})
    assert changed.status_code == 200 and changed.json()["session_revision"] == 2
    assert changed.json()["expires_at"] == view["expires_at"] and calls == [1]
    assert changed.json()["files"][0]["source_scope_label"] == "另一来源"
    assert client.delete(file_path, headers={"If-Match": "1"}).status_code == 409
    assert client.delete(file_path, headers={"If-Match": '"2"'}).status_code == 200
    empty = client.get(path).json()
    assert empty["files"] == [] and empty["source_accounts"] == [] and empty["can_preview"] is False
    assert client.delete(path).status_code == 200
    assert client.get(path).json()["state"] == "cancelled"


def test_explicit_null_scope_on_reupload_returns_to_default(context_runtime, monkeypatch):
    document = replace(_document(), source_type="wechat", source_account_identifier=None)
    client, _ = _client(context_runtime, monkeypatch, parser=lambda _upload: document)
    view = _upload(client, metadata={"files": [{"client_file_ref": "client-a", "source_scope_label": "另一来源"}]}).json()
    result = _upload(client, path="/api/v1/cash-import/sessions/" + view["session_id"] + "/files",
        metadata={"expected_session_revision": 1, "file_ref": view["files"][0]["file_ref"],
            "files": [{"client_file_ref": "client-a", "source_scope_label": None}]})
    assert result.status_code == 200 and result.json()["files"][0]["source_scope_label"] is None


def test_viewer_is_rejected_before_upload_files_are_opened(context_runtime, monkeypatch):
    from ft.adapters.relational.models import WorkspaceMembershipModel
    opened = _spools(monkeypatch)
    calls = []
    client, _ = _client(context_runtime, monkeypatch, parser=lambda _upload: calls.append(1))
    with context_runtime.sessions.begin() as session:
        session.get(WorkspaceMembershipModel, (context_runtime.workspace_id, USER)).role = "viewer"
    assert _upload(client).status_code == 403
    assert calls == [] and opened == []


def test_oversize_file_skips_only_that_file_and_keeps_other_file(context_runtime, monkeypatch):
    import ft.application.cash_import_context as context
    monkeypatch.setattr(context, "_MAX_UPLOAD_BYTES", 8)
    opened, calls = _spools(monkeypatch), []
    def parser(upload):
        calls.append(upload.client_file_ref)
        return _document()
    client, _ = _client(context_runtime, monkeypatch, parser=parser)
    response = _upload(client, metadata={"files": [{"client_file_ref": "client-a"}, {"client_file_ref": "client-b"}]},
        files=[("file:client-a", ("synthetic-large.csv", b"x" * 20)), ("file:client-b", ("synthetic-small.csv", b"okay"))])
    assert response.status_code == 201
    files = response.json()["files"]
    assert [file["status"] for file in files] == ["failed", "ready"]
    assert files[0]["issues"][0]["code"] == "statement_file_too_large" and calls == ["client-b"]
    assert response.json()["can_preview"] is True and all(file.closed for file in opened)


def test_other_operator_cannot_read_or_modify_session(context_runtime, monkeypatch):
    from ft.adapters.relational.models import UserModel, WorkspaceMembershipModel
    other = "00000000-0000-4000-8000-000000000002"
    client, store = _client(context_runtime, monkeypatch)
    view = _upload(client).json()
    with context_runtime.sessions.begin() as session:
        session.add(UserModel(id=other, email="other-cash-import@example.test", password_hash="synthetic"))
        session.flush()
        session.add(WorkspaceMembershipModel(user_id=other, workspace_id=context_runtime.workspace_id, role="editor"))
    outsider, _ = _client(context_runtime, monkeypatch, store=store, user_id=other)
    path = "/api/v1/cash-import/sessions/" + view["session_id"]
    assert outsider.get(path).status_code == 404
    assert outsider.delete(path).status_code == 404
    assert client.get(path).status_code == 200


def test_current_suggestion_is_distinct_from_saved_user_selection(context_runtime, monkeypatch):
    from ft.adapters.relational.models import AccountModel, StatementAccountMappingModel
    client, store = _client(context_runtime, monkeypatch)
    view = _upload(client).json()
    group, = view["source_accounts"]
    assert group["selection"] is None and group["suggestion"]["account_id"] is None
    with context_runtime.sessions.begin() as session:
        session.get(AccountModel, 101).currencies = ["CNY"]
        session.add(StatementAccountMappingModel(workspace_id=context_runtime.workspace_id, source_type="alipay",
            identity_kind="funding_group_v1", source_account_key=group["source_account_ref"], account_id=101))
    selected = {"source_account_ref": group["source_account_ref"], "account_id": "102", "expected_account_revision": 1}
    current = store.get(view["session_id"], workspace_id=context_runtime.workspace_id, user_id=USER)
    store.replace(view["session_id"], workspace_id=context_runtime.workspace_id, user_id=USER,
        expected_revision=1, files=current.files, plan=None,
        preview_input={"account_selections": [selected], "row_choices": [], "relation_choices": []})
    response = client.get("/api/v1/cash-import/sessions/" + view["session_id"])
    assert response.status_code == 200
    account, = response.json()["source_accounts"]
    assert account["suggestion"]["account_id"] == "101"
    assert account["selection"] == {"account_id": "102", "expected_account_revision": 1}


@pytest.mark.parametrize("consumed", [False, True])
def test_expired_session_uses_durable_receipt_before_temporary_storage(context_runtime, monkeypatch, consumed):
    from datetime import datetime, timezone
    clock = [datetime(2026, 7, 1, tzinfo=timezone.utc)]
    store = InMemoryParsedImportStore(clock=lambda: clock[0])
    client, _ = _client(context_runtime, monkeypatch, store=store)
    view = _upload(client).json()
    receipt = _consume(context_runtime, view["session_id"]) if consumed else None
    clock[0] += timedelta(days=2)
    response = client.get("/api/v1/cash-import/sessions/" + view["session_id"])
    if not consumed:
        assert response.status_code == 410
    else:
        assert response.status_code == 200
        recovered = response.json()
        assert recovered["state"] == "committed" and recovered["receipt_id"] == receipt["receipt_id"]
        assert recovered["session_revision"] is None and recovered["expires_at"] is None
        assert recovered["files"] == [] and recovered["source_accounts"] == [] and recovered["can_preview"] is False


def test_session_api_read_failure_and_cors_are_safe(context_runtime, monkeypatch):
    from sqlalchemy.exc import OperationalError
    client, _ = _client(context_runtime, monkeypatch)
    def fail(*_args, **_kwargs):
        raise OperationalError("private SQL", {}, Exception("private provider details"))
    monkeypatch.setattr(CashImportContextService, "get_view", fail, raising=False)
    response = client.get("/api/v1/cash-import/sessions/" + "a" * 43)
    assert response.status_code == 503
    assert "SQL" not in response.text and "provider" not in response.text
    cors = client.options("/api/v1/cash-import/sessions/" + "a" * 43,
        headers={"Origin": "http://127.0.0.1:5173", "Access-Control-Request-Method": "DELETE", "Access-Control-Request-Headers": "If-Match"})
    assert cors.status_code == 200
