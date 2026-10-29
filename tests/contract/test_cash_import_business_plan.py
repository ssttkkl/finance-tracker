"""实际业务计划返回预计流水列表；来源行和关系不成为并列审核流程。"""
from dataclasses import replace
from decimal import Decimal

import pytest
from sqlalchemy import func, select, update

from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.models import AccountModel, CashTransactionComponentModel, CashTransactionModel, TransactionRelationModel
from tests.contract.test_cash_draft_storage import draft_runtime
from tests.contract.test_cash_import_operations import _as_source, _bind_existing, _file, _selections


@pytest.fixture(autouse=True)
def _currencies(draft_runtime):
    with draft_runtime.sessions.begin() as session:
        session.execute(update(AccountModel).where(AccountModel.workspace_id == draft_runtime.workspace_id,
            AccountModel.id.in_((101, 102))).values(currencies=["CNY"]))


def _mirror_files():
    first = _file()
    second = _file(letter="b")
    second = _as_source(second, "icbc_debit")
    return first, second


def _build(session, runtime, files, *, mapped=True, rows=None, relations=None, previous=None):
    from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
    return CashImportBusinessPlanBuilder(runtime.workspace_id)(session=session, files=tuple(files),
        preview_input={"account_selections": _selections(files) if mapped else [],
            "row_choices": rows or [], "relation_choices": relations or []}, previous_plan=previous,
        plan_id="synthetic-current-plan")


def _existing_mirror(session, runtime):
    file = _file()
    _bind_existing(session, runtime, file)
    parent, part = session.get(CashTransactionModel, 1001), session.get(CashTransactionComponentModel, 1001)
    source = session.get(CashTransactionModel, 1003)
    parent.source_type, parent.amount, parent.occurred_at = "icbc_debit", source.amount, source.occurred_at
    parent.record_type, parent.record_subtype, part.amount = "consumption", "not_applicable", source.amount
    session.flush()
    relation = CurrentCashRelationRepository(session, runtime.workspace_id).add({"kind": "payment_mirror", "state": "confirmed",
        "primary_transaction_id": 1003, "secondary_transaction_id": 1001, "primary_component_id": 1003,
        "secondary_component_id": 1001, "anchor_transaction_id": 1003, "anchor_component_id": 1003,
        "primary_role": "expense", "secondary_role": "expense", "applied_amount": "12.5"})
    return file, relation["id"]


def test_missing_alipay_funding_uses_wallet_in_business_plan(draft_runtime):
    original = _file()
    row = original.document.rows[0]
    fields = {**row.fields, "source_payload": {**row.fields["source_payload"], "商品说明": ""}}
    file = replace(original, document=replace(original.document, rows=(replace(row, payment_method="", fields=fields),)))
    with draft_runtime.sessions() as session:
        plan = _build(session, draft_runtime, (file,))
    operation, = plan["operations"]
    item, = plan["items"]
    assert operation["planned_action"] == "create_posted"
    assert operation["values"]["account_id"] == "101"
    assert "funding_unrecognized" not in {issue["code"] for issue in operation["issues"]}
    assert item["anticipated_state"] == "posted" and item["statistics_eligible"] is True


def test_business_plan_new_and_draft_items_are_real_read_only_results(draft_runtime):
    file = _file()
    with draft_runtime.sessions() as session:
        count = session.scalar(select(func.count()).select_from(CashTransactionModel))
        posted = _build(session, draft_runtime, (file,))
        draft = _build(session, draft_runtime, (file,), mapped=False)
        assert posted["can_commit"] is True and len(posted["items"]) == 1
        assert posted["items"][0]["display"]["amount"] == "-12.5"
        assert posted["items"][0]["confirmation_status"] == "confirmed"
        assert posted["items"][0]["statistics_eligible"] is True
        assert draft["items"][0]["confirmation_status"] == "pending"
        assert draft["items"][0]["anticipated_state"] == "draft"
        assert draft["items"][0]["statistics_eligible"] is False
        assert posted["summary"]["create_posted"] == 1 and draft["summary"]["create_draft"] == 1
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == count
        assert not session.new and not session.dirty


def test_keep_existing_is_offered_only_for_a_saved_record(draft_runtime):
    from ft.application.cash_import_plans import _resolve_detail
    file = _file()
    with draft_runtime.sessions() as session:
        created = _build(session, draft_runtime, (file,))
    item, = created["items"]
    detail = _resolve_detail(created, created["item_details"][item["preview_item_id"]])
    assert "keep_existing" not in detail["members"][0]["allowed_actions"]
    with draft_runtime.sessions.begin() as session:
        _bind_existing(session, draft_runtime, file)
    changed = _file(note="来源更新备注")
    with draft_runtime.sessions() as session:
        existing = _build(session, draft_runtime, (changed,))
    item, = existing["items"]
    detail = _resolve_detail(existing, existing["item_details"][item["preview_item_id"]])
    assert "keep_existing" in detail["members"][0]["allowed_actions"]


