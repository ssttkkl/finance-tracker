"""现金导入的严格请求边界和当次上传资源。"""
from ft.web.cash_diagnostic_protocol import CashDiagnosticView

from contextlib import asynccontextmanager
import json
import math
import re
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field
from starlette.datastructures import UploadFile
from starlette.concurrency import run_in_threadpool
from starlette.formparsers import MultiPartException, MultiPartParser

from ft.application.cash_import_context import CashImportUpload


MAX_FILE_BYTES = 100 * 1024 * 1024
MAX_METADATA_BYTES = 65536
MAX_REQUEST_BYTES = 20 * MAX_FILE_BYTES + 1024 * 1024
MAX_PART_HEADER_BYTES = 16384


class CashImportProtocolError(ValueError):
    def __init__(self, code="import_payload_invalid"):
        self.code = code
        super().__init__(code)


class StrictInput(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid")


Revision = Annotated[int, Field(strict=True, gt=0, lt=2 ** 63)]
FileModifiedAtMs = Annotated[int, Field(strict=True, ge=0, lt=2 ** 63)]


class UploadFileMetadata(StrictInput):
    client_file_ref: str = Field(pattern=r"^[A-Za-z0-9_-]{1,128}$")
    password: str | None = Field(default=None, max_length=1024, repr=False)
    source_hint: str | None = Field(default=None, pattern=r"^[a-z][a-z0-9_-]{0,63}$")
    source_scope_label: str | None = Field(default=None, max_length=64)
    last_modified_at_ms: FileModifiedAtMs | None = None


class UploadMetadata(StrictInput):
    files: list[UploadFileMetadata] = Field(min_length=1, max_length=20)


class AddUploadMetadata(UploadMetadata):
    expected_session_revision: Revision
    file_ref: str | None = Field(default=None, pattern=r"^[A-Za-z0-9_-]{1,128}$")


class SourceScopeInput(StrictInput):
    expected_session_revision: Revision
    source_scope_label: str | None = Field(max_length=64)


class CashImportIssueView(StrictInput):
    code: str
    message: str
    field_path: str | None
    blocking_scope: Literal["row", "relation_group", "file", "request"]
    next_action: Literal["edit", "reselect_file", "open_record", "review_relation", "retry", "none"]


class CashImportAccountSuggestion(StrictInput):
    account_id: str | None
    expected_account_revision: Revision | None
    basis: Literal["confirmed_mapping", "legacy_mapping", "account_alias", "none"]


class CashImportAccountSelection(StrictInput):
    account_id: str | None = None
    expected_account_revision: Revision | None = None


class CashImportFileView(StrictInput):
    diagnostic: CashDiagnosticView | None = None
    file_ref: str
    client_file_ref: str
    display_name: str
    source_type: str | None
    source_scope_label: str | None
    status: Literal["ready", "partial", "password_required", "failed"]
    recognized_row_count: int | None
    issues: list[CashImportIssueView]
    can_retry: bool


class CashImportSourceAccountView(StrictInput):
    source_account_ref: str
    source_type: str
    display_name: str
    currencies: list[str]
    row_count: int
    suggestion: CashImportAccountSuggestion
    selection: CashImportAccountSelection | None


class CashImportSessionView(StrictInput):
    session_id: str
    session_revision: int | None
    expires_at: str | None
    state: Literal["open", "committed", "cancelled"]
    files: list[CashImportFileView]
    source_accounts: list[CashImportSourceAccountView]
    last_preview_input: dict[str, Any] | None
    last_plan_id: str | None
    can_preview: bool
    receipt_id: str | None


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise CashImportProtocolError
        result[key] = value
    return result


def _constant(_value):
    raise CashImportProtocolError


def _float(value):
    number = float(value)
    if not math.isfinite(number):
        raise CashImportProtocolError
    return number


def strict_json(value, model):
    try:
        payload = json.loads(value, object_pairs_hook=_pairs, parse_constant=_constant, parse_float=_float)
    except (ValueError, RecursionError):
        raise CashImportProtocolError from None
    return model.model_validate(payload)


async def json_input(request, model, *, max_bytes=MAX_METADATA_BYTES):
    chunks, size = [], 0
    async for chunk in request.stream():
        size += len(chunk)
        if size > max_bytes:
            raise CashImportProtocolError
        chunks.append(chunk)
    return await run_in_threadpool(strict_json, b"".join(chunks), model)


def expected_revision(request):
    value = request.headers.get("If-Match", "")
    if not re.fullmatch(r'"[1-9][0-9]{0,18}"|[1-9][0-9]{0,18}', value):
        raise CashImportProtocolError
    revision = int(value.strip('"'))
    if revision >= 2 ** 63:
        raise CashImportProtocolError
    return revision


class _BoundedMultipartParser(MultiPartParser):
    complete = False

    def on_end(self):
        self.complete = True

    def close_uploads(self):
        # 表单只包含已经结束的部分；解析器还可能持有未结束文件。
        for file in self._files_to_close_on_error:
            file.close()

    def on_part_begin(self):
        super().on_part_begin()
        self._header_bytes = 0

    def _header(self, start, end):
        self._header_bytes += end - start
        if self._header_bytes > MAX_PART_HEADER_BYTES:
            raise CashImportProtocolError

    def on_header_field(self, data, start, end):
        self._header(start, end)
        super().on_header_field(data, start, end)

    def on_header_value(self, data, start, end):
        self._header(start, end)
        super().on_header_value(data, start, end)

    def on_header_end(self):
        self._header(0, 4)
        super().on_header_end()


async def _bounded_stream(request):
    size = 0
    async for chunk in request.stream():
        size += len(chunk)
        if size > MAX_REQUEST_BYTES:
            raise CashImportProtocolError("upload_too_large")
        yield chunk


@asynccontextmanager
async def upload_input(request, model):
    content_type = request.headers.get("Content-Type", "")
    length = request.headers.get("Content-Length")
    if len(content_type) > 1024 or not content_type.lower().startswith("multipart/form-data;"):
        raise CashImportProtocolError
    if length is not None:
        if not re.fullmatch(r"[0-9]{1,20}", length):
            raise CashImportProtocolError
        if int(length) > MAX_REQUEST_BYTES:
            raise CashImportProtocolError("upload_too_large")
    parser = _BoundedMultipartParser(request.headers, _bounded_stream(request),
        max_files=20, max_fields=1, max_part_size=MAX_METADATA_BYTES)
    try:
        form = await parser.parse()
        if not parser.complete:
            raise CashImportProtocolError
        items = form.multi_items()
        texts = [(key, item) for key, item in items if not isinstance(item, UploadFile)]
        if len(texts) != 1 or texts[0][0] != "metadata" or len(texts[0][1].encode("utf-8")) > MAX_METADATA_BYTES:
            raise CashImportProtocolError
        metadata = strict_json(texts[0][1], model)
        parts = [(key, item) for key, item in items if isinstance(item, UploadFile)]
        wanted = ["file:" + item.client_file_ref for item in metadata.files]
        if len(set(wanted)) != len(wanted) or len(parts) != len(wanted) or {key for key, _ in parts} != set(wanted):
            raise CashImportProtocolError
        if getattr(metadata, "file_ref", None) is not None and len(parts) != 1:
            raise CashImportProtocolError
        found = dict(parts)
        uploads = []
        for item in metadata.files:
            part = found["file:" + item.client_file_ref]
            # 明确 null 和缺省不同；空字符串经领域规范化后表示默认来源。
            scope = item.source_scope_label
            if scope is None and "source_scope_label" in item.model_fields_set:
                scope = ""
            uploads.append(CashImportUpload(item.client_file_ref, part.filename or "账单", part.file,
                password=item.password, source_hint=item.source_hint, source_scope_label=scope,
                last_modified_at_ms=item.last_modified_at_ms))
        yield metadata, tuple(uploads)
    except MultiPartException:
        raise CashImportProtocolError from None
    finally:
        # 同步关闭不留取消窗口，也不依赖底层版本对异常的清理方式。
        parser.close_uploads()
