from __future__ import annotations

from datetime import datetime, timezone

import pytest

from ft.application.cash_import_staging import (
    ImportSessionExpired,
    ImportSessionForbidden,
    ImportSessionNotFound,
    InMemoryImportStagingStore,
    R2ImportStagingStore,
)


def test_staging_store_keeps_source_and_json_until_session_is_completed():
    now = [datetime(2026, 8, 14, 12, tzinfo=timezone.utc)]
    store = InMemoryImportStagingStore(ttl_seconds=1800, clock=lambda: now[0])

    session = store.create(
        workspace_id="workspace-a",
        user_id="user-a",
        filename="statement.xlsx",
        digest="digest-a",
        content=b"statement-bytes",
    )

    assert len(session.token) >= 43
    assert session.token != store.create(
        workspace_id="workspace-a",
        user_id="user-a",
        filename="statement.xlsx",
        digest="digest-b",
        content=b"other-bytes",
    ).token
    assert store.read_bytes(session.token, "source", workspace_id="workspace-a", user_id="user-a") == b"statement-bytes"

    store.write_json(session.token, "preview", {"digest": "digest-a", "items": []}, workspace_id="workspace-a", user_id="user-a")
    assert store.read_json(session.token, "preview", workspace_id="workspace-a", user_id="user-a") == {"digest": "digest-a", "items": []}

    store.complete(session.token, workspace_id="workspace-a", user_id="user-a")
    with pytest.raises(ImportSessionNotFound):
        store.read_bytes(session.token, "source", workspace_id="workspace-a", user_id="user-a")


def test_staging_store_rejects_cross_workspace_or_cross_user_access():
    store = InMemoryImportStagingStore(ttl_seconds=1800)
    session = store.create(
        workspace_id="workspace-a",
        user_id="user-a",
        filename="statement.csv",
        digest="digest-a",
        content=b"statement-bytes",
    )

    with pytest.raises(ImportSessionForbidden):
        store.read_bytes(session.token, "source", workspace_id="workspace-b", user_id="user-a")
    with pytest.raises(ImportSessionForbidden):
        store.read_bytes(session.token, "source", workspace_id="workspace-a", user_id="user-b")


def test_staging_store_expires_and_deletes_session_objects():
    now = [datetime(2026, 8, 14, 12, tzinfo=timezone.utc)]
    store = InMemoryImportStagingStore(ttl_seconds=60, clock=lambda: now[0])
    session = store.create(
        workspace_id="workspace-a",
        user_id="user-a",
        filename="statement.pdf",
        digest="digest-a",
        content=b"statement-bytes",
    )

    now[0] = datetime(2026, 8, 14, 12, 1, 1, tzinfo=timezone.utc)
    with pytest.raises(ImportSessionExpired):
        store.read_bytes(session.token, "source", workspace_id="workspace-a", user_id="user-a")
    with pytest.raises(ImportSessionNotFound):
        store.read_bytes(session.token, "source", workspace_id="workspace-a", user_id="user-a")


class _FakeS3:
    def __init__(self):
        self.objects: dict[tuple[str, str], bytes] = {}

    def put_object(self, *, Bucket, Key, Body, ContentType=None, **_kwargs):
        self.objects[(Bucket, Key)] = bytes(Body)

    def get_object(self, *, Bucket, Key):
        if (Bucket, Key) not in self.objects:
            raise KeyError(Key)
        return {"Body": _FakeBody(self.objects[(Bucket, Key)])}

    def delete_object(self, *, Bucket, Key):
        self.objects.pop((Bucket, Key), None)


class _FakeBody:
    def __init__(self, value: bytes):
        self.value = value

    def read(self):
        return self.value


def test_r2_store_uses_private_hashed_prefix_and_removes_all_session_objects():
    client = _FakeS3()
    store = R2ImportStagingStore(client, bucket="private-imports", prefix="cash-import", ttl_seconds=1800)
    session = store.create(
        workspace_id="workspace-a",
        user_id="user-a",
        filename="statement.pdf",
        digest="digest-a",
        content=b"statement-bytes",
    )
    assert session.token not in " ".join(key for _bucket, key in client.objects)

    store.write_json(session.token, "scan", {"digest": "digest-a"}, workspace_id="workspace-a", user_id="user-a")
    assert store.read_json(session.token, "scan", workspace_id="workspace-a", user_id="user-a") == {"digest": "digest-a"}
    keys_before = list(client.objects)
    assert len(keys_before) == 3

    store.complete(session.token, workspace_id="workspace-a", user_id="user-a")
    assert client.objects == {}


def test_staging_store_enforces_object_size_and_memory_session_limits():
    store = InMemoryImportStagingStore(
        ttl_seconds=1800,
        max_source_bytes=4,
        max_draft_bytes=8,
        max_sessions=1,
    )
    with pytest.raises(ValueError, match="import_session_source_too_large"):
        store.create(
            workspace_id="workspace-a",
            user_id="user-a",
            filename="statement.csv",
            digest="digest-a",
            content=b"12345",
        )
    session = store.create(
        workspace_id="workspace-a",
        user_id="user-a",
        filename="statement.csv",
        digest="digest-a",
        content=b"1234",
    )
    with pytest.raises(ValueError, match="import_session_draft_too_large"):
        store.write_bytes(session.token, "preview", b"123456789", workspace_id="workspace-a", user_id="user-a")
    with pytest.raises(ValueError, match="import_session_capacity_exceeded"):
        store.create(
            workspace_id="workspace-a",
            user_id="user-a",
            filename="another.csv",
            digest="digest-b",
            content=b"1234",
        )


def test_r2_not_found_errors_are_mapped_without_provider_details():
    class ProviderError(Exception):
        response = {"Error": {"Code": "NoSuchKey"}}

    class NotFoundS3(_FakeS3):
        def get_object(self, *, Bucket, Key):
            raise ProviderError("not found")

    store = R2ImportStagingStore(NotFoundS3(), bucket="private-imports")
    with pytest.raises(ImportSessionNotFound):
        store._get("cash-import/missing/source")
