"""transfer_rules — 单腿内部转账识别（自有资产位置转移，非真实收支）

设计原则（见 references/monthly-analysis.md）：
- 只识别「用户名下资产的位置转移」：现金 ↔ 基金 / 外汇 / 货币基金。
  这类交易钱仍在用户名下，只是换了载体，不应计入收入/支出。
- **只匹配 counterparty + description，绝不匹配 account_name**
  （account_name 含「信用卡/储蓄卡」会污染任何关键词，造成海量假阳性）。
- 每条规则都必须能区分「资产转移」与「真实收支」，用带负例的判据。

与 reconcile.py 里的「配对型」转账识别（_match_same_currency_exact /
_match_fx_loan_repayment）互补：那套要求两条腿都在 ft 里且同额同时；
这套处理「单腿」转账——对手方是金融机构/自有钱包，永远配不上对。

判据返回：
- "transfer_out"  资产流出该账户（金额为负，钱去了基金/外汇/货基）
- "transfer_in"   资产流入该账户（金额为正，从基金/外汇/货基回来）
- None            不是内部转账（真实收支或无法判定，保持原样）
"""


def _cp(row: dict) -> str:
    return row.get("counterparty", "") or ""


def _desc(row: dict) -> str:
    return row.get("description", "") or ""


def _amount(row: dict) -> float:
    try:
        return float(row.get("amount") or 0)
    except (TypeError, ValueError):
        return 0.0


# ── 规则 1：基金申赎 ────────────────────────────────────────────
# 命中：对手方为基金销售/管理公司，买入/赎回资金调拨。
# 负例：
#   - "收益发放" → 余额宝/基金分红，是真实收入，不标转账。
#   - "搬家"（蚂蚁搬家公司）→ 真实消费，虽 cp 含"蚂蚁"。
def _is_fund_transfer(row: dict) -> bool:
    cp, de = _cp(row), _desc(row)
    if "收益发放" in de:
        return False
    if "搬家" in cp:
        return False
    if "基金销售" in cp:
        return True
    if "基金快速赎回" in cp:
        return True
    if "基金管理有限公司" in cp:
        return True
    return False


# ── 规则 2：货币基金（余额宝 / 余利宝）搬家 ──────────────────────
# 命中：余额宝/余利宝的转入转出（银行卡 ↔ 货基）。
# 负例：
#   - "收益发放" → 利息，真实收入。
def _is_money_fund_transfer(row: dict) -> bool:
    de = _desc(row)
    if "收益发放" in de:
        return False
    if "余利宝" in de and ("转入" in de or "转出" in de):
        return True
    if "支付宝转入到余利宝" in de:
        return True
    if "余额宝-单次转入" in de or "余额宝-转出" in de:
        return True
    return False


# ── 规则 3：购汇 / 换汇（境内 → 境外美股账户）─────────────────────
# 命中：个人购汇、购汇还款、跨境汇款——把人民币换成外汇转到境外账户。
def _is_fx_transfer(row: dict) -> bool:
    de = _desc(row)
    return any(k in de for k in ("个人购汇", "购汇还款", "预约购汇", "跨境汇款"))


# ── 规则 3b：外汇现金链路补全 ─────────────────────────────────
# 命中：银行账单中 description 整体就是币种名（美元/港币等），表示外汇资产链路的入/出。
# 反例："美元消费"/"港币消费" 是真实境外消费，不应命中。
def _is_fx_cash_leg(row: dict) -> bool:
    return _desc(row).strip() in {"美元", "港币", "日元", "欧元"}


# ── 规则 4：银证转账 ───────────────────────────────────────────
# 命中：银行现金账户 ↔ 证券资金账户。描述/对手方必须明确出现银转证/证转银语义。
def _is_security_transfer(row: dict) -> bool:
    text = _cp(row) + " " + _desc(row)
    return any(k in text for k in ("银转证", "银行转证券", "证转银", "证券转银行"))


# ── 规则 5：基金购买 / 基金赎回 ─────────────────────────────────
# 银行账单中 description 明确为基金购买/基金赎回时，资金在现金账户与基金资产载体间移动。
# 不绑定个人姓名；只看 counterparty+description，不看 account_name。收益发放仍是真实收入。
def _is_self_fund_transfer(row: dict) -> bool:
    de = _desc(row)
    if "收益发放" in de:
        return False
    return any(k in de for k in ("基金购买", "基金赎回"))


# ── 规则 6：钱包 / 网商银行调拨 ────────────────────────────────
# 微信零钱提现、支付宝转出到网商银行等，都是自有 cash 账户之间的位置移动。
def _is_wallet_transfer(row: dict) -> bool:
    cp, de = _cp(row), _desc(row)
    if "收益发放" in de or "账户结息" in de:
        return False
    if "微信零钱提现" in cp and "支付机构提现" in de:
        return True
    if "网商银行" in cp and "转出到网商银行" in de:
        return True
    return False


# ── 规则 7：消费贷还款（花呗 / 美团月付 / 京东白条）───────────────
# 这些产品已/应建为 loan 账户；还款是 cash ↔ loan 的内部调拨，不是真实消费。
# 京东规则刻意只吃 description=还款，避免误伤京东消费/商城业务。
def _is_consumer_loan_repayment(row: dict) -> bool:
    cp, de = _cp(row), _desc(row)
    if "花呗" in cp and "还款" in de:
        return True
    if ("美团月付" in cp or "美团金融" in cp or "美团金融服务" in cp) and "月付" in de and "还款" in de:
        return True
    if cp == "京东" and de == "还款":
        return True
    if "京东白条" in cp and "还款" in de:
        return True
    return False


_RULES = (
    ("fund_redeem", _is_fund_transfer),
    ("money_fund", _is_money_fund_transfer),
    ("fx_purchase", _is_fx_transfer),
    ("fx_cash_leg", _is_fx_cash_leg),
    ("security_transfer", _is_security_transfer),
    ("self_fund", _is_self_fund_transfer),
    ("wallet_transfer", _is_wallet_transfer),
    ("consumer_loan_repayment", _is_consumer_loan_repayment),
)


def classify_single_leg(row: dict) -> tuple[str, str] | None:
    """判定单条记录是否为单腿内部转账。

    返回 (category, rule_name)：
      - ("transfer_out", rule)  金额为负（资产流出该账户）
      - ("transfer_in",  rule)  金额为正（资产流入该账户）
      - None                    不是内部转账，保持原样
    只对 category 为 income/expense 的记录生效。
    """
    if row.get("category") not in ("income", "expense"):
        return None
    for rule_name, predicate in _RULES:
        if predicate(row):
            amt = _amount(row)
            side = "transfer_out" if amt < 0 else "transfer_in"
            return side, rule_name
    return None