def test_frozen_preview_stores_member_values_once_and_restores_only_requested_detail(draft_runtime):
    import json
    from ft.application.cash_import_plans import _resolve_detail
    evidence = "synthetic-unique-evidence-" + "x" * 10000
    file = _file()
    row = file.document.rows[0]
    file = replace(file, document=replace(file.document, rows=(replace(row,
        fields={**row.fields, "source_payload": {"synthetic": evidence}}),)))
    with draft_runtime.sessions() as session:
        plan = _build(session, draft_runtime, (file,))
    assert json.dumps(plan).count(evidence) == 1
    item, = plan["items"]
    detail = _resolve_detail(plan, plan["item_details"][item["preview_item_id"]])
    assert detail["members"][0]["fields"]["source_payload"]["synthetic"] == evidence
    detail["members"][0]["fields"]["source_payload"]["synthetic"] = "local change"
    again = _resolve_detail(plan, plan["item_details"][item["preview_item_id"]])
    assert again["members"][0]["fields"]["source_payload"]["synthetic"] == evidence


@pytest.mark.parametrize("stored", [True, False])
def test_wechat_refund_number_evidence_is_global_across_session_nicknames(draft_runtime, stored):
    file = _file(provider="wechat-order")
    expense = replace(file, source_type="wechat",
        document=replace(file.document, source_type="wechat", rows=(replace(file.document.rows[0],
            source_explanation={"txn_id": "wechat-order", "merchant_order_id": "merchant-key"}),), source_nickname="昵称甲"))
    file = _file(letter="b", amount="5", provider="merchant-key")
    row = file.document.rows[0]
    refund = replace(file, source_type="wechat",
        document=replace(file.document, source_type="wechat", rows=(replace(row,
            fields={**row.fields, "record_type": "refund"}, source_explanation={"txn_id": "merchant-key",
                "txn_type": "商户消费-退款", "status": "退款成功", "direction": "收入"}),), source_nickname="昵称乙"))
    if stored:
        with draft_runtime.sessions.begin() as session:
            _bind_existing(session, draft_runtime, expense)
            session.get(CashTransactionModel, 1003).relation_metadata = expense.document.rows[0].source_explanation
    with draft_runtime.sessions() as session:
        plan = _build(session, draft_runtime, (refund,) if stored else (expense, refund))
    accepted = [group for group in plan["relation_groups"] if group["action"] == "accept"]
    assert accepted
    assert len(plan["items"]) == 1 and Decimal(plan["items"][0]["display"]["amount"]) == Decimal("-7.5")


@pytest.mark.parametrize("same_counterparty", [True, False])
def test_equal_sized_mirror_occurrences_without_unique_evidence_are_deferred(draft_runtime, same_counterparty):
    files = []
    for letter, source, provider in (("a", "alipay", "platform-a"), ("b", "alipay", "platform-b"),
        ("c", "ccb_debit", None), ("d", "ccb_debit", None)):
        file = _file(letter=letter, provider=provider, note=f"实际出现{letter}",
            name="合成商户" if same_counterparty or source == "alipay" else f"银行摘要{letter}")
        files.append(_as_source(file, source) if source == "ccb_debit" else
            replace(file, source_type=source, document=replace(file.document, source_type=source)))
    bank = files[2]
    # 同一账单内的两次出现已能确定是两笔，排除跨文件身份草稿对配对测试的干扰。
    bank = replace(bank, document=replace(bank.document, rows=(bank.document.rows[0],
        replace(files[3].document.rows[0], position=2)), declared_count=2, observed_count=2))
    files = (*files[:2], bank)
    with draft_runtime.sessions() as session:
        plan = _build(session, draft_runtime, files)
        assert len(plan["operations"]) == 4 and len(plan["items"]) == 4
        assert all(operation["planned_action"] == "create_posted" for operation in plan["operations"])
        assert plan["relation_failures"] == []
        assert plan["relation_groups"] and all(group["action"] == "defer" for group in plan["relation_groups"])
        assert all(item["confirmation_status"] == "pending" for item in plan["items"])


