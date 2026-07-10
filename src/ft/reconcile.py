"""reconcile — post-import duplicate removal and audit output"""
import csv
import re
from collections import defaultdict
from datetime import datetime, date, timedelta
from pathlib import Path

from . import models
from .accounts import load_accounts
from .dedup import dedup_with_pairs, dedup_cross_source
from .snapshot import rebuild_snapshot_from_records, git_stage
from .transfer_rules import classify_single_leg


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


TIME_RE = re.compile(r"\b([01]?\d|2[0-3]):[0-5]\d:[0-5]\d\b")


def _normal_row(row: dict) -> dict:
    return {field: row.get(field, "") for field in models.CSV_FIELDS}


def _clean_row(row: dict) -> dict:
    return _normal_row({k: v for k, v in row.items() if not k.startswith("_")})


def _effective_datetime(row: dict) -> datetime:
    dt = datetime.strptime(row["date"], "%Y-%m-%d %H:%M:%S")
    if dt.time() != datetime.min.time():
        return dt
    text = " ".join([
        row.get("counterparty", ""),
        row.get("description", ""),
        row.get("source", ""),
        row.get("bill_source", ""),
    ])
    match = TIME_RE.search(text)
    if not match:
        return dt
    hour, minute, second = map(int, match.group(0).split(":"))
    return dt.replace(hour=hour, minute=minute, second=second)


def _amount(row: dict) -> float:
    return float(row.get("amount") or 0)


def _is_locked(row: dict) -> bool:
    """locked=1 表示该行被人工锁定，reconcile 完全不处理它。"""
    return str(row.get("locked", "")).strip() == "1"


def _account_key(row: dict) -> tuple[str, str]:
    return (row.get("account_name", ""), row.get("currency", ""))


def _search_text(row: dict) -> str:
    return " ".join([
        row.get("counterparty", ""),
        row.get("description", ""),
        row.get("source", ""),
        row.get("bill_source", ""),
        row.get("account_name", ""),
    ])


def _has_signal(row_a: dict, row_b: dict, signals=("转账支取", "转账存入", "银联入账", "手机银行", "转帐", "还款", "花呗", "月付", "提现-实时提现")) -> bool:
    text = _search_text(row_a) + " " + _search_text(row_b)
    return any(signal in text for signal in signals)


def _mark_transfer(out_row: dict, in_row: dict, rule: str) -> tuple[dict, dict]:
    out_row["category"] = "transfer_out"
    out_row["transfer_account"] = in_row.get("account_name", "")
    out_row["_transfer_rule"] = rule
    in_row["category"] = "transfer_in"
    in_row["transfer_account"] = out_row.get("account_name", "")
    in_row["_transfer_rule"] = rule
    return out_row, in_row


def _account_type_map() -> dict[tuple[str, str], str]:
    return {(a["name"], a["currency"]): a["type"] for a in load_accounts()}


def _mark_single_leg_transfers(rows: list[dict], used_ids: set[int]) -> list[tuple[dict, str]]:
    """标记单腿内部转账（对手方为基金公司/购汇/货基，永远配不上对）。

    只处理尚未被配对逻辑标记的 income/expense 记录。改 category 为
    transfer_out/transfer_in，transfer_account 留空（无自有对手账户），
    记录 _transfer_rule 供审计。返回 [(row, rule), ...]。
    """
    marked = []
    for row in rows:
        if id(row) in used_ids:
            continue
        result = classify_single_leg(row)
        if result is None:
            continue
        side, rule = result
        row["category"] = side
        row["transfer_account"] = ""
        row["_transfer_rule"] = rule
        used_ids.add(id(row))
        marked.append((row, rule))
    return marked



