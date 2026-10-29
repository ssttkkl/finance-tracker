from dataclasses import replace
from hashlib import sha256
from threading import Event
from concurrent.futures import ThreadPoolExecutor

import pytest
from sqlalchemy import select, func

from ft.adapters.cash_statement_rows import ParsedCashDocument, ParsedCashRow
from ft.domain.cash_source_matching import SourceCoverage


USER = "00000000-0000-4000-8000-000000000001"


@pytest.fixture(params=["cash_web_runtime", "postgres_cash_web_runtime"])
def context_runtime(request):
    from ft.adapters.relational.models import UserModel, WorkspaceMembershipModel
    runtime = request.getfixturevalue(request.param)
    with runtime.sessions.begin() as session:
        session.add(UserModel(id=USER, email="import-context@example.test", password_hash="synthetic")); session.flush()
        session.add(WorkspaceMembershipModel(user_id=USER, workspace_id=runtime.workspace_id, role="editor"))
    return runtime


def _document():
    return ParsedCashDocument("alipay", "csv:0", "fixture-v1", (ParsedCashRow(8, {
        "amount": "-20.00", "currency": "CNY", "occurred_at": "2026-08-15T02:00:00+00:00", "note": "原始文本",
        "source_payload": {"金额": "20.00"}}, "fixture-bank", "001"),),
        "fixture-owner", "fixture-owner", SourceCoverage(), 1, 1)


def _upload(client_ref="client-1", **changes):
    from ft.application.cash_import_context import CashImportUpload
    return CashImportUpload(client_ref, changes.get("filename", "synthetic.csv"), changes.get("content", b"synthetic-original"),
        password=changes.get("password"), source_hint=changes.get("source_hint"))


def _service(runtime, parser=None, store=None):
    from ft.application.cash_import_context import CashImportContextService
    from ft.application.parsed_import_staging import InMemoryParsedImportStore
    from ft.adapters.relational.uow import RelationalUnitOfWork
    return CashImportContextService(RelationalUnitOfWork(runtime.sessions, runtime.workspace_id),
        store or InMemoryParsedImportStore(), user_id=USER, parser=parser or (lambda _upload: _document()))


def _consume(runtime, session_id):
    from ft.adapters.relational.cash_mutations import CashMutationRepository
    with runtime.sessions.begin() as session:
        return CashMutationRepository(session, runtime.workspace_id).create(user_id=USER,
            idempotency_key="committed", request_digest="a" * 64, operation_kind="import", plan_id="plan-1",
            session_digest=sha256(session_id.encode()).hexdigest(), result_summary={"status": "no_changes"}, results=[])


def test_each_file_parsed_once_and_only_parsed_rows_survive(context_runtime):
    from ft.adapters.relational.models import CashTransactionModel
    calls = []
    def parser(upload):
        calls.append(upload.client_file_ref)
        return _document()
    service = _service(context_runtime, parser)
    with context_runtime.sessions() as session:
        before = session.scalar(select(func.count()).select_from(CashTransactionModel))
    created = service.create((_upload(password="private-password"), _upload("client-2")))
    assert calls == ["client-1", "client-2"]
    assert service.get(created.session_id).session == created
    assert calls == ["client-1", "client-2"]
    assert created.files[0].document.rows[0].fields["note"] == "原始文本"
    body = service._staging._entries[created.session_id]
    assert b"private-password" not in body and b"synthetic-original" not in body
    with context_runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == before


