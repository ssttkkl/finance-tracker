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
    merchant_signal_kind: str
    candidate_count: int


MIRROR_LOW_CONFIDENCE_RULE_HINT = "possible_mirror_low_confidence"
MIRROR_WEAK_30S_RULE_HINT = "possible_mirror_weak_30s_cross_source"


STRONG_SOURCES = {"alipay", "wechat"}
WEAK_SOURCES = {"icbc_credit", "icbc_debit", "ccb_debit"}
WECHAT_SOCIAL_KEYWORDS = ("群收款", "红包", "转账", "微信好友", "QQ红包")
QR_COLLECT_KEYWORDS = ("收钱码", "扫码收款", "二维码收款")
ALIPAY_STABLE_KEYWORDS = ("缴费", "会员", "续费", "交通卡")
WEAK_GENERIC_TEXT_KEYWORDS = (
    "消费",
    "财付通",
    "微信支付",
    "支付宝消费",
    "支付宝",
    "网络技术",
    "银联",
    "快捷支付",
    "扫二维码付款",
    "扫描二维码付款",
)
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


def _full_text(row: dict) -> str:
    return " ".join([
        _counterparty(row),
        _description(row),
        str(row.get("source", "")),
        str(row.get("bill_source", "")),
    ])


def _merchant_like_text(row: dict) -> str:
    return " ".join([_counterparty(row), _description(row)])


def _looks_like_generic_channel_text(row: dict) -> bool:
    text = _merchant_like_text(row)
    return any(keyword in text for keyword in WEAK_GENERIC_TEXT_KEYWORDS)


def _looks_like_specific_merchant_text(row: dict) -> bool:
    text = _merchant_like_text(row)
    if not text.strip():
        return False
    if _looks_like_generic_channel_text(row):
        return False
    if any(keyword in text for keyword in WECHAT_SOCIAL_KEYWORDS):
        return False
    if any(keyword in text for keyword in QR_COLLECT_KEYWORDS):
        return False
    return True


def _looks_like_specific_counterparty(row: dict) -> bool:
    counterparty = _counterparty(row)
    if not counterparty.strip():
        return False
    generic_tokens = (
        "微信",
        "支付宝",
        "财付通",
        "银联",
        "退款",
        "群收款",
        "QQ红包",
        "扫二维码付款",
        "扫描二维码付款",
    )
    return not any(token in counterparty for token in generic_tokens)


def _is_safe_unique_cross_source_strong_candidate(candidate: MirrorCandidate) -> bool:
    strong_row = candidate.keep_row
    weak_row = candidate.drop_row
    if candidate.candidate_count != 1:
        return False
    if candidate.merchant_signal_kind == "refund":
        return False
    if weak_row.get("bill_source") == "ccb_debit":
        return False
    if not (_has_full_datetime(strong_row) and _has_full_datetime(weak_row)):
        return False
    if candidate.diff_seconds > 30:
        return False
    return True


def _matches_alias_group(a: dict, b: dict) -> bool:
    text = _merchant_like_text(a) + " " + _merchant_like_text(b)
    for alias_group in MERCHANT_ALIAS_SETS:
        if all(alias in text for alias in alias_group):
            return True
    return False


def _cross_verify(a: dict, b: dict) -> bool:
    ca = _counterparty(a)
    cb = _counterparty(b)
    if ca and cb and (ca in cb or cb in ca):
        return True
    da = _description(a)
    db = _description(b)
    if da and db and (da in db or db in da):
        return True
    if _matches_alias_group(a, b):
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


def _within_10_seconds(a: dict, b: dict) -> bool:
    return _within_seconds(a, b, 10)


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


def _has_refund_link(row: dict) -> bool:
    action = str(row.get("proposed_action", "")).strip()
    return bool(
        str(row.get("offset_group", "")).strip()
        or str(row.get("offset_role", "")).strip()
        or (action and action != "leave_as_is")
    )


def _is_strong_source(row: dict) -> bool:
    return row.get("bill_source") in STRONG_SOURCES


def _weak_channel_kind(row: dict) -> str:
    bill_source = row.get("bill_source")
    text = _full_text(row)
    if bill_source == "icbc_credit":
        return "icbc_credit_card_channel"
    if bill_source == "ccb_debit":
        return "ccb_debit_day_level"
    if bill_source != "icbc_debit":
        return "weak_channel_unknown"
    if any(keyword in text for keyword in ("财付通", "微信支付")):
        return "icbc_debit_wechat_gateway"
    if any(keyword in text for keyword in ("支付宝", "网络技术")):
        return "icbc_debit_alipay_gateway"
    if any(keyword in text for keyword in ("银联", "无卡快捷", "无卡付")):
        return "icbc_debit_unionpay_gateway"
    return "weak_channel_unknown"


