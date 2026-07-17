# 📒 Finance Tracker (ft)

纯 CSV 多币种个人记账工具，支持统一快照查询、股票交易和 Git 版本控制。

## 架构

**纯 CSV 审计日志 + YAML 快照，无数据库。**

```
~/.ft/
├── accounts.yaml       # 账户元数据（名称/类型/币种/启用）
├── mapping.yaml        # 支付方式 → 账户名映射（convert 用）
├── snapshot.yaml       # 统一快照（所有账户的当前余额 + 持仓）
└── records/            # 按月交易记录，按类型分子目录
    ├── cash/2026-01.csv
    ├── loan/2026-01.csv
    └── security/2026-06.csv
```

**双层存储：**
- **CSV 文件** — 不可篡改的审计日志，每月每账户类型一个文件
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
ft append alipay.csv wechat.csv                      # 步骤②：按月落盘
ft reconcile --month 2026-06                         # 步骤③：导入后统一整理
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

**平均成本法：** 买入时总成本相加摊均价，卖出时总成本减去净回收资金（price × shares - commission），剩余均价随之变化。卖亏了均价↑，卖赚了均价↓。

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

完整的数据流、pending 决策语义和审计闭环见 [账单导入与 Reconcile 全流程](docs/import-reconcile-flow.md)。

```
① ft convert → ② ft append → ③ ft reconcile → ④ AI审查/编辑 working CSV → ⑤ ft commit
   账单→统一 CSV      按月落盘       自动整理/pending        继续 reconcile       Git 提交
```

convert 输出可查看的统一 CSV；reconcile 遇到低置信候选时才会进入 AI 审查门禁。

### 退款核销目标

退款自动核销的最高优先级是：

- 最终净额正确
- 账户余额正确
- 消费统计正确

在满足以上三点时，**允许对同类多候选消费采用保守的近邻归并**，不强求严格回链到唯一原单。

这意味着：

- 优先避免漏掉退款，导致净支出偏高
- 优先避免把退款核销到不同消费类型、不同账户或错误金额
- 对同商户 / 同平台 / 同类消费中的多候选退款，只要最终核算结果正确，可接受不精确回挂到唯一原单

实际核销发生在 `reconcile` 的镜像去重之后：

- 去重删除了退款或原消费时，关系先重绑到保留记录；重绑冲突则进入 pending。
- `strong` 关系自动核销：部分退款将原消费改为净额并删除退款；全额退款删除消费和退款两条记录。
- `weak` 关系进入 pending。确认时在退款行填写 `merge_refund_into:<消费 record_id>`；拒绝时用 `leave_as_is` 并写明理由。
- 每次重绑、核销和删除都会在 reconcile audit 中保留双边追溯记录。

## AI working CSV / pending 工作流

当 `ft reconcile` 遇到程序不该直接决定的跨来源候选或 weak 退款关系时，会创建 pending 会话。convert 会保留退款事实和关联元数据，并直接输出统一 CSV。

### 命令

```bash
ft convert <bill> -s <source> -o out.csv

ft reconcile --month 2026-06
ft reconcile --continue-with-decisions
ft reconcile --abort
```

### pending 期间的保证

- 只有 weak pending 且不存在自动结果时，`reconcile` 不改正式 `records/`、不改 `snapshot.yaml`
- 同一批次已判定的强去重、转账或 `strong` 退款会先写入；其审计行暂存于 `proposed_audit.csv`，在 continue 时正式写入 audit
- 只有 `--continue-with-decisions` 成功后才正式落地
- `--abort` 会删除当前 pending 会话

### 会话目录

```text
~/.ft/pending/reconcile/<session_id>/
```

常见文件：

- `manifest.json`：会话元信息
- `status.json`：当前状态
- `ai_working.csv`：给 AI 编辑的底稿
- `staged_records/` / `proposed_audit.csv`：reconcile 中间产物

### AI 允许编辑哪些列

主要允许修改：

- `counterparty`
- `description`
- `category`
- `account_name`
- `source`
- `transfer_account`
- `locked`
- `decision_action`
- `decision_reason`

`rule_hint` 说明程序命中的规则，`suggested_action` 是程序的建议动作；`decision_action` 和 `decision_reason` 才是审查者的最终决定。

默认只读：

- `record_id`
- `date`
- `amount`
- `currency`
- `bill_source`
- `raw_counterparty`
- `raw_description`
- `raw_payment_method`
- `rule_hint`
- `suggested_action`
- `processing_status`
- `ai_group`

### decision_action 合法值

- `leave_as_is`
- `keep`
- `drop`
- `modify`
- `merge_refund_into:<record_id>`
- `net_with:<record_id>`
- `mark_transfer_out_to:<record_id>`
- `mark_transfer_in_from:<record_id>`

### 调用方 AI 的标准流程

1. 先运行 `ft convert`、`ft append` 和 `ft reconcile`
2. 如果 reconcile 进入 pending，打开 `ai_working.csv`
3. 审查整份 `ai_working.csv`，不要只看局部候选行
4. 如果体量较大，按交易日期切成三个月一批；每批只交给一个 subagent，并要求 subagent 通过推理输出标记结果，禁止用脚本批量过滤/批量判定
5. 按 `SKILL.md` 中的 pending / `ai_working.csv` 流程处理该文件并保存为编辑后的 CSV
6. 执行 `ft reconcile --continue-with-decisions`
7. 如果要放弃，执行 `ft reconcile --abort`

## 安装

```bash
git clone https://github.com/ssttkkl/finance-tracker.git
cd finance-tracker
uv sync
```

依赖：Python 3.11+, PyYAML, yfinance, openpyxl（PDF 账单需要 qpdf + mutool）

## 许可证

MIT
