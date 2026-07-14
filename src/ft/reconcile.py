"""reconcile — post-import duplicate removal and audit output"""
import csv
import shutil
import re
from collections import defaultdict
from datetime import datetime, date, timedelta
from pathlib import Path

from . import models
from .accounts import load_accounts
from .ai_apply import apply_reconcile_working_rows
from .ai_working_csv import (
    ALLOWED_ROW_STATUS,
    READ_ONLY_FIELDS,
    build_ai_working_row,
    is_allowed_ai_action,
    parse_ai_action_target,
    read_ai_working_csv,
    write_ai_working_csv,
)
from .dedup import _cross_verify, _parse_dt, _source_group, _truncate_minute, dedup_with_pairs
from .mirror_rules import detect_mirror_pairs
from .pending import clear_pending_session, create_pending_session, load_manifest, require_single_pending_session
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


def _linked_refund_record_ids(rows: list[dict], pending_rows: list[dict]) -> set[str]:
    linked_ids = {row.get("record_id", "") for row in pending_rows}
    linked_ids.discard("")
    changed = True
    while changed:
        changed = False
        for row in rows:
            target = parse_ai_action_target(row.get("proposed_action", "") or "")
            if not target or target[0] != "merge_refund_into":
                continue
            record_id = row.get("record_id", "")
            target_id = target[1]
            if record_id in linked_ids or target_id in linked_ids:
                before = len(linked_ids)
                linked_ids.update({record_id, target_id})
                changed = changed or len(linked_ids) != before
    return linked_ids


def _clean_row(row: dict) -> dict:
    return _normal_row({k: v for k, v in row.items() if not k.startswith("_")})


def _effective_datetime(row: dict) -> datetime:
    dt = _parse_dt(row["date"])
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


def _is_zero_amount(row: dict) -> bool:
    return abs(_amount(row)) < 0.005


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


def _collect_unresolved_transfer_review_row_ids(rows: list[dict]) -> set[int]:
    review_ids: set[int] = set()
    candidates = [
        row for row in rows
        if row.get("category") in ("income", "expense") and abs(_amount(row)) > 0
    ]
    out_rows = [row for row in candidates if _amount(row) < 0]
    in_rows = [row for row in candidates if _amount(row) > 0]

    for out_row in out_rows:
        possible = []
        for in_row in in_rows:
            if _account_key(out_row) == _account_key(in_row):
                continue
            if out_row.get("currency") != in_row.get("currency"):
                continue
            if abs(abs(_amount(out_row)) - abs(_amount(in_row))) > 0.01:
                continue
            diff = abs((_effective_datetime(out_row) - _effective_datetime(in_row)).total_seconds())
            if diff > 10:
                continue
            possible.append(in_row)
        if not possible:
            continue
        if len(possible) != 1 or not _has_signal(out_row, possible[0]):
            review_ids.add(id(out_row))
            review_ids.update(id(row) for row in possible)

    return review_ids


def _collect_unresolved_ccb_day_level_review_row_ids(rows: list[dict]) -> set[int]:
    review_ids: set[int] = set()
    strong_rows = [row for row in rows if row.get("bill_source") in {"wechat", "alipay"}]
    ccb_rows = [row for row in rows if row.get("bill_source") == "ccb_debit"]

    for weak_row in ccb_rows:
        weak_dt = _effective_datetime(weak_row).date()
        matched = []
        for strong_row in strong_rows:
            if strong_row.get("account_name") != weak_row.get("account_name"):
                continue
            if strong_row.get("amount") != weak_row.get("amount"):
                continue
            if strong_row.get("currency") != weak_row.get("currency"):
                continue
            strong_dt = _effective_datetime(strong_row).date()
            if abs((strong_dt - weak_dt).days) > 1:
                continue
            matched.append(strong_row)
        if len(matched) > 1:
            review_ids.add(id(weak_row))
            review_ids.update(id(row) for row in matched)

    return review_ids


def _unresolved_review_row_ids(scoped: list[dict]) -> set[int]:
    return (
        _collect_unresolved_transfer_review_row_ids(scoped)
        | _collect_unresolved_ccb_day_level_review_row_ids(scoped)
    )



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


