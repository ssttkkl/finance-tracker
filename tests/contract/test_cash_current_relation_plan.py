"""库中配对从相关流水生成完整只读计划，保持现有决定及原候选证明。"""
from copy import deepcopy
from datetime import datetime, timezone
from decimal import Decimal

import pytest
from sqlalchemy import delete, select

from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.models import (CashRelationCandidateModel, CashSourceBindingModel, CashTransactionComponentModel,
    CashTransactionModel, TransactionRelationModel)
from ft.application.cash_projections import CashProjectionService
from tests.contract.test_cash_import_commit import configured
from tests.contract.test_cash_mutation_business_plan import _plan_data, _preview
from tests.contract.test_cash_mutation_commit import _service
from tests.contract.test_cash_record_maintenance import _service as _records
from tests.contract.test_cash_relation_context import _add, _joint
from tests.contract.test_parsed_cash_import_context import context_runtime


def _facts(runtime):
    with runtime.sessions.begin() as session:
        for identifier in range(1101, 1106):
            amount = Decimal("100" if identifier in (1103, 1104) else "-100")
            session.add(CashTransactionModel(id=identifier, workspace_id=runtime.workspace_id, account_id=101,
                amount=amount, currency="CNY", occurred_at=datetime(2026, 1, 1, tzinfo=timezone.utc),
                record_type="refund" if amount > 0 else "consumption", record_subtype="not_applicable",
                source_type="current-relation-fixture", record_id=str(identifier)))
        session.flush()
        for identifier in range(1101, 1106):
            session.add(CashTransactionComponentModel(id=identifier + 5000, workspace_id=runtime.workspace_id,
                cash_transaction_id=identifier, account_id=101, amount=Decimal("100" if identifier in (1103, 1104) else "-100"),
                currency="CNY", ordinal=0))


def _rows(runtime):
    with runtime.sessions() as session:
        return deepcopy(CurrentCashRelationRepository(session, runtime.workspace_id).list_for_transactions(
            range(1101, 1106), active_only=False, include_candidates=True))


def _start(runtime, *, joint=False, state="draft", inactive=False):
    _facts(runtime)
    with runtime.sessions.begin() as session:
        if joint:
            rows = _joint(session, runtime.workspace_id)
        else:
            repo = CurrentCashRelationRepository(session, runtime.workspace_id)
            rows = (_add(repo, 1101, 1102, state=state, group="stored-group" if inactive else None),)
            if inactive:
                extra = _add(repo, 1103, 1104, state=state, group="stored-group")
                session.get(TransactionRelationModel, extra["id"]).active = False
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    return _service(runtime), rows


def _groups(service, view):
    groups = {}
    for item in view["items"]:
        for group in service.item(view["plan_id"], item["preview_item_id"])["relation_choices"]:
            groups[group["suggestion_id"]] = group
    return list(groups.values())


def _choose(service, runtime, view, choice, *, identifier="1101", patch=None):
    return _preview(service, runtime, identifier, intent="maintain_relations", patch=patch,
        edit_context_id=view["edit_context_id"], previous_plan_id=view["plan_id"],
        expected_plan_revision=view["plan_revision"], relation_choices=[choice])


@pytest.mark.parametrize("state", ["confirmed", "draft", "ignored"])
def test_default_preview_preserves_every_current_decision_and_never_discovers(context_runtime, monkeypatch, state):
    from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
    runtime = context_runtime
    service, _ = _start(runtime, state=state)
    before = _rows(runtime)
    monkeypatch.setattr(CashImportBusinessPlanBuilder, "_discover", lambda *_a, **_k: pytest.fail("maintenance searched new pairs"))
    view = _preview(service, runtime, "1101", intent="maintain_relations")
    assert _rows(runtime) == before
    assert not view["can_commit"]  # 当前组更新执行器就绪前不能走新增关系写入器。
    assert all(view["summary"].get(key, 0) == 0 for key in (
        "relations_accepted", "relations_deferred", "relations_ignored", "relations_removed"))
    group, = _groups(service, view)
    assert group["action"] == {"confirmed": "accept", "draft": "defer", "ignored": "ignore"}[state]
    assert len(view["items"]) == (1 if state == "confirmed" else 2)


