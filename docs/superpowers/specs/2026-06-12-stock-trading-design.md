# Stock Trading in Finance Tracker

## Context

Finance Tracker 目前只管理日常消费/转账。`security` 类型的账户（如 IBKR）只能记录现金变动，不能记录股票买卖和持仓。

另一套系统 `vibe-portfolio-analysis` 独立维护 `~/.hermes/portfolio.json` 管理持仓。两边数据割裂，需要合并。

## Goal

将股票交易功能接入 finance-tracker。security 类型账户使用独立 CSV 格式 + 快照文件。

## Architecture

**双层存储：**
- **审计日志（CSV）** — 每笔交易的完整记录，按天分文件，不可篡改
- **快照（YAML）** — 当前最新持仓状态，每次操作后自动更新，查询秒出

```
~/.ft/records/security/2026-06-12.csv    ← 交易流水（审计）
~/.ft/snapshot_security.yaml              ← 当前持仓快照（查询用）
```

`ft stock list` / `ft report` / `ft acct list` 全部从**快照**读取，不重放 CSV。
CSV 仅用于审计追溯、对账、验证快照正确性。

## Data Layout

```
~/.ft/records/
├── cash/        2026-01-01.csv    ← 10列标准格式（不变）
├── loan/        2026-01-01.csv    ← 10列标准格式（不变）
├── lend/        2026-01-01.csv    ← 10列标准格式（不变）
└── security/    2026-01-01.csv    ← 新格式，按天分文件
```

### Security CSV 格式

```csv
date,action,ticker,shares,price,amount,commission,currency,account_name,note
```

字段含义：

| 字段 | 说明 | 适用 action |
|------|------|------------|
| `date` | YYYY-MM-DD HH:MM:SS | 全部 |
| `action` | BUY / SELL / DIVIDEND / DEPOSIT / WITHDRAW / INIT / CHECKIN | 全部 |
| `ticker` | 股票代码，带市场后缀（如 `nvda.us`, `00700.hk`）| BUY/SELL/DIVIDEND/INIT/CHECKIN |
| `shares` | 正=增加，负=减少 | BUY/SELL/INIT/CHECKIN |
| `price` | 每股价格 | BUY/SELL/INIT（INIT 用 price × shares 设定成本）|
| `amount` | 现金端金额（买入=-600，卖出=+260）| 全部 |
| `commission` | 佣金/手续费 | BUY/SELL |
| `currency` | USD / CNY / HKD | 全部 |
| `account_name` | 账户名（IBKR/富途） | 全部 |
| `note` | 备注 | 可选 |

### 各 action 的行示例

```
action,ticker,shares,price,amount,commission
BUY,   nvda.us,  +5, 120.00, -600.00, 0.35
SELL,  nvda.us,  -2, 130.00, +260.00, 0.15
DIVIDEND,nvda.us, ,  ,       +10.00,
DEPOSIT,       ,  ,  ,       +1000.00,
WITHDRAW,      ,  ,  ,       -500.00,
INIT,  nvda.us, +45, 224.14, ,       ,     ← 初始持仓，amount=0
CHECKIN,nvda.us,+45, 220.00, ,       ,     ← 校正持仓（直接覆盖该标的）
```

### 规范

- `ticker` 带市场后缀：美股 `.us`，港股 `.hk`，A 股 `.sz`/`.sh`
- `shares` 正=增加股数，负=减少股数
- `amount` 始终是现金端视角：流入账户为正，流出为负
- **amount 不含 commission**。实际现金变动 = amount + commission（佣金也是支出）
- `commission` 只在 BUY/SELL 时填写
- `INIT` 用于首次导入初始持仓，不涉及现金变动
- `CHECKIN` 用于校正持仓，直接覆盖快照中该标的的 (shares, avg_cost)
- 负成本仓位（如已收回本金后的免费股）：`price=0`，avg_cost 为 0

## 快照文件

`~/.ft/snapshot_security.yaml`，所有 `ft stock *` 命令执行后自动更新。查询命令只读快照。

