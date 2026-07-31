from __future__ import annotations

import pytest


def test_accept_rejects_a_refund_that_cannot_form_a_cash_projection(cash_web_runtime):
    from ft.adapters.relational.models import TransactionRelationModel
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.application.relations import RelationService

    with cash_web_runtime.sessions.begin() as session:
        relation = TransactionRelationModel(
            workspace_id=cash_web_runtime.workspace_id, kind="refund_offset", subtype="",
            primary_fact_id=1003, secondary_fact_id=1001, primary_fact_type="cash", secondary_fact_type="cash",
            ordered_fact_a=1001, ordered_fact_b=1003, anchor_fact_id=1001, status="pending_review",
        )
        session.add(relation)
        session.flush()
        relation_id = str(relation.id)

    service = RelationService(RelationalUnitOfWork(cash_web_runtime.sessions, cash_web_runtime.workspace_id))

    with pytest.raises(ValueError, match="无法形成有效收支投影"):
        service.accept(relation_id, actor="tester")

    with cash_web_runtime.sessions() as session:
        assert session.get(TransactionRelationModel, int(relation_id)).status == "pending_review"


def test_relation_check_hides_internal_error_and_does_not_open_a_second_unit_of_work():
    from ft.application.relations import RelationService

    class BrokenUnitOfWork:
        entered = 0

        def __enter__(self):
            self.entered += 1
            return self

        def __exit__(self, *_args):
            return False

    unit_of_work = BrokenUnitOfWork()
    service = RelationService(unit_of_work)
    service._resolve_seeds = lambda *_args, **_kwargs: (_ for _ in ()).throw(RuntimeError("secret database path"))

    result = service.check()

    assert result.ok is False
    assert result.message == "关系检查失败"
    assert result.details == {"error": "relation.check_failed"}
    assert unit_of_work.entered == 1


def test_auto_scan_keeps_indirect_relation_kind_conflict_pending_and_commits_import(
    relation_runtime,
    monkeypatch,
):
    from ft.domain.relations import RelationEvidence, RelationProposal, RelationStatus
    from tests.test_transaction_relations_support import add_cash_fact, ensure_accounts

    services = relation_runtime.services
    ensure_accounts(services, [("结算账户", "cash")])
    expense_id = add_cash_fact(
        services,
        account_name="结算账户",
        amount="-100",
        date="2026-07-05 09:00:00",
        counterparty="原消费",
        source="alipay",
        record_id="conflict-expense",
    )
    refund_id = add_cash_fact(
        services,
        account_name="结算账户",
        amount="100",
        date="2026-07-05 09:01:00",
        counterparty="退款",
        source="alipay",
        record_id="conflict-refund",
    )
    mirrored_refund_id = add_cash_fact(
        services,
        account_name="结算账户",
        amount="100",
        date="2026-07-05 09:02:00",
        counterparty="退款",
        source="icbc",
        record_id="conflict-refund-bank",
    )
    transfer_id = add_cash_fact(
        services,
        account_name="结算账户",
        amount="-100",
        date="2026-07-05 09:03:00",
        counterparty="转出",
        source="icbc",
        record_id="conflict-transfer",
    )
    with services.uow as uow:
        uow.relations.add({
            "kind": "refund_offset",
            "primary_fact_id": expense_id,
            "secondary_fact_id": refund_id,
            "status": "accepted",
        })
        uow.relations.add({
            "kind": "payment_mirror",
            "primary_fact_id": refund_id,
            "secondary_fact_id": mirrored_refund_id,
            "status": "accepted",
        })
        uow.commit()

    candidate = RelationProposal(
        kind="transfer_pair",
        primary_fact_id=transfer_id,
        secondary_fact_id=mirrored_refund_id,
        status=RelationStatus.ACCEPTED.value,
        rule_id="fixture.transfer",
        confidence="strong",
        evidence=RelationEvidence(amount_delta="0", same_currency=True),
    )
    monkeypatch.setattr(
        "ft.application.relations.run_relation_phases",
        lambda *_args, **_kwargs: [candidate],
    )

    result = services.relations.check(
        seed_fact_ids=[expense_id, refund_id, mirrored_refund_id, transfer_id],
        trigger="manual_range",
        seed_ref="kind-conflict",
    )

    assert result.ok
    assert result.details["stats"]["pending"] == 1
    with services.uow as uow:
        relations = uow.relations.list_active(kind="transfer_pair")
        facts = uow.cashflows.list_detailed()
    assert len(facts) == 4
    assert len(relations) == 1
    assert relations[0]["status"] == "pending_review"
    assert relations[0]["evidence"]["auto_confirmation_blocker"] == "relation.kind_conflict"

    rescan = services.relations.check(
        seed_fact_ids=[expense_id, refund_id, mirrored_refund_id, transfer_id],
        trigger="manual_range",
        seed_ref="kind-conflict-rescan",
    )

    assert rescan.ok
    with services.uow as uow:
        relations = uow.relations.list_active(kind="transfer_pair")
    assert len(relations) == 1
    assert relations[0]["status"] == "pending_review"
    assert relations[0]["evidence"]["auto_confirmation_blocker"] == "relation.kind_conflict"
