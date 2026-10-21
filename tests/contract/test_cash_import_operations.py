"""实际来源与资金映射共用字段规则，逐来源行得到可执行决定。"""
from dataclasses import replace
from decimal import Decimal

import pytest
from sqlalchemy import func, select, update

from ft.adapters.cash_statement_rows import ParsedCashDocument, ParsedCashRow
from ft.adapters.relational.cash_sources import CashSourceRepository
from ft.adapters.relational.models import AccountModel, CashTransactionModel
from ft.application.cash_import_funding import scan_cash_funding
from ft.application.cash_import_source_rows import source_batch_rows
from ft.application.parsed_import_staging import ParsedImportFile
from ft.domain.cash_source_matching import SourceCoverage
from tests.contract.test_cash_draft_storage import draft_runtime


@pytest.fixture(autouse=True)
def _configured_currencies(draft_runtime):
    with draft_runtime.sessions.begin() as session:
        session.execute(update(AccountModel).where(AccountModel.workspace_id == draft_runtime.workspace_id,
            AccountModel.id.in_((101, 102))).values(currencies=["CNY"]))


def _file(*, amount="-12.5", name="合成商户", note="原始备注", letter="a", provider="001", rows=None):
    fields = {"amount": amount, "currency": "CNY", "occurred_at": "2026-07-05T00:00:00Z",
        "record_type": "consumption", "record_subtype": "not_applicable", "counterparty": name,
        "counterparty_account": "", "counterparty_account_attrs": [], "note": note,
        "source_payload": {"交易号": provider, "备注": note}}
    row = ParsedCashRow(1, fields, "合成支付账户", provider)
    document = ParsedCashDocument("alipay", "table", "fixture-v1", tuple(rows) if rows is not None else (row,),
        "fixture-owner", None, SourceCoverage(), len(rows) if rows is not None else 1, len(rows) if rows is not None else 1)
    return ParsedImportFile(f"file_{letter}", f"client_{letter}", "synthetic.csv", letter * 64, 100, "alipay", "parsed", document)


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


@pytest.mark.parametrize("protected,explicit,expected", [(False, None, "人工修改的来源原文"), (True, None, "人工旧备注"), (False, "", "")])
def test_import_manual_source_patch_rederives_note_and_explanation(draft_runtime, protected, explicit, expected):
    runtime, file = draft_runtime, _file()
    with runtime.sessions.begin() as session:
        _bind_existing(session, runtime, file, note="人工旧备注" if protected else None)
    patch = {"source_payload": {"备注": "人工修改的来源原文", "交易状态": "交易成功", "交易订单号": "raw-new"}}
    if explicit is not None:
        patch["note"] = explicit
    with runtime.sessions() as session:
        prepared = _prepare(session, runtime, (file,), choices=[{"row_ref": "file_a:r1", "action": "apply", "patch": patch}])
        operation, = prepared.operations
        assert operation["values"]["note"] == expected
        assert operation["source_explanation"]["txn_id"] == "raw-new"
        assert operation["manual_fields"] == (["note"] if explicit is not None else [])
        assert session.get(CashTransactionModel, 1003).source_payload != patch["source_payload"]


def test_metadata_only_manual_source_patch_is_an_update(draft_runtime):
    runtime, file = draft_runtime, _file()
    payload = {"备注": "原始备注", "交易订单号": "current-source-id", "交易状态": "交易成功"}
    file = replace(file, document=replace(file.document, rows=(replace(file.document.rows[0],
        fields={**file.document.rows[0].fields, "source_payload": payload}),)))
    with runtime.sessions.begin() as session:
        _bind_existing(session, runtime, file)
        session.get(CashTransactionModel, 1003).relation_metadata = {}
    with runtime.sessions() as session:
        operation, = _prepare(session, runtime, (file,), choices=[{"row_ref": "file_a:r1", "action": "apply",
            "patch": {"source_payload": payload}}]).operations
        assert operation["values"] == operation["current"]
        assert operation["planned_action"] == "update_posted"
        assert operation["source_explanation"]["txn_id"] == "current-source-id"


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
        assert operation["values"]["counterparty"] == "当前商户" and operation["values"]["note"] == "人工备注"
        assert operation["values"]["account_id"] == "101"
        assert operation["values"]["source_payload"] == changed.document.rows[0].fields["source_payload"]
        assert any(diff["field"] == "note" and diff["resolution"] == "protected" for diff in operation["field_diffs"])


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
        assert operation["planned_action"] == "update_posted" and operation["manual_fields"] == ["note"]