```yaml
# ~/.ft/snapshot_security.yaml
updated_at: "2026-06-12 15:30:00"
accounts:
  IBKR:
    currency: USD
    cash: 13401.00
    positions:
      nvda.us:
        shares: 45
        avg_cost: 224.14
      mu.us:
        shares: 8
        avg_cost: 125.00
```

## 操作 → CSV + 快照变更表

| 操作 | CSV 写入 | 快照变更 |
|------|---------|----------|
| `ft stock init` | INIT 行 | 设该标的 shares + avg_cost（从 price 派生） |
| `ft stock buy` | BUY 行 | 增 shares + 重算 avg_cost（加权平均） |
| `ft stock sell` | SELL 行 | 减 shares + avg_cost 不变 + 扣成本 |
| `ft stock dividend` | DIVIDEND 行 | 只更新 cash |
| `ft stock deposit` | DEPOSIT 行 | 只更新 cash |
| `ft stock withdraw` | WITHDRAW 行 | 只更新 cash |
| `ft stock checkin` | CHECKIN 行 | 直接覆盖该标的的 (shares, avg_cost) 或 cash |
| `ft stock list` | 不写 | 只读快照 |

### 平均成本更新（buy/sell）

**BUY：**
```
avg_cost = (current_avg_cost * current_shares + price * buy_shares) / (current_shares + buy_shares)
```

**SELL：**
```
# avg_cost 不变
# 已实现盈亏 = (sell_price - avg_cost) * sell_shares - commission
```

## CLI 设计

### `ft stock` 子命令

```bash
# 买卖
ft stock buy --ticker nvda.us --shares 5 --price 120 --commission 0.35 --account IBKR
ft stock sell --ticker nvda.us --shares 2 --price 130 --commission 0.15 --account IBKR --note "止盈"

# 现金操作
ft stock deposit --amount 1000 --account IBKR --note "入金"
ft stock withdraw --amount 500 --account IBKR
ft stock dividend --ticker nvda.us --amount 10 --account IBKR

# 初始导入
ft stock init --ticker nvda.us --shares 45 --price 224.14 --account IBKR

# 校正
ft stock checkin --account IBKR --ticker nvda.us --shares 45 --avg-cost 220.00
ft stock checkin --account IBKR --cash 14000

# 持仓查询（从快照读，拉 yfinance 市值）
ft stock list
# 📊 持仓 [USD]  IBKR
#   nvda.us    45 股  均价$224.14  成本$10,086  市值$11,250  盈亏+$1,164 (+11.5%)
#   现金: $13,401
```

### 参数简化

- `--account` 缺省值：如果有多个 security 账户，需要指定；只有 1 个时可设默认
- `--date` 缺省值：当前时间
- `--commission` 缺省值：0
- `--note` 可选

### 联动

- `ft report` 中 networth 对 security 账户：读快照 → 计算持仓市值 + 现金 → 展示总和
- `ft acct list` 中 security 账户余额 = 持仓市值 + 现金余额
- `ft list --category trade` — 列出 CSV 中的股票交易记录

## 迁移

从 `~/.hermes/portfolio.json` 中的现有持仓数据，用 `ft stock init` 一次性导入：

```bash
# 持仓（使用 price × shares 设定成本）
ft stock init --ticker mu.us --shares 8 --price 125.00 --account IBKR
ft stock init --ticker nvda.us --shares 45 --price 224.14 --account IBKR
ft stock init --ticker smh.us --shares 2 --price 245.00 --account IBKR
ft stock init --ticker qqq.us --shares 30 --price XXX --account IBKR
ft stock init --ticker avgo.us --shares 3 --price XXX --account IBKR
ft stock init --ticker mrvl.us --shares 3 --price 0 --account IBKR    # 负成本 ≈ -44.59

# 现金余额
ft stock deposit --amount 13401 --account IBKR --note "初始现金"
```

迁移后 `~/.hermes/portfolio.json` 可退役。快照和交易 CSV 成为事实源。

## 依赖

需要添加 `yfinance` 到 pyproject.toml 依赖（实时行情拉取）。