@pytest.mark.parametrize("kind", ["mutation"])
def test_edit_context_is_not_a_public_import(context_runtime, kind):
    from ft.application.cash_import_errors import ImportSessionNotFound
    from ft.application.parsed_import_staging import InMemoryParsedImportStore
    from ft.application.cash_import_plans import CashImportPlanError, CashImportPlanService
    from ft.adapters.relational.uow import RelationalUnitOfWork
    store = InMemoryParsedImportStore()
    service = _service(context_runtime, store=store)
    file = service.create((_upload(),)).files[0]
    private = store.create(workspace_id=context_runtime.workspace_id, user_id=USER,
        files=(), workflow_kind=kind)
    plan_id = f"ip_{private.session_id}.{'p' * 16}"
    store.replace(private.session_id, workspace_id=context_runtime.workspace_id, user_id=USER,
        expected_revision=1, files=(), preview_input={}, plan={"plan_id": plan_id})
    plans = CashImportPlanService(RelationalUnitOfWork(context_runtime.sessions, context_runtime.workspace_id),
        store, user_id=USER, builder=lambda **_kwargs: pytest.fail("private context reached builder"))
    for action in (lambda: service.get(private.session_id), lambda: service.cancel(private.session_id),
        lambda: service.add_files(private.session_id, (_upload("extra"),), expected_revision=2)):
        with pytest.raises(ImportSessionNotFound):
            action()
    for action in (lambda: plans.preview(private.session_id, expected_session_revision=2), lambda: plans._read_plan(plan_id)):
        with pytest.raises(CashImportPlanError, match="plan_expired"):
            action()
    from ft.application.cash_import_commit import CashImportCommandService
    commands = CashImportCommandService(RelationalUnitOfWork(context_runtime.sessions, context_runtime.workspace_id),
        store, user_id=USER)
    with pytest.raises(CashImportPlanError, match="plan_expired"):
        commands.commit(plan_id, expected_session_revision=2, idempotency_key="synthetic-private-context")
    assert commands.resolve(plan_id, idempotency_key="synthetic-private-context") == {"status": "not_committed"}


def test_file_parser_failure_and_password_request_are_isolated(context_runtime):
    from ft.application.cash_import_context import CashImportPasswordRequired
    def parser(upload):
        if upload.client_file_ref == "bad":
            raise RuntimeError("SQL and technical provider detail")
        if upload.client_file_ref == "password":
            raise CashImportPasswordRequired()
        return _document()
    created = _service(context_runtime, parser).create((_upload("bad"), _upload("good"), _upload("password")))
    assert [file.status for file in created.files] == ["failed", "parsed", "needs_password"]
    assert [file.error_code for file in created.files] == ["statement_unreadable", None, "password_required"]
    assert "technical provider detail" not in repr(created)
    assert created.files[-1].document is None


def test_default_parser_accepts_bank_tables_and_preserves_incomplete_rows(context_runtime):
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.application.cash_import_context import CashImportContextService
    from ft.application.parsed_import_staging import InMemoryParsedImportStore
    from test_bank_statement_rows import _asia_bytes, _asia_row, _ccb_bytes, _ccb_row
    service = CashImportContextService(RelationalUnitOfWork(context_runtime.sessions, context_runtime.workspace_id),
        InMemoryParsedImportStore(), user_id=USER)
    created = service.create((_upload("ccb", filename="misleading.csv", content=_ccb_bytes(_ccb_row(), _ccb_row(序号="2", 交易金额="bad"))),
        _upload("asia", filename="misleading.xls", content=_asia_bytes(_asia_row())),
        _upload("bad", content=b"unrecognized")))
    assert [file.status for file in created.files] == ["parsed", "parsed", "failed"]
    assert created.files[0].document.rows[1].issue_codes == ("amount_unreadable",)
    assert created.files[1].document.rows[0].fields["amount"] == "-100.50"
    restored = service.get(created.session_id).session
    assert restored == created
    assert restored.files[0].document.declared_count is None


