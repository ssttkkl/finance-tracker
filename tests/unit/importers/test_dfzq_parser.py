"""Unit tests for DFZQ parser edge cases.

Constitution III: Test-first - these tests must be written and verified before
implementing any parser modifications.
"""
import pytest
from decimal import Decimal

from ft.importers.dfzq import parse_dfzq_text, check_external_tools


def test_check_external_tools_detects_qpdf_and_mutool():
    """External tool check should detect qpdf and mutool versions."""
    tools = check_external_tools()

    assert "qpdf" in tools
    assert "mutool" in tools
    # If installed, should have version strings
    if tools["qpdf"] is not None:
        assert tools["qpdf"] != ""
    if tools["mutool"] is not None:
        assert tools["mutool"] != ""


def test_parse_dfzq_sample_statement():
    """Parse sample DFZQ statement with all transaction types."""
    with open("tests/fixtures/dfzq/sample_statement.txt", "r", encoding="utf-8") as f:
        lines = f.readlines()

    transactions = parse_dfzq_text(lines)

    # Should have: 1 deposit + 1 buy + 1 sell + 2 dividends + 1 CHECKIN = 6 total
    assert len(transactions) == 6

    # T1: Deposit
    assert transactions[0]["action"] == "DEPOSIT"
    assert Decimal(transactions[0]["amount"]) == Decimal("10000.00")
    assert transactions[0]["ticker"] == ""

    # T2: Buy — amount is 总发生金额 (net); fee is separate column
    assert transactions[1]["action"] == "BUY"
    assert transactions[1]["ticker"] == "600000.sh"
    assert Decimal(transactions[1]["shares"]) == Decimal("100")
    assert Decimal(transactions[1]["price"]) == Decimal("12.50")
    assert Decimal(transactions[1]["amount"]) == Decimal("1250.00")  # net 总发生金额
    assert Decimal(transactions[1]["fee"]) == Decimal("1.00")

    # T3: Sell
    assert transactions[2]["action"] == "SELL"
    assert transactions[2]["ticker"] == "600000.sh"
    assert Decimal(transactions[2]["shares"]) == Decimal("50")
    assert Decimal(transactions[2]["price"]) == Decimal("13.00")

    # T4: Cash dividend
    assert transactions[3]["action"] == "DIVIDEND"
    assert transactions[3]["ticker"] == ""  # Cash dividend has no ticker
    assert Decimal(transactions[3]["amount"]) == Decimal("50.00")

    # T5: Stock dividend
    assert transactions[4]["action"] == "DIVIDEND"
    assert transactions[4]["ticker"] == "600000.sh"
    assert Decimal(transactions[4]["shares"]) == Decimal("10")

    # T6: CHECKIN (auto-generated from last balance)
    assert transactions[5]["action"] == "CHECKIN"


def test_parse_dfzq_empty_statement():
    """Empty or invalid statement should return empty list."""
    lines = ["Some random text", "No transaction data"]
    transactions = parse_dfzq_text(lines)
    assert transactions == []


def test_parse_dfzq_handles_multiple_pages():
    """Parser should skip page markers and continue parsing."""
    lines = [
        "资金流水明细",
        "发生日期 买卖类别 证券代码 证券名称 成交数量 成交价格 总发生金额 手续费 印花税 过户费 资金余额",
        "20260612",
        "银行转证券",
        "CNY",
        "1.0000",
        "10000.00",
        "0.00",
        "10000.00",
        "第1页，共2页",  # Page marker
        "20260613",
        "证券买入",
        "600000",
        "浦发银行",
        "100",
        "12.50",
        "1250.00",
        "1.00",
        "0.00",
        "0.10",
        "8749.00",
    ]

    transactions = parse_dfzq_text(lines)

    # Should parse both transactions despite page marker
    assert len(transactions) >= 2
    assert transactions[0]["action"] == "DEPOSIT"
    assert transactions[1]["action"] == "BUY"


def test_parse_dfzq_handles_summary_section():
    """Parser should stop at summary section."""
    lines = [
        "资金流水明细",
        "发生日期 买卖类别 证券代码 证券名称 成交数量 成交价格 总发生金额 手续费 印花税 过户费 资金余额",
        "20260612",
        "银行转证券",
        "CNY",
        "1.0000",
        "10000.00",
        "0.00",
        "10000.00",
        "股票资料汇总",  # Summary section
        "600000.sh 浦发银行 100 1250.00",
        "成交汇总",
    ]

    transactions = parse_dfzq_text(lines)

    # Should have 1 deposit + 1 CHECKIN, no data from summary
    assert len(transactions) == 2
    assert transactions[0]["action"] == "DEPOSIT"


