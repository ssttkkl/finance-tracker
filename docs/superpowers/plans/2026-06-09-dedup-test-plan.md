# Finance Tracker 单测实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans.

**Goal:** 为 finance-tracker 补齐 27 个单元测试，覆盖支付方式分类、导入去重、退款抵消、信用卡还款、资产调拨、借出/赠与、完整性/异常值 7 大模块。

**Architecture:** 测试文件 `tests/test_dedup_rules.py`，使用 temp_db fixture + 临时 CSV/Excel 文件，调用 `ft.importers.*` 函数，验证 DB 结果。

**Tech Stack:** Python 3.11, pytest 9.x, openpyxl, sqlite3

---

### Task 1: 补全模块A（支付方式分类）

**Files:**
- Modify: `tests/test_dedup_rules.py`

新增测试：
- `test_credit_installment_is_transfer` — 信用卡分期(1200) 6期→transfer
- `test_credit_with_discount_is_transfer` — 信用卡+&立减金→transfer
- `test_wechat_balance_payment_is_expense` — 微信零钱→expense
- `test_alipay_balance_payment_is_expense` — 支付宝余额→expense
- `test_huabei_payment_is_transfer` — 花呗→transfer

每个测试：创建CSV/Excel → 导入 → assert category + account.type

### Task 2: 补全模块B（导入去重）+ 修复导入器bug

**Files:**
- Modify: `tests/test_dedup_rules.py`
- Modify: `src/ft/importers/alipay.py`（去重key修复）
- Modify: `src/ft/importers/wechat.py`（去重key修复）

新增测试：
- `test_same_day_same_amt_diff_merchant_both_kept` — 已有，当前FAIL
- `test_exact_duplicate_skipped` — 已有，当前PASS
- `test_reimport_same_file_skipped` — 新写

修复：alipay.py 和 wechat.py 的去重key改为 `(date, amount, category, counterparty, description, payment_method)`

### Task 3: 模块C（退款抵消）

**Files:**
- Modify: `tests/test_dedup_rules.py`

新增测试（C1/C3/C4 用 pytest.mark.xfail，C2/C5 暂不写）：
- `test_full_refund_paired_deleted` — 微信全额退款配对，两条都删
- `test_credit_card_refund_same_rule` — 信用卡退款配对
- `test_cash_refund_same_rule` — 现金退款配对

这些测试依赖 ft reconcile 实现。先用 xfail 标记。

### Task 4: 模块D（信用卡还款）

**Files:**
- Modify: `tests/test_dedup_rules.py`

新增测试：
- `test_credit_card_repayment_is_transfer` — 借记卡转出还信用卡，验证transfer

### Task 5: 模块F（资产调拨）

**Files:**
- Modify: `tests/test_dedup_rules.py`

新增测试：
- `test_forex_transfer` — 购汇
- `test_fund_trade_transfer` — 基金买卖
- `test_security_transfer` — 银证转账

测试数据放在 ICBC 借记卡格式文本中，导入后验证 category=transfer。

### Task 6: 模块G（借出与赠与）

**Files:**
- Modify: `tests/test_dedup_rules.py`

新增测试：
- `test_family_gift_is_expense` — 给家人无标注→expense
- `test_repayment_is_transfer` — 别人还钱→transfer冲借款

### Task 7: 模块H（完整性/异常值）

**Files:**
- Modify: `tests/test_dedup_rules.py`

新增测试：
- `test_expense_sum_not_abs` — 导入含退款的记录，验证expense汇总用SUM不是SUM(ABS)
- `test_no_positive_expense` — 验证DB中无正金额expense（退款冲减另算）
- `test_no_negative_income` — 验证DB中无负金额income

### Task 8: 修复微信信用卡退款bug

**Files:**
- Modify: `tests/test_dedup_rules.py`
- Modify: `src/ft/importers/wechat.py`

新增测试（RED）：
- `test_wechat_credit_refund_is_transfer` — 微信信用卡退款（收入方向 + pm=信用卡）→ transfer，不是income

修复wechat.py：在分类逻辑中，信用卡退款（收入+信用卡支付方式）应标记为transfer。

---

**执行顺序:** Task 1 → Task 2（含修复）→ Task 3（xfail）→ Task 4 → Task 5 → Task 6 → Task 7 → Task 8（含修复）

每个 Task 提交一次：`git add -A && git commit -m "test: add module X tests"`
