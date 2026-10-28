"""实际来源与资金映射共用字段规则，逐来源行得到可执行决定。"""
from dataclasses import replace
from decimal import Decimal

import pytest
from sqlalchemy import func, select, update

from ft.adapters.cash_statement_rows import ParsedCashDocument, ParsedCashRow
from ft.adapters.relational.cash_sources import CashSourceRepository
from ft.adapters.relational.models import AccountModel, CashTransactionModel
from ft.application.cash_import_funding import scan_cash_funding
from ft.application.cash_import_plans import CashImportPlanError
from ft.application.cash_import_source_rows import source_batch_rows
from ft.application.parsed_import_staging import ParsedImportFile
from ft.domain.cash_source_matching import SourceCoverage
from tests.contract.test_cash_draft_storage import draft_runtime


@pytest.fixture(autouse=True)
def _configured_currencies(draft_runtime):
    with draft_runtime.sessions.begin() as session:
        session.execute(update(AccountModel).where(AccountModel.workspace_id == draft_runtime.workspace_id,
            AccountModel.id.in_((101, 102))).values(currencies=["CNY"]))


def _file(*, amount="-12.5", name="合成商户", note="原始备注", letter="a", provider="001", rows=None,
    source_status=None, last_modified_at_ms=None):
    fields = {"amount": amount, "currency": "CNY", "occurred_at": "2026-07-05T00:00:00Z",
        "record_type": "consumption", "record_subtype": "not_applicable", "counterparty": name,
        "counterparty_account": "", "counterparty_account_attrs": [], "note": note,
        "source_payload": {"交易号": provider, "备注": note}}
    if source_status is not None:
        fields["source_payload"]["状态"] = source_status
    row = ParsedCashRow(1, fields, "合成支付账户", provider)
    document = ParsedCashDocument("alipay", "table", "fixture-v1", tuple(rows) if rows is not None else (row,),
        "fixture-owner", None, SourceCoverage(), len(rows) if rows is not None else 1, len(rows) if rows is not None else 1)
    return ParsedImportFile(f"file_{letter}", f"client_{letter}", "synthetic.csv", letter * 64, 100, "alipay", "parsed", document,
        last_modified_at_ms=last_modified_at_ms)


_UNSET_OWNER = object()


def _as_source(file, source, *, owner=_UNSET_OWNER, scope=None):
    document_owner = file.document.source_account_identifier if owner is _UNSET_OWNER else owner
    rows = file.document.rows
    if source in {"icbc_credit", "icbc_debit", "ccb_debit", "icbc_asia"}:
        base = {"icbc_credit": "工商银行信用卡", "icbc_debit": "工商银行借记卡",
            "ccb_debit": "建设银行", "icbc_asia": "工银亚洲"}[source]
        updated = []
        for row in rows:
            row_owner = row.source_account_identifier or document_owner
            tail = row_owner[-4:] if isinstance(row_owner, str) and row_owner.isascii() and row_owner.isdigit() else ""
            label = base + (f"({tail})" if tail and source != "icbc_asia" else (f"（尾号 {tail}）" if tail else ""))
            updated.append(replace(row, source_explanation={**row.source_explanation, "source_display_name": label}))
        rows = tuple(updated)
    document = replace(file.document, source_type=source, source_account_identifier=document_owner, rows=rows)
    return replace(file, source_type=source, source_scope_label=scope, document=document)


def _selections(files, account_id="101", revision=1):
    return [{"source_account_ref": group.source_account_ref, "account_id": account_id,
        "expected_account_revision": revision} for group in scan_cash_funding(files).groups]


def _prepare(session, runtime, files, *, selections=None, choices=None):
    from ft.application.cash_import_operations import CashImportOperationPlanner
    return CashImportOperationPlanner(session, runtime.workspace_id).prepare(tuple(files),
        account_selections=selections or [], row_choices=choices or [])


def test_new_record_has_fields_without_fabricated_update_differences(draft_runtime):
    file = _file()
    with draft_runtime.sessions() as session:
        result = _prepare(session, draft_runtime, (file,), selections=_selections((file,)))
        operation, = result.operations
        assert operation["planned_action"] == "create_posted"
        assert operation["values"]["amount"] == "-12.5" and operation["current"] is None
        assert operation["field_diffs"] == []