def test_parse_dfzq_constructs_ticker_suffix():
    """Parser should add correct suffix based on code."""
    lines = [
        "资金流水明细",
        "发生日期 买卖类别 证券代码 证券名称 成交数量 成交价格 总发生金额 手续费 资金余额",
        "20260612",
        "证券买入",
        "600000",  # Shanghai
        "浦发银行",
        "100",
        "12.50",
        "1250.00",
        "1.00",
        "10000.00",
        "20260613",
        "证券买入",
        "000001",  # Shenzhen
        "平安银行",
        "100",
        "15.00",
        "1500.00",
        "1.00",
        "8500.00",
    ]

    transactions = parse_dfzq_text(lines)

    assert transactions[0]["ticker"] == "600000.sh"
    assert transactions[1]["ticker"] == "000001.sz"


def test_parse_dfzq_handles_otc_securities():
    """Parser should handle OTC securities with .otc suffix."""
    lines = [
        "资金流水明细",
        "发生日期 买卖类别 证券代码 证券名称 成交数量 成交价格 总发生金额 手续费 资金余额",
        "20260612",
        "OTC资金划入",
        "850001",  # OTC code
        "100",
        "10.00",
        "1000.00",
        "1.00",
        "10000.00",
    ]

    transactions = parse_dfzq_text(lines)

    assert len(transactions) >= 1
    # OTC operations should be DEPOSIT/WITHDRAW
    assert transactions[0]["action"] in ("DEPOSIT", "WITHDRAW")


def test_parse_dfzq_aggregates_fees_in_note():
    """Parser should aggregate stamp tax and transfer fee in note."""
    lines = [
        "资金流水明细",
        "发生日期 买卖类别 证券代码 证券名称 成交数量 成交价格 总发生金额 手续费 印花税 过户费 资金余额",
        "20260612",
        "证券卖出",
        "600000",
        "浦发银行",
        "100",
        "13.00",
        "1298.40",
        "1.00",
        "0.50",
        "0.10",
        "10000.00",
    ]

    transactions = parse_dfzq_text(lines)

    assert transactions[0]["note"] == "手续费1.00 印花税0.50 过户费0.10"
    assert Decimal(transactions[0]["stamp_tax"]) == Decimal("0.50")
    assert Decimal(transactions[0]["transfer_fee"]) == Decimal("0.10")


def test_parse_dfzq_sorts_by_date():
    """Transactions should be sorted chronologically."""
    lines = [
        "资金流水明细",
        "发生日期 买卖类别 证券代码 证券名称 成交数量 成交价格 总发生金额 手续费 资金余额",
        "20260615",
        "银行转证券",
        "CNY",
        "1.0000",
        "5000.00",
        "0.00",
        "15000.00",
        "20260612",
        "银行转证券",
        "CNY",
        "1.0000",
        "10000.00",
        "0.00",
        "10000.00",
    ]

    transactions = parse_dfzq_text(lines)

    # Should be sorted: 20260612 before 20260615
    assert transactions[0]["date"] == "2026-06-12 00:00:00"
    assert transactions[1]["date"] == "2026-06-15 00:00:00"


def test_parse_dfzq_checkin_uses_last_balance():
    """CHECKIN event should use balance from last transaction."""
    lines = [
        "资金流水明细",
        "发生日期 买卖类别 证券代码 证券名称 成交数量 成交价格 总发生金额 手续费 资金余额",
        "20260612",
        "银行转证券",
        "10000.00",
        "0.00",
        "10000.00",
        "20260613",
        "证券买入",
        "600000",
        "浦发银行",
        "100",
        "12.50",
        "1250.00",
        "1.00",
        "8749.00",  # Final balance
    ]

    transactions = parse_dfzq_text(lines)

    # Last transaction should be CHECKIN with balance 8749
    checkin = transactions[-1]
    assert checkin["action"] == "CHECKIN"
    assert Decimal(checkin["amount"]) == Decimal("8749.00")
    assert checkin["date"] == "2026-06-13 00:00:00"  # Same date as last transaction


def test_parse_dfzq_handles_repo_transactions():
    """Repo transactions (融券) should be mapped to repo ticker."""
    lines = [
        "资金流水明细",
        "发生日期 买卖类别 证券代码 证券名称 成交数量 成交价格 总发生金额 手续费 资金余额",
        "20260612",
        "融券回购",
        "204001",
        "GC001",
        "100000",
        "2.50",
        "2500.00",
        "1.00",
        "97500.00",
    ]

    transactions = parse_dfzq_text(lines)

    assert transactions[0]["action"] == "BUY"
    assert transactions[0]["ticker"] == "204001"  # No suffix for repo
