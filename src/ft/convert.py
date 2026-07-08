"""convert — 账单 → 统一CSV"""
import csv
import sys
from datetime import datetime
from pathlib import Path

from .ai_apply import apply_convert_working_rows
from .ai_working_csv import (
    ALLOWED_ROW_STATUS,
    READ_ONLY_FIELDS,
    build_ai_working_row,
    is_allowed_ai_action,
    parse_ai_action_target,
    read_ai_working_csv,
    write_ai_working_csv,
)
from .mapping import load_rules, match_payment_method
from .pending import (
    clear_pending_session,
    create_pending_session,
    load_manifest,
    require_single_pending_session,
)

# 消费平台推断规则 — 从交易对方/描述中识别
PLATFORM_RULES = [
    ({"滴滴", "嘀嘀", "广州骑安", "先乘车后付款"}, "滴滴"),
    ({"高德打车", "高德地图", "高德信息技术", "高德云信"}, "高德"),
    ({"麦当劳"}, "麦当劳"),
    ({"汉堡王"}, "汉堡王"),
    ({"肯德基", "KFC"}, "肯德基"),
    ({"必胜客"}, "必胜客"),
    ({"海底捞"}, "海底捞"),
    ({"食其家"}, "食其家"),
    ({"争鲜寿司"}, "争鲜寿司"),
    ({"和府捞面"}, "和府捞面"),
    ({"袁记云饺"}, "袁记云饺"),
    ({"霸王茶姬"}, "霸王茶姬"),
    ({"茶百道"}, "茶百道"),
    ({"瑞幸咖啡", "luckin", "瑞幸", "luckincoffee"}, "瑞幸咖啡"),
    ({"奈雪"}, "奈雪"),
    ({"星巴克", "starbucks"}, "星巴克"),
    ({"LINLEE", "林里"}, "LINLEE林里"),
    ({"便利蜂", "梦想蜂"}, "便利蜂"),
    ({"7-11", "7-ELEVEN"}, "7-11"),
    ({"喜家德"}, "喜家德"),
    ({"新又好", "NewUhoo"}, "新又好"),
    ({"西部马华"}, "西部马华"),
    ({"立普世"}, "立普世"),
    ({"小红书"}, "小红书"),
    ({"bilibili", "哔哩哔哩", "B站", "b站"}, "B站"),
    ({"DeepSeek", "深度求索"}, "DeepSeek"),
    ({"携程", "去哪儿"}, "携程"),
    ({"飞猪"}, "飞猪"),
    ({"首开易生活", "预付费充电"}, "首开易生活"),
    ({"哈啰好物", "上海钧哈", "哈啰", "电动车租车"}, "哈啰"),
    ({"美团平台商户", "新渔阳滑雪场"}, "美团"),
    ({"先骑后付"}, "美团"),
    ({"北京象鲜科技", "小象超市", "美团买菜"}, "美团"),
    ({"三快在线", "三快科技"}, "美团"),
    ({"微信红包", "群收款", "转账"}, "微信"),
    ({"猫眼"}, "猫眼"),
    ({"Steam"}, "Steam"),
    ({"PIXIV", "FANBOX", "pixiv"}, "pixiv"),
    ({"中国电信"}, "中国电信"),
    ({"杭州乐读", "网易云音乐"}, "网易云音乐"),
    ({"天猫"}, "天猫"),
    ({"淘宝"}, "淘宝"),
    ({"拼多多"}, "拼多多"),
    ({"京东", "京东超市"}, "京东"),
]


def _infer_platform(counterparty: str, description: str, source: str) -> str:
    """从交易对方/说明推断消费平台，只识别公司级/连锁品牌，无匹配返回空"""
    text = (counterparty + " " + description).lower()
    # 已知误导排除：北京东子 含"京东"子串但无关
    text = text.replace("北京东子", "")

    for keywords, platform in PLATFORM_RULES:
        for kw in keywords:
            if kw.lower() in text:
                return platform
    return ""


# 支付源推断规则 — 信用卡账单中判断用了哪种支付
PAYMENT_SOURCE_RULES = [
    ("美团支付", "美团支付"),
    ("京东支付", "京东支付"),
    ("财付通(银联云闪付)", "银联云闪付"),
    ("财付通", "微信支付"),
    ("支付宝", "支付宝"),
    ("网银在线", "网银在线"),
    ("Apple.com/bill", "Apple Pay"),
    ("Apple Pay", "Apple Pay"),
    ("拼多多支付", "拼多多支付"),
    ("程支付", "携程"),
    ("抖音支付", "抖音支付"),
    ("银联云闪付", "银联云闪付"),
    ("云闪付", "云闪付"),
]


def _infer_payment_source(bill_type: str, counterparty: str, description: str) -> str:
    """推断支付源"""
    if bill_type == "ccb_debit":
        return "建行储蓄卡"
    if bill_type == "icbc_debit":
        return "银行卡"
    if bill_type == "alipay":
        return "支付宝"
    if bill_type == "wechat":
        return "微信"
    # ICBC 类：从描述/对方名中识别支付源
    text = (counterparty + " " + description).lower()
    for keyword, source in PAYMENT_SOURCE_RULES:
        if keyword.lower() in text:
            return source
    return "银行卡"


# counterparty 中去掉的支付源前缀（对应 PAYMENT_SOURCE_RULES 的关键词）
# 注意：Apple.com/bill 和 Apple Pay 不加前缀处理
STRIP_PREFIXES = [
    "美团支付-",
    "京东支付-",
    "财付通(银联云闪付)",
    "财付通-",
    "支付宝-",
    "网银在线-",
    "拼多多支付-",
    "程支付-",
    "抖音支付-",
]


def _strip_payment_prefix(counterparty: str) -> str:
    """去掉交易对方中已知的支付源前缀，保留纯商家名"""
    import re
    if not counterparty:
        return counterparty
    # 先去掉分期标记（如 "10/24 ", "5/12 "），避免破坏 startswith
    stripped = re.sub(r"^\d+/\d+\s*", "", counterparty)
    for prefix in STRIP_PREFIXES:
        if stripped.startswith(prefix):
            stripped = stripped[len(prefix):].lstrip("-")
            break
    return stripped


# O2O 平台前缀（从交易对方开头去掉，但结果非空才去掉）
O2O_PREFIXES = [
    "美团App",
    "饿了么",
    "大众点评",
    "高德团购",
]

# 品牌匹配后从 leftover 中去除的电商后缀
ECOMMERCE_SUFFIXES = [
    "自营旗舰店",
    "官方旗舰店",
    "旗舰店",
    "官方店",
    "专卖店",
    "专营店",
]


def _strip_platform_prefix(counterparty: str) -> str:
    """去掉交易对方中的O2O平台前缀（美团App/饿了么/大众点评/高德团购）。
    若去掉后为空，返回原值不变。"""
    if not counterparty:
        return counterparty
    for prefix in O2O_PREFIXES:
        if counterparty.startswith(prefix):
            stripped = counterparty[len(prefix):].lstrip()
            if stripped:
                return stripped
            return counterparty
    return counterparty


def _extract_leftover(cp: str, brand: str) -> str:
    """品牌匹配后，从原交易对方名中提取有用剩余文本。

    1. 依次去掉品牌关键词（优先最长）
    2. 去掉 O2O 平台前缀
    3. 去掉常见电商后缀（自营旗舰店等）
    4. 清理首尾标点/空格
    """
    import re

    if not cp:
        return ""

    # 找到该品牌对应的所有关键词
    brand_keywords = []
    for keywords, b in PLATFORM_RULES:
        if b == brand:
            brand_keywords = list(keywords)
            break

    if not brand_keywords:
        return ""

    leftover = cp
    cp_lower = leftover.lower()

    # 从长到短依次去掉关键词，避免被短关键词吃掉不该吃的部分
    for kw in sorted(brand_keywords, key=len, reverse=True):
        if kw.lower() in cp_lower:
            leftover = re.sub(re.escape(kw), "", leftover, flags=re.IGNORECASE)
            cp_lower = leftover.lower()

    # 去掉 O2O 平台前缀
    leftover = _strip_platform_prefix(leftover)

    # 去掉常见电商后缀
    for suffix in ECOMMERCE_SUFFIXES:
        if leftover.endswith(suffix):
            leftover = leftover[: -len(suffix)]
            break

    # 清理首尾标点/括号/空白
    leftover = re.sub(r"^[\s（(【\[、,，\-—]+", "", leftover)
    leftover = re.sub(r"[\s）)】\]、,，\-—]+$", "", leftover)

    return leftover.strip()


