from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone, timedelta, date
from zoneinfo import ZoneInfo
from decimal import Decimal
from enum import Enum
from typing import Any, Callable, Iterable, Mapping, Sequence
import bisect
import re

WORKSPACE_TZ = ZoneInfo("Asia/Shanghai")

def _as_decimal(value) -> Decimal:
    if isinstance(value, Decimal):
        return value
    return Decimal(str(value))


def _abs_decimal(value) -> Decimal:
    amount = _as_decimal(value)
    return amount if amount >= 0 else -amount


def _parse_dt(value) -> datetime:
    if isinstance(value, datetime):
        dt = value
    else:
        text = str(value or "").strip()
        if not text:
            raise ValueError("occurred_at is required")
        if "T" not in text and " " in text:
            text = text.replace(" ", "T", 1)
        dt = datetime.fromisoformat(text)
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def _time_delta_seconds(a, b) -> int:
    return abs(int((_parse_dt(a) - _parse_dt(b)).total_seconds()))


def _same_calendar_day(a, b) -> bool:
    return _parse_dt(a).date() == _parse_dt(b).date()


def _business_raw_date_string(fact: "FactView") -> str:
    """Prefer raw_payload date (source export), then formal date/occurred_at."""
    payload = fact.raw_payload if isinstance(getattr(fact, "raw_payload", None), dict) else {}
    for key in ("date", "occurred_at", "交易时间", "交易日期", "记账日期"):
        val = payload.get(key) if payload else None
        if val not in (None, ""):
            return str(val).strip()
    # formal fields
    if getattr(fact, "occurred_at", None) not in (None, ""):
        return str(fact.occurred_at).strip()
    return ""


def is_date_only_business_string(text: str) -> bool:
    """True for export dates with no real clock time.

    FR-052/053: raw ``YYYY-MM-DD`` (len 10) or ``YYYYMMDD`` is date-only.
    Full datetimes (len > 10 with time) are not date-only.
    """
    s = str(text or "").strip()
    if not s:
        return False
    # Explicit: length-10 ISO date always date-only
    if len(s) == 10 and re.fullmatch(r"\d{4}-\d{2}-\d{2}", s):
        return True
    if re.fullmatch(r"\d{8}", s):
        return True
    # Date prefix + midnight-only time (no meaningful clock) still date-only
    m = re.fullmatch(
        r"(\d{4}-\d{2}-\d{2})[ T](00:00:00|00:00)(?:\.0+)?(?:Z|[+-]\d{2}:?\d{2})?",
        s,
    )
    if m:
        return True
    return False


def business_day_shanghai(fact: "FactView") -> date | None:
    """Calendar day in Asia/Shanghai for pairing (FR-052)."""
    raw = _business_raw_date_string(fact)
    if not raw:
        return None
    # date only
    m = re.match(r"^(\d{4})-(\d{2})-(\d{2})$", raw)
    if m:
        return date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
    m = re.match(r"^(\d{4})(\d{2})(\d{2})$", raw)
    if m:
        return date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
    # full datetime string — interpret naive as Shanghai
    text = raw.replace("/", "-")
    if "T" not in text and " " in text:
        text = text.replace(" ", "T", 1)
    # strip fractional
    text = text.split(".")[0]
    try:
        if len(text) == 10:
            return date.fromisoformat(text)
        dt = datetime.fromisoformat(text)
    except ValueError:
        try:
            dt = _parse_dt(raw)
            return dt.astimezone(WORKSPACE_TZ).date()
        except Exception:
            return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=WORKSPACE_TZ)
    return dt.astimezone(WORKSPACE_TZ).date()


def fact_is_bank_date_only(fact: "FactView") -> bool:
    """True when the bank export business date has no clock time (FR-053).

    Priority:
    1. raw_payload ``date`` (or 交易日期/记账日期) — if YYYY-MM-DD (len 10) → True
    2. raw_payload without time → True
    3. Do **not** require occurred_at 16:00 fallback when raw date-only is present
    4. Fallback only when raw missing: bank source + formal sentinel 16:00/00:00
    """
    payload = fact.raw_payload if isinstance(getattr(fact, "raw_payload", None), dict) else {}
    raw = ""
    if payload:
        # Prefer pure business date keys first (not formalized occurred_at)
        for key in ("date", "交易日期", "记账日期", "occurred_at"):
            if payload.get(key) not in (None, ""):
                raw = str(payload.get(key)).strip()
                break
    if raw:
        # len-10 YYYY-MM-DD is always date-only (user requirement)
        if len(raw) == 10 and re.fullmatch(r"\d{4}-\d{2}-\d{2}", raw):
            return True
        if is_date_only_business_string(raw):
            return True
        # raw has real clock time → not date-only
        return False
    # Fallback: no raw date — bank formal UTC-midnight / 16:00 Shanghai sentinel
    from ft.domain.relations.core.routing import source_group
    if source_group(fact) != "bank":
        return False
    s = str(fact.occurred_at or "")
    return "16:00:00" in s or s.endswith("00:00:00") or "T16:00:00" in s


def same_business_day_shanghai(a: "FactView", b: "FactView") -> bool:
    da, db = business_day_shanghai(a), business_day_shanghai(b)
    return da is not None and db is not None and da == db


def _text_blob(*parts: str) -> str:
    return " ".join(str(p or "") for p in parts).lower()


def extract_card_tails(text: str) -> set[str]:
    blob = str(text or "")
    tails: set[str] = set()
    for match in re.finditer(r"(?:尾号|卡号后四位|卡尾|ending)\s*[:：]?\s*(\d{4})", blob, re.I):
        tails.add(match.group(1))
    for match in re.finditer(r"[*＊]{2,}(\d{4})", blob):
        tails.add(match.group(1))
    for match in re.finditer(r"(?<!\d)(\d{4})(?!\d)", blob):
        start = max(0, match.start() - 12)
        window = blob[start:match.end() + 4]
        if any(token in window for token in ("尾号", "卡", "card", "支付", "储蓄", "信用")):
            tails.add(match.group(1))
    return tails




def main_style_cross_verify(left: Mapping[str, Any] | str | object, right: Mapping[str, Any] | str | object) -> bool:
    """Main-branch dedup text gate: non-empty counterparty/description bidirectional substring."""
    def parts(value) -> tuple[str, str]:
        if isinstance(value, Mapping):
            return str(value.get("counterparty") or ""), str(value.get("note") or "")
        # FactView-like duck type
        if hasattr(value, "counterparty") and hasattr(value, "description") and not isinstance(value, str):
            return str(getattr(value, "counterparty") or ""), str(getattr(value, "description") or "")
        text = str(value or "")
        return text, ""

    ca, da = parts(left)
    cb, db = parts(right)
    ca = ca.rstrip("…").rstrip("...")
    cb = cb.rstrip("…").rstrip("...")
    if ca and cb and (ca in cb or cb in ca):
        return True
    if da and db and (da in db or db in da):
        return True
    return False