def test_truncated_mirror_candidates_keep_their_explicit_anchor_and_remain_selectable(draft_runtime):
    from datetime import datetime, timezone
    from ft.adapters.relational.cash_sources import CashSourceRepository
    from ft.application.cash_import_source_rows import source_batch_rows
    with draft_runtime.sessions.begin() as session:
        bank = session.get(CashTransactionModel, 1003)
        bank.source_type, bank.counterparty = "ccb_debit", "银行摘要"
        bank.record_type, bank.record_subtype = "consumption", "not_applicable"
        when = datetime(2026, 7, 5, tzinfo=timezone.utc)
        bank.occurred_at = when
        for index in range(20):
            identifier = 2001 + index
            session.add(CashTransactionModel(id=identifier, workspace_id=draft_runtime.workspace_id, account_id=101,
                amount=Decimal("-12.5"), currency="CNY", occurred_at=when, record_type="consumption",
                record_subtype="not_applicable", source_type="wechat", record_id=f"candidate-{index:03d}", counterparty="合成商户"))
            session.flush()
            session.add(CashTransactionComponentModel(id=identifier, workspace_id=draft_runtime.workspace_id,
                cash_transaction_id=identifier, account_id=101, amount=Decimal("-12.5"), currency="CNY", ordinal=0))
            old_file = _file(provider=f"candidate-{index:03d}", letter=f"{index:02x}")
            old_file = replace(old_file, source_type="wechat", digest=f"{index + 1:064x}",
                document=replace(old_file.document, source_type="wechat"))
            CashSourceRepository(session, draft_runtime.workspace_id).save_occurrence(identifier,
                next(iter(source_batch_rows((old_file,)).values())))
    file = _file(provider="zz-last")
    file = replace(file, source_type="wechat", document=replace(file.document, source_type="wechat"))
    with draft_runtime.sessions() as session:
        plan = _build(session, draft_runtime, (file,))
    assert plan["operations"][0]["planned_action"] == "create_posted", plan["operations"][0]["issues"]
    assert plan["relation_groups"], (plan["summary"], plan["relation_failures"])
    group, = plan["relation_groups"]
    assert group["action"] == "defer"
    assert any({"transaction_id": "2001"} in candidate["member_refs"] for candidate in group["candidates"])


def test_confirmed_mirror_is_one_projected_preview_item_before_any_persistence(draft_runtime):
    files = _mirror_files()
    with draft_runtime.sessions() as session:
        plan = _build(session, draft_runtime, files)
        item, = plan["items"]
        assert item["display"]["amount"] == "-12.5" and item["confirmation_status"] == "confirmed"
        assert {tuple(ref.items()) for ref in item["member_refs"]} == {(("row_ref", "file_a:r1"),), (("row_ref", "file_b:r1"),)}
        assert plan["summary"]["create_posted"] == 2 and plan["summary"]["relations_accepted"] == 1
        assert len(plan["relation_groups"]) == 1
        assert session.scalar(select(func.count()).select_from(TransactionRelationModel)) == 0


def test_deferred_mirror_keeps_two_formal_items_and_independent_statistics(draft_runtime):
    files = _mirror_files()
    with draft_runtime.sessions() as session:
        first = _build(session, draft_runtime, files)
        suggestion = first["relation_groups"][0]["suggestion_id"]
        plan = _build(session, draft_runtime, files, previous=first,
            relations=[{"suggestion_id": suggestion, "action": "defer"}])
        assert len(plan["items"]) == 2 and plan["summary"]["relations_deferred"] == 1
        assert all(item["confirmation_status"] == "pending" and item["statistics_eligible"]
            and item["anticipated_state"] == "posted" for item in plan["items"])
        assert sum(Decimal(item["display"]["amount"]) for item in plan["items"]) == Decimal("-25")
        assert all(any(issue["code"] == "relation_pending" for issue in item["attention_reasons"]) for item in plan["items"])


def test_ordinary_source_update_shows_existing_projection_without_rediscovery(draft_runtime, monkeypatch):
    from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
    with draft_runtime.sessions.begin() as session:
        _existing_mirror(session, draft_runtime)
    def unexpected(*args, **kwargs):
        pytest.fail("ordinary update started relation discovery")
    monkeypatch.setattr(CashImportBusinessPlanBuilder, "_discover", unexpected)
    changed = _file(letter="c", name="来源新名称")
    with draft_runtime.sessions() as session:
        plan = _build(session, draft_runtime, (changed,), mapped=False)
        item, = plan["items"]
        assert item["display"]["counterparty"] == "来源新名称" and Decimal(item["display"]["amount"]) == Decimal("-12.5")
        assert {tuple(ref.items()) for ref in item["member_refs"]} == {(("row_ref", "file_c:r1"),), (("transaction_id", "1001"),)}
        assert plan["summary"]["update_posted"] == 1 and plan["summary"].get("create_posted", 0) == 0
        assert not session.new and not session.dirty


def test_type_edit_rejects_plan_without_removing_existing_relation(draft_runtime):
    from ft.application.cash_import_plans import CashImportPlanError
    with draft_runtime.sessions.begin() as session:
        file, relation_id = _existing_mirror(session, draft_runtime)
    with draft_runtime.sessions() as session:
        with pytest.raises(CashImportPlanError, match="plan_input_invalid"):
            _build(session, draft_runtime, (file,), mapped=False, rows=[{"row_ref": "file_a:r1", "action": "apply",
                "patch": {"record_type": "transfer_out", "record_subtype": "ordinary_transfer"}}])
        assert session.get(TransactionRelationModel, relation_id).active is True
        assert session.get(CashTransactionModel, 1003).record_type == "consumption"


