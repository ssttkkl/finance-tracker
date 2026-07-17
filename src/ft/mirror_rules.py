from dataclasses import dataclass


@dataclass
class MirrorPair:
    keep_row: dict
    drop_row: dict
    rule_hint: str
    confidence: str


@dataclass
class MirrorDetectionResult:
    auto_drop_pairs: list
    review_pairs: list


@dataclass
class MirrorCandidate:
    keep_row: dict
    drop_row: dict
    diff_seconds: float
    weak_channel_kind: str
    candidate_count: int


@dataclass
class RankedPair:
    diff_seconds: float
    candidate_count: int
    pair: MirrorPair


MIRROR_WEAK_30S_RULE_HINT = "possible_mirror_weak_30s_cross_source"


STRONG_SOURCES = {"alipay", "wechat"}
WEAK_SOURCES = {"icbc_credit", "icbc_debit", "ccb_debit"}
MERCHANT_ALIAS_SETS = (
    {"库迪咖啡", "Cotti Coffee"},
    {"UNIQLO", "优衣库"},
)


def _date_text(row: dict) -> str:
    return str(row.get("date", ""))


def _same_day(a: dict, b: dict) -> bool:
    return _date_text(a)[:10] == _date_text(b)[:10]


def _description(row: dict) -> str:
    return str(row.get("description", ""))


def _counterparty(row: dict) -> str:
    return str(row.get("counterparty", "")).rstrip("…").rstrip("...")


def _is_safe_unique_cross_source_strong_candidate(candidate: MirrorCandidate) -> bool:
    strong_row = candidate.keep_row
    if candidate.candidate_count != 1:
        return False
    weak_row = candidate.drop_row
    if weak_row.get("bill_source") == "ccb_debit":
        return _has_full_datetime(strong_row)
    if not (_has_full_datetime(strong_row) and _has_full_datetime(weak_row)):
        return False
    if candidate.diff_seconds > 30:
        return False
    return True


def _cross_verify(a: dict, b: dict) -> bool:
    ca = _counterparty(a)
    cb = _counterparty(b)
    if ca and cb and (ca in cb or cb in ca):
        return True
    da = _description(a)
    db = _description(b)
    if da and db and (da in db or db in da):
        return True
    text = " ".join([ca, da, cb, db])
    for alias_group in MERCHANT_ALIAS_SETS:
        if all(alias in text for alias in alias_group):
            return True
    return False


def _parse_dt_text(text: str):
    if len(text) > 10:
        from datetime import datetime
        return datetime.strptime(text, "%Y-%m-%d %H:%M:%S")
    from datetime import datetime
    return datetime.strptime(text, "%Y-%m-%d")


def _time_diff_seconds(a: dict, b: dict) -> float:
    return abs((_parse_dt_text(_date_text(a)) - _parse_dt_text(_date_text(b))).total_seconds())


def _within_seconds(a: dict, b: dict, limit_seconds: int) -> bool:
    a_text = _date_text(a)
    b_text = _date_text(b)
    if len(a_text) <= 10 or len(b_text) <= 10:
        return False
    return _time_diff_seconds(a, b) <= limit_seconds


def _has_full_datetime(row: dict) -> bool:
    return len(_date_text(row)) > 10


def _account_matches_source(row: dict) -> bool:
    bill_source = row.get("bill_source")
    account_name = str(row.get("account_name", ""))
    if bill_source == "icbc_credit":
        return "信用卡" in account_name
    if bill_source == "icbc_debit":
        return "借记卡" in account_name
    if bill_source == "ccb_debit":
        return "建行" in account_name and "储蓄卡" in account_name
    return True


def _weak_channel_kind(row: dict) -> str:
    bill_source = row.get("bill_source")
    text = " ".join([
        _counterparty(row),
        _description(row),
        str(row.get("source", "")),
        str(row.get("bill_source", "")),
    ])
    if bill_source == "icbc_credit":
        return "icbc_credit_card_channel"
    if bill_source == "ccb_debit":
        return "ccb_debit_day_level"
    if bill_source != "icbc_debit":
        return "weak_channel_unknown"
    if any(keyword in text for keyword in ("财付通", "微信支付", "支付宝", "网络技术", "银联", "无卡快捷", "无卡付")):
        return "icbc_debit_gateway"
    return "weak_channel_unknown"


def _candidate_time_matches(strong_row: dict, weak_row: dict, weak_channel_kind: str) -> bool:
    if weak_channel_kind == "ccb_debit_day_level":
        return _same_day(strong_row, weak_row)
    if weak_channel_kind == "icbc_credit_card_channel":
        return _within_seconds(strong_row, weak_row, 10)
    return _within_seconds(strong_row, weak_row, 30)


def _matches_credit_strongest_extension(strong_row: dict, weak_row: dict, weak_channel_kind: str) -> bool:
    return (
        weak_channel_kind == "icbc_credit_card_channel"
        and _has_full_datetime(strong_row)
        and _has_full_datetime(weak_row)
        and _within_seconds(strong_row, weak_row, 30)
        and not _cross_verify(strong_row, weak_row)
    )


def _matches_loose_cross_source_window(strong_row: dict, weak_row: dict) -> bool:
    return (
        _within_seconds(strong_row, weak_row, 30)
        if _has_full_datetime(strong_row) and _has_full_datetime(weak_row)
        else _same_day(strong_row, weak_row)
    )


