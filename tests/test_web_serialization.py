from dataclasses import dataclass
from datetime import date, datetime, timezone
from decimal import Decimal
import json

from starlette.responses import JSONResponse

from ft.web.serialization import json_value


def test_nested_result_lists_keep_exact_decimal_and_date_strings():
    @dataclass
    class Result:
        rows: list

    value = Result([{"amount": Decimal("12345678901234567890.0100"),
        "parts": [(Decimal("-0.00100"), date(2026, 10, 24))],
        "at": datetime(2026, 10, 24, 4, 30, tzinfo=timezone.utc)}])
    body = json.loads(JSONResponse(json_value(value)).body)
    assert body == {"rows": [{"amount": "12345678901234567890.0100",
        "parts": [["-0.00100", "2026-10-24"]], "at": "2026-10-24T04:30:00+00:00"}]}
