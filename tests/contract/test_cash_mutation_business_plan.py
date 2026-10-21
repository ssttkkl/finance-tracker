"""维护从当前流水建立实际预计列表，不伪造导入文件。"""
from dataclasses import replace
from decimal import Decimal
import json

import pytest
from sqlalchemy import delete, select

from ft.adapters.relational.cash_sources import CashSourceRepository
from ft.adapters.relational.models import (AccountModel, CashSourceBindingModel, CashTransactionComponentModel,
    CashTransactionIssueModel, CashTransactionModel, TransactionRelationModel)
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.cash_projections import CashProjectionService
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from tests.contract.test_cash_import_business_plan import _existing_mirror
from tests.contract.test_cash_import_commit import configured, _plan, _commit, _results
from tests.contract.test_cash_import_operations import _file, _bind_existing
from tests.contract.test_cash_record_maintenance import _draft, _complete, _service as _records
from tests.contract.test_parsed_cash_import_context import USER, context_runtime


def _service(runtime):
    from ft.application.cash_mutation_plans import CashMutationPlanService
    return CashMutationPlanService(RelationalUnitOfWork(runtime.sessions, runtime.workspace_id),
        InMemoryParsedImportStore(), user_id=USER)


def _posted(runtime):
    service, plan = _plan(runtime)
    return _results(service, _commit(service, plan), "row")[0]["transaction_id"]


def _preview(service, runtime, identifier, *, patch=None, intent="edit_record", **metadata):
    revision = _records(runtime).read(identifier)["row_revision"]
    return service.preview({"intent": intent, "transaction_id": str(identifier), "expected_revision": revision,
        "patch": patch or {}, **metadata})


def _plan_data(service, view):
    return service._staging.get(view["edit_context_id"], workspace_id=service._workspace_id, user_id=USER).plan


def _members(service, view):
    return {member["transaction_id"]: member for item in view["items"]
        for member in service.item(view["plan_id"], item["preview_item_id"])["members"]}


def test_posted_edit_on_inactive_account_is_a_read_only_real_preview_without_discovery(context_runtime, monkeypatch):
    from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
    runtime, identifier = context_runtime, _posted(context_runtime)
    before = _records(runtime).read(identifier)
    with runtime.sessions.begin() as session:
        session.get(AccountModel, int(before["fields"]["account_id"])).active = False
    def unexpected(*args, **kwargs):
        pytest.fail("ordinary posted edit started new relation discovery")
    monkeypatch.setattr(CashImportBusinessPlanBuilder, "_discover", unexpected)
    service = _service(runtime)
    view = _preview(service, runtime, identifier, patch={"note": "明确人工备注"})
    assert view["summary"]["update_posted"] == 1 and view["can_commit"]
    member = _members(service, view)[identifier]
    assert member["fields"]["note"] == "明确人工备注" and member["fields"]["state"] == "posted"
    assert "amount" not in member["editable_fields"] and "account_name" not in member["editable_fields"]
    assert _plan_data(service, view)["dependencies"]["discovery_seeds"] == []
    assert _records(runtime).read(identifier) == before


def test_formal_financial_change_is_rejected_without_publishing(context_runtime):
    runtime, identifier = context_runtime, _posted(context_runtime)
    service = _service(runtime)
    before = _records(runtime).read(identifier)
    with pytest.raises(ValueError) as error:
        _preview(service, runtime, identifier, patch={"note": "不可部分保存", "amount": "-13"})
    assert getattr(error.value, "code", None) == "fixed_field_conflict"
    assert _records(runtime).read(identifier) == before


def test_unchanged_maintenance_keeps_one_real_item_without_claiming_update(context_runtime):
    runtime, identifier = context_runtime, _posted(context_runtime)
    service = _service(runtime)
    view = _preview(service, runtime, identifier)
    assert view["summary"]["duplicate"] == 1 and view["summary"].get("update_posted", 0) == 0
    assert len(view["items"]) == 1 and view["items"][0]["member_refs"] == [{"transaction_id": identifier}]


@pytest.mark.parametrize("state", ["confirmed", "ignored"])
def test_full_refund_remains_visible_and_existing_decision_is_not_overwritten(context_runtime, state):
    runtime = context_runtime
    first, second = _file(provider="full-order"), _file(letter="b", amount="12.5", provider="full-order_refund")
    first = replace(first, document=replace(first.document, rows=(replace(first.document.rows[0], source_explanation={"txn_id": "full-order"}),)))
    second = replace(second, document=replace(second.document, rows=(replace(second.document.rows[0],
        fields={**second.document.rows[0].fields, "record_type": "refund"}, source_explanation={"txn_id": "full-order_refund"}),)))
    importer, view = _plan(runtime, (first, second))
    receipt = _commit(importer, view)
    identifier = _results(importer, receipt, "row")[0]["transaction_id"]
    with runtime.sessions.begin() as session:
        relation = session.scalar(select(TransactionRelationModel).where(TransactionRelationModel.workspace_id == runtime.workspace_id,
            TransactionRelationModel.state == "confirmed"))
        assert relation is not None
        relation_id = relation.id
        relation.state = state
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    service = _service(runtime)
    view = _preview(service, runtime, identifier)
    item, = view["items"]
    assert Decimal(item["display"]["amount"]) == (Decimal("0") if state == "confirmed" else Decimal("-12.5"))
    assert len(item["member_refs"]) == (2 if state == "confirmed" else 1)
    assert view["summary"]["relations_accepted"] == 0 and view["summary"]["relations_removed"] == 0
    with runtime.sessions() as session:
        assert session.get(TransactionRelationModel, relation_id).state == state


