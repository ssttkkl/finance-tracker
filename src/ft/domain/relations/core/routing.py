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
    """Map bill_source/source to platform|bank|other (mirror only pairs platform×bank)."""
    blob = _text_blob(fact.bill_source, fact.source)
    if any(token in blob for token in ("alipay", "支付宝", "wechat", "weixin", "微信")):
        return "platform"
    if any(
        token in blob
        for token in (
            "icbc", "ccb", "bank", "debit", "credit", "工行", "建行", "工商", "建设",
            "储蓄", "信用卡", "unionpay", "银联",
        )
    ):
        return "bank"
    # Fall back on known enum sets used elsewhere.
    if any(token in blob for token in PAYMENT_PLATFORM_SOURCES):
        return "platform"
    if any(token in blob for token in BANK_CHANNEL_SOURCES):
        return "bank"
    return "other"