def _normalize_counterparty(raw_cp: str, raw_desc: str, source: str) -> tuple[str, str]:
    """三级回退规范化交易对方名。

    1. 去掉支付源前缀（_strip_payment_prefix）
    2. 品牌匹配（_infer_platform）→ 若命中，counterty=品牌名，剩余文本搬移到 description
       - 特殊：先骑后付 → cp=美团，desc=先骑后付
    3. O2O 平台前缀剥离（_strip_platform_prefix）→ 若变化，counterty=剥离结果
    4. 无匹配 → 原样返回
    """
    if not raw_cp:
        return (raw_cp, raw_desc)

    # Stage 1: 去掉支付源前缀
    cp = _strip_payment_prefix(raw_cp)
    desc = raw_desc

    # Stage 2: 品牌匹配
    brand = _infer_platform(cp, desc, source)
    if brand:
        # 特殊：先骑后付 → 美团
        if "先骑后付" in cp:
            new_desc = "先骑后付"
            if raw_desc:
                new_desc = f"先骑后付|{raw_desc}"
            return ("美团", new_desc)

        cp_before = cp
        cp = brand
        leftover = _extract_leftover(cp_before, brand)
        # 仅当原始描述为空或等于原始交易对方时，才用 leftover 替换
        if leftover and (not raw_desc or raw_desc == raw_cp):
            desc = leftover
        # 对于中介平台（淘宝/高德/美团等），尝试从描述中提取真实商户
        intermediary_brands = {"淘宝", "天猫", "美团", "高德", "大众点评"}
        if brand in intermediary_brands and raw_desc:
            import re
            if brand in ("淘宝", "天猫"):
                # 淘宝/天猫：description 首段含真实商户名
                # 提取到已知分隔符为止，无分隔符则整段作为商户名
                m = re.match(r"^(.+?)(?:外卖订单|订单|购物车)", raw_desc)
                if m:
                    merchant = m.group(1).strip(" ·-—")
                else:
                    merchant = raw_desc
                # 剔除已知平台相关行
                if merchant in ("超级吃货卡", "外卖红包", "淘宝"):
                    pass  # 保持 cp=淘宝
                elif merchant.startswith("淘宝"):
                    pass
                else:
                    # 剥离地址信息（括号内内容）
                    merchant = re.sub(r"[（(][^)）]*[)）]", "", merchant).strip()
                    if merchant and len(merchant) >= 2:
                        cp = merchant
                        desc = raw_desc
            elif brand == "高德":
                # 高德到店消费：description 含"XX商户 - 高德地图"模式
                m = re.match(r"^(.+?)\s*-\s*高德地图", raw_desc)
                if m:
                    cp = m.group(1).strip()
                    desc = raw_desc
                # 高德打车 → cp=高德 保持不变（正确）
        return (cp, desc)

    # Stage 3: O2O 平台前缀剥离
    stripped = _strip_platform_prefix(cp)
    if stripped != cp:
        return (stripped, desc)

    # No match
    return (cp, desc)


REFUND_MATCH_MAX_DAYS = 30
REFUND_AUTO_PASS_MAX_DAYS = 14


def _counterparty_matches(exp_cpy: str, ref_cpy: str) -> bool:
    """判断交易双方是否指向同一实体 — 精确/子串/前缀匹配"""
    if exp_cpy == ref_cpy:
        return True
    # 双方都不为空时做子串匹配
    if exp_cpy and ref_cpy and len(exp_cpy) >= 2 and len(ref_cpy) >= 2:
        if exp_cpy in ref_cpy or ref_cpy in exp_cpy:
            return True
    return False


def _parse_record_datetime(value: str) -> datetime:
    fmt = "%Y-%m-%d" if len(value) == 10 else "%Y-%m-%d %H:%M:%S"
    return datetime.strptime(value, fmt)


def _specific_payment_account(value: str) -> str:
    generic_methods = {
        "银行卡",
        "支付宝",
        "微信",
        "微信支付",
        "美团支付",
        "京东支付",
        "网银在线",
        "Apple Pay",
        "拼多多支付",
        "携程",
        "抖音支付",
        "银联云闪付",
        "云闪付",
    }
    value = (value or "").strip()
    if not value or value in generic_methods:
        return ""
    return value


def _refund_matches_basic_constraints(exp: dict, ref: dict, ref_amt: float, remaining_amount: float) -> bool:
    exp_account = _specific_payment_account(exp.get("payment_method", ""))
    ref_account = _specific_payment_account(ref.get("payment_method", ""))
    if exp_account and ref_account and exp_account != ref_account:
        return False
    if ref_amt > remaining_amount:
        return False
    if exp["date"] > ref["date"]:
        return False
    delta_days = (_parse_record_datetime(ref["date"]) - _parse_record_datetime(exp["date"])).days
    if delta_days > REFUND_MATCH_MAX_DAYS:
        return False
    return True


def _refund_source_signal(ref: dict) -> str:
    return ref.get("_refund_signal", "")


def _refund_signal_is_strong(ref: dict) -> bool:
    return _refund_source_signal(ref) in {
        "alipay_status",
        "alipay_category_nocount",
        "wechat_status",
        "icbc_credit_return",
        "ccb_debit_desc",
    }


def _alipay_refund_signal(*, txn_type: str, txn_status: str, direction: str, description: str) -> str:
    if txn_status == "退款成功":
        return "alipay_status"
    if txn_type == "退款" and direction == "不计收支":
        return "alipay_category_nocount"
    if "退款" in description:
        return "alipay_desc"
    return ""


def _classify_refund_match(*, ref: dict, rule_hint: str, exact_amt: bool,
                           candidate_count: int, expense: dict) -> tuple[str, bool]:
    if candidate_count != 1:
        return "weak", True
    if rule_hint in {"refund_desc_fallback", "refund_gross_candidate"}:
        return "weak", True
    if not _refund_signal_is_strong(ref):
        return "weak", True
    delta_days = (_parse_record_datetime(ref["date"]) - _parse_record_datetime(expense["date"])).days
    if delta_days > REFUND_AUTO_PASS_MAX_DAYS:
        return "weak", True
    if exact_amt:
        return "strong", False
    if rule_hint == "refund_raw_cp_match" and "***" in expense.get("counterparty", ""):
        return "weak", True
    return "strong", False


def _build_refund_tracking_pair(*, expense: dict, refund: dict, match_type: str,
                               rule_hint: str, match_strength: str,
                               pending_required: bool, candidate_count: int) -> dict:
    return {
        "expense": dict(expense),
        "refund": dict(refund),
        "match_type": match_type,
        "rule_hint": rule_hint,
        "match_strength": match_strength,
        "pending_required": pending_required,
        "candidate_count": candidate_count,
        "source_refund_signal": _refund_source_signal(refund),
    }


def _refund_txn_base_id(txn_id: str) -> str:
    txn_id = (txn_id or "").strip()
    if not txn_id or "_" not in txn_id:
        return ""
    return txn_id.split("_", 1)[0]


def _alipay_desc_order_key(description: str) -> str:
    import re

    description = (description or "").strip()
    patterns = [
        r"美团订单-([A-Za-z0-9]+)",
        r"商户单号([A-Za-z0-9]+)",
    ]
    for pattern in patterns:
        match = re.search(pattern, description)
        if match:
            return match.group(1)
    return ""


