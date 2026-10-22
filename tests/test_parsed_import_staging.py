from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from hashlib import sha256
from io import BytesIO
import json
from threading import Lock

import pytest

from ft.adapters.cash_statement_rows import ParsedCashDocument, ParsedCashRow
from ft.domain.cash_source_matching import SourceCoverage


class ProviderError(Exception):
    def __init__(self, code):
        self.response = {"Error": {"Code": code}}
        super().__init__("provider details must stay private")


class ConditionalS3:
    def __init__(self):
        self.objects = {}
        self.lock = Lock()
        self.bodies = []
        self.writes = []
        self.fail_after_put = False
        self.metadata = {}
        self.modified = {}
        self.clock = lambda: datetime.now(timezone.utc)
        self.list_calls = []
        self.head_calls = []

    def put_object(self, *, Bucket, Key, Body, ContentType, IfMatch=None, IfNoneMatch=None, Metadata=None):
        with self.lock:
            old = self.objects.get((Bucket, Key))
            if IfNoneMatch == "*" and old is not None or IfMatch is not None and (old is None or self.etag(old) != IfMatch):
                raise ProviderError("PreconditionFailed")
            self.objects[Bucket, Key] = bytes(Body)
            self.metadata[Bucket, Key] = dict(Metadata or {})
            self.modified[Bucket, Key] = self.clock()
            self.writes.append((Key, IfMatch, IfNoneMatch, ContentType))
            if self.fail_after_put:
                self.fail_after_put = False
                raise RuntimeError("connection lost after storage committed")
            return {"ETag": self.etag(Body)}

    def get_object(self, *, Bucket, Key):
        with self.lock:
            if (Bucket, Key) not in self.objects:
                raise ProviderError("NoSuchKey")
            content = self.objects[Bucket, Key]
            body = BytesIO(content)
            self.bodies.append(body)
            return {"Body": body, "ETag": self.etag(content), "ContentLength": len(content)}

    def delete_object(self, *, Bucket, Key):
        with self.lock:
            self.objects.pop((Bucket, Key), None)

    def list_objects_v2(self, *, Bucket, Prefix, MaxKeys, ContinuationToken=None):
        self.list_calls.append((Prefix, MaxKeys, ContinuationToken))
        keys = sorted(key for bucket, key in self.objects if bucket == Bucket and key.startswith(Prefix)
            and (ContinuationToken is None or key > ContinuationToken))
        return {"Contents": [{"Key": key} for key in keys[:MaxKeys]], "IsTruncated": len(keys) > MaxKeys,
            "NextContinuationToken": keys[MaxKeys - 1] if len(keys) > MaxKeys else None}

    def head_object(self, *, Bucket, Key):
        self.head_calls.append(Key)
        if (Bucket, Key) not in self.objects:
            raise ProviderError("NoSuchKey")
        return {"Metadata": self.metadata.get((Bucket, Key), {}), "LastModified": self.modified[Bucket, Key]}

    @staticmethod
    def etag(content):
        return '"' + sha256(content).hexdigest() + '"'


@pytest.fixture(params=["memory", "r2"])
def staging(request):
    from ft.application.parsed_import_staging import InMemoryParsedImportStore, R2ParsedImportStore
    now = [datetime(2026, 10, 21, 12, tzinfo=timezone.utc)]
    client = ConditionalS3()
    client.clock = lambda: now[0]
    options = {"ttl_seconds": 60, "clock": lambda: now[0]}
    store = InMemoryParsedImportStore(**options) if request.param == "memory" else R2ParsedImportStore(client, bucket="private-imports", **options)
    return store, now, client


def _file(*, ref="f1", client_ref="client-f1"):
    from ft.application.parsed_import_staging import ParsedImportFile
    return ParsedImportFile(ref, client_ref, "synthetic.csv", "a" * 64, 100,
        "alipay", "parsed", ParsedCashDocument("alipay", "csv:0", "native-v1",
            (ParsedCashRow(8, {"amount": "-20.00", "note": "原始说明", "source_payload": {"金额": "20.00"}}, "fixture-bank", "001"),),
            "fixture-owner", "fixture-owner", SourceCoverage(), 1, 1))