def _match_same_currency_exact(rows: list[dict]) -> list[tuple[dict, dict, str]]:
    matches = []
    used = set()
    candidates = [
        row for row in rows
        if row.get("category") in ("income", "expense") and abs(_amount(row)) > 0
    ]
    for out_row in sorted([r for r in candidates if _amount(r) < 0], key=lambda r: r["date"]):
        if id(out_row) in used:
            continue
        possible = []
        for in_row in candidates:
            if id(in_row) in used or _amount(in_row) <= 0:
                continue
            if _account_key(out_row) == _account_key(in_row):
                continue
            if out_row.get("currency") != in_row.get("currency"):
                continue
            if abs(abs(_amount(out_row)) - abs(_amount(in_row))) > 0.01:
                continue
            diff = abs((_effective_datetime(out_row) - _effective_datetime(in_row)).total_seconds())
            if diff > 10:
                continue
            if not _has_signal(out_row, in_row):
                continue
            possible.append((diff, in_row))
        if len(possible) != 1:
            continue
        _diff, in_row = possible[0]
        used.add(id(out_row))
        used.add(id(in_row))
        matches.append((out_row, in_row, "same_currency_exact"))
    return matches


def _is_unionpay_wechat_cash_signal(out_row: dict, in_row: dict) -> bool:
    """同日宽窗口现金账户调拨信号：银联入账 ↔ 无卡付/转账支取。"""
    out_text = _search_text(out_row)
    in_text = _search_text(in_row)
    return (
        ("银联入账" in in_text or "电子汇入" in in_text)
        and any(k in out_text for k in ("无卡付", "转账支取"))
    )


def _match_same_day_unionpay_cash_transfer(rows: list[dict], used_ids: set[int]) -> list[tuple[dict, dict, str]]:
    """同日同额、跨现金账户、强银联/微信/云闪付信号的宽窗口转账。

    银行账单入账腿常为 00:00:00，而出账腿有真实时分秒，超过 ±10 秒。
    为避免误伤消费，只接受“银联入账/电子汇入”与“无卡付/转账支取”的组合，且必须唯一匹配。
    """
    acct_types = _account_type_map()
    matches = []
    candidates = [
        row for row in rows
        if id(row) not in used_ids
        and row.get("category") in ("income", "expense")
        and abs(_amount(row)) > 0
        and acct_types.get(_account_key(row)) == "cash"
    ]
    for out_row in sorted([r for r in candidates if _amount(r) < 0], key=_effective_datetime):
        if id(out_row) in used_ids:
            continue
        possible = []
        for in_row in candidates:
            if id(in_row) in used_ids or _amount(in_row) <= 0:
                continue
            if _account_key(out_row) == _account_key(in_row):
                continue
            if out_row.get("currency") != in_row.get("currency"):
                continue
            if abs(abs(_amount(out_row)) - abs(_amount(in_row))) > 0.01:
                continue
            if _effective_datetime(out_row).date() != _effective_datetime(in_row).date():
                continue
            if not _is_unionpay_wechat_cash_signal(out_row, in_row):
                continue
            diff = abs((_effective_datetime(out_row) - _effective_datetime(in_row)).total_seconds())
            possible.append((diff, in_row))
        if len(possible) != 1:
            continue
        _diff, in_row = sorted(possible, key=lambda x: x[0])[0]
        used_ids.add(id(out_row))
        used_ids.add(id(in_row))
        matches.append((out_row, in_row, "same_day_unionpay_cash_transfer"))
    return matches


def _match_same_currency_cash_loan_repayment(rows: list[dict], used_ids: set[int]) -> list[tuple[dict, dict, str]]:
    """同币种 cash→loan 还款，允许分钟级延迟。"""
    acct_types = _account_type_map()
    matches = []
    candidates = [
        row for row in rows
        if id(row) not in used_ids
        and row.get("category") in ("income", "expense")
        and abs(_amount(row)) > 0
    ]
    out_rows = [
        row for row in candidates
        if _amount(row) < 0 and acct_types.get(_account_key(row)) == "cash"
        and any(k in _search_text(row) for k in ("还款", "自动还款", "主动还款"))
    ]
    in_rows = [
        row for row in candidates
        if _amount(row) > 0 and acct_types.get(_account_key(row)) == "loan"
        and any(k in _search_text(row) for k in ("转帐", "转账", "银行卡中心", "手机银行"))
    ]
    for out_row in sorted(out_rows, key=_effective_datetime):
        if id(out_row) in used_ids:
            continue
        possible = []
        for in_row in in_rows:
            if id(in_row) in used_ids:
                continue
            if out_row.get("currency") != in_row.get("currency"):
                continue
            if abs(abs(_amount(out_row)) - abs(_amount(in_row))) > 0.01:
                continue
            diff = abs((_effective_datetime(out_row) - _effective_datetime(in_row)).total_seconds())
            if diff > 600:
                continue
            possible.append((diff, in_row))
        if len(possible) != 1:
            continue
        _diff, in_row = sorted(possible, key=lambda x: x[0])[0]
        used_ids.add(id(out_row))
        used_ids.add(id(in_row))
        matches.append((out_row, in_row, "same_currency_cash_loan_repayment"))
    return matches