def test_conflict_and_unreadable_rows_are_processing_details_without_fake_items(draft_runtime):
    from ft.adapters.cash_statement_rows import ParsedCashRow
    file = _file()
    with draft_runtime.sessions.begin() as session:
        _bind_existing(session, draft_runtime, file)
    bad = _file(letter="b", amount="-99")
    good = _file(letter="c", provider="002")
    unreadable = _file(letter="d", rows=(ParsedCashRow(1, None, disposition="skipped", error_code="row_unreadable"),))
    with draft_runtime.sessions() as session:
        plan = _build(session, draft_runtime, (bad, good, unreadable))
        assert len(plan["items"]) == 1 and plan["summary"]["skip"] == 2
        assert {row["code"] for row in plan["processing_details"] if row.get("code")} == {"fixed_field_conflict", "row_unreadable"}


def test_relation_discovery_failure_stops_plan_without_recursive_retry(draft_runtime, monkeypatch):
    import ft.application.cash_import_business_plan as module
    def broken(*args, **kwargs):
        raise RuntimeError("SQL private bill body")
    monkeypatch.setattr(module, "plan_relation_proposals", broken)
    files = _mirror_files()
    with draft_runtime.sessions() as session:
        with pytest.raises(RuntimeError):
            _build(session, draft_runtime, files)


def test_unidentified_record_id_is_reused_when_only_relation_choice_changes(draft_runtime):
    files = tuple(file if file.source_type == "alipay" else replace(file, document=replace(file.document,
        rows=tuple(replace(row, provider_id=None) for row in file.document.rows))) for file in _mirror_files())
    with draft_runtime.sessions() as session:
        first = _build(session, draft_runtime, files)
        suggestion = first["relation_groups"][0]["suggestion_id"]
        second = _build(session, draft_runtime, files, previous=first,
            relations=[{"suggestion_id": suggestion, "action": "defer"}])
        assert [op["values"]["record_id"] for op in first["operations"]] == [op["values"]["record_id"] for op in second["operations"]]


def test_duplicate_summary_counts_rows_once_and_all_conflicts_can_finish_with_results(draft_runtime):
    file = _file()
    with draft_runtime.sessions.begin() as session:
        _bind_existing(session, draft_runtime, file)
    with draft_runtime.sessions() as session:
        duplicate = _build(session, draft_runtime, (file,), mapped=False)
        assert duplicate["summary"]["duplicate"] == 1 and duplicate["can_commit"] is True
        assert duplicate["items"] == [] and len(duplicate["processing_details"]) == 1
        conflicted = _build(session, draft_runtime, (_file(letter="b", amount="-99"),), mapped=False)
        assert conflicted["can_commit"] is True and conflicted["summary"]["skip"] == 1
        assert conflicted["items"] == []


def test_new_and_finalizing_records_have_financial_edit_fields_before_commit(draft_runtime):
    with draft_runtime.sessions() as session:
        plan = _build(session, draft_runtime, (_file(),))
        member, = next(iter(plan["item_details"].values()))["members"]
        assert {"account_id", "amount", "currency", "components", "occurred_at"} <= set(member["editable_fields"])


def test_actual_builder_can_publish_read_and_repreview_through_session_service(draft_runtime):
    from ft.adapters.relational.models import UserModel, WorkspaceMembershipModel
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
    from ft.application.cash_import_context import CashImportUpload
    from ft.application.cash_import_plans import CashImportPlanService
    from ft.application.parsed_import_staging import InMemoryParsedImportStore
    from ft.application.cash_import_funding import scan_cash_funding
    user = "00000000-0000-4000-8000-000000000001"
    with draft_runtime.sessions.begin() as session:
        session.add(UserModel(id=user, email="actual-plan@example.test", password_hash="synthetic"))
        session.flush()
        session.add(WorkspaceMembershipModel(user_id=user, workspace_id=draft_runtime.workspace_id, role="editor"))
    documents = {"first": _mirror_files()[0].document, "second": _mirror_files()[1].document}
    store = InMemoryParsedImportStore()
    service = CashImportPlanService(RelationalUnitOfWork(draft_runtime.sessions, draft_runtime.workspace_id), store,
        user_id=user, parser=lambda upload: documents[upload.client_file_ref], builder=CashImportBusinessPlanBuilder(draft_runtime.workspace_id))
    created = service.create((CashImportUpload("first", "one.csv", b"first"), CashImportUpload("second", "two.csv", b"second")))
    selections = [{"source_account_ref": group.source_account_ref, "account_id": "101", "expected_account_revision": 1}
        for group in scan_cash_funding(created.files).groups]
    plan = service.preview(created.session_id, expected_session_revision=1, account_selections=selections)
    assert len(plan["items"]) == 1 and "operations" not in plan and "relation_groups" not in plan
    detail = service.item(plan["plan_id"], plan["items"][0]["preview_item_id"])
    assert len(detail["members"]) == 2 and len(detail["relation_choices"]) == 1
    assert all(not part.get("component_id") for member in detail["members"] for part in member["fields"]["components"])
    suggestion = detail["relation_choices"][0]["suggestion_id"]
    second = service.preview(created.session_id, expected_session_revision=2, previous_plan_id=plan["plan_id"],
        account_selections=selections, relation_choices=[{"suggestion_id": suggestion, "action": "defer"}])
    assert len(second["items"]) == 2 and all(item["confirmation_status"] == "pending" for item in second["items"])


