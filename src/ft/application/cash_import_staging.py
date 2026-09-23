"""Short-lived, non-business storage for browser cash-import sessions."""
from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
import re
from secrets import token_urlsafe
from threading import RLock
from typing import Callable, Protocol
from urllib.parse import urlparse


_ALLOWED_OBJECTS = frozenset({"source", "scan", "preview", "result"})
_MAX_BATCH_FILES = 20
_DEFAULT_SOURCE_MAX_BYTES = 100 * 1024 * 1024
_DEFAULT_DRAFT_MAX_BYTES = 25 * 1024 * 1024


class ImportSessionError(ValueError):
    """Base error for invalid or unavailable temporary import sessions."""


class ImportSessionNotFound(ImportSessionError):
    def __init__(self) -> None:
        super().__init__("import_session_not_found")


class ImportSessionForbidden(ImportSessionError):
    def __init__(self) -> None:
        super().__init__("import_session_forbidden")


class ImportSessionExpired(ImportSessionError):
    def __init__(self) -> None:
        super().__init__("import_session_expired")


class ImportSessionStorageUnavailable(ImportSessionError):
    def __init__(self) -> None:
        super().__init__("import_session_storage_unavailable")


class ImportSessionPasswordRequired(ImportSessionError):
    def __init__(self, token: str) -> None:
        self.token = token
        super().__init__("import_password_required")


@dataclass(frozen=True)
class ImportSessionFile:
    index: int
    filename: str
    digest: str
    size: int
    source: str = ""
    channel: str | None = None
    status: str = "pending"
    error_code: str | None = None


@dataclass(frozen=True)
class ImportSession:
    token: str
    workspace_id: str
    user_id: str
    filename: str
    digest: str
    source: str = ""
    currency: str | None = None
    channel: str | None = None
    files: tuple[ImportSessionFile, ...] = ()
    batch_digest: str | None = None
    created_at: datetime = datetime.min.replace(tzinfo=timezone.utc)
    expires_at: datetime = datetime.min.replace(tzinfo=timezone.utc)


class ImportStagingStore(Protocol):
    def create(
        self,
        *,
        workspace_id: str,
        user_id: str,
        filename: str,
        digest: str,
        content: bytes,
        source: str = "",
        currency: str | None = None,
    ) -> ImportSession: ...

    def create_batch(
        self,
        *,
        workspace_id: str,
        user_id: str,
        files: list[dict],
        batch_digest: str,
        currency: str | None = None,
    ) -> ImportSession: ...

    def get(self, token: str, *, workspace_id: str, user_id: str) -> ImportSession: ...

    def update(self, token: str, *, workspace_id: str, user_id: str, **changes: object) -> ImportSession: ...

    def read_bytes(self, token: str, name: str, *, workspace_id: str, user_id: str) -> bytes: ...

    def write_bytes(self, token: str, name: str, content: bytes, *, workspace_id: str, user_id: str) -> None: ...

    def read_json(self, token: str, name: str, *, workspace_id: str, user_id: str) -> dict | list: ...

    def write_json(self, token: str, name: str, payload: dict | list, *, workspace_id: str, user_id: str) -> None: ...

    def complete(self, token: str, *, workspace_id: str, user_id: str) -> None: ...


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _check_object_name(name: str) -> str:
    source_match = re.fullmatch(r"source-(\d+)", str(name))
    if name not in _ALLOWED_OBJECTS and not (
        source_match is not None and int(source_match.group(1)) < _MAX_BATCH_FILES
    ):
        raise ValueError("import_session_object_invalid")
    return name


def _source_object_name(index: int) -> str:
    if not 0 <= int(index) < _MAX_BATCH_FILES:
        raise ValueError("import_session_file_index_invalid")
    return f"source-{int(index)}"


def _check_object_size(name: str, content: bytes, *, source_limit: int, draft_limit: int) -> bytes:
    value = bytes(content)
    is_source = name == "source" or re.fullmatch(r"source-\d+", str(name)) is not None
    limit = source_limit if is_source else draft_limit
    if len(value) > limit:
        code = "import_session_source_too_large" if is_source else "import_session_draft_too_large"
        raise ValueError(code)
    return value