def _match_fx_loan_repayment(rows: list[dict], used_ids: set[int]) -> list[tuple[dict, dict, str]]:
    acct_types = _account_type_map()
    matches = []
    candidates = [
        row for row in rows
        if row.get("category") in ("income", "expense") and abs(_amount(row)) > 0
    ]
    out_rows = [
        row for row in candidates
        if id(row) not in used_ids and _amount(row) < 0
        and acct_types.get(_account_key(row)) == "cash"
    ]
    in_rows = [
        row for row in candidates
        if id(row) not in used_ids and _amount(row) > 0
        and acct_types.get(_account_key(row)) == "loan"
        and row.get("currency") != "CNY"
        and ("手机银行" in _search_text(row) or "转帐" in _search_text(row))
    ]
    for in_row in sorted(in_rows, key=_effective_datetime):
        possible = []
        for out_row in out_rows:
            if id(out_row) in used_ids:
                continue
            if out_row.get("currency") == in_row.get("currency"):
                continue
            diff = abs((_effective_datetime(out_row) - _effective_datetime(in_row)).total_seconds())
            if diff <= 10:
                possible.append((diff, abs(_amount(out_row)), out_row))
        if len(possible) != 1:
            continue
        _diff, _abs_amount, out_row = sorted(possible, key=lambda x: (x[0], x[1]))[0]
        used_ids.add(id(out_row))
        used_ids.add(id(in_row))
        matches.append((out_row, in_row, "fx_loan_repayment"))
    return matches


def _audit_path(run_at: str) -> Path:
    audit_dir = models.FT_DIR / "audit" / "reconcile"
    audit_dir.mkdir(parents=True, exist_ok=True)
    return audit_dir / f"{run_at}.csv"


