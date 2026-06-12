---
name: finance-tracker
category: finance
description: 使用场景：管理个人财务、导入银行/信用卡/支付宝/微信账单、记录股票买卖、管理多币种资产（USD/CNY/HKD）、验证财务数据一致性
design_spec: docs/superpowers/specs/2026-06-12-csv-only-design.md, docs/superpowers/specs/2026-06-12-stock-trading-design.md, docs/superpowers/specs/2026-06-12-unified-snapshot-design.md
---

# Finance Tracker

纯 CSV 多币种个人记账工具，支持统一快照查询、股票交易和 Git 版本控制。

## 架构

**CSV 审计日志 + YAML 快照，无数据库。**

```
~/.ft/
├── accounts.yaml       # 账户元数据
├── mapping.yaml        # 支付方式→账户名（convert 用）
├── snapshot.yaml       # 统一快照（当前余额+持仓）
└── records/
    ├── cash/           # 现金类
    ├── loan/           # 贷款类（信用卡）
    ├── lend/           # 借出
    └── security/       # 股票（独立10列格式）
```

所有写操作同时更新 CSV + 快照。所有查询只读快照（秒出）。

`~/.ft/` 自动作为 Git 仓库，每次写操作自动 commit。

## 命令速查

### 账单导入流水线（6 步）

```
① convert → ② AI审查转换 → ③ 手动修正 → ④ merge → ⑤ AI审查合并 → ⑥ append（确认后落盘）
```

| 步骤 | 操作 | 产出 |
|------|------|------|
| ① convert | `ft convert 支付宝.csv -s alipay -o 支付宝.csv` | 统一 CSV + `_refunds.csv` |
| ② AI审查 | 逐项审查退款核销：退款是否有重/有漏、counterparty/platform 是否正确 | 审查报告 + 需修正项列表 |
| ③ 手动修正 | 直接修改 CSV（或修复转换器重跑） | 修正后的 CSV |
| ④ merge | `ft merge a.csv b.csv -o merged/` | `merged.csv` + `removed.csv` |
| ⑤ AI审查 | 逐项审查去重：是否有误删/漏删，removed 每对决策是否正确 | 审查报告 |
| ⑥ append | 输出统计「即将追加 N 条记录，来自 X 个账户」，确认后 `ft append merged.csv` | 落盘到 `records/{type}/YYYY-MM-DD.csv` |

**核心原则：** AI 审查是必须的门禁，不允许跳过审查直接进入下一步。

### AI 审查要点

#### 转换阶段（步骤②）
- 退款核销是否正确（全额/部分/孤退款标记）
- 退款是否有漏（配对的消费行是否都找到了）
- counterparty 提取是否正确（脱敏/乱码/截断）
- platform 推断是否遗漏（如 PAYPAL_PIXIVFANBOX 应标 pixiv）
- source 字段是否正确（银行账单不应出现"支付宝"）

#### 合并阶段（步骤⑤）
- `removed.csv` 每对去重：金额相等 + 时间差 ≤ 10s + account_name 一致 + 交叉验证通过
- 无误删（不同交易被合并）
- 无漏删（应去重未去重）
- 保留记录来自高优先级源（支付宝 > 微信 > 银行）

### 账户管理

| 命令 | 说明 |
|------|------|
| `ft acct add <名称> --type cash\|loan\|lend\|security --currency CNY\|USD\|HKD` | 新增账户 |
| `ft acct list` | 列出所有账户及余额 |
| `ft acct rename\|delete\|activate\|deactivate` | 管理账户 |

### 查询

| 命令 | 说明 |
|------|------|
| `ft report [--month YYYY-MM]` | 资产负债 + 消费 + 收入 + 转账 |
| `ft list [--month\|--account\|--category\|--limit]` | 交易明细 |
| `ft stock list` | 持仓总览（实时拉 yfinance 市值） |

### 手动记账

| 命令 | 说明 |
|------|------|
| `ft add -a -30 -c 麦当劳 --account 支付宝余额` | 单笔录入（按金额正负自动判断收支） |
| `ft checkin <账户> --balance N` | 余额快照（重置余额起点） |
| `ft transfer --from A --to B --amount N [--to-amount M]` | 转账（跨币种用 --to-amount） |

### 单笔录入

```bash
ft add -a -30 -c 麦当劳 --account 支付宝余额
ft add -a 200 --counterparty 工资 --account 工行借记卡
ft add -a -16.5 -c 瑞幸 --account 工行信用卡 --source 美团支付 --platform 瑞幸咖啡 -d "生椰拿铁"
```

`-a/--amount` 和 `-c/--counterparty` 必填，`--account` 必填。category 按金额正负自动判断。

| 命令 | 说明 |
|------|------|
| `ft report [--month YYYY-MM]` | 资产负债 + 消费 + 收入 + 转账 |
| `ft list [--month\|--account\|--category\|--limit]` | 交易明细 |
| `ft checkin <账户> --balance N` | 余额快照（重置余额起点） |
| `ft transfer --from A --to B --amount N [--to-amount M]` | 转账（跨币种用 --to-amount） |

### 股票交易