def test_manual_note_patch_overrides_protection_and_fixed_manual_patch_is_rejected(draft_runtime):
    runtime, first = draft_runtime, _file()
    with runtime.sessions.begin() as session:
        _bind_existing(session, runtime, first, note="人工备注")
    with runtime.sessions() as session:
        cleared = _prepare(session, runtime, (first,), choices=[{"row_ref": "file_a:r1", "action": "apply", "patch": {"note": ""}}])
        assert cleared.operations[0]["values"]["note"] == "" and cleared.operations[0]["manual_fields"] == ["note"]
        rejected = _prepare(session, runtime, (first,), choices=[{"row_ref": "file_a:r1", "action": "apply", "patch": {"amount": "-100"}}])
        assert rejected.rows[0]["code"] == "fixed_field_conflict" and not rejected.operations


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


def test_bad_row_patch_or_unreadable_source_does_not_block_other_rows(draft_runtime):
    runtime = draft_runtime
    first = _file()
    good = _file(provider="002", letter="b")
    unreadable = _file(letter="c", rows=(ParsedCashRow(1, None, disposition="skipped", error_code="row_unreadable"),))
    with runtime.sessions() as session:
        prepared = _prepare(session, runtime, (first, good, unreadable), selections=_selections((first, good)),
            choices=[{"row_ref": "file_a:r1", "action": "apply", "patch": {"record_type": "invalid"}}])
        assert [row["planned_action"] for row in prepared.rows] == ["skip", "create_posted", "skip"]
        assert prepared.rows[0]["code"] == "invalid_record_type" and prepared.rows[2]["code"] == "row_unreadable"


def test_selected_different_financial_account_is_conflict_for_existing_posted_record(draft_runtime):
    runtime, first = draft_runtime, _file()
    with runtime.sessions.begin() as session:
        _bind_existing(session, runtime, first)
    changed = _file(letter="b", name="新名称")
    with runtime.sessions() as session:
        prepared = _prepare(session, runtime, (changed,), selections=_selections((changed,), account_id="102"))
        assert prepared.rows[0]["planned_action"] == "skip" and prepared.rows[0]["code"] == "fixed_field_conflict"


def test_conflicting_manual_choices_for_shared_operation_skip_all_its_rows(draft_runtime):
    files = (_file(), _file(letter="b"))
    with draft_runtime.sessions() as session:
        prepared = _prepare(session, draft_runtime, files, selections=_selections(files), choices=[
            {"row_ref": "file_a:r1", "action": "apply", "patch": {"note": "一个决定"}},
            {"row_ref": "file_b:r1", "action": "apply", "patch": {"note": "另一个决定"}}])
        assert not prepared.operations
        assert [row["code"] for row in prepared.rows] == ["batch_choice_conflict"] * 2


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


def test_bad_category_shape_is_row_failure_and_foreign_category_is_not_assignable(draft_runtime):
    files = (_file(), _file(letter="b", provider="002"))
    with draft_runtime.sessions() as session:
        prepared = _prepare(session, draft_runtime, files, selections=_selections(files), choices=[
            {"row_ref": "file_a:r1", "action": "apply", "patch": {"category_id": {"id": "wrong"}}},
            {"row_ref": "file_b:r1", "action": "apply", "patch": {"category_id": "foreign-category"}}])
        assert [row["code"] for row in prepared.rows] == ["invalid_field", "category_unavailable"]
        assert not prepared.operations


def test_plain_type_edit_is_planned_without_searching_or_writing_current_relations(draft_runtime):
    file = _file()
    with draft_runtime.sessions.begin() as session:
        _bind_existing(session, draft_runtime, file)
    with draft_runtime.sessions() as session:
        operation, = _prepare(session, draft_runtime, (file,), choices=[{"row_ref": "file_a:r1", "action": "apply",
            "patch": {"record_type": "transfer_out", "record_subtype": "ordinary_transfer"}}]).operations
        assert operation["type_changed"] is True and operation["values"]["record_type"] == "transfer_out"
        assert session.get(CashTransactionModel, 1003).record_type == "consumption"
        assert not session.new and not session.dirty