def test_missing_alipay_funding_uses_wallet_group_and_can_post(draft_runtime):
    original = _file()
    row = replace(original.document.rows[0], payment_method="")
    file = replace(original, document=replace(original.document, rows=(row,)))
    group, = scan_cash_funding((file,)).groups
    assert group.display_name == "支付宝余额"
    with draft_runtime.sessions() as session:
        result = _prepare(session, draft_runtime, (file,), selections=_selections((file,)))
    operation, = result.operations
    assert operation["planned_action"] == "create_posted"
    assert operation["values"]["account_id"] == "101"
    assert operation["values"]["components"] == [{"ordinal": 0, "account_id": "101", "amount": "-12.5", "currency": "CNY"}]
    assert "funding_unrecognized" not in {issue["code"] for issue in operation["issues"]}


@pytest.mark.parametrize("protected,explicit,expected", [(False, None, "人工修改的来源原文"), (True, None, "人工旧备注"), (False, "", "")])
def test_import_user_source_payload_patch_is_rejected_without_partial_public_edit(draft_runtime, protected, explicit, expected):
    runtime, file = draft_runtime, _file()
    with runtime.sessions.begin() as session:
        _bind_existing(session, runtime, file, note="人工旧备注" if protected else None)
    patch = {"source_payload": {"备注": "伪造来源原文"}}
    if explicit is not None:
        patch["note"] = explicit
    with runtime.sessions() as session:
        with pytest.raises(CashImportPlanError, match="plan_input_invalid"):
            _prepare(session, runtime, (file,), choices=[{"row_ref": "file_a:r1", "action": "apply", "patch": patch}])
        assert session.get(CashTransactionModel, 1003).source_payload != patch["source_payload"]


def test_posted_user_source_payload_noop_is_still_not_an_editable_field(draft_runtime):
    runtime, file = draft_runtime, _file()
    with runtime.sessions.begin() as session:
        _bind_existing(session, runtime, file)
    with runtime.sessions() as session:
        with pytest.raises(CashImportPlanError, match="plan_input_invalid"):
            _prepare(session, runtime, (file,), choices=[{"row_ref": "file_a:r1", "action": "apply",
                "patch": {"source_payload": file.document.rows[0].fields["source_payload"]}}])


def _bind_existing(session, runtime, file, *, note=None):
    parent = session.get(CashTransactionModel, 1003)
    row = next(iter(source_batch_rows((file,)).values()))
    parent.source_type, parent.record_id = row.source_type, "synthetic-existing"
    for field in ("amount", "currency", "occurred_at", "record_type", "record_subtype", "counterparty",
        "counterparty_account", "counterparty_account_attrs", "note", "source_payload"):
        value = row.fields[field]
        if field == "amount":
            value = Decimal(value)
        if field == "occurred_at":
            from datetime import datetime
            value = datetime.fromisoformat(value.replace("Z", "+00:00"))
        setattr(parent, field, value)
    if note is not None:
        parent.note, parent.manual_overrides = note, {"note": True}
    session.flush()
    CashSourceRepository(session, runtime.workspace_id).save_occurrence(1003, row)


def test_complete_new_and_unmapped_new_have_formal_or_draft_decisions_without_writes(draft_runtime):
    runtime, file = draft_runtime, _file()
    with runtime.sessions() as session:
        before = session.scalar(select(func.count()).select_from(CashTransactionModel))
        posted = _prepare(session, runtime, (file,), selections=_selections((file,)))
        draft = _prepare(session, runtime, (file,))
        assert [row["planned_action"] for row in posted.rows] == ["create_posted"]
        assert [row["planned_action"] for row in draft.rows] == ["create_draft"]
        assert posted.operations[0]["values"]["amount"] == "-12.5"
        assert posted.operations[0]["values"]["account_id"] == "101"
        assert draft.operations[0]["values"]["account_id"] is None
        assert any(issue["code"] == "account_missing" for issue in draft.operations[0]["issues"])
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == before
        assert not session.new and not session.dirty


def test_repeated_provider_row_across_files_creates_one_operation_with_all_row_results(draft_runtime):
    runtime = draft_runtime
    files = (_file(), _file(letter="b"))
    with runtime.sessions() as session:
        prepared = _prepare(session, runtime, files, selections=_selections(files))
        assert len(prepared.operations) == 1 and len(prepared.rows) == 2
        assert sorted(row["planned_action"] for row in prepared.rows) == ["create_posted", "duplicate_in_batch"]
        assert len({row["operation_ref"] for row in prepared.rows}) == 1
        assert len(prepared.operations[0]["source_updates"]) == 2


