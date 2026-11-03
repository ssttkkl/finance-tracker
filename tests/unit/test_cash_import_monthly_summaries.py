from ft.application.cash_import_plans import _import_monthly_summaries


def test_import_monthly_summaries_use_full_plan_and_local_month_with_signed_refund():
    rows = [
        {"occurred_at": "2026-10-31T16:30:00Z", "amount": "100.00", "currency": "CNY",
            "economic_type": "income", "statistics_eligible": True, "confirmation_status": "confirmed"},
        {"occurred_at": "2026-11-01T01:00:00Z", "amount": "-40.00", "currency": "CNY",
            "economic_type": "expense", "statistics_eligible": True, "confirmation_status": "pending"},
        {"occurred_at": "2026-11-02T01:00:00Z", "amount": "15.00", "currency": "CNY",
            "economic_type": "expense", "statistics_eligible": True, "confirmation_status": "confirmed"},
        {"occurred_at": None, "amount": None, "currency": None,
            "economic_type": None, "statistics_eligible": False, "confirmation_status": "pending"},
    ]
    result = _import_monthly_summaries(rows, "Asia/Shanghai")
    assert result["all"] == [{"month": "2026-11", "currencies": [
        {"currency": "CNY", "income": "100.00", "expense": "-25.00"}]}]
    assert result["pending"] == [{"month": "2026-11", "currencies": [
        {"currency": "CNY", "income": "0", "expense": "-40.00"}]}]
    assert result["confirmed"] == [{"month": "2026-11", "currencies": [
        {"currency": "CNY", "income": "100.00", "expense": "15.00"}]}]
