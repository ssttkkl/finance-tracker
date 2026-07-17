"""Shared Alipay CSV encoding detection for the converter pipeline."""

ENCODINGS = ["utf-8", "gbk", "gb18030", "utf-8-sig"]


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