def _create(store, **changes):
    return store.create(workspace_id="workspace-a", user_id="user-a", files=changes.get("files", (_file(),)))


def _get(store, session, **changes):
    return store.get(session.session_id, workspace_id=changes.get("workspace_id", "workspace-a"), user_id=changes.get("user_id", "user-a"))


def test_document_page_proof_and_locator_roundtrip_without_original_file(staging):
    store, _, _ = staging
    file = _file()
    coverage = SourceCoverage(document_key="f" * 64, page_total=3, page_fingerprints={"2": "e" * 64})
    row = replace(file.document.rows[0], document_position="p2:t0:r1")
    file = replace(file, document=replace(file.document, rows=(row,), coverage=coverage))
    session = _create(store, files=(file,))
    restored = _get(store, session).files[0].document
    assert restored.coverage == coverage and restored.rows[0].document_position == "p2:t0:r1"


def test_retry_context_inherits_original_lifetime_and_clears_private_data_on_cancel(staging):
    store, now, _ = staging
    original = _create(store)
    now[0] += timedelta(seconds=30)
    child = store.create(workspace_id=original.workspace_id, user_id=original.user_id,
        files=original.files, workflow_kind="retry", context_data={"synthetic_proof": "a" * 64},
        created_at=original.created_at, expires_at=original.expires_at)
    assert child.created_at == original.created_at and child.expires_at == original.expires_at
    assert _get(store, child).workflow_kind == "retry"
    child.context_data["synthetic_proof"] = "caller mutation"
    assert _get(store, child).context_data == {"synthetic_proof": "a" * 64}
    changed = _replace(store, child, preview_input={"synthetic": True})
    assert changed.expires_at == original.expires_at and changed.workflow_kind == "retry"
    closed = store.cancel(child.session_id, workspace_id=child.workspace_id,
        user_id=child.user_id, expected_revision=changed.revision)
    assert closed.context_data == {} and closed.files == () and closed.cancelled
    now[0] = original.expires_at
    from ft.application.cash_import_staging import ImportSessionExpired
    with pytest.raises(ImportSessionExpired):
        _get(store, closed)


def test_mutation_context_has_no_files_and_keeps_owner_cas_and_original_ttl(staging):
    from ft.application.cash_import_staging import ImportSessionForbidden
    from ft.application.parsed_import_staging import ImportSessionRevisionConflict
    store, now, _ = staging
    current = store.create(workspace_id="workspace-a", user_id="user-a", files=(),
        workflow_kind="mutation", context_data={"transaction_id": "1001", "version": 1})
    assert current.files == () and _get(store, current).workflow_kind == "mutation"
    with pytest.raises(ImportSessionForbidden):
        _get(store, current, user_id="user-b")
    now[0] += timedelta(seconds=30)
    changed = _replace(store, current, preview_input={"patch": {"note": "合成维护正文"}},
        plan={"plan_id": "synthetic-plan"})
    assert changed.expires_at == current.expires_at and changed.files == ()
    with pytest.raises(ImportSessionRevisionConflict):
        _replace(store, current, plan={})
    closed = store.cancel(current.session_id, workspace_id=current.workspace_id,
        user_id=current.user_id, expected_revision=changed.revision)
    assert closed.files == () and closed.context_data == {} and closed.preview_input == {} and closed.plan is None