def test_pdf_password_retry_uses_only_new_upload_and_scrubs_password_from_display_name(context_runtime, monkeypatch):
    from pdfminer.pdfdocument import PDFPasswordIncorrect
    from ft.application.cash_import_context import CashImportContextService
    from ft.application.parsed_import_staging import InMemoryParsedImportStore
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from test_icbc_pdf_statement import PDF, Page, _credit, _install
    import pdfplumber
    page = Page(_credit())
    document, _calls = _install(monkeypatch, [page])
    passwords, streams = [], []
    def open_(stream, *, password=None):
        passwords.append(password)
        streams.append(stream)
        if password != "fixture-secret":
            raise PDFPasswordIncorrect("private details")
        return document
    monkeypatch.setattr(pdfplumber, "open", open_)
    store = InMemoryParsedImportStore()
    service = CashImportContextService(RelationalUnitOfWork(context_runtime.sessions, context_runtime.workspace_id), store, user_id=USER)
    created = service.create((_upload(filename="工行（密码fixture-secret）.pdf", content=PDF),))
    first = created.files[0]
    assert first.status == "needs_password" and first.error_code == "password_required"
    assert "fixture-secret" not in first.filename
    wrong = service.replace_file(created.session_id, first.file_ref,
        _upload(filename="工行（密码fixture-secret）.pdf", content=PDF, password="wrong"), expected_revision=1)
    assert wrong.files[0].status == "needs_password" and wrong.files[0].error_code == "password_incorrect"
    finished = service.replace_file(created.session_id, wrong.files[0].file_ref,
        _upload(filename="工行（密码fixture-secret）.pdf", content=PDF, password="fixture-secret"), expected_revision=2)
    assert finished.files[0].status == "parsed" and finished.revision == 3
    assert finished.expires_at == created.expires_at
    assert passwords == [None, "wrong", "fixture-secret"] and all(stream.closed for stream in streams)
    restored = service.get(created.session_id).session
    assert restored == finished and "fixture-secret" not in repr(restored)


@pytest.mark.parametrize("filename,password", [
    ("工行流水密码（fixture-secret）.pdf", None),
    ("工行流水（密码：fixture-secret）.pdf", None),
    ("账" * 250 + "fixture-secret.pdf", "fixture-secret"),
])
def test_password_is_scrubbed_from_filename_before_truncation(context_runtime, filename, password):
    created = _service(context_runtime).create((_upload(filename=filename, password=password),))
    name = created.files[0].filename
    assert "fixture-secret" not in name and "fixtu" not in name and len(name) <= 255


def test_bad_document_from_one_importer_does_not_block_the_other_file(context_runtime):
    def parser(upload):
        return replace(_document(), rows=({"wrong": "shape"},)) if upload.client_file_ref == "bad" else _document()
    created = _service(context_runtime, parser).create((_upload("bad"), _upload("good")))
    assert [file.status for file in created.files] == ["failed", "parsed"]


def test_replace_reparses_only_uploaded_file_and_changes_row_namespace(context_runtime):
    calls = []
    service = _service(context_runtime, lambda upload: calls.append(upload.client_file_ref) or _document())
    created = service.create((_upload(), _upload("client-2")))
    old_ref = created.files[0].file_ref
    service._staging.replace(created.session_id, workspace_id=context_runtime.workspace_id, user_id=USER, expected_revision=1,
        files=created.files, preview_input={"row_choices": [{"row_ref": f"{old_ref}:r8"}, {"row_ref": f"{created.files[1].file_ref}:r8"}]}, plan={"plan_id": "obsolete"})
    updated = service.replace_file(created.session_id, old_ref, _upload(password="one-use"), expected_revision=2)
    assert calls == ["client-1", "client-2", "client-1"]
    assert updated.revision == 3 and updated.plan is None
    assert updated.files[0].file_ref != old_ref
    assert updated.files[1] == created.files[1]
    assert updated.preview_input["row_choices"] == [{"row_ref": f"{created.files[1].file_ref}:r8"}]
    assert b"one-use" not in service._staging._entries[created.session_id]


def test_wechat_nickname_is_available_only_on_the_temporary_account_group(context_runtime):
    from ft.application.cash_import_session_view import cash_import_session_view
    from ft.application.cash_import_funding import scan_cash_funding
    service = _service(context_runtime, lambda _: replace(_document(), source_type="wechat",
        source_account_identifier=None, source_account_label="微信昵称甲", source_nickname="微信昵称甲"))
    created = service.create((_upload(),))
    groups = scan_cash_funding(created.files).groups
    view = cash_import_session_view(created, groups, {group.source_account_ref: {
        "account_id": None, "expected_account_revision": None, "basis": "none"} for group in groups})
    assert "source_scope_label" not in view["files"][0]
    assert view["source_accounts"][0]["session_label"] == "微信昵称甲"
    assert "微信昵称甲" not in view["source_accounts"][0]["source_account_ref"]