def test_same_batch_uses_newest_file_snapshot_for_one_existing_source_update(draft_runtime):
    runtime = draft_runtime
    initial = _file(letter="s", source_status="等待确认收货")
    with runtime.sessions.begin() as session:
        _bind_existing(session, runtime, initial)
    newer = _file(letter="a", source_status="交易成功", last_modified_at_ms=200)
    older = _file(letter="b", source_status="等待确认收货", last_modified_at_ms=100)
    files = (newer, older)
    with runtime.sessions() as session:
        prepared = _prepare(session, runtime, files, selections=_selections(files))
    operation, = prepared.operations
    assert operation["planned_action"] == "update_posted"
    assert operation["values"]["source_payload"]["状态"] == "交易成功"
    assert [(row["row_ref"], row["planned_action"]) for row in prepared.rows] == [
        ("file_a:r1", "update_posted"), ("file_b:r1", "duplicate_in_batch")]


def test_cross_batch_source_update_ignores_old_and_new_file_modification_order(draft_runtime):
    runtime = draft_runtime
    initial = _file(letter="s", source_status="等待确认收货", last_modified_at_ms=200)
    with runtime.sessions.begin() as session:
        _bind_existing(session, runtime, initial)
    next_batch = _file(letter="a", source_status="交易成功", last_modified_at_ms=100)
    with runtime.sessions() as session:
        prepared = _prepare(session, runtime, (next_batch,))
    operation, = prepared.operations
    assert operation["planned_action"] == "update_posted"
    assert operation["values"]["source_payload"]["状态"] == "交易成功"


def test_source_changes_preserve_manual_note_and_do_not_require_active_account_mapping(draft_runtime):
    runtime, first = draft_runtime, _file()
    with runtime.sessions.begin() as session:
        _bind_existing(session, runtime, first, note="人工备注")
        session.get(AccountModel, 101).active = False
    changed = _file(letter="b", name="当前商户", note="新来源备注")
    with runtime.sessions() as session:
        prepared = _prepare(session, runtime, (changed,))
        operation, = prepared.operations
        assert operation["planned_action"] == "update_posted" and operation["transaction_id"] == "1003"
        assert operation["values"]["counterparty"] == "当前商户" and operation["values"]["note"] == "新来源备注"
        assert operation["values"]["account_id"] == "101"
        assert operation["values"]["source_payload"] == changed.document.rows[0].fields["source_payload"]
        assert any(diff["field"] == "note" and diff["resolution"] == "apply" for diff in operation["field_diffs"])


def test_fixed_amount_conflict_skips_only_one_row_without_partial_mutable_updates(draft_runtime):
    runtime, first = draft_runtime, _file()
    with runtime.sessions.begin() as session:
        _bind_existing(session, runtime, first)
    bad = _file(amount="-99", name="不可部分更新", letter="b")
    good = _file(provider="002", letter="c")
    with runtime.sessions() as session:
        prepared = _prepare(session, runtime, (bad, good), selections=_selections((bad, good)))
        assert prepared.rows[0]["planned_action"] == "skip" and prepared.rows[0]["code"] == "fixed_field_conflict"
        assert prepared.rows[1]["planned_action"] == "create_posted" and len(prepared.operations) == 1
        assert session.get(CashTransactionModel, 1003).counterparty == first.document.rows[0].fields["counterparty"]


def test_explicit_same_value_note_is_a_manual_decision_even_when_source_is_duplicate(draft_runtime):
    runtime, first = draft_runtime, _file()
    with runtime.sessions.begin() as session:
        _bind_existing(session, runtime, first)
    with runtime.sessions() as session:
        prepared = _prepare(session, runtime, (first,), choices=[{"row_ref": "file_a:r1", "action": "apply", "patch": {"note": "原始备注"}}])
        operation, = prepared.operations
        assert operation["planned_action"] == "update_posted" and operation["entry_edit"]["patch"] == {"note": "原始备注"}


