"""单腿内部转账识别规则的单测。

正例取自真实 ~/.ft 数据的 counterparty/description 长相；
反例（陷阱）也取自真实数据，防止误伤真实收支。
"""
from ft.transfer_rules import classify_single_leg


def _row(amount, cp, desc, category="expense"):
    return {
        "amount": str(amount),
        "counterparty": cp,
        "description": desc,
        "category": category,
        "account_name": "支付宝余额",  # 故意放污染词，验证规则不看 account_name
    }


# ── 规则 1：基金申赎 ──────────────────────────────────────────
def test_fund_buy_via_alipay_marked_transfer_out():
    # 支付宝里基金买入显示成 desc='...C-买入'，金额为负
    r = _row(-100, "蚂蚁财富-蚂蚁（杭州）基金销售有限公司",
             "蚂蚁财富-大成纳斯达克100ETF联接(QDII)C-买入")
    assert classify_single_leg(r) == ("transfer_out", "fund_redeem")


def test_fund_redeem_marked_transfer_in():
    r = _row(10000, "中国工商银行股份有限公司基金快速赎回", "基金赎回", category="income")
    assert classify_single_leg(r) == ("transfer_in", "fund_redeem")


def test_fund_sale_desc_consumption_still_transfer():
    # 支付宝把基金申赎写成 desc='消费'，靠 cp 精确匹配
    r = _row(-100, "蚂蚁（杭州）基金销售有限公司", "消费")
    assert classify_single_leg(r) == ("transfer_out", "fund_redeem")


# 陷阱反例：余额宝收益发放是真实收入，不能标转账
def test_fund_income_distribution_not_transfer():
    r = _row(3.5, "长城基金管理有限公司", "余额宝-2025.07.10-收益发放", category="income")
    assert classify_single_leg(r) is None


# 陷阱反例：蚂蚁搬家公司是真实消费
def test_ant_moving_company_not_transfer():
    r = _row(-500, "蚂蚁搬家总部联系方式1336650656", "收款方备注:二维码收款")
    assert classify_single_leg(r) is None


# ── 规则 2：货币基金搬家 ──────────────────────────────────────
def test_yulibao_transfer_in():
    r = _row(18, "网商银行", "支付宝转入到余利宝", category="income")
    assert classify_single_leg(r) == ("transfer_in", "money_fund")


def test_yulibao_transfer_out_to_bank():
    r = _row(-1911, "网商银行", "余利宝-转出到银行卡")
    assert classify_single_leg(r) == ("transfer_out", "money_fund")


def test_yuebao_single_transfer_in():
    r = _row(999, "余额宝", "余额宝-单次转入", category="income")
    assert classify_single_leg(r) == ("transfer_in", "money_fund")


# ── 规则 3：购汇换汇 ──────────────────────────────────────────
def test_personal_fx_purchase_transfer_out():
    r = _row(-13959, "黄文龙", "个人购汇")
    assert classify_single_leg(r) == ("transfer_out", "fx_purchase")


def test_cross_border_remittance_transfer_out():
    r = _row(-50000, "境外券商", "跨境汇款")
    assert classify_single_leg(r) == ("transfer_out", "fx_purchase")


def test_foreign_currency_cash_leg_transfer_in():
    # 外汇链路补全：银行账单里 description 明确为美元/港币，正数是外汇资产流入该账户。
    r = _row(5000, "本人外汇账户", "美元", category="income")
    assert classify_single_leg(r) == ("transfer_in", "fx_cash_leg")


def test_foreign_currency_cash_leg_transfer_out():
    r = _row(-7000, "境外券商", "港币")
    assert classify_single_leg(r) == ("transfer_out", "fx_cash_leg")


def test_foreign_currency_words_in_real_expense_not_transfer():
    # 真实境外消费里出现美元/港币不能误标；必须是描述整体就是币种/外汇调拨语义。
    r = _row(-100, "PAYPAL/SOME_STORE", "美元消费")
    assert classify_single_leg(r) is None


# ── 规则 4：银证转账 / 本人基金申赎 ─────────────────────────────
def test_bank_to_security_transfer_out():
    r = _row(-10000, "银行转证券", "银转证")
    assert classify_single_leg(r) == ("transfer_out", "security_transfer")


def test_security_to_bank_transfer_in():
    r = _row(26070.51, "证券转银行", "证转银", category="income")
    assert classify_single_leg(r) == ("transfer_in", "security_transfer")


