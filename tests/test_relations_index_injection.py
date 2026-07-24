"""FactCandidateIndex runtime injection (008)."""
from __future__ import annotations

from decimal import Decimal

from ft.domain.relations import FactView, source_group
from ft.domain.relations.core.types import FactCandidateIndex
from ft.domain.relations.refund.signals import DefaultRefundTextGates, has_refund_signal


def _fv(id: str, amount: str, *, src: str, desc: str = "", day: str = "2026-01-02") -> FactView:
    return FactView(
        id=id,
        amount=Decimal(amount),
        currency="CNY",
        account_id="a",
        counterparty="商户",
        note=desc,
        bill_source=src,
        source=src,
        occurred_at=f"{day}T10:00:00+00:00",
        fact_type="cash",
    )


def test_without_refund_gates_no_refund_bucket_hits():
    exp = _fv("e", "-10.00", src="alipay", desc="消费", day="2026-01-01")
    ref = _fv("r", "10.00", src="alipay", desc="退款成功", day="2026-01-02")
    idx = FactCandidateIndex([exp, ref], source_group=source_group, refund_gates=None)
    # refund leg not indexed without gates
    assert idx.refund_candidates(ref) == []


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