def test_mutation_context_cannot_gain_files_or_allow_empty_import(staging):
    from ft.application.parsed_import_staging import ParsedImportDataError
    store, _, _ = staging
    with pytest.raises(ParsedImportDataError):
        store.create(workspace_id="workspace-a", user_id="user-a", files=())
    with pytest.raises(ParsedImportDataError):
        store.create(workspace_id="workspace-a", user_id="user-a", files=(_file(),), workflow_kind="mutation")
    current = store.create(workspace_id="workspace-a", user_id="user-a", files=(), workflow_kind="mutation")
    with pytest.raises(ParsedImportDataError):
        _replace(store, current, files=(_file(),))
    assert _get(store, current) == current


@pytest.mark.parametrize("kind,created_offset,expires_offset", [
    ("arbitrary", 0, 60), ("retry", 0, 1900), ("retry", 10, 60), ("retry", -60, -1),
])
def test_invalid_context_kind_or_lifetime_cannot_be_written(staging, kind, created_offset, expires_offset):
    store, now, _ = staging
    from ft.application.parsed_import_staging import ParsedImportDataError
    with pytest.raises(ParsedImportDataError):
        store.create(workspace_id="workspace-a", user_id="user-a", files=(_file(),),
            workflow_kind=kind, created_at=now[0] + timedelta(seconds=created_offset),
            expires_at=now[0] + timedelta(seconds=expires_offset))


def test_legacy_context_without_kind_is_plain_import(staging):
    store, _, client = staging
    session = _create(store)
    entries = client.objects if client.objects else store._entries
    key = next(iter(entries))
    value = json.loads(entries[key])
    value.pop("workflow_kind", None)
    value.pop("context_data", None)
    value["files"][0].pop("is_subset", None)
    entries[key] = json.dumps(value).encode()
    restored = _get(store, session)
    assert restored.workflow_kind == "import" and restored.context_data == {}
    assert not restored.files[0].is_subset


def test_parsed_content_budget_is_independent_of_complete_plan_budget(staging):
    from ft.application.parsed_import_staging import InMemoryParsedImportStore, ParsedImportDataError, R2ParsedImportStore
    original, now, client = staging
    options = dict(max_bytes=12000, max_parsed_bytes=3000, clock=lambda: now[0])
    store = InMemoryParsedImportStore(**options) if isinstance(original, InMemoryParsedImportStore) else R2ParsedImportStore(
        client, bucket="separate-budgets", **options)
    session = _create(store)
    plan = {"synthetic": "x" * 5000}
    updated = store.replace(session.session_id, workspace_id=session.workspace_id, user_id=session.user_id,
        expected_revision=1, files=session.files, preview_input={}, plan=plan)
    assert updated.plan == plan
    large = replace(session.files[0], document=replace(session.files[0].document,
        rows=(replace(session.files[0].document.rows[0], fields={"note": "x" * 4000}),)))
    with pytest.raises(ParsedImportDataError, match="import_session_draft_too_large"):
        store.replace(session.session_id, workspace_id=session.workspace_id, user_id=session.user_id,
            expected_revision=2, files=(large,), preview_input={}, plan=None)
    assert _get(store, session).revision == 2 and _get(store, session).plan == plan
    with pytest.raises(ParsedImportDataError, match="import_session_draft_too_large"):
        store.replace(session.session_id, workspace_id=session.workspace_id, user_id=session.user_id,
            expected_revision=2, files=session.files, preview_input={}, plan={"synthetic": "x" * 13000})
    assert _get(store, session).revision == 2 and _get(store, session).plan == plan
    one = replace(session.files[0], document=replace(session.files[0].document,
        rows=(replace(session.files[0].document.rows[0], fields={"note": "x" * 1700}),)))
    two = replace(one, file_ref="file_two", client_file_ref="client_two")
    store.validate_file(one)
    store.validate_file(two)
    with pytest.raises(ParsedImportDataError, match="import_session_draft_too_large"):
        store.replace(session.session_id, workspace_id=session.workspace_id, user_id=session.user_id,
            expected_revision=2, files=(one, two), preview_input={}, plan=None)
    assert _get(store, session).revision == 2 and _get(store, session).plan == plan


