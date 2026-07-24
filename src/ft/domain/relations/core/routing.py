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

from ft.domain.relations.core.geometry import _text_blob
from ft.domain.relations.core.types import BANK_CHANNEL_SOURCES, FactView, PAYMENT_PLATFORM_SOURCES
def source_group(fact: FactView) -> str:
    """Map channel fields to platform|bank|other (mirror only pairs platform×bank).

    Prefer formal ``bill_source``/``source`` (015: filled from ``source_type``).
    Only when both are empty, fall back to free-text cues on note/counterparty.
    """
    formal = _text_blob(fact.bill_source, fact.source)
    text_fb = _text_blob(
        getattr(fact, "counterparty", ""),
        getattr(fact, "note", ""),
        getattr(fact, "account_name", ""),
    )
    blob = formal if formal.strip() else text_fb

    def _classify(b: str) -> str | None:
        if any(token in b for token in ("alipay", "支付宝", "wechat", "weixin", "微信")):
            return "platform"
        if any(
            token in b
            for token in (
                "icbc", "ccb", "bank", "debit", "credit", "工行", "建行", "工商", "建设",
                "储蓄", "信用卡", "unionpay", "银联",
            )
        ):
            return "bank"
        if any(token in b for token in PAYMENT_PLATFORM_SOURCES):
            return "platform"
        if any(token in b for token in BANK_CHANNEL_SOURCES):
            return "bank"
        return None

    hit = _classify(blob)
    if hit:
        return hit
    # If formal channel was non-empty but unrecognized, do not let free-text flip it
    # unless formal empty already handled. Try text only as last resort when formal empty.
    if formal.strip():
        return "other"
    hit = _classify(text_fb)
    return hit or "other"