def test_unbound_internal_business_identifiers_do_not_become_refund_proof(context_runtime):
    runtime, original = context_runtime, _posted(context_runtime)
    importer, plan = _plan(runtime, (_file(letter="b", provider="independent-draft"),), mapped=False)
    refund = _results(importer, _commit(importer, plan, "synthetic-unbound-draft"), "row")[0]["transaction_id"]
    with runtime.sessions.begin() as session:
        for identifier, amount, kind, record_id in ((original, "-12.5", "consumption", "unsafe-order"),
            (refund, "12.5", "refund", "unsafe-order_refund")):
            row = session.get(CashTransactionModel, int(identifier))
            row.amount, row.record_type, row.record_id = Decimal(amount), kind, record_id
            row.source_payload, row.relation_metadata = {}, {}
            row.account_id, row.counterparty = 101, "相同商户"
            part = session.scalar(select(CashTransactionComponentModel).where(CashTransactionComponentModel.cash_transaction_id == int(identifier)))
            part.account_id, part.amount = 101, Decimal(amount)
            session.execute(delete(CashSourceBindingModel).where(CashSourceBindingModel.workspace_id == runtime.workspace_id,
                CashSourceBindingModel.transaction_id == int(identifier)))
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    service = _service(runtime)
    view = _preview(service, runtime, refund, intent="finalize_draft")
    assert view["summary"]["relations_accepted"] == 0
    assert all(len(item["member_refs"]) == 1 for item in view["items"])


def test_type_split_and_whole_projection_category_keep_member_notes_separate(context_runtime):
    from ft.application.cash_categories import CashCategoryService
    runtime = context_runtime
    with runtime.sessions.begin() as session:
        _, relation_id = _existing_mirror(session, runtime)
        session.get(CashTransactionModel, 1003).note = "成员甲"
        session.get(CashTransactionModel, 1001).note = "成员乙"
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    category = CashCategoryService(runtime.sessions, runtime.workspace_id).create(name="维护分类")["id"]
    service = _service(runtime)
    view = _preview(service, runtime, "1003", patch={"record_type": "transfer_out", "record_subtype": "ordinary_transfer",
        "category_id": category, "note": "只改成员甲"})
    assert len(view["items"]) == 2 and view["summary"]["update_posted"] == 2
    members = _members(service, view)
    assert {member["fields"]["category_id"] for member in members.values()} == {category}
    assert members["1003"]["fields"]["note"] == "只改成员甲" and members["1001"]["fields"]["note"] == "成员乙"
    data = _plan_data(service, view)
    assert all("category_id" in operation["manual_fields"] for operation in data["operations"])
    assert data["category_member_ids"] == ["1001", "1003"]
    effects = [effect for item in view["items"] for effect in service.item(view["plan_id"], item["preview_item_id"])["relation_effects"]]
    assert any(effect["relation_id"] == str(relation_id) and effect["effect"] == "remove" for effect in effects)
    with runtime.sessions() as session:
        assert session.get(TransactionRelationModel, relation_id).active
        assert session.get(CashTransactionModel, 1003).note == "成员甲"


def test_retained_source_issue_is_visible_but_not_scheduled_for_rewrite(context_runtime):
    runtime, identifier = context_runtime, _posted(context_runtime)
    with runtime.sessions.begin() as session:
        issue = CashSourceRepository(session, runtime.workspace_id).save_issue(identifier, "source:update", code="source_update_pending",
            pending_fields={"counterparty": "待采用名称"}, pending_source_payload={"备注": "待采用原文"})
        issue_id, revision = str(issue.id), issue.revision
    service = _service(runtime)
    view = _preview(service, runtime, identifier, patch={"note": "明确备注"})
    assert view["items"][0]["confirmation_status"] == "pending"
    assert any(reason["code"] == "source_update_pending" for reason in view["items"][0]["attention_reasons"])
    operation, = _plan_data(service, view)["operations"]
    assert operation["retained_issue_ids"] == [issue_id]
    assert not any(item["code"] == "source_update_pending" for item in operation["issues"])
    with runtime.sessions() as session:
        issue = session.get(CashTransactionIssueModel, int(issue_id))
        assert issue.revision == revision and issue.pending_source_payload == {"备注": "待采用原文"}