def test_escaped_json_overflow_keeps_size_reason_and_previous_plan(staging):
    from ft.application.parsed_import_staging import InMemoryParsedImportStore, ParsedImportDataError, R2ParsedImportStore
    original, now, client = staging
    options = dict(max_bytes=12000, max_parsed_bytes=3000, clock=lambda: now[0])
    store = InMemoryParsedImportStore(**options) if isinstance(original, InMemoryParsedImportStore) else R2ParsedImportStore(
        client, bucket="escaped-budget", **options)
    session = _create(store)
    with pytest.raises(ParsedImportDataError, match="import_session_draft_too_large"):
        store.replace(session.session_id, workspace_id=session.workspace_id, user_id=session.user_id,
            expected_revision=1, files=session.files, preview_input={}, plan={"synthetic": '"' * 7000})
    assert _get(store, session).revision == 1 and _get(store, session).plan is None


def test_datetime_in_source_fields_keeps_safe_invalid_json_failure(staging):
    from ft.application.parsed_import_staging import ParsedImportDataError
    store, now, _client = staging
    file = _file()
    file = replace(file, document=replace(file.document, rows=(replace(file.document.rows[0],
        fields={"occurred_at": now[0]}),)))
    with pytest.raises(ParsedImportDataError, match="import_session_json_invalid"):
        _create(store, files=(file,))


def _replace(store, session, **changes):
    return store.replace(session.session_id, workspace_id="workspace-a", user_id="user-a", expected_revision=session.revision,
        files=changes.get("files", session.files), preview_input=changes.get("preview_input", session.preview_input), plan=changes.get("plan", session.plan))


def test_parsed_session_roundtrip_is_detached_and_never_contains_upload_bytes(staging):
    store, _now, client = staging
    session = _create(store)
    assert session.revision == 1
    assert len(session.session_id) == 43
    assert _get(store, session) == session
    session.files[0].document.rows[0].fields["note"] = "caller mutation"
    assert _get(store, session).files[0].document.rows[0].fields["note"] == "原始说明"
    for body in client.objects.values():
        value = json.loads(body)
        assert value["files"][0]["document"]["rows"][0]["fields"]["amount"] == "-20.00"
        assert "content" not in value["files"][0]
        assert "password" not in value["files"][0]
        assert session.session_id.encode() not in body
    assert all(body.closed for body in client.bodies)


def test_row_level_source_scope_survives_both_parsed_stores(staging):
    store, _now, _client = staging
    file = _file()
    row = replace(file.document.rows[0], source_account_identifier="6222000000001234")
    file = replace(file, source_type="icbc_credit", document=replace(file.document, source_type="icbc_credit", source_account_identifier=None, rows=(row,)))
    session = _create(store, files=(file,))
    assert _get(store, session).files[0].document.rows[0].source_account_identifier == "6222000000001234"


def test_source_explanation_roundtrip_is_detached(staging):
    store, _now, _client = staging
    file = _file()
    row = replace(file.document.rows[0], source_explanation={"txn_id": "001", "_is_refund": False})
    session = _create(store, files=(replace(file, document=replace(file.document, rows=(row,))),))
    row.source_explanation["txn_id"] = "mutated"
    assert _get(store, session).files[0].document.rows[0].source_explanation == {"txn_id": "001", "_is_refund": False}


def test_old_parsed_objects_without_source_explanation_remain_readable(staging):
    store, _now, client = staging
    session = _create(store)
    entries = client.objects if client.objects else store._entries
    key = next(iter(entries))
    value = json.loads(entries[key])
    value["files"][0]["document"]["rows"][0].pop("source_explanation", None)
    entries[key] = json.dumps(value).encode()
    assert _get(store, session).files[0].document.rows[0].source_explanation == {}


