"""Unpaired pending relations (FR-042–047 / US6b): one pending item; acceptance needs --other."""
from __future__ import annotations

from decimal import Decimal

import pytest

from ft.domain.relations import (
    FactView,
    RelationKind,
    RelationEvidence,
    RelationProposal,
    RelationStatus,
    evaluate_refund_offset,
    evaluate_transfer_pair,
    match_payment_mirrors_greedy,
    project_balances_and_pnl,
)


def _fv(**kwargs):
    fid = str(kwargs.get("id") or "")
    default = "icbc" if fid.startswith("b") else "alipay"
    base = dict(
        currency="CNY", account_type="cash", fact_type="cash", deleted=False,
        bill_source=default, source=default,
    )
    base.update(kwargs)
    if "record_type" not in base:
        amount = Decimal(str(base.get("amount") or 0))
        note_text = str(base.get("note") or "")
        if amount < 0:
            base["record_type"] = "transfer_out" if "转账" in note_text else "consumption"
        elif amount > 0:
            base["record_type"] = (
                "refund" if any(token in note_text for token in ("退款", "退货", "冲正")) else
                "transfer_in" if "转账" in note_text else "income"
            )
        else:
            base["record_type"] = "other"
    return FactView(**base)


def test_multi_candidate_refund_is_single_open_leg_pending():
    expenses = [
        _fv(
            id=f"e{i}",
            amount=Decimal("-100"),
            account_id="1",
            occurred_at=f"2026-01-{('01' if i == 0 else '02')} 10:00:00",
            counterparty="京东",
            note="消费",
            category="expense",
        )
        for i in range(3)
    ]
    refund = _fv(
        id="r",
        amount=Decimal("100"),
        account_id="1",
        occurred_at="2026-01-10 10:00:00",
        counterparty="京东",
        note="退货退款",
        category="income",
    )
    proposal = evaluate_refund_offset(refund, expenses)
    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
    assert proposal.open_leg is True
    assert proposal.secondary_fact_id is None
    assert proposal.anchor_fact_id == "r"
    assert proposal.evidence.open_leg is True
    assert proposal.evidence.anchor_role == "refund"
    assert proposal.evidence.candidate_count == 3
    assert list(proposal.evidence.candidate_fact_ids) == sorted(
        proposal.evidence.candidate_fact_ids
    )
    assert set(proposal.evidence.candidate_fact_ids) == {"e0", "e1", "e2"}
    assert len(proposal.evidence.candidate_fact_ids) <= 20


def test_expense_seed_does_not_fan_out_multi_candidate_bilateral():
    """The refund anchors the unpaired relation; the expense must not emit N bilateral candidates."""
    expenses = [
        _fv(
            id=f"e{i}",
            amount=Decimal("-50"),
            account_id="1",
            occurred_at=f"2026-01-0{i+1} 10:00:00",
            counterparty="京东",
            category="expense",
        )
        for i in range(2)
    ]
    refund = _fv(
        id="r",
        amount=Decimal("50"),
        account_id="1",
        occurred_at="2026-01-08 10:00:00",
        counterparty="京东",
        note="退款",
        category="income",
    )
    # Each expense seed with both refund + sibling expenses would previously fan out.
    for expense in expenses:
        others = [refund] + [e for e in expenses if e.id != expense.id]
        proposal = evaluate_refund_offset(expense, others)
        assert proposal is None or (
            proposal.open_leg is False
            and proposal.secondary_fact_id is not None
            and proposal.status == RelationStatus.ACCEPTED.value
        )


def test_zero_candidate_refund_signal_open_leg():
    refund = _fv(
        id="r",
        amount=Decimal("88"),
        account_id="1",
        occurred_at="2026-01-10 10:00:00",
        counterparty="京东",
        note="退货退款",
        category="income",
    )
    proposal = evaluate_refund_offset(refund, [])
    assert proposal is not None
    assert proposal.open_leg is True
    assert proposal.secondary_fact_id is None
    assert proposal.anchor_fact_id == "r"
    assert proposal.evidence.candidate_count == 0
    assert list(proposal.evidence.candidate_fact_ids) == []