@pytest.mark.parametrize("refund,expected", [("5", "-7.5"), ("12.5", "0")])
def test_refund_and_mirror_bundle_is_projected_and_deferred_as_one_complete_decision(draft_runtime, refund, expected):
    a, b = _mirror_files()
    a = replace(a, document=replace(a.document, rows=(replace(a.document.rows[0], source_explanation={"txn_id": "order1"}),)))
    c = _file(letter="c", amount=refund, provider="003")
    c = replace(c, document=replace(c.document, rows=(replace(c.document.rows[0], fields={**c.document.rows[0].fields,
        "record_type": "refund"}, source_explanation={"txn_id": "order1_refund", "_is_refund": True}),)))
    d = replace(c, file_ref="file_d", client_file_ref="client_d", digest="d" * 64, source_type="icbc_debit",
        document=replace(c.document, source_type="icbc_debit", rows=(replace(c.document.rows[0], provider_id="004",
            source_explanation={"_is_refund": True, "_icbc_refund_merchant_trusted": True}),)))
    d = _as_source(d, "icbc_debit")
    files = a, b, c, d
    with draft_runtime.sessions() as session:
        first = _build(session, draft_runtime, files)
        assert len(first["items"]) == 1 and Decimal(first["items"][0]["display"]["amount"]) == Decimal(expected)
        assert first["items"][0]["confirmation_status"] == "confirmed"
        group, = first["relation_groups"]
        assert len(group["edges"]) >= 3 and len(group["member_refs"]) == 4
        deferred = _build(session, draft_runtime, files, previous=first,
            relations=[{"suggestion_id": group["suggestion_id"], "action": "defer"}])
        assert len(deferred["items"]) == 4
        assert all(item["confirmation_status"] == "pending" and item["statistics_eligible"] for item in deferred["items"])
        assert deferred["summary"]["relations_deferred"] == 1


def test_independent_relation_groups_validate_their_own_closure_not_the_entire_import(draft_runtime, monkeypatch):
    import ft.application.cash_import_business_plan as module
    counts = []
    original = module.build_cash_relation_graph
    def measured(facts, *args, **kwargs):
        facts = tuple(facts)
        counts.append(len(facts))
        return original(facts, *args, **kwargs)
    monkeypatch.setattr(module, "build_cash_relation_graph", measured)
    a, b = _mirror_files()
    files = []
    for file in (a, b):
        rows = tuple(replace(file.document.rows[0], position=index + 1, provider_id=str(index + 1),
            fields={**file.document.rows[0].fields, "amount": str(-index - 1), "source_payload": {"交易号": str(index + 1)}})
            for index in range(20))
        files.append(replace(file, document=replace(file.document, rows=rows, declared_count=20, observed_count=20)))
    with draft_runtime.sessions() as session:
        plan = _build(session, draft_runtime, files)
        assert len(plan["items"]) == 20 and len(plan["relation_groups"]) == 20
        assert counts[-1] >= 40 and sum(count > 2 for count in counts) == 1
        assert all(count <= 2 for count in counts[:-1])


def _open_transfer(session, runtime, monkeypatch):
    from types import SimpleNamespace
    from ft.domain.relations import RelationEvidence, RelationProposal
    import ft.application.cash_import_business_plan as module
    for identifier in (1001, 1002):
        parent, part = session.get(CashTransactionModel, identifier), session.get(CashTransactionComponentModel, identifier)
        parent.amount, part.amount, parent.account_id, part.account_id = Decimal("12.5"), Decimal("12.5"), 102, 102
        parent.record_type, parent.record_subtype = "transfer_in", "ordinary_transfer"
    session.flush()
    file = _file(provider="999")
    row = file.document.rows[0]
    file = replace(file, source_type="wechat", document=replace(file.document, source_type="wechat",
        rows=(replace(row, fields={**row.fields, "record_type": "transfer_out", "record_subtype": "ordinary_transfer"}),)))
    def discover(facts, **kwargs):
        anchor = next(fact for fact in facts if fact.record_type == "transfer_out")
        return SimpleNamespace(proposals=(RelationProposal("transfer_pair", anchor.id, None, subtype="ordinary_transfer",
            evidence=RelationEvidence(open_leg=True, anchor_role="outgoing", candidate_fact_ids=("1001", "1002"))),))
    monkeypatch.setattr(module, "plan_relation_proposals", discover)
    return file