def _audit_fields() -> list[str]:
    return [
        "run_at", "scope_from", "scope_to", "record_id", "date", "amount", "currency",
        "counterparty", "description", "category", "account_name", "source",
        "bill_source", "transfer_account", "locked", "offset_group", "offset_role",
        "offset_strength", "offset_source", "offset_rule_hint", "offset_match_type",
        "proposed_action", "record_file", "dedup_status", "reconcile_status",
        "transfer_side", "match_rule", "match_confidence", "counterpart_file",
        "counterpart_account", "counterpart_currency", "counterpart_amount",
    ]


def _write_audit_rows(path: Path, run_at: str, scope_from: str, scope_to: str,
                      pairs: list[tuple[dict, dict]], extra_audit_rows: list[dict]) -> Path:
    fields = _audit_fields()
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
        for row in extra_audit_rows:
            writer.writerow({
                "run_at": run_at,
                "scope_from": scope_from or "",
                "scope_to": scope_to or "",
                **{field: row.get(field, "") for field in fields if field not in ("run_at", "scope_from", "scope_to")},
            })
    return path


def _write_audit(run_at: str, scope_from: str, scope_to: str, pairs: list[tuple[dict, dict]],
                 extra_audit_rows: list[dict]) -> Path:
    return _write_audit_rows(_audit_path(run_at), run_at, scope_from, scope_to, pairs, extra_audit_rows)


def _load_entries() -> list[dict]:
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
    return entries


def _build_scope_labels(start: date | None, end: date | None) -> tuple[str, str]:
    if start and end:
        return start.isoformat(), end.isoformat()
    if start:
        return start.isoformat(), ""
    if end:
        return "", end.isoformat()
    return "", ""


def _scoped_record_files(entries: list[dict], start: date | None, end: date | None) -> list[str]:
    return sorted({
        row["_record_file"]
        for row in entries
        if _in_scope(row["date"], start, end)
    })


def _prepare_reconcile_state(*, month=None, date_from=None, date_to=None):
    start, end = _parse_scope(month=month, date_from=date_from, date_to=date_to)
    scope_from, scope_to = _build_scope_labels(start, end)

    entries = _load_entries()
    scoped_record_files = _scoped_record_files(entries, start, end)
    scoped = [row for row in entries if _in_scope(row["date"], start, end) and not _is_zero_amount(row)]
    scoped_ids = {id(row) for row in scoped}
    scoped_locked = [row for row in scoped if _is_locked(row)]
    scoped_active = [row for row in scoped if not _is_locked(row)]
    mirror_review_annotations = _mirror_review_annotations(scoped_active)
    unresolved_review_row_ids = _unresolved_review_row_ids(scoped_active)
    kept, removed, pairs = dedup_with_pairs(scoped_active)
    kept_base = [{**row} for row in kept]
    transfer_matches = _match_same_currency_exact(kept)
    used_transfer_ids = {id(row) for match in transfer_matches for row in match[:2]}
    transfer_matches.extend(_match_same_day_unionpay_cash_transfer(kept, used_transfer_ids))
    transfer_matches.extend(_match_same_currency_cash_loan_repayment(kept, used_transfer_ids))
    transfer_matches.extend(_match_fx_loan_repayment(kept, used_transfer_ids))
    for out_row, in_row, rule in transfer_matches:
        _mark_transfer(out_row, in_row, rule)
    single_leg_marks = _mark_single_leg_transfers(kept, used_transfer_ids)

    review_row_ids = set(mirror_review_annotations) | unresolved_review_row_ids
    has_only_review = bool(mirror_review_annotations) and not removed
    has_mixed_high_and_review = bool(mirror_review_annotations) and bool(removed)
    pending_rows = [row for row in scoped if id(row) in review_row_ids] if review_row_ids else scoped

    state = {
        "start": start,
        "end": end,
        "scope_from": scope_from,
        "scope_to": scope_to,
        "entries": entries,
        "scoped_record_files": scoped_record_files,
        "scoped": scoped,
        "scoped_ids": scoped_ids,
        "scoped_locked": scoped_locked,
        "mirror_review_annotations": mirror_review_annotations,
        "unresolved_review_row_ids": unresolved_review_row_ids,
        "pending_rows": pending_rows,
        "has_only_review": has_only_review,
        "kept": kept,
        "kept_base": kept_base,
        "removed": removed,
        "pairs": pairs,
        "transfer_matches": transfer_matches,
        "single_leg_marks": single_leg_marks,
    }
    return state