def test_replacement_reads_new_wechat_nickname_without_carrying_prior_session_label(context_runtime):
    from ft.application.cash_import_funding import scan_cash_funding
    service = _service(context_runtime, lambda upload: replace(_document(), source_type="wechat",
        source_account_identifier=None, source_account_label=upload.filename, source_nickname=upload.filename))
    created = service.create((_upload(filename="昵称甲.xlsx"),))
    first_group, = scan_cash_funding(created.files).groups
    updated = service.replace_file(created.session_id, created.files[0].file_ref,
        _upload(filename="昵称乙.xlsx"), expected_revision=created.revision)
    second_group, = scan_cash_funding(updated.files).groups
    assert first_group.session_label == "昵称甲.xlsx"
    assert second_group.session_label == "昵称乙.xlsx"
    assert first_group.source_account_ref != second_group.source_account_ref


def test_add_remove_and_stale_revision_do_not_reread_original_files(context_runtime):
    from ft.application.parsed_import_staging import ImportSessionRevisionConflict
    calls = []
    service = _service(context_runtime, lambda upload: calls.append(upload.client_file_ref) or _document())
    first = service.create((_upload(),))
    added = service.add_files(first.session_id, (_upload("client-2"),), expected_revision=1)
    removed = service.remove_file(first.session_id, first.files[0].file_ref, expected_revision=2)
    assert calls == ["client-1", "client-2"]
    assert removed.files == (added.files[-1],)
    with pytest.raises(ImportSessionRevisionConflict):
        service.add_files(first.session_id, (_upload("stale"),), expected_revision=1)
    assert calls == ["client-1", "client-2"]


def test_source_can_be_detected_and_converted_without_a_second_reader(context_runtime):
    from ft.application.cash_import_context import CashImportContextService
    from ft.application.parsed_import_staging import InMemoryParsedImportStore
    from ft.adapters.relational.uow import RelationalUnitOfWork
    service = CashImportContextService(RelationalUnitOfWork(context_runtime.sessions, context_runtime.workspace_id), InMemoryParsedImportStore(), user_id=USER)
    csv = "交易时间,收/支,金额,交易对方,商品说明,交易订单号\n2026-08-15 10:00:00,支出,20.00,测试商户,原始说明,001\n".encode()
    created = service.create((_upload(content=csv),))
    assert created.files[0].source_type == "alipay"
    assert created.files[0].document.rows[0].fields["amount"] == "-20.00"


def test_parsing_does_not_hold_the_workspace_lock(context_runtime):
    from ft.adapters.relational.uow import RelationalUnitOfWork
    def parser(_upload):
        entered = Event()
        def other_command():
            with RelationalUnitOfWork(context_runtime.sessions, context_runtime.workspace_id):
                entered.set()
        with ThreadPoolExecutor(max_workers=1) as executor:
            future = executor.submit(other_command)
            assert entered.wait(2), "heavy parsing must be outside the workspace boundary"
            future.result(timeout=2)
        return _document()
    assert _service(context_runtime, parser).create((_upload(),)).files[0].status == "parsed"


def test_revoked_import_permission_is_rechecked_after_parsing(context_runtime):
    from ft.adapters.relational.models import WorkspaceMembershipModel
    from ft.application.cash_import_context import CashImportPermissionDenied
    def parser(_upload):
        with context_runtime.sessions.begin() as session:
            session.get(WorkspaceMembershipModel, (context_runtime.workspace_id, USER)).role = "viewer"
        return _document()
    service = _service(context_runtime, parser)
    with pytest.raises(CashImportPermissionDenied):
        service.create((_upload(),))
    assert service._staging._entries == {}


