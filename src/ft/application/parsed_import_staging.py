"""仅保存解析内容的短期会话；对象 CAS 不能代替应用层工作区锁。"""
from __future__ import annotations

from dataclasses import asdict, dataclass, fields, replace
from datetime import datetime, timedelta, timezone
from hashlib import sha256
import json
import math
import re
from secrets import token_urlsafe
from threading import RLock
from typing import Any

from ft.adapters.cash_statement_rows import ParsedCashDocument, ParsedCashRow
from ft.domain.cash_source_matching import SourceCoverage
from ft.application.cash_import_staging import (
    ImportSessionError, ImportSessionExpired, ImportSessionForbidden,
    ImportSessionNotFound, ImportSessionStorageUnavailable,
)


class ParsedImportDataError(ImportSessionError):
    pass


class ImportSessionRevisionConflict(ImportSessionError):
    def __init__(self):
        super().__init__("import_session_revision_conflict")


class ImportSessionClosed(ImportSessionError):
    def __init__(self):
        super().__init__("import_session_closed")


@dataclass(frozen=True)
class ParsedImportFile:
    file_ref: str
    client_file_ref: str
    filename: str
    digest: str
    size: int
    source_type: str | None
    status: str
    document: ParsedCashDocument | None = None
    error_code: str | None = None


@dataclass(frozen=True)
class ParsedImportSession:
    session_id: str
    workspace_id: str
    user_id: str
    created_at: datetime
    expires_at: datetime
    revision: int
    files: tuple[ParsedImportFile, ...]
    preview_input: dict[str, Any]
    plan: dict[str, Any] | None
    cancelled: bool = False


def _now():
    return datetime.now(timezone.utc)


def _hash(value):
    return sha256(value.encode()).hexdigest()


