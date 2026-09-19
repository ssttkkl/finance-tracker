"""WeChat bill status constants shared by the converter pipeline."""

from pathlib import Path

INCOME_OK = {"已存入零钱", "已收钱"}
EXPENSE_OK = {"支付成功", "已转账", "对方已收钱"}
REQUIRED_HEADERS = frozenset({"交易时间", "收/支", "金额(元)"})
PROBE_MAX_ROWS = 50
PROBE_MAX_COLUMNS = 128


def can_parse_wechat(path) -> bool:
    """Return whether a bounded XLSX prefix contains a WeChat header."""
    stream = None
    workbook = None
    try:
        import openpyxl

        stream = Path(path).open("rb")
        workbook = openpyxl.load_workbook(stream, read_only=True, data_only=True)
    except Exception:  # noqa: BLE001 - a format probe treats unreadable files as no match.
        if stream is not None:
            stream.close()
        return False

    try:
        worksheet = workbook.active
        for row in worksheet.iter_rows(
            min_row=1,
            max_row=PROBE_MAX_ROWS,
            max_col=PROBE_MAX_COLUMNS,
            values_only=True,
        ):
            values = {str(value).strip() for value in row if value is not None}
            if REQUIRED_HEADERS.issubset(values):
                return True
        return False
    finally:
        workbook.close()
        stream.close()