def test_open_relation_candidate_is_selected_from_previous_plan_and_confirms_whole_decision(draft_runtime, monkeypatch):
    with draft_runtime.sessions.begin() as session:
        file = _open_transfer(session, draft_runtime, monkeypatch)
    with draft_runtime.sessions() as session:
        first = _build(session, draft_runtime, (file,))
        group, = first["relation_groups"]
        assert len(group["candidates"]) == 2 and group["action"] == "defer"
        chosen = next(candidate for candidate in group["candidates"] if {"transaction_id": "1002"} in candidate["member_refs"])
        second = _build(session, draft_runtime, (file,), previous=first,
            relations=[{"suggestion_id": group["suggestion_id"], "action": "accept", "candidate_id": chosen["candidate_id"]}])
        item, = second["items"]
        assert item["confirmation_status"] == "confirmed" and Decimal(item["display"]["amount"]) == 0
        assert {"transaction_id": "1002"} in item["member_refs"]
        assert session.scalar(select(func.count()).select_from(TransactionRelationModel)) == 0


def test_candidate_change_invalidates_old_choice_and_never_silently_selects_another(draft_runtime, monkeypatch):
    with draft_runtime.sessions.begin() as session:
        file = _open_transfer(session, draft_runtime, monkeypatch)
    with draft_runtime.sessions() as session:
        first = _build(session, draft_runtime, (file,))
    group, = first["relation_groups"]
    choice = {"suggestion_id": group["suggestion_id"], "action": "accept", "candidate_id": group["candidates"][0]["candidate_id"]}
    with draft_runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1001).note = "用户尚未看过的新状态"
    with draft_runtime.sessions() as session:
        second = _build(session, draft_runtime, (file,), previous=first, relations=[choice])
        assert second["invalidated_choices"] == [{"kind": "relation", "reference": choice["suggestion_id"], "code": "relation_context_changed"}]
        assert all(group["action"] != "accept" for group in second["relation_groups"])


def test_relation_choice_cannot_inject_a_candidate_from_outside_previous_plan(draft_runtime, monkeypatch):
    from ft.application.cash_import_plans import CashImportPlanError
    with draft_runtime.sessions.begin() as session:
        file = _open_transfer(session, draft_runtime, monkeypatch)
    with draft_runtime.sessions() as session:
        first = _build(session, draft_runtime, (file,))
        with pytest.raises(CashImportPlanError, match="plan_input_invalid"):
            _build(session, draft_runtime, (file,), previous=first,
                relations=[{"suggestion_id": first["relation_groups"][0]["suggestion_id"], "action": "accept", "candidate_id": "foreign"}])


@pytest.mark.parametrize("source,explanation,incoming_explanation", [
    ("alipay", {"txn_id": "long-order"}, {"txn_id": "long-order_refund"}),
    ("wechat", {"txn_id": "wechat-order", "merchant_order_id": "merchant-key"},
        {"txn_id": "merchant-key", "txn_type": "商户消费-退款", "status": "退款成功", "direction": "收入"}),
])
def test_reliable_refund_keys_can_find_older_records_outside_text_candidate_window(draft_runtime, source, explanation, incoming_explanation):
    file = _file(provider=explanation["txn_id"])
    row = replace(file.document.rows[0], fields={**file.document.rows[0].fields, "occurred_at": "2025-01-01T00:00:00Z"},
        source_explanation=explanation)
    old = replace(file, source_type=source, document=replace(file.document, source_type=source, rows=(row,)))
    with draft_runtime.sessions.begin() as session:
        _bind_existing(session, draft_runtime, old)
        session.get(CashTransactionModel, 1003).relation_metadata = explanation
    incoming = _file(letter="b", amount="5", provider=incoming_explanation["txn_id"])
    row = replace(incoming.document.rows[0], fields={**incoming.document.rows[0].fields, "record_type": "refund"},
        source_explanation=incoming_explanation)
    incoming = replace(incoming, source_type=source, document=replace(incoming.document, source_type=source, rows=(row,)))
    with draft_runtime.sessions() as session:
        plan = _build(session, draft_runtime, (incoming,))
        item, = plan["items"]
        assert Decimal(item["display"]["amount"]) == Decimal("-7.5") and item["confirmation_status"] == "confirmed"
        assert {"transaction_id": "1003"} in item["member_refs"]