@pytest.mark.parametrize("explanation", [{"password": "secret"}, {"note": "generated"}, {"txn_id": []}, {"_is_refund": 1}, None])
def test_invalid_source_explanation_cannot_be_published(staging, explanation):
    from ft.application.parsed_import_staging import ParsedImportDataError
    store, _now, _client = staging
    session = _create(store)
    file = session.files[0]
    changed = replace(file, document=replace(file.document, rows=(replace(file.document.rows[0], source_explanation=explanation),)))
    with pytest.raises(ParsedImportDataError):
        _replace(store, session, files=(changed,))
    assert _get(store, session) == session


@pytest.mark.parametrize("owner", ["", "x" * 257, 123, []])
def test_invalid_row_level_scope_is_rejected_without_corrupting_session(staging, owner):
    from ft.application.parsed_import_staging import ParsedImportDataError
    store, _now, _client = staging
    session = _create(store)
    file = session.files[0]
    changed = replace(file, document=replace(file.document, rows=(replace(file.document.rows[0], source_account_identifier=owner),)))
    with pytest.raises(ParsedImportDataError):
        _replace(store, session, files=(changed,))
    assert _get(store, session) == session


@pytest.mark.parametrize("changes", [{"workspace_id": "workspace-b"}, {"user_id": "user-b"}])
def test_parsed_session_is_bound_to_workspace_and_user(staging, changes):
    from ft.application.cash_import_staging import ImportSessionForbidden
    store, _now, _client = staging
    session = _create(store)
    with pytest.raises(ImportSessionForbidden):
        _get(store, session, **changes)


def test_replacement_atomically_changes_files_input_and_plan_and_rejects_stale_revision(staging):
    from ft.application.parsed_import_staging import ImportSessionRevisionConflict
    store, _now, client = staging
    first = _create(store)
    second = _replace(store, first, files=(_file(ref="replacement"),), preview_input={"rows": []}, plan={"plan_id": "plan-1", "items": []})
    assert second.revision == 2
    assert second.files[0].file_ref == "replacement"
    assert second.plan["plan_id"] == "plan-1"
    with pytest.raises(ImportSessionRevisionConflict):
        _replace(store, first)
    assert _get(store, first) == second
    if client.writes:
        assert client.writes[0][2] == "*"
        assert client.writes[-1][1] is not None


def test_only_one_concurrent_replacement_can_publish(staging):
    from ft.application.parsed_import_staging import ImportSessionRevisionConflict
    store, _now, _client = staging
    first = _create(store)
    def publish(index):
        try:
            return _replace(store, first, plan={"plan_id": f"p-{index}"}).revision
        except ImportSessionRevisionConflict:
            return "conflict"
    with ThreadPoolExecutor(max_workers=8) as executor:
        outcomes = list(executor.map(publish, range(8)))
    assert outcomes.count(2) == 1
    assert outcomes.count("conflict") == 7
    assert _get(store, first).revision == 2


def test_cancel_clears_financial_body_and_cannot_be_reopened_by_stale_publish(staging):
    from ft.application.parsed_import_staging import ImportSessionRevisionConflict, ImportSessionClosed
    store, _now, _client = staging
    first = _create(store)
    cancelled = store.cancel(first.session_id, workspace_id="workspace-a", user_id="user-a", expected_revision=1)
    assert cancelled.cancelled and cancelled.revision == 2
    assert cancelled.files == () and cancelled.plan is None and cancelled.preview_input == {}
    with pytest.raises(ImportSessionRevisionConflict):
        _replace(store, first)
    with pytest.raises(ImportSessionClosed):
        _replace(store, cancelled, files=(_file(),))
    assert _get(store, first).cancelled


def test_revision_changes_never_extend_ttl_and_expiry_deletes_body(staging):
    from ft.application.cash_import_staging import ImportSessionExpired, ImportSessionNotFound
    store, now, client = staging
    first = _create(store)
    now[0] += timedelta(seconds=59)
    second = _replace(store, first)
    assert second.expires_at == first.expires_at
    now[0] += timedelta(seconds=1)
    with pytest.raises(ImportSessionExpired):
        _get(store, first)
    with pytest.raises(ImportSessionNotFound):
        _get(store, first)
    assert not client.objects


