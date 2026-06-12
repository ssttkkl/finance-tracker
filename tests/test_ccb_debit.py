"""建行储蓄卡 XLS 转换器测试"""
import pytest
import xlwt
import os
import tempfile
from ft.importers.ccb_debit import read_ccb_debit, _extract_ccb_counterparty
from ft.convert import _pair_refunds


def _make_xls(card: str, rows: list[tuple]) -> str:
    """创建测试用 XLS 文件，返回路径。
    rows: [(摘要, 币别, 钞汇, 交易日期, 交易金额, 账户余额, 交易地点, 对方账号与户名), ...]
    交易地点列：新版有真实值（如 '财付通-微信支付-瑞幸咖啡'），旧版为 '***'
    """
    wb = xlwt.Workbook()
    ws = wb.add_sheet("Sheet0")
    # 标题行
    ws.write(0, 4, "中国建设银行个人活期账户全部交易明细")
    # 卡号行
    ws.write(1, 1, f"卡号/账号:{card}")
    ws.write(1, 3, "客户名称:测试用户")
    ws.write(1, 5, "起始日期:20260101")
    ws.write(1, 7, "结束日期:20260609")
    # 合计行
    ws.write(2, 1, "当前时间段收支金额合计：")
    # 表头行
    headers = ["序号", "摘要", "币别", "钞汇", "交易日期", "交易金额", "账户余额", "交易地点/附言", "对方账号与户名"]
    for ci, h in enumerate(headers):
        ws.write(3, ci, h)
    # 数据行
    for ri, row in enumerate(rows):
        ws.write(4 + ri, 0, ri + 1)  # 序号列
        for ci, val in enumerate(row):
            ws.write(4 + ri, ci + 1, val)
    tmp = tempfile.NamedTemporaryFile(suffix=".xls", delete=False)
    tmp.close()
    wb.save(tmp.name)
    return tmp.name


# ── Location-based counterparty extraction (NEW) ──

class TestLocationCounterparty:
    def test_wechat_pay(self):
        """财付通-微信支付-瑞幸咖啡 → 瑞幸咖啡"""
        path = _make_xls("6217000000000002820", [
            ("消费", "人民币元", "钞", "20260128", "-4.23", "1,845.76",
             "财付通-微信支付-瑞幸咖啡", "Z******0010/***咖啡"),
        ])
        recs, tracking = read_ccb_debit(path)
        os.unlink(path)
        assert len(recs) == 1
        assert tracking == []
        assert recs[0]["counterparty"] == "瑞幸咖啡"
        assert recs[0]["payment_method"] == "微信支付"

    def test_alipay(self):
        """支付宝-淘宝-于震 → 于震"""
        path = _make_xls("6217000000000002820", [
            ("消费", "人民币元", "钞", "20260321", "-2598.95", "5,974.20",
             "支付宝-淘宝-于震", "Z******0010/*震"),
        ])
        recs, tracking = read_ccb_debit(path)
        os.unlink(path)
        assert recs[0]["counterparty"] == "于震"
        assert recs[0]["payment_method"] == "支付宝"

    def test_alipay_external(self):
        """支付宝-支付宝外部商户-上海部恩科技有限公司 → 上海部恩科技有限公司"""
        path = _make_xls("6217000000000002820", [
            ("消费", "人民币元", "钞", "20260430", "-89.50", "5,430.29",
             "支付宝-支付宝外部商户-上海部恩科技有限公司", "Z******0010/***公司"),
        ])
        recs, tracking = read_ccb_debit(path)
        os.unlink(path)
        assert recs[0]["counterparty"] == "上海部恩科技有限公司"
        assert recs[0]["payment_method"] == "支付宝"

    def test_meituan(self):
        """美团支付-美团特约商户 → 美团特约商户"""
        path = _make_xls("6217000000000002820", [
            ("消费退货", "人民币元", "钞", "20260528", "22.23", "306.54",
             "美团支付-美团特约商户", "11000175712473/北京三快在线科技有限公司"),
        ])
        recs, tracking = read_ccb_debit(path)
        os.unlink(path)
        assert recs[0]["counterparty"] == "美团特约商户"
        assert recs[0]["payment_method"] == "美团支付"

    def test_paypal(self):
        """PAYPAL_PIXIVFANBOX → PAYPAL_PIXIVFANBOX"""
        path = _make_xls("6217000000000002820", [
            ("无卡自助交易", "人民币元", "钞", "20260101", "-13.99", "1,879.94",
             "PAYPAL_PIXIVFANBOX", "685070248160001/PAYPAL_PIXIVFANBOX"),
        ])
        recs, tracking = read_ccb_debit(path)
        os.unlink(path)
        assert recs[0]["counterparty"] == "PAYPAL_PIXIVFANBOX"
        assert recs[0]["payment_method"] == "PayPal"

    def test_direct(self):
        """北京市政交通一卡通有限公司 → same"""
        path = _make_xls("6217000000000002820", [
            ("有卡自助消费", "人民币元", "钞", "20260102", "-29.95", "1,849.99",
             "北京市政交通一卡通有限公司", "898111941110139/北京市政交通一卡通有限公司"),
        ])
        recs, tracking = read_ccb_debit(path)
        os.unlink(path)
        assert recs[0]["counterparty"] == "北京市政交通一卡通有限公司"
        # 直接商户名 → 建行储蓄卡
        assert "建行储蓄卡" in recs[0]["payment_method"]