def test_unique_exact_weak_refund_promotes_to_bilateral_accepted():
    expense = _fv(
        id="e",
        amount=Decimal("-100"),
        account_id="1",
        occurred_at="2026-01-01 10:00:00",
        counterparty="商家A",
        category="expense",
    )
    refund = _fv(
        id="r",
        amount=Decimal("100"),
        account_id="1",
        occurred_at="2026-01-05 10:00:00",
        counterparty="其他",
        note="退款到账",
        category="income",
    )
    proposal = evaluate_refund_offset(refund, [expense])
    assert proposal is not None
    assert proposal.open_leg is False
    assert proposal.secondary_fact_id == "r"
    assert proposal.primary_fact_id == "e"
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.confidence == "strong"


def test_unique_strong_refund_still_bilateral_accepted():
    expense = _fv(
        id="e",
        amount=Decimal("-100"),
        account_id="1",
        occurred_at="2026-01-01 10:00:00",
        counterparty="商家A",
        category="expense",
    )
    refund = _fv(
        id="r",
        amount=Decimal("30"),
        account_id="1",
        occurred_at="2026-01-05 10:00:00",
        counterparty="商家A",
        note="退款",
        category="income",
    )
    proposal = evaluate_refund_offset(refund, [expense])
    assert proposal is not None
    assert proposal.open_leg is False
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.secondary_fact_id == "r"


def test_transfer_multi_candidate_open_leg():
    out_leg = _fv(
        id="a",
        amount=Decimal("-1000"),
        account_id="1",
        account_name="A",
        occurred_at="2026-01-01 10:00:00",
        note="转账支取",
    )
    ins = [
        _fv(
            id=f"b{i}",
            amount=Decimal("1000"),
            account_id=str(i + 2),
            account_name=f"B{i}",
            occurred_at=f"2026-01-01 10:00:0{i + 1}",
            note="转账存入",
        )
        for i in range(2)
    ]
    proposal = evaluate_transfer_pair(out_leg, ins)
    assert proposal is not None
    assert proposal.open_leg is True
    assert proposal.secondary_fact_id is None
    assert proposal.anchor_fact_id == "a"
    assert proposal.evidence.anchor_role in {"out", "seed", "transfer_out", "anchor"}
    assert proposal.evidence.candidate_count == 2
    assert set(proposal.evidence.candidate_fact_ids) == {"b0", "b1"}


def test_payment_mirror_never_open_leg():
    # A mirror requires the same account and must remain bilateral, never unpaired.
    platform = _fv(
        id="p",
        amount=Decimal("-40"),
        account_id="card",
        account_name="建行",
        occurred_at="2026-01-01 09:00:00",
        counterparty="商户",
        note="订单X",
    )
    bank = _fv(
        id="b",
        amount=Decimal("-40"),
        account_id="card",
        account_name="建行",
        occurred_at="2026-01-01 09:00:05",
        counterparty="商户",
        note="订单X",
    )
    proposals = match_payment_mirrors_greedy([platform, bank])
    assert proposals
    for p in proposals:
        assert p.open_leg is False
        assert p.secondary_fact_id is not None


def test_projection_ignores_open_leg_pending():
    expense = _fv(
        id="e",
        amount=Decimal("-100"),
        account_id="1",
        account_name="支付宝",
        occurred_at="2026-01-01 10:00:00",
        counterparty="京东",
    )
    refund = _fv(
        id="r",
        amount=Decimal("100"),
        account_id="1",
        account_name="支付宝",
        occurred_at="2026-01-05 10:00:00",
        counterparty="京东",
        note="退款",
        category="income",
    )
    open_rel = {
        "kind": RelationKind.REFUND_OFFSET.value,
        "status": RelationStatus.PENDING_REVIEW.value,
        "primary_fact_id": "r",
        "secondary_fact_id": None,
        "anchor_fact_id": "r",
        "evidence": {"open_leg": True},
    }
    # Even if mistakenly marked accepted with null other, projection must ignore.
    accepted_open = {
        **open_rel,
        "status": RelationStatus.ACCEPTED.value,
    }
    result = project_balances_and_pnl([expense, refund], [accepted_open])
    assert result.expenses["CNY"] == Decimal("100")