def _collect_order_based_refund_candidates(expenses: list, ref: dict, consumed: list[bool], remaining: list[float], ref_amt: float):
    matches: dict[int, str] = {}
    merchant_order_id = (ref.get("merchant_order_id", "") or "").strip()
    refund_txn_base_id = _refund_txn_base_id(ref.get("txn_id", ""))
    desc_order_key = _alipay_desc_order_key(ref.get("description", ""))

    def try_add(expense_index: int, rule_hint: str):
        if consumed[expense_index]:
            return
        exp = expenses[expense_index]
        if not _refund_matches_basic_constraints(exp, ref, ref_amt, remaining[expense_index]):
            return
        matches.setdefault(expense_index, rule_hint)

    if merchant_order_id:
        for i, exp in enumerate(expenses):
            if (exp.get("merchant_order_id", "") or "").strip() == merchant_order_id:
                try_add(i, "refund_merchant_order_match")
    if refund_txn_base_id:
        for i, exp in enumerate(expenses):
            if (exp.get("txn_id", "") or "").strip() == refund_txn_base_id:
                try_add(i, "refund_txn_base_match")
    if desc_order_key:
        for i, exp in enumerate(expenses):
            exp_desc_order_key = _alipay_desc_order_key(exp.get("description", ""))
            if exp_desc_order_key and exp_desc_order_key == desc_order_key:
                try_add(i, "refund_desc_order_match")

    candidates = []
    for i, rule_hint in matches.items():
        exp = expenses[i]
        exact_amt = abs(remaining[i] - ref_amt) < 0.01
        desc_match = bool(ref.get("description")) and (
            ref["description"] == exp.get("description", "")
            or ref["description"] in exp.get("description", "")
            or exp.get("description", "") in ref["description"]
        )
        candidates.append({
            "expense_index": i,
            "exact_amt": exact_amt,
            "desc_match": desc_match,
            "expense_date": exp["date"],
            "rule_hint": rule_hint,
        })
    return candidates


def _pair_refunds(expenses: list, refunds: list, others: list):
    """通用的退款配对核销逻辑（Alipay 和 WeChat 共用）
    返回 (cleaned_records, tracking_pairs)
    """
    expense_ids = {id(r) for r in expenses}
    refund_ids = {id(r) for r in refunds}
    others = [r for r in others if id(r) not in expense_ids and id(r) not in refund_ids]

    consumed = [False] * len(expenses)
    remaining = [abs(exp["amount"]) for exp in expenses]
    tracking_pairs = []

    for ref in sorted(refunds, key=lambda x: x["date"]):
        ref_amt = abs(ref["amount"])
        candidates = _collect_order_based_refund_candidates(expenses, ref, consumed, remaining, ref_amt)
        if not candidates:
            for i, exp in enumerate(expenses):
                if consumed[i]:
                    continue
                rule_hint = "refund_cp_match"
                # 尝试匹配：先比 normalized cp，再比原始 cp（ICBC 退款匹配需要）
                if not _counterparty_matches(exp["counterparty"], ref["counterparty"]):
                    raw_cp = exp.get("_raw_cp", "")
                    if not raw_cp or not _counterparty_matches(raw_cp, ref["counterparty"]):
                        continue
                    rule_hint = "refund_raw_cp_match"
                if not _refund_matches_basic_constraints(exp, ref, ref_amt, remaining[i]):
                    continue

                exact_amt = abs(remaining[i] - ref_amt) < 0.01
                desc_match = bool(ref["description"]) and (
                    ref["description"] == exp["description"]
                    or ref["description"] in exp["description"]
                    or exp["description"] in ref["description"]
                )
                candidates.append({
                    "expense_index": i,
                    "exact_amt": exact_amt,
                    "desc_match": desc_match,
                    "expense_date": exp["date"],
                    "rule_hint": rule_hint,
                })

        if not candidates:
            # 用描述兜底匹配（当对方名不可靠时）
            for i, exp in enumerate(expenses):
                if consumed[i]:
                    continue
                if not _refund_matches_basic_constraints(exp, ref, ref_amt, remaining[i]):
                    continue
                if not exp["description"] or not ref["description"]:
                    continue
                if (ref["description"] == exp["description"]
                        or ref["description"] in exp["description"]
                        or exp["description"] in ref["description"]):
                    exact = abs(remaining[i] - ref_amt) < 0.01
                    candidates.append({
                        "expense_index": i,
                        "exact_amt": exact,
                        "desc_match": True,
                        "expense_date": exp["date"],
                        "rule_hint": "refund_desc_fallback",
                    })

        if not candidates:
            # 孤退款：检查是否为"原始全额退款"——退款金额等于某笔支出的原始金额（非剩余）
            # 适用于支付宝中同时存在净退款(83.5)和全额退款(1025.5)的场景
            gross_matched = False
            for i, exp in enumerate(expenses):
                if consumed[i]:
                    continue
                raw_amt = abs(exp["amount"])
                if (
                    abs(raw_amt - ref_amt) < 0.01
                    and _counterparty_matches(exp["counterparty"], ref["counterparty"])
                    and _refund_matches_basic_constraints(exp, ref, ref_amt, raw_amt)
                ):
                    consumed[i] = True
                    match_strength, pending_required = _classify_refund_match(
                        ref=ref,
                        rule_hint="refund_gross_candidate",
                        exact_amt=True,
                        candidate_count=1,
                        expense=expenses[i],
                    )
                    tracking_pairs.append(_build_refund_tracking_pair(
                        expense=expenses[i],
                        refund=ref,
                        match_type="full",
                        rule_hint="refund_gross_candidate",
                        match_strength=match_strength,
                        pending_required=pending_required,
                        candidate_count=1,
                    ))
                    gross_matched = True
                    break
            if gross_matched:
                continue
            # 真孤退款 → income
            others.append({
                "date": ref["date"],
                "amount": ref["amount"],
                "currency": ref.get("currency", "CNY"),
                "payment_method": ref["payment_method"],
                "card_number": ref.get("card_number", ""),
                "counterparty": _strip_payment_prefix(ref["counterparty"]),
                "description": ref["description"],
                "category": "income",
            })
            continue

        # 优先级：精确金额 > 说明匹配 > 最近消费
        candidates.sort(key=lambda c: (c["exact_amt"], c["desc_match"], c["expense_date"]), reverse=True)
        best = candidates[0]
        best_idx = best["expense_index"]
        exact_amt = best["exact_amt"]
        rule_hint = best["rule_hint"]
        match_strength, pending_required = _classify_refund_match(
            ref=ref,
            rule_hint=rule_hint,
            exact_amt=exact_amt,
            candidate_count=len(candidates),
            expense=expenses[best_idx],
        )

        if exact_amt:
            consumed[best_idx] = True
            tracking_pairs.append(_build_refund_tracking_pair(
                expense=expenses[best_idx],
                refund=ref,
                match_type="full",
                rule_hint=rule_hint,
                match_strength=match_strength,
                pending_required=pending_required,
                candidate_count=len(candidates),
            ))
        else:
            original_amount = -remaining[best_idx]
            remaining[best_idx] = round(remaining[best_idx] - ref_amt, 2)
            tracking_pairs.append(_build_refund_tracking_pair(
                expense={**expenses[best_idx], "amount": original_amount},
                refund=ref,
                match_type="partial",
                rule_hint=rule_hint,
                match_strength=match_strength,
                pending_required=pending_required,
                candidate_count=len(candidates),
            ))

    result = []
    for i, exp in enumerate(expenses):
        if not consumed[i]:
            result.append({
                "date": exp["date"],
                "amount": -remaining[i],
                "currency": exp.get("currency", "CNY"),
                "payment_method": exp["payment_method"],
                "card_number": exp.get("card_number", ""),
                "counterparty": exp["counterparty"],
                "description": exp["description"],
                "category": "expense",
            })
    result.extend(others)
    return result, tracking_pairs


