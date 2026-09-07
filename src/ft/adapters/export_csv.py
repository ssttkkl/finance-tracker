"""CSV 交换文件适配器。

CSV 输出由 API/Web 的导出流程调用；本模块不提供命令行入口。
"""

import csv
import json


def _csv_value(value):
    if isinstance(value, (dict, list, tuple)):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return value


def write_csv_export(payload, output):
    fieldnames = list(payload.fieldnames)
    if not fieldnames and payload.rows:
        fieldnames = list(payload.rows[0].keys())
    with open(output, "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(
            {key: _csv_value(value) for key, value in row.items()}
            for row in payload.rows
        )
