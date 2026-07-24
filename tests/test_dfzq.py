"""Tests for dfzq.py — 东方证券PDF文本解析器"""
import pytest
from decimal import Decimal
from ft.importers.dfzq import _ticker_suffix, parse_dfzq_text


# ── _ticker_suffix ──────────────────────────────────────────

class TestTickerSuffix:
    def test_shenzhen_0_start(self):
        assert _ticker_suffix("000001") == ".sz"

    def test_shenzhen_1_start(self):
        assert _ticker_suffix("159919") == ".sz"

    def test_shenzhen_2_start(self):
        assert _ticker_suffix("200625") == ".sz"

    def test_shanghai_5_start(self):
        assert _ticker_suffix("510050") == ".sh"

    def test_shanghai_6_start(self):
        assert _ticker_suffix("600519") == ".sh"

    def test_otc_851890(self):
        assert _ticker_suffix("851890") == ".otc"

    def test_otc_007011(self):
        assert _ticker_suffix("007011") == ".otc"

    def test_reverse_repo(self):
        assert _ticker_suffix("204001") == ""


# ── 辅助：构造单条交易 lines ──────────────────────────

def _trade_lines(date: str, action: str, ticker: str, name: str,
                 shares: str, price: str, total_amount: str,
                 fee: str, stamp_tax: str, transfer_fee: str,
                 balance: str) -> list[str]:
    """返回11行文本，模拟 PDF 中一笔交易的字段"""
    return [date, action, ticker, name, shares, price,
            total_amount, fee, stamp_tax, transfer_fee, balance]


# ── 解析器测试 ──────────────────────────────────────────