def test_open_leg_accept_requires_other_and_binds(relation_runtime):
    services = relation_runtime.services
    assert services.accounts.create_account("支付宝", "cash", "CNY").ok
    # Three same-merchant expenses and one refund produce one unpaired pending relation.
    for i, day in enumerate(("01", "02", "02"), start=1):
        services.cashflow.add_manual_transaction(
            amount=Decimal("-100.00"),
            counterparty="京东",
            account_name="支付宝",
            currency="CNY",
            date=f"2026-01-{day} 10:00:00",
                note=f"消费{i}",
                category="expense",
                record_type="consumption",
        )
    services.cashflow.add_manual_transaction(
        amount=Decimal("100.00"),
        counterparty="京东",
        account_name="支付宝",
        currency="CNY",
        date="2026-01-10 10:00:00",
            note="退货退款",
            category="income",
            record_type="refund",
    )
    with services.uow as uow:
        rows = uow.cashflows.list_detailed()
    ids = [r["id"] for r in rows]
    services.relations.check(seed_fact_ids=ids, trigger="manual_range")
    pending = [
        p
        for p in services.relations.list_pending(kind=RelationKind.REFUND_OFFSET.value)
        if p.get("secondary_fact_id") in (None, "")
        or (p.get("evidence") or {}).get("open_leg")
    ]
    assert len(pending) == 1, pending
    open_row = pending[0]
    assert open_row["secondary_fact_id"] in (None, "")
    assert open_row.get("anchor_fact_id") or (open_row.get("evidence") or {}).get("open_leg")
    evidence = open_row.get("evidence") or {}
    assert evidence.get("open_leg") is True
    assert int(evidence.get("candidate_count") or 0) >= 2
    cand_ids = list(evidence.get("candidate_fact_ids") or [])
    assert len(cand_ids) >= 2

    # Accept without other fails closed.
    with pytest.raises(ValueError, match="--other"):
        services.relations.accept(open_row["id"], actor="user", reason="bind")

    # Illegal other fails closed.
    with services.uow as uow:
        all_rows = uow.cashflows.list_detailed()
    refund_id = next(
        r["id"] for r in all_rows if Decimal(str(r["amount"])) > 0
    )
    expense_ids = [r["id"] for r in all_rows if Decimal(str(r["amount"])) < 0]
    # Use a non-candidate/wrong shape if possible: refund as other is illegal.
    with pytest.raises(ValueError):
        services.relations.accept(
            open_row["id"], actor="user", reason="bad", other_fact_id=refund_id
        )

    # Legal other → accepted bilateral; projection nets.
    other = expense_ids[0]
    accepted = services.relations.accept(
        open_row["id"], actor="user", reason="this one", other_fact_id=other
    )
    assert accepted.ok
    assert accepted.details["status"] == RelationStatus.ACCEPTED.value
    assert accepted.details["primary_fact_id"] == other
    assert accepted.details["secondary_fact_id"] == refund_id
    assert accepted.details["secondary_fact_id"] not in (None, "")
    assert accepted.details["primary_fact_id"] not in (None, "")
    assert accepted.details["secondary_fact_id"] != accepted.details["primary_fact_id"]

    projection = services.relations.project()
    # One expense netted by full refund → remaining expenses 200 (2×100).
    assert Decimal(str(projection["expenses"]["CNY"])) == Decimal("200")