def _candidates_from_matched_rows(matched: list[dict], weak_row: dict, weak_channel_kind: str) -> list[MirrorCandidate]:
    candidate_count = len(matched)
    return [
        MirrorCandidate(
            keep_row=strong_row,
            drop_row=weak_row,
            diff_seconds=_time_diff_seconds(strong_row, weak_row) if len(_date_text(weak_row)) > 10 else 0,
            weak_channel_kind=weak_channel_kind,
            candidate_count=candidate_count,
        )
        for strong_row in matched
    ]


def _strong_rows_matching_amount_account(rows: list[dict], weak_row: dict) -> list[dict]:
    return [
        strong_row for strong_row in rows
        if strong_row.get("account_name") == weak_row.get("account_name")
        and strong_row.get("amount") == weak_row.get("amount")
        and strong_row.get("currency") == weak_row.get("currency")
    ]


def _collect_ranked_pairs(entries: list[RankedPair], used_strong_ids: set[int], used_drop_ids: set[int]) -> list[MirrorPair]:
    pairs: list[MirrorPair] = []
    for entry in sorted(entries, key=lambda item: (item.diff_seconds, item.candidate_count)):
        pair = entry.pair
        if id(pair.keep_row) in used_strong_ids or id(pair.drop_row) in used_drop_ids:
            continue
        used_strong_ids.add(id(pair.keep_row))
        used_drop_ids.add(id(pair.drop_row))
        pairs.append(pair)
    return pairs


def _build_mirror_candidates(rows: list[dict]) -> list[MirrorCandidate]:
    strong_rows = [row for row in rows if row.get("bill_source") in STRONG_SOURCES]
    weak_rows = [row for row in rows if row.get("bill_source") in WEAK_SOURCES]
    candidates: list[MirrorCandidate] = []
    for weak_row in weak_rows:
        if not _account_matches_source(weak_row):
            continue
        weak_channel_kind = _weak_channel_kind(weak_row)
        matched = [
            strong_row
            for strong_row in _strong_rows_matching_amount_account(strong_rows, weak_row)
            if _candidate_time_matches(strong_row, weak_row, weak_channel_kind)
            or _matches_credit_strongest_extension(strong_row, weak_row, weak_channel_kind)
        ]
        candidates.extend(_candidates_from_matched_rows(matched, weak_row, weak_channel_kind))
    return candidates


def _build_loose_cross_source_candidates(rows: list[dict]) -> list[MirrorCandidate]:
    strong_rows = [row for row in rows if row.get("bill_source") in STRONG_SOURCES]
    weak_rows = [row for row in rows if row.get("bill_source") in WEAK_SOURCES]
    candidates: list[MirrorCandidate] = []
    for weak_row in weak_rows:
        matched = [
            strong_row for strong_row in _strong_rows_matching_amount_account(strong_rows, weak_row)
            if strong_row.get("bill_source") != weak_row.get("bill_source")
            and _matches_loose_cross_source_window(strong_row, weak_row)
            and not _cross_verify(strong_row, weak_row)
        ]
        candidates.extend(_candidates_from_matched_rows(matched, weak_row, ""))
    return candidates


def _classify_candidate(candidate: MirrorCandidate) -> tuple[str, MirrorPair] | None:
    strong_row = candidate.keep_row
    weak_row = candidate.drop_row
    if _is_safe_unique_cross_source_strong_candidate(candidate):
        rule_hint = {
            "icbc_debit": "debit_purchase_mirror_icbc",
            "ccb_debit": "debit_purchase_mirror_ccb_unique_day",
        }.get(weak_row.get("bill_source"), "card_channel_purchase_mirror")
        return "auto", MirrorPair(strong_row, weak_row, rule_hint, "high")

    if candidate.weak_channel_kind == "icbc_debit_gateway":
        return "review", MirrorPair(strong_row, weak_row, "debit_purchase_mirror_icbc", "low")

    return None


def detect_mirror_pairs(rows: list[dict]) -> MirrorDetectionResult:
    auto_candidates: list[RankedPair] = []
    review_candidates: list[RankedPair] = []
    for candidate in _build_mirror_candidates(rows):
        classified = _classify_candidate(candidate)
        if classified is None:
            continue
        bucket, pair = classified
        payload = RankedPair(candidate.diff_seconds, candidate.candidate_count, pair)
        if bucket == "auto":
            auto_candidates.append(payload)
        else:
            review_candidates.append(payload)

    used_strong_ids = set()
    used_drop_ids = set()

    auto_drop_pairs = _collect_ranked_pairs(auto_candidates, used_strong_ids, used_drop_ids)
    review_pairs = _collect_ranked_pairs(review_candidates, used_strong_ids, used_drop_ids)

    for candidate in sorted(_build_loose_cross_source_candidates(rows), key=lambda item: (item.diff_seconds, item.candidate_count)):
        if id(candidate.keep_row) in used_strong_ids or id(candidate.drop_row) in used_drop_ids:
            continue
        pair = MirrorPair(
            candidate.keep_row,
            candidate.drop_row,
            MIRROR_WEAK_30S_RULE_HINT,
            "low",
        )
        used_strong_ids.add(id(pair.keep_row))
        used_drop_ids.add(id(pair.drop_row))
        review_pairs.append(pair)

    return MirrorDetectionResult(auto_drop_pairs=auto_drop_pairs, review_pairs=review_pairs)