def test_manual_note_patch_overrides_protection_and_fixed_manual_patch_is_rejected(draft_runtime):
    runtime, first = draft_runtime, _file()
    with runtime.sessions.begin() as session:
        _bind_existing(session, runtime, first, note="人工备注")
    with runtime.sessions() as session:
        cleared = _prepare(session, runtime, (first,), choices=[{"row_ref": "file_a:r1", "action": "apply", "patch": {"note": ""}}])
        assert cleared.operations[0]["entry_edit"]["patch"] == {"note": ""} and cleared.operations[0]["values"]["note"] == "原始备注"
        with pytest.raises(CashImportPlanError, match="plan_input_invalid"):
            _prepare(session, runtime, (first,), choices=[{"row_ref": "file_a:r1", "action": "apply", "patch": {"amount": "-100"}}])


def test_duplicate_keeps_result_and_binding_work_but_does_not_claim_record_update(draft_runtime):
    runtime, first = draft_runtime, _file()
    with runtime.sessions.begin() as session:
        _bind_existing(session, runtime, first)
    with runtime.sessions() as session:
        prepared = _prepare(session, runtime, (_file(letter="b"),))
        assert prepared.rows[0]["planned_action"] == "duplicate"
        operation, = prepared.operations
        assert operation["planned_action"] == "duplicate" and operation["source_updates"][0]["bind"] is True


def test_known_source_update_can_defer_on_existing_record_without_creating_a_draft(draft_runtime):
    runtime, first = draft_runtime, _file()
    with runtime.sessions.begin() as session:
        _bind_existing(session, runtime, first)
    changed = _file(letter="b", name="稍后处理的名称")
    with runtime.sessions() as session:
        prepared = _prepare(session, runtime, (changed,), choices=[{"row_ref": "file_b:r1", "action": "defer"}])
        operation, = prepared.operations
        assert operation["transaction_id"] == "1003" and operation["values"]["state"] == "posted"
        assert operation["values"]["counterparty"] == first.document.rows[0].fields["counterparty"]
        assert operation["pending_fields"]["counterparty"] == "稍后处理的名称"
        assert operation["issues"] and operation["planned_action"] == "update_posted"


def test_bad_row_patch_rejects_plan_but_unreadable_source_does_not_block_other_rows(draft_runtime):
    runtime = draft_runtime
    first = _file()
    good = _file(provider="002", letter="b")
    unreadable = _file(letter="c", rows=(ParsedCashRow(1, None, disposition="skipped", error_code="row_unreadable"),))
    with runtime.sessions() as session:
        with pytest.raises(CashImportPlanError, match="plan_input_invalid"):
            _prepare(session, runtime, (first, good, unreadable), selections=_selections((first, good)),
                choices=[{"row_ref": "file_a:r1", "action": "apply", "patch": {"record_type": "invalid"}}])
        prepared = _prepare(session, runtime, (first, good, unreadable), selections=_selections((first, good)))
        assert [row["planned_action"] for row in prepared.rows] == ["create_posted", "create_posted", "skip"]
        assert prepared.rows[2]["code"] == "row_unreadable"


def test_selected_different_financial_account_is_conflict_for_existing_posted_record(draft_runtime):
    runtime, first = draft_runtime, _file()
    with runtime.sessions.begin() as session:
        _bind_existing(session, runtime, first)
    changed = _file(letter="b", name="新名称")
    with runtime.sessions() as session:
        prepared = _prepare(session, runtime, (changed,), selections=_selections((changed,), account_id="102"))
        assert prepared.rows[0]["planned_action"] == "skip" and prepared.rows[0]["code"] == "fixed_field_conflict"


@pytest.mark.parametrize("second_note", ["一个决定", "另一个决定"])
def test_multiple_choices_for_one_source_operation_are_invalid(draft_runtime, second_note):
    from ft.application.cash_import_plans import CashImportPlanError
    files = (_file(), _file(letter="b"))
    with draft_runtime.sessions() as session:
        with pytest.raises(CashImportPlanError, match="plan_input_invalid"):
            _prepare(session, draft_runtime, files, selections=_selections(files), choices=[
            {"row_ref": "file_a:r1", "action": "apply", "patch": {"note": "一个决定"}},
            {"row_ref": "file_b:r1", "action": "apply", "patch": {"note": second_note}}])


