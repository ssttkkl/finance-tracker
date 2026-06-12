# 📒 Finance Tracker (ft)

纯 CSV 多币种个人记账工具，支持统一快照查询、股票交易和 Git 版本控制。

## 架构

**纯 CSV 审计日志 + YAML 快照，无数据库。**

```
~/.ft/
├── accounts.yaml       # 账户元数据（名称/类型/币种/启用）
├── mapping.yaml        # 支付方式 → 账户名映射（convert 用）
├── snapshot.yaml       # 统一快照（所有账户的当前余额 + 持仓）
└── records/            # 按天交易记录，按类型分子目录
    ├── cash/2026-01-01.csv
    ├── loan/2026-01-15.csv
    └── security/2026-06-12.csv
```

**双层存储：**
- **CSV 文件** — 不可篡改的审计日志，每天每账户类型一个文件
- **`snapshot.yaml`** — 当前状态快照，查询秒出
- 所有写操作（append/checkin/transfer/stock）同时更新 **CSV + 快照**
- 所有查询（report/acct list/stock list）只读 **快照**，不扫 CSV

**Git 版本控制：** `~/.ft/` 自动作为 git 仓库。每次写操作自动 commit。历史查看：

```bash
cd ~/.ft && git log
```

## 快速入门

```bash
ft acct list              # 查看账户列表（首次自动创建 accounts.yaml）
ft convert 支付宝.csv -s alipay -o alipay.csv       # 步骤①：原始账单→统一CSV
ft merge alipay.csv wechat.csv -o merged.csv        # 步骤②：跨源去重
ft append merged.csv                                # 步骤③：按天落盘
ft report [--month 2026-06]                         # 资产负债 + 消费 + 收入
ft list [--account 支付宝余额] [--limit 10]        # 交易明细
ft checkin 支付宝余额 --balance 5000                # 余额快照（重置余额）
ft transfer --from 工行借记卡 --to 工行信用卡 --amount 3000  # 转账
```

## 股票交易

security 类型账户使用独立 CSV 格式记录股票买卖。

```bash
ft stock buy --ticker nvda.us --shares 5 --price 120 --account IBKR
ft stock sell --ticker nvda.us --shares 2 --price 130 --account IBKR
ft stock dividend --ticker nvda.us --amount 10 --account IBKR
ft stock deposit --amount 1000 --account IBKR
ft stock init --ticker nvda.us --shares 45 --price 224.14 --account IBKR   # 初始持仓导入
ft stock checkin --account IBKR --ticker nvda.us --shares 45 --avg-cost 220  # 校正持仓
ft stock list          # 持仓总览（实时拉取 yfinance 市值）
```

**平均成本法：** 买入时加权平均，卖出时均价不变、按比例扣除成本。

## 数据一致性

```bash
ft verify                # 检查 CSV ↔ 快照是否一致
ft verify --fix          # 从 CSV 全量重建快照
```

- **security：** 重放全部交易 → 逐标的对比股数和现金
- **cash/loan/lend：** 检查所有 account_name 是否在 accounts.yaml 中注册

## 多币种

跨币种转账用两个金额，不用汇率：

```bash
ft transfer --from 工行借记卡 --to IBKR --amount 36250 --to-amount 5000
```

报告按币种分组展示，不做汇率折算。

## 流水线：账单导入

```
① ft convert → ② AI审查 → ③ 手动修正 → ④ ft merge → ⑤ AI审查 → ⑥ ft append
   账单→CSV     Codex审查     改错         去重         验证去重     按天落盘
   +_refunds    逐源审查                                   +removed
```

每步产出可查看可修改的 CSV，AI 审查是必须的门禁。

## 安装

```bash
git clone https://github.com/ssttkkl/finance-tracker.git
cd finance-tracker
uv sync
```

依赖：Python 3.11+, PyYAML, yfinance, openpyxl（PDF 账单需要 qpdf + mutool）

## 许可证

MIT
