from decimal import Decimal, localcontext

import pytest

from ft.domain.decimal import exact_decimal


@pytest.mark.parametrize("text", ["0", "-0", "0E-19", "-0.0000", "1E+19", "1E-18", "-1E-18",
    "1.2345678901234567890", "99999999999999999999.999999999999999999",
    "-99999999999999999999.999999999999999999"])
def test_decimal_validation_preserves_exact_representation_independent_of_context(text):
    with localcontext() as context:
        context.prec = 2
        value = Decimal(text)
        assert exact_decimal(value).as_tuple() == value.as_tuple()
        assert exact_decimal(text).as_tuple() == value.as_tuple()


@pytest.mark.parametrize("text", ["NaN", "sNaN", "Infinity", "-Infinity", "1E+20", "1E-19", "0E-20",
    "99999999999999999999.9999999999999999999"])
def test_decimal_validation_rejects_nonfinite_and_out_of_range_typed_values(text):
    with pytest.raises(ValueError):
        exact_decimal(Decimal(text))


def test_decimal_subclass_preserves_existing_conversion_protocol():
    class CustomDecimal(Decimal):
        def __str__(self):
            return "12.5"
    assert exact_decimal(CustomDecimal("3")) == Decimal("12.5")