def test_foreign_user_cannot_delete_expired_body(staging):
    from ft.application.cash_import_staging import ImportSessionForbidden, ImportSessionExpired
    store, now, _client = staging
    first = _create(store)
    now[0] += timedelta(seconds=60)
    with pytest.raises(ImportSessionForbidden):
        _get(store, first, user_id="user-b")
    with pytest.raises(ImportSessionExpired):
        _get(store, first)


def test_payload_overflow_leaves_previous_state_untouched(staging):
    from ft.application.parsed_import_staging import ParsedImportDataError
    store, _now, _client = staging
    first = _create(store)
    store._max_bytes = 2500
    with pytest.raises(ParsedImportDataError, match="import_session_draft_too_large"):
        _replace(store, first, plan={"large": "x" * 3000})
    assert _get(store, first) == first


@pytest.mark.parametrize("bad", [b"original upload bytes", {"amount": float("nan")}, {"nested": b"password"}])
def test_only_json_business_values_can_be_staged(staging, bad):
    from ft.application.parsed_import_staging import ParsedImportDataError
    store, _now, _client = staging
    first = _create(store)
    with pytest.raises(ParsedImportDataError):
        _replace(store, first, plan={"payload": bad})
    assert _get(store, first) == first


@pytest.mark.parametrize("files", [(), tuple(_file(ref=f"f-{index}", client_ref=f"c-{index}") for index in range(21)), (_file(), _file())])
def test_file_limits_and_unique_references_are_checked_before_write(staging, files):
    from ft.application.parsed_import_staging import ParsedImportDataError
    store, _now, client = staging
    with pytest.raises(ParsedImportDataError):
        _create(store, files=files)
    assert not client.objects


def test_r2_uncertain_write_is_never_retried_as_unconditional_put():
    from ft.application.parsed_import_staging import R2ParsedImportStore, ImportSessionRevisionConflict
    from ft.application.cash_import_staging import ImportSessionStorageUnavailable
    client = ConditionalS3()
    store = R2ParsedImportStore(client, bucket="private-imports")
    first = _create(store)
    client.fail_after_put = True
    with pytest.raises(ImportSessionStorageUnavailable) as error:
        _replace(store, first, plan={"plan_id": "current"})
    assert "provider" not in str(error.value)
    assert _get(store, first).revision == 2
    with pytest.raises(ImportSessionRevisionConflict):
        _replace(store, first)
    assert len(client.writes) == 2


def test_r2_corrupt_or_oversized_response_never_returns_partial_state():
    from ft.application.parsed_import_staging import R2ParsedImportStore
    from ft.application.cash_import_staging import ImportSessionStorageUnavailable
    client = ConditionalS3()
    store = R2ParsedImportStore(client, bucket="private-imports")
    first = _create(store)
    key = next(iter(client.objects))
    for body in [b'{"corrupt":true}', b"x" * 4000]:
        client.objects[key] = body
        store._max_bytes = 2500
        with pytest.raises(ImportSessionStorageUnavailable):
            _get(store, first)
        assert client.bodies[-1].closed


def test_memory_session_capacity_is_reclaimed_after_expiry():
    from ft.application.parsed_import_staging import InMemoryParsedImportStore, ParsedImportDataError
    now = [datetime(2026, 10, 21, 12, tzinfo=timezone.utc)]
    store = InMemoryParsedImportStore(clock=lambda: now[0], ttl_seconds=1, max_sessions=1)
    _create(store)
    with pytest.raises(ParsedImportDataError, match="import_session_capacity_exceeded"):
        _create(store)
    now[0] += timedelta(seconds=1)
    assert _create(store).revision == 1