def test_unexpected_row_preparation_failure_is_safe_and_other_rows_continue(draft_runtime, monkeypatch):
    from ft.application.cash_import_operations import CashImportOperationPlanner
    original = CashImportOperationPlanner._operation

    def fail_one(self, ref, *args, **kwargs):
        if ref == "file_a:r1":
            raise RuntimeError("SQL private source body")
        return original(self, ref, *args, **kwargs)

    monkeypatch.setattr(CashImportOperationPlanner, "_operation", fail_one)
    files = (_file(), _file(letter="b", provider="002"))
    with draft_runtime.sessions() as session:
        prepared = _prepare(session, draft_runtime, files, selections=_selections(files))
        assert prepared.rows[0]["planned_action"] == "skip"
        assert "private" not in str(prepared.rows) and "SQL" not in str(prepared.rows)
        assert prepared.rows[1]["planned_action"] == "create_posted"


def test_fixed_conflict_explains_the_actual_field_in_safe_row_result(draft_runtime):
    file = _file()
    with draft_runtime.sessions.begin() as session:
        _bind_existing(session, draft_runtime, file)
    with draft_runtime.sessions() as session:
        prepared = _prepare(session, draft_runtime, (_file(amount="-20", letter="b"),))
        issue, = prepared.rows[0]["issues"]
        assert issue["field_path"] == "amount" and "金额" in issue["message"]


@pytest.mark.parametrize("account_id,active,expected", [("101", True, "create_posted"),
    ("101", False, "create_draft"), ("104", True, "create_draft")])
def test_explicit_draft_financial_completion_revalidates_account_and_clears_old_missing_reason(draft_runtime, account_id, active, expected):
    file = _file()
    with draft_runtime.sessions.begin() as session:
        session.get(AccountModel, 101).active = active
    patch = {"account_id": account_id, "components": [{"ordinal": 0, "account_id": account_id,
        "amount": "-12.5", "currency": "CNY"}]}
    with draft_runtime.sessions() as session:
        operation, = _prepare(session, draft_runtime, (file,), choices=[{"row_ref": "file_a:r1", "action": "apply", "patch": patch}]).operations
        assert operation["planned_action"] == expected
        if expected == "create_posted":
            assert not operation["issues"] and operation["expected_accounts"] == [{"account_id": "101",
                "exists": True, "active": True, "type": "cash", "currency_support": {"CNY": True}}]
        else:
            assert any(issue["code"] == "account_unavailable" for issue in operation["issues"])


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


def test_unknown_patch_field_does_not_turn_safe_error_formatting_into_batch_failure(draft_runtime):
    files = (_file(), _file(letter="b", provider="002"))
    with draft_runtime.sessions() as session:
        prepared = _prepare(session, draft_runtime, files, selections=_selections(files), choices=[
            {"row_ref": "file_a:r1", "action": "apply", "patch": {"bad field SQL": True}}])
        assert prepared.rows[0]["code"] == "field_not_editable"
        assert prepared.rows[0]["issues"][0]["field_path"] is None
        assert prepared.rows[1]["planned_action"] == "create_posted"
        assert "SQL" not in str(prepared.rows)


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
        prepared = _prepare(session, draft_runtime, (file,), selections=_selections((file,)), choices=[
            {"row_ref": "file_a:r1", "action": "apply", "patch": {"components": [{"component_id": "1001",
                "ordinal": 0, "account_id": "101", "amount": "-12.5", "currency": "CNY"}]}}])
        assert not prepared.operations and prepared.rows[0]["code"] == "component_reference_invalid"


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
        prepared = _prepare(session, draft_runtime, (file,), choices=[{"row_ref": "file_a:r1", "action": "apply",
            "patch": {"components": components}}])
        assert not prepared.operations and prepared.rows[0]["code"] == "component_reference_invalid"