def test_keep_existing_requires_a_matched_saved_record(draft_runtime):
    from ft.application.cash_import_plans import CashImportPlanError
    file = _file()
    choice = [{"row_ref": "file_a:r1", "action": "keep_existing"}]
    with draft_runtime.sessions() as session:
        with pytest.raises(CashImportPlanError, match="plan_input_invalid"):
            _prepare(session, draft_runtime, (file,), choices=choice)
    with draft_runtime.sessions.begin() as session:
        _bind_existing(session, draft_runtime, file)
    with draft_runtime.sessions() as session:
        prepared = _prepare(session, draft_runtime, (file,), choices=choice)
        assert prepared.rows[0]["planned_action"] == "duplicate"
        assert prepared.operations[0]["transaction_id"] == "1003"


def test_same_file_ambiguous_draft_does_not_finalize_on_complete_reupload(draft_runtime):
    file = _file()
    with draft_runtime.sessions.begin() as session:
        _bind_existing(session, draft_runtime, file)
        session.get(CashTransactionModel, 1003).state = "draft"
        CashSourceRepository(session, draft_runtime.workspace_id).save_issue(1003, "source_identity",
            code="source_identity_ambiguous", candidates={1001: 1})
    with draft_runtime.sessions() as session:
        operation, = _prepare(session, draft_runtime, (file,), selections=_selections((file,))).operations
        assert operation["values"]["state"] == "draft"
        assert operation["planned_action"] != "finalize_draft"
        assert any(issue["code"] == "source_identity_ambiguous" for issue in operation["issues"])


def test_new_draft_never_substitutes_currency_or_guesses_financial_account(draft_runtime):
    file = _file()
    with draft_runtime.sessions.begin() as session:
        session.get(AccountModel, 101).currencies = ["USD"]
    with draft_runtime.sessions() as session:
        operation, = _prepare(session, draft_runtime, (file,), selections=_selections((file,), revision=2)).operations
        assert operation["values"]["state"] == "draft" and operation["values"]["currency"] == "CNY"
        assert any(issue["code"] == "account_currency_missing" for issue in operation["issues"])


def test_bad_category_shape_and_foreign_category_reject_plan(draft_runtime):
    files = (_file(), _file(letter="b", provider="002"))
    with draft_runtime.sessions() as session:
        for patch in ({"category_id": {"id": "wrong"}}, {"category_id": "foreign-category"}):
            with pytest.raises(CashImportPlanError, match="plan_input_invalid"):
                _prepare(session, draft_runtime, files, selections=_selections(files), choices=[
                    {"row_ref": "file_a:r1", "action": "apply", "patch": patch}])


def test_posted_type_edit_rejects_plan_without_changing_current_record(draft_runtime):
    file = _file()
    with draft_runtime.sessions.begin() as session:
        _bind_existing(session, draft_runtime, file)
    with draft_runtime.sessions() as session:
        with pytest.raises(CashImportPlanError, match="plan_input_invalid"):
            _prepare(session, draft_runtime, (file,), choices=[{"row_ref": "file_a:r1", "action": "apply",
                "patch": {"record_type": "transfer_out", "record_subtype": "ordinary_transfer"}}])
        assert session.get(CashTransactionModel, 1003).record_type == "consumption"
        assert not session.new and not session.dirty


def test_manual_type_patch_incompatible_with_source_subtype_rejects_plan(draft_runtime):
    file = _file()
    with draft_runtime.sessions() as session:
        with pytest.raises(CashImportPlanError, match="plan_input_invalid"):
            _prepare(session, draft_runtime, (file,), selections=_selections((file,)), choices=[
                {"row_ref": "file_a:r1", "action": "apply", "patch": {"record_type": "transfer_out"}}])


def test_unexpected_row_preparation_failure_stops_plan(draft_runtime, monkeypatch):
    from ft.application.cash_import_operations import CashImportOperationPlanner
    original = CashImportOperationPlanner._operation

    def fail_one(self, ref, *args, **kwargs):
        if ref == "file_a:r1":
            raise RuntimeError("SQL private source body")
        return original(self, ref, *args, **kwargs)

    monkeypatch.setattr(CashImportOperationPlanner, "_operation", fail_one)
    files = (_file(), _file(letter="b", provider="002"))
    with draft_runtime.sessions() as session:
        with pytest.raises(RuntimeError):
            _prepare(session, draft_runtime, files, selections=_selections(files))


def test_fixed_conflict_explains_the_actual_field_in_safe_row_result(draft_runtime):
    file = _file()
    with draft_runtime.sessions.begin() as session:
        _bind_existing(session, draft_runtime, file)
    with draft_runtime.sessions() as session:
        prepared = _prepare(session, draft_runtime, (_file(amount="-20", letter="b"),))
        issue, = prepared.rows[0]["issues"]
        assert issue["field_path"] == "amount" and "金额" in issue["message"]