| 命令 | 说明 |
|------|------|
| `ft stock buy --ticker mu.us --shares 5 --price 120 --account IBKR` | 买入 |
| `ft stock sell --ticker mu.us --shares 2 --price 130 --account IBKR` | 卖出 |
| `ft stock dividend --ticker nvda.us --amount 10 --account IBKR` | 股息 |
| `ft stock deposit\|withdraw --amount 1000 --account IBKR` | 入金/出金 |
| `ft stock init --ticker nvda.us --shares 45 --price 224.14 --account IBKR` | 初始持仓（不涉及现金） |
| `ft stock checkin --account IBKR --ticker nvda.us --shares 45 --avg-cost 220` | 校正持仓 |
| `ft stock list` | 持仓总览（实时拉 yfinance 市值） |

### 数据一致性

| 命令 | 说明 |
|------|------|
| `ft verify` | 检查 CSV 与快照是否一致 |
| `ft verify --fix` | 从 CSV 全量重建快照 |

## Security CSV 格式

```csv
date,action,ticker,shares,price,amount,commission,currency,account_name,note
```

| action | ticker | shares | price | amount | 说明 |
|--------|--------|--------|-------|--------|------|
| BUY | nvda.us | +5 | 120.00 | -600.00 | 买入，amount 不含佣金 |
| SELL | nvda.us | -2 | 130.00 | +260.00 | 卖出 |
| DEPOSIT | | | | +1000.00 | 入金 |
| DIVIDEND | nvda.us | | | +10.00 | 股息 |
| INIT | nvda.us | +45 | 224.14 | 0 | 初始持仓 |
| CHECKIN | nvda.us | +45 | 220.00 | 0 | 校正 |

ticker 格式：`mu.us`（美股）、`00700.hk`（港股）。平均成本法：买入加权平均，卖出均价不变。

## 快照结构

```yaml
accounts:
  cash:
    支付宝余额: 2414.94
  loan:
    工行信用卡(1200): -13349.95
  lend: {}
  security:
    IBKR:
      currency: USD
      cash: 9979.98
      positions:
        nvda.us: {shares: 45, avg_cost: 224.14}
```

cash/loan/lend 直接存余额（float）。security 存结构化持仓。

## 平台规则

### source vs platform 定义

两个独立维度，互不干扰：

| 维度 | 含义 | 问题 | 例子 |
|------|------|------|------|
| **source** | 支付渠道（通过什么方式付的钱） | "怎么付的" | 支付宝、微信支付、京东支付、美团支付、Apple Pay、银行卡 |
| **platform** | 消费去向（钱花在了哪个平台/商家） | "在哪花的" | 麦当劳、京东、滴滴、Steam、B站、淘宝 |

**区分规则：**
- 支付宝/微信账单：source 始终是"支付宝"/"微信"，platform 从对方名+描述推断
- 信用卡账单：source 从交易场所前缀推断（美团支付/财付通/京东支付），platform 从整个描述内容推断
- source 和 platform 可以相同（如京东商城用京东支付），但不代表它们是同一回事

### O2O 中介特殊规则

O2O 平台（美团App、饿了么、淘宝闪购、高德团购）是中介——钱通过它们付给真实商家。

**核心规则：O2O 中介需标注真实消费平台。**

- 如果真实商家是已知连锁品牌（麦当劳、霸王茶姬、食其家等）→ platform 标注该品牌名
- 如果真实商家是个人、非连锁小店（不在平台规则中）→ platform 留空，不标记
- 美团/饿了么/淘宝闪购等中介名本身不记为 platform
- 规则顺序：具体品牌必须排在泛化关键词前面

| 描述 | platform | 原因 |
|------|----------|------|
| 美团App麦当劳（鼎成中心店） | 麦当劳 | 连锁品牌，标注真实平台 |
| 美团App霸XX姬（XX店） | 霸王茶姬 | 连锁品牌，标注真实平台 |
| 美团App老王饺子馆 | (空) | 非连锁小店，不标记 |
| 美团收银（麦当劳XX店） | 麦当劳 | 连锁品牌，标注真实平台（同美团App逻辑） |
| 美团收银（老王饺子馆） | (空) | 非连锁小店，不标记（同美团App逻辑） |
| 先骑后付（美团单车） | 美团 | 美团自有服务 |
| 小象超市 | 美团 | 美团自有服务 |
| 大众点评XX店铺 | 大众点评 | 非 O2O 中介场景 |

### 匹配优先级

1. **公司注册全名**（信用卡账单专用）→ 映射为品牌
2. **连锁品牌名**（麦当劳/肯德基/霸王茶姬）
3. **电商/数字平台**（京东/淘宝/Steam/B站）
4. **出行平台**（滴滴/哈啰）
5. **自有服务**（美团单车/小象超市 → 美团）
6. **无匹配 → 空字符串**，绝不 fallback 到账单来源名

### 不建规则的类型

- **个人商家**（戴永鸿、黄晓楠等）→ 空
- **非平台公司**（度友科技、北京合兴等）→ 空
- **支付方式**（Apple Pay、云闪付等）→ 属于 source，不是 platform

## 已知陷阱

| 问题 | 对策 |
|------|------|
| 借记卡余额为负（CSV 只有消费） | `ft checkin <卡名> --balance <真实余额>` |
| 新版支付宝「不计收支」方向 | 判断 `in ("收入","不计收支")` 而非 `== "收入"` |
| ICBC 信用卡交易场所提取 | 从金额行向前扫描，不用 ±8 窗口 |
| O2O 平台被误标为 platform | 具体品牌规则排在平台规则前 |
| 去重源分类用 CSV 字段 | 按文件来源分类，不依赖 CSV 内字段 |
| snapshot.yaml 重复条目 | `ft verify --fix` 全量重建 |