def test_joint_draft_loads_both_mirrors_and_accepts_only_as_one_full_preview(context_runtime):
    runtime = context_runtime
    service, rows = _start(runtime, joint=True)
    before = _rows(runtime)
    view = _preview(service, runtime, "1101", intent="maintain_relations")
    group, = [group for group in _groups(service, view) if group["kind"] == "payment_mirror"]
    plan = _plan_data(service, view)
    stored = next(item for item in plan["relation_groups"] if item["suggestion_id"] == group["suggestion_id"])
    assert {edge["id"] for edge in stored["edges"]} == {row["id"] for row in rows}
    assert len(view["items"]) == 2 and all(Decimal(item["display"]["amount"]) == 0 for item in view["items"])
    selected = _choose(service, runtime, view, {"suggestion_id": group["suggestion_id"], "action": "accept"})
    item, = selected["items"]
    assert Decimal(item["display"]["amount"]) == 0 and item["confirmation_status"] == "confirmed"
    assert {ref["transaction_id"] for ref in item["member_refs"]} == {"1101", "1102", "1103", "1104"}
    assert selected["summary"]["relations_accepted"] == 1
    assert _rows(runtime) == before


def test_mixed_inactive_old_group_is_loaded_without_reactivation(context_runtime):
    runtime = context_runtime
    service, _ = _start(runtime, state="confirmed", inactive=True)
    before = _rows(runtime)
    view = _preview(service, runtime, "1101", intent="maintain_relations")
    stored, = _plan_data(service, view)["relation_groups"]
    assert len(stored["edges"]) == 2 and sum(edge["active"] for edge in stored["edges"]) == 1
    group, = _groups(service, view)
    removed = _choose(service, runtime, view, {"suggestion_id": group["suggestion_id"], "action": "remove"})
    assert removed["summary"]["relations_removed"] == 1
    assert all(not edge["active"] for edge in _plan_data(service, removed)["relation_groups"][0]["edges"])
    assert _rows(runtime) == before


def test_explicit_removal_marks_the_existing_confirmed_relation_effect(context_runtime):
    runtime = context_runtime
    service, rows = _start(runtime, state="confirmed")
    view = _preview(service, runtime, "1101", intent="maintain_relations")
    group, = _groups(service, view)
    removed = _choose(service, runtime, view, {"suggestion_id": group["suggestion_id"], "action": "remove"})
    effects = [effect for item in removed["items"] for effect in service.item(
        removed["plan_id"], item["preview_item_id"])["relation_effects"]]
    assert effects and all(effect["effect"] == "remove" for effect in effects if effect["relation_id"] == str(rows[0]["id"]))


def test_stale_removal_choice_does_not_relabel_an_existing_confirmed_group_as_deferred(context_runtime):
    runtime = context_runtime
    service, _ = _start(runtime, state="confirmed")
    view = _preview(service, runtime, "1101", intent="maintain_relations")
    group, = _groups(service, view)
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1102).note = "对侧已有更新"
    selected = _choose(service, runtime, view, {"suggestion_id": group["suggestion_id"], "action": "remove"})
    assert selected["invalidated_choices"]
    current, = _groups(service, selected)
    assert current["action"] == "accept"
    item, = selected["items"]
    assert item["confirmation_status"] == "confirmed" and len(item["member_refs"]) == 2


def _open(runtime, *, stale=False):
    _facts(runtime)
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        row = repo.add({"kind": "payment_mirror", "primary_transaction_id": 1101,
            "primary_component_id": 6101, "anchor_transaction_id": 1101, "anchor_component_id": 6101})
        repo.set_candidates(row["id"], [{"candidate_transaction_id": identifier, "candidate_component_id": identifier + 5000,
            "expected_row_revision": session.get(CashTransactionModel, identifier).row_revision,
            "evidence": {}, "validity": "stale" if stale and identifier == 1105 else "valid"} for identifier in (1102, 1105)],
            expected_revision=row["row_revision"])
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    return _service(runtime), row["id"]


