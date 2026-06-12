# Finance Tracker: 去重与导入器单测设计

> **设计版本:** 1.0
> **日期:** 2026-06-09
> **对应规则:** 用户6条去重业务规则

**目标:** 为 finance-tracker 的导入器（alipay/wechat/icbc）编写完整单元测试，
覆盖支付方式分类、导入去重、退款抵消、信用卡还款、资产调拨、借出/赠与、完整性/异常值7大模块。

**架构:** 每个测试使用临时 SQLite 数据库 + 临时 CSV/Excel 文件作为输入，
调用 `ft.importers.*` 的导入函数，验证数据库中的结果符合预期。

**Tech Stack:** Python 3.11, pytest 9.x, openpyxl, sqlite3

---

## 测试模块

### 模块A：支付方式分类（8个测试）

核心规则：读取支付宝/微信账单的"支付方式"字段，按以下规则分类。
不依赖金额、日期或跨来源匹配。

| # | 测试名 | 来源 | 输入支付方式 | 方向 | 期望类别 | 期望账户 |
|---|--------|------|-------------|------|---------|---------|
| A1 | test_credit_card_payment_is_transfer | alipay | `工商银行信用卡(1200)` | 支出 | transfer | 贷款 |
| A2 | test_credit_installment_is_transfer | alipay | `工商银行信用卡分期(1200) 6期` | 支出 | transfer | 贷款 |
| A3 | test_credit_with_discount_is_transfer | alipay | `工商银行信用卡(1200)&立减金` | 支出 | transfer | 贷款 |
| A4 | test_debit_card_payment_is_expense | alipay | `网商银行储蓄卡(4164)` | 支出 | expense | 现金 |
| A5 | test_wechat_balance_payment_is_expense | wechat | `零钱` | 支出 | expense | 现金 |
| A6 | test_alipay_balance_payment_is_expense | alipay | `账户余额` | 支出 | expense | 现金 |
| A7 | test_huabei_payment_is_transfer | alipay | `花呗` | 支出 | transfer | 贷款 |
| A8 | test_normal_income_stays_income | alipay | ``(空) | 收入 | income | 现金 |

**验证:** 检查 DB 中记录的 category 和 account_id 对应的 account.type。

### 模块B：导入去重（3个测试）

同一来源内，不允许因 date+amount 误跳，仅全字段精确匹配视为重复。

| # | 测试名 | 场景 | 期望 |
|---|--------|------|------|
| B1 | test_same_day_same_amt_diff_merchant_both_kept | 同日¥4.5两笔（便利蜂+麦当劳） | 2条都保留 |
| B2 | test_exact_duplicate_skipped | 完全相同行重复 | 只保留1条 |
| B3 | test_reimport_same_file_skipped | 导入同文件两次 | 只新增一次 |

**B1关键:** 当前代码使用 `(date, amount, category)` 作为去重key，会误跳。
修复后应使用全字段（含 counterparty, description 等）。

### 模块C：退款抵消（5个测试）

规则1：消费后退款配对抵消。不分现金/信用卡，不分来源。

| # | 测试名 | 场景 | 期望 |
|---|--------|------|------|
| C1 | test_full_refund_paired_deleted | 微信，消费¥600+退款¥600，同商户 | 两条都从DB消失 |
| C2 | test_partial_refund_net_amount | 微信，消费¥47.8+退¥10.9 | 消费改¥36.9，删退款 |
| C3 | test_credit_card_refund_same_rule | 支付宝，信用卡退款¥600 | 同C1，两条都删 |
| C4 | test_cash_refund_same_rule | 支付宝/微信，现金退款 | 同C1，两条都删 |
| C5 | test_refund_before_purchase_flagged | 退款日期<消费日期 | 标记为异常（不删除） |

**配对策略:**
- 全额退款: 同一来源 + 同商户名（含模糊匹配）+ 同金额 + 退款日>=消费日
- 部分退款: 退款金额 < 原始金额，商户匹配，消费改为(原始金额-退款金额)，删退款行
- 配对在 reconcile 阶段执行，不在导入时做

### 模块D：信用卡还款（1个测试）

规则2：用借记卡/余额还信用卡 → 现金→贷款的内部转账。

| # | 测试名 | 场景 | 期望 |
|---|--------|------|------|
| D1 | test_credit_card_repayment_is_transfer | 借记卡"转自己"金额匹配信用卡"贷"金额 | transfer，非expense |

### 模块F：资产调拨（3个测试）

规则4：购汇/基金买卖/银证转账 → transfer。

| # | 测试名 | 场景 | 期望 |
|---|--------|------|------|
| F1 | test_forex_transfer | 购汇（人民币→美元） | transfer |
| F2 | test_fund_trade_transfer | 基金购买/赎回 | transfer |
| F3 | test_security_transfer | 银证转账 | transfer |

测试数据放入 ICBC 借记卡导入器中（这些交易出现在借记卡账单里）。

### 模块G：借出与赠与（3个测试）

规则5：明确标注"出借"→借款账户 transfer；无标注→赠与 expense；别人还钱→冲借款 transfer。

| # | 测试名 | 场景 | 期望 |
|---|--------|------|------|
| G1 | test_lending_is_transfer_to_lend | 支付宝，"出借"关键词 | transfer, account.type=lend |
| G2 | test_family_gift_is_expense | 借记卡，给梁碧玲无出借标记 | expense |
| G3 | test_repayment_is_transfer | 别人转账备注"还款" | transfer, account.type=lend |

### 模块H：完整性/异常值（4个测试）

规则6：金额完整性、异常值检测。

| # | 测试名 | 场景 | 期望 |
|---|--------|------|------|
| H1 | test_zero_amount_skipped | 金额=0 | 不导入 |
| H2 | test_expense_sum_not_abs | expense含正数退款冲减 | SUM(amount)≠SUM(ABS(amount)) |
| H3 | test_no_positive_expense | 正金额expense=0条 | 0条 |
| H4 | test_no_negative_income | 负金额income=0条 | 0条 |

## 测试边界说明

- **部分退款处理:** C2 的实现需要在 reconcile 阶段修改原始消费金额，当前 ft 尚未实现该逻辑。测试应先标记为 expected failure 或用 xfail，等 reconcile 功能实现后再启用。
- **配对日期检查:** C5 要求在 reconcile 时做日期校验，当前也未实现。同理先用 xfail。
- **微信status过滤:** 微信现金退款（收入方向+status含"退款"）须先通过 INCOME_OK 过滤，当前已修复。
- **支付宝0元退款:** 支付宝中有金额=0的退款记录（如¥0的优惠券退款），这些已在 H1 中覆盖，导入时跳过。

## 文件结构

```
tests/
├── test_dedup_rules.py      ← 当前文件，已有部分测试
├── conftest.py               ← pytest fixtures（temp_db, _make_alipay_csv 等）
├── data/                     ← 测试用 fixture 数据（如有）
```

测试文件不拆分，统一放在 `test_dedup_rules.py` 中，
按模块（A/B/C/D/F/G/H）顺序排列，每个测试有完整注释。
