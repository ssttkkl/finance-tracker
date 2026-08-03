"""FactCandidateIndex runtime injection (008)."""
from __future__ import annotations

import builtins
from decimal import Decimal

from ft.domain.relations import FactView, MatchContext, run_relation_phases, source_group
from ft.domain.relations.core.types import FactCandidateIndex
from ft.domain.relations.refund.signals import DefaultRefundTextGates, has_refund_signal


def _fv(
    id: str,
    amount: str,
    *,
    src: str,
    desc: str = "",
    day: str = "2026-01-02",
    raw_payload: dict | None = None,
    record_type: str | None = None,
) -> FactView:
    return FactView(
        id=id,
        amount=Decimal(amount),
        currency="CNY",
        account_id="a",
        counterparty="商户",
        note=desc,
        occurred_at=f"{day}T10:00:00+00:00",
        bill_source=src,
        source=src,
        fact_type="cash",
        record_type=record_type or ("consumption" if Decimal(amount) < 0 else "refund"),
        raw_payload=raw_payload,
    )


def test_formal_refund_does_not_need_text_gates_for_candidate_index():
    exp = _fv("e", "-10.00", src="alipay", desc="消费", day="2026-01-01")
    ref = _fv("r", "10.00", src="alipay", desc="退款成功", day="2026-01-02")
    idx = FactCandidateIndex([exp, ref], source_group=source_group, refund_gates=None)
    assert [fact.id for fact in idx.refund_candidates(ref)] == ["e"]


def test_with_refund_gates_finds_expense_for_refund():
    exp = _fv("e", "-10.00", src="alipay", desc="消费", day="2026-01-01")
    ref = _fv("r", "10.00", src="alipay", desc="退款成功", day="2026-01-02")
    assert has_refund_signal(ref.text)
    idx = FactCandidateIndex(
        [exp, ref],
        source_group=source_group,
        refund_gates=DefaultRefundTextGates(),
    )
    cands = idx.refund_candidates(ref)
    assert any(c.id == "e" for c in cands)


def test_icbc_structured_return_enters_refund_bucket_without_refund_text():
    exp = _fv("e", "-272.00", src="icbc_credit", desc="山葵村烤肉", day="2026-05-25")
    ref = _fv(
        "r", "272.00", src="icbc_credit", desc="山葵村烤肉", day="2026-05-25",
        raw_payload={
            "bill_source": "icbc_credit",
            "summary": "退货",
            "refund_signal": "icbc_credit_return",
        },
    )
    idx = FactCandidateIndex(
        [exp, ref], source_group=source_group, refund_gates=DefaultRefundTextGates()
    )

    assert [fact.id for fact in idx.refund_candidates(ref)] == ["e"]


def test_icbc_structured_signal_rejects_summary_without_signal():
    exp = _fv("e", "-272.00", src="icbc_credit", desc="山葵村烤肉", day="2026-05-25")
    ref = _fv(
        "r", "272.00", src="icbc_credit", desc="退货 山葵村烤肉", day="2026-05-25",
        # The old summary-only row is explicitly not a refund.
        raw_payload={"bill_source": "icbc_credit", "summary": "退货"},
        record_type="income",
    )
    idx = FactCandidateIndex(
        [exp, ref], source_group=source_group, refund_gates=DefaultRefundTextGates()
    )

    assert idx.refund_candidates(ref) == []


def test_candidate_index_aggregates_refund_days_once_per_account_currency(monkeypatch):
    facts = [
        _fv(f"expense-{day}", "-10.00", src="alipay", day=f"2026-01-0{day}")
        for day in range(1, 5)
    ]
    calls = 0
    original_sorted = builtins.sorted

    def tracked_sorted(values, *args, **kwargs):
        nonlocal calls
        calls += 1
        return original_sorted(values, *args, **kwargs)

    monkeypatch.setattr(builtins, "sorted", tracked_sorted)

    FactCandidateIndex(facts, source_group=source_group, refund_gates=None)

    assert calls == 1


def test_relation_scan_builds_mirror_components_once_for_all_refund_seeds(monkeypatch):
    import ft.domain.relations.pipeline as pipeline

    platform_expense = _fv("platform-expense", "-10.00", src="alipay", day="2026-01-01")
    bank_expense = _fv("bank-expense", "-10.00", src="icbc_debit", day="2026-01-01")
    refunds = [
        _fv(f"refund-{index}", "10.00", src="alipay", day="2026-01-02")
        for index in range(2)
    ]
    facts = [platform_expense, bank_expense, *refunds]
    index = FactCandidateIndex(facts, source_group=source_group, refund_gates=None)
    calls = 0
    original_build = pipeline.build_mirror_components

    def tracked_build(*args, **kwargs):
        nonlocal calls
        calls += 1
        return original_build(*args, **kwargs)

    monkeypatch.setattr(pipeline, "build_mirror_components", tracked_build)

    run_relation_phases(
        facts,
        ctx=MatchContext(),
        seed_ids=[fact.id for fact in facts],
        index=index,
    )

    assert calls == 1


def test_core_types_module_has_no_refund_pack_import():
    import ast
    from pathlib import Path

    src = Path("src/ft/domain/relations/core/types.py").read_text(encoding="utf-8")
    tree = ast.parse(src)
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and node.module:
            assert "refund" not in node.module, node.module
            assert "transfer" not in node.module, node.module
            assert "mirror" not in node.module, node.module
