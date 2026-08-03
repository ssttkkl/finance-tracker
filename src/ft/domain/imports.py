"""Commands and schemas for cashflow import use cases."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


CASHFLOW_EXPORT_FIELDS = (
    "record_id", "occurred_at", "amount", "currency", "counterparty",
    "counterparty_account", "note", "category", "record_type", "account_name", "source_type",
)


def infer_statement_source(source_path: str) -> str:
    """Best-effort channel name from filename (tests/CLI may omit explicit source)."""
    name = Path(source_path).name.lower()
    if "currentaccounthistory" in name:
        return "icbc-asia-current-account"
    if "alipay" in name or "支付宝" in name:
        return "alipay"
    if "wechat" in name or "微信" in name:
        return "wechat"
    if "ccb" in name or "建设" in name:
        return "ccb-debit"
    if "icbc" in name or "工商" in name:
        if "debit" in name or "借记" in name:
            return "icbc-debit"
        return "icbc"
    if "dfzq" in name or "东方" in name:
        return "dfzq"
    if "usmart" in name:
        return "usmart-hk"
    if "schwab" in name:
        return "schwab"
    if "ibkr" in name or "ib." in name:
        return "ibkr"
    return "alipay"


@dataclass(frozen=True)
class StatementImportCommand:
    source_path: str
    source: str = ""
    currency: str | None = None
    password: str | None = None

    def __post_init__(self):
        if not (self.source or "").strip():
            object.__setattr__(self, "source", infer_statement_source(self.source_path))