def _prepare_batch_files(files: list[dict], *, source_limit: int) -> tuple[tuple[ImportSessionFile, ...], dict[str, bytes]]:
    if not isinstance(files, list) or not files or len(files) > _MAX_BATCH_FILES:
        raise ValueError("import_session_file_count_invalid")
    metadata: list[ImportSessionFile] = []
    objects: dict[str, bytes] = {}
    for index, item in enumerate(files):
        if not isinstance(item, dict):
            raise ValueError("import_session_file_invalid")
        content = _check_object_size(
            _source_object_name(index),
            item.get("content") or b"",
            source_limit=source_limit,
            draft_limit=_DEFAULT_DRAFT_MAX_BYTES,
        )
        filename = str(item.get("filename") or "statement").strip() or "statement"
        digest = str(item.get("digest") or hashlib.sha256(content).hexdigest())
        if len(digest) > 128:
            raise ValueError("import_session_digest_invalid")
        metadata.append(ImportSessionFile(
            index=index,
            filename=filename,
            digest=digest,
            size=len(content),
            source=str(item.get("source") or ""),
            channel=(str(item["channel"]) if item.get("channel") not in (None, "") else None),
        ))
        objects[_source_object_name(index)] = content
    return tuple(metadata), objects


@dataclass
class _MemoryEntry:
    session: ImportSession
    objects: dict[str, bytes]


class InMemoryImportStagingStore:
    """Explicit local/test implementation; never a production fallback."""

    def __init__(
        self,
        *,
        ttl_seconds: int = 1800,
        clock: Callable[[], datetime] = _utc_now,
        max_source_bytes: int = _DEFAULT_SOURCE_MAX_BYTES,
        max_draft_bytes: int = _DEFAULT_DRAFT_MAX_BYTES,
        max_sessions: int = 256,
    ):
        if not 1 <= int(ttl_seconds) <= 1800 or int(max_source_bytes) < 1 or int(max_draft_bytes) < 1 or int(max_sessions) < 1:
            raise ValueError("import_session_ttl_invalid")
        self._ttl = int(ttl_seconds)
        self._clock = clock
        self._source_limit = int(max_source_bytes)
        self._draft_limit = int(max_draft_bytes)
        self._max_sessions = int(max_sessions)
        self._entries: dict[str, _MemoryEntry] = {}
        self._lock = RLock()

    def create(
        self,
        *,
        workspace_id: str,
        user_id: str,
        filename: str,
        digest: str,
        content: bytes,
        source: str = "",
        currency: str | None = None,
    ) -> ImportSession:
        content = _check_object_size("source", content, source_limit=self._source_limit, draft_limit=self._draft_limit)
        now = self._clock()
        token = token_urlsafe(32)
        session = ImportSession(
            token=token,
            workspace_id=str(workspace_id),
            user_id=str(user_id),
            filename=str(filename or "statement"),
            digest=str(digest),
            source=str(source or ""),
            currency=currency,
            created_at=now,
            expires_at=now + timedelta(seconds=self._ttl),
        )
        with self._lock:
            expired = [token for token, entry in self._entries.items() if now >= entry.session.expires_at]
            for expired_token in expired:
                del self._entries[expired_token]
            if len(self._entries) >= self._max_sessions:
                raise ValueError("import_session_capacity_exceeded")
            self._entries[token] = _MemoryEntry(session=session, objects={"source": bytes(content)})
        return session

    def create_batch(
        self,
        *,
        workspace_id: str,
        user_id: str,
        files: list[dict],
        batch_digest: str,
        currency: str | None = None,
    ) -> ImportSession:
        metadata, objects = _prepare_batch_files(files, source_limit=self._source_limit)
        now = self._clock()
        token = token_urlsafe(32)
        session = ImportSession(
            token=token,
            workspace_id=str(workspace_id),
            user_id=str(user_id),
            filename=metadata[0].filename,
            digest=str(batch_digest),
            source="",
            currency=currency,
            files=metadata,
            batch_digest=str(batch_digest),
            created_at=now,
            expires_at=now + timedelta(seconds=self._ttl),
        )
        with self._lock:
            expired = [token for token, entry in self._entries.items() if now >= entry.session.expires_at]
            for expired_token in expired:
                del self._entries[expired_token]
            if len(self._entries) >= self._max_sessions:
                raise ValueError("import_session_capacity_exceeded")
            self._entries[token] = _MemoryEntry(session=session, objects=objects)
        return session

    def _entry(self, token: str, *, workspace_id: str, user_id: str) -> _MemoryEntry:
        with self._lock:
            entry = self._entries.get(str(token))
            if entry is None:
                raise ImportSessionNotFound
            if self._clock() >= entry.session.expires_at:
                del self._entries[str(token)]
                raise ImportSessionExpired
            if entry.session.workspace_id != str(workspace_id) or entry.session.user_id != str(user_id):
                raise ImportSessionForbidden
            return entry

    def get(self, token: str, *, workspace_id: str, user_id: str) -> ImportSession:
        return self._entry(token, workspace_id=workspace_id, user_id=user_id).session

    def update(self, token: str, *, workspace_id: str, user_id: str, **changes: object) -> ImportSession:
        with self._lock:
            entry = self._entry(token, workspace_id=workspace_id, user_id=user_id)
            allowed = {"source", "currency", "channel", "files", "batch_digest"}
            if set(changes) - allowed:
                raise ValueError("import_session_metadata_invalid")
            entry.session = replace(entry.session, **changes)
            return entry.session

    def read_bytes(self, token: str, name: str, *, workspace_id: str, user_id: str) -> bytes:
        _check_object_name(name)
        with self._lock:
            entry = self._entry(token, workspace_id=workspace_id, user_id=user_id)
            if name not in entry.objects:
                raise ImportSessionNotFound
            return bytes(entry.objects[name])

    def write_bytes(self, token: str, name: str, content: bytes, *, workspace_id: str, user_id: str) -> None:
        name = _check_object_name(name)
        content = _check_object_size(name, content, source_limit=self._source_limit, draft_limit=self._draft_limit)
        with self._lock:
            entry = self._entry(token, workspace_id=workspace_id, user_id=user_id)
            entry.objects[name] = content

    def read_json(self, token: str, name: str, *, workspace_id: str, user_id: str) -> dict | list:
        try:
            payload = json.loads(self.read_bytes(token, name, workspace_id=workspace_id, user_id=user_id))
        except (TypeError, ValueError, json.JSONDecodeError) as exc:
            raise ImportSessionNotFound from exc
        if not isinstance(payload, (dict, list)):
            raise ImportSessionNotFound
        return payload

    def write_json(self, token: str, name: str, payload: dict | list, *, workspace_id: str, user_id: str) -> None:
        if not isinstance(payload, (dict, list)):
            raise ValueError("import_session_json_invalid")
        self.write_bytes(
            token,
            name,
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
            workspace_id=workspace_id,
            user_id=user_id,
        )

    def complete(self, token: str, *, workspace_id: str, user_id: str) -> None:
        with self._lock:
            self._entry(token, workspace_id=workspace_id, user_id=user_id)
            del self._entries[str(token)]