def _copy_scoped_records(session_dir: Path, scoped: list[dict]):
    staged_root = session_dir / "staged_records"
    copied = set()
    for row in scoped:
        src = Path(row["_record_file"])
        if src in copied or not src.exists():
            continue
        rel = src.relative_to(models.RECORDS_DIR)
        dest = staged_root / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dest)
        copied.add(src)


def _mirror_review_annotations(scoped: list[dict]) -> dict[int, dict]:
    review_pairs = detect_mirror_pairs(scoped).review_pairs
    annotations: dict[int, dict] = {}
    for idx, pair in enumerate(review_pairs, 1):
        group = f"mirror_{idx:04d}"
        for row, role in ((pair.keep_row, "keep"), (pair.drop_row, "drop")):
            annotations[id(row)] = {
                "ai_group": group,
                "rule_hint": pair.rule_hint,
                "ai_reason": f"{pair.rule_hint}:{role}",
            }
    return annotations


def _has_low_confidence_mirror_review(state: dict) -> bool:
    return bool(state.get("mirror_review_annotations"))


def _has_unresolved_review(state: dict) -> bool:
    return bool(state.get("unresolved_review_row_ids"))


def _should_enter_reconcile_pending(state: dict) -> bool:
    if not state.get("scoped"):
        return False
    if _has_low_confidence_mirror_review(state):
        return True
    if _has_unresolved_review(state):
        return True
    return False


def _create_reconcile_pending_session(state: dict):
    manifest = {
        "scope_from": state["scope_from"],
        "scope_to": state["scope_to"],
    }
    session_dir = create_pending_session("reconcile", manifest)
    session_id = session_dir.name

    pending_rows = state["scoped"] if state.get("has_only_review") else state.get("pending_rows", state["scoped"])
    linked_refund_ids = _linked_refund_record_ids(state["scoped"], pending_rows)
    if linked_refund_ids:
        pending_rows = [
            row for row in state["scoped"]
            if row.get("record_id", "") in linked_refund_ids
        ]
    mirror_review_annotations = state.get("mirror_review_annotations", {})
    auto_removed_ids = {id(remove_row) for _keep_row, remove_row in state.get("pairs", [])}

    def _pending_defaults(row: dict) -> dict:
        defaults = dict(mirror_review_annotations.get(id(row), {}))
        if id(row) in auto_removed_ids:
            defaults["row_status"] = "dropped"
        return defaults

    ai_rows = [
        build_ai_working_row(
            {
                **_clean_row(row),
                "record_file": row.get("_record_file", ""),
                "record_type": row.get("_record_type", ""),
            },
            record_id=f"r_{idx:06d}",
            session_id=session_id,
            defaults=_pending_defaults(row),
        )
        for idx, row in enumerate(pending_rows, 1)
    ]
    write_ai_working_csv(session_dir / "ai_working.csv", ai_rows)
    _copy_scoped_records(session_dir, pending_rows)

    run_at = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    transfer_audit_rows = []
    for out_row, in_row, rule in state["transfer_matches"]:
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
    for row, rule in state["single_leg_marks"]:
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
    proposed_audit = _write_audit_rows(
        session_dir / "proposed_audit.csv",
        run_at,
        state["scope_from"],
        state["scope_to"],
        state["pairs"],
        transfer_audit_rows,
    )

    from .pending import format_pending_guidance
    print(format_pending_guidance("reconcile", session_dir))


