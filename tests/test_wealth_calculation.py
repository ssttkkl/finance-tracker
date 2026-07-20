from decimal import Decimal


def test_complete_identity_classifies_known_cashflows_without_double_counting() -> None:
    from ft.domain.wealth_calculation import WealthEvent, calculate_identity

    result = calculate_identity(
        opening=Decimal("100"), closing=Decimal("130"),
        events=(
            WealthEvent("salary", Decimal("50")),
            WealthEvent("investment_funding", Decimal("40")),
            WealthEvent("transfer", Decimal("-20")),
            WealthEvent("transfer", Decimal("20")),
            WealthEvent("dividend", Decimal("3")),
            WealthEvent("fee", Decimal("-1")),
        ),
    )
    assert result.external_cashflow == Decimal("50")
    assert result.investment_return == Decimal("2")
    assert result.unexplained_adjustment == Decimal("-22")
    assert result.closing - result.opening == sum(result.components.values())
    assert result.explained_ratio == Decimal("15") / Decimal("26")


def test_liability_and_residual_signs_are_net_worth_signs() -> None:
    from ft.domain.wealth_calculation import calculate_identity

    result = calculate_identity(
        opening=Decimal("0"), closing=Decimal("-20"), liability_revaluation=Decimal("-20")
    )
    assert result.liability_revaluation == Decimal("-20")
    assert result.unexplained_adjustment == Decimal("0")
