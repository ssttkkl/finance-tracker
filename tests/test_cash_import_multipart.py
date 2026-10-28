"""流式协议的资源边界，不使用数据库或真实账单。"""
import asyncio
import json
from types import SimpleNamespace

from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest
from starlette.requests import ClientDisconnect, Request

from ft.web.cash_import_protocol import CashImportProtocolError, UploadMetadata, upload_input


def _part(name, content, *, filename=None, ended=True):
    value = b'--synthetic\r\nContent-Disposition: form-data; name="' + name.encode() + b'"'
    if filename is not None:
        value += b'; filename="' + filename.encode() + b'"'
    return value + b'\r\n\r\n' + content + (b'\r\n' if ended else b'')


def _prefix():
    return _part("metadata", json.dumps({"files": [{"client_file_ref": "a"}]}).encode())


def _opened(monkeypatch):
    import starlette.formparsers as forms
    original, opened = forms.SpooledTemporaryFile, []
    def tracked(*args, **kwargs):
        file = original(*args, **kwargs)
        opened.append(file)
        return file
    monkeypatch.setattr(forms, "SpooledTemporaryFile", tracked)
    return opened


async def _read(chunks, *, interrupt=None):
    index = 0
    async def receive():
        nonlocal index
        if index == len(chunks):
            if interrupt is not None:
                raise interrupt
            return {"type": "http.disconnect"}
        value = chunks[index]
        index += 1
        return {"type": "http.request", "body": value, "more_body": interrupt is not None or index < len(chunks)}
    request = Request({"type": "http", "method": "POST", "path": "/", "headers": [
        (b"content-type", b"multipart/form-data; boundary=synthetic")]}, receive=receive)
    async with upload_input(request, UploadMetadata) as (_metadata, uploads):
        return [upload.content.read() for upload in uploads]


@pytest.mark.parametrize("extra", [False, True])
def test_truncated_multipart_is_rejected_and_every_created_file_is_closed(monkeypatch, extra):
    opened = _opened(monkeypatch)
    body = _prefix() + _part("file:a", b"one", filename="synthetic.csv", ended=extra)
    if extra:
        body += _part("file:undeclared", b"unfinished", filename="unfinished.csv", ended=False)
    with pytest.raises(CashImportProtocolError):
        asyncio.run(_read([body]))
    assert opened and all(file.closed for file in opened)


def test_actual_chunked_stream_limit_closes_already_open_file(monkeypatch):
    import ft.web.cash_import_protocol as protocol
    opened = _opened(monkeypatch)
    first = _prefix() + _part("file:a", b"one", filename="synthetic.csv", ended=False)
    monkeypatch.setattr(protocol, "MAX_REQUEST_BYTES", len(first) + 8)
    with pytest.raises(CashImportProtocolError, match="upload_too_large"):
        asyncio.run(_read([first, b"x" * 50]))
    assert opened and all(file.closed for file in opened)


def test_header_limit_after_first_file_closes_all_created_files(monkeypatch):
    opened = _opened(monkeypatch)
    first = _prefix() + _part("file:a", b"one", filename="synthetic.csv")
    body = first + _part("file:b", b"two", filename="x" * 17000) + b"--synthetic--\r\n"
    with pytest.raises(CashImportProtocolError):
        asyncio.run(_read([body]))
    assert opened and all(file.closed for file in opened)


@pytest.mark.parametrize("error", [ClientDisconnect(), asyncio.CancelledError()])
def test_transport_interruption_closes_files(monkeypatch, error):
    opened = _opened(monkeypatch)
    body = _prefix() + _part("file:a", b"unfinished", filename="synthetic.csv", ended=False)
    with pytest.raises(type(error)):
        asyncio.run(_read([body], interrupt=error))
    assert opened and all(file.closed for file in opened)


def test_normal_stream_files_live_only_inside_request(monkeypatch):
    opened = _opened(monkeypatch)
    body = _prefix() + _part("file:a", b"one", filename="synthetic.csv") + b"--synthetic--\r\n"
    assert asyncio.run(_read([body])) == [b"one"]
    assert opened and all(file.closed for file in opened)


@pytest.mark.parametrize("kind", ["files", "metadata"])
def test_count_and_text_limits_close_earlier_files(monkeypatch, kind):
    opened = _opened(monkeypatch)
    if kind == "files":
        body = _prefix() + b"".join(_part(f"file:{index}", b"one", filename="synthetic.csv") for index in range(21))
    else:
        body = _part("file:a", b"one", filename="synthetic.csv") + _part("metadata", b" " * 65537 + b"{}")
    with pytest.raises(CashImportProtocolError):
        asyncio.run(_read([body + b"--synthetic--\r\n"]))
    assert opened and all(file.closed for file in opened)


def test_display_filename_scrubs_password_and_full_account():
    from ft.application.cash_import_context import _display_filename
    value = _display_filename("银行_1234567890123456_密码=synthetic-secret.pdf", "synthetic-secret")
    assert "1234567890123456" not in value and "synthetic-secret" not in value
    assert "3456" in value and value.endswith(".pdf")


@pytest.mark.parametrize("filename,secret", [
    ("银行账单.1234567890123456", "1234567890123456"),
    ("银行账单.password=synthetic-secret", "synthetic-secret"),
])
def test_last_filename_segment_cannot_bypass_redaction(filename, secret):
    from ft.application.cash_import_context import _display_filename
    assert secret not in _display_filename(filename, None)


def test_public_upload_openapi_has_resolvable_references():
    from ft.web.cash_import_routes import cash_import_router
    app = FastAPI()
    app.include_router(cash_import_router(SimpleNamespace()))
    document = TestClient(app).get("/openapi.json").json()
    def visit(value):
        if isinstance(value, dict):
            if isinstance(value.get("$ref"), str) and value["$ref"].startswith("#/"):
                resolved = document
                for part in value["$ref"][2:].split("/"):
                    resolved = resolved[part.replace("~1", "/").replace("~0", "~")]
            for item in value.values():
                visit(item)
        elif isinstance(value, list):
            for item in value:
                visit(item)
    visit(document)
    schema = document["paths"]["/api/v1/cash-import/sessions"]["post"]["requestBody"]["content"]["multipart/form-data"]["schema"]
    metadata = schema["properties"]["metadata"]["contentSchema"]
    assert metadata["additionalProperties"] is False
    assert metadata["properties"]["files"]["items"]["properties"]["client_file_ref"]["type"] == "string"
