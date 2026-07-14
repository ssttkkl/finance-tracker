import pytest

from ft.ai_working_csv import (
    AI_WORKING_FIELDS,
    build_ai_working_row,
    parse_decision_action_target,
    write_ai_working_csv,
    read_ai_working_csv,
)


def test_build_ai_working_row_separates_system_hints_from_decisions(tmp_path):
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
        defaults={
            "rule_hint": "possible_mirror_weak_30s_cross_source",
            "suggested_action": "drop",
        },
    )

    assert list(row.keys()) == AI_WORKING_FIELDS
    assert row["record_id"] == "r_000001"
    assert row["session_id"] == "reconcile_2026-07-07_10-00-00"
    assert row["rule_hint"] == "possible_mirror_weak_30s_cross_source"
    assert row["suggested_action"] == "drop"
    assert row["decision_action"] == "leave_as_is"
    assert row["decision_reason"] == ""
    assert row["processing_status"] == "active"
    assert {"ai_reason", "ai_action", "row_status"}.isdisjoint(row)
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


def test_read_ai_working_csv_rejects_legacy_decision_fields(tmp_path):
    path = tmp_path / "legacy.csv"
    path.write_text("record_id,ai_action,row_status,ai_reason\nr_000001,drop,active,hint\n", encoding="utf-8")

    with pytest.raises(ValueError, match="已废弃字段"):
        read_ai_working_csv(path)


def test_parse_decision_action_target_returns_action_and_target():
    assert parse_decision_action_target("merge_refund_into:c_000002") == ("merge_refund_into", "c_000002")
    assert parse_decision_action_target("leave_as_is") is None
