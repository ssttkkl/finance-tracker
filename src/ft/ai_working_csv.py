import csv
from pathlib import Path


AI_WORKING_FIELDS = [
    "record_id", "source_record_id", "session_id", "date", "amount", "currency",
    "counterparty", "description", "category", "account_name",
    "source", "bill_source", "transfer_account", "locked",
    "offset_group", "offset_role", "offset_strength", "offset_source",
    "offset_rule_hint", "offset_match_type", "proposed_action",
    "raw_counterparty", "raw_description", "raw_payment_method",
    "record_file", "record_type",
    "row_status", "ai_action", "ai_group", "ai_reason", "decision_reason",
    "rule_hint",
]

READ_ONLY_FIELDS = {
    "record_id", "source_record_id", "session_id", "date", "amount", "currency", "bill_source",
    "raw_counterparty", "raw_description", "raw_payment_method",
}

EDITABLE_FIELDS = set(AI_WORKING_FIELDS) - READ_ONLY_FIELDS
ALLOWED_ROW_STATUS = {
    "active",
    "dropped",
    "drop_after_merge",
    "merged_net",
    "transfer_out",
    "transfer_in",
}
ALLOWED_AI_ACTIONS = {"leave_as_is", "keep", "drop", "modify"}
ACTION_PREFIXES = (
    "merge_refund_into:",
    "net_with:",
    "mark_transfer_out_to:",
    "mark_transfer_in_from:",
)


def is_allowed_ai_action(action: str) -> bool:
    if action in ALLOWED_AI_ACTIONS:
        return True
    return any(action.startswith(prefix) for prefix in ACTION_PREFIXES)


def parse_ai_action_target(action: str) -> tuple[str, str] | None:
    for prefix in ACTION_PREFIXES:
        if action.startswith(prefix):
            return prefix[:-1], action[len(prefix):]
    return None


def build_ai_working_row(row: dict, *, record_id: str, session_id: str, defaults: dict | None = None) -> dict:
    defaults = defaults or {}
    result = {
        "record_id": record_id,
        "source_record_id": row.get("record_id", ""),
        "session_id": session_id,
        "date": row.get("date", ""),
        "amount": str(row.get("amount", "")),
        "currency": row.get("currency", ""),
        "counterparty": row.get("counterparty", ""),
        "description": row.get("description", ""),
        "category": row.get("category", ""),
        "account_name": row.get("account_name", ""),
        "source": row.get("source", ""),
        "bill_source": row.get("bill_source", ""),
        "transfer_account": row.get("transfer_account", ""),
        "locked": row.get("locked", ""),
        "offset_group": row.get("offset_group", ""),
        "offset_role": row.get("offset_role", ""),
        "offset_strength": row.get("offset_strength", ""),
        "offset_source": row.get("offset_source", ""),
        "offset_rule_hint": row.get("offset_rule_hint", ""),
        "offset_match_type": row.get("offset_match_type", ""),
        "proposed_action": row.get("proposed_action", "leave_as_is"),
        "raw_counterparty": row.get("raw_counterparty", row.get("counterparty", "")),
        "raw_description": row.get("raw_description", row.get("description", "")),
        "raw_payment_method": row.get("raw_payment_method", row.get("payment_method", "")),
        "record_file": row.get("record_file", row.get("_record_file", "")),
        "record_type": row.get("record_type", row.get("_record_type", "")),
        "row_status": defaults.get("row_status", "active"),
        "ai_action": defaults.get("ai_action", "leave_as_is"),
        "ai_group": defaults.get("ai_group", ""),
        "ai_reason": defaults.get("ai_reason", ""),
        "decision_reason": "",
        "rule_hint": defaults.get("rule_hint", ""),
    }
    return result


def write_ai_working_csv(path: Path, rows: list[dict]):
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=AI_WORKING_FIELDS)
        writer.writeheader()
        writer.writerows([{field: row.get(field, "") for field in AI_WORKING_FIELDS} for row in rows])


def read_ai_working_csv(path: Path) -> list[dict]:
    with open(path, encoding="utf-8") as f:
        return list(csv.DictReader(f))