class TestParseDfzqText:
    """基础买入/卖出解析"""

    def test_buy_stock(self):
        """买入解析 + amount 计算验证"""
        lines = [
            "资金流水明细(2024/07/01-2026/06/13)",
            *_trade_lines("20240701", "证券买入", "000001", "平安银行",
                          "1000", "11.50", "-11505.00", "5.00",
                          "1.15", "0.50", "50000.00"),
        ]
        records = parse_dfzq_text(lines)
        assert len(records) == 2  # 1 trade + 1 CHECKIN
        r = records[0]
        assert r["date"] == "2024-07-01 00:00:00"
        assert r["action"] == "BUY"
        assert r["ticker"] == "000001.sz"
        assert r["name"] == "平安银行"
        assert r["shares"] == 1000.0
        assert r["price"] == 11.50
        assert r["amount"] == Decimal("-11505.00")
        assert r["fee"] == 5.0
        assert r["stamp_tax"] == Decimal("1.15")
        assert r["transfer_fee"] == Decimal("0.50")
        assert r["balance"] == 50000.0
        assert "印花税" in r["note"]
        assert "过户费" in r["note"]

    def test_sell_stock(self):
        """卖出解析 + amount 计算验证"""
        lines = [
            "资金流水明细(2024/07/01-2026/06/13)",
            *_trade_lines("20240702", "证券卖出", "600519", "贵州茅台",
                          "100", "1500.00", "149970.00", "30.00",
                          "150.00", "5.00", "200000.00"),
        ]
        records = parse_dfzq_text(lines)
        assert len(records) == 2
        r = records[0]
        assert r["date"] == "2024-07-02 00:00:00"
        assert r["action"] == "SELL"
        assert r["ticker"] == "600519.sh"
        assert r["name"] == "贵州茅台"
        assert r["shares"] == 100.0
        assert r["price"] == 1500.0
        assert r["amount"] == Decimal("149970.00")

    def test_deposit(self):
        """银行转证券 → DEPOSIT"""
        lines = [
            "资金流水明细(2024/07/01-2026/06/13)",
            *_trade_lines("20240703", "银行转证券", "", "",
                          "0", "0", "50000.00", "0", "0", "0", "100000.00"),
        ]
        records = parse_dfzq_text(lines)
        assert len(records) == 2
        r = records[0]
        assert r["action"] == "DEPOSIT"
        assert r["shares"] == 0
        assert r["price"] == 0
        assert r["ticker"] == ""

    def test_withdraw(self):
        """OTC资金划出 → WITHDRAW"""
        lines = [
            "资金流水明细(2024/07/01-2026/06/13)",
            *_trade_lines("20240704", "OTC资金划出", "", "",
                          "0", "0", "-30000.00", "0", "0", "0", "70000.00"),
        ]
        records = parse_dfzq_text(lines)
        assert len(records) == 2
        r = records[0]
        assert r["action"] == "WITHDRAW"
        assert r["shares"] == 0
        assert r["price"] == 0

    def test_reverse_repo_buy(self):
        """融券回购 → BUY (ticker=204001)"""
        lines = [
            "资金流水明细(2024/07/01-2026/06/13)",
            *_trade_lines("20240705", "融券回购", "204001", "GC001",
                          "1000", "100.00", "-100000.00", "1.00",
                          "0", "0", "50000.00"),
        ]
        records = parse_dfzq_text(lines)
        assert len(records) == 2
        r = records[0]
        assert r["action"] == "BUY"
        assert r["ticker"] == "204001"
        assert r["name"] == "GC001"
        assert r["date"] == "2024-07-05 00:00:00"

    def test_reverse_repo_sell(self):
        """融券购回 → SELL (ticker=204001)"""
        lines = [
            "资金流水明细(2024/07/01-2026/06/13)",
            *_trade_lines("20240706", "融券购回", "204001", "GC001",
                          "1000", "100.10", "100100.00", "1.00",
                          "0", "0", "150000.00"),
        ]
        records = parse_dfzq_text(lines)
        assert len(records) == 2
        r = records[0]
        assert r["action"] == "SELL"
        assert r["ticker"] == "204001"
        assert r["date"] == "2024-07-06 00:00:00"

    def test_reverse_order_to_ascending(self):
        """倒序输入（最新在前）→ 升序输出"""
        lines = [
            "资金流水明细(2024/07/01-2026/06/13)",
            *_trade_lines("20240703", "证券卖出", "000002", "万科A",
                          "500", "15.00", "7496.25", "3.75",
                          "7.50", "0.25", "57493.35"),
            *_trade_lines("20240701", "证券买入", "000001", "平安银行",
                          "1000", "11.50", "-11505.00", "5.00",
                          "1.15", "0.50", "50000.00"),
        ]
        records = parse_dfzq_text(lines)
        assert len(records) == 3  # 2 trades + 1 CHECKIN
        dates = [r["date"] for r in records]
        assert dates == sorted(dates)
        assert dates[0] == "2024-07-01 00:00:00"
        assert dates[1] == "2024-07-03 00:00:00"

    def test_checkin_takes_last_balance(self):
        """CHECKIN 取最后一笔交易的资金余额"""
        lines = [
            "资金流水明细(2024/07/01-2026/06/13)",
            *_trade_lines("20240703", "证券卖出", "000002", "万科A",
                          "500", "15.00", "7496.25", "3.75",
                          "7.50", "0.25", "57493.35"),
            *_trade_lines("20240701", "证券买入", "000001", "平安银行",
                          "1000", "11.50", "-11505.00", "5.00",
                          "1.15", "0.50", "50000.00"),
        ]
        records = parse_dfzq_text(lines)
        checkin = records[-1]
        assert checkin["action"] == "CHECKIN"
        assert checkin["amount"] == Decimal("57493.35")
        assert checkin["date"] == "2024-07-03 00:00:00"
        assert checkin["ticker"] == ""
        assert checkin["name"] == ""

    def test_stamp_tax_and_transfer_fee_in_note(self):
        """印花税/过户费写入 note 字段"""
        lines = [
            "资金流水明细(2024/07/01-2026/06/13)",
            *_trade_lines("20240701", "证券买入", "000001", "平安银行",
                          "1000", "11.50", "-11505.00", "5.00",
                          "1.15", "0.50", "50000.00"),
        ]
        records = parse_dfzq_text(lines)
        r = records[0]
        assert "印花税1.15" in r["note"]
        assert "过户费0.50" in r["note"]

    def test_empty_input(self):
        """空输入不崩溃"""
        assert parse_dfzq_text([]) == []
        assert parse_dfzq_text(["无关内容"]) == []

    def test_no_header(self):
        """没有资金流水明细表头 → 空列表"""
        lines = ["第1页，共1页", "\f"]
        assert parse_dfzq_text(lines) == []

    def test_skip_page_headers(self):
        """跳过页眉和页码标记"""
        lines = [
            "资金流水明细(2024/07/01-2026/06/13)",
            *_trade_lines("20240701", "证券买入", "000001", "平安银行",
                          "1000", "11.50", "-11505.00", "5.00",
                          "1.15", "0.50", "50000.00"),
            "第1页，共2页",
            "\f",
            "资金流水明细(2024/07/01-2026/06/13)",  # 页眉重复
            *_trade_lines("20240703", "证券卖出", "000002", "万科A",
                          "500", "15.00", "7496.25", "3.75",
                          "7.50", "0.25", "57493.35"),
            "第2页，共2页",
            "\f",
        ]
        records = parse_dfzq_text(lines)
        assert len(records) == 3  # 2 trades + 1 CHECKIN

    def test_otc_ticker_suffix(self):
        """OTC 代码加 .otc 后缀"""
        lines = [
            "资金流水明细(2024/07/01-2026/06/13)",
            *_trade_lines("20240701", "证券买入", "851890", "某OTC产品",
                          "1000", "1.00", "-1001.00", "1.00",
                          "0", "0", "50000.00"),
        ]
        records = parse_dfzq_text(lines)
        r = records[0]
        assert r["ticker"] == "851890.otc"

    def test_all_actions_in_one_batch(self):
        """一次解析多种交易类型"""
        lines = [
            "资金流水明细(2024/07/01-2026/06/13)",
            *_trade_lines("20240705", "融券回购", "204001", "GC001",
                          "1000", "100.00", "-100000.00", "1.00",
                          "0", "0", "50000.00"),
            *_trade_lines("20240704", "OTC资金划出", "", "",
                          "0", "0", "-30000.00", "0", "0", "0", "50000.00"),
            *_trade_lines("20240703", "银行转证券", "", "",
                          "0", "0", "50000.00", "0", "0", "0", "80000.00"),
            *_trade_lines("20240702", "证券卖出", "600519", "贵州茅台",
                          "100", "1500.00", "149970.00", "30.00",
                          "150.00", "5.00", "30000.00"),
            *_trade_lines("20240701", "证券买入", "000001", "平安银行",
                          "1000", "11.50", "-11505.00", "5.00",
                          "1.15", "0.50", "100000.00"),
        ]
        records = parse_dfzq_text(lines)
        assert len(records) == 6  # 5 trades + 1 CHECKIN
        dates = [r["date"] for r in records]
        assert dates == sorted(dates)
        actions = [r["action"] for r in records]
        assert actions[:5] == ["BUY", "SELL", "DEPOSIT", "WITHDRAW", "BUY"]
        assert records[-1]["action"] == "CHECKIN"
        assert records[-1]["amount"] == 50000.0

    def test_amount_formula_buy(self):
        """买入：amount = 总发生金额（净额）；手续费仅审计字段，map 阶段 peel。"""
        lines = [
            "资金流水明细(2024/07/01-2026/06/13)",
            *_trade_lines("20240701", "证券买入", "000001", "平安银行",
                          "1000", "11.50", "-11505.00", "5.00",
                          "1.15", "0.50", "100000.00"),
        ]
        records = parse_dfzq_text(lines)
        r = records[0]
        assert r["amount"] == Decimal("-11505.00")
        assert r["amount"] == r["total_amount"]
        assert r["fee"] == Decimal("5.00")
        # net is not pure shares*price when statement net embeds fees
        assert abs(r["amount"]) != r["shares"] * r["price"]

    def test_amount_formula_sell(self):
        """卖出：amount = 总发生金额（净额）；不与 shares*price 强等。"""
        lines = [
            "资金流水明细(2024/07/01-2026/06/13)",
            *_trade_lines("20240702", "证券卖出", "600519", "贵州茅台",
                          "100", "1500.00", "149970.00", "30.00",
                          "150.00", "5.00", "200000.00"),
        ]
        records = parse_dfzq_text(lines)
        r = records[0]
        assert r["amount"] == Decimal("149970.00")
        assert r["amount"] == r["total_amount"]
        assert r["fee"] == Decimal("30.00")