def _read_alipay_raw(path: str):
    """解析支付宝CSV，不落库，返回 list[dict]"""
    from .importers.alipay import _detect_encoding
    import csv as csv_mod

    enc = _detect_encoding(path)
    with open(path, "r", encoding=enc) as f:
        text = f.read()
    lines = text.splitlines()

    header_ln = None
    for i, line in enumerate(lines):
        if "交易时间" in line and "收/支" in line and "金额" in line:
            header_ln = i
            break
    if header_ln is None:
        print("❌ 无法找到支付宝账单表头")
        return []

    reader = csv_mod.reader(lines[header_ln:])
    header = next(reader)
    h = {col: idx for idx, col in enumerate(header)}

    raw = []  # (date_str, amount, payment_method, counterparty, description, category, txn_type)
    for row in reader:
        if len(row) < 7:
            raise ValueError(
                f"❌ 支付宝账单行缺少字段: 仅 {len(row)} 列，预期 >= 7\n"
                f"   row={row}\n"
                f"   可能是支付宝导出的格式已变更，需要更新转换器"
            )
        date_str = row[h.get("交易时间", 0)].strip()[:19].replace("/", "-")
        direction = row[h.get("收/支", 5)].strip()
        amount_str = row[h.get("金额", 6)].strip()
        txn_type = row[h.get("交易分类", 1)].strip()
        try:
            amount = float(amount_str)
        except ValueError:
            raise ValueError(
                f"❌ 支付宝账单金额无法解析: amount_str={amount_str!r}\n"
                f"   date={date_str} direction={direction} type={txn_type}"
            )
        if amount == 0:
            # 0 元交易：会员卡抵扣、积分兑换等，无资金变动，保留记录但不计入财务
            category = "expense" if direction == "支出" else "income"
        else:
            category = "expense" if amount < 0 else "income"
            if direction == "支出":
                amount = -amount
            elif direction == "收入":
                pass
            elif direction == "不计收支":
                # 支付宝提现到银行卡在原始账单里是"不计收支"，但从支付宝余额账户视角是资产流出。
                # 退款类不计收支仍保持正数，交给 _pair_refunds 配对核销。
                desc_for_direction = row[h.get("商品说明", 4)].strip() if "商品说明" in h else ""
                is_investment_outflow = (
                    txn_type == "投资理财"
                    and "收益发放" not in desc_for_direction
                    and "转出到银行卡" not in desc_for_direction
                    and any(k in desc_for_direction for k in ("转入", "买入", "单次转入"))
                )
                if (
                    txn_type == "账户提现"
                    or "提现-实时提现" in desc_for_direction
                    or "转出到网商银行" in desc_for_direction
                    or is_investment_outflow
                ):
                    amount = -amount
                    category = "expense"
            else:
                raise ValueError(
                    f"❌ 支付宝账单未知收/支方向: direction={direction!r}\n"
                    f"   date={date_str} type={txn_type} amount={amount_str}\n"
                    f"   请确认是否需更新转换器以支持新的方向类型"
                )

        payment_method = row[h.get("收/付款方式", 7)].strip() if "收/付款方式" in h else ""
        counterparty = row[h.get("交易对方", 2)].strip()
        desc = row[h.get("商品说明", 4)].strip() or counterparty
        txn_type = row[h.get("交易分类", 1)].strip()

        # 交易状态：用于识别"下单未付款"的假交易
        txn_status = row[h.get("交易状态", 8)].strip() if "交易状态" in h else ""

        # 交易关闭/已关闭/还款失败 → 没有实际资金流动，跳过（不管收/支方向）
        if txn_status in ("交易关闭", "已关闭", "还款失败"):
            continue

        # 方向=不计收支 + 金额=0 → 预授权解冻/冻结解冻等，无实际资金流动，跳过
        if direction == "不计收支" and amount == 0:
            continue

        if amount != 0:
            category = "expense" if amount < 0 else "income"

        normalized_cp, enriched_desc = _normalize_counterparty(counterparty, desc[:80], "alipay")
        refund_signal = _alipay_refund_signal(
            txn_type=txn_type,
            txn_status=txn_status,
            direction=direction,
            description=enriched_desc,
        )
        txn_id = row[h.get("交易订单号", 9)].strip() if "交易订单号" in h else ""
        merchant_order_id = row[h.get("商家订单号", 10)].strip() if "商家订单号" in h else ""
        raw.append({
            "date": date_str,
            "amount": round(amount, 2),
            "payment_method": payment_method,
            "counterparty": normalized_cp,
            "description": enriched_desc[:80],
            "category": category,
            "txn_type": txn_type,
            "txn_id": txn_id,
            "merchant_order_id": merchant_order_id,
            "_alipay_direction": direction,
            "_refund_signal": refund_signal,
        })

    # 退款配对核销
    expenses = [r for r in raw if r["category"] == "expense"]
    refunds = [r for r in raw if r["amount"] > 0 and r.get("_refund_signal")]
    records, tracking_pairs = _pair_refunds(expenses, refunds, raw)
    return records, tracking_pairs


def _read_wechat_raw(path: str):
    """解析微信Excel，不落库，返回 list[dict]"""
    try:
        import openpyxl
    except ImportError:
        print("❌ 需要 openpyxl: pip install openpyxl")
        return []

    from .importers.wechat import INCOME_OK

    wb = openpyxl.load_workbook(path)
    ws = wb.active

    header_row_i = None
    for i, row in enumerate(ws.iter_rows(min_row=1, max_row=50, values_only=True), 1):
        if row[0] and "交易时间" in str(row[0]):
            header_row_i = i
            break
    if not header_row_i:
        print("❌ 无法找到微信账单表头")
        return []

    header = [str(c or "") for c in next(ws.iter_rows(min_row=header_row_i, max_row=header_row_i, values_only=True))]
    h = {col: idx for idx, col in enumerate(header)}

    raw = []
    for row in ws.iter_rows(min_row=header_row_i + 1, values_only=True):
        if not row or not any(v for v in row if v is not None):
            continue
        vals = [str(c or "") for c in row]
        direction = vals[h["收/支"]] if "收/支" in h else ""
        status = vals[h["当前状态"]] if "当前状态" in h else ""

        if direction == "支出":
            # 保留所有实际发生的消费（包括已退款的），只排除明确失败状态
            if status in ("交易失败", "已关闭", "已撤销"):
                continue
        if direction == "收入":
            is_refund = "退款" in status
            if not is_refund and status not in INCOME_OK:
                continue

        try:
            amount = float(vals[h["金额(元)"]])
        except (ValueError, KeyError):
            continue
        
        # 提前提取所有字段，后面会按需使用
        payment_method = vals[h["支付方式"]] if "支付方式" in h else ""
        counterparty = vals[h["交易对方"]] if "交易对方" in h else ""
        desc = vals[h["商品"]] if "商品" in h else ""
        txn_type = vals[h["交易类型"]] if "交易类型" in h else ""
        date_raw = vals[h["交易时间"]] if "交易时间" in h else ""
        date_str = date_raw[:19].replace("/", "-")

        if direction == "支出":
            amount = -amount
        elif direction == "收入":
            pass
        elif txn_type in ("零钱提现", "充值", "零钱充值", "零钱通存取", "理财通", "购买理财通", "信用卡还款"):
            # 微信中性交易（收/支="/"）金额列始终为正，不能按金额正负判断方向，必须按交易类型语义判断。
            if txn_type == "零钱提现":
                # 从微信零钱提现到银行卡：当前记录落在到账银行卡/支付方式账户，记入账。
                category = "income"
            elif txn_type in ("充值", "零钱充值", "购买理财通", "信用卡还款"):
                # 银行卡/零钱流出到微信零钱、理财通或信用卡还款。
                amount = -amount
                category = "expense"
            elif txn_type in ("零钱通存取", "理财通"):
                text = f"{counterparty}{desc}{status}"
                if any(k in text for k in ("转出", "取出", "赎回", "到账")):
                    category = "income"
                else:
                    amount = -amount
                    category = "expense"
            else:
                continue
            # 描述为空或无意义时，用交易类型代替
            if not desc or desc in ("/", "-"):
                desc = txn_type
            normalized_cp, enriched_desc = _normalize_counterparty(counterparty, desc[:80], "wechat")
            raw.append({
                "date": date_str,
                "amount": round(amount, 2),
                "payment_method": payment_method,
                "counterparty": normalized_cp,
                "description": enriched_desc[:80],
                "category": category,
                "status": status,
                "_refund_signal": "wechat_status" if "退款" in status else "",
            })
            continue
        else:
            continue
        if amount == 0:
            continue
        # 描述为空或无意义时，用交易类型代替
        if not desc or desc in ("/", "-"):
            desc = txn_type

        # convert 层只看收支方向，不做语义判断
        category = "expense" if amount < 0 else "income"

        normalized_cp, enriched_desc = _normalize_counterparty(counterparty, desc[:80], "wechat")
        raw.append({
            "date": date_str,
            "amount": round(amount, 2),
            "payment_method": payment_method,
            "counterparty": normalized_cp,
            "description": enriched_desc[:80],
            "category": category,
            "status": status,
            "_refund_signal": "wechat_status" if "退款" in status else "",
        })

    # 退款配对核销
    expenses = [r for r in raw if r["category"] == "expense"]
    refunds = [r for r in raw if r["amount"] > 0 and "退款" in r["status"]]
    records, tracking_pairs = _pair_refunds(expenses, refunds, raw)
    return records, tracking_pairs


