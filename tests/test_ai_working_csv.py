from ft.ai_working_csv import (
    AI_WORKING_FIELDS,
    build_ai_working_row,
    parse_ai_action_target,
    write_ai_working_csv,
    read_ai_working_csv,
)


def test_build_ai_working_row_sets_expected_defaults(tmp_path):
    row = build_ai_working_row(
        {
            "date": "2026-06-12 10:00:03",
            "amount": "-30.00",
            "currency": "CNY",
            "counterparty": "麦当劳",
            "description": "麦当劳",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "支付宝",
            "bill_source": "alipay",
        },
        record_id="r_000001",
        session_id="reconcile_2026-07-07_10-00-00",
    )

    assert list(row.keys()) == AI_WORKING_FIELDS
    assert row["record_id"] == "r_000001"
    assert row["session_id"] == "reconcile_2026-07-07_10-00-00"
    assert row["row_status"] == "active"
    assert row["ai_action"] == "leave_as_is"
    assert "needs_ai" not in row
    assert row["raw_counterparty"] == "麦当劳"
    assert row["raw_description"] == "麦当劳"


def test_write_and_read_ai_working_csv_preserves_field_order_and_values(tmp_path):
    path = tmp_path / "ai_working.csv"
    rows = [
        build_ai_working_row(
            {
                "date": "2026-06-12 10:00:03",
                "amount": "-30.00",
                "currency": "CNY",
                "counterparty": "麦当劳",
                "description": "麦当劳",
                "category": "expense",
                "account_name": "工行信用卡(1200)",
                "source": "支付宝",
                "bill_source": "alipay",
            },
            record_id="r_000001",
            session_id="s1",
        )
    ]

    write_ai_working_csv(path, rows)
    loaded = read_ai_working_csv(path)

    assert loaded == rows


def test_parse_ai_action_target_returns_action_and_target():
    assert parse_ai_action_target("merge_refund_into:c_000002") == ("merge_refund_into", "c_000002")
    assert parse_ai_action_target("leave_as_is") is None