def test_system_open_refund_auto_accept_keeps_expense_as_primary(relation_runtime):
    services = relation_runtime.services
    assert services.accounts.create_account("支付宝", "cash", "CNY").ok
    assert services.cashflow.add_manual_transaction(
        amount=Decimal("-70.00"),
        counterparty="京东",
        account_name="支付宝",
        currency="CNY",
        date="2026-04-01 10:00:00",
        note="原消费",
        category="expense",
    ).ok
    assert services.cashflow.add_manual_transaction(
        amount=Decimal("70.00"),
        counterparty="京东",
        account_name="支付宝",
        currency="CNY",
        date="2026-04-02 10:00:00",
        note="退款",
        category="income",
    ).ok

    with services.uow as uow:
        rows = uow.cashflows.list_detailed()
        expense_id = next(row["id"] for row in rows if Decimal(str(row["amount"])) < 0)
        refund_id = next(row["id"] for row in rows if Decimal(str(row["amount"])) > 0)
        open_relation_id = uow.relations.add({
            "kind": RelationKind.REFUND_OFFSET.value,
            "primary_fact_id": refund_id,
            "secondary_fact_id": None,
            "primary_fact_type": "cash",
            "secondary_fact_type": None,
            "anchor_fact_id": refund_id,
            "status": RelationStatus.PENDING_REVIEW.value,
            "rule_id": "refund_offset.open_leg",
            "confidence": "weak",
            "evidence": {"open_leg": True, "anchor_role": "refund"},
            "created_by": "system",
        })
        proposal = RelationProposal(
            kind=RelationKind.REFUND_OFFSET.value,
            primary_fact_id=expense_id,
            secondary_fact_id=refund_id,
            status=RelationStatus.ACCEPTED.value,
            rule_id="refund_offset.auto_accept",
            confidence="strong",
            evidence=RelationEvidence(extras={"refund_amount": "70"}),
            anchor_fact_id=refund_id,
        )
        accepted = services.relations._persist_proposal(
            uow,
            proposal,
            {expense_id: Decimal("70")},
        )
        uow.commit()

    assert accepted is not None
    assert accepted["id"] == open_relation_id
    assert accepted["status"] == RelationStatus.ACCEPTED.value
    assert accepted["primary_fact_id"] == expense_id
    assert accepted["secondary_fact_id"] == refund_id
    assert accepted["primary_fact_id"] != accepted["secondary_fact_id"]

    from ft.application.cash_projections import CashProjectionService

    CashProjectionService(relation_runtime.sessions, relation_runtime.workspace_id).rebuild()
    with relation_runtime.sessions() as session:
        from ft.adapters.relational.projections import RelationalCashProjectionRepository

        facts, relations = RelationalCashProjectionRepository(
            session,
            relation_runtime.workspace_id,
        ).read_sources()
    assert [(relation.primary_fact_id, relation.secondary_fact_id) for relation in relations] == [
        (expense_id, refund_id),
    ]


def test_partial_refund_keeps_expense_eligible_across_scans(relation_runtime):
    services = relation_runtime.services
    assert services.accounts.create_account("支付宝", "cash", "CNY").ok
    assert services.cashflow.add_manual_transaction(
        amount=Decimal("-100.00"),
        counterparty="商家A",
        account_name="支付宝",
        currency="CNY",
        date="2026-04-01 10:00:00",
        note="原消费",
        category="expense",
        record_type="consumption",
    ).ok
    assert services.cashflow.add_manual_transaction(
        amount=Decimal("30.00"),
        counterparty="商家A",
        account_name="支付宝",
        currency="CNY",
        date="2026-04-02 10:00:00",
        note="退款一",
        category="income",
        record_type="refund",
    ).ok

    with services.uow as uow:
        rows = uow.cashflows.list_detailed()
        expense_id = next(row["id"] for row in rows if row["note"] == "原消费")
        first_refund_id = next(row["id"] for row in rows if row["note"] == "退款一")
    first = services.relations.check(
        seed_fact_ids=[expense_id, first_refund_id],
        trigger="manual_range",
    )
    assert first.ok, first.message

    assert services.cashflow.add_manual_transaction(
        amount=Decimal("20.00"),
        counterparty="商家A",
        account_name="支付宝",
        currency="CNY",
        date="2026-04-03 10:00:00",
        note="退款二",
        category="income",
        record_type="refund",
    ).ok
    with services.uow as uow:
        rows = uow.cashflows.list_detailed()
        second_refund_id = next(row["id"] for row in rows if row["note"] == "退款二")
    second = services.relations.check(
        seed_fact_ids=[second_refund_id],
        trigger="manual_range",
    )
    assert second.ok, second.message

    with services.uow as uow:
        accepted = uow.relations.list_active(
            kind=RelationKind.REFUND_OFFSET.value,
            status=RelationStatus.ACCEPTED.value,
        )
    assert {(row["primary_fact_id"], row["secondary_fact_id"]) for row in accepted} == {
        (expense_id, first_refund_id),
        (expense_id, second_refund_id),
    }