def _parse_icbc_lines(lines: list[str], is_credit: bool):
    """解析 ICBC PDF 文本行，返回统一 records（纯函数，方便单测）"""
    import re
    records = []

    if is_credit:
        i, current_date, current_time, current_card = 0, None, "00:00:00", ""
        while i < len(lines):
            line = lines[i].strip()
            dm = re.match(r"^(\d{4}-\d{2}-\d{2})$", line)
            if dm:
                current_date = dm.group(1)
                current_time = lines[i+1].strip() if i + 1 < len(lines) else "00:00:00"
                # 卡号在时间行下一行（16~19位纯数字）
                card_line = lines[i+2].strip() if i + 2 < len(lines) else ""
                current_card = card_line if re.match(r"^\d{16,19}$", card_line) else ""
                i += 1
                continue
            if not current_date:
                i += 1
                continue
            amt_m = re.match(r"^([+-]?[\d,]+\.\d{2})$", line)
            if amt_m:
                amount = _parse_amt(amt_m.group(1))

                # 后向扫描：找币种 + 借/贷方向（出现在金额行之前）
                currency = "CNY"
                is_charge = False
                _skip_keywords = {"交易币种", "入账币种", "入账金额", "账户余额",
                                  "对方户名", "对方账号", "摘要", "交易场所",
                                  "交易卡号", "收", "支", "消费", "活期",
                                  "钞", "汇"}
                for k in range(i - 1, max(-1, i - 20), -1):
                    if k < 0:
                        break
                    cur_raw = lines[k].strip()
                    if not cur_raw:
                        continue
                    if cur_raw == "借" and not is_charge:
                        is_charge = True
                        continue
                    if cur_raw == "贷":
                        continue
                    if cur_raw in ("美元", "USD"):
                        currency = "USD"
                        continue
                    if cur_raw in ("港币", "HKD"):
                        currency = "HKD"
                        continue
                    if cur_raw in ("日元", "JPY"):
                        currency = "JPY"
                        continue
                    if cur_raw in ("人民币",):
                        continue  # 人民币标志，继续往前找借/贷
                    if cur_raw in _skip_keywords or re.match(r"^[+-]?[\d,]+\.\d{2}$", cur_raw):
                        continue  # 已知标签或金额行，跳过
                    break  # 其他非空行→停止后向扫描
                if is_charge:
                    amount = -abs(amount)
                category = "expense" if amount < 0 else "income"

                # 从金额行向后扫描
                counterparty = ""
                description = ""
                j = i + 1
                while j < min(len(lines), i + 12):
                    s = lines[j].strip()
                    j += 1
                    if re.match(r"^[+-]?[\d,]+\.[\d]{2}$", s):
                        continue
                    if re.match(r"^\d{4}-\d{2}-\d{2}$", s):  # 跳过日期行
                        continue
                    if re.match(r"^\d{2}:\d{2}:\d{2}$", s):  # 跳过时间行
                        continue
                    if "本页" in s:  # 跳过页合计行
                        continue
                    if not s:
                        continue
                    if s in ("人民币", "美元", "港币", "日元", "消费", "借", "贷",
                             "对方户名", "对方账号", "摘要", "交易场所",
                             "交易卡号", "收", "支", "交易币种", "入账币种",
                             "入账金额", "账户余额"):
                        continue
                    if s.startswith("下单时间"):  # 跳过页脚元数据行
                        continue
                    if not counterparty and not re.match(r"^\d{4,}$", s) and "****" not in s:
                        counterparty = s
                        continue
                    if counterparty and re.match(r"^\d", s) and "****" in s:
                        continue
                    if counterparty and re.match(r"^\d{4,}$", s):
                        continue
                    if s in ("转帐", "退款", "利息", "结息"):
                        continue
                    if s.startswith("手机银行") or s.startswith("网上银行"):
                        description = s
                        counterparty = counterparty if counterparty else ""
                        break
                    if any(kw in s for kw in ["美团支付", "京东支付", "财付通",
                                               "支付宝", "网银在线", "Apple",
                                               "拼多多支付", "程支付", "抖音支付"]):
                        description = s
                        break
                    if counterparty and s != counterparty:
                        if counterparty == "退货":
                            description = s  # 退货不合并，留 description 用于退款配对
                        else:
                            counterparty = counterparty + s  # 商家名换行合并
                        break
                    counterparty = s

                normalized_cp, enriched_desc = _normalize_counterparty(counterparty, description[:80], "icbc")
                rec = {
                    "date": f"{current_date} {current_time}",
                    "amount": round(amount, 2),
                    "currency": currency,
                    "counterparty": normalized_cp,
                    "description": enriched_desc[:80],
                    "category": category,
                    "payment_method": _infer_payment_source("icbc", normalized_cp, enriched_desc[:80]),
                    "card_number": current_card[-4:] if current_card else "",
                    "_raw_cp": counterparty,  # 保存原始 cp 用于退款匹配
                    "_refund_signal": "",
                }
                if counterparty == "退货":
                    rec["_is_refund"] = True
                    rec["_refund_signal"] = "icbc_credit_return"
                records.append(rec)
                current_date = None
            i += 1
    else:
        i = 0
        while i < len(lines):
            line = lines[i].strip()
            amt_m = re.match(r"^([+-][\d,]+\.[\d]{2})$", line)
            if not amt_m:
                i += 1
                continue
            amount = _parse_amt(amt_m.group(1))
            date = ""
            date_line_idx = -1
            for lookback in range(1, min(11, i + 1)):
                potential = lines[i - lookback].strip()
                dm = re.match(r"^(\d{4}-\d{2}-\d{2})$", potential)
                if dm:
                    date = dm.group(1)
                    date_line_idx = i - lookback
                    break
            if not date:
                i += 1
                continue

            # 提取时间（日期下行）
            time_str = "00:00:00"
            if date_line_idx + 1 < len(lines):
                time_candidate = lines[date_line_idx + 1].strip()
                if re.match(r"^\d{2}:\d{2}:\d{2}$", time_candidate):
                    time_str = time_candidate

            ctx_text = " ".join(lines[max(0, date_line_idx):min(len(lines), i + 8)])
            description = ""
            for j in range(date_line_idx + 1, i):
                s = lines[j].strip()
                if s and len(s) <= 10 and s not in ("活期", "00000", "人民币", "钞", "汇", "1614", "4600", "2116", "6982") \
                        and not re.match(r"^\d{2}:\d{2}:\d{2}$", s):
                    summary = s.replace("支", "").strip()
                    if summary:
                        description = summary
                        break

            cpy = ""
            for j in range(i + 1, min(len(lines), i + 6)):
                s = lines[j].strip()
                if s and not re.match(r"^[\d,]+\.\d{2}$", s):
                    if s not in ("手机银行", "网上银行", "快捷支付", "其他", "批量业务", "(空)"):
                        cpy = s
                        break

            is_reversal = "撤销" in ctx_text
            if is_reversal:
                i += 1
                continue

            # convert 层只看收支方向
            category = "expense" if amount < 0 else "income"

            normalized_cp, enriched_desc = _normalize_counterparty(cpy, description[:80], "icbc")
            records.append({
                "date": f"{date} {time_str}",
                "amount": round(amount, 2),
                "counterparty": normalized_cp,
                "description": enriched_desc[:80] or normalized_cp[:80],
                "category": category,
            })
            i += 1

    # ICBC 退货配对核销（用 description 中的原始商家匹配对应消费）
    expenses = [r for r in records if r["category"] == "expense"]
    refunds = [r for r in records if r["amount"] > 0 and r.get("_is_refund")]
    if refunds:
        for r in refunds:
            if r.get("description"):
                # 用原始描述匹配，保留 _raw_cp 用于 _pair_refunds
                r["counterparty"] = r["description"]
        records, tracking_pairs = _pair_refunds(expenses, refunds, records)
        # 退款配对后，normalize tracking pair 中的 counterparty
        for pair in tracking_pairs:
            for key in ("expense", "refund"):
                raw = pair[key].get("counterparty", "")
                desc = pair[key].get("description", "")
                new_cp, new_desc = _normalize_counterparty(raw, desc, "icbc")
                pair[key]["counterparty"] = new_cp
                pair[key]["description"] = new_desc
        # 过滤 ICBC 孤退款收入行（cp 为"消费"/"财付通"的 refund orphan，非真实收入）
        records = [r for r in records if not (
            r["category"] == "income"
            and r.get("counterparty", "") in ("消费", "财付通")
        )]
    else:
        tracking_pairs = []

    return records, tracking_pairs