def test_open_current_candidates_require_original_current_proof_and_preview_complete_choice(context_runtime):
    runtime = context_runtime
    service, _ = _open(runtime, stale=True)
    before = _rows(runtime)
    view = _preview(service, runtime, "1101", intent="maintain_relations")
    group, = _groups(service, view)
    candidate, = group["candidates"]
    assert {ref["transaction_id"] for ref in candidate["member_refs"]} == {"1101", "1102"}
    selected = _choose(service, runtime, view, {"suggestion_id": group["suggestion_id"],
        "action": "accept", "candidate_id": candidate["candidate_id"]})
    assert any({ref["transaction_id"] for ref in item["member_refs"]} == {"1101", "1102"} for item in selected["items"])
    assert _rows(runtime) == before


def test_candidate_content_change_without_parent_revision_invalidates_old_selection(context_runtime):
    runtime = context_runtime
    service, relation = _open(runtime)
    view = _preview(service, runtime, "1101", intent="maintain_relations")
    group, = _groups(service, view)
    before = _records(runtime).read("1101")["row_revision"]
    with runtime.sessions.begin() as session:
        row = session.scalar(select(CashRelationCandidateModel).where(CashRelationCandidateModel.relation_id == relation))
        row.evidence = {"primary_original_expense": True}
    assert _records(runtime).read("1101")["row_revision"] == before
    selected = _choose(service, runtime, view, {"suggestion_id": group["suggestion_id"], "action": "accept",
        "candidate_id": group["candidates"][0]["candidate_id"]})
    assert selected["invalidated_choices"] == [{"kind": "relation", "reference": group["suggestion_id"], "code": "relation_context_changed"}]
    assert selected["summary"]["relations_accepted"] == 0


def test_candidate_reference_must_come_from_the_previous_current_plan(context_runtime):
    runtime = context_runtime
    service, _ = _open(runtime)
    view = _preview(service, runtime, "1101", intent="maintain_relations")
    group, = _groups(service, view)
    with pytest.raises(ValueError) as captured:
        _choose(service, runtime, view, {"suggestion_id": group["suggestion_id"], "action": "accept", "candidate_id": "foreign"})
    assert captured.value.code == "plan_input_invalid"


@pytest.mark.parametrize("change", ["validity", "parent_revision"])
def test_tentative_secondary_does_not_bypass_a_stale_candidate_proof(context_runtime, change):
    runtime = context_runtime
    service, rows = _start(runtime)
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        repo.set_candidates(rows[0]["id"], [{"candidate_transaction_id": 1102, "candidate_component_id": 6102,
            "expected_row_revision": session.get(CashTransactionModel, 1102).row_revision,
            "validity": "stale" if change == "validity" else "valid", "evidence": {}}], expected_revision=rows[0]["row_revision"])
        if change == "parent_revision":
            session.get(CashTransactionModel, 1102).note = "改变了原候选修订"
    view = _preview(service, runtime, "1101", intent="maintain_relations")
    group, = _groups(service, view)
    assert "accept" not in group["allowed_actions"] and not group["candidates"]


def test_reselected_current_candidate_keeps_old_tentative_counterpart_in_the_preview(context_runtime):
    runtime = context_runtime
    service, rows = _start(runtime)
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        repo.set_candidates(rows[0]["id"], [{"candidate_transaction_id": 1105, "candidate_component_id": 6105,
            "expected_row_revision": session.get(CashTransactionModel, 1105).row_revision, "evidence": {}}],
            expected_revision=rows[0]["row_revision"])
    view = _preview(service, runtime, "1101", intent="maintain_relations")
    group, = _groups(service, view)
    candidate, = group["candidates"]
    selected = _choose(service, runtime, view, {"suggestion_id": group["suggestion_id"], "action": "accept",
        "candidate_id": candidate["candidate_id"]})
    members = [{ref["transaction_id"] for ref in item["member_refs"]} for item in selected["items"]]
    assert {"1101", "1105"} in members and {"1102"} in members
    previous = next(item for item in selected["items"] if item["member_refs"] == [{"transaction_id": "1102"}])
    assert previous["confirmation_status"] == "confirmed"


@pytest.mark.parametrize("current_hint", [False, True])
def test_unconfirmed_role_is_rediscovered_from_current_source_evidence(context_runtime, current_hint):
    runtime = context_runtime
    service, rows = _start(runtime)
    with runtime.sessions.begin() as session:
        row = session.get(CashTransactionModel, 1101)
        row.source_type, row.record_type = "wechat", "refund"
        row.relation_metadata = {"direction": "支出", "status": "已全额退款"} if current_hint else {}
        session.get(TransactionRelationModel, rows[0]["id"]).evidence = {"primary_original_expense": True}
    view = _preview(service, runtime, "1101", intent="maintain_relations")
    group, = _groups(service, view)
    assert ("accept" in group["allowed_actions"]) is current_hint


