from collections import defaultdict

from .ai_working_csv import parse_ai_action_target


def _materialize_row(row: dict) -> dict:
    return {
        "date": row.get("date", ""),
        "amount": row.get("amount", ""),
        "currency": row.get("currency", ""),
        "counterparty": row.get("counterparty", ""),
        "description": row.get("description", ""),
        "category": row.get("category", ""),
        "account_name": row.get("account_name", ""),
        "source": row.get("source", ""),
        "bill_source": row.get("bill_source", ""),
        "transfer_account": row.get("transfer_account", ""),
        "locked": row.get("locked", ""),
    }


def apply_convert_working_rows(edited_rows: list[dict]) -> list[dict]:
    rows_by_id = {row["record_id"]: row for row in edited_rows}
    referenced_ids = set()
    for row in edited_rows:
        target = parse_ai_action_target(row.get("ai_action", "leave_as_is") or "leave_as_is")
        if target and target[0] in {"merge_refund_into", "net_with"}:
            referenced_ids.add(target[1])

    consumed_ids = set()
    final_rows = []

    for row in edited_rows:
        record_id = row["record_id"]
        if record_id in consumed_ids:
            continue
        if row.get("row_status", "active") == "dropped":
            consumed_ids.add(record_id)
            continue

        ai_action = row.get("ai_action", "leave_as_is") or "leave_as_is"
        if ai_action == "drop":
            consumed_ids.add(record_id)
            continue
        if record_id in referenced_ids and not parse_ai_action_target(ai_action):
            consumed_ids.add(record_id)
            continue

        target = parse_ai_action_target(ai_action)
        if target:
            action_name, target_id = target
            target_row = rows_by_id[target_id]
            if action_name in {"merge_refund_into", "net_with"}:
                amount = float(row.get("amount") or 0)
                if action_name == "merge_refund_into":
                    expense_row = target_row
                    refund_row = row
                else:
                    expense_row = row if amount < 0 else target_row
                    refund_row = row if amount > 0 else target_row
                net_amount = float(expense_row.get("amount") or 0) + float(refund_row.get("amount") or 0)
                if abs(net_amount) >= 0.005:
                    merged = dict(expense_row)
                    merged["amount"] = str(round(net_amount, 2))
                    final_rows.append(_materialize_row(merged))
                consumed_ids.add(record_id)
                consumed_ids.add(target_id)
                continue

        final_rows.append(_materialize_row(row))
        consumed_ids.add(record_id)

    return final_rows


def apply_reconcile_working_rows(edited_rows: list[dict]) -> tuple[dict[str, list[dict]], list[dict]]:
    rows_by_id = {row["record_id"]: row for row in edited_rows}
    extra_audit_rows = []
    by_file: dict[str, list[dict]] = defaultdict(list)

    for row in edited_rows:
        ai_action = row.get("ai_action", "leave_as_is") or "leave_as_is"
        if row.get("row_status", "active") == "dropped" or ai_action == "drop":
            if ai_action == "drop":
                extra_audit_rows.append({
                    **_materialize_row(row),
                    "record_file": row.get("record_file", ""),
                    "dedup_status": "去除",
                    "reconcile_status": "ai_drop",
                    "transfer_side": "",
                    "match_rule": "ai_dedup_decision",
                    "match_confidence": "ai",
                    "counterpart_file": "",
                    "counterpart_account": "",
                    "counterpart_currency": "",
                    "counterpart_amount": "",
                })
            continue

        materialized = _materialize_row(row)
        target = parse_ai_action_target(ai_action)
        if target:
            action_name, target_id = target
            target_row = rows_by_id[target_id]
            if action_name == "mark_transfer_out_to":
                materialized["category"] = "transfer_out"
                materialized["transfer_account"] = target_row.get("account_name", "")
                extra_audit_rows.append({
                    **materialized,
                    "record_file": row.get("record_file", ""),
                    "reconcile_status": "ai_transfer_matched",
                    "transfer_side": "out",
                    "match_rule": "ai_transfer_decision",
                    "match_confidence": "ai",
                    "counterpart_file": target_row.get("record_file", ""),
                    "counterpart_account": target_row.get("account_name", ""),
                    "counterpart_currency": target_row.get("currency", ""),
                    "counterpart_amount": target_row.get("amount", ""),
                })
            elif action_name == "mark_transfer_in_from":
                materialized["category"] = "transfer_in"
                materialized["transfer_account"] = target_row.get("account_name", "")
                extra_audit_rows.append({
                    **materialized,
                    "record_file": row.get("record_file", ""),
                    "reconcile_status": "ai_transfer_matched",
                    "transfer_side": "in",
                    "match_rule": "ai_transfer_decision",
                    "match_confidence": "ai",
                    "counterpart_file": target_row.get("record_file", ""),
                    "counterpart_account": target_row.get("account_name", ""),
                    "counterpart_currency": target_row.get("currency", ""),
                    "counterpart_amount": target_row.get("amount", ""),
                })

        by_file[row.get("record_file", "")].append(materialized)

    return by_file, extra_audit_rows
