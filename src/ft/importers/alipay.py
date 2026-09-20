"""Shared Alipay CSV encoding detection for the converter pipeline."""

import csv
from csv import reader
from io import StringIO
from pathlib import Path

ENCODINGS = ["utf-8", "gbk", "gb18030", "utf-8-sig"]
PROBE_MAX_BYTES = 256 * 1024
REQUIRED_HEADERS = frozenset({"交易时间", "收/支", "金额"})


def _detect_encoding(path):
    with open(path, "rb") as f:
        raw = f.read(4096)
    for encoding in ENCODINGS:
        try:
            raw.decode(encoding)
            return encoding
        except UnicodeDecodeError:
            continue
    return "gbk"


def can_parse_alipay(path) -> bool:
    """Return whether the bounded CSV prefix has an Alipay statement header."""
    try:
        encoding = _detect_encoding(path)
        with Path(path).open("rb") as stream:
            raw = stream.read(PROBE_MAX_BYTES)
        text = raw.decode(encoding)
    except (OSError, UnicodeError):
        return False

    try:
        for row in reader(StringIO(text)):
            if row:
                row[0] = row[0].removeprefix("\ufeff")
            if REQUIRED_HEADERS.issubset(row):
                return True
    except (csv.Error, ValueError):
        return False
    return False
