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


# ── 规则 4：银证转账 ───────────────────────────────────────────
# 命中：银行现金账户 ↔ 证券资金账户。描述/对手方必须明确出现银转证/证转银语义。
def _is_security_transfer(row: dict) -> bool:
    text = _cp(row) + " " + _desc(row)
    return any(k in text for k in ("银转证", "银行转证券", "证转银", "证券转银行"))


# ── 规则 5：本人名义基金申赎 ───────────────────────────────────
# 真实漏标形态：银行账单里 counterparty=黄文龙，description=基金购买/基金赎回。
# 只看 counterparty+description，不看 account_name；收益发放仍由基金规则负例排除。
def _is_self_fund_transfer(row: dict) -> bool:
    cp, de = _cp(row), _desc(row)
    if "收益发放" in de:
        return False
    if not any(name in cp for name in ("黄文龙", "HUANG WENLONG", "Huang Wenlong")):
        return False
    return any(k in de for k in ("基金购买", "基金赎回"))


_RULES = (
    ("fund_redeem", _is_fund_transfer),
    ("money_fund", _is_money_fund_transfer),
    ("fx_purchase", _is_fx_transfer),
    ("security_transfer", _is_security_transfer),
    ("self_fund", _is_self_fund_transfer),
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
