"""Tests for convert layer — category + refund pairing logic"""
import os
import pytest
import tempfile
import csv
from pathlib import Path

TEST_DIR = Path(tempfile.mkdtemp())


def _make_alipay_csv(rows: list[list[str]], path: str):
    """Write a minimal Alipay-style CSV"""
    header = ["交易时间", "交易分类", "交易对方", "商品说明", "收/支", "金额", "收/付款方式"]
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(header)
        for r in rows:
            writer.writerow(r)


class TestAlipayCategory:
    """convert 层只看收支方向，不做语义判断"""

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
        assert records[0]["description"] == "提现-实时提现"

    def test_全额退款_收入方向(self):
        """方向=收入，全额退款 → 双向核销"""
        csv_path = str(TEST_DIR / "alipay_full_refund.csv")
        _make_alipay_csv([
            ["2026-01-01 12:00:00", "消费", "商家A", "买书", "支出", "100.00", "工商银行信用卡(1200)"],
            ["2026-01-05 10:00:00", "退款", "商家A", "退款-买书", "收入", "100.00", "工商银行信用卡(1200)"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert len(records) == 0, f"期望0条, 得到{len(records)}: {records}"

    def test_全额退款_不计收支方向(self):
        """方向=不计收支，全额退款 → 双向核销（真实支付宝格式）"""
        csv_path = str(TEST_DIR / "alipay_full_refund_nocount.csv")
        _make_alipay_csv([
            ["2026-01-01 12:00:00", "消费", "商家A", "买书", "支出", "100.00", "工商银行信用卡(1200)"],
            ["2026-01-05 10:00:00", "退款", "商家A", "退款-买书", "不计收支", "100.00", "工商银行信用卡(1200)"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert len(records) == 0, f"期望0条, 得到{len(records)}: {records}"

    def test_部分退款_不计收支方向(self):
        """方向=不计收支，部分退款30 → 原消费减为70"""
        csv_path = str(TEST_DIR / "alipay_partial_nocount.csv")
        _make_alipay_csv([
            ["2026-01-01 12:00:00", "消费", "商家A", "买书", "支出", "100.00", "工商银行信用卡(1200)"],
            ["2026-01-05 10:00:00", "退款", "商家A", "退款-买书", "不计收支", "30.00", "工商银行信用卡(1200)"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert len(records) == 1
        assert records[0]["category"] == "expense"
        assert records[0]["amount"] == -70.0

    def test_不计收支_非退款_转出(self):
        """不计收支 + 非退款（如转出到网商银行）→ 进入退款配对，孤退款保留为 income"""
        csv_path = str(TEST_DIR / "alipay_transfer_out_nocount.csv")
        _make_alipay_csv([
            ["2026-01-05 08:00:00", "其他", "网商银行", "转出到网商银行", "不计收支", "485.73", "账户余额"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert len(records) == 1
        assert records[0]["category"] == "income"
        assert records[0]["amount"] == 485.73

    def test_不计收支_零金额_跳过(self):
        """不计收支 + 金额为0 → 跳过"""
        csv_path = str(TEST_DIR / "alipay_zero_nocount.csv")
        _make_alipay_csv([
            ["2026-01-05 08:00:00", "信用借还", "哈啰好物", "预授权解冻", "不计收支", "0.00", "花呗"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert len(records) == 0

    def test_退款_优先匹配更早的消费(self):
        """同商家同金额无说明，退款匹配更早的那笔"""
        csv_path = str(TEST_DIR / "alipay_refund_match_earlier.csv")
        _make_alipay_csv([
            ["2026-01-01 12:00:00", "消费", "京东", "商品A", "支出", "100.00", "花呗"],
            ["2026-01-02 14:00:00", "消费", "京东", "商品B", "支出", "100.00", "花呗"],
            ["2026-01-05 10:00:00", "退款", "京东", "退款-商品A", "不计收支", "100.00", "花呗"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert len(records) == 1
        # 商品A（更早那笔）被核销，商品B 保留
        assert records[0]["description"] == "商品B"

    def test_退款_无说明时匹配最近的那笔(self):
        """同商家同金额都无说明，退款匹配日期最近的"""
        csv_path = str(TEST_DIR / "alipay_refund_no_desc.csv")
        _make_alipay_csv([
            ["2026-01-01 12:00:00", "消费", "京东", "", "支出", "100.00", "花呗"],
            ["2026-01-02 14:00:00", "消费", "京东", "", "支出", "100.00", "花呗"],
            ["2026-01-05 10:00:00", "退款", "京东", "", "不计收支", "100.00", "花呗"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert len(records) == 1
        # 更近那笔（01-02）被核销，01-01 保留
        assert records[0]["date"] == "2026-01-01 12:00:00"

    def test_退款_交易分类非退款_按说明兜底(self):
        """交易分类≠退款但描述含"退款-"，也应配对核销"""
        csv_path = str(TEST_DIR / "alipay_refund_desc_only.csv")
        _make_alipay_csv([
            ["2026-01-21 21:13:04", "交通出行", "高德打车", "高德打车订单", "支出", "22.00", "工行信用卡(1200)"],
            ["2026-01-21 21:13:48", "交通出行", "高德打车", "退款-高德打车订单", "不计收支", "5.29", "工行信用卡(1200)"],
        ], csv_path)
        from ft.convert import _read_alipay_raw
        records, _ = _read_alipay_raw(csv_path)
        assert len(records) == 1
        assert records[0]["category"] == "expense"
        assert records[0]["amount"] == -16.71  # 22 - 5.29


# ── 微信 ──────────────────────────────────────────────────

def _make_wechat_xlsx(rows: list[list[str]], path: str):
    import openpyxl
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.append(["交易时间", "交易对方", "商品", "收/支", "金额(元)", "支付方式", "当前状态"])
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
        """手机银行转账：counterparty=对方户名, description=手机银行"""
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
        assert records[0]["description"] == "手机银行"

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
        assert records[0]["description"] == "手机银行"

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
            "161402******4636",
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
        assert records[0]["description"] != "17:25:13", \
            f"时间不应跑到 description，实际={records[0]['description']!r}"


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
        assert records[0]["description"] != "2026-01-09", \
            f"description 不应是日期，实际={records[0]['description']!r}"

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
        desc = records[0]["description"]
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
        desc = records[0]["description"]
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

    def test_全额退款_双向核销(self):
        """退货600→核销消费600，两条都消失"""
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
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        # 全额核销→0条
        assert len(records) == 0, f"期望0条, 实际{len(records)}: {records}"

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
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["category"] == "expense"
        assert records[0]["amount"] == -16.71, f"amount={records[0]['amount']}"

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
        """全额退款核销后的记录仍保留card_number（但全额核销会被删除）"""
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
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 0

    def test_icbc_卡号_部分退款保留(self):
        """部分退款后的消费记录保留card_number"""
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
        records, _ = _parse_icbc_lines(lines, is_credit=True)
        assert len(records) == 1
        assert records[0]["card_number"] == "1200", f"card={records[0]['card_number']!r}"
        assert records[0]["amount"] == -16.71

    def test_mapping_卡号路由_1200(self):
        """卡号路由：按 source+payment_method 匹配，长规则优先"""
        from ft.mapping import match_payment_method
        rules = [
            {"source": "icbc_credit_1200", "match": "*", "account": "工行信用卡(1200)", "currency": "CNY"},
            {"source": "icbc_credit", "match": "*", "account": "工行信用卡", "currency": "CNY"},
        ]
        # 精确卡号路由优先
        match = match_payment_method(rules, "icbc_credit_1200", "*")
        assert match is not None
        assert match["account"] == "工行信用卡(1200)"
        # 泛用路由
        match2 = match_payment_method(rules, "icbc_credit_9999", "*")
        assert match2 is None  # 无匹配

    def test_mapping_卡号路由_9166(self):
        """icbc_credit_9166 → 按 mapping 动态路由"""
        from ft.mapping import match_payment_method
        rules = [
            {"source": "icbc_credit_9166", "match": "*", "account": "工行信用卡(9166)", "currency": "CNY"},
        ]
        match = match_payment_method(rules, "icbc_credit_9166", "*")
        assert match is not None
        assert match["currency"] == "CNY"  # 路由行为由 mapping 文件决定，不硬编码 account
        assert match["account"] == "工行信用卡(9166)"


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
        assert rec["description"] == "支付宝转账"
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
        assert rec["description"] == "无卡支付"
        assert rec["currency"] == "CNY"

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
        assert rec["description"] == "个人购汇"

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
        assert rec["description"] == "支付宝转账", f"desc={rec['description']!r}"

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

class TestRefundTracking:
    """_build_refund_tracking_rows — 退款状态 + 全额/部分核销"""

    def test_部分退款的_refund_status(self):
        """部分退款的 refund_status 应为 已部分退款(净额-xx.xx)"""
        from ft.convert import _build_refund_tracking_rows
        from ft.mapping import load_rules

        rules, default_action = load_rules()
        pair = {
            "expense": {
                "date": "2026-01-01", "amount": -178.23, "currency": "CNY",
                "counterparty": "洁丽", "description": "被子", "platform": "",
                "card_number": "1200", "payment_method": "工商银行信用卡(1200)*",
            },
            "refund": {
                "date": "2026-01-02", "amount": 89.12, "currency": "CNY",
                "counterparty": "洁丽", "description": "退款", "platform": "",
                "card_number": "1200", "payment_method": "工商银行信用卡(1200)*",
            },
            "match_type": "partial",
        }
        rows = _build_refund_tracking_rows([pair], rules, default_action, "icbc_credit")
        assert len(rows) == 2
        # 消费行最后一列是 refund_status
        assert rows[0][-1] == "已部分退款(净额-89.11)", \
            f"actual={rows[0][-1]!r}"
        # 退款行最后一列固定为 退款核销
        assert rows[1][-1] == "退款核销"

    def test_全额退款的_refund_status(self):
        """全额退款的 refund_status 应为 已全额退款"""
        from ft.convert import _build_refund_tracking_rows
        from ft.mapping import load_rules

        rules, default_action = load_rules()
        pair = {
            "expense": {
                "date": "2026-01-01", "amount": -100.00, "currency": "CNY",
                "counterparty": "京东", "description": "商品", "platform": "",
                "card_number": "1200", "payment_method": "工商银行信用卡(1200)*",
            },
            "refund": {
                "date": "2026-01-02", "amount": 100.00, "currency": "CNY",
                "counterparty": "京东", "description": "退款", "platform": "",
                "card_number": "1200", "payment_method": "工商银行信用卡(1200)*",
            },
            "match_type": "full",
        }
        rows = _build_refund_tracking_rows([pair], rules, default_action, "icbc_credit")
        assert len(rows) == 2
        assert rows[0][-1] == "已全额退款", \
            f"actual={rows[0][-1]!r}"
        assert rows[1][-1] == "退款核销"

    def test_platform_一致性(self):
        """counterparty 规范化后，支出和退款的 counterparty 应一致"""
        from ft.convert import _build_refund_tracking_rows
        from ft.mapping import load_rules

        rules, default_action = load_rules()
        pair = {
            "expense": {"date": "2026-01-17", "amount": -60.9, "currency": "CNY",
                        "counterparty": "拼多多支付-橙予进口专营店", "description": "拼多多支付-橙予进口专营店",
                        "card_number": "1200", "payment_method": "拼多多支付"},
            "refund": {"date": "2026-01-18", "amount": 60.9, "currency": "CNY",
                       "counterparty": "拼多多支付-橙予进口专营店", "description": "拼多多支付-橙予进口专营店",
                       "card_number": "1200", "payment_method": "拼多多支付"},
            "match_type": "full",
        }
        rows = _build_refund_tracking_rows([pair], rules, default_action, "icbc_credit")
        assert len(rows) == 2
        # 两行的 counterparty 应一致
        cp_exp = rows[0][3]
        cp_ref = rows[1][3]
        assert cp_exp == cp_ref, f"counterparty mismatch: {cp_exp} vs {cp_ref}"



# ── ICBC 退款平台修正 ──────────────────────────────────────

class TestIcbcRefundPlatform:
    """ICBC 退货的 platform 在 counterparty 更新后应重新计算"""

    def test_退款行platform跟随counterparty更新(self):
        """_parse_icbc_lines 将退货的 counterparty 归一化为品牌名（如「拼多多」）"""
        from ft.convert import _parse_icbc_lines, _build_refund_tracking_rows
        from ft.mapping import load_rules

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
        assert len(tracking_pairs) == 1, f"expected 1 tracking pair, got {len(tracking_pairs)}"
        pair = tracking_pairs[0]
        # refund 的 counterparty 应归一化为「拼多多」
        ref_cp = pair["refund"]["counterparty"]
        assert ref_cp == "拼多多", \
            f"退款tracking pair中counterparty应为拼多多，got: {ref_cp!r}"
        # 记录被消费后 records 应为空
        assert len(records) == 0, f"expected 0 records after full refund, got {len(records)}"


class TestIcbcDebitReversal:
    def test_购汇还款撤销配对(self):
        """购汇还款 + 撤销交易 → 全抵消"""
        from ft.convert import _pair_reversals

        records = [
            {"date": "2026-01-16 21:25:22", "amount": -761.08, "currency": "CNY",
             "counterparty": "测试用户", "description": "购汇还款", "category": "expense",
             "payment_method": "手机银行", "platform": ""},
            {"date": "2026-01-16 21:25:22", "amount": 761.08, "currency": "CNY",
             "counterparty": "测试用户", "description": "撤销交易", "category": "income",
             "payment_method": "手机银行", "platform": ""},
        ]
        result, pairs = _pair_reversals(records)
        assert len(result) == 0  # both removed
        assert len(pairs) == 1
        assert pairs[0]["match_type"] == "full"

    def test_不同对方不配对(self):
        """不同对方的撤销不配对"""
        from ft.convert import _pair_reversals

        records = [
            {"date": "2026-01-16 21:25:22", "amount": -761.08, "currency": "CNY",
             "counterparty": "测试用户", "description": "购汇还款", "category": "expense"},
            {"date": "2026-01-16 21:25:22", "amount": 761.08, "currency": "CNY",
             "counterparty": "其他人", "description": "撤销交易", "category": "income"},
        ]
        result, pairs = _pair_reversals(records)
        assert len(result) == 2  # both kept
        assert len(pairs) == 0

    def test_非撤销收入不配对(self):
        """普通收入（不含"撤销"）不参与撤销配对"""
        from ft.convert import _pair_reversals

        records = [
            {"date": "2026-01-16 21:25:22", "amount": -100.00, "currency": "CNY",
             "counterparty": "某商户", "description": "消费", "category": "expense"},
            {"date": "2026-01-16 21:25:22", "amount": 100.00, "currency": "CNY",
             "counterparty": "某商户", "description": "退款", "category": "income"},
        ]
        result, pairs = _pair_reversals(records)
        assert len(result) == 2  # "退款"不含"撤销"，不配对
        assert len(pairs) == 0