def test_open_leg_reject_suppresses_reopen(relation_runtime):
    services = relation_runtime.services
    assert services.accounts.create_account("支付宝", "cash", "CNY").ok
    for day in ("01", "01"):
        services.cashflow.add_manual_transaction(
            amount=Decimal("-80.00"),
            counterparty="京东",
            account_name="支付宝",
            currency="CNY",
            date=f"2026-02-{day} 10:00:00",
                note="消费",
                category="expense",
                record_type="consumption",
        )
    services.cashflow.add_manual_transaction(
        amount=Decimal("80.00"),
        counterparty="京东",
        account_name="支付宝",
        currency="CNY",
        date="2026-02-10 10:00:00",
            note="退货退款",
            category="income",
            record_type="refund",
    )
    with services.uow as uow:
        ids = [r["id"] for r in uow.cashflows.list_detailed()]
    services.relations.check(seed_fact_ids=ids, trigger="manual_range")
    pending = [
        p
        for p in services.relations.list_pending(kind=RelationKind.REFUND_OFFSET.value)
        if (p.get("evidence") or {}).get("open_leg") or p.get("secondary_fact_id") in (None, "")
    ]
    assert len(pending) == 1
    rid = pending[0]["id"]
    anchor = pending[0].get("anchor_fact_id") or pending[0]["primary_fact_id"]
    rejected = services.relations.reject(rid, actor="user", reason="not sure")
    assert rejected.ok
    services.relations.check(seed_fact_ids=ids, trigger="manual_range")
    pending_after = [
        p
        for p in services.relations.list_pending(kind=RelationKind.REFUND_OFFSET.value)
        if (p.get("evidence") or {}).get("open_leg")
        or p.get("secondary_fact_id") in (None, "")
    ]
    # A rejection occupies the anchor key, so no second unpaired relation is created.
    assert pending_after == []
    with services.uow as uow:
        if hasattr(uow.relations, "find_open_by_anchor"):
            occupied = uow.relations.find_open_by_anchor(
                kind=RelationKind.REFUND_OFFSET.value,
                anchor_fact_id=anchor,
                subtype="",
            )
            assert occupied is not None
            assert occupied["status"] == RelationStatus.REJECTED.value


def test_personal_fx_open_leg_accepts_a_legal_candidate(relation_runtime):
    from tests.test_transaction_relations_support import add_cash_fact, ensure_accounts

    services = relation_runtime.services
    ensure_accounts(services, [("工行借记卡", "cash"), ("外币账户", "cash")])
    out_id = add_cash_fact(
        services, account_name="工行借记卡", amount="-100", currency="CNY",
        date="2026-05-02 09:36:56", description="个人购汇", bill_source="icbc_debit",
        record_id="fx-out", record_type="fx_out",
    )
    usd_id = add_cash_fact(
        services, account_name="外币账户", amount="14", currency="USD",
        date="2026-05-02 09:36:56", description="个人购汇", bill_source="icbc_debit",
        record_id="fx-usd", record_type="fx_in", category="income",
    )
    hkd_id = add_cash_fact(
        services, account_name="外币账户", amount="110", currency="HKD",
        date="2026-05-02 09:36:56", description="个人购汇", bill_source="icbc_debit",
        record_id="fx-hkd", record_type="fx_in", category="income",
    )
    result = services.relations.check(
        seed_fact_ids=[out_id, usd_id, hkd_id], trigger="manual_range",
    )
    assert result.ok, result.message
    pending = services.relations.list_pending(kind=RelationKind.TRANSFER_PAIR.value)
    assert len(pending) == 1

    accepted = services.relations.accept(
        pending[0]["id"], actor="tester", reason="确认美元购汇", other_fact_id=usd_id,
    )

    assert accepted.ok
    assert accepted.details["status"] == RelationStatus.ACCEPTED.value
    assert {accepted.details["primary_fact_id"], accepted.details["secondary_fact_id"]} == {
        out_id,
        usd_id,
    }



