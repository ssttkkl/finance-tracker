"""跨源去重：支付宝/微信优先，银行重复剔除"""
from collections import defaultdict
from datetime import datetime


def _parse_dt(s: str) -> datetime:
    try:
        return datetime.strptime(s, "%Y-%m-%d %H:%M:%S")
    except ValueError:
        return datetime.strptime(s, "%Y-%m-%d")


def _truncate_minute(dt: datetime) -> datetime:
    return dt.replace(second=0, microsecond=0)


def _source_group(bs: str) -> str:
    if bs == "alipay":
        return "alipay"
    elif bs == "wechat":
        return "wechat"
    return "bank"


def _cross_verify(a: dict, b: dict) -> bool:
    # 1. counterparty 双向子串（双方非空，先剥掉截断标记 "…"）
    ca = a.get("counterparty", "").rstrip("…").rstrip("...")
    cb = b.get("counterparty", "").rstrip("…").rstrip("...")
    if ca and cb and (ca in cb or cb in ca):
        return True
    # 3. description 双向子串（双方非空）
    da, db = a.get("description", ""), b.get("description", "")
    if da and db and (da in db or db in da):
        return True
    return False


def dedup_with_pairs(records: list[dict]) -> tuple[list[dict], list[dict], list[tuple[dict, dict]]]:
    """返回 (保留记录, 被删记录含dedup_status, 保留/删除配对)"""
    if not records:
        return [], [], []

    # Parse dates and group by (minute, amount, currency)
    groups: dict[tuple, list[tuple[datetime, dict]]] = defaultdict(list)
    for r in records:
        dt = _parse_dt(r["date"])
        key = (_truncate_minute(dt), float(r["amount"]), r["currency"])
        groups[key].append((dt, r))

    removed_ids = set()
    removed_pairs: list[tuple[dict, dict]] = []  # (kept_rec, removed_rec)
    # Track candidates already used in a match (each candidate matches at most 1 bank)
    matched_candidate_ids = set()

    sorted_minutes = sorted(groups.keys())

    for i, minute_key in enumerate(sorted_minutes):
        group = groups[minute_key]

        # Classify current group
        alipay = [(dt, r) for dt, r in group if _source_group(r["bill_source"]) == "alipay"]
        wechat = [(dt, r) for dt, r in group if _source_group(r["bill_source"]) == "wechat"]
        bank   = [(dt, r) for dt, r in group if _source_group(r["bill_source"]) == "bank"]

        # Candidates: alipay + wechat from current group
        candidates = alipay + wechat

        # Add alipay + wechat from previous minute (cross-minute boundary)
        if i > 0:
            prev = groups[sorted_minutes[i - 1]]
            candidates += [(dt, r) for dt, r in prev
                           if _source_group(r["bill_source"]) in ("alipay", "wechat")]

        # Match bank records against candidates
        for b_dt, b_rec in bank:
            if id(b_rec) in removed_ids:
                continue
            best_match = None
            best_diff = float("inf")
            for c_dt, c_rec in candidates:
                if id(c_rec) in matched_candidate_ids:
                    continue
                diff = abs((b_dt - c_dt).total_seconds())
                if diff <= 10 and b_rec["account_name"] == c_rec["account_name"] and _cross_verify(b_rec, c_rec):
                    if diff < best_diff:
                        best_diff = diff
                        best_match = c_rec
            if best_match is not None:
                removed_ids.add(id(b_rec))
                removed_pairs.append((best_match, b_rec))
                matched_candidate_ids.add(id(best_match))

    # Split
    kept = [r for r in records if id(r) not in removed_ids]

    # Build removed list with dedup_status
    removed = []
    for keep_rec, remove_rec in removed_pairs:
        removed.append({**keep_rec, "dedup_status": "保留"})
        removed.append({**remove_rec, "dedup_status": "去除"})

    # Sort by date
    kept.sort(key=lambda r: r["date"])
    removed.sort(key=lambda r: r["date"])

    return kept, removed, removed_pairs


def dedup(records: list[dict]) -> tuple[list[dict], list[dict]]:
    """返回 (保留记录, 被删记录含dedup_status)"""
    kept, removed, _ = dedup_with_pairs(records)
    return kept, removed
