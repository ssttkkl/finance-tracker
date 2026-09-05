"""Tests for convert layer — category + refund pairing logic"""
import os
import pytest
import tempfile
import csv
from decimal import Decimal
from pathlib import Path


TEST_DIR = Path(tempfile.mkdtemp())

def _pairs_only(tracking_pairs):
    return [p for p in (tracking_pairs or []) if not p.get("_acceptance")]


def _make_alipay_csv(rows: list[list[str]], path: str):
    """Write a minimal Alipay-style CSV"""
    header = ["交易时间", "交易分类", "交易对方", "商品说明", "收/支", "金额", "收/付款方式"]
    optional_headers = ["交易状态", "交易订单号", "商家订单号"]
    max_cols = max((len(r) for r in rows), default=len(header))
    if max_cols > len(header):
        header.extend(optional_headers[: max_cols - len(header)])
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(header)
        for r in rows:
            writer.writerow(r)


class TestAlipayCategory:
    """convert 层只看收支方向，不做语义判断"""

    def test_支出_交易关闭_导入(self):
        """007: 支出 + 交易关闭 + 有付款方式 → 已支付关单，必须导入。"""
        csv_path = str(TEST_DIR / "alipay_expense_closed.csv")
        _make_alipay_csv([
            ["2024-06-04 14:24:45", "交通出行", "铁路12306", "火车票", "支出", "433.00", "建设银行储蓄卡(2820)", "交易关闭", "OID_CLOSED"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, pairs = _read_alipay_raw(csv_path)
        assert len(records) == 1
        assert records[0]["amount"] == -433.0
        assert records[0].get("platform_status") == "交易关闭"

    def test_收入_交易关闭_未支付关闭跳过(self):
        """007 FR-008a: 非支出关闭 + 空付款方式 → skipped_unpaid_closed。"""
        csv_path = str(TEST_DIR / "alipay_income_closed.csv")
        _make_alipay_csv([
            ["2025-06-04 02:50:01", "收入", "****0", "专拍 定金50", "收入", "999.00", "", "交易关闭"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, pairs = _read_alipay_raw(csv_path)
        assert records == []
        acc = next(p["_acceptance"] for p in pairs if p.get("_acceptance"))
        assert acc["skipped_unpaid_closed"] == 1

    def test_普通消费_支出(self):
        csv_path = str(TEST_DIR / "alipay_normal.csv")
        _make_alipay_csv([
            ["2026-01-01 12:00:00", "消费", "商家A", "买书", "支出", "100.00", "工商银行信用卡(1200)"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert len(records) == 1
        assert records[0]["category"] == "expense"
        assert records[0]["amount"] == -100.0

    def test_支付宝_record_id_优先使用交易订单号(self):
        csv_path = str(TEST_DIR / "alipay_txn_id.csv")
        _make_alipay_csv([
            ["2026-01-01 12:00:00", "消费", "商家A", "买书", "支出", "100.00", "工商银行信用卡(1200)", "交易成功", "2026010122001111111111111111", "MEO_1"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert len(records) == 1
        assert records[0]["_fact_id"] == "alipay_2026010122001111111111111111"

    def test_孤退款_收入方向_无原记录(self):
        """单独一条退款（方向=收入，无对应原记录）→ 保持 income"""
        csv_path = str(TEST_DIR / "alipay_orphan_refund.csv")
        _make_alipay_csv([
            ["2026-01-02 10:00:00", "退款", "商家A", "退款-买书", "收入", "100.00", "工商银行信用卡(1200)"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert len(records) == 1
        assert records[0]["category"] == "income"
        assert records[0]["amount"] == 100.0

    def test_孤退款_不计收支方向_无原记录(self):
        """单独一条退款（方向=不计收支，无对应原记录）→ 保持 income"""
        csv_path = str(TEST_DIR / "alipay_orphan_refund_nocount.csv")
        _make_alipay_csv([
            ["2026-01-02 10:00:00", "退款", "商家A", "退款-买书", "不计收支", "100.00", "工商银行信用卡(1200)"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert len(records) == 1
        assert records[0]["category"] == "income"
        assert records[0]["amount"] == 100.0

    def test_账户提现_不计收支方向_余额流出(self):
        """支付宝提现到银行卡：方向=不计收支，但支付宝余额应减少。"""
        csv_path = str(TEST_DIR / "alipay_account_withdrawal_nocount.csv")
        _make_alipay_csv([
            ["2023-06-15 12:25:59", "账户提现", "中国工商银行", "提现-实时提现", "不计收支", "200.00", "余额"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert len(records) == 1
        assert records[0]["category"] == "expense"
        assert records[0]["amount"] == -200.0
        assert records[0]["counterparty"] == "中国工商银行"
        assert records[0]["note"] == "提现-实时提现"

    def test_不计收支_已关闭订单跳过(self):
        csv_path = str(TEST_DIR / "alipay_closed_status.csv")
        _make_alipay_csv([
            ["2025-07-20 19:29:50", "充值缴费", "北京市自来水集团有限责任公司", "7月-水费", "不计收支", "45.00", "", "已关闭"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert records == []

    def test_不计收支_还款失败跳过(self):
        csv_path = str(TEST_DIR / "alipay_repayment_failed.csv")
        _make_alipay_csv([
            ["2023-07-11 08:18:07", "信用借还", "花呗", "花呗自动还款", "不计收支", "1350.30", "", "还款失败"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert records == []

    def test_不计收支_转入余利宝_余额流出(self):
        csv_path = str(TEST_DIR / "alipay_yulibao_inflow_nocount.csv")
        _make_alipay_csv([
            ["2025-06-10 23:06:02", "投资理财", "网商银行", "支付宝转入到余利宝", "不计收支", "186.00", "账户余额", "交易成功"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert len(records) == 1
        assert records[0]["category"] == "expense"
        assert records[0]["amount"] == -186.0

    def test_不计收支_基金买入_付款账户流出(self):
        csv_path = str(TEST_DIR / "alipay_fund_buy_nocount.csv")
        _make_alipay_csv([
            ["2025-02-27 09:14:17", "投资理财", "蚂蚁财富-蚂蚁（杭州）基金销售有限公司", "蚂蚁财富-大成纳斯达克100ETF联接(QDII)C-买入", "不计收支", "100.00", "中国建设银行储蓄卡(2820)", "交易成功"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert len(records) == 1
        assert records[0]["category"] == "expense"
        assert records[0]["amount"] == -100.0

    def test_不计收支_转出到网商银行_余额流出(self):
        csv_path = str(TEST_DIR / "alipay_to_mybank_nocount.csv")
        _make_alipay_csv([
            ["2026-06-02 00:23:51", "其他", "网商银行", "转出到网商银行", "不计收支", "485.73", "账户余额", "交易成功"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert len(records) == 1
        assert records[0]["category"] == "expense"
        assert records[0]["amount"] == Decimal("-485.73")

    def test_不计收支_收益发放保持收入(self):
        csv_path = str(TEST_DIR / "alipay_yuebao_yield_nocount.csv")
        _make_alipay_csv([
            ["2025-08-17 03:04:40", "投资理财", "长城基金管理有限公司", "余额宝-2025.08.16-收益发放", "不计收支", "0.03", "余额宝", "交易成功"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert len(records) == 1
        assert records[0]["category"] == "income"
        assert records[0]["amount"] == Decimal("0.03")

    def test_全额退款_收入方向(self):
        """方向=收入，全额退款 → convert 保留消费与退款两条事实。"""
        csv_path = str(TEST_DIR / "alipay_full_refund.csv")
        _make_alipay_csv([
            ["2026-01-01 12:00:00", "消费", "商家A", "买书", "支出", "100.00", "工商银行信用卡(1200)"],
            ["2026-01-05 10:00:00", "退款", "商家A", "退款-买书", "收入", "100.00", "工商银行信用卡(1200)"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, tracking_pairs = _read_alipay_raw(csv_path)
        assert len(records) == 2
        assert len(_pairs_only(tracking_pairs)) == 1
        assert {r["category"] for r in records} == {"expense", "income"}

    def test_全额退款_不计收支方向(self):
        """方向=不计收支，全额退款 → convert 保留消费与退款两条事实。"""
        csv_path = str(TEST_DIR / "alipay_full_refund_nocount.csv")
        _make_alipay_csv([
            ["2026-01-01 12:00:00", "消费", "商家A", "买书", "支出", "100.00", "工商银行信用卡(1200)"],
            ["2026-01-05 10:00:00", "退款", "商家A", "退款-买书", "不计收支", "100.00", "工商银行信用卡(1200)"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, tracking_pairs = _read_alipay_raw(csv_path)
        assert len(records) == 2
        assert len(_pairs_only(tracking_pairs)) == 1
        assert {r["category"] for r in records} == {"expense", "income"}

    def test_部分退款_不计收支方向(self):
        """方向=不计收支，部分退款30 → convert 保留消费与退款原始事实。"""
        csv_path = str(TEST_DIR / "alipay_partial_nocount.csv")
        _make_alipay_csv([
            ["2026-01-01 12:00:00", "消费", "商家A", "买书", "支出", "100.00", "工商银行信用卡(1200)"],
            ["2026-01-05 10:00:00", "退款", "商家A", "退款-买书", "不计收支", "30.00", "工商银行信用卡(1200)"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, tracking_pairs = _read_alipay_raw(csv_path)
        assert len(records) == 2
        expense = next(r for r in records if r["category"] == "expense")
        refund = next(r for r in records if r["category"] == "income")
        assert expense["amount"] == -100.0
        assert refund["amount"] == 30.0
        assert _pairs_only(tracking_pairs)[0]["match_type"] == "partial"

    def test_不计收支_非退款_转出(self):
        """不计收支 + 转出到网商银行 → 从支付宝余额视角是资产流出。"""
        csv_path = str(TEST_DIR / "alipay_transfer_out_nocount.csv")
        _make_alipay_csv([
            ["2026-01-05 08:00:00", "其他", "网商银行", "转出到网商银行", "不计收支", "485.73", "账户余额"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert len(records) == 1
        assert records[0]["category"] == "expense"
        assert records[0]["amount"] == Decimal("-485.73")

    def test_不计收支_零金额_导入解冻(self):
        """007: 0 元解冻/免押 MUST 导入（余额无影响）。"""
        csv_path = str(TEST_DIR / "alipay_zero_nocount.csv")
        _make_alipay_csv([
            ["2026-01-05 08:00:00", "信用借还", "哈啰好物", "预授权解冻", "不计收支", "0.00", "花呗", "解冻成功", "UF1"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert len(records) == 1
        assert records[0]["amount"] == 0

    def test_退款_优先匹配更早的消费(self):
        """同商家同金额且描述能唯一收窄时，仍按更强业务信号匹配。"""
        csv_path = str(TEST_DIR / "alipay_refund_match_earlier.csv")
        _make_alipay_csv([
            ["2026-01-01 12:00:00", "消费", "京东", "商品A", "支出", "100.00", "花呗"],
            ["2026-01-02 14:00:00", "消费", "京东", "商品B", "支出", "100.00", "花呗"],
            ["2026-01-05 10:00:00", "退款", "京东", "退款-商品A", "不计收支", "100.00", "花呗"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, tracking_pairs = _read_alipay_raw(csv_path)
        assert len(records) == 3
        assert _pairs_only(tracking_pairs)[0]["expense"]["note"] == "商品A"

    def test_退款_无说明时匹配最近的那笔(self):
        """同商家同金额都无说明时，按最近候选自动锁定关系。"""
        csv_path = str(TEST_DIR / "alipay_refund_no_desc.csv")
        _make_alipay_csv([
            ["2026-01-01 12:00:00", "消费", "京东", "", "支出", "100.00", "花呗"],
            ["2026-01-02 14:00:00", "消费", "京东", "", "支出", "100.00", "花呗"],
            ["2026-01-05 10:00:00", "退款", "京东", "", "不计收支", "100.00", "花呗"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, tracking_pairs = _read_alipay_raw(csv_path)
        assert len(records) == 3
        assert _pairs_only(tracking_pairs)[0]["expense"]["date"] == "2026-01-02 14:00:00"
        assert _pairs_only(tracking_pairs)[0]["candidate_count"] == 2
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "strong"

    def test_退款成功_非退款分类_仍识别为强信号退款(self):
        csv_path = str(TEST_DIR / "alipay_refund_status_strong.csv")
        _make_alipay_csv([
            ["2026-01-21 21:13:04", "交通出行", "高德打车", "高德打车订单", "支出", "22.00", "工行信用卡(1200)", "交易成功"],
            ["2026-01-21 21:13:48", "交通出行", "高德打车", "退款-高德打车订单", "不计收支", "5.29", "工行信用卡(1200)", "退款成功"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, tracking_pairs = _read_alipay_raw(csv_path)
        assert len(records) == 2
        assert next(r for r in records if r["category"] == "expense")["amount"] == -22.0
        assert next(r for r in records if r["category"] == "income")["amount"] == Decimal("5.29")
        assert _pairs_only(tracking_pairs)[0]["source_refund_signal"] == "alipay_status"
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "strong"

    def test_退款分类且不计收支_交易成功_仍识别为强信号退款(self):
        csv_path = str(TEST_DIR / "alipay_refund_category_nocount.csv")
        _make_alipay_csv([
            ["2026-01-01 12:00:00", "投资理财", "蚂蚁财富", "基金买入", "不计收支", "100.00", "建行储蓄卡(2820)", "交易成功"],
            ["2026-01-02 09:00:00", "退款", "蚂蚁财富", "买入退款", "不计收支", "100.00", "建行储蓄卡(2820)", "交易成功"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, tracking_pairs = _read_alipay_raw(csv_path)
        assert len(records) == 2
        assert _pairs_only(tracking_pairs)[0]["source_refund_signal"] == "alipay_category_nocount"
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "strong"

    def test_退款_优先按商家订单号唯一锁定原消费(self):
        csv_path = str(TEST_DIR / "alipay_refund_merchant_order_match.csv")
        _make_alipay_csv([
            ["2026-01-01 10:00:00", "餐饮美食", "美团", "美团订单-AAA111", "支出", "50.00", "支付宝余额", "交易成功", "txn_1", "MEO_1"],
            ["2026-01-01 11:00:00", "餐饮美食", "美团", "美团订单-BBB222", "支出", "50.00", "支付宝余额", "交易成功", "txn_2", "MEO_2"],
            ["2026-01-02 09:00:00", "退款", "美团", "退款-美团订单-BBB222", "不计收支", "50.00", "支付宝余额", "退款成功", "txn_refund_2", "MEO_2"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, tracking_pairs = _read_alipay_raw(csv_path)
        assert len(records) == 3
        assert _pairs_only(tracking_pairs)[0]["expense"]["note"] == "美团订单-BBB222"
        assert _pairs_only(tracking_pairs)[0]["rule_hint"] in {"refund_merchant_order_match", "refund_desc_order_match"}
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "strong"

    def test_退款_优先按退款交易订单号_base_锁定原消费(self):
        csv_path = str(TEST_DIR / "alipay_refund_txn_base_match.csv")
        _make_alipay_csv([
            ["2026-01-01 10:00:00", "交通出行", "铁路12306", "火车票", "支出", "100.00", "建行储蓄卡(2820)", "交易成功", "202601011000000001", "MO_1"],
            ["2026-01-01 11:00:00", "交通出行", "铁路12306", "火车票", "支出", "100.00", "建行储蓄卡(2820)", "交易成功", "202601011100000002", "MO_2"],
            ["2026-01-02 09:00:00", "交通出行", "退款方", "退款-火车票", "不计收支", "100.00", "建行储蓄卡(2820)", "退款成功", "202601011100000002_202601020900000999", ""],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, tracking_pairs = _read_alipay_raw(csv_path)
        assert len(records) == 3
        assert _pairs_only(tracking_pairs)[0]["expense"]["txn_id"] == "202601011100000002"
        assert _pairs_only(tracking_pairs)[0]["rule_hint"] in {"refund_txn_base_match", "import.alipay.order_prefix.v1"}
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "strong"

    def test_退款_优先按描述业务单号锁定原消费(self):
        csv_path = str(TEST_DIR / "alipay_refund_desc_order_match.csv")
        _make_alipay_csv([
            ["2026-01-01 10:00:00", "餐饮美食", "美团", "美团订单-AAA111", "支出", "30.00", "支付宝余额", "交易成功", "txn_1", ""],
            ["2026-01-01 11:00:00", "餐饮美食", "美团", "美团订单-BBB222", "支出", "30.00", "支付宝余额", "交易成功", "txn_2", ""],
            ["2026-01-02 09:00:00", "退款", "美团", "退款-美团订单-BBB222", "不计收支", "30.00", "支付宝余额", "退款成功", "txn_refund_2", ""],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, tracking_pairs = _read_alipay_raw(csv_path)
        assert len(records) == 3
        assert _pairs_only(tracking_pairs)[0]["expense"]["note"] == "美团订单-BBB222"
        assert _pairs_only(tracking_pairs)[0]["rule_hint"] == "refund_desc_order_match"
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "strong"

    def test_退款_交易分类非退款_按说明兜底(self):
        """交易分类≠退款但仅靠描述含退款语义时，保留为 weak 供 AI 审查。"""
        csv_path = str(TEST_DIR / "alipay_refund_desc_only.csv")
        _make_alipay_csv([
            ["2026-01-21 21:13:04", "交通出行", "麦当劳", "堂食", "支出", "30.00", "支付宝余额", "交易成功"],
            ["2026-01-21 21:13:48", "交通出行", "退款方", "退款-堂食", "收入", "30.00", "支付宝余额", "交易成功"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, tracking_pairs = _read_alipay_raw(csv_path)
        assert len(records) == 2
        assert _pairs_only(tracking_pairs)[0]["rule_hint"] == "refund_desc_fallback"
        assert _pairs_only(tracking_pairs)[0]["source_refund_signal"] == "alipay_desc"
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "weak"

    def test_支付宝强退款信号_标题去退款前缀后长公共前缀可自动核销(self):
        csv_path = str(TEST_DIR / "alipay_refund_desc_confirm_strong.csv")
        _make_alipay_csv([
            ["2023-06-21 19:17:51", "家居家装", "gr**店", "洁丽雅凉拖鞋女士夏季男防滑浴室洗澡eva踩屎感室内家居家用情侣", "支出", "12.90", "花呗", "交易成功", "2023062122001112651410697832", "T200P1919551332039815681"],
            ["2023-07-16 12:42:18", "退款", "gr**店", "退款-洁丽雅凉拖鞋女士夏季男防滑浴室洗澡eva踩屎感室内家居情侣新款", "不计收支", "12.90", "花呗", "退款成功", "2023071622001112651422694804_1934049903688815681", "T200P1934049903688815681"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, tracking_pairs = _read_alipay_raw(csv_path)
        assert len(records) == 2
        assert _pairs_only(tracking_pairs)[0]["rule_hint"] == "refund_cp_match"
        assert _pairs_only(tracking_pairs)[0]["candidate_count"] == 1
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "strong"

    def test_退款_不能跨付款账户匹配(self):
        csv_path = str(TEST_DIR / "alipay_refund_account_mismatch.csv")
        _make_alipay_csv([
            ["2026-03-06 20:26:41", "消费", "匹歪", "PY市场-虚拟物品购买", "支出", "296.98", "网商储蓄卡(4164)"],
            ["2026-04-30 16:47:25", "退款", "匹歪", "退款-PY市场-虚拟物品购买", "收入", "89.50", "建行储蓄卡(2820)"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert len(records) == 2
        expense = next(r for r in records if r["category"] == "expense")
        refund = next(r for r in records if r["category"] == "income")
        assert expense["amount"] == Decimal("-296.98")
        assert refund["amount"] == 89.5

    def test_退款_不能跨过长时间窗口匹配(self):
        csv_path = str(TEST_DIR / "alipay_refund_far_apart.csv")
        _make_alipay_csv([
            ["2026-02-11 19:36:11", "消费", "tb**9", "DAYNY高弹正肩修身显壮打底长袖健身显壮内搭t恤百搭休闲美式打底", "支出", "62.62", "工行信用卡(1200)"],
            ["2026-05-19 18:14:21", "退款", "tb**9", "退款-DAY NY凉感极简正肩boxy短袖t恤男美式休闲收袖口健身内搭上衣", "收入", "51.30", "工行信用卡(1200)"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert len(records) == 2
        expense = next(r for r in records if r["category"] == "expense")
        refund = next(r for r in records if r["category"] == "income")
        assert expense["amount"] == Decimal("-62.62")
        assert refund["amount"] == Decimal("51.30")


# ── 微信 ──────────────────────────────────────────────────

def _make_wechat_xlsx(rows: list[list[str]], path: str):
    import openpyxl
    wb = openpyxl.Workbook()
    ws = wb.active
    header = ["交易时间", "交易对方", "商品", "收/支", "金额(元)", "支付方式", "当前状态"]
    optional_headers = ["交易类型", "交易单号", "商户单号"]
    max_cols = max((len(r) for r in rows), default=len(header))
    if max_cols > len(header):
        header.extend(optional_headers[: max_cols - len(header)])
    ws.append(header)
    for r in rows:
        ws.append(r)
    wb.save(path)


class TestWechatCategory:
    def test_普通消费_支出(self):
        path = str(TEST_DIR / "wechat_normal.xlsx")
        _make_wechat_xlsx([
            ["2026-01-01 12:00:00", "商家A", "奶茶", "支出", "30.00", "零钱", "支付成功"],
        ], path)
        from ft.convert import _read_wechat_raw
        records, _ = _read_wechat_raw(path)
        assert len(records) == 1
        assert records[0]["category"] == "expense"
        assert records[0]["amount"] == -30.0

    def test_微信_record_id_优先使用交易单号(self):
        path = str(TEST_DIR / "wechat_txn_id.xlsx")
        _make_wechat_xlsx([
            ["2026-01-01 12:00:00", "商家A", "奶茶", "支出", "30.00", "零钱", "支付成功", "商户消费", "4200000000000000000000000001", "MCH_1"],
        ], path)
        from ft.convert import _read_wechat_raw
        records, _ = _read_wechat_raw(path)
        assert len(records) == 1
        assert records[0]["_fact_id"] == "wechat_4200000000000000000000000001"

    def test_二维码收款_已收钱_收入(self):
        path = str(TEST_DIR / "wechat_qr_income_received.xlsx")
        _make_wechat_xlsx([
            ["2024-01-12 18:47:05", "聂龙羽", "收款方备注:二维码收款", "收入", "30.00", "零钱", "已收钱", "二维码收款"],
        ], path)
        from ft.convert import _read_wechat_raw
        records, _ = _read_wechat_raw(path)
        assert len(records) == 1
        assert records[0]["category"] == "income"
        assert records[0]["amount"] == 30.0

    def test_零钱提现_中性_银行卡入账(self):
        """零钱提现 = 微信零钱出账；到账卡仅作证据，现金账单适配层统一为「微信零钱」。"""
        path = str(TEST_DIR / "wechat_wallet_withdrawal.xlsx")
        _make_wechat_xlsx([
            ["2025-08-17 23:54:28", "建设银行(2820)", "/", "/", "2100.00", "建设银行储蓄卡(2820)", "提现已到账", "零钱提现"],
        ], path)
        from ft.convert import _read_wechat_raw
        records, _ = _read_wechat_raw(path)
        assert len(records) == 1
        assert records[0]["category"] == "expense"
        assert records[0]["amount"] == -2100.0
        assert records[0]["payment_method"] == "零钱"
        # destination card retained for evidence
        assert "建设银行" in str(records[0].get("counterparty") or "")

    def test_零钱充值_中性_付款账户流出(self):
        path = str(TEST_DIR / "wechat_wallet_recharge.xlsx")
        _make_wechat_xlsx([
            ["2025-05-27 16:31:28", "建设银行(2820)", "/", "/", "240.00", "建设银行储蓄卡(2820)", "充值完成", "零钱充值"],
        ], path)
        from ft.convert import _read_wechat_raw
        records, _ = _read_wechat_raw(path)
        assert len(records) == 1
        assert records[0]["category"] == "expense"
        assert records[0]["amount"] == -240.0
        assert records[0]["payment_method"] == "建设银行储蓄卡(2820)"

    def test_购买理财通_中性_付款账户流出(self):
        path = str(TEST_DIR / "wechat_licaitong_buy.xlsx")
        _make_wechat_xlsx([
            ["2025-04-10 17:32:52", "理财通", "国泰利泽90天债券C(013066)", "/", "6000.00", "建设银行储蓄卡", "支付成功", "购买理财通"],
        ], path)
        from ft.convert import _read_wechat_raw
        records, _ = _read_wechat_raw(path)
        assert len(records) == 1
        assert records[0]["category"] == "expense"
        assert records[0]["amount"] == -6000.0
        assert records[0]["note"] == "国泰利泽90天债券C(013066)"

    def test_信用卡还款_中性_付款账户流出(self):
        path = str(TEST_DIR / "wechat_credit_card_repayment.xlsx")
        _make_wechat_xlsx([
            ["2025-10-04 16:52:41", "工商银行信用卡还款", "/", "/", "500.00", "零钱", "支付成功", "信用卡还款"],
        ], path)
        from ft.convert import _read_wechat_raw
        records, _ = _read_wechat_raw(path)
        assert len(records) == 1
        assert records[0]["category"] == "expense"
        assert records[0]["amount"] == -500.0
        assert records[0]["note"] == "信用卡还款"

    def test_微信自助侠设备号部分退款_识别为强匹配(self):
        path = str(TEST_DIR / "wechat_refund_zizhuxia_device.xlsx")
        _make_wechat_xlsx([
            ["2025-05-27 21:49:25", "自助侠", "充电柜-1017122_2", "支出", "2.00", "零钱", "已退款(¥0.73)", "商户消费", "4200002659202505274772023434", "010233939681236096"],
            ["2025-05-28 06:06:37", "自助侠", "自助侠", "收入", "0.73", "零钱", "已退款¥0.73", "自助侠-退款", "50301903272025052895854168505", ""],
        ], path)
        from ft.convert import _read_wechat_raw
        records, tracking_pairs = _read_wechat_raw(path)
        assert len(records) == 2
        assert next(r for r in records if r["category"] == "expense")["amount"] == -2.0
        assert next(r for r in records if r["category"] == "income")["amount"] == Decimal("0.73")
        assert any(k in _pairs_only(tracking_pairs)[0]["rule_hint"] for k in ("wechat", "device", "partial", "embedded"))
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "strong"

    def test_微信美团平台退款_按订单号唯一锁定原消费(self):
        path = str(TEST_DIR / "wechat_refund_meituan_order.xlsx")
        _make_wechat_xlsx([
            ["2025-12-02 09:41:13", "美团", "瑞幸咖啡-美团App-25120211100400001300774750489312", "支出", "17.80", "工商银行信用卡(9166)", "已全额退款", "商户消费", "4200002957202512028946944728", "20251202094109U95283610624414069"],
            ["2025-12-02 18:41:26", "美团", "麻小磊串串麻辣烫-美团App-25120211100400001300859984400312", "支出", "9.90", "工商银行信用卡(9166)", "已全额退款", "商户消费", "4200002875202512020751494795", "20251202184122U54819688981374182"],
            ["2025-12-09 19:59:48", "美团平台商户", "美团平台商户", "收入", "9.90", "工商银行信用卡(9166)", "已全额退款", "美团平台商户-退款", "50103805552025120937120252155", ""],
        ], path)
        from ft.convert import _read_wechat_raw
        records, tracking_pairs = _read_wechat_raw(path)
        assert len(records) == 3
        assert _pairs_only(tracking_pairs)[0]["expense"]["note"] == "麻小磊串串麻辣烫-美团App-25120211100400001300859984400312"
        assert any(k in _pairs_only(tracking_pairs)[0]["rule_hint"] for k in ("wechat", "full", "meituan"))
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "strong"

    def test_微信订单号级唯一命中_超过14天仍自动核销(self):
        path = str(TEST_DIR / "wechat_refund_meituan_order_far_apart.xlsx")
        _make_wechat_xlsx([
            ["2025-12-02 09:41:13", "美团", "瑞幸咖啡-美团App-25120211100400001300774750489312", "支出", "17.80", "工商银行信用卡(9166)", "已全额退款", "商户消费", "4200002957202512028946944728", "20251202094109U95283610624414069"],
            ["2026-01-02 06:28:21", "美团平台商户", "美团平台商户", "收入", "17.80", "工商银行信用卡(9166)", "已全额退款", "美团平台商户-退款", "50102705982026010294929152629", ""],
        ], path)
        from ft.convert import _read_wechat_raw
        records, tracking_pairs = _read_wechat_raw(path)
        assert len(records) == 2
        assert any(k in _pairs_only(tracking_pairs)[0]["rule_hint"] for k in ("wechat", "full", "meituan"))
        assert _pairs_only(tracking_pairs)[0]["candidate_count"] == 1
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "strong"

    def test_微信互联互通钱包充值部分退款_按稳定描述强匹配(self):
        path = str(TEST_DIR / "wechat_refund_wallet_token.xlsx")
        _make_wechat_xlsx([
            ["2025-09-08 20:05:19", "互联互通", "钱包充值", "支出", "2.00", "建设银行储蓄卡(2820)", "已退款(¥0.70)", "商户消费", "4200002846202509086533217395", "8004166849525251"],
            ["2025-09-09 04:01:03", "互联互通", "互联互通", "收入", "0.70", "建设银行储蓄卡(2820)", "已退款¥0.70", "互联互通-退款", "50100204552025090915297649813", ""],
        ], path)
        from ft.convert import _read_wechat_raw
        records, tracking_pairs = _read_wechat_raw(path)
        assert len(records) == 2
        assert next(r for r in records if r["category"] == "expense")["amount"] == -2.0
        assert next(r for r in records if r["category"] == "income")["amount"] == Decimal("0.70")
        assert any(k in _pairs_only(tracking_pairs)[0]["rule_hint"] for k in ("wechat", "partial", "embedded", "token"))
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "strong"

    def test_微信品牌别名退款_仍识别为强匹配(self):
        path = str(TEST_DIR / "wechat_refund_brand_alias.xlsx")
        _make_wechat_xlsx([
            ["2024-12-26 14:55:45", "UNIQLO", "优衣库商品", "支出", "79.00", "工商银行信用卡(1200)", "已全额退款", "商户消费", "4200002363202412263911689588", "ZFDD02024122629136430461"],
            ["2024-12-26 14:58:08", "优衣库", "优衣库", "收入", "79.00", "工商银行信用卡(1200)", "已全额退款", "优衣库-退款", "50302801902024122666359503558", ""],
        ], path)
        from ft.convert import _read_wechat_raw
        records, tracking_pairs = _read_wechat_raw(path)
        assert len(records) == 2
        assert any(k in _pairs_only(tracking_pairs)[0]["rule_hint"] for k in ("wechat", "full", "brand"))
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "strong"

    def test_微信红包退回_归为转账退回不生成消费退款关系(self):
        path = str(TEST_DIR / "wechat_refund_red_packet_auto.xlsx")
        _make_wechat_xlsx([
            ["2025-05-15 17:09:34", "发给是我小转转啊", "/", "支出", "50.00", "零钱", "已全额退款", "微信红包（单发）", "100003980125051500055211649566164214", "1000039801202505157184950651034"],
            ["2025-05-16 17:09:37", "/", "/", "收入", "50.00", "零钱", "已全额退款", "微信红包-退款", "1000039801202505157184950651034", ""],
        ], path)
        from ft.convert import _read_wechat_raw
        records, tracking_pairs = _read_wechat_raw(path)
        assert len(records) == 2
        assert {row["record_type"] for row in records} == {"transfer_reversal"}
        assert _pairs_only(tracking_pairs) == []

    def test_微信转账退回_归为转账退回不生成消费退款关系(self):
        path = str(TEST_DIR / "wechat_refund_transfer_auto.xlsx")
        _make_wechat_xlsx([
            ["2026-03-07 04:11:13", "是我小转转啊", "转账备注:微信转账", "支出", "60.00", "建设银行储蓄卡(2820)", "已全额退款", "转账", "53010002371104202603070433707100", "1000050001202603070820865004483"],
            ["2026-03-08 04:11:14", "/", "转账备注:微信转账", "收入", "60.00", "建设银行储蓄卡(2820)", "已全额退款", "转账-退款", "132100005020107202603080012202175009214", ""],
        ], path)
        from ft.convert import _read_wechat_raw
        records, tracking_pairs = _read_wechat_raw(path)
        assert len(records) == 2
        assert {row["record_type"] for row in records} == {"transfer_reversal"}
        assert _pairs_only(tracking_pairs) == []

    def test_微信多候选退款_按最近候选自动核销(self):
        path = str(TEST_DIR / "wechat_refund_recent_candidate_tiebreak.xlsx")
        _make_wechat_xlsx([
            ["2025-05-01 08:00:00", "自助侠", "充电柜-1017122_2", "支出", "2.00", "零钱", "已退款(¥0.73)", "商户消费", "4200002659202505011111111111", "010233900000000001"],
            ["2025-05-07 08:00:00", "自助侠", "充电柜-1017122_2", "支出", "2.00", "零钱", "已退款(¥0.73)", "商户消费", "4200002659202505072222222222", "010233900000000002"],
            ["2025-05-08 09:40:26", "自助侠", "自助侠", "收入", "0.73", "零钱", "已退款¥0.73", "自助侠-退款", "50303603312025050811743080052", ""],
        ], path)
        from ft.convert import _read_wechat_raw
        records, tracking_pairs = _read_wechat_raw(path)
        assert len(records) == 3
        retained = next(r for r in records if r["date"] == "2025-05-01 08:00:00")
        matched = next(r for r in records if r["date"] == "2025-05-07 08:00:00")
        refund = next(r for r in records if r["category"] == "income")
        assert retained["amount"] == -2.0
        assert matched["amount"] == -2.0
        assert refund["amount"] == Decimal("0.73")
        assert _pairs_only(tracking_pairs)[0]["expense"]["date"] == "2025-05-07 08:00:00"
        assert any(k in _pairs_only(tracking_pairs)[0]["rule_hint"] for k in ("wechat", "device", "partial", "embedded"))
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "strong"

    def test_微信京东拆分退款_连续冲减同一原消费(self):
        path = str(TEST_DIR / "wechat_refund_jd_split.xlsx")
        _make_wechat_xlsx([
            ["2024-11-11 01:15:51", "京东", "京东-订单编号299561054326", "支出", "557.92", "零钱", "已退款(¥470.72)", "商户消费", "42000000000000000001", "4061882411110115470131313588"],
            ["2024-11-11 01:16:12", "京东商城平台商户", "京东商城平台商户", "收入", "341.30", "零钱", "已退款¥470.72", "京东商城平台商户-退款", "50300801362024111145407841691", ""],
            ["2024-11-11 01:16:17", "京东商城平台商户", "京东商城平台商户", "收入", "32.56", "零钱", "已退款¥470.72", "京东商城平台商户-退款", "50300801362024111115414528395", ""],
            ["2024-11-11 01:16:25", "京东商城平台商户", "京东商城平台商户", "收入", "96.86", "零钱", "已退款¥470.72", "京东商城平台商户-退款", "50300801362024111115462617037", ""],
        ], path)
        from ft.convert import _read_wechat_raw
        records, tracking_pairs = _read_wechat_raw(path)
        assert len(records) == 4
        assert next(r for r in records if r["category"] == "expense")["amount"] == Decimal("-557.92")
        assert len([r for r in records if r["category"] == "income"]) == 3
        assert len(_pairs_only(tracking_pairs)) == 3
        assert all(p["match_strength"] == "strong" for p in _pairs_only(tracking_pairs))


# ── 消费平台推断 ──────────────────────────────────────────

class TestInferPlatform:
    """_infer_platform 从交易对方/说明推断消费平台

    设计规则：
    - 只识别公司级/连锁品牌，个人商家不建规则
    - 无匹配返回空（不 fallback）
    - 美团 O2O（外卖/到店）不标为美团，只标自有服务（单车/小象超市等）
    """

    # ── 平台品牌识别 ──

    def test_京东(self):
        from ft.convert import _infer_platform
        assert _infer_platform("京东", "京东超市", "wechat") == "京东"

    def test_淘宝_from_对方名(self):
        from ft.convert import _infer_platform
        assert _infer_platform("淘宝", "商品", "alipay") == "淘宝"

    def test_天猫(self):
        from ft.convert import _infer_platform
        assert _infer_platform("天猫**市", "商品", "alipay") == "天猫"

    def test_拼多多(self):
        from ft.convert import _infer_platform
        assert _infer_platform("拼多多", "", "wechat") == "拼多多"

    def test_饿了么_O2O_不标(self):
        """饿了么是O2O外卖平台，不标平台"""
        from ft.convert import _infer_platform
        assert _infer_platform("饿了么", "外卖", "alipay") == ""

    def test_拉扎斯_饿了么公司_O2O(self):
        """拉扎斯=饿了么运营主体，同样不标"""
        from ft.convert import _infer_platform
        assert _infer_platform("", "支付宝-上海拉扎斯信息科技有限", "icbc") == ""

    def test_淘宝闪购_O2O_不标(self):
        """淘宝闪购通过淘宝平台消费，标淘宝"""
        from ft.convert import _infer_platform
        assert _infer_platform("淘宝闪购", "外卖订单", "alipay") == "淘宝"

    def test_高德团购_O2O_不标(self):
        """高德团购是本地优惠，不标为高德"""
        from ft.convert import _infer_platform
        assert _infer_platform("", "高德团购-温野菜涮涮锅", "alipay") == ""

    def test_高德打车_自有服务(self):
        """高德打车是高德自有出行服务"""
        from ft.convert import _infer_platform
        assert _infer_platform("高德打车", "高德打车订单", "alipay") == "高德"

    def test_高德信息技术_公司全名(self):
        """高德公司全名仍识别为高德（账单扣款给高德公司）"""
        from ft.convert import _infer_platform
        assert _infer_platform("", "支付宝-高德信息技术有限公司", "icbc") == "高德"

    def test_滴滴(self):
        from ft.convert import _infer_platform
        assert _infer_platform("广州骑安", "先乘车后付款", "wechat") == "滴滴"

    # ── 美团：只标自有服务，不标 O2O ──

    def test_美团_自有服务_单车(self):
        """先骑后付=美团单车，是美团自有服务"""
        from ft.convert import _infer_platform
        assert _infer_platform("美团", "先骑后付", "wechat") == "美团"

    def test_美团_自有服务_小象超市(self):
        """北京象鲜科技=小象超市，是美团自有服务"""
        from ft.convert import _infer_platform
        assert _infer_platform("", "美团支付-北京象鲜科技有限公司", "icbc") == "美团"

    def test_美团_自有服务_三快在线(self):
        """三快在线=美团母公司平台"""
        from ft.convert import _infer_platform
        assert _infer_platform("", "支付宝-北京三快在线科技有限公司", "icbc") == "美团"

    def test_美团_自有服务_平台商户(self):
        """美团平台商户=美团自有服务"""
        from ft.convert import _infer_platform
        assert _infer_platform("", "美团平台商户", "wechat") == "美团"

    def test_美团_O2O_标记具体商家(self):
        """美团App上的外卖商家（霸王茶姬）标记为霸王茶姬"""
        from ft.convert import _infer_platform
        assert _infer_platform("", "霸王茶姬-美团App-260524", "alipay") == "霸王茶姬"

    def test_美团_O2O_食其家(self):
        """美团App上食其家标记为食其家"""
        from ft.convert import _infer_platform
        assert _infer_platform("", "美团App食其家牛丼咖喱", "icbc") == "食其家"

    def test_美团_O2O_未知商家(self):
        """美团App上不在规则中的商家 → 空"""
        from ft.convert import _infer_platform
        assert _infer_platform("", "美团App无名小店", "icbc") == ""

    def test_美团_O2O_大众点评不标(self):
        """大众点评App（美团系）的中介消费不标，具体品牌有规则则标"""
        from ft.convert import _infer_platform
        assert _infer_platform("", "东方唯尔-大众点评App", "alipay") == ""

    def test_美团_O2O_连锁店优先(self):
        """美团App麦当劳→麦当劳（连锁品牌优先于美团）"""
        from ft.convert import _infer_platform
        assert _infer_platform("", "美团支付-美团App麦当劳麦咖啡", "icbc") == "麦当劳"

    # ── 连锁餐饮 ──

    def test_瑞幸(self):
        from ft.convert import _infer_platform
        assert _infer_platform("luckin coffee", "订单付款", "wechat") == "瑞幸咖啡"

    def test_麦当劳(self):
        from ft.convert import _infer_platform
        assert _infer_platform("麦当劳", "麦当劳", "wechat") == "麦当劳"

    def test_便利蜂(self):
        from ft.convert import _infer_platform
        assert _infer_platform("便利蜂", "便利蜂购物", "wechat") == "便利蜂"

    def test_肯德基(self):
        from ft.convert import _infer_platform
        assert _infer_platform("", "财付通-肯德基", "icbc") == "肯德基"

    def test_必胜客(self):
        from ft.convert import _infer_platform
        assert _infer_platform("", "美团支付-美团App必胜客", "icbc") == "必胜客"

    def test_星巴克(self):
        from ft.convert import _infer_platform
        assert _infer_platform("星巴克", "", "wechat") == "星巴克"

    def test_7_11(self):
        from ft.convert import _infer_platform
        assert _infer_platform("", "7-11(SEB)", "wechat") == "7-11"

    def test_喜家德(self):
        from ft.convert import _infer_platform
        assert _infer_platform("", "财付通-喜家德北京鼎成时代", "icbc") == "喜家德"

    def test_新又好(self):
        from ft.convert import _infer_platform
        assert _infer_platform("", "财付通-NewUhoo新又好", "icbc") == "新又好"

    def test_西部马华(self):
        from ft.convert import _infer_platform
        assert _infer_platform("", "财付通-西部马华", "icbc") == "西部马华"

    def test_立普世(self):
        from ft.convert import _infer_platform
        assert _infer_platform("", "财付通-立普世咖啡", "icbc") == "立普世"

    def test_奈雪(self):
        from ft.convert import _infer_platform
        assert _infer_platform("", "奈雪的茶", "wechat") == "奈雪"

    # ── 公司全名匹配（信用卡账单） ──

    def test_嘀嘀_公司全名(self):
        from ft.convert import _infer_platform
        assert _infer_platform("", "支付宝-北京嘀嘀无限科技发展有", "icbc") == "滴滴"

    def test_梦想蜂_便利蜂公司(self):
        from ft.convert import _infer_platform
        assert _infer_platform("", "财付通-北京梦想蜂连锁商业有限", "icbc") == "便利蜂"

    def test_高德信息技术(self):
        from ft.convert import _infer_platform
        assert _infer_platform("", "支付宝-高德信息技术有限公司", "icbc") == "高德"

    # ── 其他平台 ──

    def test_DeepSeek(self):
        from ft.convert import _infer_platform
        assert _infer_platform("杭州深度求索", "DeepSeek-API服务", "alipay") == "DeepSeek"

    def test_Steam(self):
        from ft.convert import _infer_platform
        assert _infer_platform("", "支付宝-Steam", "icbc") == "Steam"

    def test_B站(self):
        from ft.convert import _infer_platform
        assert _infer_platform("bilibili", "", "wechat") == "B站"

    def test_小红书(self):
        from ft.convert import _infer_platform
        assert _infer_platform("小红书", "", "wechat") == "小红书"

    def test_携程(self):
        from ft.convert import _infer_platform
        assert _infer_platform("", "程支付-上海携程国际旅行社", "icbc") == "携程"

    def test_猫眼(self):
        from ft.convert import _infer_platform
        assert _infer_platform("", "美团支付-猫眼", "icbc") == "猫眼"

    def test_中国电信(self):
        from ft.convert import _infer_platform
        assert _infer_platform("", "支付宝-中国电信股份有限公司", "icbc") == "中国电信"

    def test_网易云音乐(self):
        from ft.convert import _infer_platform
        assert _infer_platform("", "支付宝-杭州乐读科技有限公司", "icbc") == "网易云音乐"

    def test_首开易生活(self):
        from ft.convert import _infer_platform
        assert _infer_platform("", "财付通-首开易生活", "wechat") == "首开易生活"

    def test_哈啰(self):
        from ft.convert import _infer_platform
        assert _infer_platform("上海钧哈网络科技有限公司", "电动车租车", "alipay") == "哈啰"

    def test_微信红包(self):
        """微信红包/群收款/转账标为微信（微信自身功能）"""
        from ft.convert import _infer_platform
        assert _infer_platform("某人", "微信红包", "wechat") == "微信"

    def test_群收款(self):
        from ft.convert import _infer_platform
        assert _infer_platform("某人", "群收款", "wechat") == "微信"

    # ── 无匹配 → 空（不 fallback） ──

    def test_无匹配_支付宝账单(self):
        """支付宝账单中无名小店的消费 → 空"""
        from ft.convert import _infer_platform
        assert _infer_platform("商家A", "买书", "alipay") == ""

    def test_无匹配_微信账单(self):
        """微信账单中无名小店的消费 → 空"""
        from ft.convert import _infer_platform
        assert _infer_platform("商家A", "奶茶", "wechat") == ""

    def test_无匹配_信用卡账单(self):
        """信用卡账单中无名小店的消费 → 空"""
        from ft.convert import _infer_platform
        assert _infer_platform("", "消费", "icbc") == ""

    def test_无匹配_个人商家(self):
        """个人商家名（戴永鸿/度友科技等）不建规则 → 空"""
        from ft.convert import _infer_platform
        assert _infer_platform("", "支付宝-戴永鸿", "icbc") == ""
        assert _infer_platform("", "支付宝-度友科技有限公司", "icbc") == ""

    def test_无匹配_ApplePay(self):
        """Apple Pay 是支付源（source），不是消费平台 → 空"""
        from ft.convert import _infer_platform
        assert _infer_platform("", "Apple.com/bill MQZF72ZG2Fa0S", "icbc") == ""

    # ── Codex review 修复后新增 ──

    def test_7_ELEVEN_归7_11(self):
        """7-ELEVEN 是 7-11 的英文名，不应归便利蜂"""
        from ft.convert import _infer_platform
        assert _infer_platform("7-11(SEB)", "7-ELEVEn北京朝阳关庄路店", "wechat") == "7-11"
        assert _infer_platform("", "7-ELEVEN便利店", "wechat") == "7-11"

    def test_北京东子_不触发京东(self):
        """北京东子是餐馆名，含"京东"子串但不相关"""
        from ft.convert import _infer_platform
        assert _infer_platform("柳州螺蛳粉北京西单店", "北京东子柳州螺蛳粉", "wechat") == ""

    def test_淘宝闪购_品牌优先(self):
        """淘宝闪购不阻挡 description 中的具体品牌"""
        from ft.convert import _infer_platform
        assert _infer_platform("淘宝闪购", "LINLEE林里柠檬茶外卖订单", "alipay") == "LINLEE林里"
        assert _infer_platform("淘宝闪购", "肯德基宅急送望京外卖", "alipay") == "肯德基"
        assert _infer_platform("淘宝闪购", "食其家牛丼咖喱外卖", "alipay") == "食其家"
        assert _infer_platform("淘宝闪购", "袁记云饺小营店外卖", "alipay") == "袁记云饺"
        assert _infer_platform("淘宝闪购", "霸王茶姬鼎成中心店外卖", "alipay") == "霸王茶姬"

    def test_淘宝闪购_无品牌_兜底淘宝(self):
        """淘宝闪购+无品牌→淘宝（兜底匹配）"""
        from ft.convert import _infer_platform
        assert _infer_platform("淘宝闪购", "随便一个店", "alipay") == "淘宝"

    def test_美团App_不压品牌(self):
        """美团App消费，具体品牌应优先于美团"""
        from ft.convert import _infer_platform
        assert _infer_platform("美团", "食其家·牛丼咖喱-美团App", "wechat") == "食其家"
        assert _infer_platform("美团", "麦当劳-美团App", "wechat") == "麦当劳"
        assert _infer_platform("美团", "霸王茶姬鼎成中心店-美团App", "alipay") == "霸王茶姬"

    def test_美团App_无品牌_不标(self):
        """美团App+无品牌→空（中介不标）"""
        from ft.convert import _infer_platform
        assert _infer_platform("美团", "美团App鸟楽町热浪串烧酒场", "icbc") == ""
        assert _infer_platform("美团", "无名小店", "wechat") == ""
        assert _infer_platform("美团", "财付通-美团", "icbc") == ""

    def test_大众点评App_中介不标(self):
        """大众点评是O2O中介，不标，有具体品牌则标品牌"""
        from ft.convert import _infer_platform
        assert _infer_platform("", "东方唯尔-大众点评App", "alipay") == ""
        assert _infer_platform("", "麦当劳-大众点评App", "wechat") == "麦当劳"

    def test_美团收银_不标(self):
        """美团收银是商户POS，不是消费平台"""
        from ft.convert import _infer_platform
        assert _infer_platform("秦巴江湖麻辣烫", "美团收银909700211260140713", "alipay") == ""


# ── 支付源推断 ──────────────────────────────────────────

class TestInferPaymentSource:
    """_infer_payment_source 推断支付源"""

    def test_支付宝_source(self):
        from ft.convert import _infer_payment_source
        assert _infer_payment_source("alipay", "", "") == "支付宝"

    def test_微信_source(self):
        from ft.convert import _infer_payment_source
        assert _infer_payment_source("wechat", "", "") == "微信"

    def test_ICBC_美团支付(self):
        from ft.convert import _infer_payment_source
        assert _infer_payment_source("icbc", "", "美团支付-xxxx") == "美团支付"

    def test_ICBC_京东支付(self):
        from ft.convert import _infer_payment_source
        assert _infer_payment_source("icbc", "", "京东支付-xxxx") == "京东支付"

    def test_ICBC_财付通(self):
        from ft.convert import _infer_payment_source
        assert _infer_payment_source("icbc", "", "财付通-xxxx") == "微信支付"

    def test_ICBC_支付宝(self):
        from ft.convert import _infer_payment_source
        assert _infer_payment_source("icbc", "", "支付宝-xxxx") == "支付宝"

    def test_ICBC_ApplePay(self):
        from ft.convert import _infer_payment_source
        assert _infer_payment_source("icbc", "", "Apple Pay") == "Apple Pay"

    def test_ICBC_无匹配(self):
        from ft.convert import _infer_payment_source
        assert _infer_payment_source("icbc", "", "星巴克消费") == "银行卡"

    def test_ICBC_斜线支付方式(self):
        """支付方式为/时根据描述推断"""
        from ft.convert import _infer_payment_source
        assert _infer_payment_source("icbc", "/", "美团支付-外卖") == "美团支付"

    def test_icbc_debit_source_固定为银行卡(self):
        """工行储蓄卡的 source 固定为 银行卡，不受对方/描述影响"""
        from ft.convert import _infer_payment_source
        # 即使对方名含 支付宝
        assert _infer_payment_source("icbc_debit", "支付宝-某某", "支付宝转账") == "银行卡"
        assert _infer_payment_source("icbc_debit", "某某", "消费") == "银行卡"

    def test_ccb_debit_source_固定为建行储蓄卡(self):
        """建行储蓄卡的 source 固定为 建行储蓄卡，不受对方/描述影响"""
        from ft.convert import _infer_payment_source
        assert _infer_payment_source("ccb_debit", "支付宝-某某", "消费") == "建行储蓄卡"


class TestCcbRefundClassification:
    def test_ccb_refund_signal_single_candidate_can_be_strong(self):
        from ft.convert import _classify_refund_match
        strength = _classify_refund_match(
            ref={"_refund_signal": "ccb_debit_refund", "_ccb_refund_same_cluster": True, "occurred_at": "2026-03-15"},
            rule_hint="refund_cp_match",
            exact_amt=True,
            candidate_count=1,
            expense={"occurred_at": "2026-03-14"},
        )
        assert strength == "strong"

    def test_ccb_refund_signal_multi_candidate_same_cluster_stays_weak(self):
        from ft.convert import _classify_refund_match
        strength = _classify_refund_match(
            ref={"_refund_signal": "ccb_debit_refund", "_ccb_refund_same_cluster": True, "occurred_at": "2026-03-15"},
            rule_hint="refund_cp_match",
            exact_amt=True,
            candidate_count=2,
            expense={"occurred_at": "2026-03-14"},
        )
        assert strength == "weak"


# ── ICBC PDF 行解析 ──────────────────────────────────────

class TestIcbcParseLines:
    """_parse_icbc_lines — 时间/币种/交易对方/描述提取"""

    def test_时间从日期下行读取(self):
        """时间应从日期行的下一行 HH:MM:SS 读取，非硬编码 00:00:00"""
        lines = [
            "2026-01-15",
            "21:23:58",
            "",
            "-1,234.56",
            "",
            "消费",
            "",
            "对方户名",
            "测试用户",
            "对方账号",
            "摘要",
            "手机银行",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["date"] == "2026-01-15 21:23:58"

    def test_USD币种检测(self):
        """美元交易应识别为 USD（币种在金额行正上方）"""
        lines = [
            "2026-01-20",
            "14:30:15",
            "美元",
            "-108.91",
            "",
            "消费",
            "",
            "对方户名",
            "NVIDIA CORP",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["currency"] == "USD"

    def test_HKD币种检测(self):
        lines = [
            "2026-01-20",
            "14:30:15",
            "港币",
            "-500.00",
            "",
            "消费",
            "",
            "对方户名",
            "HK MERCHANT",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["currency"] == "HKD"

    def test_CNY默认币种(self):
        """人民币交易默认 CNY"""
        lines = [
            "2026-01-15",
            "21:23:58",
            "",
            "-1,234.56",
            "",
            "消费",
            "",
            "人民币",
            "",
            "对方户名",
            "商家A",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["currency"] == "CNY"

    def test_交易对方_描述分离(self):
        """手机银行转账：counterparty=对方户名, note=手机银行"""
        lines = [
            "2026-01-15",
            "21:23:58",
            "",
            "-1,234.56",
            "",
            "消费",
            "",
            "对方户名",
            "测试用户",
            "对方账号",
            "摘要",
            "手机银行",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["counterparty"] == "测试用户"
        assert records[0]["note"] == "手机银行"

    def test_工行信用卡_record_id_使用短hash(self):
        lines = [
            "2026-01-15",
            "21:23:58",
            "6222020200041200",
            "人民币",
            "借",
            "-1,234.56",
            "测试用户",
            "手机银行",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["_fact_id"] == "icbc_credit_eed5d67399a3"

    def test_美元转账_描述不被污染(self):
        """美元转账 via 支付宝-高德 → counterparty=测试用户, desc=手机银行"""
        lines = [
            "2026-01-15",
            "21:23:58",
            "",
            "-108.91",
            "",
            "消费",
            "",
            "美元",
            "",
            "对方户名",
            "测试用户",
            "对方账号",
            "1234****5678",
            "摘要",
            "手机银行",
            "交易币种",
            "美元",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["counterparty"] == "测试用户"
        assert records[0]["note"] == "手机银行"

    def test_平台从交易对方推断(self):
        """滴滴消费 → platform=滴滴"""
        lines = [
            "2026-01-15",
            "21:23:58",
            "",
            "-13.00",
            "",
            "消费",
            "",
            "对方户名",
            "支付宝-北京嘀嘀无限科技发展有",
            "摘要",
            "支付宝-北京嘀嘀无限科技发展有",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["counterparty"] == "滴滴"

    def test_多条记录_时间独立(self):
        """多条记录各自从对应日期行读取时间，不互相污染"""
        lines = [
            "2026-01-15",
            "21:23:58",
            "",
            "-100.00",
            "",
            "消费",
            "",
            "对方户名",
            "商家A",
            "",
            "2026-01-16",
            "10:05:30",
            "",
            "-200.00",
            "",
            "消费",
            "",
            "对方户名",
            "商家B",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 2
        assert records[0]["date"] == "2026-01-15 21:23:58"
        assert records[1]["date"] == "2026-01-16 10:05:30"

    def test_负金额_支出(self):
        lines = [
            "2026-01-15",
            "21:23:58",
            "",
            "-100.00",
            "",
            "消费",
            "",
            "对方户名",
            "商家A",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["category"] == "expense"
        assert records[0]["amount"] == -100.0

    def test_正金额_收入(self):
        lines = [
            "2026-01-15",
            "21:23:58",
            "",
            "+500.00",
            "",
            "消费",
            "",
            "对方户名",
            "商家B",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["category"] == "income"
        assert records[0]["amount"] == 500.0

    def test_正金额无加号_收入(self):
        """没有 +/- 前缀的正数（退款/退货）"""
        lines = [
            "2026-01-15",
            "21:23:58",
            "",
            "12.00",
            "",
            "消费",
            "",
            "对方户名",
            "商家C",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["category"] == "income"
        assert records[0]["amount"] == 12.0



    def test_借记卡_时间不归一为零(self):
        """借记卡分支应提取时间行，不应硬编码 00:00:00"""
        lines = [
            "2023-06-13",         # 日期行
            "17:25:13",           # 时间行
            "1614020101021984636",
            "活期", "00000", "人民币", "钞", "消费", "1614",
            "-17.00",             # 金额行
            "1,234.56",           # 余额行
            "深圳市财付通支付",    # 对方户名
            "1219****0038",
            "其他",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=False)
        assert len(records) == 1, f"应解析出1条记录，实际={len(records)}"
        assert records[0]["date"] == "2023-06-13 17:25:13", \
            f"date 应包含时间，实际={records[0]['date']!r}"
        assert records[0]["note"] != "17:25:13", \
            f"时间不应跑到 description，实际={records[0]['note']!r}"


# ── ICBC 边界修复 ──────────────────────────────────────

class TestIcbcEdgeCases:
    """Codex review 发现的边界问题"""

    def test_前向扫描跳过日期行(self):
        """金额行后的日期行不应成为 description"""
        lines = [
            "2026-01-09",
            "07:36:18",
            "",
            "-78.00",
            "",
            "消费",
            "",
            "对方户名",
            "Apple.com/bill MQZF72ZG2Fa0S",
            "对方账号",
            "摘要",
            "2026-01-09",  # 日期行不应变成 description
            "交易场所",
            "手机银行",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["note"] != "2026-01-09", \
            f"description 不应是日期，实际={records[0]['note']!r}"

    def test_前向扫描跳过_本页合计行(self):
        """'本页支出算术合计' 不应混入 description"""
        lines = [
            "2026-05-09",
            "11:51:52",
            "",
            "-20.00",
            "",
            "消费",
            "",
            "对方户名",
            "财付通-NewUhoo新又好",
            "摘要",
            "本页支出算术合计：711.73",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        desc = records[0]["note"]
        assert "本页支出" not in desc, f"description 不应含合计行: {desc!r}"

    def test_前向扫描跳过_下单时间(self):
        """'下单时间' 行是页脚元数据，不应进入 description"""
        lines = [
            "2026-06-09",
            "18:17:47",
            "",
            "-24.00",
            "",
            "消费",
            "",
            "对方户名",
            "财付通-NewUhoo新又好",
            "摘要",
            "本页支出算术合计：520.71",
            "",
            "下单时间：2026-06-09 18:57:11",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        desc = records[0]["note"]
        assert "本页支出" not in desc, f"description 不应含合计行: {desc!r}"
        assert "下单时间" not in desc, f"description 不应含页脚元数据: {desc!r}"

    def test_借字精确匹配_借记卡不触发负号(self):
        """'借记卡' 中的 '借' 不应导致金额取反（仅精确匹配）"""
        lines = [
            "2026-01-15",
            "21:23:58",
            "6225****1200",  # 借记卡号含 '借' 但行内容 != "借"
            "",
            "+100.00",
            "",
            "借    记",  # ctx 中有 '借' 但不是精确匹配
            "",
            "对方户名",
            "商家A",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        # +100.00 是收入，不应被取反
        assert records[0]["amount"] == 100.0, f"不应取反, amount={records[0]['amount']}"

    def test_单独借行_仍触发负号(self):
        """金额行之前的单独 '借' 行应触发金额取反"""
        lines = [
            "2026-01-15",
            "21:23:58",
            "622599000000001200",
            "借",    # 出现在金额行之前（PDF 实际格式）
            "人民币",
            "100.00",
            "",
            "消费",
            "",
            "对方户名",
            "商家A",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["amount"] == -100.0, f"应取反为负数, amount={records[0]['amount']}"

    def test_币种_前一行空白_向后扫描(self):
        """金额前一行空白时，应继续向前找到币种行"""
        lines = [
            "2026-01-20",
            "14:30:15",
            "",      # 空白
            "美元",  # 向前找币种
            "",      # 空白
            "-108.91",
            "",
            "消费",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["currency"] == "USD", \
            f"币种应为 USD, 实际={records[0]['currency']!r}"


    def test_信用卡转账摘要保留且不混入交易场所(self):
        lines = [
            "2026-05-14",
            "10:14:05",
            "6225990041051200",
            "贷",
            "人民币",
            "12302.04",
            "人民币",
            "12302.04",
            "黄文龙",
            "6212****3697",
            "转账",
            "手机银行",
        ]
        from ft.convert import _parse_icbc_lines

        records, _ = _parse_icbc_lines(lines, is_credit=True)

        assert len(records) == 1
        assert records[0]["summary"] == "转账"
        assert records[0]["note"] == "手机银行"
        assert records[0]["counterparty"] == "黄文龙"
        assert records[0]["amount"] == Decimal("12302.04")
    def test_借记卡回退解析跳过业务流水号并保留转账摘要(self):
        lines = [
            "2026-05-14",
            "10:14:05",
            "1614020101021984636",
            "活期",
            "00000",
            "人民币",
            "钞",
            "转账",
            "1614",
            "-12,302.04",
            "12,025.09",
            "黄文龙",
            "6225****1200",
            "手机银行",
        ]
        from ft.convert import _parse_icbc_lines

        records, _ = _parse_icbc_lines(lines, is_credit=False)

        assert len(records) == 1
        assert records[0]["summary"] == "转账"
        assert records[0]["note"] == "转账"
        assert records[0]["amount"] == Decimal("-12302.04")


class TestPlatformEdgeCases:
    """平台推断边界"""

    def test_711_合计数字不触发平台(self):
        """'711.73' 作为大段数字不应触发 7-11 平台"""
        from ft.convert import _infer_platform
        result = _infer_platform(
            "财付通-NewUhoo新又好",
            "本页支出算术合计：711.73",
            "icbc",
        )
        assert result != "7-11", f"数字 711.73 不应触发 7-11, 实际={result!r}"


class TestPaymentSourceEdgeCases:
    """支付源推断边界"""

    def test_Apple_bill_是ApplePay(self):
        """Apple.com/bill 视为 Apple Pay 渠道（设计决定）"""
        from ft.convert import _infer_payment_source
        result = _infer_payment_source(
            "icbc",
            "",
            "Apple.com/bill MQZF72ZG2Fa0S",
        )
        assert result == "Apple Pay", \
            f"Apple.com/bill 应为 Apple Pay, 实际={result!r}"


class TestStripPaymentPrefix:
    """_strip_payment_prefix — 从交易对方中去掉已知支付源前缀"""

    def test_去掉_美团支付前缀(self):
        from ft.convert import _strip_payment_prefix
        assert _strip_payment_prefix("美团支付-美团App霸王茶姬（鼎成中心店）") == "美团App霸王茶姬（鼎成中心店）"

    def test_去掉_支付宝前缀(self):
        from ft.convert import _strip_payment_prefix
        assert _strip_payment_prefix("支付宝-北京嘀嘀无限科技发展有限公司") == "北京嘀嘀无限科技发展有限公司"

    def test_去掉_财付通前缀(self):
        from ft.convert import _strip_payment_prefix
        assert _strip_payment_prefix("财付通-新渔阳滑雪场") == "新渔阳滑雪场"

    def test_去掉_京东支付前缀(self):
        from ft.convert import _strip_payment_prefix
        assert _strip_payment_prefix("京东支付-京东商城业务") == "京东商城业务"

    def test_去掉_程支付前缀(self):
        from ft.convert import _strip_payment_prefix
        assert _strip_payment_prefix("程支付-上海携程国际旅行社有限公司") == "上海携程国际旅行社有限公司"

    def test_去掉_网银在线前缀(self):
        from ft.convert import _strip_payment_prefix
        assert _strip_payment_prefix("网银在线-爽威京东自营旗舰店") == "爽威京东自营旗舰店"

    def test_去掉_拼多多支付前缀(self):
        from ft.convert import _strip_payment_prefix
        assert _strip_payment_prefix("拼多多支付-橙予进口专营店") == "橙予进口专营店"

    def test_去掉_抖音支付前缀(self):
        from ft.convert import _strip_payment_prefix
        assert _strip_payment_prefix("抖音支付-抖音团购") == "抖音团购"

    def test_Apple_com_bill_不加前缀(self):
        from ft.convert import _strip_payment_prefix
        assert _strip_payment_prefix("Apple.com/bill MQZF72ZG2Fa0S") == "Apple.com/bill MQZF72ZG2Fa0S"

    def test_无前缀_保持原样(self):
        from ft.convert import _strip_payment_prefix
        assert _strip_payment_prefix("测试用户") == "测试用户"
        assert _strip_payment_prefix("退货") == "退货"
        assert _strip_payment_prefix("转帐") == "转帐"
        assert _strip_payment_prefix("DEEPINFRA.COM") == "DEEPINFRA.COM"
        assert _strip_payment_prefix("") == ""

    def test_支付宝前缀只去掉一次(self):
        """'支付宝-支付宝-消费' → 去掉一次支付宝- → '支付宝-消费'"""
        from ft.convert import _strip_payment_prefix
        result = _strip_payment_prefix("支付宝-支付宝-消费")
        assert result == "支付宝-消费", f"实际={result!r}"


class TestIcbcRefundPairing:
    """ICBC 退货配对核销"""

    def test_全额退款_双向保留事实(self):
        """退货600→建立全额关系，但消费与退款事实都保留。"""
        lines = [
            "2026-01-02",
            "12:35:30",
            "622599000000001200",
            "借",
            "人民币",
            "600.00",
            "人民币",
            "600.00",
            "消费",
            "财付通-新渔阳滑雪场",
            "",
            "2026-01-02",
            "17:14:07",
            "379983032529166",
            "贷",
            "人民币",
            "600.00",
            "人民币",
            "600.00",
            "退货",
            "财付通-新渔阳滑雪场",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 2
        assert len(_pairs_only(tracking_pairs)) == 1
        assert {r["category"] for r in records} == {"expense", "income"}

    def test_部分退款_减少金额(self):
        """退货5.29→核销消费22，消费减为16.71"""
        lines = [
            "2026-01-21",
            "21:13:49",
            "622599000000001200",
            "借",
            "人民币",
            "22.00",
            "人民币",
            "22.00",
            "消费",
            "支付宝-高德信息技术有限公司",
            "",
            "2026-01-21",
            "21:13:49",
            "379983032529166",
            "贷",
            "人民币",
            "5.29",
            "人民币",
            "5.29",
            "退货",
            "支付宝-高德信息技术有限公司",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 2
        assert next(r for r in records if r["category"] == "expense")["amount"] == -22.0
        assert next(r for r in records if r["category"] == "income")["amount"] == Decimal("5.29")
        assert _pairs_only(tracking_pairs)[0]["match_type"] == "partial"

    def test_孤退货_保留收入(self):
        """无对应消费的退货 → 保留为 income"""
        lines = [
            "2026-01-02",
            "17:14:07",
            "379983032529166",
            "贷",
            "人民币",
            "600.00",
            "人民币",
            "600.00",
            "退货",
            "财付通-新渔阳滑雪场",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["category"] == "income"
        assert records[0]["amount"] == 600.0

    def test_退款摘要_也会按退款配对保留事实(self):
        """信用卡账单里出现“退款”摘要时，也应走退款配对，但保留原始事实。"""
        lines = [
            "2026-01-02",
            "12:35:30",
            "622599000000001200",
            "借",
            "人民币",
            "600.00",
            "人民币",
            "600.00",
            "消费",
            "财付通-新渔阳滑雪场",
            "",
            "2026-01-02",
            "17:14:07",
            "379983032529166",
            "贷",
            "人民币",
            "600.00",
            "人民币",
            "600.00",
            "退款",
            "财付通-新渔阳滑雪场",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 2
        assert len(_pairs_only(tracking_pairs)) == 1
        assert _pairs_only(tracking_pairs)[0]["refund"]["_refund_signal"] == "icbc_credit_return"
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "strong"

    def test_刷卡金退款摘要_保留原始事实并标记冲减类型(self):
        """刷卡金退款样式应保留原始事实，只附加 offset 元信息。"""
        lines = [
            "2024-11-23",
            "14:40:04",
            "622599000000001200",
            "借",
            "人民币",
            "3.88",
            "人民币",
            "3.88",
            "刷卡金退款-美好星期五3.88元刷卡金",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(_pairs_only(tracking_pairs)) == 0
        assert len(records) == 1
        assert records[0]["category"] == "expense"
        assert records[0]["amount"] == Decimal("-3.88")
        assert records[0]["offset_type"] == "benefit_rebate"
        assert records[0]["offset_action"] == "keep_as_offset_income"
        assert records[0]["counterparty"] == "刷卡金退款-美好星期五3.88元刷卡金"

    def test_刷卡金入账_识别为消费冲减收入(self):
        lines = [
            "2024-11-21",
            "13:18:24",
            "622599000000001200",
            "贷",
            "人民币",
            "0.66",
            "人民币",
            "0.66",
            "转帐",
            "刷卡金入账-任务中心11月连续签",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(_pairs_only(tracking_pairs)) == 0
        assert len(records) == 1
        assert records[0]["category"] == "income"
        assert records[0]["amount"] == Decimal("0.66")
        assert records[0]["offset_type"] == "benefit_rebate"
        assert records[0]["offset_strength"] == "strong"
        assert records[0]["offset_action"] == "keep_as_offset_income"

    def test_返现_识别为活动返现冲减(self):
        lines = [
            "2025-11-13",
            "18:32:19",
            "379983032529166",
            "贷",
            "港币",
            "24.00",
            "港币",
            "24.00",
            "HKMetroRebate",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(_pairs_only(tracking_pairs)) == 0
        assert len(records) == 1
        assert records[0]["category"] == "income"
        assert records[0]["currency"] == "HKD"
        assert records[0]["offset_type"] == "campaign_cashback"
        assert records[0]["offset_strength"] == "strong"
        assert records[0]["offset_action"] == "keep_as_offset_income"

    def test_减免年费_识别为费用返还冲减(self):
        lines = [
            "2025-11-18",
            "03:04:28",
            "379983032529166",
            "贷",
            "人民币",
            "0.00",
            "人民币",
            "0.00",
            "退货",
            "减免年费1000.00元",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(_pairs_only(tracking_pairs)) == 0
        assert len(records) == 1
        assert records[0]["category"] == "income"
        assert records[0]["offset_type"] == "fee_reversal"
        assert records[0]["offset_strength"] == "strong"
        assert records[0]["offset_action"] == "keep_as_offset_income"

    def test_铁路退款_同类多候选按最近归并直过(self):
        lines = [
            "2026-01-01",
            "08:00:00",
            "622599000000001200",
            "借",
            "人民币",
            "100.00",
            "人民币",
            "100.00",
            "消费",
            "中国铁路网络有限公司",
            "",
            "2026-01-01",
            "09:00:00",
            "622599000000001200",
            "借",
            "人民币",
            "120.00",
            "人民币",
            "120.00",
            "消费",
            "中国铁路网络有限公司",
            "",
            "2026-01-01",
            "10:00:00",
            "379983032529166",
            "贷",
            "人民币",
            "100.00",
            "人民币",
            "100.00",
            "退货",
            "支付宝-中国铁路网络有限公司",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(_pairs_only(tracking_pairs)) == 1
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "strong"
        assert _pairs_only(tracking_pairs)[0]["candidate_count"] == 2
        assert _pairs_only(tracking_pairs)[0]["expense"]["date"] == "2026-01-01 09:00:00"

    def test_京东退款_同类多候选按最近且可覆盖金额归并直过(self):
        lines = [
            "2026-01-02",
            "09:00:00",
            "622599000000001200",
            "借",
            "人民币",
            "60.00",
            "人民币",
            "60.00",
            "消费",
            "京东支付-京东商城业务",
            "",
            "2026-01-02",
            "09:30:00",
            "622599000000001200",
            "借",
            "人民币",
            "80.00",
            "人民币",
            "80.00",
            "消费",
            "京东支付-京东商城业务",
            "",
            "2026-01-02",
            "10:00:00",
            "379983032529166",
            "贷",
            "人民币",
            "50.00",
            "人民币",
            "50.00",
            "退货",
            "京东支付-京东商城业务",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(_pairs_only(tracking_pairs)) == 1
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "strong"
        assert _pairs_only(tracking_pairs)[0]["candidate_count"] == 2
        assert _pairs_only(tracking_pairs)[0]["expense"]["amount"] == -80.0

    def test_脏商户退货_保持弱置信待审(self):
        lines = [
            "2026-01-02",
            "12:35:30",
            "622599000000001200",
            "借",
            "人民币",
            "600.00",
            "人民币",
            "600.00",
            "消费",
            "中国铁路网络有限公司",
            "",
            "2026-01-02",
            "17:14:07",
            "379983032529166",
            "贷",
            "人民币",
            "600.00",
            "人民币",
            "600.00",
            "退货",
            "中国",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(_pairs_only(tracking_pairs)) == 1
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "weak"

    def test_跨消费类型候选_保持弱置信待审(self):
        lines = [
            "2026-01-03",
            "09:00:00",
            "622599000000001200",
            "借",
            "人民币",
            "100.00",
            "人民币",
            "100.00",
            "消费",
            "京东支付-京东商城业务",
            "中国铁路网络有限公司",
            "2026-01-03",
            "09:10:00",
            "622599000000001200",
            "借",
            "人民币",
            "100.00",
            "人民币",
            "100.00",
            "消费",
            "京东支付-京东商城业务",
            "",
            "2026-01-03",
            "10:00:00",
            "379983032529166",
            "贷",
            "人民币",
            "100.00",
            "人民币",
            "100.00",
            "退货",
            "京东支付-京东商城业务",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(_pairs_only(tracking_pairs)) == 1
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "weak"

    def test_退款金额冲超候选_保持弱置信待审(self):
        lines = [
            "2026-01-04",
            "09:00:00",
            "622599000000001200",
            "借",
            "人民币",
            "20.00",
            "人民币",
            "20.00",
            "消费",
            "京东支付-京东商城业务",
            "",
            "2026-01-04",
            "10:00:00",
            "379983032529166",
            "贷",
            "人民币",
            "50.00",
            "人民币",
            "50.00",
            "退货",
            "京东支付-京东商城业务",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(_pairs_only(tracking_pairs)) == 0
        incomes = [r for r in records if r["category"] == "income"]
        assert len(incomes) == 1

    def test_原始商户唯一退货_保留事实并输出强关系(self):
        lines = [
            "2026-01-02",
            "12:35:30",
            "622599000000001200",
            "借",
            "人民币",
            "600.00",
            "人民币",
            "600.00",
            "消费",
            "财付通-新渔阳滑雪场",
            "",
            "2026-01-02",
            "17:14:07",
            "379983032529166",
            "贷",
            "人民币",
            "600.00",
            "人民币",
            "600.00",
            "退货",
            "财付通-新渔阳滑雪场",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 2
        assert len(_pairs_only(tracking_pairs)) == 1
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "strong"
        assert {r["category"] for r in records} == {"expense", "income"}

    def test_自助侠重复退款_同类近邻可自动核销(self):
        lines = [
            "2026-01-05",
            "08:00:00",
            "622599000000001200",
            "借",
            "人民币",
            "2.00",
            "人民币",
            "2.00",
            "消费",
            "财付通-自助侠",
            "",
            "2026-01-05",
            "08:10:00",
            "622599000000001200",
            "借",
            "人民币",
            "2.00",
            "人民币",
            "2.00",
            "消费",
            "财付通-自助侠",
            "",
            "2026-01-05",
            "09:00:00",
            "379983032529166",
            "贷",
            "人民币",
            "0.70",
            "人民币",
            "0.70",
            "退货",
            "财付通-自助侠",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(_pairs_only(tracking_pairs)) == 1
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "strong"
        assert _pairs_only(tracking_pairs)[0]["candidate_count"] == 2

    def test_美团平台退款_同类多候选可自动核销(self):
        lines = [
            "2026-01-06",
            "12:00:00",
            "622599000000001200",
            "借",
            "人民币",
            "20.00",
            "人民币",
            "20.00",
            "消费",
            "美团支付-北京象鲜科技有限公司",
            "",
            "2026-01-06",
            "12:05:00",
            "622599000000001200",
            "借",
            "人民币",
            "40.00",
            "人民币",
            "40.00",
            "消费",
            "美团支付-北京象鲜科技有限公司",
            "",
            "2026-01-06",
            "12:20:00",
            "379983032529166",
            "贷",
            "人民币",
            "5.00",
            "人民币",
            "5.00",
            "退货",
            "美团支付-北京象鲜科技有限公司",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(_pairs_only(tracking_pairs)) == 1
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "strong"

    def test_刷卡金退款_不进入原消费核销(self):
        lines = [
            "2024-11-23",
            "12:00:00",
            "622599000000001200",
            "借",
            "人民币",
            "3.88",
            "人民币",
            "3.88",
            "消费",
            "拼多多支付-拼多多平台商户",
            "",
            "2024-11-23",
            "14:40:04",
            "622599000000001200",
            "借",
            "人民币",
            "3.88",
            "人民币",
            "3.88",
            "刷卡金退款-美好星期五3.88元刷卡金",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(_pairs_only(tracking_pairs)) == 0
        assert len(records) == 2
        benefit = next(r for r in records if r.get("offset_type") == "benefit_rebate")
        assert benefit["category"] == "expense"
        assert benefit["amount"] == Decimal("-3.88")
        assert benefit["offset_action"] == "keep_as_offset_income"

    def test_混合场景_退款核销与刷卡金并存(self):
        lines = [
            "2026-01-02",
            "12:35:30",
            "622599000000001200",
            "借",
            "人民币",
            "600.00",
            "人民币",
            "600.00",
            "消费",
            "财付通-新渔阳滑雪场",
            "",
            "2026-01-02",
            "17:14:07",
            "379983032529166",
            "贷",
            "人民币",
            "600.00",
            "人民币",
            "600.00",
            "退货",
            "财付通-新渔阳滑雪场",
            "2024-11-21",
            "13:18:24",
            "622599000000001200",
            "贷",
            "人民币",
            "0.66",
            "人民币",
            "0.66",
            "转帐",
            "刷卡金入账-任务中心11月连续签",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(_pairs_only(tracking_pairs)) == 1
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "strong"
        assert len(records) == 3
        benefit = next(r for r in records if r.get("offset_type") == "benefit_rebate")
        assert benefit["offset_action"] == "keep_as_offset_income"
        assert {r["category"] for r in records} == {"expense", "income"}

    def test_退货包裹减免年费_优先识别为费用返还(self):
        lines = [
            "2025-11-18",
            "03:04:28",
            "379983032529166",
            "贷",
            "人民币",
            "0.00",
            "人民币",
            "0.00",
            "退货",
            "减免年费100.00元",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(_pairs_only(tracking_pairs)) == 0
        assert len(records) == 1
        assert records[0]["offset_type"] == "fee_reversal"
        assert records[0]["offset_strength"] == "strong"

    def test_退货包裹返现_优先识别为活动返现(self):
        lines = [
            "2025-11-13",
            "18:32:19",
            "379983032529166",
            "贷",
            "人民币",
            "24.00",
            "人民币",
            "24.00",
            "退货",
            "美国运通人民币卡日本便利店返现",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(_pairs_only(tracking_pairs)) == 0
        assert len(records) == 1
        assert records[0]["offset_type"] == "campaign_cashback"
        assert records[0]["offset_strength"] == "strong"

    def test_icbc_币种_USD(self):
        """DEEPINFRA.COM美元交易→币种应为USD"""
        lines = [
            "2026-03-17",
            "18:32:25",
            "379983032529166",
            "借",
            "美元",
            "5.00",
            "美元",
            "5.00",
            "消费",
            "DEEPINFRA.COM",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["currency"] == "USD", f"currency={records[0]['currency']}"
        assert records[0]["amount"] == -5.0
        assert records[0]["counterparty"] == "DEEPINFRA.COM"

    def test_icbc_币种_JPY(self):
        """日元OCR交易→币种应为JPY"""
        lines = [
            "2026-03-22",
            "18:32:30",
            "379983032529166",
            "贷",
            "日元",
            "3000.00",
            "日元",
            "3000.00",
            "消费",
            "OCULUS *PKLN3JVQK2",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["currency"] == "JPY", f"currency={records[0]['currency']}"
        assert records[0]["amount"] == 3000.0

    def test_icbc_币种_JPY_手机银行(self):
        """日元入账（手机银行摘要）→币种应为JPY"""
        lines = [
            "2026-04-17",
            "12:40:07",
            "379983032529166",
            "贷",
            "日元",
            "3000.00",
            "日元",
            "3000.00",
            "手机银行",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["currency"] == "JPY", f"currency={records[0]['currency']}"
        assert records[0]["amount"] == 3000.0


class TestCardNumber:
    """卡号提取 + 路由"""

    def test_icbc_卡号从PDF提取(self):
        """信用卡PDF中的卡号应被提取为末尾4位"""
        lines = [
            "2026-03-01",
            "17:39:57",
            "622599000000001200",
            "借",
            "人民币",
            "15.80",
            "人民币",
            "15.80",
            "消费",
            "支付宝-高德信息技术有限公司",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["card_number"] == "1200", f"card={records[0]['card_number']!r}"

    def test_icbc_卡号_第二张卡(self):
        """不同卡号应正确提取"""
        lines = [
            "2026-03-17",
            "18:32:25",
            "622599000000000851",
            "借",
            "美元",
            "5.00",
            "美元",
            "5.00",
            "消费",
            "DEEPINFRA.COM",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["card_number"] == "0851", f"card={records[0]['card_number']!r}"

    def test_icbc_信用卡保留完整内部来源身份并以尾号兼容展示(self):
        lines = [
            "2026-03-17", "18:32:25", "622599000000000851", "借",
            "人民币", "5.00", "人民币", "5.00", "消费", "测试商户",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["_source_account_identifier"] == "622599000000000851"
        assert records[0]["file_account_key"] == "622599000000000851"
        assert records[0]["source_display_name"] == "工商银行信用卡"
        assert records[0]["card_number"] == "0851"

    def test_icbc_信用卡接受账单声明的掩码卡号作为来源身份(self):
        lines = [
            "2026-03-17", "18:32:25", "6225****9166", "借",
            "人民币", "5.00", "人民币", "5.00", "消费", "测试商户",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["_source_account_identifier"] == "6225****9166"
        assert records[0]["card_number"] == "9166"

    def test_icbc_信用卡业务行标识包含完整卡号避免跨卡碰撞(self):
        base = [
            "2026-03-17", "18:32:25", None, "借", "人民币", "5.00",
            "人民币", "5.00", "消费", "测试商户",
        ]
        from ft.convert import _parse_icbc_lines
        first, _ = _parse_icbc_lines(
            [*base[:2], "622599000000001200", *base[3:]], is_credit=True,
        )
        second, _ = _parse_icbc_lines(
            [*base[:2], "622599000000000851", *base[3:]], is_credit=True,
        )
        assert first[0]["_fact_id"] != second[0]["_fact_id"]

    def test_icbc_卡号_无卡号行(self):
        """没有卡号行的旧PDF格式→card_number为空"""
        lines = [
            "2026-01-15",
            "21:23:58",
            "",
            "-1,234.56",
            "",
            "消费",
            "测试用户",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0].get("card_number", "") == ""

    def test_icbc_卡号_通过核销保留(self):
        """全额退款关系识别后，消费与退款事实都保留 card_number。"""
        lines = [
            "2026-01-02",
            "12:35:30",
            "622599000000001200",
            "借",
            "人民币",
            "600.00",
            "人民币",
            "600.00",
            "消费",
            "财付通-新渔阳滑雪场",
            "",
            "2026-01-02",
            "17:14:07",
            "622599000000001200",
            "贷",
            "人民币",
            "600.00",
            "人民币",
            "600.00",
            "退货",
            "财付通-新渔阳滑雪场",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 2
        assert len(_pairs_only(tracking_pairs)) == 1
        assert all(r.get("card_number") == "1200" for r in records)

    def test_icbc_卡号_部分退款保留(self):
        """部分退款关系识别后，消费与退款事实都保留 card_number。"""
        lines = [
            "2026-01-21",
            "21:13:49",
            "622599000000001200",
            "借",
            "人民币",
            "22.00",
            "人民币",
            "22.00",
            "消费",
            "支付宝-测试商家",
            "",
            "2026-01-25",
            "10:00:00",
            "622599000000001200",
            "贷",
            "人民币",
            "5.29",
            "人民币",
            "5.29",
            "退货",
            "支付宝-测试商家",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 2
        assert len(_pairs_only(tracking_pairs)) == 1
        assert all(r["card_number"] == "1200" for r in records)

class TestTDDRegressions:
    """TDD 回归 — 先 RED 后 GREEN"""

    def test_icbc_卡号_不泄漏到下一笔(self):
        """第一笔有卡号，第二笔无卡号行 => 第二笔卡号应为空"""
        lines = [
            "2026-03-01",
            "17:39:57",
            "622599000000001200",
            "借",
            "人民币",
            "15.80",
            "人民币",
            "15.80",
            "消费",
            "测试商家A",
            "",
            "2026-03-02",
            "10:00:00",
            "",
            "借",
            "人民币",
            "30.00",
            "人民币",
            "30.00",
            "消费",
            "测试商家B",
        ]
        from ft.convert import _parse_icbc_lines
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 2
        assert records[0]["card_number"] == "1200", f"first={records[0]['card_number']!r}"
        assert records[1].get("card_number", "") == "", f"second={records[1]['card_number']!r}"

    def test_京东不再挡品牌(self):
        """description 含品牌名时品牌优先级高于京东"""
        from ft.convert import _infer_platform
        assert _infer_platform("京东", "霸王茶姬", "wechat") == "霸王茶姬"
        assert _infer_platform("京东支付-京东商城", "霸王茶姬", "icbc") == "霸王茶姬"
        assert _infer_platform("京东", "京东超市", "wechat") == "京东"


# ─── 储蓄卡（借记账户）解析 ──────────────────────────────────────────────

class TestIcbcDebit:
    """工行储蓄卡PDF解析 — TDD RED→GREEN"""

    def test_解析一行_基本字段(self):
        """储蓄卡PDF一行数据应正确解析出日期/金额/币种/摘要/对方/渠道"""
        row = [
            "2026-01-05\n20:32:09",   # 0 交易日期
            "1614020101021984636",     # 1 账号
            "活期",                    # 2 储种
            "00000",                   # 3 序号
            "人民币",                  # 4 币种
            "钞",                      # 5 钞汇
            "支付宝转账",              # 6 摘要
            "1614",                    # 7 地区
            "+3,500.00",              # 8 收入/支出金额
            "17,851.26",              # 9 余额
            "金哲玄",                  # 10 对方户名
            "2088****0156",           # 11 对方账号
            "快捷支付",                # 12 渠道
        ]
        from ft.convert import _parse_icbc_debit_row
        rec = _parse_icbc_debit_row(row)
        assert rec is not None
        assert rec["date"] == "2026-01-05 20:32:09"
        assert rec["amount"] == 3500.0
        assert rec["currency"] == "CNY"
        assert rec["counterparty"] == "金哲玄"
        assert rec["note"] == "支付宝转账"
        assert rec["category"] == "income"
        assert rec["payment_method"] == "快捷支付"

    def test_解析一行_支出(self):
        """支出方向应为 expense"""
        row = [
            "2026-01-10\n10:00:17", "1614020101021984636", "活期", "00000",
            "人民币", "钞", "无卡支付", "4600", "-2,000.00",
            "15,851.26", "梁碧玲", "6217****8572", "网上银行",
        ]
        from ft.convert import _parse_icbc_debit_row
        rec = _parse_icbc_debit_row(row)
        assert rec is not None
        assert rec["amount"] == -2000.0
        assert rec["category"] == "expense"
        assert rec["counterparty"] == "梁碧玲"
        assert rec["note"] == "无卡支付"
        assert rec["currency"] == "CNY"

    def test_工行借记卡_pdf_record_id_使用短hash(self):
        row = [
            "2026-01-10\n10:00:17", "1614020101021984636", "活期", "00000",
            "人民币", "钞", "无卡支付", "4600", "-2,000.00",
            "15,851.26", "梁碧玲", "6217****8572", "网上银行",
        ]
        from ft.convert import _parse_icbc_debit_row
        rec = _parse_icbc_debit_row(row)
        assert rec is not None
        assert rec["_fact_id"].startswith("icbc_debit_")
        assert rec["_fact_id"] == "icbc_debit_c9f2acd76932"

    def test_工行借记卡_record_id包含本方账号和余额证据(self):
        row = [
            "2026-01-10\n10:00:17", "1614020101021984636", "活期", "00000",
            "人民币", "钞", "无卡支付", "4600", "-2,000.00",
            "15,851.26", "梁碧玲", "6217****8572", "网上银行",
        ]
        from ft.convert import _parse_icbc_debit_row

        account_a = _parse_icbc_debit_row(row, source_payload={"余额": "15,851.26"})
        account_b = _parse_icbc_debit_row(
            [row[0], "1614020101021984637", *row[2:]],
            source_payload={"余额": "15,851.26"},
        )
        balance_b = _parse_icbc_debit_row(row, source_payload={"余额": "13,851.26"})

        assert account_a["_source_account_identifier"] == "1614020101021984636"
        assert account_a["_fact_id"] != account_b["_fact_id"]
        assert account_a["_fact_id"] != balance_b["_fact_id"]

    def test_解析一行_美元(self):
        """美元交易正确识别"""
        row = [
            "2026-01-23\n19:36:54", "1614020101021984636", "活期", "00000",
            "美元", "汇", "个人购汇", "1614", "+2,000.00",
            "4,000.00", "测试用户", "6212****0000", "手机银行",
        ]
        from ft.convert import _parse_icbc_debit_row
        rec = _parse_icbc_debit_row(row)
        assert rec is not None
        assert rec["amount"] == 2000.0
        assert rec["currency"] == "USD"
        assert rec["note"] == "个人购汇"

    def test_摘要水印噪声_支付宝转账(self):
        """摘要含残余水印文字时匹配已知关键词（金哲玄支付 宝转账→支付宝转账）"""
        row = [
            "2026-01-05\n20:32:09", "1614020101021984636", "活期", "00000",
            "人民币", "钞", "金哲玄支付\n宝转账", "1614", "+3,500.00",
            "17,851.26", "金哲玄", "2088****0156", "快捷支付",
        ]
        from ft.convert import _parse_icbc_debit_row
        rec = _parse_icbc_debit_row(row)
        assert rec is not None
        assert rec["note"] == "支付宝转账", f"desc={rec['note']!r}"

    def test_工行借记卡_退款标记为_refund_candidate(self):
        row = [
            "2026-01-05\n20:32:09", "1614020101021984636", "活期", "00000",
            "人民币", "钞", "退款", "1614", "+19.90",
            "405.84", "支付宝（中国）网络技术有限公司", "2155****0690", "快捷支付",
        ]
        from ft.convert import _parse_icbc_debit_row
        rec = _parse_icbc_debit_row(row)
        assert rec is not None
        assert rec["category"] == "income"
        assert rec["_debit_offset_type"] == "refund"
        assert rec["_is_refund"] is True

    def test_工行借记卡_退货标记为_refund_candidate(self):
        row = [
            "2026-01-05\n20:32:09", "1614020101021984636", "活期", "00000",
            "人民币", "钞", "退货", "0200", "+100.00",
            "1076.16", "中国银联无卡快捷支付业务专户", "3602****5565", "网上银行",
        ]
        from ft.convert import _parse_icbc_debit_row
        rec = _parse_icbc_debit_row(row)
        assert rec is not None
        assert rec["category"] == "income"
        assert rec["_debit_offset_type"] == "refund"
        assert rec["_is_refund"] is True

    def test_工行借记卡_撤销交易标记为_reversal_candidate(self):
        row = [
            "2026-01-05\n20:32:09", "1614020101021984636", "活期", "00000",
            "人民币", "钞", "撤销交易", "1614", "+761.08",
            "33628.24", "黄文龙", "3799****9166", "手机银行",
        ]
        from ft.convert import _parse_icbc_debit_row
        rec = _parse_icbc_debit_row(row)
        assert rec is not None
        assert rec["category"] == "income"
        assert rec["_debit_offset_type"] == "reversal"
        assert rec["_is_reversal"] is True

    def test_工行借记卡_利息不进入退款链路(self):
        row = [
            "2026-01-05\n20:32:09", "1614020101021984636", "活期", "00000",
            "人民币", "钞", "利息", "1614", "+0.25",
            "998.87", "（空）", "（空）", "批量业务",
        ]
        from ft.convert import _parse_icbc_debit_row
        rec = _parse_icbc_debit_row(row)
        assert rec is not None
        assert rec["_debit_offset_type"] == ""
        assert rec["_is_refund"] is False
        assert rec["_is_reversal"] is False

    def test_工行借记卡_基金赎回不进入退款链路(self):
        row = [
            "2026-01-05\n20:32:09", "1614020101021984636", "活期", "00000",
            "人民币", "钞", "基金赎回", "1614", "+10000.00",
            "98270.93", "中国工商银行股份有限公司基金快速赎回", "0200****6428", "业务资金清算专户",
        ]
        from ft.convert import _parse_icbc_debit_row
        rec = _parse_icbc_debit_row(row)
        assert rec is not None
        assert rec["_debit_offset_type"] == ""
        assert rec["_is_refund"] is False
        assert rec["_is_reversal"] is False

    def test_无日期行_抛错(self):
        """日期缺失的行应抛 ValueError（格式变更即中断，不静默丢弃）"""
        row = [None, None, None, None, None, None, None, None, None, None, None, None, None]
        from ft.convert import _parse_icbc_debit_row
        with pytest.raises(ValueError, match="无法提取日期"):
            _parse_icbc_debit_row(row)

    def test_短行_抛错(self):
        """不足13列的行应抛 ValueError（疑似 pdfplumber 截断/格式变更）"""
        short_row = ["2026-01-05\n20:32:09", "1614020101021984636"]  # 只有2列
        from ft.convert import _parse_icbc_debit_row
        with pytest.raises(ValueError):
            _parse_icbc_debit_row(short_row)

    def test_基金赎回_counterparty不乱码(self):
        """基金赎回的 counterparty 不应包含摘要乱码，应清洗为基金清算专户"""
        from ft.convert import _parse_icbc_debit_row
        row = [
            "2026-03-26\n12:00:00",  # 0 交易日期
            "1614020101021984636",   # 1 账号
            "活期",                  # 2 储种
            "00000",                 # 3 序号
            "人民币",                # 4 币种
            "钞",                    # 5 钞汇
            "基金赎回",              # 6 摘要
            "1614",                  # 7 地区
            "+1,000.00",            # 8 收入/支出金额
            "19,000.00",            # 9 余额
            "中国工商银行业股务份有资金限清公算司专基户金快速赎回",  # 10 对方户名(乱码)
            "2088****0156",         # 11 对方账号
            "手机银行",              # 12 渠道
        ]
        rec = _parse_icbc_debit_row(row)
        assert rec is not None
        assert rec["counterparty"] == "中国工商银行股份有限公司基金清算专户", \
            f"counterparty={rec['counterparty']!r}"
        assert "基金" not in rec["counterparty"] or rec["counterparty"] == "中国工商银行股份有限公司基金清算专户"

    def test_基金购买_counterparty也不乱码(self):
        """基金购买的 counterparty 也应清洗为基金清算专户"""
        from ft.convert import _parse_icbc_debit_row
        row = [
            "2026-04-15\n09:30:00", "1614020101021984636", "活期", "00000",
            "人民币", "钞", "基金购买", "1614", "-5,000.00",
            "14,000.00",
            "中国工商银基行金股购份买有限公清司算专户",  # 乱码
            "2088****0156", "手机银行",
        ]
        rec = _parse_icbc_debit_row(row)
        assert rec is not None
        assert rec["counterparty"] == "中国工商银行股份有限公司基金清算专户", \
            f"counterparty={rec['counterparty']!r}"

    @pytest.mark.skip(reason="依赖真实PDF文件，仅本地运行")
    def test_真实PDF_转换(self):
        """用真实的储蓄卡PDF运行完整转换"""
        import os
        path = "/path/to/icbc_debit_statement.pdf"
        if not os.path.exists(path):
            pytest.skip("PDF文件不存在")
        password = "your_password"
        from ft.convert import _read_icbc_debit_raw
        records, bill_type, tracking_pairs = _read_icbc_debit_raw(path, password)
        assert bill_type == "icbc_debit"
        assert len(records) >= 58, f"got {len(records)} records"
        # 检查关键字段
        recs_by_date = {r["date"]: r for r in records}
        key = "2026-01-12 14:31:25"
        assert key in recs_by_date, f"missing {key}, have: {sorted(recs_by_date.keys())[:5]}"
        r = recs_by_date[key]
        assert r["amount"] == 22508.75, f"amount={r['amount']}"
        assert r["currency"] == "CNY"
        assert "北京屏芯" in r["counterparty"], f"cpy={r['counterparty']}"
        # 撤销交易：已通过 _pair_reversals 配对核销，不在 records 中，而在 tracking_pairs 中
        rev_key = "2026-01-16 21:25:22"
        rev_recs = [r for r in records if r["date"] == rev_key]
        assert len(rev_recs) == 0, f"撤销交易应已配对移除，但 records 中仍存在: {len(rev_recs)} 条"
        # 验证撤销配对在 tracking_pairs 中
        rev_pairs = [p for p in tracking_pairs
                     if p["expense"]["date"] == rev_key or p["refund"]["date"] == rev_key]
        assert len(rev_pairs) >= 1, f"撤销交易应在 tracking_pairs 中，got {len(rev_pairs)}"
        # 美元交易
        usd_recs = [r for r in records if r["currency"] == "USD"]
        assert len(usd_recs) >= 6, f"only {len(usd_recs)} USD records"


# ─── 退款追踪行构建 ──────────────────────────────────────────────

# ── ICBC 退款平台修正 ──────────────────────────────────────

class TestIcbcRefundPlatform:
    """ICBC 退货的 platform 在 counterparty 更新后应重新计算"""

    def test_退款行platform跟随counterparty更新(self):
        """_parse_icbc_lines 将退货的 counterparty 归一化为品牌名（如「拼多多」）"""
        from ft.convert import _parse_icbc_lines

        lines = [
            "2026-01-17",
            "14:29:40",
            "622599000000001200",
            "借",
            "人民币",
            "60.90",
            "人民币",
            "60.90",
            "消费",
            "拼多多支付-橙予进口专营店",
            "",
            "2026-01-18",
            "14:29:48",
            "379983032529166",
            "贷",
            "人民币",
            "60.90",
            "人民币",
            "60.90",
            "退货",
            "拼多多支付-橙予进口专营店",
        ]
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(_pairs_only(tracking_pairs)) == 1, f"expected 1 tracking pair, got {len(tracking_pairs)}"
        pair = _pairs_only(tracking_pairs)[0]
        # refund 的 counterparty 应归一化为「拼多多」
        ref_cp = pair["refund"]["counterparty"]
        assert ref_cp == "拼多多", \
            f"退款tracking pair中counterparty应为拼多多，got: {ref_cp!r}"
        # convert 保留消费与退款原始事实
        assert len(records) == 2


class TestIcbcDebitReversal:
    def test_购汇还款撤销配对(self):
        """购汇还款 + 撤销交易 → 全抵消"""
        from ft.convert import _pair_reversals

        records = [
            {"occurred_at": "2026-01-16 21:25:22", "amount": -761.08, "currency": "CNY",
             "counterparty": "测试用户", "note": "购汇还款", "category": "expense",
             "payment_method": "手机银行", "platform": ""},
            {"occurred_at": "2026-01-16 21:25:22", "amount": 761.08, "currency": "CNY",
             "counterparty": "测试用户", "note": "撤销交易", "category": "income",
             "payment_method": "手机银行", "platform": ""},
        ]
        result, pairs = _pair_reversals(records)
        assert len(result) == 2
        assert len(pairs) == 1
        assert pairs[0]["match_type"] == "full"

    def test_不同对方不配对(self):
        """不同对方的撤销不配对"""
        from ft.convert import _pair_reversals

        records = [
            {"occurred_at": "2026-01-16 21:25:22", "amount": -761.08, "currency": "CNY",
             "counterparty": "测试用户", "note": "购汇还款", "category": "expense"},
            {"occurred_at": "2026-01-16 21:25:22", "amount": 761.08, "currency": "CNY",
             "counterparty": "其他人", "note": "撤销交易", "category": "income"},
        ]
        result, pairs = _pair_reversals(records)
        assert len(result) == 2  # both kept
        assert len(pairs) == 0

    def test_非撤销收入不配对(self):
        """普通收入（不含"撤销"）不参与撤销配对"""
        from ft.convert import _pair_reversals

        records = [
            {"occurred_at": "2026-01-16 21:25:22", "amount": -100.00, "currency": "CNY",
             "counterparty": "某商户", "note": "消费", "category": "expense"},
            {"occurred_at": "2026-01-16 21:25:22", "amount": 100.00, "currency": "CNY",
             "counterparty": "某商户", "note": "退款", "category": "income"},
        ]
        result, pairs = _pair_reversals(records)
        assert len(result) == 2  # "退款"不含"撤销"，不配对
        assert len(pairs) == 0

    def test_工行借记卡_支付宝退款_唯一候选自动核销(self):
        lines = [
            "2026-01-05",
            "09:00:00",
            "1614020101021984636",
            "-19.90",
            "快捷支付",
            "支付宝（中国）网络技术有限公司",
            "2026-01-05",
            "10:00:00",
            "1614020101021984636",
            "+19.90",
            "退款",
            "支付宝（中国）网络技术有限公司",
        ]
        from ft.convert import _parse_icbc_lines, _build_convert_fact_rows, _attach_tracking_metadata
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=False)
        fact_rows = _attach_tracking_metadata(_build_convert_fact_rows(records, tracking_pairs), tracking_pairs)
        assert len(fact_rows) == 2
        expense = next(r for r in fact_rows if r["category"] == "expense")
        refund = next(r for r in fact_rows if r["category"] == "income")
        assert len(_pairs_only(tracking_pairs)) == 1
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "strong"
        assert _pairs_only(tracking_pairs)[0]["candidate_count"] == 1
        assert expense["offset_role"] == "expense"
        assert refund["offset_role"] == "refund"
        assert refund["proposed_action"] == f"merge_refund_into:{expense['record_id']}"

    def test_工行借记卡_同类多候选退款_最近归并直过(self):
        lines = [
            "2026-01-05",
            "09:00:00",
            "1614020101021984636",
            "-20.00",
            "快捷支付",
            "支付宝（中国）网络技术有限公司",
            "2026-01-05",
            "09:30:00",
            "1614020101021984636",
            "-40.00",
            "快捷支付",
            "支付宝（中国）网络技术有限公司",
            "2026-01-05",
            "10:00:00",
            "1614020101021984636",
            "+15.00",
            "退款",
            "支付宝（中国）网络技术有限公司",
        ]
        from ft.convert import _parse_icbc_lines, _build_convert_fact_rows, _attach_tracking_metadata
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=False)
        fact_rows = _attach_tracking_metadata(_build_convert_fact_rows(records, tracking_pairs), tracking_pairs)
        assert len(_pairs_only(tracking_pairs)) == 1
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "strong"
        assert _pairs_only(tracking_pairs)[0]["candidate_count"] == 2
        assert _pairs_only(tracking_pairs)[0]["expense"]["amount"] == -40.0
        refund = next(r for r in fact_rows if r["category"] == "income")
        assert refund["offset_strength"] == "strong"

    def test_工行借记卡_跨账户候选退款_保持弱置信(self):
        lines = [
            "2026-01-05",
            "09:00:00",
            "1614020101021984636",
            "-100.00",
            "快捷支付",
            "支付宝（中国）网络技术有限公司",
            "2026-01-05",
            "09:10:00",
            "1614020101021984637",
            "-100.00",
            "网上银行",
            "支付宝（中国）网络技术有限公司",
            "2026-01-05",
            "10:00:00",
            "1614020101021984636",
            "+50.00",
            "退款",
            "支付宝（中国）网络技术有限公司",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=False)
        assert len(_pairs_only(tracking_pairs)) == 1
        assert _pairs_only(tracking_pairs)[0]["match_strength"] == "weak"

    def test_工行借记卡_退款冲超候选_不自动核销(self):
        lines = [
            "2026-01-05",
            "09:00:00",
            "1614020101021984636",
            "-20.00",
            "快捷支付",
            "支付宝（中国）网络技术有限公司",
            "2026-01-05",
            "10:00:00",
            "1614020101021984636",
            "+50.00",
            "退款",
            "支付宝（中国）网络技术有限公司",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=False)
        assert len(_pairs_only(tracking_pairs)) == 0
        incomes = [r for r in records if r["category"] == "income"]
        assert len(incomes) == 1


class TestAlipayOrderPrefix007:
    def test_closed_and_refund_tracking_pair(self):
        csv_path = str(TEST_DIR / "alipay_closed_refund.csv")
        origin = "2025101322001112651418454050"
        refund = origin + "_2991492337631815681_advance"
        _make_alipay_csv([
            ["2025-10-13 12:33:20", "消费", "飞鱼", "T恤", "支出", "65.90", "花呗", "交易关闭", origin],
            ["2025-10-18 14:54:04", "退款", "飞鱼", "退款-T恤", "不计收支", "65.90", "花呗", "退款成功", refund],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, pairs = _read_alipay_raw(csv_path)
        assert len(records) == 2
        assert any(
            (p.get("rule_hint") == "import.alipay.order_prefix.v1")
            or ("order" in str(p.get("rule_hint", "")))
            for p in pairs if not p.get("_acceptance")
        )
        amts = sorted(float(f["amount"]) for f in records)
        assert amts == [-65.9, 65.9]