def test_nested_invalid_json_is_rejected_without_leaking_a_recursion_error(staging):
    from ft.application.parsed_import_staging import ParsedImportDataError
    store, _now, _client = staging
    session = _create(store)
    cycle = {}
    cycle["cycle"] = cycle
    with pytest.raises(ParsedImportDataError, match="import_session_json_invalid"):
        _replace(store, session, plan=cycle)
    assert _get(store, session) == session


def test_publish_that_crosses_expiry_never_returns_a_successful_expired_session(staging, monkeypatch):
    from ft.application.cash_import_staging import ImportSessionExpired, ImportSessionNotFound
    store, now, _client = staging
    first = _create(store)
    original = store._write_raw
    def delayed_write(*args, **kwargs):
        now[0] += timedelta(seconds=60)
        return original(*args, **kwargs)
    monkeypatch.setattr(store, "_write_raw", delayed_write)
    with pytest.raises(ImportSessionExpired):
        _replace(store, first)
    with pytest.raises(ImportSessionNotFound):
        _get(store, first)


def test_background_sweep_removes_unvisited_expired_sessions_in_bounded_pages(staging):
    store, now, client = staging
    expired = [_create(store) for _ in range(3)]
    now[0] += timedelta(seconds=30)
    live = _create(store)
    now[0] += timedelta(seconds=30)
    scanned = deleted = 0
    cursor = None
    while True:
        result = store.sweep_expired(cursor=cursor, limit=2)
        assert result.scanned <= 2
        scanned += result.scanned; deleted += result.deleted
        cursor = result.next_cursor
        if cursor is None:
            break
    assert scanned == 4 and deleted == 3
    if client.list_calls:
        assert client.bodies == [] and len(client.head_calls) == 4
        assert all(call[0] == "cash-import/parsed/" and call[1] == 2 for call in client.list_calls)
        assert all(client.metadata["private-imports", store._key(item.session_id)]["expires-at"] == item.expires_at.isoformat() for item in expired)
    else:
        assert set(store._entries) == {live.session_id}
    assert _get(store, live) == live


def test_sweep_does_not_extend_expiry_after_a_late_edit(staging):
    store, now, _client = staging
    first = _create(store)
    now[0] += timedelta(seconds=50)
    _replace(store, first, preview_input={"changed": True})
    now[0] += timedelta(seconds=10)
    assert store.sweep_expired(limit=10).deleted == 1


def test_wechat_scope_roundtrips_and_invalid_scope_never_replaces_session(staging):
    from ft.application.parsed_import_staging import ParsedImportDataError
    store, _now, _client = staging
    file = _file()
    file = replace(file, source_type="wechat", document=replace(file.document, source_type="wechat"), source_scope_label="工作微信")
    created = _create(store, files=(file,))
    assert _get(store, created).files[0].source_scope_label == "工作微信"
    for invalid in (replace(file, source_scope_label="  工作微信  "), replace(file, source_type="alipay", document=replace(file.document, source_type="alipay"))):
        with pytest.raises(ParsedImportDataError, match="import_session_file_invalid"):
            _replace(store, created, files=(invalid,))
    assert _get(store, created) == created


def test_r2_sweep_ignores_unrelated_keys_and_can_clean_pre_metadata_objects():
    from ft.application.parsed_import_staging import R2ParsedImportStore
    client = ConditionalS3()
    now = datetime(2026, 10, 21, 12, tzinfo=timezone.utc)
    store = R2ParsedImportStore(client, bucket="bucket", clock=lambda: now)
    for key in ("cash-import/files/original", "cash-import/parsed/unrelated", "elsewhere/parsed/" + "a" * 64 + "/session"):
        client.objects["bucket", key] = b"must not read or delete"
    old_key = "cash-import/parsed/" + "b" * 64 + "/session"
    client.objects["bucket", old_key] = b"expired financial body must not be downloaded"
    client.modified["bucket", old_key] = now - timedelta(seconds=1800)
    result = store.sweep_expired(limit=100)
    assert result.deleted == 1 and len(client.objects) == 3
    assert client.bodies == [] and client.head_calls == [old_key]


