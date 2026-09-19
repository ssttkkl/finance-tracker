"""WeChat bill status constants shared by the converter pipeline."""

from pathlib import Path

INCOME_OK = {"已存入零钱", "已收钱"}
EXPENSE_OK = {"支付成功", "已转账", "对方已收钱"}
REQUIRED_HEADERS = frozenset({"交易时间", "收/支", "金额(元)"})


def can_parse_wechat(path) -> bool:
    """Return whether the first 50 XLSX rows contain a WeChat header."""
    if Path(path).suffix.lower() != ".xlsx":
        return False
    try:
        import openpyxl

        workbook = openpyxl.load_workbook(path, read_only=True, data_only=True)
    except Exception:  # noqa: BLE001 - a format probe treats unreadable files as no match.
        return False

    try:
        worksheet = workbook.active
        for row in worksheet.iter_rows(min_row=1, max_row=50, values_only=True):
            values = {str(value).strip() for value in row if value is not None}
            if REQUIRED_HEADERS.issubset(values):
                return True
        return False
    finally:
        workbook.close()