def _valid_files(files):
    if not isinstance(files, tuple) or len(files) > 20:
        raise ParsedImportDataError("import_session_file_count_invalid")
    refs, client_refs = set(), set()
    for item in files:
        if not isinstance(item, ParsedImportFile) or any(not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", value) for value in (item.file_ref, item.client_file_ref)):
            raise ParsedImportDataError("import_session_file_invalid")
        if item.file_ref in refs or item.client_file_ref in client_refs:
            raise ParsedImportDataError("import_session_file_reference_conflict")
        refs.add(item.file_ref); client_refs.add(item.client_file_ref)
        if (not isinstance(item.filename, str) or not item.filename or len(item.filename) > 255 or "/" in item.filename or "\\" in item.filename
            or not isinstance(item.digest, str) or not re.fullmatch(r"[0-9a-f]{64}", item.digest)
            or type(item.size) is not int or not 0 <= item.size < 2 ** 63
            or item.status == "parsed" and item.size > 100 * 1024 * 1024
            or item.status not in {"parsed", "needs_password", "failed"}
            or item.source_type is not None and (not isinstance(item.source_type, str) or not re.fullmatch(r"[a-z][a-z0-9_-]{0,63}", item.source_type))
            or item.error_code is not None and (not isinstance(item.error_code, str) or not re.fullmatch(r"[a-z][a-z0-9_]{0,63}", item.error_code))
            or (item.status == "parsed") != isinstance(item.document, ParsedCashDocument)
            or item.document is not None and item.document.source_type != item.source_type):
            raise ParsedImportDataError("import_session_file_invalid")
        if item.document is not None:
            doc = item.document
            if not isinstance(doc.rows, tuple) or len(doc.rows) > 100_000 or not isinstance(doc.coverage, SourceCoverage) or type(doc.coverage.complete) is not bool:
                raise ParsedImportDataError("import_session_document_invalid")
            positions = set()
            for row in doc.rows:
                if (not isinstance(row, ParsedCashRow) or type(row.position) is not int or not 1 <= row.position <= 200_000
                    or row.position in positions or row.disposition not in {"candidate", "skipped"}
                    or row.fields is not None and not isinstance(row.fields, dict)
                    or row.disposition == "candidate" and row.fields is None
                    or not isinstance(row.payment_method, str)
                    or row.provider_id is not None and (not isinstance(row.provider_id, str) or not 1 <= len(row.provider_id) <= 512)
                    or not isinstance(row.issue_codes, tuple)
                    or any(not isinstance(code, str) or not re.fullmatch(r"[a-z][a-z0-9_]{0,63}", code) for code in (*row.issue_codes, *((row.error_code,) if row.error_code is not None else ())))):
                    raise ParsedImportDataError("import_session_document_invalid")
                positions.add(row.position)


def _json_budget(value, budget, *, depth=0):
    if depth > 64:
        raise ParsedImportDataError("import_session_json_invalid")
    if isinstance(value, str):
        try:
            budget[0] -= len(value.encode("utf-8"))
        except UnicodeError:
            raise ParsedImportDataError("import_session_json_invalid") from None
    elif value is None or type(value) in (bool, int):
        budget[0] -= len(str(value))
    elif type(value) is float:
        if not math.isfinite(value):
            raise ParsedImportDataError("import_session_json_invalid")
        budget[0] -= len(str(value))
    elif isinstance(value, datetime):
        budget[0] -= len(value.isoformat())
    elif type(value) in (ParsedImportSession, ParsedImportFile, ParsedCashDocument, ParsedCashRow, SourceCoverage):
        for field in fields(value):
            _json_budget(getattr(value, field.name), budget, depth=depth + 1)
    elif isinstance(value, (list, tuple)):
        budget[0] -= 2 + len(value)
        for item in value:
            _json_budget(item, budget, depth=depth + 1)
    elif isinstance(value, dict) and all(isinstance(key, str) for key in value):
        budget[0] -= 2 + 2 * len(value)
        for key, item in value.items():
            _json_budget(key, budget, depth=depth + 1)
            _json_budget(item, budget, depth=depth + 1)
    else:
        raise ParsedImportDataError("import_session_json_invalid")
    if budget[0] < 0:
        raise ParsedImportDataError("import_session_draft_too_large")


def _restore_file(item):
    item = dict(item)
    doc = item.get("document")
    if doc is not None:
        doc = dict(doc)
        doc["rows"] = tuple(ParsedCashRow(**dict(row, issue_codes=tuple(row["issue_codes"]))) for row in doc["rows"])
        doc["coverage"] = SourceCoverage(**doc["coverage"])
        item["document"] = ParsedCashDocument(**doc)
    return ParsedImportFile(**item)


class _ParsedImportStore:
    def __init__(self, *, ttl_seconds=1800, clock=_now, max_bytes=25 * 1024 * 1024):
        if type(ttl_seconds) is not int or not 1 <= ttl_seconds <= 1800 or type(max_bytes) is not int or max_bytes < 1:
            raise ValueError("import_session_storage_config_invalid")
        self._ttl, self._clock, self._max_bytes = ttl_seconds, clock, max_bytes

    def validate_file(self, item):
        _valid_files((item,))
        _json_budget(item, [self._max_bytes])
        try:
            content = json.dumps(asdict(item), ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode()
            if len(content) > self._max_bytes:
                raise ParsedImportDataError("import_session_draft_too_large")
            _valid_files((_restore_file(json.loads(content)),))
        except (TypeError, KeyError, AttributeError, RecursionError):
            raise ParsedImportDataError("import_session_document_invalid") from None

    def _encode(self, session):
        _valid_files(session.files)
        if not isinstance(session.preview_input, dict) or session.plan is not None and not isinstance(session.plan, dict):
            raise ParsedImportDataError("import_session_json_invalid")
        _json_budget(session, [self._max_bytes])
        value = asdict(session)
        value["session_digest"] = _hash(value.pop("session_id"))
        value["created_at"] = session.created_at.isoformat()
        value["expires_at"] = session.expires_at.isoformat()
        _json_budget(value, [self._max_bytes])
        try:
            content = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode()
        except (TypeError, ValueError, RecursionError):
            raise ParsedImportDataError("import_session_json_invalid") from None
        if len(content) > self._max_bytes:
            raise ParsedImportDataError("import_session_draft_too_large")
        return content

    def _decode(self, session_id, content):
        try:
            value = json.loads(content)
            if value.pop("session_digest") != _hash(session_id):
                raise ValueError
            files = tuple(_restore_file(item) for item in value.pop("files"))
            value["created_at"] = datetime.fromisoformat(value["created_at"])
            value["expires_at"] = datetime.fromisoformat(value["expires_at"])
            result = ParsedImportSession(session_id=session_id, files=files, **value)
            if (type(result.revision) is not int or result.revision < 1 or type(result.cancelled) is not bool
                or result.created_at.tzinfo is None or result.expires_at.tzinfo is None
                or not 0 < (result.expires_at - result.created_at).total_seconds() <= 1800
                or any(not isinstance(owner, str) or not owner or len(owner) > 128 for owner in (result.workspace_id, result.user_id))):
                raise ValueError
            self._encode(result)
            return result
        except (TypeError, ValueError, KeyError, AttributeError, RecursionError):
            raise ImportSessionStorageUnavailable from None

    def create(self, *, workspace_id, user_id, files):
        if any(not isinstance(owner, str) or not owner or len(owner) > 128 for owner in (workspace_id, user_id)):
            raise ParsedImportDataError("import_session_owner_invalid")
        if not files:
            raise ParsedImportDataError("import_session_file_count_invalid")
        now = self._clock()
        if not isinstance(now, datetime) or now.tzinfo is None:
            raise ValueError("import_session_clock_invalid")
        for _attempt in range(3):
            session = ParsedImportSession(token_urlsafe(32), workspace_id, user_id, now, now + timedelta(seconds=self._ttl), 1, files, {}, None)
            content = self._encode(session)
            self._validate_roundtrip(session.session_id, content)
            try:
                self._publish(session, content, expected=None)
                return self._decode(session.session_id, content)
            except ImportSessionRevisionConflict:
                continue
        raise ImportSessionStorageUnavailable

    def _read(self, session_id, *, workspace_id, user_id):
        if not isinstance(session_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", session_id):
            raise ImportSessionNotFound
        content, etag = self._read_raw(session_id)
        if len(content) > self._max_bytes:
            raise ImportSessionStorageUnavailable
        session = self._decode(session_id, content)
        if session.workspace_id != workspace_id or session.user_id != user_id:
            raise ImportSessionForbidden
        if self._clock() >= session.expires_at:
            self._delete_raw(session_id)
            raise ImportSessionExpired
        return session, etag

    def get(self, session_id, *, workspace_id, user_id):
        return self._read(session_id, workspace_id=workspace_id, user_id=user_id)[0]

    def _validate_roundtrip(self, session_id, content):
        try:
            self._decode(session_id, content)
        except ImportSessionStorageUnavailable:
            raise ParsedImportDataError("import_session_json_invalid") from None

    def _publish(self, session, content, *, expected):
        if self._clock() >= session.expires_at:
            raise ImportSessionExpired
        self._write_raw(session.session_id, content, expected=expected)
        if self._clock() >= session.expires_at:
            self._delete_raw(session.session_id)
            raise ImportSessionExpired

    def replace(self, session_id, *, workspace_id, user_id, expected_revision, files, preview_input, plan):
        current, etag = self._read(session_id, workspace_id=workspace_id, user_id=user_id)
        if type(expected_revision) is not int or expected_revision != current.revision:
            raise ImportSessionRevisionConflict
        if current.cancelled:
            raise ImportSessionClosed
        updated = replace(current, revision=current.revision + 1, files=files, preview_input=preview_input, plan=plan)
        content = self._encode(updated)
        self._validate_roundtrip(session_id, content)
        self._publish(updated, content, expected=etag)
        return self._decode(session_id, content)

    def cancel(self, session_id, *, workspace_id, user_id, expected_revision):
        current, etag = self._read(session_id, workspace_id=workspace_id, user_id=user_id)
        if type(expected_revision) is not int or expected_revision != current.revision:
            raise ImportSessionRevisionConflict
        if current.cancelled:
            return current
        updated = replace(current, revision=current.revision + 1, files=(), preview_input={}, plan=None, cancelled=True)
        content = self._encode(updated)
        self._validate_roundtrip(session_id, content)
        self._publish(updated, content, expected=etag)
        return self._decode(session_id, content)


class InMemoryParsedImportStore(_ParsedImportStore):
    def __init__(self, *, max_sessions=256, max_total_bytes=100 * 1024 * 1024, **options):
        super().__init__(**options)
        if type(max_sessions) is not int or max_sessions < 1 or type(max_total_bytes) is not int or max_total_bytes < 1:
            raise ValueError("import_session_storage_config_invalid")
        self._max_sessions, self._max_total_bytes = max_sessions, max_total_bytes
        self._entries = {}
        self._lock = RLock()

    def _read_raw(self, session_id):
        with self._lock:
            if session_id not in self._entries:
                raise ImportSessionNotFound
            content = self._entries[session_id]
            return content, sha256(content).hexdigest()

    def _write_raw(self, session_id, content, *, expected):
        with self._lock:
            old = self._entries.get(session_id)
            if (expected is None and old is not None) or (expected is not None and (old is None or sha256(old).hexdigest() != expected)):
                raise ImportSessionRevisionConflict
            if expected is None:
                for token, body in tuple(self._entries.items()):
                    if self._clock() >= datetime.fromisoformat(json.loads(body)["expires_at"]):
                        del self._entries[token]
                if len(self._entries) >= self._max_sessions:
                    raise ParsedImportDataError("import_session_capacity_exceeded")
            if sum(len(body) for body in self._entries.values()) - len(old or b"") + len(content) > self._max_total_bytes:
                raise ParsedImportDataError("import_session_capacity_exceeded")
            self._entries[session_id] = content

    def _delete_raw(self, session_id):
        with self._lock:
            self._entries.pop(session_id, None)


class R2ParsedImportStore(_ParsedImportStore):
    def __init__(self, client, *, bucket, prefix="cash-import", **options):
        super().__init__(**options)
        prefix = str(prefix).strip("/")
        if not bucket or not re.fullmatch(r"[A-Za-z0-9_/-]+", prefix) or ".." in prefix:
            raise ValueError("import_session_storage_config_invalid")
        self._client, self._bucket, self._prefix = client, bucket, prefix

    def _key(self, session_id):
        return f"{self._prefix}/parsed/{_hash(session_id)}/session"

    @staticmethod
    def _provider_code(error):
        return str((getattr(error, "response", None) or {}).get("Error", {}).get("Code", ""))

    def _read_raw(self, session_id):
        body = None
        try:
            response = self._client.get_object(Bucket=self._bucket, Key=self._key(session_id))
            body = response["Body"]
            if response.get("ContentLength", 0) > self._max_bytes or not response.get("ETag"):
                raise ImportSessionStorageUnavailable
            chunks, size = [], 0
            while chunk := body.read(min(65536, self._max_bytes + 1 - size)):
                chunks.append(chunk); size += len(chunk)
                if size > self._max_bytes:
                    raise ImportSessionStorageUnavailable
            return b"".join(chunks), response["ETag"]
        except Exception as error:
            if self._provider_code(error) in {"404", "NoSuchKey", "NotFound"}:
                raise ImportSessionNotFound from None
            raise ImportSessionStorageUnavailable from None
        finally:
            if body is not None:
                try:
                    body.close()
                except Exception:
                    raise ImportSessionStorageUnavailable from None

    def _write_raw(self, session_id, content, *, expected):
        try:
            condition = {"IfNoneMatch": "*"} if expected is None else {"IfMatch": expected}
            self._client.put_object(Bucket=self._bucket, Key=self._key(session_id), Body=content, ContentType="application/json", **condition)
        except Exception as error:
            if self._provider_code(error) in {"412", "PreconditionFailed", "ConditionalRequestConflict"}:
                raise ImportSessionRevisionConflict from None
            raise ImportSessionStorageUnavailable from None

    def _delete_raw(self, session_id):
        try:
            self._client.delete_object(Bucket=self._bucket, Key=self._key(session_id))
        except Exception:
            raise ImportSessionStorageUnavailable from None
