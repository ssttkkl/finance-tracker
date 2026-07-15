from ft.refund_reconcile import resolve_refund_relations, settle_refund_relations


def _expense(record_id: str, amount: float) -> dict:
    return {
        "record_id": record_id,
        "date": "2026-06-01 10:00:00",
        "amount": str(amount),
        "currency": "CNY",
        "counterparty": "商户",
        "description": "消费",
        "category": "expense",
        "account_name": "支付宝余额",
        "offset_group": "refund_000001",
        "offset_role": "expense",
        "offset_strength": "strong",
        "offset_source": "alipay_status",
        "offset_rule_hint": "refund_cp_match",
        "offset_match_type": "partial",
        "proposed_action": "leave_as_is",
    }


def _refund(record_id: str, amount: float, expense_id: str, strength: str = "strong") -> dict:
    return {
        "record_id": record_id,
        "date": "2026-06-02 10:00:00",
        "amount": str(amount),
        "currency": "CNY",
        "counterparty": "商户",
        "description": "退款",
        "category": "income",
        "account_name": "支付宝余额",
        "offset_group": "refund_000001",
        "offset_role": "refund",
        "offset_strength": strength,
        "offset_source": "alipay_status",
        "offset_rule_hint": "refund_cp_match",
        "offset_match_type": "partial",
        "proposed_action": f"merge_refund_into:{expense_id}",
    }


def test_strong_partial_refund_keeps_net_expense_and_removes_refund():
    expense = _expense("expense", -100)
    refund = _refund("refund", 30, "expense")
    relations, pending, _audit = resolve_refund_relations([expense, refund], [expense, refund], {})

    result, audit_rows = settle_refund_relations([expense, refund], relations)

    assert pending == []
    assert [(row["record_id"], row["amount"]) for row in result] == [("expense", "-70.0")]
    assert result[0]["offset_group"] == ""
    assert result[0]["proposed_action"] == "leave_as_is"
    assert {row["record_id"] for row in audit_rows} == {"expense", "refund"}
    assert {row["reconcile_status"] for row in audit_rows} == {"refund_partial_auto"}


def test_full_refund_removes_both_records():
    expense = _expense("expense", -100)
    refund = _refund("refund", 100, "expense")
    relations, pending, _audit = resolve_refund_relations([expense, refund], [expense, refund], {})

    result, audit_rows = settle_refund_relations([expense, refund], relations)

    assert pending == []
    assert result == []
    assert {row["record_id"] for row in audit_rows} == {"expense", "refund"}
    assert {row["reconcile_status"] for row in audit_rows} == {"refund_full_auto"}


def test_deleted_expense_relation_is_rebound_to_kept_expense():
    dropped_expense = _expense("dropped", -100)
    kept_expense = _expense("kept", -100)
    refund = _refund("refund", 100, "dropped")

    relations, pending, audit_rows = resolve_refund_relations(
        [dropped_expense, kept_expense, refund],
        [kept_expense, refund],
        {"dropped": "kept"},
    )

    assert [(pair.refund_id, pair.expense_id) for pair in relations] == [("refund", "kept")]
    assert pending == []
    assert audit_rows[0]["reconcile_status"] == "refund_rebound_after_dedup"
    assert audit_rows[0]["counterpart_record_id"] == "kept"
