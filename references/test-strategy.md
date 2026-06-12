# 单测设计：去重与导入器

**版本:** 2026-06-09（基于27个测试的完整设计）
**文件:** `~/Projects/finance-tracker/tests/test_dedup_rules.py`

**TDD 铁律：RED → GREEN → REFACTOR。不允许 happy test。每个测试必须反映真实业务场景。**

## 测试运行

```bash
cd ~/Projects/finance-tracker && uv run pytest tests/ -v
```

## 模块结构

### 模块A：支付方式分类（8个测试）

| # | 测试名 | 输入支付方式 | 期望 |
|---|--------|-------------|------|
| A1 | test_credit_card_payment_is_transfer | `工商银行信用卡(1200)` | transfer |
| A2 | test_credit_installment_is_transfer | `工商银行信用卡分期(1200) 6期` | transfer |
| A3 | test_credit_with_discount_is_transfer | `工商银行信用卡(1200)&立减金` | transfer |
| A4 | test_debit_card_payment_is_expense | `网商银行储蓄卡(4164)` | expense |
| A5 | test_wechat_balance_payment_is_expense | `零钱` | expense |
| A6 | test_alipay_balance_payment_is_expense | `账户余额` | expense |
| A7 | test_huabei_payment_is_transfer | `花呗` | transfer |
| A8 | test_normal_income_stays_income | `(空)` 收钱码收款 | income |

**规则:** 仅凭 `收/付款方式` / `支付方式` 字段分类。不依赖金额、日期或跨来源匹配。

**优先级（从高到低）:**
1. 出借 or 购汇/基金/银证/还款 keywords → transfer
2. 信用卡/花呗 in payment_method → transfer
3. 退款 in 交易分类 + amount>0 → expense（正数冲减）
4. 支出=expense, 收入=income

### 模块B：导入去重（3个测试）

| # | 测试名 | 场景 | 期望 |
|---|--------|------|------|
| B1 | test_same_day_same_amt_diff_merchant_both_kept | 同日¥4.5两笔（便利蜂+麦当劳） | 2条都保留 |
| B2 | test_exact_duplicate_skipped | 完全相同行重复 | 只保留1条 |
| B3 | test_reimport_same_file_skipped | 导入同一文件两次 | 只新增一次 |

**去重key:** `(date, round(amount,2), category, counterparty, description, payment_method)` — 全字段精确匹配，不是按 date+amount。

### 模块C：退款抵消（5个测试，待 reconcile 实现）

| # | 测试名 | 场景 | 期望 |
|---|--------|------|------|
| C1 | test_full_refund_paired_deleted | 微信全额退款 | 两条都删 |
| C2 | test_partial_refund_net_amount | 消费¥47.8退¥10.9 | 消费改¥36.9，删退款 |
| C3 | test_credit_card_refund_same_rule | 信用卡退款¥600 | 同C1 |
| C4 | test_cash_refund_same_rule | 现金退款 | 同C1 |
| C5 | test_refund_before_purchase_flagged | 退款日<消费日 | 标记异常 |

全部 skip 标记（reconcile 尚未实现）。

### 模块D：信用卡还款（1个测试）

| # | 测试名 | 场景 | 期望 |
|---|--------|------|------|
| D1 | test_credit_card_repayment_is_transfer | 建行储蓄卡还信用卡 | transfer |

### 模块F：资产调拨（2个测试）

| # | 测试名 | 场景 | 期望 |
|---|--------|------|------|
| F1 | test_forex_transfer | 购汇（人民币→美元） | transfer |
| F2 | test_fund_trade_transfer | 基金购买 | transfer |

### 模块G：借出与赠与（3个测试）

| # | 测试名 | 场景 | 期望 |
|---|--------|------|------|
| G1 | test_lending_is_transfer_to_lend | "出借"关键词 | transfer, lend |
| G2 | test_family_gift_is_expense | 给梁碧玲无标注 | expense |
| G3 | test_repayment_is_transfer | "还款"收入 | transfer, lend |

### 模块H：完整性/异常值（1个测试）

| # | 测试名 | 场景 | 期望 |
|---|--------|------|------|
| H1 | test_expense_sum_not_abs | 含正数expense冲减 | SUM(amount)≠SUM(ABS(amount)) |

另外在导入器中直接跳过的测试（A模块已覆盖）：0元记录跳过。

## 已知失败 / 待实现

- 模块C（全部4个）：依赖 `ft reconcile` 命令实现配对删除逻辑，当前 skip
- C2（部分退款）：也依赖 reconcile
- 微信退款导入：微信退款记录须先通过 INCOME_OK 过滤 + is_refund 逻辑，当前已修复
- 支付宝退款：支付宝退款在 `交易分类` = "退款" 列检测，支付宝原始数据中的退款行 status=退款成功，原消费 status=交易关闭

## 2026-06-09 会话中修复的Bug

1. **现金退款标income** — 非信用卡退款（收入方向）被标记为income，应标记为expense（正数冲减）
2. **出借标expense** — 描述含"出借"但非信用卡支付方式→走兜底else标为expense，应标transfer归借款账户
3. **0元记录被导入** — 金额=0的记录（全额优惠券抵扣）被标记为income导入，应skip
4. **微信信用卡退款标income** — 微信信用卡退款（收入方向+pm含信用卡）走兜底else→income，应标transfer
5. **去重key误跳** — key=(date, amount, category) 导致同日同金额不同商户的第二笔被跳过，改为全字段精确匹配
6. **购汇/基金/银证标expense** — 未加关键词分类，走常规逻辑标expense，应标transfer
7. **还款收入标income** — "还款"收入走兜底→income，应标transfer归借款账户
