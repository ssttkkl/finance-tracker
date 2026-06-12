---
name: finance-tracker
category: finance
description: 纯CSV资产+消费管理系统 — 多币种账本+股票持仓追踪+Git版本控制
---

# Finance Tracker

`~/bin/ft` → `uv run --directory ~/.hermes/skills/finance/finance-tracker ft`

## 架构

双层存储 + Git 版本控制：

```
~/.ft/
├── snapshot.yaml          # 统一快照（查询用，秒出）
├── accounts.yaml          # 账户配置（name/type/currency/active）
├── mapping.yaml           # convert 阶段映射规则
├── .git/                  # 自动版本控制
└── records/
    ├── cash/2026-01-01.csv    # 消费交易流水
    ├── loan/2026-01-01.csv
    └── security/2026-06-12.csv  # 股票交易
```

- CSV 是审计日志（不可篡改），snapshot.yaml 是当前状态（可重建）
- 写操作（append/checkin/transfer/stock）→ 写 CSV + 更新 snapshot.yaml
- 查询（report/acct list/stock list）→ 只读 snapshot.yaml
- 每次写操作自动 git commit
- 修复：`ft verify --fix` 从 CSV 全量重建

## 消费流水线

```bash
ft convert 支付宝.csv --source alipay -o alipay.csv
ft convert 微信.xlsx --source wechat -o wechat.csv
ft merge alipay.csv wechat.csv -o merged.csv
ft append merged.csv
```

## 账户 & 报告

```bash
ft acct add <name> --type cash --currency CNY
ft acct list
ft checkin <account> --balance N
ft transfer --from A --to B --amount N [--to-amount M]
ft report [--month YYYY-MM]
ft list [--month] [--account] [--category] [--limit]
```

## 股票交易

```bash
ft stock buy --ticker nvda.us --shares 5 --price 120 --account IBKR
ft stock sell --ticker nvda.us --shares 2 --price 130 --account IBKR
ft stock deposit --amount 1000 --account IBKR
ft stock dividend --ticker nvda.us --amount 10 --account IBKR
ft stock init --ticker nvda.us --shares 45 --price 224.14 --account IBKR
ft stock checkin --account IBKR --ticker nvda.us --shares 45 --avg-cost 220
ft stock list
```

## 数据验证

```bash
ft verify          # 检查 CSV ↔ 快照
ft verify --fix    # 从 CSV 重建快照
```

## Security CSV 格式

```csv
date,action,ticker,shares,price,amount,commission,currency,account_name,note
```

ticker 规范：美股 .us（nvda.us），港股 .hk（00700.hk）。平均成本法（非 FIFO）。

## 快照结构

```yaml
accounts:
  cash:     {余额 float}
  loan:     {余额 float}
  lend:     {余额 float}
  security: {account: {currency, cash, positions: {ticker: {shares, avg_cost}}}}
```

## Git 版本控制

`~/.ft/` 自动 git 管理。首次写操作自动 init，之后每次自动 commit。

```bash
cd ~/.ft && git log --oneline -3
# auto(snapshot): 2026-06-13 00:21
# auto(append): 2026-06-13 00:15
# auto: init ft data repo
```

手动改 CSV 后自行 `git commit`。

## 已知陷阱

| 陷阱 | 说明 | 对策 |
|------|------|------|
| 借记卡余额为负 | CSV 只有消费无初始余额 | `ft checkin` 设真实余额 |
| snapshot 重复条目 | YAML 被追加而非覆盖 | `ft verify --fix` |
| ticker 不带市场后缀 | yfinance 需要 .us/.hk | 统一小写+后缀 |