def _merchant_signal_kind(strong_row: dict, weak_row: dict) -> str:
    strong_text = _full_text(strong_row)
    weak_text = _full_text(weak_row)
    text = strong_text + " " + weak_text
    if _has_refund_link(strong_row) or _has_refund_link(weak_row):
        return "refund"
    if any(keyword in text for keyword in WECHAT_SOCIAL_KEYWORDS):
        return "social_flow"
    if any(keyword in text for keyword in QR_COLLECT_KEYWORDS):
        return "qr_collect"
    if any(keyword in text for keyword in ALIPAY_STABLE_KEYWORDS):
        return "stable_service"
    return "merchant_consume"


def _candidate_time_matches(strong_row: dict, weak_row: dict, weak_channel_kind: str) -> bool:
    if weak_channel_kind == "icbc_credit_card_channel":
        return _within_10_seconds(strong_row, weak_row)
    if weak_channel_kind in {
        "icbc_debit_wechat_gateway",
        "icbc_debit_alipay_gateway",
        "icbc_debit_unionpay_gateway",
        "weak_channel_unknown",
    }:
        return _within_seconds(strong_row, weak_row, 30)
    if weak_channel_kind == "ccb_debit_day_level":
        return _same_day(strong_row, weak_row)
    return False


def _build_mirror_candidates(rows: list[dict]) -> list[MirrorCandidate]:
    strong_rows = [row for row in rows if _is_strong_source(row)]
    weak_rows = [row for row in rows if row.get("bill_source") in WEAK_SOURCES]
    candidates: list[MirrorCandidate] = []
    for weak_row in weak_rows:
        if not _account_matches_source(weak_row):
            continue
        weak_channel_kind = _weak_channel_kind(weak_row)
        matched = [
            strong_row for strong_row in strong_rows
            if strong_row.get("account_name") == weak_row.get("account_name")
            and strong_row.get("amount") == weak_row.get("amount")
            and strong_row.get("currency") == weak_row.get("currency")
            and _candidate_time_matches(strong_row, weak_row, weak_channel_kind)
        ]
        candidate_count = len(matched)
        for strong_row in matched:
            candidates.append(MirrorCandidate(
                keep_row=strong_row,
                drop_row=weak_row,
                diff_seconds=_time_diff_seconds(strong_row, weak_row) if len(_date_text(weak_row)) > 10 else 0,
                weak_channel_kind=weak_channel_kind,
                merchant_signal_kind=_merchant_signal_kind(strong_row, weak_row),
                candidate_count=candidate_count,
            ))
    return candidates


def _build_loose_cross_source_candidates(rows: list[dict]) -> list[MirrorCandidate]:
    strong_rows = [row for row in rows if _is_strong_source(row)]
    weak_rows = [row for row in rows if row.get("bill_source") in WEAK_SOURCES]
    candidates: list[MirrorCandidate] = []
    for weak_row in weak_rows:
        matched = [
            strong_row for strong_row in strong_rows
            if strong_row.get("bill_source") != weak_row.get("bill_source")
            and strong_row.get("account_name") == weak_row.get("account_name")
            and strong_row.get("amount") == weak_row.get("amount")
            and strong_row.get("currency") == weak_row.get("currency")
            and (
                _within_seconds(strong_row, weak_row, 30)
                if _has_full_datetime(strong_row) and _has_full_datetime(weak_row)
                else _same_day(strong_row, weak_row)
            )
            and not _cross_verify(strong_row, weak_row)
        ]
        candidate_count = len(matched)
        for strong_row in matched:
            candidates.append(MirrorCandidate(
                keep_row=strong_row,
                drop_row=weak_row,
                diff_seconds=_time_diff_seconds(strong_row, weak_row),
                weak_channel_kind=_weak_channel_kind(weak_row),
                merchant_signal_kind=_merchant_signal_kind(strong_row, weak_row),
                candidate_count=candidate_count,
            ))
    return candidates


