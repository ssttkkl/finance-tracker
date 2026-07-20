from decimal import Decimal


def test_modified_dietz_and_linked_return_fail_on_non_positive_capital() -> None:
    from ft.domain.wealth_calculation import linked_return, modified_dietz

    assert modified_dietz(Decimal("100"), Decimal("120"), ((Decimal("10"), Decimal("0.5")),)) == Decimal("10") / Decimal("105")
    assert modified_dietz(Decimal("0"), Decimal("10"), ()) is None
    assert linked_return((Decimal("0.1"), Decimal("0.2"))) == Decimal("0.32")
    assert linked_return((Decimal("0.1"), None)) is None