def test_choices_that_compete_for_one_endpoint_fail_together_without_keeping_first_pair(draft_runtime, monkeypatch):
    from types import SimpleNamespace
    from ft.domain.relations import RelationEvidence, RelationProposal
    import ft.application.cash_import_business_plan as module
    with draft_runtime.sessions.begin() as session:
        first = _open_transfer(session, draft_runtime, monkeypatch)
    second = replace(first, file_ref="file_b", client_file_ref="client_b", digest="b" * 64,
        document=replace(first.document, rows=(replace(first.document.rows[0], provider_id="another-transfer",
            fields={**first.document.rows[0].fields, "source_payload": {"交易号": "another-transfer"}}),)))
    def discover(facts, **kwargs):
        return SimpleNamespace(proposals=tuple(RelationProposal("transfer_pair", fact.id, None, subtype="ordinary_transfer",
            evidence=RelationEvidence(open_leg=True, anchor_role="outgoing", candidate_fact_ids=("1001", "1002")))
            for fact in facts if fact.record_type == "transfer_out"))
    monkeypatch.setattr(module, "plan_relation_proposals", discover)
    with draft_runtime.sessions() as session:
        initial = _build(session, draft_runtime, (first, second))
        assert len(initial["relation_groups"]) == 2
        choices = [{"suggestion_id": group["suggestion_id"], "action": "accept",
            "candidate_id": next(candidate["candidate_id"] for candidate in group["candidates"]
                if {"transaction_id": "1001"} in candidate["member_refs"])} for group in initial["relation_groups"]]
        conflicted = _build(session, draft_runtime, (first, second), previous=initial, relations=choices)
        assert all(group["action"] == "skip" for group in conflicted["relation_groups"])
        assert all(len(item["member_refs"]) == 1 for item in conflicted["items"])
        assert len({group["commit_group_id"] for group in conflicted["relation_groups"]}) == 1
        assert len(conflicted["operations"]) == 2


def test_one_invalid_relation_reports_reason_while_independent_valid_pair_is_kept(draft_runtime, monkeypatch):
    from types import SimpleNamespace
    from ft.domain.relations import RelationProposal
    import ft.application.cash_import_business_plan as module
    a, b = _mirror_files()
    files = a, b, _file(letter="c", amount="-7", provider="003"), _as_source(
        _file(letter="d", amount="-7", provider="004"), "icbc_debit")
    def discover(facts, **kwargs):
        by_amount = {amount: sorted((fact for fact in facts if fact.amount == amount and int(fact.parent_id) >= 2 ** 63), key=lambda fact: fact.bill_source)
            for amount in (Decimal("-12.5"), Decimal("-7"))}
        good, bad = by_amount[Decimal("-12.5")], by_amount[Decimal("-7")]
        return SimpleNamespace(proposals=(RelationProposal("payment_mirror", good[0].id, good[1].id,
            status="accepted", confidence="strong"), RelationProposal("transfer_pair", bad[0].id, bad[1].id,
            subtype="ordinary_transfer", status="accepted", confidence="strong")))
    monkeypatch.setattr(module, "plan_relation_proposals", discover)
    with draft_runtime.sessions() as session:
        plan = _build(session, draft_runtime, files)
        assert len(plan["operations"]) == 4 and len(plan["items"]) == 3
        assert plan["summary"]["relations_accepted"] == 1 and plan["summary"]["relations_failed"] == 1
        assert len([item for item in plan["items"] if item["confirmation_status"] == "pending"]) == 2


@pytest.mark.parametrize("action", ["accept", "ignore"])
@pytest.mark.parametrize("old_first", [True, False])
def test_explicit_current_group_choice_wins_over_retained_invalidated_choice(draft_runtime, monkeypatch, action, old_first):
    with draft_runtime.sessions.begin() as session:
        file = _open_transfer(session, draft_runtime, monkeypatch)
    with draft_runtime.sessions() as session:
        first = _build(session, draft_runtime, (file,))
    original, = first["relation_groups"]
    stale = {"suggestion_id": original["suggestion_id"], "action": "accept", "candidate_id": original["candidates"][0]["candidate_id"]}
    with draft_runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1001).note = "候选已变化，需要重新确认"
    with draft_runtime.sessions() as session:
        changed = _build(session, draft_runtime, (file,), previous=first, relations=[stale])
        current, = changed["relation_groups"]
        assert current["action"] == "defer" and current["suggestion_id"] != stale["suggestion_id"]
        choice = {"suggestion_id": current["suggestion_id"], "action": action, "candidate_id": current["candidates"][0]["candidate_id"]}
        selected = _build(session, draft_runtime, (file,), previous=changed,
            relations=[stale, choice] if old_first else [choice, stale])
        group, = selected["relation_groups"]
        assert group["action"] == action
        assert selected["invalidated_choices"]
        if action == "accept":
            assert len(selected["items"]) == 1 and selected["items"][0]["confirmation_status"] == "confirmed"
        assert session.scalar(select(func.count()).select_from(TransactionRelationModel)) == 0