def _classify_candidate(candidate: MirrorCandidate) -> tuple[str, MirrorPair] | None:
    strong_row = candidate.keep_row
    weak_row = candidate.drop_row
    if candidate.weak_channel_kind == "icbc_credit_card_channel":
        if candidate.candidate_count != 1:
            return None
        if _cross_verify(strong_row, weak_row):
            confidence = "low" if candidate.merchant_signal_kind == "refund" else "high"
            bucket = "review" if confidence == "low" else "auto"
            return bucket, MirrorPair(strong_row, weak_row, "card_channel_purchase_mirror", confidence)
        if (
            candidate.merchant_signal_kind == "merchant_consume"
            and _looks_like_specific_merchant_text(strong_row)
            and _looks_like_generic_channel_text(weak_row)
        ):
            return "auto", MirrorPair(strong_row, weak_row, "card_channel_purchase_mirror", "high")
        return None

    if candidate.weak_channel_kind == "ccb_debit_day_level":
        if candidate.candidate_count != 1:
            return None
        if _description(strong_row) == "群收款" and _description(weak_row) == "充值":
            return "review", MirrorPair(strong_row, weak_row, "possible_wechat_topup_or_group_collection_mirror", "low")
        if candidate.merchant_signal_kind == "refund":
            return "review", MirrorPair(strong_row, weak_row, "debit_purchase_mirror_ccb_unique_day", "low")
        return "auto", MirrorPair(strong_row, weak_row, "debit_purchase_mirror_ccb_unique_day", "high")

    if candidate.weak_channel_kind in {
        "icbc_debit_wechat_gateway",
        "icbc_debit_alipay_gateway",
        "icbc_debit_unionpay_gateway",
    }:
        if candidate.merchant_signal_kind in {"refund", "social_flow"}:
            return "review", MirrorPair(strong_row, weak_row, "debit_purchase_mirror_icbc", "low")
        if candidate.candidate_count > 1:
            return "review", MirrorPair(strong_row, weak_row, "debit_purchase_mirror_icbc", "low")
        if candidate.merchant_signal_kind == "stable_service":
            return "auto", MirrorPair(strong_row, weak_row, "debit_purchase_mirror_icbc", "high")
        if _cross_verify(strong_row, weak_row):
            return "auto", MirrorPair(strong_row, weak_row, "debit_purchase_mirror_icbc", "high")
        if (
            candidate.merchant_signal_kind == "merchant_consume"
            and _looks_like_specific_merchant_text(strong_row)
            and _looks_like_generic_channel_text(weak_row)
        ):
            return "auto", MirrorPair(strong_row, weak_row, "debit_purchase_mirror_icbc", "high")
        return "review", MirrorPair(strong_row, weak_row, "debit_purchase_mirror_icbc", "low")

    if candidate.weak_channel_kind == "weak_channel_unknown" and weak_row.get("bill_source") == "icbc_debit":
        if candidate.candidate_count != 1:
            return None
        if _cross_verify(strong_row, weak_row):
            confidence = "low" if candidate.merchant_signal_kind == "refund" else "high"
            bucket = "review" if confidence == "low" else "auto"
            return bucket, MirrorPair(strong_row, weak_row, "debit_purchase_mirror_icbc", confidence)
        if (
            candidate.merchant_signal_kind == "merchant_consume"
            and _looks_like_specific_merchant_text(strong_row)
            and _looks_like_generic_channel_text(weak_row)
        ):
            return "auto", MirrorPair(strong_row, weak_row, "debit_purchase_mirror_icbc", "high")

    return None


def detect_mirror_pairs(rows: list[dict]) -> MirrorDetectionResult:
    auto_candidates: list[tuple[float, int, MirrorPair]] = []
    review_candidates: list[tuple[float, int, MirrorPair]] = []
    for candidate in _build_mirror_candidates(rows):
        classified = _classify_candidate(candidate)
        if classified is None:
            continue
        bucket, pair = classified
        payload = (candidate.diff_seconds, candidate.candidate_count, pair)
        if bucket == "auto":
            auto_candidates.append(payload)
        else:
            review_candidates.append(payload)

    auto_drop_pairs = []
    review_pairs = []
    used_strong_ids = set()
    used_drop_ids = set()

    for diff, candidate_count, pair in sorted(auto_candidates, key=lambda item: (item[0], item[1])):
        if id(pair.keep_row) in used_strong_ids or id(pair.drop_row) in used_drop_ids:
            continue
        used_strong_ids.add(id(pair.keep_row))
        used_drop_ids.add(id(pair.drop_row))
        auto_drop_pairs.append(pair)

    for diff, candidate_count, pair in sorted(review_candidates, key=lambda item: (item[0], item[1])):
        if id(pair.keep_row) in used_strong_ids or id(pair.drop_row) in used_drop_ids:
            continue
        used_strong_ids.add(id(pair.keep_row))
        used_drop_ids.add(id(pair.drop_row))
        review_pairs.append(pair)

    for candidate in sorted(_build_loose_cross_source_candidates(rows), key=lambda item: (item.diff_seconds, item.candidate_count)):
        if id(candidate.keep_row) in used_strong_ids or id(candidate.drop_row) in used_drop_ids:
            continue
        if _is_safe_unique_cross_source_strong_candidate(candidate):
            pair = MirrorPair(
                candidate.keep_row,
                candidate.drop_row,
                "debit_purchase_mirror_icbc" if candidate.drop_row.get("bill_source") == "icbc_debit" else "card_channel_purchase_mirror",
                "high",
            )
            used_strong_ids.add(id(pair.keep_row))
            used_drop_ids.add(id(pair.drop_row))
            auto_drop_pairs.append(pair)
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