def _read_icbc_raw(path: str, password: str):
    """解析工行PDF，不落库，返回 (list[dict], bill_type, tracking_pairs)"""
    import subprocess, os

    decrypted = path + ".decrypted.pdf"
    ret = subprocess.run(
        ["qpdf", "--decrypt", "--password=" + password, path, decrypted],
        capture_output=True, text=True, timeout=30,
    )
    if ret.returncode != 0:
        print(f"❌ 解密失败: {ret.stderr.strip()}")
        return [], "", []

    txt_path = path + ".txt"
    ret = subprocess.run(
        ["mutool", "draw", "-F", "text", "-o", txt_path, decrypted],
        capture_output=True, text=True, timeout=60,
    )
    os.unlink(decrypted)
    if ret.returncode != 0:
        print(f"❌ 提取文本失败: {ret.stderr.strip()}")
        return [], "", []

    with open(txt_path, encoding="utf-8") as f:
        text = f.read()
    os.unlink(txt_path)

    is_credit = "信用卡" in text
    lines = text.split("\n")
    records, tracking_pairs = _parse_icbc_lines(lines, is_credit=is_credit)
    return records, "icbc_credit" if is_credit else "icbc_debit", tracking_pairs


def _pair_reversals(records: list) -> tuple[list, list]:
    """配对反向冲销交易（撤销交易、退款等）。

    匹配条件：
    1. 金额相反（abs(a) == abs(b)）
    2. 同一对方（counterparty 相同）
    3. 收入行的 description 包含 "撤销"（说明这是撤销操作）
    4. 收入时间 >= 支出时间，且在同一天内
    """
    expenses = [(i, r) for i, r in enumerate(records) if r["category"] == "expense"]
    incomes = [(i, r) for i, r in enumerate(records)
               if r["category"] == "income" and "撤销" in r.get("description", "")]

    tracking_pairs = []
    paired_exp = set()
    paired_inc = set()

    for ii, inc in incomes:
        inc_amt = abs(inc["amount"])
        for ei, exp in expenses:
            if ei in paired_exp:
                continue
            if abs(abs(exp["amount"]) - inc_amt) > 0.005:
                continue
            if exp["counterparty"] != inc["counterparty"]:
                continue
            if exp["date"][:10] != inc["date"][:10]:
                continue
            if exp["date"] > inc["date"]:
                continue

            # 匹配成功
            paired_exp.add(ei)
            paired_inc.add(ii)
            tracking_pairs.append({
                "expense": dict(exp),
                "refund": dict(inc),
                "match_type": "full",
            })
            break

    result = []
    for i, rec in enumerate(records):
        if i in paired_exp or i in paired_inc:
            continue
        result.append(rec)

    return result, tracking_pairs


def _read_icbc_debit_raw(path: str, password: str):
    """解析工行储蓄卡PDF（表格格式），返回 (list[dict], bill_type)"""
    import pdfplumber as _pp

    pdf = _pp.open(path, password=password)
    records = []

    for page in pdf.pages:
        # 过滤水印大字（字号≥15），保留表格文字
        filtered = page.filter(lambda obj: obj.get("object_type") != "char" or obj.get("size", 99) < 15)
        tables = filtered.extract_tables()
        if not tables:
            continue
        for row in tables[0][1:]:  # 跳过表头
            if not row or not row[0]:
                continue
            rec = _parse_icbc_debit_row(row)
            if rec:
                records.append(rec)

    pdf.close()

    records, rev_pairs = _pair_reversals(records)
    return records, "icbc_debit", rev_pairs


def _parse_icbc_debit_row(row: list) -> dict | None:
    """解析储蓄卡PDF的一行表格数据"""
    import re as _re

    dt_str = (row[0] or "").replace("\n", " ")
    dm = _re.search(r"(\d{4}-\d{2}-\d{2})", dt_str)
    tm = _re.search(r"(\d{2}:\d{2}:\d{2})", dt_str)
    if not dm:
        raise ValueError(
            f"❌ 工行借记卡行无法提取日期: row={row}\n"
            f"   可能是PDF格式变更，需要更新转换器"
        )
    # 保护：pdfplumber 可能返回截断行
    if len(row) < 13:
        raise ValueError(
            f"❌ 工行借记卡行列数不足: 仅 {len(row)} 列，预期 >= 13\n"
            f"   row={row}\n"
            f"   可能是pdfplumber解析结果截断或PDF格式变更"
        )
    date = f"{dm.group(1)} {tm.group(1) if tm else '00:00:00'}"

    # 币种
    cur_str = (row[4] or "").replace("\n", "").strip()
    cur_map = {"人民币": "CNY", "美元": "USD", "港币": "HKD", "日元": "JPY"}
    currency = cur_map.get(cur_str, "CNY")

    # 金额 — 水印已过滤，干净的数字
    amt_str = (row[8] or "").replace("\n", "").strip()
    amt_m = _re.search(r"([+-])?([\d,]+\.[\d]{2})", amt_str)
    if not amt_m:
        raise ValueError(
            f"❌ 工行借记卡行金额无法解析: amt_str={amt_str!r}\n"
            f"   row={row}\n"
            f"   可能是PDF格式变更或水印干扰"
        )
    sign = amt_m.group(1) or ""
    num = float(amt_m.group(2).replace(",", ""))
    amount = -num if sign == "-" else num

    # 摘要 — 匹配已知关键词（水印噪声可能把关键词拆散）
    summary = _match_debit_summary((row[6] or "").replace("\n", "").strip())

    # 对方户名
    counterparty = (row[10] or "").replace("\n", "").strip()

    # 清洗对方户名：摘要文本可能因水印/换行混入 counterparty
    if counterparty and summary:
        # 用 _is_subseq 检测（乱码后字符不一定连续出现）
        if _is_subseq("基金快速赎回", counterparty) or _is_subseq("基金赎回", counterparty):
            counterparty = "中国工商银行股份有限公司基金清算专户"
        elif _is_subseq("基金购买", counterparty):
            counterparty = "中国工商银行股份有限公司基金清算专户"

    # 渠道
    channel = (row[12] or "").replace("\n", "").strip()

    category = "expense" if amount < 0 else "income"

    return {
        "date": date,
        "amount": amount,
        "currency": currency,
        "counterparty": counterparty,
        "description": summary,
        "category": category,
        "payment_method": channel,
    }


