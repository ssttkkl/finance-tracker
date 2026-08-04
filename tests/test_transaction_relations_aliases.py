"""US7 account alias tests."""
from __future__ import annotations

from decimal import Decimal

from ft.domain.relations import FactView, evaluate_payment_mirror


def test_alias_filters_without_import_hijack():
    # Mirror requires same account_id; alias only validates the candidate, never rewrites accounts.
    seed = FactView(
        id="p1", amount=Decimal("-22"), currency="CNY", account_id="ccb",
        account_name="建行储蓄", occurred_at="2026-06-01 10:00:00",
        counterparty="商家", note="付款方式 尾号7777",
        bill_source="alipay", source="alipay",
        record_type="consumption",
    )
    bank = FactView(
        id="b1", amount=Decimal("-22"), currency="CNY", account_id="ccb",
        account_name="建行储蓄", occurred_at="2026-06-01 10:00:03",
        counterparty="商家", note="消费",
        bill_source="icbc", source="icbc",
        record_type="consumption",
    )
    proposal = evaluate_payment_mirror(
        seed, [bank], aliases_by_tail={"7777": ["ccb", "建行储蓄"]},
    )
    assert proposal is not None
    # import account names unchanged — domain does not rewrite accounts
    assert seed.account_name == "建行储蓄"
    assert bank.account_name == "建行储蓄"
