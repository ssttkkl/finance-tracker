from ft.ai_working_csv import build_ai_working_row


def test_apply_convert_working_rows_merges_refund_into_net_row():
    from ft.ai_apply import apply_convert_working_rows

    rows = [
        build_ai_working_row(
            {
                "date": "2026-01-01 12:00:00",
                "amount": "-100.00",
                "currency": "CNY",
                "counterparty": "商家A",
                "description": "买书",
                "category": "expense",
                "account_name": "支付宝",
                "source": "支付宝",
                "bill_source": "alipay",
                "offset_group": "refund_000001",
                "offset_role": "expense",
                "offset_strength": "weak",
                "offset_source": "alipay_status",
                "offset_rule_hint": "refund_cp_match",
                "offset_match_type": "partial",
                "proposed_action": "leave_as_is",
            },
            record_id="c_000001",
            session_id="s1",
        ),
        build_ai_working_row(
            {
                "date": "2026-01-05 10:00:00",
                "amount": "30.00",
                "currency": "CNY",
                "counterparty": "商家A",
                "description": "退款",
                "category": "income",
                "account_name": "支付宝",
                "source": "支付宝",
                "bill_source": "alipay",
                "offset_group": "refund_000001",
                "offset_role": "refund",
                "offset_strength": "weak",
                "offset_source": "alipay_status",
                "offset_rule_hint": "refund_cp_match",
                "offset_match_type": "partial",
                "proposed_action": "merge_refund_into:c_000001",
            },
            record_id="c_000002",
            session_id="s1",
            defaults={"suggested_action": "merge_refund_into:c_000001"},
        ),
    ]
    rows[1]["decision_action"] = "merge_refund_into:c_000001"

    final_rows = apply_convert_working_rows(rows)

    assert len(final_rows) == 1
    assert final_rows[0]["record_id"] == "c_000001"
    assert final_rows[0]["amount"] == "-70.0"
    assert final_rows[0]["category"] == "expense"
    assert final_rows[0]["offset_group"] == "refund_000001"
    assert final_rows[0]["offset_role"] == "expense"
    assert final_rows[0]["offset_strength"] == "weak"
    assert final_rows[0]["offset_source"] == "alipay_status"
    assert final_rows[0]["offset_rule_hint"] == "refund_cp_match"
    assert final_rows[0]["offset_match_type"] == "partial"
    assert final_rows[0]["proposed_action"] == "leave_as_is"


def test_apply_convert_working_rows_keeps_refund_fact_when_no_ai_merge():
    from ft.ai_apply import apply_convert_working_rows

    rows = [
        build_ai_working_row(
            {
                "record_id": "c_000001",
                "date": "2026-01-01 12:00:00",
                "amount": "-100.00",
                "currency": "CNY",
                "counterparty": "商家A",
                "description": "买书",
                "category": "expense",
                "account_name": "支付宝",
                "source": "支付宝",
                "bill_source": "alipay",
                "offset_group": "refund_000001",
                "offset_role": "expense",
                "offset_strength": "weak",
                "offset_source": "alipay_status",
                "offset_rule_hint": "refund_cp_match",
                "offset_match_type": "partial",
                "proposed_action": "leave_as_is",
            },
            record_id="c_000001",
            session_id="s1",
        ),
        build_ai_working_row(
            {
                "record_id": "c_000002",
                "date": "2026-01-05 10:00:00",
                "amount": "30.00",
                "currency": "CNY",
                "counterparty": "商家A",
                "description": "退款",
                "category": "income",
                "account_name": "支付宝",
                "source": "支付宝",
                "bill_source": "alipay",
                "offset_group": "refund_000001",
                "offset_role": "refund",
                "offset_strength": "weak",
                "offset_source": "alipay_status",
                "offset_rule_hint": "refund_cp_match",
                "offset_match_type": "partial",
                "proposed_action": "merge_refund_into:c_000001",
            },
            record_id="c_000002",
            session_id="s1",
        ),
    ]

    final_rows = apply_convert_working_rows(rows)
    assert len(final_rows) == 2
    assert {r["category"] for r in final_rows} == {"expense", "income"}
    expense = next(r for r in final_rows if r["category"] == "expense")
    refund = next(r for r in final_rows if r["category"] == "income")
    assert expense["record_id"] == "c_000001"
    assert expense["offset_group"] == "refund_000001"
    assert expense["offset_role"] == "expense"
    assert expense["offset_strength"] == "weak"
    assert expense["offset_rule_hint"] == "refund_cp_match"
    assert expense["offset_match_type"] == "partial"
    assert refund["record_id"] == "c_000002"
    assert refund["offset_group"] == "refund_000001"
    assert refund["offset_role"] == "refund"
    assert refund["offset_strength"] == "weak"
    assert refund["offset_rule_hint"] == "refund_cp_match"
    assert refund["offset_match_type"] == "partial"
    assert refund["proposed_action"] == "merge_refund_into:c_000001"


def test_apply_reconcile_working_rows_marks_transfer_and_collects_ai_drop_audit():
    from ft.ai_apply import apply_reconcile_working_rows

    rows = [
        build_ai_working_row(
            {
                "date": "2026-06-12 10:00:00",
                "amount": "-100.00",
                "currency": "CNY",
                "counterparty": "微信",
                "description": "转账支取",
                "category": "expense",
                "account_name": "支付宝余额",
                "source": "支付宝",
                "bill_source": "alipay",
                "record_file": "/tmp/cash.csv",
            },
            record_id="r_000001",
            session_id="s2",
            defaults={"suggested_action": "mark_transfer_out_to:r_000002"},
        ),
        build_ai_working_row(
            {
                "date": "2026-06-12 10:00:02",
                "amount": "100.00",
                "currency": "CNY",
                "counterparty": "微信",
                "description": "银联入账",
                "category": "income",
                "account_name": "微信零钱",
                "source": "微信",
                "bill_source": "wechat",
                "record_file": "/tmp/cash.csv",
            },
            record_id="r_000002",
            session_id="s2",
            defaults={"suggested_action": "mark_transfer_in_from:r_000001"},
        ),
        build_ai_working_row(
            {
                "date": "2026-06-12 10:00:03",
                "amount": "-30.00",
                "currency": "CNY",
                "counterparty": "麦当劳",
                "description": "麦当劳",
                "category": "expense",
                "account_name": "工行信用卡(1200)",
                "source": "银行卡",
                "bill_source": "icbc_credit",
                "record_file": "/tmp/loan.csv",
            },
            record_id="r_000003",
            session_id="s2",
            defaults={"suggested_action": "drop"},
        ),
    ]
    rows[0]["decision_action"] = "mark_transfer_out_to:r_000002"
    rows[1]["decision_action"] = "mark_transfer_in_from:r_000001"
    rows[2]["decision_action"] = "drop"

    by_file, extra_audit_rows = apply_reconcile_working_rows(rows)

    assert by_file["/tmp/cash.csv"][0]["category"] == "transfer_out"
    assert by_file["/tmp/cash.csv"][0]["transfer_account"] == "微信零钱"
    assert by_file["/tmp/cash.csv"][1]["category"] == "transfer_in"
    assert by_file["/tmp/cash.csv"][1]["transfer_account"] == "支付宝余额"
    assert any(row["reconcile_status"] == "ai_drop" for row in extra_audit_rows)
    assert any(row["reconcile_status"] == "ai_transfer_matched" for row in extra_audit_rows)