# ── Legacy fallback (*** → 对方户名) ──

class TestLegacyFallback:
    def test_location_stars_fallback(self):
        """旧版 *** → 回退对方户名 '/' 分割"""
        path = _make_xls("6217000000000002820", [
            ("消费", "人民币元", "钞", "20260128", "-9.30", "1,836.46",
             "***", "Z******0010/***ee"),
        ])
        recs, tracking = read_ccb_debit(path)
        os.unlink(path)
        assert recs[0]["counterparty"] == "***ee"
        # *** → 建行储蓄卡
        assert "建行储蓄卡" in recs[0]["payment_method"]

    def test_location_empty_fallback(self):
        """空交易地点 → 回退对方户名"""
        path = _make_xls("6217000000000002820", [
            ("利息存入", "人民币元", "钞", "20260321", "0.51", "8,573.15",
             "", "31001502500050030259/东方财富证券股份有限公司（客户）"),
        ])
        recs, tracking = read_ccb_debit(path)
        os.unlink(path)
        assert recs[0]["counterparty"] == "东方财富证券股份有限公司（客户）"


# ── Basic parsing (date/amount/currency/category/card_number) ──

class TestBasicParsing:
    def test_expense(self):
        path = _make_xls("6217000000000002820", [
            ("消费", "人民币元", "钞", "20260128", "-4.23", "1,845.76",
             "财付通-微信支付-瑞幸咖啡", "Z******0010/***咖啡"),
        ])
        recs, _ = read_ccb_debit(path)
        os.unlink(path)
        assert len(recs) == 1
        r = recs[0]
        assert r["date"] == "2026-01-28 00:00:00"
        assert r["amount"] == -4.23
        assert r["currency"] == "CNY"
        assert r["category"] == "expense"
        assert r["counterparty"] == "瑞幸咖啡"
        assert r["description"] == "消费"
        assert r["card_number"] == "2820"

    def test_income(self):
        path = _make_xls("6217000000000002820", [
            ("银联入账", "人民币元", "钞", "20260217", "100.00", "856.76",
             "***", "6212250000000000000/测试用户"),
        ])
        recs, _ = read_ccb_debit(path)
        os.unlink(path)
        assert len(recs) == 1
        r = recs[0]
        assert r["amount"] == 100.00
        assert r["category"] == "income"
        assert r["description"] == "银联入账"

    def test_transfer_out(self):
        path = _make_xls("6236680000000000523", [
            ("转账支取", "人民币元", "钞", "20260212", "-27,572.03", "0.00",
             "***", "8******0005/*文龙"),
        ])
        recs, _ = read_ccb_debit(path)
        os.unlink(path)
        assert len(recs) == 1
        assert recs[0]["amount"] == -27572.03
        assert recs[0]["description"] == "转账支取"
        assert recs[0]["card_number"] == "0523"

    def test_securities_transfer_in(self):
        path = _make_xls("6236680000000000523", [
            ("证转银", "人民币元", "钞", "20260212", "26,070.51", "27,572.03",
             "***", "31001502500050030259/东方财富证券股份有限公司（客户）"),
        ])
        recs, _ = read_ccb_debit(path)
        os.unlink(path)
        assert recs[0]["amount"] == 26070.51
        assert recs[0]["description"] == "证转银"
        assert recs[0]["category"] == "income"

    def test_securities_transfer_out(self):
        path = _make_xls("6236680000000000523", [
            ("银转证", "人民币元", "钞", "20260305", "-10,000.00", "0.00",
             "***", "31001502500050030259/东方财富证券股份有限公司（客户）"),
        ])
        recs, _ = read_ccb_debit(path)
        os.unlink(path)
        assert recs[0]["amount"] == -10000.00
        assert recs[0]["description"] == "银转证"
        assert recs[0]["category"] == "expense"

    def test_interest(self):
        path = _make_xls("6217000000000002820", [
            ("利息存入", "人民币元", "钞", "20260321", "0.51", "8,573.15",
             "", ""),
        ])
        recs, _ = read_ccb_debit(path)
        os.unlink(path)
        assert recs[0]["amount"] == 0.51
        assert recs[0]["description"] == "利息存入"

    def test_topup(self):
        path = _make_xls("6217000000000002820", [
            ("充值", "人民币元", "钞", "20260219", "-4.90", "9,665.76",
             "财付通-微信支付-苹果电脑贸易（上海）有限公司", "Z******0010/*收款"),
        ])
        recs, _ = read_ccb_debit(path)
        os.unlink(path)
        assert recs[0]["amount"] == -4.90
        assert recs[0]["description"] == "充值"
        assert recs[0]["category"] == "expense"
        assert recs[0]["counterparty"] == "苹果电脑贸易（上海）有限公司"

    def test_cardless_transaction(self):
        path = _make_xls("6217000000000002820", [
            ("无卡自助交易", "人民币元", "钞", "20260101", "-13.99", "1,879.94",
             "PAYPAL_PIXIVFANBOX", "685070248160001/PAYPAL_PIXIVFANBOX"),
        ])
        recs, _ = read_ccb_debit(path)
        os.unlink(path)
        assert recs[0]["counterparty"] == "PAYPAL_PIXIVFANBOX"
        assert recs[0]["description"] == "无卡自助交易"
        assert recs[0]["payment_method"] == "PayPal"

    def test_card_present_transaction(self):
        path = _make_xls("6217000000000002820", [
            ("有卡自助消费", "人民币元", "钞", "20260102", "-29.95", "1,849.99",
             "北京市政交通一卡通有限公司", "898111941110139/北京市政交通一卡通有限公司"),
        ])
        recs, _ = read_ccb_debit(path)
        os.unlink(path)
        assert recs[0]["counterparty"] == "北京市政交通一卡通有限公司"

    def test_multi_currency(self):
        path = _make_xls("6217000000000002820", [
            ("消费", "美元", "钞", "20260301", "-100.00", "500.00",
             "PAYPAL/SOME_STORE", "PAYPAL/SOME_STORE"),
        ])
        recs, _ = read_ccb_debit(path)
        os.unlink(path)
        assert recs[0]["currency"] == "USD"


