"""数据模型常量"""
from pathlib import Path

from .schema import (
    ACCOUNT_ICONS,
    ACCOUNT_LABELS,
    ACCOUNT_TYPES,
    CASH_CSV_FIELDS,
    CATEGORIES,
    CATEGORY_LABELS,
    CRYPTO_IDS,
    CSV_FIELDS,
    CURRENCIES,
    CURRENCY_SYMBOLS,
    FOREIGN_EXCHANGE_KEYWORDS,
    SOURCE_LABELS,
    VALID_ACTIONS,
)

# 数据目录
FT_DIR = Path.home() / ".ft"
RECORDS_DIR = FT_DIR / "records"
ACCOUNTS_PATH = FT_DIR / "accounts.yaml"
PENDING_DIR = FT_DIR / "pending"


def month_key(date_str: str) -> str:
    return date_str[:7]


def records_month_path(record_type: str, date_str: str, records_dir: Path | None = None) -> Path:
    base = records_dir or RECORDS_DIR
    return Path(base) / record_type / f"{month_key(date_str)}.csv"
