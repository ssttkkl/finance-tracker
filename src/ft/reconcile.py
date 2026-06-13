"""reconcile — post-import duplicate removal and audit output"""
import csv
from collections import defaultdict
from datetime import datetime, date, timedelta
from pathlib import Path

from . import models
from .dedup import dedup_with_pairs
from .snapshot import rebuild_snapshot_from_records, git_stage


def _parse_scope(month=None, date_from=None, date_to=None):
    if month:
        start = datetime.strptime(month + "-01", "%Y-%m-%d").date()
        year, mon = map(int, month.split("-"))
        if mon == 12:
            end = date(year, 12, 31)
        else:
            end = date(year, mon + 1, 1) - timedelta(days=1)
        return start, end
    start = datetime.strptime(date_from, "%Y-%m-%d").date() if date_from else None
    end = datetime.strptime(date_to, "%Y-%m-%d").date() if date_to else None
    if start and end and start > end:
        raise ValueError("❌ --from 不能晚于 --to")
    return start, end


def _in_scope(date_text: str, start: date | None, end: date | None) -> bool:
    row_day = datetime.strptime(date_text[:10], "%Y-%m-%d").date()
    if start and row_day < start:
        return False
    if end and row_day > end:
        return False
    return True


def _audit_path(run_at: str) -> Path:
    audit_dir = models.FT_DIR / "audit" / "reconcile"
    audit_dir.mkdir(parents=True, exist_ok=True)
    return audit_dir / f"{run_at}.csv"


def _write_audit(run_at: str, scope_from: str, scope_to: str, pairs: list[tuple[dict, dict]]) -> Path:
    path = _audit_path(run_at)
    fields = [
        "run_at", "scope_from", "scope_to", "date", "amount", "currency",
        "counterparty", "description", "category", "account_name", "source",
        "bill_source", "record_file", "dedup_status",
    ]
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        for keep_row, remove_row in pairs:
            keep = {k: v for k, v in keep_row.items() if not k.startswith("_")}
            remove = {k: v for k, v in remove_row.items() if not k.startswith("_")}
            writer.writerow({
                "run_at": run_at,
                "scope_from": scope_from or "",
                "scope_to": scope_to or "",
                **keep,
                "record_file": keep_row.get("_record_file", ""),
                "dedup_status": "保留",
            })
            writer.writerow({
                "run_at": run_at,
                "scope_from": scope_from or "",
                "scope_to": scope_to or "",
                **remove,
                "record_file": remove_row.get("_record_file", ""),
                "dedup_status": "去除",
            })
    return path


def do_reconcile(*, month=None, date_from=None, date_to=None):
    start, end = _parse_scope(month=month, date_from=date_from, date_to=date_to)
    if start and end:
        scope_from = start.isoformat()
        scope_to = end.isoformat()
    elif start:
        scope_from = start.isoformat()
        scope_to = ""
    elif end:
        scope_from = ""
        scope_to = end.isoformat()
    else:
        scope_from = ""
        scope_to = ""

    entries: list[dict] = []
    for typ in ("cash", "loan"):
        type_dir = models.RECORDS_DIR / typ
        if not type_dir.exists():
            continue
        for csv_file in sorted(type_dir.glob("*.csv")):
            with open(csv_file, encoding="utf-8") as f:
                for row in csv.DictReader(f):
                    row = dict(row)
                    row["_record_file"] = str(csv_file)
                    row["_record_type"] = typ
                    entries.append(row)

    scoped = [row for row in entries if _in_scope(row["date"], start, end)]
    kept, removed, pairs = dedup_with_pairs(scoped)
    if not removed:
        print("无重复项")
        return

    rows_by_file: dict[str, list[dict]] = defaultdict(list)
    for row in entries:
        if row in scoped:
            continue
        clean = {k: v for k, v in row.items() if not k.startswith("_")}
        rows_by_file[row["_record_file"]].append(clean)

    for row in kept:
        clean = {k: v for k, v in row.items() if not k.startswith("_")}
        rows_by_file[row["_record_file"]].append(clean)

    touched_files = sorted({row["_record_file"] for row in scoped})
    for file_path_str in touched_files:
        file_path = Path(file_path_str)
        final_rows = rows_by_file.get(file_path_str, [])
        if not final_rows:
            if file_path.exists():
                file_path.unlink()
            continue
        final_rows.sort(key=lambda r: r["date"])
        with open(file_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=models.CSV_FIELDS)
            writer.writeheader()
            writer.writerows(final_rows)

    rebuild_snapshot_from_records(models.RECORDS_DIR)
    run_at = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    audit_path = _write_audit(run_at, scope_from, scope_to, pairs)
    print(f"✅ 去重完成，审计文件: {audit_path}")
    git_stage(models.FT_DIR)