def _validate_reconcile_working_rows(original_rows: list[dict], edited_rows: list[dict], session_id: str):
    if len(original_rows) != len(edited_rows):
        raise ValueError(f"❌ edited CSV 行数不匹配: 期望 {len(original_rows)} 行，实际 {len(edited_rows)} 行")

    original_by_id = {row["record_id"]: row for row in original_rows}
    edited_by_id = {row.get("record_id", ""): row for row in edited_rows}
    edited_ids = list(edited_by_id)
    if len(set(edited_ids)) != len(edited_ids):
        raise ValueError("❌ edited CSV 中存在重复 record_id")
    if set(edited_ids) != set(original_by_id):
        missing = sorted(set(original_by_id) - set(edited_ids))
        extra = sorted(set(edited_ids) - set(original_by_id))
        raise ValueError(f"❌ edited CSV 的 record_id 集合不一致: missing={missing} extra={extra}")

    for row in edited_rows:
        if row.get("session_id") != session_id:
            raise ValueError(f"❌ session_id 不匹配: record_id={row.get('record_id', '')} session_id={row.get('session_id', '')}")
        original = original_by_id[row["record_id"]]
        for field in READ_ONLY_FIELDS:
            if row.get(field, "") != original.get(field, ""):
                raise ValueError(f"❌ 只读字段被修改: record_id={row['record_id']} field={field}")

        row_status = row.get("row_status", "active") or "active"
        ai_action = row.get("ai_action", "leave_as_is") or "leave_as_is"
        if row_status not in ALLOWED_ROW_STATUS:
            raise ValueError(f"❌ 非法 row_status: record_id={row['record_id']} row_status={row_status}")
        if not is_allowed_ai_action(ai_action):
            raise ValueError(f"❌ 非法 ai_action: record_id={row['record_id']} ai_action={ai_action}")
        if row_status == "dropped" and ai_action not in {"drop", "leave_as_is"}:
            raise ValueError(f"❌ dropped 行只能配合 drop/leave_as_is: record_id={row['record_id']}")
        if ai_action == "drop" and row.get("ai_reason", "").strip() == "":
            raise ValueError(f"❌ drop 动作必须填写 ai_reason: record_id={row['record_id']}")

        if ai_action == "modify":
            changed_fields = [
                field for field in ("counterparty", "description", "category", "account_name", "source", "transfer_account", "locked")
                if row.get(field, "") != original.get(field, "")
            ]
            if not changed_fields:
                raise ValueError(f"❌ ai_action=modify 但没有实际修改字段: record_id={row['record_id']}")
            if row.get("ai_reason", "").strip() == "":
                raise ValueError(f"❌ modify 动作必须填写 ai_reason: record_id={row['record_id']}")

    for row in edited_rows:
        ai_action = row.get("ai_action", "leave_as_is") or "leave_as_is"
        target = parse_ai_action_target(ai_action)
        if not target:
            continue
        action_name, target_id = target
        if row.get("ai_reason", "").strip() == "":
            raise ValueError(f"❌ {action_name} 动作必须填写 ai_reason: record_id={row['record_id']}")
        if not target_id or target_id not in edited_by_id:
            raise ValueError(f"❌ 引用的 record_id 不存在: record_id={row['record_id']} ai_action={ai_action}")
        target_row = edited_by_id[target_id]
        if action_name == "mark_transfer_out_to":
            if row.get("amount", "").startswith("-") is False:
                raise ValueError(f"❌ mark_transfer_out_to 只能用于支出行: record_id={row['record_id']}")
            expected = f"mark_transfer_in_from:{row['record_id']}"
            if (target_row.get("ai_action", "leave_as_is") or "leave_as_is") != expected:
                raise ValueError(f"❌ 转账双边动作应成对出现: record_id={row['record_id']} target={target_id}")
        elif action_name == "mark_transfer_in_from":
            if row.get("amount", "").startswith("-"):
                raise ValueError(f"❌ mark_transfer_in_from 只能用于收入行: record_id={row['record_id']}")
            expected = f"mark_transfer_out_to:{row['record_id']}"
            if (target_row.get("ai_action", "leave_as_is") or "leave_as_is") != expected:
                raise ValueError(f"❌ 转账双边动作应成对出现: record_id={row['record_id']} target={target_id}")