class R2ImportStagingStore:
    """S3-compatible R2 implementation with no public object URLs."""

    def __init__(
        self,
        client,
        *,
        bucket: str,
        prefix: str = "cash-import",
        ttl_seconds: int = 1800,
        clock: Callable[[], datetime] = _utc_now,
        max_source_bytes: int = _DEFAULT_SOURCE_MAX_BYTES,
        max_draft_bytes: int = _DEFAULT_DRAFT_MAX_BYTES,
    ):
        normalized_prefix = str(prefix).strip("/")
        if (
            not bucket or not normalized_prefix or ".." in normalized_prefix
            or not 1 <= int(ttl_seconds) <= 1800
            or int(max_source_bytes) < 1 or int(max_draft_bytes) < 1
        ):
            raise ValueError("import_session_storage_config_invalid")
        self._client = client
        self._bucket = bucket
        self._prefix = normalized_prefix
        self._ttl = int(ttl_seconds)
        self._clock = clock
        self._source_limit = int(max_source_bytes)
        self._draft_limit = int(max_draft_bytes)

    @classmethod
    def from_environment(cls, *, environ=None) -> "R2ImportStagingStore":
        environment = dict(os.environ if environ is None else environ)
        endpoint = environment.get("FT_R2_ENDPOINT", "").strip()
        bucket = environment.get("FT_R2_BUCKET", "").strip()
        access_key = environment.get("FT_R2_ACCESS_KEY_ID", "").strip()
        secret_key = environment.get("FT_R2_SECRET_ACCESS_KEY", "").strip()
        if not endpoint or not bucket or not access_key or not secret_key:
            raise ValueError("import_session_storage_config_missing")
        parsed = urlparse(endpoint)
        if parsed.scheme != "https" or not parsed.netloc or parsed.path not in {"", "/"} or parsed.query or parsed.fragment:
            raise ValueError("import_session_storage_config_invalid")
        try:
            import boto3

            client = boto3.client(
                "s3",
                endpoint_url=endpoint,
                region_name=environment.get("FT_R2_REGION", "auto"),
                aws_access_key_id=access_key,
                aws_secret_access_key=secret_key,
            )
        except Exception as exc:  # noqa: BLE001 - configuration must fail closed.
            raise ValueError("import_session_storage_config_invalid") from exc
        try:
            ttl = int(environment.get("FT_IMPORT_STAGING_TTL_SECONDS", "1800"))
        except (TypeError, ValueError) as exc:
            raise ValueError("import_session_storage_config_invalid") from exc
        prefix = environment.get("FT_R2_PREFIX", "cash-import")
        return cls(client, bucket=bucket, prefix=prefix, ttl_seconds=ttl)

    @staticmethod
    def _token_digest(token: str) -> str:
        return hashlib.sha256(str(token).encode("utf-8")).hexdigest()

    def _prefix_for(self, token: str) -> str:
        return f"{self._prefix}/{self._token_digest(token)}"

    def _key(self, token: str, name: str) -> str:
        return f"{self._prefix_for(token)}/{_check_object_name(name)}"

    def _manifest_key(self, token: str) -> str:
        return f"{self._prefix_for(token)}/manifest"

    def _put(self, key: str, content: bytes, content_type: str = "application/octet-stream") -> None:
        try:
            self._client.put_object(Bucket=self._bucket, Key=key, Body=content, ContentType=content_type)
        except Exception as exc:  # noqa: BLE001 - adapter hides provider details.
            raise ImportSessionStorageUnavailable from exc

    def _get(self, key: str) -> bytes:
        try:
            response = self._client.get_object(Bucket=self._bucket, Key=key)
            return bytes(response["Body"].read())
        except Exception as exc:  # noqa: BLE001 - provider errors must not leak.
            if isinstance(exc, KeyError):
                raise ImportSessionNotFound from None
            provider_response = getattr(exc, "response", None) or {}
            provider_code = provider_response.get("Error", {}).get("Code")
            if str(provider_code) in {"404", "NoSuchKey", "NotFound"}:
                raise ImportSessionNotFound from None
            raise ImportSessionStorageUnavailable from exc

    def _delete(self, key: str) -> None:
        try:
            self._client.delete_object(Bucket=self._bucket, Key=key)
        except Exception as exc:  # noqa: BLE001 - cleanup is best effort but explicit.
            raise ImportSessionStorageUnavailable from exc

    def create(
        self,
        *,
        workspace_id: str,
        user_id: str,
        filename: str,
        digest: str,
        content: bytes,
        source: str = "",
        currency: str | None = None,
    ) -> ImportSession:
        content = _check_object_size("source", content, source_limit=self._source_limit, draft_limit=self._draft_limit)
        now = self._clock()
        token = token_urlsafe(32)
        session = ImportSession(
            token=token,
            workspace_id=str(workspace_id),
            user_id=str(user_id),
            filename=str(filename or "statement"),
            digest=str(digest),
            source=str(source or ""),
            currency=currency,
            created_at=now,
            expires_at=now + timedelta(seconds=self._ttl),
        )
        self._put(self._key(token, "source"), bytes(content))
        try:
            self._put(self._manifest_key(token), self._manifest(session), "application/json")
        except Exception:
            try:
                self._delete(self._key(token, "source"))
            finally:
                raise
        return session

    def create_batch(
        self,
        *,
        workspace_id: str,
        user_id: str,
        files: list[dict],
        batch_digest: str,
        currency: str | None = None,
    ) -> ImportSession:
        metadata, objects = _prepare_batch_files(files, source_limit=self._source_limit)
        now = self._clock()
        token = token_urlsafe(32)
        session = ImportSession(
            token=token,
            workspace_id=str(workspace_id),
            user_id=str(user_id),
            filename=metadata[0].filename,
            digest=str(batch_digest),
            source="",
            currency=currency,
            files=metadata,
            batch_digest=str(batch_digest),
            created_at=now,
            expires_at=now + timedelta(seconds=self._ttl),
        )
        written: list[str] = []
        try:
            for name, content in objects.items():
                self._put(self._key(token, name), content)
                written.append(name)
            self._put(self._manifest_key(token), self._manifest(session), "application/json")
        except Exception:
            for name in written:
                try:
                    self._delete(self._key(token, name))
                except Exception:
                    pass
            raise
        return session

    @staticmethod
    def _manifest(session: ImportSession) -> bytes:
        payload = {
            "token_digest": hashlib.sha256(session.token.encode("utf-8")).hexdigest(),
            "workspace_id": session.workspace_id,
            "user_id": session.user_id,
            "filename": session.filename,
            "digest": session.digest,
            "source": session.source,
            "currency": session.currency,
            "channel": session.channel,
            "batch_digest": session.batch_digest,
            "files": [
                {
                    "index": item.index,
                    "filename": item.filename,
                    "digest": item.digest,
                    "size": item.size,
                    "source": item.source,
                    "channel": item.channel,
                    "status": item.status,
                    "error_code": item.error_code,
                }
                for item in session.files
            ],
            "created_at": session.created_at.isoformat(),
            "expires_at": session.expires_at.isoformat(),
        }
        return json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    def get(self, token: str, *, workspace_id: str, user_id: str) -> ImportSession:
        payload = self._read_manifest(token)
        try:
            files = tuple(
                ImportSessionFile(
                    index=int(item["index"]),
                    filename=str(item["filename"]),
                    digest=str(item["digest"]),
                    size=int(item.get("size") or 0),
                    source=str(item.get("source") or ""),
                    channel=(str(item["channel"]) if item.get("channel") not in (None, "") else None),
                    status=str(item.get("status") or "pending"),
                    error_code=(str(item["error_code"]) if item.get("error_code") not in (None, "") else None),
                )
                for item in (payload.get("files") or ())
                if isinstance(item, dict)
            )
            session = ImportSession(
                token=str(token),
                workspace_id=str(payload["workspace_id"]),
                user_id=str(payload["user_id"]),
                filename=str(payload["filename"]),
                digest=str(payload["digest"]),
                source=str(payload.get("source") or ""),
                currency=payload.get("currency"),
                channel=payload.get("channel"),
                files=files,
                batch_digest=(str(payload["batch_digest"]) if payload.get("batch_digest") not in (None, "") else None),
                created_at=datetime.fromisoformat(payload["created_at"]),
                expires_at=datetime.fromisoformat(payload["expires_at"]),
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise ImportSessionNotFound from exc
        if self._clock() >= session.expires_at:
            self._delete_all(token)
            raise ImportSessionExpired
        if session.workspace_id != str(workspace_id) or session.user_id != str(user_id):
            raise ImportSessionForbidden
        return session

    def _read_manifest(self, token: str) -> dict:
        try:
            payload = json.loads(self._get(self._manifest_key(token)))
        except json.JSONDecodeError as exc:
            raise ImportSessionNotFound from exc
        if not isinstance(payload, dict) or payload.get("token_digest") != self._token_digest(token):
            raise ImportSessionNotFound
        return payload

    def update(self, token: str, *, workspace_id: str, user_id: str, **changes: object) -> ImportSession:
        session = self.get(token, workspace_id=workspace_id, user_id=user_id)
        allowed = {"source", "currency", "channel", "files", "batch_digest"}
        if set(changes) - allowed:
            raise ValueError("import_session_metadata_invalid")
        session = replace(session, **changes)
        self._put(self._manifest_key(token), self._manifest(session), "application/json")
        return session

    def read_bytes(self, token: str, name: str, *, workspace_id: str, user_id: str) -> bytes:
        self.get(token, workspace_id=workspace_id, user_id=user_id)
        return self._get(self._key(token, name))

    def write_bytes(self, token: str, name: str, content: bytes, *, workspace_id: str, user_id: str) -> None:
        name = _check_object_name(name)
        content = _check_object_size(name, content, source_limit=self._source_limit, draft_limit=self._draft_limit)
        self.get(token, workspace_id=workspace_id, user_id=user_id)
        self._put(self._key(token, name), content)

    def read_json(self, token: str, name: str, *, workspace_id: str, user_id: str) -> dict | list:
        try:
            payload = json.loads(self.read_bytes(token, name, workspace_id=workspace_id, user_id=user_id))
        except json.JSONDecodeError as exc:
            raise ImportSessionNotFound from exc
        if not isinstance(payload, (dict, list)):
            raise ImportSessionNotFound
        return payload

    def write_json(self, token: str, name: str, payload: dict | list, *, workspace_id: str, user_id: str) -> None:
        if not isinstance(payload, (dict, list)):
            raise ValueError("import_session_json_invalid")
        self.write_bytes(
            token,
            name,
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
            workspace_id=workspace_id,
            user_id=user_id,
        )

    def _delete_all(self, token: str) -> None:
        names = set(_ALLOWED_OBJECTS)
        try:
            payload = self._read_manifest(token)
            for item in payload.get("files") or ():
                if isinstance(item, dict):
                    names.add(_source_object_name(int(item["index"])))
        except (ImportSessionNotFound, KeyError, TypeError, ValueError):
            pass
        for name in names:
            self._delete(self._key(token, name))
        self._delete(self._manifest_key(token))

    def complete(self, token: str, *, workspace_id: str, user_id: str) -> None:
        self.get(token, workspace_id=workspace_id, user_id=user_id)
        self._delete_all(token)