def _match_debit_summary(text: str) -> str:
    """从含噪声的摘要文本中匹配已知交易类型关键词"""
    import re as _re

    # 已知摘要关键词，按频率/重要性排序
    KWS = [
        "支付宝转账", "无卡支付", "工资", "转账", "购汇还款", "撤销交易",
        "个人购汇", "跨境汇款", "利息", "基金购买", "基金赎回",
        "他行汇入", "银联入账", "预约购汇", "网转", "跨行汇款",
    ]

    # 去除非中文噪声，保留中文字符
    clean = _re.sub(r"[^\u4e00-\u9fff]", "", text)

    for kw in KWS:
        # 检查关键词中的所有字是否都在clean中按顺序出现
        if _is_subseq(kw, clean):
            return kw
    # 兜底：回退原始文本（去噪声）
    return clean


def _is_subseq(pattern: str, text: str) -> bool:
    """检查 pattern 的字符是否按顺序出现在 text 中（用于噪声中匹配关键词）"""
    it = iter(text)
    return all(ch in it for ch in pattern)


def _parse_amt(s: str) -> float:
    s = s.strip().replace(",", "").replace("+", "")
    try:
        return float(s)
    except ValueError:
        return 0.0


def _extract_merchant(ctx: str, nearby: list) -> str:
    import re as _re
    candidates = []
    for line in nearby:
        s = line.strip()
        if s in ("", "借", "贷", "消费", "入账日期", "交易卡号", "收", "支",
                 "交易币种", "入账币种", "入账金额", "账户余额",
                 "人民币", "美元", "港币", "欧元", "日元",
                 "对方户名", "对方账号", "摘要", "交易场所"):
            continue
        if _re.match(r"^[\d,]+\.[\d]{2}$", s):
            continue
        if _re.match(r"^\d{4}-\d{2}-\d{2}$", s):
            continue
        if _re.match(r"^\d{2}:\d{2}:\d{2}$", s):
            continue
        if len(s) < 2:
            continue
        if _re.match(r"^\d{16,}$", s):   # 过滤卡号（非商户名）
            continue
        candidates.append(s)

    for c in candidates:
        for kw in ["美团支付-", "京东支付-", "财付通-", "支付宝-", "网银在线-"]:
            if kw in c:
                after = c.split(kw, 1)[1]
                after = after.split(",")[0].split("（")[0].strip()
                after = after.split("…")[0].strip()
                return f"{kw.split('-')[0]}-{after[:24]}"

    candidates = [c for c in candidates if c != "消费"]
    return candidates[0][:60] if candidates else ""


def _route_account(rec, rules, default_action, bill_type):
    """路由单条 rec 到账户名（复用 do_convert 中的映射逻辑）"""
    from .mapping import match_payment_method
    card_num = rec.get("card_number", "")
    if card_num:
        match = match_payment_method(rules, f"{bill_type}_{card_num}", "*")
    else:
        match = None
    if not match:
        match = match_payment_method(rules, bill_type, rec.get("payment_method", ""))
    if match:
        return match["account"]
    return "未知"


def _build_refund_tracking_rows(tracking_pairs, rules, default_action, bill_type):
    """将 tracking_pairs 转成 10 列追踪 CSV 行（每对两行）"""
    rows = []
    for pair in tracking_pairs:
        exp = pair["expense"]
        ref = pair["refund"]
        strength = pair.get("match_strength", "")
        status_suffix = f"[{strength}]" if strength else ""

        # 消费行
        exp_net = round(exp["amount"] + ref["amount"], 2)
        exp_status = "已全额退款" if abs(exp_net) < 0.01 else \
                     f"已部分退款(净额{exp_net})"
        if status_suffix:
            exp_status = f"{exp_status}{status_suffix}"
        exp_acct = _route_account(exp, rules, default_action, bill_type)
        exp_source = _infer_payment_source(bill_type, exp.get("counterparty", ""), exp.get("description", ""))
        rows.append([
            exp["date"], exp["amount"], exp.get("currency", "CNY"),
            exp.get("counterparty", ""), exp.get("description", ""),
            "expense", exp_acct, exp_source,
            bill_type, exp_status,
        ])

        # 退款行
        ref_acct = _route_account(ref, rules, default_action, bill_type)
        ref_source = _infer_payment_source(bill_type, ref.get("counterparty", ""), ref.get("description", ""))
        refund_status = "退款核销"
        if status_suffix:
            refund_status = f"{refund_status}{status_suffix}"
        rows.append([
            ref["date"], ref["amount"], ref.get("currency", "CNY"),
            ref.get("counterparty", ""), ref.get("description", ""),
            "income", ref_acct, ref_source,
            bill_type, refund_status,
        ])
    return rows


def _build_output_row(rec: dict, *, bill_type: str, rules, default_action, account: str = None,
                      currency: str = None) -> dict:
    if account:
        acct_name = account
        cur = currency or "CNY"
    else:
        card_num = rec.get("card_number", "")
        if card_num:
            match = match_payment_method(rules, f"{bill_type}_{card_num}", "*")
        else:
            match = None
        if not match:
            match = match_payment_method(rules, bill_type, rec.get("payment_method", ""))
        if match:
            acct_name = match["account"]
            cur = match["currency"]
        else:
            raise ValueError(
                f"❌ 未匹配规则: source={bill_type} "
                f"payment_method='{rec.get('payment_method', '')}' "
                f"counterparty='{rec.get('counterparty', '')}' "
                f"amount={rec.get('amount', '')}\n"
                f"  请在 ~/.ft/mapping.yaml 中添加映射规则后重试"
            )

    payment_src = _infer_payment_source(
        bill_type,
        rec.get("counterparty", ""),
        rec.get("description", ""),
    )

    cpy = rec.get("counterparty", "")
    if bill_type == "icbc_credit" or bill_type == "icbc_debit":
        cpy = _strip_payment_prefix(cpy)

    return {
        "date": rec["date"],
        "amount": rec["amount"],
        "currency": rec.get("currency", cur) or cur,
        "counterparty": cpy,
        "description": rec.get("description", ""),
        "category": rec["category"],
        "account_name": acct_name,
        "source": payment_src,
        "bill_source": bill_type,
        "transfer_account": rec.get("transfer_account", ""),
        "locked": rec.get("locked", ""),
    }


def _write_output_csv(path: str | Path, rows: list[dict]):
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["date", "amount", "currency", "counterparty",
                         "description", "category", "account_name", "source",
                         "bill_source"])
        writer.writerows([
            [
                row["date"], row["amount"], row["currency"], row["counterparty"],
                row["description"], row["category"], row["account_name"], row["source"],
                row["bill_source"],
            ]
            for row in rows
        ])


def _write_refund_csv(path: str | Path, tracking_pairs, rules, default_action, bill_type: str):
    refund_rows = _build_refund_tracking_rows(tracking_pairs, rules, default_action, bill_type)
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["date", "amount", "currency", "counterparty",
                         "description", "category", "account_name", "source",
                         "bill_source", "refund_status"])
        writer.writerows(refund_rows)