def continue_reconcile(edited_csv: str):
    session_dir = require_single_pending_session("reconcile")
    manifest = load_manifest(session_dir)
    original_rows = read_ai_working_csv(session_dir / "ai_working.csv")
    edited_rows = read_ai_working_csv(Path(edited_csv))
    _validate_reconcile_working_rows(original_rows, edited_rows, manifest["session_id"])

    by_file, extra_audit_rows = apply_reconcile_working_rows(edited_rows)

    edited_ids_by_file: dict[str, set[str]] = defaultdict(set)
    for row in edited_rows:
        edited_ids_by_file[row.get("record_file", "")].add(row["record_id"])

    touched_files = sorted(edited_ids_by_file)
    for file_path_str in touched_files:
        file_path = Path(file_path_str)
        existing_rows = []
        if file_path.exists():
            with open(file_path, encoding="utf-8") as f:
                existing_rows = [_normal_row(row) for row in csv.DictReader(f)]

        # Pending edits replace only their own records; keep unrelated rows in the same month.
        final_rows = [
            row for row in existing_rows
            if row.get("record_id", "") not in edited_ids_by_file[file_path_str]
        ]
        final_rows.extend(by_file.get(file_path_str, []))
        final_rows.sort(key=lambda r: r["date"])
        with open(file_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=models.CSV_FIELDS)
            writer.writeheader()
            writer.writerows(final_rows)

    rebuild_snapshot_from_records(models.RECORDS_DIR)
    proposed_audit = session_dir / "proposed_audit.csv"
    if proposed_audit.exists():
        run_at = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
        if extra_audit_rows:
            audit_path = _write_audit(run_at, manifest.get("scope_from", ""), manifest.get("scope_to", ""), [], extra_audit_rows)
        else:
            audit_path = _audit_path(run_at)
            shutil.copy2(proposed_audit, audit_path)
        print(f"✅ 去重完成，审计文件: {audit_path}")
    clear_pending_session("reconcile")
    git_stage(models.FT_DIR)


def abort_reconcile():
    clear_pending_session("reconcile")
    print("✅ 已放弃当前 pending reconcile 会话")


def do_reconcile(*, month=None, date_from=None, date_to=None):
    state = _prepare_reconcile_state(month=month, date_from=date_from, date_to=date_to)
    touched_files = state.get("scoped_record_files", sorted({row["_record_file"] for row in state["scoped"]}))

    if not state["scoped"]:
        rows_by_file: dict[str, list[dict]] = defaultdict(list)
        for row in state["entries"]:
            if _in_scope(row["date"], state["start"], state["end"]):
                continue
            rows_by_file[row["_record_file"]].append(_clean_row(row))

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
        print("无重复项")
        return

    rows_by_file: dict[str, list[dict]] = defaultdict(list)
    for row in state["entries"]:
        if id(row) in state["scoped_ids"]:
            continue
        if _in_scope(row["date"], state["start"], state["end"]) and _is_zero_amount(row):
            continue
        rows_by_file[row["_record_file"]].append(_clean_row(row))

    for row in state["scoped_locked"]:
        rows_by_file[row["_record_file"]].append(_clean_row(row))

    kept_rows_for_write = state["kept_base"] if state.get("has_only_review") else state["kept"]
    for row in kept_rows_for_write:
        rows_by_file[row["_record_file"]].append(_clean_row(row))

    has_pending_review = _has_low_confidence_mirror_review(state)
    has_mixed_high_and_review = has_pending_review and bool(state["removed"])

    if not _should_enter_reconcile_pending(state):
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
        if not state["removed"] and not state["transfer_matches"] and not state["single_leg_marks"]:
            print("无重复项")
            return

        run_at = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
        transfer_audit_rows = []
        for out_row, in_row, rule in state["transfer_matches"]:
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
        for row, rule in state["single_leg_marks"]:
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
        audit_path = _write_audit(run_at, state["scope_from"], state["scope_to"], state["pairs"], transfer_audit_rows)
        print(f"✅ 去重完成，审计文件: {audit_path}")
        git_stage(models.FT_DIR)
        return

    if has_mixed_high_and_review:
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

    if has_mixed_high_and_review:
        run_at = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
        transfer_audit_rows = []
        for out_row, in_row, rule in state["transfer_matches"]:
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
        for row, rule in state["single_leg_marks"]:
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
        _write_audit(run_at, state["scope_from"], state["scope_to"], state["pairs"], transfer_audit_rows)
        git_stage(models.FT_DIR)

    _create_reconcile_pending_session(state)
