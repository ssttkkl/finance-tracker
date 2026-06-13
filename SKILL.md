---
name: finance-tracker
category: finance
description: 使用场景：管理个人财务、导入银行/信用卡/支付宝/微信账单、记录股票买卖、管理多币种资产（USD/CNY/HKD）、验证财务数据一致性
design_spec: docs/superpowers/specs/2026-06-12-csv-only-design.md, docs/superpowers/specs/2026-06-12-stock-trading-design.md, docs/superpowers/specs/2026-06-12-unified-snapshot-design.md, docs/superpowers/specs/2026-06-13-dfzq-stock-converter-design.md, docs/superpowers/specs/2026-06-13-git-transactional-commit-design.md
---

# Finance Tracker

纯 CSV 多币种个人记账工具，支持统一快照查询、股票交易和 Git 版本控制。

## 架构

**CSV 审计日志 + YAML 快照，无数据库。** 所有写操作同时更新两者，所有查询只读快照。`~/.ft/` 自动作为 Git 仓库，每次写入自动 `git add -A`，但**不自动 commit**。用户通过 `ft commit` 手动提交累积的变更（事务型提交）。详见设计文档 `docs/superpowers/specs/2026-06-13-git-transactional-commit.md`。

## 命令速查

### 账单导入流水线（6 步）

```
① convert → ② AI审查转换 → ③ AI修正 → ④ append → ⑤ reconcile → ⑥ commit
```

| 步骤 | 操作 | 产出 |
|------|------|------|
| ① convert | `ft convert <账单> -s alipay|wechat|icbc|ccb-debit -o <csv>` | 统一 CSV + `_refunds.csv` |
| ② AI审查 | 逐项审查（见 `references/review-checklist.md`） | 审查报告 |
| ③ AI修正 | AI 根据审查结果逐项修正 CSV 或转换代码 | 修正后的 CSV |
| ④ append | `ft append <csvs...>` | 落盘到 records/ |
| ⑤ reconcile | `ft reconcile [--month YYYY-MM | --from YYYY-MM-DD --to YYYY-MM-DD]` | 去重 + 审计 CSV |
| ⑥ commit | `ft commit` | Git 提交 |

convert 说明：`alipay`（支付宝 CSV）、`wechat`（微信 xlsx）、`icbc`（工行 PDF，需 --password，自动检测信用卡/借记卡）、`ccb-debit`（建行 xls）。

AI 审查要点：按优先级 **P0(金额影响) > P1(source) > P2(脱敏) > P3(counterparty)** 逐项检查。每个转换后的 CSV 文件独立审查，每文件分配一个 subagent。详细审查清单见 `references/review-checklist.md`。

转换阶段：退款配对数学正确性（全额=0？部分=净额正确？）、source 正确性、数据脱敏、counterparty 规范化。注意 _pair_refunds 产生的孤退款行（orphan income）可能在 CSV 中残留，需检查过滤。

reconcile 阶段：审计文件中每对 dedup_status=保留/去除 行必须成对出现；保留行必须仍存在于 records 中，去除行不应再出现在 records。漏删：同来源+同日+同金额+同 counterparty 的明显重复（注意同日不同时独立交易）。

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

> **月度分析：** Report 的原始数字包含大量资金调拨（银证转账、基金赎回等），不反映真实收支。详见 `references/monthly-analysis.md` 的过滤方法。

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

### Git 版本控制（事务型提交）

| 命令 | 说明 |
|------|------|
| `ft status` | 查看未提交的改动（git status --short） |
| `ft commit [-m "消息"]` | 提交所有 staged 变更。无参时自动生成 `chore: YYYY-MM-DD HH:mm` |
| `ft reset` | 丢弃所有未提交改动，执行前确认提示 |

每次写入操作（`ft append`、`ft reconcile`、`ft add`、`ft stock buy/sell`、`ft transfer`、`ft verify --fix` 等）自动执行 `git add -A`，不自动 commit。`ft commit` 一次性提交所有累积变更。

### 账单字段

每笔交易记录包含以下字段（9列）：

| 字段 | 说明 | 示例 |
|------|------|------|
| `date` | 交易时间，精确到秒 | `2026-01-01 13:00:40` |
| `amount` | 带符号金额，负=支出，正=收入 | `-16.30` |
| `currency` | 币种 | `CNY` / `USD` / `HKD` |
| `counterparty` | 标准化商户名 | `麦当劳` / `渝八两重庆鸡公煲` |
| `description` | 商品/交易说明（80字截断，含品牌提取残留） | `安尔雅家具` / `生椰拿铁` |
| `category` | 收支类型 | `expense` / `income` |
| `account_name` | 账户名（对应 accounts.yaml） | `支付宝余额` |
| `source` | 支付渠道（怎么付的） | `支付宝` / `美团支付` |
| `bill_source` | 账单来源 | `alipay` / `icbc_credit` |