def test_nonzero_choice_generation_survives_file_invalidation_and_blocks_old_fingerprint_revival(draft_runtime, monkeypatch):
    from types import SimpleNamespace
    from ft.application.cash_import_choice_context import CONTEXT_KEY, retained_choice_context
    with draft_runtime.sessions.begin() as session:
        file = _open_transfer(session, draft_runtime, monkeypatch)
    with draft_runtime.sessions() as session:
        first = _build(session, draft_runtime, (file,))
    initial = first["relation_groups"][0]
    choice0 = {"suggestion_id": initial["suggestion_id"], "action": "accept", "candidate_id": initial["candidates"][0]["candidate_id"]}
    with draft_runtime.sessions() as session:
        changed = _build(session, draft_runtime, (file,), previous=first, relations=[choice0],
            rows=[{"row_ref": "file_a:r1", "action": "apply", "patch": {"source_payload": {"备注": "第一次来源变化"}}}])
    with draft_runtime.sessions() as session:
        returned = _build(session, draft_runtime, (file,), previous=changed, relations=[choice0])
        current = returned["relation_groups"][0]
        assert current["choice_generation"] == 1 and current["action"] == "defer"
        choice1 = {"suggestion_id": current["suggestion_id"], "action": "accept", "candidate_id": current["candidates"][0]["candidate_id"]}
        selected = _build(session, draft_runtime, (file,), previous=returned, relations=[choice1])
    proof = retained_choice_context(SimpleNamespace(plan=selected, context_data={}))[CONTEXT_KEY]
    with draft_runtime.sessions() as session:
        invalidated = _build(session, draft_runtime, (file,), previous=proof, relations=[choice1],
            rows=[{"row_ref": "file_a:r1", "action": "apply", "patch": {"source_payload": {"备注": "文件变化后再次来源变化"}}}])
    with draft_runtime.sessions() as session:
        final = _build(session, draft_runtime, (file,), previous=invalidated, relations=[choice1])
        assert final["relation_groups"][0]["action"] == "defer"
        assert final["relation_groups"][0]["choice_generation"] == 2
        assert final["relation_groups"][0]["suggestion_id"] != choice1["suggestion_id"]


def test_relation_candidate_members_are_resolved_readonly_from_the_same_plan(draft_runtime):
    from ft.application.cash_import_plans import _resolve_detail, _public_detail
    first, bank = _mirror_files()
    second = _file(letter="c", provider="another-payment")
    with draft_runtime.sessions() as session:
        plan = _build(session, draft_runtime, (first, second, bank))
    pending = next(item for item in plan["items"] if item["confirmation_status"] == "pending")
    detail = _public_detail(_resolve_detail(plan, plan["item_details"][pending["preview_item_id"]]))
    def reference(member):
        return member.get("row_ref"), member.get("transaction_id")
    expected = {reference(member) for group in detail["relation_choices"]
        for choice in [group, *group["candidates"]] for member in choice["member_refs"]}
    members = {reference(member): member for member in detail["relation_members"]}
    assert expected and expected <= members.keys()
    for member in members.values():
        assert member["fields"]["amount"] == "-12.5"
        assert member["fields"]["counterparty"] == first.document.rows[0].fields["counterparty"]
        assert member["editable_fields"] == [] and member["allowed_actions"] == []
        assert "fields_ref" not in member


@pytest.mark.parametrize('source,expected', [('fixture-other', False), ('icbc_debit', True), ('alipay', True), ('   ', True)])
def test_negative_consumption_candidates_keep_bank_platform_and_unknown_source(draft_runtime, source, expected):
    from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
    from ft.application.cash_import_operations import CashImportOperationPlanner
    file = _file()
    with draft_runtime.sessions.begin() as session:
        _bind_existing(session, draft_runtime, file)
        parent = session.get(CashTransactionModel, 1003)
        parent.source_type, parent.record_origin, parent.record_type = source, 'import', 'consumption'
        session.flush()
        plan = CashImportOperationPlanner(session, draft_runtime.workspace_id).prepare((file,), account_selections=_selections((file,)), row_choices=[])
        # Force a new operation to exercise automatic candidates independently of source binding.
        operation = {**plan.operations[0], 'values': {**plan.operations[0]['values'], 'record_type': 'consumption', 'amount': '-12.5'}}
        builder = CashImportBusinessPlanBuilder(draft_runtime.workspace_id)
        assert (1003 in builder._candidate_ids(session, [operation], {})) is expected
        operation['values']['record_type'], operation['values']['amount'] = 'refund', '12.5'
        assert 1003 in builder._candidate_ids(session, [operation], {})


def test_candidate_pruning_matches_complete_discovery_with_other_source_refund(draft_runtime, monkeypatch):
    from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
    file = _file(provider='new-payment')
    with draft_runtime.sessions.begin() as session:
        _bind_existing(session, draft_runtime, _file(provider='old-payment'))
        parent = session.get(CashTransactionModel, 1003)
        parent.source_type, parent.record_origin = 'merchant-export', 'import'
        parent.record_type, parent.record_subtype, parent.amount = 'refund', 'not_applicable', Decimal('12.5')
        parent.note, parent.counterparty = '原订单退款', '合成商户'
        session.get(CashTransactionComponentModel, 1003).amount = parent.amount
        session.flush()
        pruned = _build(session, draft_runtime, (file,))
        original = CashImportBusinessPlanBuilder._candidate_ids
        monkeypatch.setattr(CashImportBusinessPlanBuilder, '_candidate_ids', lambda self, *a: original(self, *a) | {1003})
        complete = _build(session, draft_runtime, (file,), previous=pruned)
        for key in ('items', 'item_details', 'summary', 'relation_groups', 'relation_failures', 'type_effects'):
            assert pruned[key] == complete[key]
        assert pruned['relation_groups'] == []
