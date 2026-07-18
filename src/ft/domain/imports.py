"""Commands and schemas for cashflow import use cases."""
from dataclasses import dataclass


CASHFLOW_EXPORT_FIELDS = (
    "record_id", "date", "amount", "currency", "counterparty",
    "description", "category", "account_name", "source", "bill_source",
    "offset_group", "offset_role", "offset_strength", "offset_source",
    "offset_rule_hint", "offset_match_type", "proposed_action",
)


@dataclass(frozen=True)
class StatementImportCommand:
    source_path: str
    source: str
    account: str
    currency: str = "CNY"
    password: str | None = None