#### counterparty 规范化

从原始账单数据到 `counterparty` 字段经过三级 fallthrough：

1. **品牌匹配** — 命中已知品牌/连锁（瑞幸咖啡、麦当劳、京东等）→ `counterparty` = 品牌名，原始剩余信息（门店/商品）迁移到 `description`
2. **O2O 渠道剥离** — 未命中品牌，但有 O2O 中介前缀（美团App/饿了么/大众点评等）→ 去掉前缀，`counterparty` = 商铺名
3. **原样保留** — 无匹配 → `counterparty` 保持不变

| 原始交易对方 | 原始 description | → counterparty | → description |
|:---|:---|:---|:---|
| 安尔雅家具京东自营旗舰店 | *(空)* | 京东 | 安尔雅家具 |
| 美团App麦当劳麦咖啡(北京武圣 | *(空)* | 麦当劳 | 麦咖啡(北京武圣) |
| luckin coffee | 订单付款 | 瑞幸咖啡 | 订单付款 |
| 美团App渝八两重庆鸡公煲 | *(空)* | 渝八两重庆鸡公煲 | *(空)* |
| 先骑后付 | *(空)* | 美团 | 先骑后付 |
| 先享后付订单到期扣款 | *(空)* | 先享后付订单到期扣款 | *(空)* |
| 北京屏芯科技有限公司 | 工资 | 北京屏芯科技有限公司 | 工资 |

source 是支付渠道（怎么付的），与 counterparty 无关。

- 支付宝/微信账单：source 固定为"支付宝"/"微信"
- 信用卡账单：source 从交易场所前缀推断（美团支付/财付通/京东支付）
- 单笔录入 `ft add`：source 可选，手动指定

## 股票交易

security 类型账户使用独立 CSV 格式，支持 A 股（`159740.sz`）、美股（`mu.us`）、港股（`00700.hk`）。采用平均成本法：买入时加权平均，卖出时均价不变按比例扣减成本。

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

### 证券对账单批量导入

```bash
# 步骤① 转换：券商PDF → stock CSV
ft stock convert 电子对账单.pdf -s dfzq --password 099215 -o dfzq_stock.csv

# 步骤② AI审查：逐条审查转换结果（见 references/stock-convert-review.md）
# 步骤③ 落库：确认后导入 records/security/ + 快照
ft stock append dfzq_stock.csv
```

支持 `-s` 扩展其他券商，转换器存放在 `importers/<source>.py`。
开发新券商转换器参考 `references/stock-convert-dev-prompt.md`。

### 全量刷新（清空 → 重导）

当需要清空已有 cash/loan 数据并替换为新的 records 内容时：

```bash
cd ~/.ft

# ① 确认：检查 records 中的账户是否都在 accounts.yaml 中
python3 -c "
import csv
from pathlib import Path
accts = set()
for p in Path('records').rglob('*.csv'):
    with open(p) as f:
        accts.update(r['account_name'] for r in csv.DictReader(f))
import yaml
with open('accounts.yaml') as f:
    known = {a['name'] for a in yaml.safe_load(f)['accounts']}
missing = accts - known
if missing:
    print('❌ accounts.yaml 缺失:', missing)
else:
    print('✅ 所有账户都存在')
"

# ② 清空 cash/loan
rm records/cash/*.csv records/loan/*.csv

# ③ 追加缺失账户（如 建行储蓄卡(0523) 和 花呗）
ft acct add "建行储蓄卡(0523)" --type cash --currency CNY
ft acct add "花呗" --type loan --currency CNY

# ④ 导入
ft append alipay.csv wechat.csv icbc.csv

# ⑤ 验证
ft verify --fix
```

**重要：** stock/security 数据不受影响，只清理 cash 和 loan。

### 消费账单导入

参见「账单导入流水线（6 步）」一节。
开发新账单源转换器参考 `references/consumption-convert-dev-prompt.md`。

## 已知陷阱

| 问题 | 对策 |
|------|------|
| 借记卡余额为负（CSV 只有消费） | `ft checkin <卡名> --balance <真实余额>` |
| **支付宝「不计收支」方向 — 交易关闭=跳过** | `方向=不计收支` 有三种处理方式（看原始账单 `交易状态` 列）：① **状态=交易关闭**：下单未付款，无实际资金流动，直接 `continue`；② **状态=退款成功**：真实退款，进入 `_pair_refunds` 配对核销；③ **状态=交易成功/转出成功**：余额宝收益/基金转入等，保留。**修复**：`_read_alipay_raw` 中新增 `if direction == "不计收支" and txn_status == "交易关闭": continue`。详细追踪见 `references/alipay-bujizhishou-debug-trace.md` |
| **O2O 平台被误标** | 品牌规则排在平台规则前 |
| **中文子串匹配误伤（京东误中北京东湖渠店）** | _infer_platform 用 kw.lower() in text 做子串匹配，中文无词边界。已知误伤：京东 in 北京东湖渠店→True。淘宝/高德中介平台：品牌匹配后在 intermediary_brands 分支从 desc 提取到分隔符为止，不用 _infer_platform 做二次匹配（也会子串误伤） |
| **snapshot 不一致** | `ft verify --fix` 重建。security 账户统一写入 `accounts.security`，`repair_security` 构建时从 `accounts.yaml` 获取币种 |
| **Normalizer 破坏 ICBC 退款匹配** | `_pair_refunds` 前存 `_raw_cp` + `_is_refund` flag，fallback 匹配。同时过滤掉 `_pair_refunds` 后残留的 cp="消费"/"财付通" orphan income |
| **「退货」sentinel 被 normalizer 吞掉** | ICBC 退款检测依赖 `counterparty == "退货"`，但 normalizer 可能把「退货」归一化为品牌名。修复：调用 normalizer 前检查原始 `counterparty == "退货"`，设 `_is_refund = True` 标志，退款检测改为判断 `r.get("_is_refund")`。**注意：** 退款记录在 `_pair_refunds` 前已经 `_normalize_counterparty` 处理（`_parse_icbc_lines` 中），此时 `_is_refund` 标志已经丢失，需在创建记录时提前打标 |
| **删除/新增列要检查所有 CSV header 硬编码** | 以下文件都有硬编码 header 或列序，容易被漏：`do_convert()`（主输出 + `_refunds.csv`）、`reconcile.py`（审计输出字段）、`dedup.py`（列比较代码）、`ccb_debit.py`（列构造）。改 `CSV_FIELDS` 后必须搜索全项目引用的字段名确认无残留 |
| **convert 输出行数远少于预期** | 优先检查 `mapping.yaml` 是否有未覆盖的 `payment_method`，**不是排查 `_pair_refunds`**。常见遗漏：`工商银行储蓄卡(3697)*`（未加规则时 223 条被 skip）、`余额`、`余额宝*`、`中国建设银行储蓄卡(2820)*`（全称前缀）、`工商银行信用卡(9166)*`。`do_convert` 现已改为无匹配时抛 ValueError 阻断，不再静默 skip。**注意：** `fnmatch.fnmatch("anything", "")` 返回 `False`，`match: ""` 规则只匹配空字符串 |
| **Alipay 同时产生净退款和全额退款（gross refund）** | 支付宝为同一笔退款可能产生两条记录：净退款（如 +83.5）和全额退款（如 +1025.5）。全额退款金额等于原始支出金额，但此时原始支出已被净退款部分消耗。`_pair_refunds` 的孤退款处理器中需要"原始全额匹配"检查：当退款无法匹配任何剩余支出时，检查是否等于某笔支出的原始金额（非剩余金额），是则直接全额核销。已在 `convert.py` `_pair_refunds` 的 `if not candidates:` 分支中实现 |
| **无匹配规则静默 skip → 已改为 ValueError** | `do_convert` 中无 mapping 匹配时原行为是打印警告后 skip（`⚠️ 未匹配规则`），导致数据静默丢失。现已改为抛 `ValueError` 立即阻断，消息包含 source、payment_method、counterparty、amount 和修复指引。`append.py` 中 `account_name` 为空、未知账户、`date` 为空同理改为抛 ValueError |
| **CCB 建行转换：消费-前缀 + 证券转账账号泄露** | `ccb_debit.py` 的 `_extract_ccb_counterparty` 需要处理：① `消费-` 前缀（内部有多层嵌套，`支付宝-支付宝-消费-商户名`，需要连续剥掉所有子前缀）；② 证券转账账号（`银行转证券8888086011314150转入086` → `银行转证券`）。paymemt 子前缀需支持连续匹配（`while True` 循环）、新增 `消费-` 作为子前缀、"云闪付" 来源检测 |
