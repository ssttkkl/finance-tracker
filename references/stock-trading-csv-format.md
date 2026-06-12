# Stock Trading CSV Format

Design spec: `docs/superpowers/specs/2026-06-12-stock-trading-design.md`

## Security Directory

`~/.ft/records/security/YYYY-MM-DD.csv` — 按天分文件，独立于 cash/loan/lend 的 10 列格式。

## CSV 列

```
date,action,ticker,shares,price,amount,commission,currency,account_name,note
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `date` | YYYY-MM-DD HH:MM:SS | 精确到秒 |
| `action` | BUY / SELL / DIVIDEND / DEPOSIT / WITHDRAW / INIT / CHECKIN | 交易类型 |
| `ticker` | str | 带市场后缀：nvda.us, 00700.hk, 600519.sh |
| `shares` | int/float | 正=增加，负=减少（BUY 正，SELL 负）|
| `price` | float | 每股价格（BUY/SELL/INIT 必填）|
| `amount` | float | 现金端金额：买入=-600，卖出=+260。不含佣金 |
| `commission` | float | 佣金（BUY/SELL 时填写）|
| `currency` | USD / CNY / HKD | 币种 |
| `account_name` | str | 账户名，对应 accounts.yaml 中 type=security 的账户 |
| `note` | str | 可选备注 |

## 现金余额公式

```
cash_balance = SUM(amount) - SUM(commission)
```
commission 始终为正，所以：
- BUY: amount(-600) - commission(0.35) → 现金减少 600.35
- SELL: amount(+260) - commission(0.15) → 现金增加 259.85

## 平均成本计算

```
holdings = {}  # {ticker: {"shares": N, "total_cost": float, "avg_price": float}}

for row in rows_sorted_by_date:
    if action == "BUY":
        shares += abs(shares)
        total_cost += abs(amount) + commission
        avg_price = total_cost / shares

    elif action == "SELL":
        cost_deduct = avg_price * abs(shares)
        total_cost -= cost_deduct
        shares -= abs(shares)

    elif action == "INIT":
        shares = shares
        total_cost = shares * price  # price 是均价
        avg_price = price

    # 其他 action 只影响现金，不影响持仓
```

## CLI 命令

```bash
ft stock buy --ticker nvda.us --shares 5 --price 120 --commission 0.35 --account IBKR
ft stock sell --ticker nvda.us --shares 2 --price 130 --commission 0.15 --account IBKR
ft stock dividend --ticker nvda.us --amount 10 --account IBKR
ft stock deposit --amount 1000 --account IBKR
ft stock withdraw --amount 500 --account IBKR
ft stock init --ticker nvda.us --shares 45 --price 224.14 --account IBKR
ft stock checkin --account IBKR --ticker nvda.us --shares 45 --avg-cost 220  # 校正持仓
ft stock checkin --account IBKR --cash 14000                           # 校正现金
ft stock list                                                          # 持仓总览
```

## 快照文件

`~/.ft/snapshot_security.yaml` — 每次 `ft stock *` 命令后自动更新。查询命令只读快照，不重放 CSV。

```yaml
accounts:
  IBKR:
    currency: USD
    cash: 9979.98
    positions:
      nvda.us:
        shares: 45
        avg_cost: 224.14
updated_at: "2026-06-12 15:30:00"
```

**快照更新规则：**
| 操作 | 快照变更 |
|------|---------|
| BUY | shares+=N, avg_cost=加权平均, cash-=amount+commission |
| SELL | shares-=N, avg_cost不变, cash+=amount-commission |
| INIT | 直接设 (shares, avg_cost=price) |
| DIVIDEND/DEPOSIT/WITHDRAW | 只更新 cash |
| CHECKIN(ticker) | 直接覆盖该标的 (shares, avg_cost) |
| CHECKIN(cash) | 直接覆盖现金余额 |

## 数据一致性验证

```bash
ft verify
```

对所有类型账户统一校验：
- **security**：重放 CSV 交易 → 按标的对比股数和现金 → 与 snapshot_security.yaml 比较
- **cash/loan/lend**：检查 CSV 中所有 account_name 是否在 accounts.yaml 中注册

## Test isolation pitfall

`stock.SNAPSHOT_PATH` 是模块级全局变量，引用 `models.FT_DIR / "snapshot_security.yaml"`。测试 fixture 必须同时 patch：
- `stock.SNAPSHOT_PATH` → 临时文件
- `models.RECORDS_DIR` → 临时目录
- `models.ACCOUNTS_PATH` → 临时 YAML

不然测试之间会互相污染快照。

## 联动

- `ft report` 中 security 账户读 security CSV → 持仓市值 + 现金 → 展示总和
- `ft acct list` 中 security 账户余额 = 持仓市值 + 现金余额