def _write_audit(run_at: str, scope_from: str, scope_to: str, pairs: list[tuple[dict, dict]],
                 transfer_audit_rows: list[dict]) -> Path:
    path = _audit_path(run_at)
    fields = [
        "run_at", "scope_from", "scope_to", "date", "amount", "currency",
        "counterparty", "description", "category", "account_name", "source",
        "bill_source", "transfer_account", "locked", "record_file", "dedup_status",
        "reconcile_status", "transfer_side", "match_rule", "match_confidence",
        "counterpart_file", "counterpart_account", "counterpart_currency",
        "counterpart_amount",
    ]
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        for keep_row, remove_row in pairs:
            writer.writerow({
                "run_at": run_at,
                "scope_from": scope_from or "",
                "scope_to": scope_to or "",
                **_clean_row(keep_row),
                "record_file": keep_row.get("_record_file", ""),
                "dedup_status": "保留",
                "reconcile_status": "dedup",
            })
            writer.writerow({
                "run_at": run_at,
                "scope_from": scope_from or "",
                "scope_to": scope_to or "",
                **_clean_row(remove_row),
                "record_file": remove_row.get("_record_file", ""),
                "dedup_status": "去除",
                "reconcile_status": "dedup",
            })
        for row in transfer_audit_rows:
            writer.writerow({
                "run_at": run_at,
                "scope_from": scope_from or "",
                "scope_to": scope_to or "",
                **{field: row.get(field, "") for field in fields if field not in ("run_at", "scope_from", "scope_to")},
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
                    row = _normal_row(dict(row))
                    row["_record_file"] = str(csv_file)
                    row["_record_type"] = typ
                    entries.append(row)

    scoped = [row for row in entries if _in_scope(row["date"], start, end)]
    # 用 id() 集合判定归属，避免值相等误判（两条内容相同的 dict 会被 `in` 混淆）。
    scoped_ids = {id(row) for row in scoped}
    # 锁定行（locked=1）：reconcile 完全不碰——不去重、不配对、不单腿标记，
    # 仅原样写回。彻底尊重人工修正（含 ft transfer 手动写入的转账）。
    scoped_locked = [row for row in scoped if _is_locked(row)]
    scoped_active = [row for row in scoped if not _is_locked(row)]
    kept, removed, pairs = dedup_with_pairs(scoped_active)
    # 第二轮：跨源去重（同账户+同日+同额+不同source）
    kept2, removed2, pairs2 = dedup_cross_source(kept)
    kept = kept2
    removed.extend(removed2)
    pairs.extend(pairs2)
    transfer_matches = _match_same_currency_exact(kept)
    used_transfer_ids = {id(row) for match in transfer_matches for row in match[:2]}
    transfer_matches.extend(_match_same_day_unionpay_cash_transfer(kept, used_transfer_ids))
    transfer_matches.extend(_match_same_currency_cash_loan_repayment(kept, used_transfer_ids))
    transfer_matches.extend(_match_fx_loan_repayment(kept, used_transfer_ids))
    for out_row, in_row, rule in transfer_matches:
        _mark_transfer(out_row, in_row, rule)

    single_leg_marks = _mark_single_leg_transfers(kept, used_transfer_ids)

    rows_by_file: dict[str, list[dict]] = defaultdict(list)
    for row in entries:
        if id(row) in scoped_ids:
            continue
        rows_by_file[row["_record_file"]].append(_clean_row(row))

    # 锁定行原样写回（不经任何识别逻辑）
    for row in scoped_locked:
        rows_by_file[row["_record_file"]].append(_clean_row(row))

    for row in kept:
        rows_by_file[row["_record_file"]].append(_clean_row(row))

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
    if not removed and not transfer_matches and not single_leg_marks:
        print("无重复项")
        return

    run_at = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    transfer_audit_rows = []
    for out_row, in_row, rule in transfer_matches:
        transfer_audit_rows.append({
            **_clean_row(out_row),
            "record_file": out_row.get("_record_file", ""),
            "reconcile_status": "transfer_matched",
            "transfer_side": "out",
            "match_rule": rule,
            "match_confidence": "high",
            "counterpart_file": in_row.get("_record_file", ""),
            "counterpart_account": in_row.get("account_name", ""),
            "counterpart_currency": in_row.get("currency", ""),
            "counterpart_amount": in_row.get("amount", ""),
        })
        transfer_audit_rows.append({
            **_clean_row(in_row),
            "record_file": in_row.get("_record_file", ""),
            "reconcile_status": "transfer_matched",
            "transfer_side": "in",
            "match_rule": rule,
            "match_confidence": "high",
            "counterpart_file": out_row.get("_record_file", ""),
            "counterpart_account": out_row.get("account_name", ""),
            "counterpart_currency": out_row.get("currency", ""),
            "counterpart_amount": out_row.get("amount", ""),
        })
    for row, rule in single_leg_marks:
        transfer_audit_rows.append({
            **_clean_row(row),
            "record_file": row.get("_record_file", ""),
            "reconcile_status": "transfer_single_leg",
            "transfer_side": "out" if _amount(row) < 0 else "in",
            "match_rule": rule,
            "match_confidence": "rule",
            "counterpart_file": "",
            "counterpart_account": "",
            "counterpart_currency": "",
            "counterpart_amount": "",
        })
    audit_path = _write_audit(run_at, scope_from, scope_to, pairs, transfer_audit_rows)
    print(f"✅ 去重完成，审计文件: {audit_path}")
    git_stage(models.FT_DIR)