def _prepare_convert_rows(path: str, source: str, password: str = None):
    if source == "icbc":
        rows, bill_type, tracking_pairs = _read_icbc_raw(path, password)
        if not rows:
            return [], "", []
    elif source == "icbc-debit":
        rows, bill_type, tracking_pairs = _read_icbc_debit_raw(path, password)
        if not rows:
            return [], "", []
    elif source == "alipay":
        rows, tracking_pairs = _read_alipay_raw(path)
        bill_type = "alipay"
    elif source == "wechat":
        rows, tracking_pairs = _read_wechat_raw(path)
        bill_type = "wechat"
    elif source == "ccb-debit":
        from .importers.ccb_debit import read_ccb_debit
        rows, _ = read_ccb_debit(path)
        bill_type = "ccb_debit"
        expenses = [r for r in rows if r["category"] == "expense"]
        refunds = []
        others = []
        for row in rows:
            if row["category"] == "income" and "退货" in row.get("description", ""):
                refunds.append({**row, "_refund_signal": "ccb_debit_desc"})
            elif row["category"] == "expense":
                continue
            else:
                others.append(row)
        rows, tracking_pairs = _pair_refunds(expenses, refunds, others)
    else:
        print(f"❌ 未知账单类型: {source}")
        return [], "", []
    return rows, bill_type, tracking_pairs


def _should_enter_pending(tracking_pairs) -> bool:
    return any(pair.get("pending_required", True) for pair in tracking_pairs)


def _build_convert_ai_rows(*, prepared_rows: list[dict], tracking_pairs, bill_type: str,
                           rules, default_action, account: str | None, currency: str | None,
                           session_id: str) -> list[dict]:
    ai_rows = []
    next_index = 1

    for row in prepared_rows:
        output_row = _build_output_row(
            row,
            bill_type=bill_type,
            rules=rules,
            default_action=default_action,
            account=account,
            currency=currency,
        )
        ai_rows.append(build_ai_working_row(output_row, record_id=f"c_{next_index:06d}", session_id=session_id))
        next_index += 1

    pending_pairs = [pair for pair in tracking_pairs if pair.get("pending_required", True)]
    for group_index, pair in enumerate(pending_pairs, 1):
        rule_hint = pair.get("rule_hint", "refund_candidate")
        ai_group = f"refund_{group_index:03d}"
        pair_records = (
            {"category": "expense", **pair["expense"]},
            {"category": "income", **pair["refund"]},
        )
        for record in pair_records:
            output_row = _build_output_row(
                record,
                bill_type=bill_type,
                rules=rules,
                default_action=default_action,
                account=account,
                currency=currency,
            )
            ai_rows.append(build_ai_working_row(
                output_row,
                record_id=f"c_{next_index:06d}",
                session_id=session_id,
                defaults={
                    "ai_group": ai_group,
                    "rule_hint": rule_hint,
                },
            ))
            next_index += 1

    return ai_rows


def _create_convert_pending_session(*, source_path: str, output: str, account: str | None, currency: str | None,
                                    bill_type: str, prepared_rows: list[dict], tracking_pairs, rules, default_action):
    manifest = {
        "source_path": source_path,
        "output_path": output,
        "account": account,
        "currency": currency,
        "bill_type": bill_type,
        "refund_output_path": output.replace(".csv", "_refunds.csv"),
    }
    session_dir = create_pending_session("convert", manifest)
    session_id = session_dir.name

    output_rows = [
        _build_output_row(rec, bill_type=bill_type, rules=rules, default_action=default_action,
                          account=account, currency=currency)
        for rec in prepared_rows
    ]
    ai_rows = _build_convert_ai_rows(
        prepared_rows=prepared_rows,
        tracking_pairs=tracking_pairs,
        bill_type=bill_type,
        rules=rules,
        default_action=default_action,
        account=account,
        currency=currency,
        session_id=session_id,
    )
    write_ai_working_csv(session_dir / "ai_working.csv", ai_rows)
    _write_output_csv(session_dir / "proposed_output.csv", output_rows)
    _write_refund_csv(session_dir / "proposed_refunds.csv", tracking_pairs, rules, default_action, bill_type)

    from .pending import format_pending_guidance
    print(format_pending_guidance("convert", session_dir))


def _validate_convert_working_rows(original_rows: list[dict], edited_rows: list[dict], session_id: str):
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

        target = parse_ai_action_target(ai_action)
        if not target:
            continue
        action_name, target_id = target
        if row.get("ai_reason", "").strip() == "":
            raise ValueError(f"❌ {action_name} 动作必须填写 ai_reason: record_id={row['record_id']}")
        if not target_id or target_id not in edited_by_id:
            raise ValueError(f"❌ 引用的 record_id 不存在: record_id={row['record_id']} ai_action={ai_action}")
        target_row = edited_by_id[target_id]
        if action_name == "merge_refund_into":
            if row.get("category") != "income":
                raise ValueError(f"❌ merge_refund_into 只能用于 income 行: record_id={row['record_id']}")
            if target_row.get("category") != "expense":
                raise ValueError(f"❌ merge_refund_into 只能并入 expense 行: record_id={row['record_id']} target={target_id}")
        elif action_name == "net_with":
            amount = float(row.get("amount") or 0)
            target_amount = float(target_row.get("amount") or 0)
            if amount == 0 or target_amount == 0 or amount * target_amount > 0:
                raise ValueError(f"❌ net_with 两边金额必须是一正一负: record_id={row['record_id']} target={target_id}")


def continue_convert(edited_csv: str):
    session_dir = require_single_pending_session("convert")
    manifest = load_manifest(session_dir)
    original_rows = read_ai_working_csv(session_dir / "ai_working.csv")
    edited_rows = read_ai_working_csv(Path(edited_csv))
    _validate_convert_working_rows(original_rows, edited_rows, manifest["session_id"])

    final_rows = apply_convert_working_rows(edited_rows)

    _write_output_csv(manifest["output_path"], final_rows)
    proposed_refunds = session_dir / "proposed_refunds.csv"
    if proposed_refunds.exists():
        Path(manifest["refund_output_path"]).write_text(proposed_refunds.read_text(encoding="utf-8"), encoding="utf-8")
    clear_pending_session("convert")
    print(f"✅ 已继续执行 convert，输出 {len(final_rows)} 条 → {manifest['output_path']}")
    if Path(manifest["refund_output_path"]).exists():
        print(f"✅ 已写退款追踪 → {manifest['refund_output_path']}")


def abort_convert():
    clear_pending_session("convert")
    print("✅ 已放弃当前 pending convert 会话")


def do_convert(path: str, source: str, output: str, password: str = None,
               account: str = None, currency: str = None):
    """convert 命令入口"""
    rules, default_action = load_rules()
    rows, bill_type, tracking_pairs = _prepare_convert_rows(path, source, password)

    if _should_enter_pending(tracking_pairs):
        _create_convert_pending_session(
            source_path=path,
            output=output,
            account=account,
            currency=currency,
            bill_type=bill_type,
            prepared_rows=rows,
            tracking_pairs=tracking_pairs,
            rules=rules,
            default_action=default_action,
        )
        return

    if not rows:
        print("❌ 无数据可输出")
        return

    output_rows = [
        _build_output_row(rec, bill_type=bill_type, rules=rules, default_action=default_action,
                          account=account, currency=currency)
        for rec in rows
    ]
    _write_output_csv(output, output_rows)
    if tracking_pairs:
        _write_refund_csv(output.replace(".csv", "_refunds.csv"), tracking_pairs, rules, default_action, bill_type)
    print(f"✅ 已转换 {len(output_rows)} 条 → {output}")
    if tracking_pairs:
        print(f"✅ 已写退款追踪 → {output.replace('.csv', '_refunds.csv')}")
