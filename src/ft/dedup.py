"""跨源去重：支付宝/微信优先，银行重复剔除"""
from collections import defaultdict
from datetime import datetime


def _parse_dt(s: str) -> datetime:
    return datetime.strptime(s, "%Y-%m-%d %H:%M:%S")


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


def _day_key(date_str: str) -> str:
    """截取日期部分 YYYY-MM-DD"""
    return date_str[:10]


_SOURCE_PRIORITY = {"alipay": 0, "wechat": 1}


def _best_record(records: list[dict]) -> dict:
    """从一组重复记录中选出"最佳"保留：优先级 alipay > wechat > bank，
    同源时选信息量最多的（counterparty+description 最长）。"""
    def info_len(r):
        return len(r.get("counterparty", "")) + len(r.get("description", ""))
    return max(records, key=lambda r: (-_SOURCE_PRIORITY.get(r.get("bill_source", ""), 2), info_len(r)))


def dedup_cross_source(records: list[dict]) -> tuple[list[dict], list[dict], list[tuple[dict, dict]]]:
    """第二轮去重：基于 (账户, 日期, 金额, 类别) 的跨源/同源去重。

    处理 dedup_with_pairs 遗漏的两类情况：
    1. 跨源：同账户+同日+同额+同category，但 bill_source 不同
       （如铁路12306 via alipay vs 中国铁路网络 via icbc_credit）
    2. 同源3+：同账户+同日+同额+同category+同source，3笔以上
    """
    if not records:
        return [], [], []

    by_key: dict[tuple, list[dict]] = defaultdict(list)
    for r in records:
        key = (r.get("account_name", ""), _day_key(r.get("date", "")),
               round(float(r.get("amount", 0)), 2), r.get("category", ""))
        by_key[key].append(r)

    removed_ids: set[int] = set()
    removed_pairs: list[tuple[dict, dict]] = []

    for key, items in by_key.items():
        if len(items) < 2:
            continue

        sources = set(r.get("bill_source", "") for r in items)
        # 只处理高置信度场景：
        # 1. 跨源恰好2笔（同交易在 alipay/wechat 和 bank 各出现一次）
        # 2. 同源恰好2笔（同一来源重复记录，如京东记了两次）
        # 跨源3+笔或同源3+笔不自动处理（可能含不同交易）
        is_cross_2 = len(sources) > 1 and len(items) == 2
        is_same_2 = len(sources) == 1 and len(items) == 2

        if not is_cross_2 and not is_same_2:
            continue

        best = _best_record(items)
        for r in items:
            if id(r) is id(best):
                continue
            removed_ids.add(id(r))
            removed_pairs.append((best, r))

    kept = [r for r in records if id(r) not in removed_ids]
    removed = []
    for keep_rec, remove_rec in removed_pairs:
        removed.append({**keep_rec, "dedup_status": "保留"})
        removed.append({**remove_rec, "dedup_status": "去除"})

    kept.sort(key=lambda r: r.get("date", ""))
    removed.sort(key=lambda r: r.get("date", ""))
    return kept, removed, removed_pairs


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