@pytest.mark.parametrize("account_id,active,expected", [("101", True, "create_posted"),
    ("101", False, None), ("104", True, None)])
def test_explicit_draft_financial_completion_revalidates_account_and_clears_old_missing_reason(draft_runtime, account_id, active, expected):
    file = _file()
    with draft_runtime.sessions.begin() as session:
        session.get(AccountModel, 101).active = active
    patch = {"account_id": account_id, "components": [{"ordinal": 0, "account_id": account_id,
        "amount": "-12.5", "currency": "CNY"}]}
    with draft_runtime.sessions() as session:
        if expected is None:
            with pytest.raises(CashImportPlanError, match="plan_input_invalid"):
                _prepare(session, draft_runtime, (file,), choices=[{"row_ref": "file_a:r1", "action": "apply", "patch": patch}])
            return
        operation, = _prepare(session, draft_runtime, (file,), choices=[{"row_ref": "file_a:r1", "action": "apply", "patch": patch}]).operations
        assert operation["planned_action"] == expected
        if expected == "create_posted":
            assert not operation["issues"] and operation["expected_accounts"] == [{"account_id": "101",
                "exists": True, "active": True, "type": "cash", "currency_support": {"CNY": True}}]


@pytest.mark.parametrize("parts", [
    [{"ordinal": 0, "account_id": "101", "amount": "-6", "currency": "CNY"},
     {"ordinal": 0, "account_id": "102", "amount": "-6.5", "currency": "CNY"}],
    [{"ordinal": 0, "account_id": "101", "amount": "-1", "currency": "CNY"}],
    [{"ordinal": 0, "account_id": "101", "amount": "12.5", "currency": "CNY"}],
])
@pytest.mark.parametrize("missing_time", [False, True])
def test_explicit_financial_contradictions_reject_plan_instead_of_creating_draft(draft_runtime, parts, missing_time):
    file = _file()
    if missing_time:
        row = replace(file.document.rows[0], fields={**file.document.rows[0].fields, "occurred_at": None})
        file = replace(file, document=replace(file.document, rows=(row,)))
    patch = {"cash_granularity": "aggregate", "account_id": None, "components": parts}
    with draft_runtime.sessions() as session:
        with pytest.raises(CashImportPlanError, match="plan_input_invalid"):
            _prepare(session, draft_runtime, (file,), choices=[{"row_ref": "file_a:r1", "action": "apply", "patch": patch}])


def test_corrected_missing_amount_and_time_can_be_posted_without_stale_parser_issues(draft_runtime):
    file = _file()
    row = replace(file.document.rows[0], fields={**file.document.rows[0].fields, "amount": None, "occurred_at": None},
        issue_codes=("amount_unreadable", "time_unreadable"))
    file = replace(file, document=replace(file.document, rows=(row,)))
    patch = {"amount": "-12.5", "occurred_at": "2026-07-05T00:00:00Z",
        "components": [{"ordinal": 0, "account_id": "101", "amount": "-12.5", "currency": "CNY"}]}
    with draft_runtime.sessions() as session:
        operation, = _prepare(session, draft_runtime, (file,), selections=_selections((file,)),
            choices=[{"row_ref": "file_a:r1", "action": "apply", "patch": patch}]).operations
        assert operation["planned_action"] == "create_posted" and not operation["issues"]


def test_existing_complete_draft_uses_current_account_eligibility_when_finalizing(draft_runtime):
    file = _file()
    with draft_runtime.sessions.begin() as session:
        _bind_existing(session, draft_runtime, file)
        session.get(CashTransactionModel, 1003).state = "draft"
        session.get(AccountModel, 101).active = False
    with draft_runtime.sessions() as session:
        operation, = _prepare(session, draft_runtime, (file,)).operations
        assert operation["values"]["state"] == "draft"
        assert any(issue["code"] == "account_unavailable" for issue in operation["issues"])


def test_unknown_patch_field_rejects_plan_without_leaking_input(draft_runtime):
    files = (_file(), _file(letter="b", provider="002"))
    with draft_runtime.sessions() as session:
        with pytest.raises(CashImportPlanError) as error:
            _prepare(session, draft_runtime, files, selections=_selections(files), choices=[
                {"row_ref": "file_a:r1", "action": "apply", "patch": {"bad field SQL": True}}])
        assert str(error.value) == "plan_input_invalid"


