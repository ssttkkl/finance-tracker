---
name: finance-tracker
category: finance
description: 使用场景：管理个人财务、导入银行/信用卡/支付宝/微信账单、记录股票买卖、管理多币种资产（USD/CNY/HKD）、验证财务数据一致性
design_spec: docs/superpowers/specs/2026-06-12-csv-only-design.md, docs/superpowers/specs/2026-06-12-stock-trading-design.md, docs/superpowers/specs/2026-06-12-unified-snapshot-design.md
---

# Finance Tracker

纯 CSV 多币种个人记账工具，支持统一快照查询、股票交易和 Git 版本控制。

## 架构

**CSV 审计日志 + YAML 快照，无数据库。** 所有写操作同时更新两者，所有查询只读快照。`~/.ft/` 自动作为 Git 仓库，每次写操作自动 commit。

## 命令速查

### 账单导入流水线（6 步）

```
① convert → ② AI审查转换 → ③ 手动修正 → ④ merge → ⑤ AI审查合并 → ⑥ append（确认后落盘）
```

| 步骤 | 操作 | 产出 |
|------|------|------|
| ① convert | `ft convert <账单> -s alipay|wechat|icbc|ccb-debit -o <csv>` | 统一 CSV + `_refunds.csv` |
| ② AI审查 | 逐项审查退款核销 | 审查报告 |
| ③ 手动修正 | 直接修改 CSV 或修复转换器重跑 | 修正后的 CSV |
| ④ merge | `ft merge <csvs> -o merged/` | `merged.csv` + `removed.csv` |
| ⑤ AI审查 | 逐项审查去重决策 | 审查报告 |
| ⑥ append | 确认后 `ft append merged.csv` | 落盘到 records/ |

convert 说明：`alipay`（支付宝 CSV）、`wechat`（微信 xlsx）、`icbc`（工行 PDF，需 --password，自动检测信用卡/借记卡）、`ccb-debit`（建行 xls）。

AI 审查要点：转换阶段检查退款核销、counterparty、platform、source 是否正确。合并阶段检查去重是否有误删/漏删。

### 账户管理

| 命令 | 说明 |
|------|------|
| `ft acct add <名称> --type cash|loan|lend|security --currency CNY|USD|HKD` | 新增 |
| `ft acct list` | 列表+余额 |
| `ft acct rename|delete|activate|deactivate` | 管理 |

### 查询

| 命令 | 说明 |
|------|------|
| `ft report [--month YYYY-MM]` | 资产负债 + 消费 + 收入 + 转账 |
| `ft list [--month|--account|--category|--limit]` | 交易明细 |

### 手动记账

| 命令 | 说明 |
|------|------|
| `ft add -a -30 -c 麦当劳 --account 支付宝余额` | 单笔录入（按金额正负自动判断收支） |
| `ft checkin <账户> --balance N` | 余额快照（重置余额起点） |
| `ft transfer --from A --to B --amount N [--to-amount M]` | 转账（跨币种用 --to-amount） |

### 数据一致性

| 命令 | 说明 |
|------|------|
| `ft verify` | 检查 CSV 与快照是否一致 |
| `ft verify --fix` | 从 CSV 全量重建快照 |

### 账单字段

每笔交易记录包含以下字段：

| 字段 | 说明 | 示例 |
|------|------|------|
| `date` | 交易时间，精确到秒 | `2026-01-01 13:00:40` |
| `amount` | 带符号金额，负=支出，正=收入 | `-16.30` |
| `currency` | 币种 | `CNY` / `USD` / `HKD` |
| `counterparty` | 交易对方 | `美团App霸王茶姬` |
| `description` | 商品说明（80字截断） | `生椰拿铁` |
| `category` | 收支类型 | `expense` / `income` |
| `account_name` | 账户名（对应 accounts.yaml） | `支付宝余额` |
| `source` | 支付渠道（怎么付的） | `支付宝` / `美团支付` |
| `platform` | 消费去向（在哪花的） | `霸王茶姬` / `京东` |
| `bill_source` | 账单来源 | `alipay` / `icbc_credit` |

#### source vs platform

source 是支付渠道，platform 是消费去向。两者独立：

- 支付宝/微信账单：source 固定为"支付宝"/"微信"，platform 从对方名+描述推断
- 信用卡账单：source 从交易场所前缀推断（美团支付/财付通/京东支付），platform 从描述推断
- 单笔录入 `ft add`：source 和 platform 可选，手动指定

#### O2O 中介规则

O2O 平台（美团App、饿了么、淘宝闪购）是中介——钱通过它们付给真实商家。**需标注真实消费平台。**

- 已知连锁品牌 → 标注品牌名
- 非连锁小店 → 留空
- 中介名本身不记为 platform
- 具体品牌规则排在泛化关键词前

| 描述 | platform |
|------|----------|
| 美团App麦当劳 | 麦当劳 |
| 美团App老王饺子馆 | (空) |
| 美团收银麦当劳 | 麦当劳 |
| 美团收银老王饺子馆 | (空) |
| 先骑后付 | 美团 |
| 小象超市 | 美团 |

#### 匹配优先级

公司全名 → 连锁品牌 → 电商/数字平台 → 出行 → 自有服务 → 空。绝不 fallback 到账单来源名。

## 股票交易

security 类型账户使用独立 CSV 格式，支持美股（`mu.us`）和港股（`00700.hk`）。采用平均成本法：买入时加权平均，卖出时均价不变按比例扣减成本。

`ft stock buy/sell` 自动扣减/增加现金并更新持仓。`ft stock checkin` 用于初始导入或校正持仓/现金（不涉及现金变动）。`ft stock list` 实时拉取 yfinance 市值。

```bash
# 日常买卖
ft stock buy --ticker nvda.us --shares 5 --price 120 --commission 0.35 --account IBKR
ft stock sell --ticker nvda.us --shares 2 --price 130 --commission 0.15 --account IBKR

# 现金操作
ft stock deposit --amount 1000 --account IBKR
ft stock withdraw --amount 500 --account IBKR
ft stock dividend --ticker nvda.us --amount 10 --account IBKR

# 校正（首次迁移或手动修正时用）
ft stock checkin --account IBKR --ticker nvda.us --shares 45 --avg-cost 224.14
ft stock checkin --account IBKR --cash 14000

# 查询
ft stock list
```

## 已知陷阱

| 问题 | 对策 |
|------|------|
| 借记卡余额为负（CSV 只有消费） | `ft checkin <卡名> --balance <真实余额>` |
| 新版支付宝「不计收支」方向 | 判断 `in ("收入","不计收支")` 而非 `== "收入"` |
| O2O 平台被误标 | 品牌规则排在平台规则前 |
| snapshot 不一致 | `ft verify --fix` 重建 |