# ── Refund pairing via _pair_refunds (NEW) ──

class TestRefundPairingWithPairRefunds:
    def test_full_refund_with_location(self):
        """消费和退款用同样的 counterparty 提取，_pair_refunds 精确匹配"""
        path = _make_xls("6217000000000002820", [
            ("消费", "人民币元", "钞", "20260314", "-200.00", "8,539.89",
             "财付通-微信支付-鸟楽町居酒屋Bistro", "Z******0010/***ro"),
            ("消费退货", "人民币元", "钞", "20260314", "200.00", "8,739.89",
             "财付通-鸟楽町居酒屋Bistro", "833678836/鸟楽町居酒屋Bistro"),
        ])
        recs, tracking = read_ccb_debit(path)
        os.unlink(path)
        # read_ccb_debit 不配对，返回 2 条记录
        assert len(recs) == 2
        assert tracking == []

        # 用 _pair_refunds 做退款配对
        expenses = [r for r in recs if r["category"] == "expense"]
        refunds = [r for r in recs if r["category"] == "income" and "退货" in r.get("description", "")]
        others = [r for r in recs if not (r["category"] == "expense" or (r["category"] == "income" and "退货" in r.get("description", "")))]
        result, tp = _pair_refunds(expenses, refunds, others)
        assert len(result) == 0  # 全额配对 → 两条都删除
        assert len(tp) == 1
        assert tp[0]["match_type"] == "full"

    def test_orphan_refund_with_location(self):
        """22.23 美团特约商户 — counterparty 无匹配，孤退款"""
        path = _make_xls("6217000000000002820", [
            ("消费退货", "人民币元", "钞", "20260528", "22.23", "306.54",
             "美团支付-美团特约商户", "11000175712473/北京三快在线科技有限公司"),
        ])
        recs, tracking = read_ccb_debit(path)
        os.unlink(path)
        assert len(recs) == 1
        assert tracking == []

        # 用 _pair_refunds — 无消费可配 → 孤退款保留为 income
        expenses = [r for r in recs if r["category"] == "expense"]
        refunds = [r for r in recs if r["category"] == "income" and "退货" in r.get("description", "")]
        others = [r for r in recs if not (r["category"] == "expense" or (r["category"] == "income" and "退货" in r.get("description", "")))]
        result, tp = _pair_refunds(expenses, refunds, others)
        assert len(result) == 1
        assert result[0]["category"] == "income"
        assert result[0]["amount"] == 22.23
        assert len(tp) == 0  # 孤退款不进 tracking

    def test_full_refund_same_day_legacy(self):
        """旧版 *** → 回退对方户名，_pair_refunds 用子串匹配"""
        path = _make_xls("6217000000000002820", [
            ("消费", "人民币元", "钞", "20260314", "-200.00", "8,539.89",
             "***", "Z******0010/***ro"),
            ("消费退货", "人民币元", "钞", "20260314", "200.00", "8,739.89",
             "***", "833678836/鸟楽町居酒屋Bistro"),
        ])
        recs, tracking = read_ccb_debit(path)
        os.unlink(path)
        assert len(recs) == 2

        expenses = [r for r in recs if r["category"] == "expense"]
        refunds = [r for r in recs if r["category"] == "income" and "退货" in r.get("description", "")]
        others = [r for r in recs if not (r["category"] == "expense" or (r["category"] == "income" and "退货" in r.get("description", "")))]
        result, tp = _pair_refunds(expenses, refunds, others)
        assert len(result) == 0  # 全额配对
        assert len(tp) == 1
        assert tp[0]["match_type"] == "full"

    def test_full_refund_next_day_legacy(self):
        """旧版 充值+退款 次日，counterparty 不匹配（**转账 vs 微信转账），不配对"""
        path = _make_xls("6217000000000002820", [
            ("充值", "人民币元", "钞", "20260307", "-60.00", "8,829.32",
             "***", "Z******0010/**转账"),
            ("消费退货", "人民币元", "钞", "20260308", "60.00", "8,889.32",
             "***", "1000050201/微信转账"),
        ])
        recs, tracking = read_ccb_debit(path)
        os.unlink(path)
        assert len(recs) == 2

        expenses = [r for r in recs if r["category"] == "expense"]
        refunds = [r for r in recs if r["category"] == "income" and "退货" in r.get("description", "")]
        others = [r for r in recs if not (r["category"] == "expense" or (r["category"] == "income" and "退货" in r.get("description", "")))]
        result, tp = _pair_refunds(expenses, refunds, others)
        # _pair_refunds 需要 counterparty 或 description 匹配 —
        # "**转账"≠"微信转账"，"充值"≠"消费退货" → 不配对，两条都保留
        assert len(result) == 2
        assert tp == []

    def test_orphan_refund_legacy(self):
        """旧版孤退款"""
        path = _make_xls("6217000000000002820", [
            ("消费退货", "人民币元", "钞", "20260528", "22.23", "306.54",
             "***", "11000175712473/北京三快在线科技有限公司"),
        ])
        recs, tracking = read_ccb_debit(path)
        os.unlink(path)
        assert len(recs) == 1

        expenses = [r for r in recs if r["category"] == "expense"]
        refunds = [r for r in recs if r["category"] == "income" and "退货" in r.get("description", "")]
        others = [r for r in recs if not (r["category"] == "expense" or (r["category"] == "income" and "退货" in r.get("description", "")))]
        result, tp = _pair_refunds(expenses, refunds, others)
        assert len(result) == 1
        assert result[0]["category"] == "income"
        assert result[0]["amount"] == 22.23
        assert result[0]["description"] == "消费退货"

    def test_partial_refund_kept_as_orphan(self):
        """部分退款 — _pair_refunds 不配对（金额不精确），保留"""
        path = _make_xls("6217000000000002820", [
            ("消费", "人民币元", "钞", "20260314", "-500.00", "8,000.00",
             "财付通-微信支付-某商店", "Z******0010/***店"),
            ("消费退货", "人民币元", "钞", "20260315", "200.00", "8,200.00",
             "财付通-某商店", "2088002502045789/某商店"),
        ])
        recs, tracking = read_ccb_debit(path)
        os.unlink(path)
        assert len(recs) == 2

        expenses = [r for r in recs if r["category"] == "expense"]
        refunds = [r for r in recs if r["category"] == "income" and "退货" in r.get("description", "")]
        others = [r for r in recs if not (r["category"] == "expense" or (r["category"] == "income" and "退货" in r.get("description", "")))]
        result, tp = _pair_refunds(expenses, refunds, others)
        # _pair_refunds 做 partial match: 500 - 200 = 300 保留
        assert len(result) == 1
        assert result[0]["amount"] == -300.00  # 消费 500 减退款 200
        assert len(tp) == 1
        assert tp[0]["match_type"] == "partial"
