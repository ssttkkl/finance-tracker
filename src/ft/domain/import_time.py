"""Import timestamp normalization shared by parser and relation planning."""
from __future__ import annotations

from datetime import datetime, timezone
from zoneinfo import ZoneInfo


SOURCE_TIMEZONES = {
    "alipay": "Asia/Shanghai",
    "wechat": "Asia/Shanghai",
    "icbc_credit": "Asia/Shanghai",
    "icbc_debit": "Asia/Shanghai",
    "ccb_debit": "Asia/Shanghai",
    "icbc_asia": "Asia/Hong_Kong",
}


def normalize_timestamp(value, *, default_timezone: str = "UTC") -> str:
    """Return an ISO-8601 UTC timestamp with an explicit offset."""
    if isinstance(value, datetime):
        parsed = value
    else:
        text = str(value or "").strip()
        if not text:
            raise ValueError("账单时间为空")
        try:
            parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
        except ValueError as exc:
            raise ValueError("账单时间无法解析") from exc
    if parsed.tzinfo is None:
        try:
            parsed = parsed.replace(tzinfo=ZoneInfo(default_timezone))
        except Exception as exc:  # noqa: BLE001 - invalid runtime timezone is configuration failure.
            raise ValueError("账单来源时区无效") from exc
    return parsed.astimezone(timezone.utc).isoformat()


def normalize_statement_timestamp(value, *, source: str) -> str:
    """Normalize a statement timestamp using its declared source timezone."""
    source_key = str(source or "").strip().lower()
    return normalize_timestamp(
        value,
        default_timezone=SOURCE_TIMEZONES.get(source_key, "UTC"),
    )