def test_unidentified_equivalent_repetitions_do_not_create_extra_operations(draft_runtime):
    first = _file(provider=None)
    coverage = SourceCoverage(start="2026-07-01T00:00:00Z", end="2026-08-01T00:00:00Z", complete=True, evidence="fixture")
    rows = (first.document.rows[0], replace(first.document.rows[0], position=2))
    first = replace(first, document=replace(first.document, rows=rows, coverage=coverage,
        declared_count=2, observed_count=2))
    second = replace(first, file_ref="file_b", client_file_ref="client_b", digest="b" * 64)
    with draft_runtime.sessions() as session:
        prepared = _prepare(session, draft_runtime, (first, second), selections=_selections((first, second)))
        assert len(prepared.operations) == 2
        assert [row["planned_action"] for row in prepared.rows] == ["create_posted", "create_posted",
            "duplicate_in_batch", "duplicate_in_batch"]


def test_account_rename_does_not_invalidate_current_valid_mapping(draft_runtime):
    file = _file()
    with draft_runtime.sessions.begin() as session:
        session.get(AccountModel, 101).name = "最新账户名"
    with draft_runtime.sessions() as session:
        operation, = _prepare(session, draft_runtime, (file,), selections=_selections((file,))).operations
        assert operation["values"]["state"] == "posted" and not operation["issues"]


def test_new_record_cannot_claim_an_existing_component_identifier(draft_runtime):
    file = _file()
    with draft_runtime.sessions() as session:
        with pytest.raises(CashImportPlanError, match="plan_input_invalid"):
            _prepare(session, draft_runtime, (file,), selections=_selections((file,)), choices=[
                {"row_ref": "file_a:r1", "action": "apply", "patch": {"components": [{"component_id": "1001",
                    "ordinal": 0, "account_id": "101", "amount": "-12.5", "currency": "CNY"}]}}])


def test_existing_draft_funding_refresh_preserves_current_component_identity(draft_runtime):
    file = _file()
    with draft_runtime.sessions.begin() as session:
        _bind_existing(session, draft_runtime, file)
        session.get(CashTransactionModel, 1003).state = "draft"
    with draft_runtime.sessions() as session:
        operation, = _prepare(session, draft_runtime, (file,), selections=_selections((file,))).operations
        assert operation["planned_action"] == "finalize_draft"
        assert operation["values"]["components"][0]["component_id"] == "1003"


@pytest.mark.parametrize("components", [
    [{"component_id": "1001", "ordinal": 0, "account_id": "101", "amount": "-12.5", "currency": "CNY"}],
    [{"component_id": "1003", "ordinal": 0, "account_id": "101", "amount": "-6.25", "currency": "CNY"},
     {"component_id": "1003", "ordinal": 1, "account_id": "101", "amount": "-6.25", "currency": "CNY"}],
])
def test_explicit_draft_component_ids_must_be_owned_and_unique(draft_runtime, components):
    file = _file()
    with draft_runtime.sessions.begin() as session:
        _bind_existing(session, draft_runtime, file)
        session.get(CashTransactionModel, 1003).state = "draft"
    with draft_runtime.sessions() as session:
        with pytest.raises(CashImportPlanError, match="plan_input_invalid"):
            _prepare(session, draft_runtime, (file,), choices=[{"row_ref": "file_a:r1", "action": "apply",
                "patch": {"components": components}}])


def test_typed_preparation_keeps_default_wire_result_and_input_unchanged(draft_runtime):
    from copy import deepcopy
    from ft.application.cash_import_operations import CashImportOperationPlanner, _wire
    file = _file()
    before = deepcopy(file)
    identity = {row.occurrence_key: 'synthetic-record' for row in source_batch_rows((file,)).values()}
    arguments = {'account_selections': _selections((file,)), 'record_ids': identity}
    with draft_runtime.sessions() as session:
        planner = CashImportOperationPlanner(session, draft_runtime.workspace_id)
        wire = planner.prepare((file,), **arguments)
        typed = planner.prepare((file,), **arguments, wire_values=False)
    assert _wire(typed.operations) == list(wire.operations)
    assert typed.operations[0]['values']['amount'] == Decimal('-12.5')
    assert file == before
