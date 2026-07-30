"""收支投影领域类型的公开合同。"""
from datetime import datetime, timezone
from decimal import Decimal

import pytest


def test_projection_types_are_immutable_and_use_controlled_values():
    from ft.domain.cash_projection import (
        CashProjectionError,
        CashProjectionFact,
        EconomicType,
        ProjectionComposition,
        ProjectionRelation,
    )

    fact = CashProjectionFact(
        id=1, account_id=1, occurred_at=datetime.now(timezone.utc), amount=Decimal("-1"),
        currency="CNY", counterparty="商户", category="餐饮", note="", source_type="fixture", record_id="r-1",
    )
    relation = ProjectionRelation(id=1, kind="payment_mirror", primary_fact_id=1, secondary_fact_id=2)
    assert EconomicType.EXPENSE.value == "expense"
    assert ProjectionComposition.PAYMENT_MIRROR.value == "payment_mirror"
    assert fact.currency == "CNY"
    assert relation.status == "accepted"
    with pytest.raises((AttributeError, TypeError)):
        fact.amount = Decimal("0")
    assert CashProjectionError("projection.incomplete").code == "projection.incomplete"


@pytest.mark.parametrize("amount", ["NaN", "Infinity", "1.0000000000000000001"])
def test_projection_fact_rejects_non_exact_amount(amount):
    from ft.domain.cash_projection import CashProjectionError, CashProjectionFact

    with pytest.raises(CashProjectionError, match="projection.invalid_fact"):
        CashProjectionFact(
            id=1, account_id=1, occurred_at=datetime.now(timezone.utc), amount=Decimal(amount),
            currency="CNY", counterparty="", category="", note="", source_type=None, record_id="",
        )