@pytest.mark.parametrize("kind", ["investment", "p2p"])
@pytest.mark.parametrize("change", ["none", "key_removed", "key_changed", "other_scope"])
def test_special_refund_rechecks_current_hard_keys_and_real_source_scope(context_runtime, kind, change):
    from ft.domain.cash_source_matching import provider_identity_key
    runtime = context_runtime
    _facts(runtime)
    with runtime.sessions.begin() as session:
        source = "alipay" if kind == "investment" else "wechat"
        for identifier in (1101, 1103):
            row = session.get(CashTransactionModel, identifier)
            row.source_type = source
            row.record_type = ("investment_out" if identifier == 1101 else "refund") if kind == "investment" else "transfer_reversal"
            metadata = {"txn_id": "order" if identifier == 1101 else "order_refund",
                "merchant_order_id": "return-key" if identifier == 1101 else "order", "direction": "支出" if identifier == 1101 else "收入",
                "status": "转账成功" if identifier == 1101 else "退款成功", "txn_type": "转账退款"}
            if identifier == 1101 and change == "key_removed":
                metadata.pop("txn_id")
                metadata.pop("merchant_order_id")
            elif identifier == 1101 and change == "key_changed":
                metadata.update(txn_id="unrelated-order", merchant_order_id="unrelated-merchant")
            row.relation_metadata = metadata
            session.add(CashSourceBindingModel(workspace_id=runtime.workspace_id, transaction_id=identifier,
                source_type=source, source_scope_kind="account", source_scope_key="different" if identifier == 1103 and change == "other_scope" else "same",
                identity_kind="provider_id", identity_key=provider_identity_key("order" if identifier == 1101 else "order_refund"),
                identity_strength="provider", strategy_version="synthetic-current-proof"))
        CurrentCashRelationRepository(session, runtime.workspace_id).add({"kind": "refund_offset",
            "subtype": "p2p_return" if kind == "p2p" else "", "primary_transaction_id": 1101, "secondary_transaction_id": 1103,
            "primary_component_id": 6101, "secondary_component_id": 6103, "anchor_transaction_id": 1101, "anchor_component_id": 6101,
            "primary_role": "p2p_out" if kind == "p2p" else "expense", "secondary_role": "p2p_in" if kind == "p2p" else "refund",
            "evidence": {"investment_refund_hard_key": True, "p2p_return_hard_key": True}})
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    service = _service(runtime)
    view = _preview(service, runtime, "1101", intent="maintain_relations")
    group, = _groups(service, view)
    assert ("accept" in group["allowed_actions"]) is (change == "none")


@pytest.mark.parametrize("action", ["defer", "ignore", "remove"])
def test_parent_only_draft_can_be_deferred_or_cleared_without_guessing_parts(context_runtime, action):
    runtime = context_runtime
    _facts(runtime)
    with runtime.sessions.begin() as session:
        parent = session.get(CashTransactionModel, 1101)
        parent.state, parent.amount, parent.currency, parent.account_id = "draft", None, None, None
        parent.record_type, parent.record_subtype, parent.occurred_at = None, None, None
        session.execute(delete(CashTransactionComponentModel).where(CashTransactionComponentModel.cash_transaction_id == 1101))
        CurrentCashRelationRepository(session, runtime.workspace_id).add({"kind": "payment_mirror",
            "primary_transaction_id": 1101, "anchor_transaction_id": 1101})
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    service = _service(runtime)
    view = _preview(service, runtime, "1101", intent="maintain_relations")
    group, = _groups(service, view)
    assert "accept" not in group["allowed_actions"]
    selected = _choose(service, runtime, view, {"suggestion_id": group["suggestion_id"], "action": action})
    assert selected["items"][0]["anticipated_state"] == "draft"
    assert selected["items"][0]["display"]["amount"] is None
    assert _records(runtime).read("1101")["state"] == "draft"