def test_self_fund_purchase_marked_transfer_out():
    # 银行账单里基金购买/基金赎回本身就是本人基金资产载体调拨，不应写死姓名。
    r = _row(-9933.21, "基金业务", "基金购买")
    assert classify_single_leg(r) == ("transfer_out", "self_fund")


def test_self_fund_redeem_marked_transfer_in():
    r = _row(10107.2, "基金业务", "基金赎回", category="income")
    assert classify_single_leg(r) == ("transfer_in", "self_fund")


# ── 规则 6：钱包/网商/消费贷还款 ───────────────────────────────
def test_wechat_withdrawal_transfer_in():
    r = _row(1376.07, "微信零钱提现", "支付机构提现", category="income")
    assert classify_single_leg(r) == ("transfer_in", "wallet_transfer")


def test_alipay_to_mybank_transfer_in():
    r = _row(320, "网商银行", "转出到网商银行", category="income")
    assert classify_single_leg(r) == ("transfer_in", "wallet_transfer")


def test_huabei_repayment_transfer_in():
    r = _row(1350.30, "花呗", "花呗主动还款-2023年07月账单", category="income")
    assert classify_single_leg(r) == ("transfer_in", "consumer_loan_repayment")


def test_credit_card_mobile_bank_transfer_in():
    r = _row(3000, "转帐", "手机银行", category="income")
    assert classify_single_leg(r) == ("transfer_in", "consumer_loan_repayment")


def test_credit_card_wechat_transfer_in():
    r = _row(500, "转帐收入", "财付通", category="income")
    assert classify_single_leg(r) == ("transfer_in", "consumer_loan_repayment")


def test_meituan_monthly_repayment_transfer_out():
    r = _row(-188.87, "美团金融服务", "美团订单-【美团月付】主动还款2023年12月账单")
    assert classify_single_leg(r) == ("transfer_out", "consumer_loan_repayment")


def test_jd_repayment_transfer_out():
    r = _row(-994.86, "京东", "还款")
    assert classify_single_leg(r) == ("transfer_out", "consumer_loan_repayment")


def test_bank_side_generic_repayment_consumption_transfer_out():
    r = _row(-246.64, "还款", "消费")
    assert classify_single_leg(r) == ("transfer_out", "consumer_loan_repayment")


def test_online_payment_repayment_transfer_out():
    r = _row(-355.16, "网银在线（北京", "还款")
    assert classify_single_leg(r) == ("transfer_out", "consumer_loan_repayment")


def test_qiandai_repayment_transfer_out():
    r = _row(-245.35, "北京钱袋宝支付技", "还款")
    assert classify_single_leg(r) == ("transfer_out", "consumer_loan_repayment")


def test_meituan_monthly_counterparty_repayment_transfer_out():
    r = _row(-175.06, "美团月付还款", "消费")
    assert classify_single_leg(r) == ("transfer_out", "consumer_loan_repayment")


def test_wallet_recharge_account_transfer_out():
    r = _row(-240, "微信零钱充值账户", "充值")
    assert classify_single_leg(r) == ("transfer_out", "wallet_transfer")


def test_interop_wallet_recharge_not_transfer():
    r = _row(-2, "互联互通", "钱包充值")
    assert classify_single_leg(r) is None


def test_alipay_realtime_withdrawal_transfer_in():
    r = _row(500, "中国工商银行", "提现-实时提现", category="income")
    assert classify_single_leg(r) == ("transfer_in", "wallet_transfer")


def test_jd_purchase_not_consumer_loan_repayment():
    r = _row(-200, "京东", "商城业务")
    assert classify_single_leg(r) is None


# ── 反例：真实收支不能被误标 ──────────────────────────────────
def test_real_expense_mcdonalds_not_transfer():
    r = _row(-30, "麦当劳", "")
    assert classify_single_leg(r) is None


def test_real_income_salary_not_transfer():
    r = _row(20000, "北京屏芯科技有限公司", "工资", category="income")
    assert classify_single_leg(r) is None


# 陷阱反例：desc='充值' 是真实小额消费（话费/二维码），不是自有钱包充值
def test_recharge_desc_is_not_wallet_transfer():
    r = _row(-8.88, "微信", "充值")
    assert classify_single_leg(r) is None


# 已是 transfer 的记录不重复处理
def test_already_transfer_returns_none():
    r = _row(-100, "蚂蚁（杭州）基金销售有限公司", "消费", category="transfer_out")
    assert classify_single_leg(r) is None
