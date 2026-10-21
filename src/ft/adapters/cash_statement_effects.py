"""来源行补丁的无 IO 派生；只更新原文备注及当前单行解释。"""
import re

from ft.adapters.cash_statement_evidence import extract_source_explanation
from ft.domain.cash_record_policy import source_note


_IDENTITY_KEYS = frozenset({"_source_account_identifier", "file_account_key", "card_number", "source_display_name"})


def _cells(source_type, payload):
    if not isinstance(payload, dict):
        return {}
    if source_type not in {"icbc_credit", "icbc_debit"}:
        return payload
    result, ambiguous = {}, set()
    for key, value in payload.items():
        normalized = re.sub(r"\s+", "", key)
        if normalized in result:
            ambiguous.add(normalized)
        result[normalized] = value
    return {key: value for key, value in result.items() if key not in ambiguous}


def _text(cells, key, *, pdf=False):
    value = cells.get(key)
    if type(value) is not str:
        return ""
    return (value.replace("\n", "") if pdf else value).strip()


def cash_source_note(source_type, payload):
    return source_note(_cells(source_type, payload))


def _platform(source_type, cells, amount):
    from ft import convert
    if source_type == "wechat":
        status, direction = _text(cells, "当前状态"), _text(cells, "收/支")
        method, kind = _text(cells, "支付方式"), _text(cells, "交易类型")
        if direction not in {"支出", "收入"} and kind == "零钱提现":
            method = "零钱"
        return {"txn_type": kind, "txn_id": _text(cells, "交易单号"), "merchant_order_id": _text(cells, "商户单号"),
            "status": status, "platform_status": status, "_wechat_direction": direction,
            "payment_method": method, "_refund_signal": "wechat_status" if "退款" in status else ""}
    kind, status, direction = (_text(cells, key) for key in ("交易分类", "交易状态", "收/支"))
    counterparty = _text(cells, "交易对方")
    description = _text(cells, "商品说明") or counterparty
    _, description = convert._normalize_counterparty(counterparty, description[:80], "alipay")
    method = _text(cells, "收/付款方式")
    if amount is not None:
        # 复用既有理财渠道规则；只取资金渠道，不采用其金额或收支结果。
        method = convert._alipay_route_wealth_product({"amount": amount, "note": description,
            "payment_method": method})["payment_method"]
    return {"txn_type": kind, "txn_id": _text(cells, "交易订单号"), "merchant_order_id": _text(cells, "商家订单号"),
        "platform_status": status, "_alipay_direction": direction, "payment_method": method,
        "_refund_signal": convert._alipay_refund_signal(txn_type=kind, txn_status=status,
            direction=direction, description=description)}


def _bank(source_type, cells, amount, identity):
    if source_type == "icbc_asia":
        card = identity.get("card_number", "")
        return {"summary": _text(cells, "摘要"), "txn_type": _text(cells, "業務類型"),
            "location": _text(cells, "交易場所"), "_raw_cp": _text(cells, "對方戶名"),
            "payment_method": f"工银亚洲活期账户({card})" if card else "工银亚洲活期账户"}
    from ft.importers.ccb_debit import (_extract_ccb_counterparty, _extract_ccb_acct_name_counterparty,
        _infer_ccb_payment_source, _infer_ccb_refund_signal)
    summary, location, account = (_text(cells, key) for key in ("摘要", "交易地点/附言", "对方账号与户名"))
    location_cp, account_cp = _extract_ccb_counterparty(location), _extract_ccb_acct_name_counterparty(account)
    refund = _infer_ccb_refund_signal(summary, amount) if amount is not None else ""
    return {"summary": summary, "location": location, "acct_name_raw": account,
        "_raw_cp": account_cp or location_cp or "", "_ccb_location_cp": location_cp or "", "_ccb_acct_cp": account_cp,
        "payment_method": _infer_ccb_payment_source(location, identity.get("card_number", "")),
        "_ccb_refund_signal": refund, "_refund_signal": refund}


def _icbc(source_type, cells, amount):
    from ft import convert
    summary, counterparty = (_text(cells, key, pdf=True) for key in ("摘要", "对方户名"))
    if source_type == "icbc_debit":
        summary = convert._match_debit_summary(summary)
        if counterparty and summary and any(convert._is_subseq(word, counterparty)
            for word in ("基金快速赎回", "基金赎回", "基金购买")):
            counterparty = "中国工商银行股份有限公司基金清算专户"
        offset = "reversal" if summary == "撤销交易" else "refund" if summary in {"退款", "退货"} else ""
        return {"summary": summary, "_raw_cp": counterparty, "payment_method": _text(cells, "渠道", pdf=True),
            "_debit_offset_type": offset, "_is_refund": offset == "refund", "_is_reversal": offset == "reversal",
            "refund_signal": "icbc_debit_return" if summary == "退货" else "",
            "_refund_signal": "icbc_debit_refund" if offset == "refund" and amount is not None and amount > 0 else ""}
    description = _text(cells, "交易场所", pdf=True)
    offset = convert._classify_icbc_credit_offset_type(counterparty, description, summary)
    normalization = description
    if not normalization and convert._infer_payment_source("icbc", counterparty, "") != "银行卡":
        normalization = counterparty
    normalized_cp, enriched = convert._normalize_counterparty(counterparty, normalization[:80], "icbc")
    evidence = {"summary": summary, "_raw_cp": counterparty, "offset_type": offset,
        "payment_method": convert._infer_payment_source("icbc", normalized_cp, enriched[:80])}
    if offset == "merchant_refund":
        merchant = description if counterparty == "退货" and description else counterparty
        evidence.update(_is_refund=True, _refund_signal="icbc_credit_return",
            refund_signal="icbc_credit_return" if summary == "退货" else "",
            _icbc_refund_merchant_trusted=not convert._is_icbc_credit_untrusted_merchant_text(merchant))
    return evidence


def derive_cash_source_explanation(source_type, payload, *, amount, source_explanation):
    """按最终正文替换可变解释；本方身份只能来自可信的既有来源解释。"""
    identity = {key: value for key, value in extract_source_explanation(source_explanation).items() if key in _IDENTITY_KEYS}
    cells = _cells(source_type, payload)
    if source_type in {"wechat", "alipay"}:
        evidence = _platform(source_type, cells, amount)
    elif source_type in {"ccb_debit", "icbc_asia"}:
        evidence = _bank(source_type, cells, amount, identity)
    elif source_type in {"icbc_credit", "icbc_debit"}:
        evidence = _icbc(source_type, cells, amount)
    else:
        evidence = {}
    return extract_source_explanation({**evidence, **identity})


def prepare_cash_source_effects(values, patch, *, source_type, source_explanation, protected_fields=frozenset()):
    """字段已按统一策略校验；返回最终值及需要替换的解释，None 表示不重算。"""
    result = dict(values)
    if set(patch) & {"source_payload", "amount"}:
        if "source_payload" in patch and "note" not in patch and "note" not in protected_fields:
            result["note"] = cash_source_note(source_type, result.get("source_payload"))
        explanation = derive_cash_source_explanation(source_type, result.get("source_payload"),
            amount=result.get("amount"), source_explanation=source_explanation)
    elif set(patch) & {"record_type", "record_subtype"}:
        # 单行来源事实与类型独立；动态角色在规划时结合最终类型生成。
        explanation = extract_source_explanation(source_explanation)
    else:
        explanation = None
    return result, explanation