def test_draft_finalization_previews_same_identity_and_only_its_parts_become_discovery_seeds(context_runtime):
    runtime, identifier = context_runtime, _draft(context_runtime)
    before = _records(runtime).read(identifier)
    service = _service(runtime)
    view = _preview(service, runtime, identifier, intent="finalize_draft", patch=_complete(before))
    assert view["summary"]["finalize_draft"] == 1 and view["can_commit"]
    assert all(item["anticipated_state"] == "posted" for item in view["items"])
    data = _plan_data(service, view)
    operation, = data["operations"]
    assert operation["transaction_id"] == identifier and operation["values"]["state"] == "posted"
    assert set(data["dependencies"]["discovery_seeds"]) == {part["component_id"] for part in operation["preview_components"]}
    assert _records(runtime).read(identifier) == before


@pytest.mark.parametrize("code", ["source_identity_ambiguous", "source_update_pending"])
def test_complete_draft_cannot_bypass_unresolved_source_issue(context_runtime, code):
    runtime, identifier = context_runtime, _draft(context_runtime)
    before = _records(runtime).read(identifier)
    _records(runtime).save_draft(identifier, expected_revision=before["row_revision"], patch=_complete(before),
        idempotency_key="synthetic-complete-before-finalization")
    with runtime.sessions.begin() as session:
        issue = CashSourceRepository(session, runtime.workspace_id).save_issue(identifier, "source:pending", code=code,
            pending_source_payload={"备注": "不能丢失"})
        issue_id = issue.id
    with pytest.raises(ValueError) as error:
        _preview(_service(runtime), runtime, identifier, intent="finalize_draft")
    assert getattr(error.value, "code", None) == code
    with runtime.sessions() as session:
        assert session.get(CashTransactionModel, int(identifier)).state == "draft"
        assert session.get(CashTransactionIssueModel, issue_id).pending_source_payload == {"备注": "不能丢失"}


def test_incomplete_draft_does_not_publish_a_posting_plan(context_runtime):
    runtime, identifier = context_runtime, _draft(context_runtime)
    with pytest.raises(ValueError) as error:
        _preview(_service(runtime), runtime, identifier, intent="finalize_draft")
    assert getattr(error.value, "code", None) == "account_missing"
    assert _records(runtime).read(identifier)["state"] == "draft"


def test_pairing_failure_does_not_prevent_valid_finalization_preview(context_runtime, monkeypatch):
    import ft.application.cash_import_business_plan as business
    runtime, identifier = context_runtime, _draft(context_runtime)
    def unavailable(*args, **kwargs):
        raise RuntimeError("sensitive-native-error-must-not-escape")
    monkeypatch.setattr(business, "plan_relation_proposals", unavailable)
    service = _service(runtime)
    view = _preview(service, runtime, identifier, intent="finalize_draft", patch=_complete(_records(runtime).read(identifier)))
    assert view["can_commit"] and view["summary"]["finalize_draft"] == 1
    assert view["items"][0]["anticipated_state"] == "posted" and view["items"][0]["confirmation_status"] == "pending"
    assert "sensitive-native-error" not in json.dumps(view)


@pytest.mark.parametrize("same_scope", [True, False])
def test_current_source_scope_and_remote_refund_key_work_without_files(context_runtime, same_scope):
    runtime = context_runtime
    old = _file(provider="original-order")
    row = replace(old.document.rows[0], fields={**old.document.rows[0].fields, "occurred_at": "2025-01-01T00:00:00Z"},
        source_explanation={"txn_id": "original-order", "merchant_order_id": "merchant-key"})
    old = replace(old, source_type="wechat", source_scope_label="来源甲", document=replace(old.document, source_type="wechat", rows=(row,)))
    with runtime.sessions.begin() as session:
        _bind_existing(session, runtime, old)
        session.get(CashTransactionModel, 1003).relation_metadata = row.source_explanation
    incoming = _file(letter="b", amount="5", provider="merchant-key")
    row = replace(incoming.document.rows[0], fields={**incoming.document.rows[0].fields, "record_type": "refund"},
        source_explanation={"txn_id": "merchant-key", "txn_type": "商户消费-退款",
            "status": "退款成功", "platform_status": "退款成功", "direction": "收入", "_wechat_direction": "收入"})
    incoming = replace(incoming, source_type="wechat", source_scope_label="来源甲" if same_scope else "来源乙",
        document=replace(incoming.document, source_type="wechat", rows=(row,)))
    importer, view = _plan(runtime, (incoming,), mapped=False)
    identifier = _results(importer, _commit(importer, view), "row")[0]["transaction_id"]
    detail = _records(runtime).read(identifier)
    patch = {"account_id": "101", "components": [{"component_id": detail["fields"]["components"][0]["component_id"],
        "ordinal": 0, "account_id": "101", "amount": "5", "currency": "CNY"}]}
    service = _service(runtime)
    view = _preview(service, runtime, identifier, intent="finalize_draft", patch=patch)
    assert bool(view["summary"]["relations_accepted"]) is same_scope
    if same_scope:
        assert view["items"][0]["member_refs"] == [{"transaction_id": "1003"}, {"transaction_id": identifier}]
        assert Decimal(view["items"][0]["display"]["amount"]) == Decimal("-7.5")