def test_personal_fx_open_leg_accept_rejects_non_candidate_and_occupied_endpoint(relation_runtime):
    from tests.test_transaction_relations_support import add_cash_fact, ensure_accounts

    services = relation_runtime.services
    ensure_accounts(services, [("工行借记卡", "cash"), ("美元账户", "cash")])
    out_id = add_cash_fact(
        services, account_name="工行借记卡", amount="-100", currency="CNY",
        date="2026-05-02 09:36:56", description="个人购汇", bill_source="icbc_debit",
        record_id="fx-out", record_type="fx_out",
    )
    usd_id = add_cash_fact(
        services, account_name="美元账户", amount="14", currency="USD",
        date="2026-05-02 09:36:56", description="个人购汇", bill_source="icbc_debit",
        record_id="fx-usd", record_type="fx_in", category="income",
    )
    hkd_id = add_cash_fact(
        services, account_name="美元账户", amount="110", currency="HKD",
        date="2026-05-02 09:36:56", description="个人购汇", bill_source="icbc_debit",
        record_id="fx-hkd", record_type="fx_in", category="income",
    )
    unrelated_id = add_cash_fact(
        services, account_name="美元账户", amount="14", currency="USD",
        date="2026-05-02 09:36:56", description="普通收入", bill_source="icbc_debit",
        record_id="ordinary-income", record_type="income", category="income",
    )

    result = services.relations.check(
        seed_fact_ids=[out_id, usd_id, hkd_id, unrelated_id], trigger="manual_range",
    )
    assert result.ok, result.message
    pending = services.relations.list_pending(kind=RelationKind.TRANSFER_PAIR.value)
    assert len(pending) == 1
    open_row = pending[0]
    assert open_row["subtype"] == "currency_exchange"
    assert set(open_row["evidence"]["candidate_fact_ids"]) == {str(usd_id), str(hkd_id)}

    with pytest.raises(ValueError, match="购汇转出和购汇转入"):
        services.relations.accept(
            open_row["id"], actor="tester", reason="wrong leg", other_fact_id=unrelated_id,
        )

    with services.uow as uow:
        uow.relations.add({
            "kind": RelationKind.TRANSFER_PAIR.value,
            "subtype": "currency_exchange",
            "primary_fact_id": out_id,
            "secondary_fact_id": usd_id,
            "primary_fact_type": "cash",
            "secondary_fact_type": "cash",
            "status": RelationStatus.ACCEPTED.value,
            "rule_id": "fixture.occupied",
            "confidence": "strong",
        })
        uow.commit()

    with pytest.raises(ValueError, match="已被另一条已确认关系占用"):
        services.relations.accept(
            open_row["id"], actor="tester", reason="occupied", other_fact_id=usd_id,
        )

    with services.uow as uow:
        assert uow.relations.get(open_row["id"])["status"] == RelationStatus.PENDING_REVIEW.value


def test_transfer_open_leg_persisted_and_accept(relation_runtime):
    services = relation_runtime.services
    assert services.accounts.create_account("A", "cash", "CNY").ok
    assert services.accounts.create_account("B1", "cash", "CNY").ok
    assert services.accounts.create_account("B2", "cash", "CNY").ok
    services.cashflow.add_manual_transaction(
        amount=Decimal("-500.00"),
        counterparty="",
        account_name="A",
        currency="CNY",
        date="2026-03-01 10:00:00",
            note="转账支取",
            category="expense",
            record_type="transfer_out",
    )
    services.cashflow.add_manual_transaction(
        amount=Decimal("500.00"),
        counterparty="",
        account_name="B1",
        currency="CNY",
        date="2026-03-01 10:00:03",
            note="转账存入",
            category="income",
            record_type="transfer_in",
    )
    services.cashflow.add_manual_transaction(
        amount=Decimal("500.00"),
        counterparty="",
        account_name="B2",
        currency="CNY",
        date="2026-03-01 10:00:04",
            note="转账存入",
            category="income",
            record_type="transfer_in",
    )
    with services.uow as uow:
        rows = uow.cashflows.list_detailed()
    ids = [r["id"] for r in rows]
    out_id = next(r["id"] for r in rows if Decimal(str(r["amount"])) < 0)
    in_ids = [r["id"] for r in rows if Decimal(str(r["amount"])) > 0]
    services.relations.check(seed_fact_ids=ids, trigger="manual_range")
    pending = [
        p
        for p in services.relations.list_pending(kind=RelationKind.TRANSFER_PAIR.value)
        if (p.get("evidence") or {}).get("open_leg") or p.get("secondary_fact_id") in (None, "")
    ]
    assert len(pending) == 1
    open_row = pending[0]
    assert open_row["secondary_fact_id"] in (None, "")
    accepted = services.relations.accept(
        open_row["id"], actor="user", reason="B1", other_fact_id=in_ids[0]
    )
    assert accepted.ok
    assert accepted.details["status"] == RelationStatus.ACCEPTED.value
    assert {accepted.details["primary_fact_id"], accepted.details["secondary_fact_id"]} == {
        out_id,
        in_ids[0],
    }
    projection = services.relations.project()
    # Transfer excluded from P&L; remaining B2 income still counts as income.
    assert Decimal(str(projection["expenses"].get("CNY", "0"))) == Decimal("0")