def test_r2_cleanup_failure_is_safe_and_does_not_delete_live_objects(staging, monkeypatch):
    from ft.application.cash_import_staging import ImportSessionStorageUnavailable
    store, _now, client = staging
    if not isinstance(store, __import__("ft.application.parsed_import_staging", fromlist=["R2ParsedImportStore"]).R2ParsedImportStore):
        return
    live = _create(store)
    def failed_head(**_options):
        raise RuntimeError("provider credentials and object body")
    monkeypatch.setattr(client, "head_object", failed_head)
    with pytest.raises(ImportSessionStorageUnavailable) as error:
        store.sweep_expired()
    assert "credentials" not in str(error.value) and len(client.objects) == 1
    assert _get(store, live) == live


@pytest.mark.parametrize("limit", [0, 201, True, "10"])
def test_sweep_rejects_unbounded_or_malformed_page_size(staging, limit):
    from ft.application.parsed_import_staging import ParsedImportDataError
    with pytest.raises(ParsedImportDataError, match="import_session_sweep_invalid"):
        staging[0].sweep_expired(limit=limit)


def test_r2_body_close_failure_is_still_a_safe_storage_error():
    from ft.application.parsed_import_staging import R2ParsedImportStore
    from ft.application.cash_import_staging import ImportSessionStorageUnavailable
    class FailingBody(BytesIO):
        def close(self):
            super().close()
            raise RuntimeError("provider secret while closing")
    class ClosingS3(ConditionalS3):
        def get_object(self, **kwargs):
            response = super().get_object(**kwargs)
            response["Body"] = FailingBody(response["Body"].getvalue())
            return response
    store = R2ParsedImportStore(ClosingS3(), bucket="private-imports")
    first = _create(store)
    with pytest.raises(ImportSessionStorageUnavailable) as error:
        _get(store, first)
    assert "provider secret" not in str(error.value)


@pytest.mark.parametrize("rows", [({"wrong": "row structure"},), (ParsedCashRow(1, "invalid fields"),)])
def test_invalid_nested_document_is_rejected_before_it_can_corrupt_existing_session(staging, rows):
    from ft.application.parsed_import_staging import ParsedImportDataError
    store, _now, _client = staging
    first = _create(store)
    broken = replace(first.files[0], document=replace(first.files[0].document, rows=rows))
    with pytest.raises(ParsedImportDataError):
        _replace(store, first, files=(broken,))
    assert _get(store, first) == first


def test_non_financial_json_numbers_can_roundtrip_in_source_evidence(staging):
    store, _now, _client = staging
    first = _create(store)
    second = _replace(store, first, plan={"evidence": {"score": 0.95}})
    assert _get(store, first) == second


@pytest.mark.parametrize("content", ['{"note":"\\ud800"}', '{"\\ud800":"value"}'])
def test_unpaired_unicode_surrogate_has_safe_error_and_preserves_current_revision(staging, content):
    from ft.application.parsed_import_staging import ParsedImportDataError
    store, _now, _client = staging
    first = _create(store)
    with pytest.raises(ParsedImportDataError, match="import_session_json_invalid"):
        _replace(store, first, plan=json.loads(content))
    assert _get(store, first) == first


@pytest.mark.parametrize("container", ["list", "dict"])
def test_json_scalar_depth_boundary_is_preserved(staging, container):
    from ft.application.parsed_import_staging import ParsedImportDataError
    store, _now, _client = staging
    first = _create(store)
    value = "正常字段"
    for _ in range(62):
        value = [value] if container == "list" else {"value": value}
    accepted = _replace(store, first, plan={"nested": value})
    assert _get(store, accepted).plan == {"nested": value}
    value = [value] if container == "list" else {"value": value}
    with pytest.raises(ParsedImportDataError, match="import_session_json_invalid"):
        _replace(store, accepted, plan={"nested": value})
    assert _get(store, accepted) == accepted