def test_commit_while_file_parses_prevents_late_publication(context_runtime):
    from ft.adapters.relational.cash_mutations import CashMutationConflict
    service = _service(context_runtime)
    first = service.create((_upload(),))
    service._parser = lambda _upload: (_consume(context_runtime, first.session_id), _document())[1]
    with pytest.raises(CashMutationConflict, match="session_committed"):
        service.add_files(first.session_id, (_upload("late"),), expected_revision=1)
    saved = service._staging.get(first.session_id, workspace_id=context_runtime.workspace_id, user_id=USER)
    assert saved == first


def test_consumed_session_is_resolved_before_staging_and_cannot_be_cancelled(context_runtime):
    from ft.adapters.relational.cash_mutations import CashMutationConflict
    service = _service(context_runtime)
    first = service.create((_upload(),))
    receipt = _consume(context_runtime, first.session_id)
    service._staging._entries.clear()
    context = service.get(first.session_id)
    assert context.session is None and context.receipt["receipt_id"] == receipt["receipt_id"]
    with pytest.raises(CashMutationConflict, match="session_committed"):
        service.cancel(first.session_id)


def test_cancel_clears_body_and_cannot_be_reopened(context_runtime):
    from ft.application.parsed_import_staging import ImportSessionClosed
    service = _service(context_runtime)
    first = service.create((_upload(),))
    cancelled = service.cancel(first.session_id)
    assert cancelled.cancelled and cancelled.files == ()
    assert service.cancel(first.session_id) == cancelled
    with pytest.raises(ImportSessionClosed):
        service.add_files(first.session_id, (_upload("later"),), expected_revision=cancelled.revision)


def test_aggregate_budget_marks_new_file_failed_and_keeps_existing_file(context_runtime):
    from ft.application.parsed_import_staging import InMemoryParsedImportStore
    store = InMemoryParsedImportStore(max_bytes=5000)
    def parser(upload):
        doc = _document()
        if upload.client_file_ref == "large":
            doc.rows[0].fields["note"] = "x" * 3600
        return doc
    service = _service(context_runtime, parser, store)
    first = service.create((_upload(),))
    added = service.add_files(first.session_id, (_upload("large"),), expected_revision=1)
    assert added.files[0] == first.files[0]
    assert added.files[1].status == "failed" and added.files[1].error_code == "statement_rows_too_large"


def test_oversized_file_keeps_observed_size_and_does_not_invoke_parser(context_runtime, monkeypatch):
    import ft.application.cash_import_context as module
    monkeypatch.setattr(module, "_MAX_UPLOAD_BYTES", 5)
    calls = []
    created = _service(context_runtime, lambda upload: calls.append(upload) or _document()).create((_upload(content=b"123456"),))
    assert calls == []
    assert created.files[0].size == 6
    assert created.files[0].status == "failed" and created.files[0].document is None


def test_unexpected_converter_diagnostic_survives_staging_and_replacement_clears_it(context_runtime, caplog):
    import logging
    from ft.application.cash_import_session_view import cash_import_file_view
    from ft.cash_diagnostics import cash_diagnostic_scope
    def parser(upload):
        if upload.content == b"broken":
            with cash_diagnostic_scope("row_conversion", [{"row_number": 8, "position_kind": "csv_record"}], scope="row"):
                raise RuntimeError("private SQL account password")
        return _document()
    service = _service(context_runtime, parser)
    with caplog.at_level(logging.ERROR):
        current = service.create((_upload(content=b"broken", filename="bill.csv"),))
    file, = current.files
    diagnostic = file.diagnostic
    assert diagnostic["stage"] == "row_conversion" and diagnostic["scope"] == "row"
    assert diagnostic["locations"] == [{"file_ref": file.file_ref, "display_name": "bill.csv",
        "row_ref": file.file_ref + ":r8", "row_number": 8, "position_kind": "csv_record"}]
    assert diagnostic["error_id"] in caplog.text and "private SQL" not in caplog.text
    restored = service.get(current.session_id).session
    assert cash_import_file_view(restored.files[0])["diagnostic"] == diagnostic
    result = service.replace_file(current.session_id, file.file_ref, _upload(content=b"fixed"), expected_revision=current.revision)
    assert result.files[0].diagnostic is None
