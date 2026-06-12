# 东方证券对账单转换 + stock append 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现东方证券 PDF 对账单转换为 stock CSV，并通过 `ft stock append` 导入

**Architecture:** 新增 importers/dfzq.py 纯解析函数，扩展 stock.py 新增 do_convert/do_append，注册 CLI

**Tech Stack:** Python, qpdf, mutool, csv

---

### Task 1: 准备测试数据 + 注册账户

**Files:**
- Create: `tests/fixtures/dfzq_text_sample.txt`
- 注册：`ft acct add 东方证券 --type security --currency CNY`

- [ ] **Step 1: 提取 PDF 真实文本做测试数据**

```bash
# 提取文本保存为 fixture
mkdir -p tests/fixtures
mutool draw -F text /tmp/decrypted_dfzq.pdf > tests/fixtures/dfzq_text_sample.txt
wc -l tests/fixtures/dfzq_text_sample.txt
```

- [ ] **Step 2: 注册账户**

```bash
cd ~/.hermes/skills/finance/finance-tracker
ft acct add 东方证券 --type security --currency CNY
```

预期输出类似：`✅ 已创建 security 账户: 东方证券 (CNY)`

- [ ] **Step 3: Commit**

```bash
git add tests/fixtures/
git commit -m "test: 添加东方证券PDF测试文本fixture"
```

---

### Task 2: 实现 importers/dfzq.py — PDF 解析器

**Files:**
- Create: `src/ft/importers/dfzq.py`
- Create: `tests/test_dfzq.py`

**Context:** 东方证券 PDF 经 qpdf 解密 + mutool draw -F text 后，每字段占一行。资金流水明细在页眉「资金流水明细(2024/07/01-2026/06/13)」之后，每条交易顺序为：发生日期(8位数字)、买卖类别、证券代码、证券名称、成交数量、成交价格、总发生金额、手续费、印花税、过户费、资金余额。

注意：PDF 含多页（每页末尾有"第X页，共Y页"标记和翻页符 \f），需跳过页眉行和汇总股票资料部分，只解析资金流水明细段。数据按时间倒序排列。

- [ ] **Step 1: 写 PDF 解析器纯函数**

创建 `src/ft/importers/dfzq.py`，实现 `parse_dfzq_text(lines)` 和 `_ticker_suffix(code)`：

Action 映射：
- 证券买入 → BUY
- 证券卖出 → SELL
- 银行转证券 → DEPOSIT
- OTC资金划出 → WITHDRAW
- 融券回购 → BUY (ticker=204001)
- 融券购回 → SELL (ticker=204001)

Ticker 后缀：
- 0/1/2 开头 → .sz（含159 ETFS）
- 5/6 开头 → .sh
- OTC 代码（851890/007011等）→ .otc
- 204001 → 无后缀

资金余额从最后一条交易取，末尾追加 CHECKIN 行。

- [ ] **Step 2: 写单元测试**

测试覆盖：
- 买入/卖出解析和 amount 计算（amount = 总发生金额 + 手续费）
- DEPOSIT/WITHDRAW
- 融券回购/融券购回
- 倒序输入→升序排序
- CHECKIN 取最后一笔资金余额
- Ticker 后缀映射（深/沪/OTC/逆回购）
- 空输入不崩溃

- [ ] **Step 3: 运行测试**

```bash
cd ~/.hermes/skills/finance/finance-tracker
uv run pytest tests/test_dfzq.py -v
```

- [ ] **Step 4: Commit**

```bash
git add src/ft/importers/dfzq.py tests/test_dfzq.py
git commit -m "feat: 东方证券 PDF 对账单解析器"
```

---

### Task 3: 实现 stock.py do_convert + do_append + CLI 注册

**Files:**
- Modify: `src/ft/stock.py`
- Modify: `src/ft/cli.py`
- Create: `tests/test_stock_convert.py`

- [ ] **Step 1: 在 stock.py 添加 do_convert 函数**

`do_convert(path, source, output, password, account, currency)`：
1. source="dfzq" → qpdf 解密 → mutool 提取文本 → parse_dfzq_text
2. 填充 account_name
3. 写 10 列 stock CSV

- [ ] **Step 2: 在 stock.py 添加 do_append 函数**

`do_append(file_path)`：
1. 校验 CSV 10列格式 + action 合法性 + account 注册
2. 按 date 排序
3. 每行写入 records/security/YYYY-MM-DD.csv
4. 调用 repair_security() 重建快照
5. git commit

- [ ] **Step 3: 注册 CLI**

在 cli.py 的 stock 子命令下添加：
- `stock convert <file> -s <source> -o <output> [--password] [--account]`
- `stock append <file>`

- [ ] **Step 4: 集成测试**

创建 `tests/test_stock_convert.py`，测试：
- do_append 有效 CSV → 写入 + 快照重建
- do_append 未知账户 → 报错不写入
- fixture 环境隔离（tmp records_dir + accounts.yaml）

- [ ] **Step 5: 运行测试**

```bash
cd ~/.hermes/skills/finance/finance-tracker
uv run pytest tests/test_dfzq.py tests/test_stock_convert.py -v
```

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: stock convert/append CLI + do_convert/do_append"
```

---

### 最终验证

- [ ] **执行端到端测试**

```bash
cd ~/.hermes/skills/finance/finance-tracker

# 运行所有测试
uv run pytest tests/ -v

# 实际转换对账单
uv run python -m ft.cli stock convert ~/Downloads/电子对账单.pdf -s dfzq --password 099215 -o /tmp/dfzq_stock.csv

# 检查 CSV
head -5 /tmp/dfzq_stock.csv
wc -l /tmp/dfzq_stock.csv

# 导入
uv run python -m ft.cli stock append /tmp/dfzq_stock.csv

# 查看持仓
uv run python -m ft.cli stock list
```
