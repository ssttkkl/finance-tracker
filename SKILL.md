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

### 账单导入流水线（5 步）

```
① convert → ② append → ③ reconcile → ④ AI审查/编辑 working CSV → ⑤ commit
```

| 步骤 | 操作 | 产出 |
|------|------|------|
| ① convert | `ft convert <账单> -s alipay|wechat|icbc|ccb-debit -o <csv>` | 统一 CSV；保留退款关系元数据 |
| ② append | `ft append <csvs...>` | 落盘到 records/ |
| ③ reconcile | `ft reconcile [--month YYYY-MM | --from YYYY-MM-DD --to YYYY-MM-DD]` | 去重结果或 pending reconcile 会话 |
| ④ AI审查/编辑 | 如进入 pending，编辑 `ai_working.csv` 后执行 `ft reconcile --continue-with-decisions <edited.csv>` | 正式 records + audit |
| ⑤ commit | `ft commit` | Git 提交 |

convert 说明：`alipay`（支付宝 CSV）、`wechat`（微信 xlsx）、`icbc`（工行 PDF，需 --password，自动检测信用卡/借记卡，**支持多卡路由** — 见下方"ICBC 信用卡 PDF 内含多卡交易"陷阱）、`ccb-debit`（建行 xls）。

> **转换器路径注意：** `which ft` 指向的可能是 hermes-agent venv 中的 pip 安装版（`/Users/huangwenlong/.hermes/hermes-agent/venv/bin/ft`），而开发版在 `~/bin/ft`（uv run 模式）。开发版用了修改后的 `convert.py`（补丁/新功能），安装版用的是旧代码。**修改转换器代码后必须确保 `ft` 命令用的是开发版**。推荐用 `~/bin/ft`，或用 `PYTHONPATH` 指向开发目录后通过 venv python 调用。

AI 审查要点：按优先级 **P0(金额影响) > P1(source) > P2数据脱敏 > P3 counterparty** 逐项检查。每个转换后的 CSV 文件独立审查，每文件分配一个 subagent。详细审查清单见 `references/review-checklist.md`。跨支付渠道排查“状态/方向/中性交易”类转换器问题时，按 `references/payment-statement-direction-audit.md` 先只读重转到 `/tmp`、统计风险类、再和 records 精确匹配；不要把历史导入范围差异直接当 bug。

### 退款核销目标

退款自动核销的最高优先级是：

- 最终净额正确
- 账户余额正确
- 消费统计正确

在满足以上三点时，**允许对同类多候选消费采用保守的近邻归并**，不强求严格回链到唯一原单。

执行时遵循以下边界：

- 优先避免漏掉退款，导致净支出偏高
- 优先避免把退款核销到不同消费类型、不同账户或错误金额
- 对同商户 / 同平台 / 同类消费中的多候选退款，只要最终核算结果正确，可接受不精确回挂到唯一原单

### Pending / ai_working.csv 标准处理流程

如果命令进入 pending，会生成 `~/.ft/pending/.../<session_id>/ai_working.csv`。CLI 只负责提示你去看 `SKILL.md`；真正的审查细则以这里为准。

标准处理步骤：

1. 运行 `ft convert`、`ft append` 和 `ft reconcile`
2. 若 reconcile 进入 pending，打开会话目录下的 `ai_working.csv`
3. 先保留 `ai_working.csv` 作为原始底稿，不要直接在原文件上覆盖修改
4. 审查对象是**整份 `ai_working.csv`**，不要只看局部候选行或只看程序预标记区域
5. 复制出 `edited.csv`，再按下面的编辑协议审查并修改允许编辑的列
6. 如果是体量较大的 pending，先按交易日期切成 **三个月一批** 分别审查；每批只交给一个 subagent，再由主调用方合并所有批次结果生成最终 `edited.csv`
7. 执行 `ft reconcile --continue-with-decisions edited.csv`
8. 若放弃本次会话，执行 `ft reconcile --abort`

补充决策规则：

- `leave_as_is` 只能表示**已经审查过且明确决定保留原样**
- **禁止**把“暂时判断不了 / 不想承担判断 / 需要用户拍板”的情况直接写成 `leave_as_is`
- **禁止**因为程序预填了 `ai_reason=...:drop`、`...:keep`、`rule_hint`、`ai_group`，就把它们直接当成最终审查结论批量落盘；这些字段只能当线索，不能替代逐组审查
- 对于证据不足、高风险或存在多种合理解释的候选，必须先整理成“待用户选择”的候选组，由调用方明确拍板后，才能继续 `reconcile`
- 如果整份 `edited.csv` 里所有候选都保持 `leave_as_is`，调用方必须先自检：这是“逐组审查后的明确保留结论”，还是“实际上没有完成审查”。后者禁止 continue

硬性要求：

- **禁止为了跑通流程而对未修改的 `ai_working.csv` 直接原样 continue**
- **禁止把审查后的文件直接覆盖回原始 `ai_working.csv`，否则 continue 校验时会丢失“原稿 vs 编辑稿”的差异**
- **大体量 pending，尤其 `reconcile`，禁止让单个 subagent 一次性审全量；必须按三个月切批后分别审查**
- **subagent 禁止用脚本批量过滤/批量判定；只能用推理给出标记结果，脚本最多用于切批或复制文件**
- **AI 必须对 `edited.csv` 中保留的每一行给出结论**（drop / leave_as_is / modify / 配对类动作之一）；禁止只阅读少量样本后，用脚本按关键词或现成 hint 批量改写大批记录
- **禁止**把“不确定 / 高风险 / 多候选”默认落成 `leave_as_is` 后直接 continue；这类候选必须先升级给用户选择
- **禁止**仅依据 `ai_reason` 里自带的 `:drop/:keep` 后缀，批量把整批候选改成 `drop` 或 `leave_as_is`；必须先验证这些 hint 是否在本轮审查边界内成立
- 必须保持：`ai_working.csv` = 原始底稿，`edited.csv` = AI 审查后的结果

### AI working CSV 编辑协议

调用方 AI 必须遵守：

- 保留所有行，不得新增/删除 `record_id`
- 不修改只读字段：`record_id`、`source_record_id`、`session_id`、`date`、`amount`、`currency`、`bill_source`、`raw_*`、`ai_reason`
- 主要编辑列：`counterparty`、`description`、`category`、`account_name`、`source`、`transfer_account`、`locked`、`row_status`、`ai_action`、`ai_group`、`decision_reason`
- `drop` / `modify` / 引用型 `ai_action` 必须填写 `decision_reason`；活跃分组的 `keep` / `leave_as_is` 也必须填写

合法 `ai_action`：

- `leave_as_is`
- `keep`
- `drop`
- `modify`
- `merge_refund_into:<record_id>`
- `net_with:<record_id>`
- `mark_transfer_out_to:<record_id>`
- `mark_transfer_in_from:<record_id>`

### 调用方 AI 审查提示词模板

可直接把下面这段作为外部 AI 的工作提示词，只替换文件路径和当前阶段：

```text
你正在审查 finance-tracker 的 pending 工作底稿 ai_working.csv。

目标：只在证据充分时修改允许编辑的列；不要新增或删除行；不要修改只读字段；所有最终决策都要写 decision_reason。

请按以下顺序处理：
1. 先通读整份 ai_working.csv，审查所有 active 行，不要只看局部候选或局部模式。
2. 仅修改这些允许编辑的列：counterparty、description、category、account_name、source、transfer_account、locked、row_status、ai_action、ai_group、decision_reason。
3. 严禁修改只读列：record_id、source_record_id、session_id、date、amount、currency、bill_source、raw_counterparty、raw_description、raw_payment_method、ai_reason。
4. 如果当前文件体量较大，按交易日期切成三个月一批；每批只交给一个 subagent 审查。
5. subagent 禁止用脚本批量过滤、批量判定或自动打标；必须通过推理给出标记结果。脚本最多只能用于切批、复制文件或合并已得出的结果。
6. 审查完成的标准不是“看过几条代表样本”，而是**本批次中每一行都已经有明确结论**。任何保留在 `edited.csv` 里的行，都必须对应一个逐行审查后的动作。
7. 如果判断应保留原样，写 `keep` 或 `leave_as_is`，并填写 decision_reason。这里只能用于“已完成审查且明确决定保留”的行，不能把“不确定”伪装成保留。
8. 如果判断应删除，写 drop，并填写 decision_reason。
9. 如果判断应合并/配对，使用合法 ai_action（merge_refund_into:<record_id> / net_with:<record_id> / mark_transfer_out_to:<record_id> / mark_transfer_in_from:<record_id>），并填写 decision_reason。
10. `ai_reason` 中若出现 `...:drop`、`...:keep`，只能视为程序提示，不得直接批量照抄成最终结论；必须逐组确认该提示是否仍然成立，尤其要检查 refund、社交转账、二维码收款、date-only、multi-candidate 等边界。
11. 如果存在证据不足、高风险或多候选、而你又无法明确做出 `drop/modify/配对/明确保留` 结论的组，不要默认写成 `leave_as_is` 并继续；先把这些组整理给调用方或用户选择。
12. 修改完成后保存为 `edited.csv`，不要覆盖原始 `ai_working.csv`；只有当本轮需要拍板的组已经被明确处理，且本批次保留下来的每一行都已完成逐行审查后，才执行 continue 命令；如果无法完成本轮决策，则不要继续执行，由调用方选择继续补审、让用户拍板或 abort。
13. 严禁把未修改的 `ai_working.csv` 直接拿去 continue；这会绕过 AI 审查流程，结果不具备审查意义。
```

转换阶段：重点检查退款配对数学正确性（全额=0？部分=净额正确？）、source 正确性、数据脱敏、counterparty 规范化。注意 _pair_refunds 产生的孤退款行（orphan income）可能在 CSV 中残留，需检查过滤。

**关键：每转完一个文件就停下来让用户确认，不要一次转完所有文件再统一审查。**

reconcile 阶段：审计文件中每对 dedup_status=保留/去除 行必须成对出现；保留行必须仍存在于 records 中，去除行不应再出现在 records。漏删：同来源+同日+同金额+同 counterparty 的明显重复（注意同日不同时独立交易）。

**合并审查（步骤 ⑤）**：reconcile 后按 6 个月一批分给 subagent 并行审查（见 `references/reconcile-transfer-leakage-audit.md`）。每个 subagent 只看自己那一批，不能跨批次做全局判断；主调用方负责把各批次修改合并回同一个最终 `edited.csv`。审查输出保持极简：不要逐条展开所有“无需标注”，只统计无需标注数量/置信度分布；明细只列“未标注”和“误标注”，每条给 `置信度 + 判断 + 最小定位信息`。

### 账户管理

| 命令 | 说明 |
|------|------|
| `ft acct add <名称> --type cash|loan|lend|security|crypto --currency CNY|USD|HKD` | 新增 |
| `ft acct list` | 列表+余额 |
| `ft acct rename|delete|activate|deactivate` | 管理 |

#### 加密货币账户（crypto）

`crypto` 类型账户复用 security 引擎（同 snapshot 桶、同 `records/security/` CSV、同 `ft stock` 命令）。运营模型为稳定币计价：**USDT=现金（1:1 USD），BTC/ETH 等为持仓，成本 USD 计价**。价格走 CoinGecko（honor `HTTP_PROXY`，失败显示 N/A）。新增币种在 `models.py` 的 `CRYPTO_IDS` 补一行 `symbol → CoinGecko id`（已内置 btc/eth/usdt/usdc/sol/bnb/xrp/doge/ada）。crypto 账户统一用 USD。

```bash
ft acct add 币安 --type crypto --currency USD
ft stock deposit --amount 5000 --account 币安              # 充 USDT(现金)
ft stock buy  --ticker btc --shares 0.05 --price 60000 --account 币安
ft stock sell --ticker eth --shares 1    --price 3000  --account 币安
ft stock list                                             # BTC/ETH 自动走 CoinGecko
```

### 查询

| 命令 | 说明 |
|------|------|
| `ft report [--month YYYY-MM]` | 资产负债 + 消费 + 收入 + 转账 |
| `ft list [--month|--account|--category|--limit]` | 交易明细 |

> **月度分析：** Report 的原始数字包含大量资金调拨（银证转账、基金赎回等），不反映真实收支。`report.py` 目前也**没有净收入指标**（income−expense），且收支求和不排除 transfer 污染。做月度收支/净收入/趋势分析或计划改 report 前，先看 `references/monthly-analysis.md`（含过滤方法、污染规模基线、关键词匹配假阳性陷阱——**绝不能对 account_name 做关键词匹配**、结构性缺陷与 TDD 落地路线）。转账识别分**配对型**（reconcile 原有，两腿都在 ft）与**单腿型**（对手方是基金公司/购汇/货基，永远配不上对，靠 `transfer_rules.py` 规则识别）；单腿转账的规则设计、真实数据假阳性陷阱（收益发放=真收入、蚂蚁搬家=真消费、desc='充值'全是假阳性）、TDD+预演落库流程见 `references/single-leg-transfer-detection.md`。

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

### Reconcile transfer leakage audits

When auditing `ft reconcile` for missed internal transfers, use the batch-review/TDD/dry-run workflow in [`references/reconcile-transfer-leakage-audit.md`](references/reconcile-transfer-leakage-audit.md). Key rule: split existing data into 6-month read-only review batches, integrate high-confidence cases, write RED tests first, then implement minimal rules, and dry-run on a copied ledger before touching real `~/.ft`.

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
| `transfer_account` | 内部转账对手账户（转账行才有） | `微信零钱` |
| `locked` | 人工锁定标记；`1`=reconcile 完全不碰此行（不去重、不配对、不单腿标记，仅原样写回）。`ft transfer` 手动写入的行自动 `locked=1` | `1` / 空 |

> **reconcile 幂等性**：CSV 为 11 列。`locked=1` 的行被 reconcile 完全跳过，因此重复 `ft reconcile` 不会重标已处理行，也不会覆盖人工修正——多次执行收敛、无 git diff。设计/实现/存量迁移/真实数据验证/回归隔离纪律见 `references/reconcile-idempotency-locked-column.md`。

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

security 类型账户使用独立 CSV 格式，支持 A 股（`159740.sz`）、美股（`mu.us`）、港股（`00700.hk`）。采用平均成本法：**买入时总成本相加摊均价，卖出时总成本减去净回收资金（price × shares - commission），剩余均价随之变化**。卖出亏损 → 均价上升，卖出盈利 → 均价下降。

### 平均成本计算示例

```
买入前: 8股 × $954.75 = $7,638.02
买入 2股 @ $900 → 总成本 = $7,638 + $1,800 = $9,438, 均价 = $9,438 / 10 = $943.80
卖出 2股 @ $969 → 回收 = $1,938, 总成本 = $9,438 - $1,938 = $7,500, 均价 = $7,500 / 8 = $937.50（卖赚了，均价↓）
卖出 2股 @ $900 → 回收 = $1,800, 总成本 = $7,500 - $1,800 = $5,700, 均价 = $5,700 / 6 = $950.00（卖亏了，均价↑）
清仓 6股 @ $950 → 回收 = $5,700, 总成本 = $5,700 - $5,700 = $0（回收等于投入，成本归零）
清仓 6股 @ $1,000 → 回收 = $6,000, 总成本 = $5,700 - $6,000 = -$300（负成本，已收回本金+盈利）
```

### 做空（Short Selling）

`ft stock sell` 在无持仓时会**自动创建负股数（做空）**，而非报错。做空后可用 `ft stock buy` 平仓（减少负股数靠近 0）。

做空 avg_cost 计算示例：
```
做空前: 0 股
沽空 5 股 @ $220 → shares=-5, 总成本 = -(5×$220)=-$1,100, avg_cost=$220（正数=做空价）
回补 2 股 @ $215 → shares=-3, 总成本 = -$1,100 + (2×$215)=-$670, avg_cost=-$670/-3=$223.17（含佣金则略偏移）
```

要点：
- 做空 SELL 产生负 shares，avg_cost 为正数（做空价格）
- 平仓 BUY 减少负 shares 的绝对值，剩余做空均价上移/下移取决于平仓价
- 做空记录参与 `verify --fix` 全量重放和 snapshot 校验
- `repair_security` 保留负 shares（不再 `<= 0: continue`）

`ft stock buy/sell` 自动扣减/增加现金并更新持仓，`ft stock checkin` 用于初始导入或校正持仓/现金（不涉及现金变动，支持 Polymarket 这类 fractional shares）。**把一个混着多券商持仓的 security 账户拆成多个真实券商账户**（同一只票分散在多家券商、股数对不上单一 App）的完整流程见 `references/split-security-account.md`（含：从盈亏反推均价、股数闭合核对、checkin 无法归零持仓需手改 snapshot.yaml、已清仓票记卖出留痕）。外部平台开户同步命令采用可扩展层级 `ft stock sync <provider>`，不要再新增 `sync-xxx` 平铺命令；变更命令形状时先写 CLI RED 测试覆盖新路径分发和旧路径移除，再更新 help/文档并用真实 `--help` + `--dry-run` 验证。`ft stock sync polymarket --wallet <profile_wallet>` 可通过 Polymarket 公开 Activity API 增量同步官方成交记录：先校验目标账户是 USD security 账户，再解析 proxy wallet、拉取 `type=TRADE` activity；既有 `transactionHash` 在同一 `account_name` 内幂等跳过，fresh batch 中同 tx 只折叠 exact duplicate rows（避免丢同一链上 tx 的多 fill/多 market 成交），再写入标准 stock CSV/records；也可用 `--proxy-wallet <0x...>` 跳过 profile 解析、`--account <账户名>` 指定另一个 Polymarket security 账户、`--dry-run` 只预览、`-o <csv>` 输出待导入 CSV。执行用户要求的 Polymarket 同步时，推荐顺序是：先 `--dry-run` 确认 `new rows`，再跑真实写入（即使 dry-run 显示 0，也可跑真实命令验证“没有新增”），随后必须跑 `ft verify`，再用带代理的 `ft stock list` 报最新持仓。同步命令会在 stdout 打印 proxy wallet；最终回复和日志摘要中一律把 wallet/proxy wallet 写成 `[REDACTED]`，不要复述真实地址。用户明确要求中文沟通，财务/Polymarket 汇报默认用中文、先给结果数字和是否写入，再给必要明细。`ft stock list` 实时拉取 yfinance 市值；Polymarket 持仓支持 `pm:<slug>:yes|no` 伪 ticker，并通过 Polymarket gamma API 拉取最新报价，且会保留小数股数显示。账户/报表估值会按当前市价计算，不再只看成本价。Polymarket 持仓的具体约定见 `references/polymarket-holdings.md`（含负现金解释/校正规则）；内置增量同步命令的设计/测试/维护要点见 `references/polymarket-sync-feature.md`；实现/审查任何 security 外部同步或 stock import 功能时，先按 `references/security-sync-hardening-checklist.md` 检查账户定位、去重、mixed CSV schema、原子写入和回滚测试；用公开 Data API 从钱包 Activity 全量刷新历史交易见 `references/polymarket-public-history-import.md`；用官方 Polymarket Activity 全量替换手工/半自动记录的详细流程见 `references/polymarket-official-activity-import.md`（解析 proxy wallet、优先 `/activity` 而非 `/trades`、备份不要放进 `~/.ft` git、保留换汇审计行但补 `stock deposit` 供 security replay 计现金）；重复导入去重流程见 `references/polymarket-import-dedupe.md`；Gamma 字段兼容坑见 `references/polymarket-gamma-field-quirks.md`；security 校验的转账审计行/浮点残差坑见 `references/security-verify-pitfalls.md`；完整做空迁移记录见 `references/short-selling-support.md`。长时间多轮 Codex/review 后，向用户汇报时先给“到底改了啥”的短 changelog：用户可见功能、关键安全修复、验证命令/结果、是否写入真实数据；不要先展开冗长过程叙事。 

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

# Polymarket 官方 Activity 增量同步（先 dry-run 看新增数；wallet/proxy_wallet 可放 ~/.ft/credentials.yaml 的 polymarket 段）
ft stock sync polymarket --dry-run
ft stock sync polymarket
# 也可临时覆盖 credentials.yaml 中的地址
ft stock sync polymarket --wallet 0xYourProfileWallet --dry-run
ft stock sync polymarket --proxy-wallet 0xYourProxyWallet --dry-run -o /tmp/polymarket_new.csv

# 加密交易所成交同步（ccxt 私有 API，先 dry-run；凭证在 ~/.ft/credentials.yaml）
ft stock sync kraken --account 币安 --dry-run --since 2026-01-01 --symbol BTC/USDT
ft stock sync okx --account OKX -o /tmp/exchange_new.csv

# 手工币币兑换（持仓换持仓，成本结转，不碰现金）
ft stock swap --account 币安 --from-ticker btc --from-shares 0.5 --to-ticker eth --to-shares 10

# 查询
ft stock list
```

#### 持仓成本 / 现价 / 市值 / 盈亏查询

当用户问“持仓成本”“市值、成本、现价、盈亏”这类只读查询时：
1. 先运行 `ft verify`，确认 Security CSV ↔ Snapshot 对齐；不要在校验失败时直接报数。
2. 再运行 `HTTP_PROXY=${HTTP_PROXY:-http://127.0.0.1:7890} HTTPS_PROXY=${HTTPS_PROXY:-http://127.0.0.1:7890} ~/bin/ft stock list` 拉取实时估值。
3. `ft stock list` 表格通常显示：股数、均价、成本、市值、盈亏、涨幅；若用户要求“现价”，用 `现价 = 市值 / 股数` 计算，不要把均价误当现价。
4. 按账户/币种分组展示，**不要跨币种合计盈亏**；每组可列：标的、股数、现价、成本价、总成本、市值、盈亏、盈亏率。
5. 对 `成本=0` 或 `负成本` 的标的，说明盈亏率可能无参考意义，这是平均成本法/历史卖出摊低成本导致，不一定是错误。
6. 当用户质疑“股数/市值看着不对”“是不是有脏数据”时，不要只重复 `ft stock list`。按 `references/security-position-sanity-audit.md` 做专项排查：扫描 zero-cost/negative-cost/tiny-shares，追溯 records 来源，Polymarket 用官方 `data-api.polymarket.com/positions` 对比当前真实持仓，另扫 security CSV 币种字段与账户币种不一致。

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

**⚠️ 用户偏好：每一步执行完必须停下来让用户确认，不要一次性跑完全部流程。** 涉及数据删除的 destructive 操作更需逐级确认。

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

> **⚠️ snapshot 残留意：** `ft verify --fix` 是**增量重建**，不会自动删除 snapshot 中已无 CSV 记录的 cash/loan/lend 段。如果只清空不复重载（永久删除 cash/loan 数据），需要**额外手动清理 snapshot.yaml**：
> ```bash
> cd ~/.ft
> python3 -c "
> import yaml
> with open('snapshot.yaml') as f:
>     data = yaml.safe_load(f)
> data['accounts'] = {'security': data['accounts'].get('security', {})}
> data['updated_at'] = '2026-06-13'
> with open('snapshot.yaml', 'w') as f:
>     yaml.dump(data, f, allow_unicode=True, default_flow_style=False, sort_keys=False)
> "
> git add snapshot.yaml && git commit -m "chore: 清理snapshot中已删除的cash/loan段"
> ```
> 注意：如果后续还要重载新数据（清空 → 导入 → `ft verify --fix`），则 snapshot 会随新导入自动重建，**不需要**手动清理。

### 证券记录清空重导

**⚠️ 用户偏好：每一步执行完必须停下来让用户确认，不要一次性跑完全部流程。**

无需清空整个 records 目录，只需删除指定账户名的行。典型的触发场景：某券商转换器修 bug 后需要清除旧数据用新转换器重导。

```bash
# ① 备份
cp -r ~/.ft/records/security ~/.ft/records/security.bak

# ② 清除指定账户（如 东方证券）的所有记录
python3 -c "
import csv, os
records_dir = os.path.expanduser('~/.ft/records/security/')
for fn in sorted(os.listdir(records_dir)):
    if not fn.endswith('.csv'): continue
    fpath = os.path.join(records_dir, fn)
    with open(fpath, 'r', newline='', encoding='utf-8') as f:
        rows = list(csv.reader(f))
    if not rows: continue
    try:
        idx = rows[0].index('account_name')
    except ValueError:
        continue
    kept = [r for r in rows if idx >= len(r) or r[idx] != '东方证券']
    removed = len(rows) - len(kept)
    if removed == 0: continue
    with open(fpath, 'w', newline='', encoding='utf-8') as f:
        csv.writer(f).writerows(kept)
    print(f'{fn}: removed {removed} rows')
"

# ③ 用修复后的转换器重导
ft stock convert 电子对账单.pdf -s dfzq --password <password> -o /tmp/clean.csv
ft stock append /tmp/clean.csv

# ④ 验证
ft verify --fix
ft stock list
```

注意：转换器代码修改后需清除 `__pycache__` 再运行：
```bash
find ~/.hermes/skills/finance/finance-tracker/src/ft/importers/__pycache__/ -name "*dfzq*" -delete
```

### 消费账单导入

参见"账单导入流水线（6 步）"一节。
开发新账单源转换器参考 `references/consumption-convert-dev-prompt.md`。

## 已知陷阱

| 问题 | 对策 |
|------|------|
| 借记卡余额为负（CSV 只有消费） | `ft checkin <卡名> --balance <真实余额>` |
| **支付宝「交易关闭/已关闭/还款失败」全方向跳过；不计收支转入/买入/转出=余额流出** | 支付宝 `交易状态` 优先于 `收/支` 方向：① **状态=交易关闭/已关闭/还款失败**：无资金流动，不管 `收/支=支出/收入/不计收支` 都 `continue`；② `方向=不计收支` 且 **金额=0**（预授权/0元退款/解冻等）：无资金流动，`continue`；③ **交易分类=账户提现** 或 **商品说明含 `提现-实时提现`**：支付宝余额提现到银行卡，从 `支付宝余额` 账户视角转为负数 `expense`，后续 reconcile 与银行入账腿配成 `transfer_out/transfer_in`；④ **转出到网商银行**：从支付宝余额视角为负数 `expense`；⑤ **投资理财转入/买入/单次转入**（排除 `收益发放`、`转出到银行卡`）：付款账户流出，转为负数 `expense`；⑥ **退款成功**：真实退款，进入 `_pair_refunds` 配对核销；⑦ **收益发放/转出到银行卡**：保留正数 income。每次修改后用下载目录原始支付宝账单全量扫描 `交易状态` + `收/支`，确认 closed/failed/skip/outflow 规则没有残留。详细追踪见 `references/alipay-bujizhishou-debug-trace.md`；契约冲突裁定见 `references/reconcile-idempotency-locked-column.md` 后半段 |
| **O2O 平台被误标** | 品牌规则排在平台规则前 |
| **中文子串匹配误伤（京东误中北京东湖渠店）** | _infer_platform 用 kw.lower() in text 做子串匹配，中文无词边界。已知误伤：京东 in 北京东湖渠店→True。淘宝/高德中介平台：品牌匹配后在 intermediary_brands 分支从 desc 提取到分隔符为止，不用 _infer_platform 做二次匹配（也会子串误伤） |
| **yfinance 混合市场拉价导致 NaN** | 不要把 `.US`、`.SZ`、`.SS`、`.HK` 混在同一个 `yf.download(...)` 批次里。按市场拆组后分别拉价；如果批量结果仍有缺失，再对单票做 fallback 重试。详见 `references/yfinance-market-grouping.md` |
| **snapshot 不一致** | `ft verify --fix` 重建。security 账户统一写入 `accounts.security`，`repair_security` 构建时从 `accounts.yaml` 获取币种。若同名账户在 `accounts.yaml` / `snapshot.yaml` 出现不同币种，先删掉脏重复条目，再用 `ft acct list` + `ft stock list` 复核。详见 `references/account-currency-hygiene.md` |
| **证券账户参与 `ft transfer` 会在 `records/security/` 写入转账审计行** | `ft transfer --from/--to <security账户>` 可能生成 cash-style header（`date,amount,currency,...,transfer_account`）的 CSV 放在 `records/security/`，不是 stock header（`date,action,ticker,...`）。`verify_security` / `_replay_security_csv` 必须跳过没有 `action` 或 `ticker` 字段的行，否则会 `KeyError: 'action'`。记跨币种换汇时先用 `ft transfer --from 东方证券 --to Polymarket --amount 5000 --to-amount 735.29 ...`，再跑 `ft acct list` 与 `ft verify` 复核。**日期参数坑：** 当前 `ft transfer --date` 只应传 `YYYY-MM-DD`，不要传完整 `YYYY-MM-DD HH:MM:SS`；否则 `transfer.py` 会用完整字符串作为文件名，生成 `records/security/2026-06-30 10:16:09.csv`，且记录日期会变成 `2026-06-30 10:16:09 10:16:15`。若已发生，需把这两条 transfer 行搬回 `records/security/YYYY-MM-DD.csv` 并删除带时分秒的文件。 |
| **Normalizer 破坏 ICBC 退款匹配** | `_pair_refunds` 前存 `_raw_cp` + `_is_refund` flag，fallback 匹配。同时过滤掉 `_pair_refunds` 后残留的 cp="消费"/"财付通" orphan income |
| **「退货」sentinel 被 normalizer 吞掉** | ICBC 退款检测依赖 `counterparty == "退货"`，但 normalizer 可能把「退货」归一化为品牌名。修复：调用 normalizer 前检查原始 `counterparty == "退货"`，设 `_is_refund = True` 标志，退款检测改为判断 `r.get("_is_refund")`。**注意：** 退款记录在 `_pair_refunds` 前已经 `_normalize_counterparty` 处理（`_parse_icbc_lines` 中），此时 `_is_refund` 标志已经丢失，需在创建记录时提前打标 |
| **删除/新增列要检查所有 CSV header 硬编码** | 以下文件都有硬编码 header 或列序，容易被漏：`do_convert()`（主输出 + `_refunds.csv`）、`reconcile.py` 的 **`_write_audit()` 里独立的 `fields` 列表**（不是 `CSV_FIELDS`，改列后要在这个列表对应位置手动插入新列名，否则 `DictWriter` 抛 `ValueError: dict contains fields not in fieldnames`）、`dedup.py`（列比较代码）、`ccb_debit.py`（列构造）、`transfer.py`（`new_row` dict）。改 `CSV_FIELDS` 后必须搜索全项目引用的字段名确认无残留。同时存量 records CSV（1590+ 个文件）需批量迁移加新列，见 `references/reconcile-idempotency-locked-column.md` |
| **convert 输出行数远少于预期** | 优先检查以下顺序：① `mapping.yaml` 是否有未覆盖的 `payment_method`；② **转换器代码中是否有静默丢弃逻辑**（`continue` 或 `return None` 跳过非预期数据）。常见遗漏：`工商银行储蓄卡(3697)*`、`余额`、`余额宝*`、`中国建设银行储蓄卡(2820)*`（全称前缀）、`工商银行信用卡(9166)*`。`do_convert` 现已改为无匹配时抛 ValueError 阻断，不再静默 skip。**注意：** `fnmatch.fnmatch("anything", "")` 返回 `False`，`match: ""` 规则只匹配空字符串。详见 `references/convert-no-silent-drop.md`。 |
| **mapping.yaml 和 accounts.yaml 修改后先提交再执行 destructive git 操作** | `mapping.yaml` 和 `accounts.yaml` 的改动容易被破坏性 git 操作（`git reset --hard`、`git stash`）丢失。**在 `git reset --hard` 或 `git stash` 前，必须先确保 mapping.yaml 和 accounts.yaml 已提交**（`git add mapping.yaml accounts.yaml && ft commit -m "chore: update mapping/accounts"`）。否则映射规则丢失后重新补全极其耗时。 |
| **Alipay 同时产生净退款和全额退款（gross refund）** | 支付宝为同一笔退款可能产生两条记录：净退款（如 +83.5）和全额退款（如 +1025.5）。全额退款金额等于原始支出金额，但此时原始支出已被净退款部分消耗。`_pair_refunds` 的孤退款处理器中需要"原始全额匹配"检查：当退款无法匹配任何剩余支出时，检查是否等于某笔支出的原始金额（非剩余金额），是则直接全额核销。已在 `convert.py` `_pair_refunds` 的 `if not candidates:` 分支中实现 |
| **微信中性交易（零钱提现/充值/零钱通/理财通/信用卡还款）不能按金额正负判断** | 微信导出账单的 `收/支` 列对这些交易标记为 `/`，且 `金额(元)` 始终是正数。`_read_wechat_raw` 必须先提取 `txn_type`/`payment_method`/`counterparty`/`desc`/`date_raw`/`date_str`，再按 `交易类型` 判方向：`零钱提现`=目标银行卡入账 income；`零钱充值`/`充值`=付款账户流出 expense；`购买理财通`=付款账户流出 expense；`信用卡还款`=付款账户流出 expense；`零钱通存取`/`理财通` 需按 `转出/取出/赎回/到账` 判 income，否则 expense。微信 `收入 + 当前状态=已收钱` 是真实二维码收款，`INCOME_OK` 必须包含 `已收钱`，否则漏收入。修复后用下载目录微信原始 xlsx 复扫风险类（`收入/已收钱` 与 `/` 中性交易），再和 records 精确匹配确认 `missing_after_fix=0`。详见 `references/wechat-neutral-txns.md`。 |
| **支付宝 0 元交易（会员卡抵扣/积分兑换）被跳过** | 支付宝导出中的 `哈啰骑行卡抵扣` 等 0 元交易是合法记录（会员卡覆盖了费用，无实际资金变动），原 `if amount == 0: continue` 静默丢弃。**修复**：改为保留 0 金额记录，category 按 `收/支` 方向判断（支出→expense，收入→income）。注意 `category = "expense" if amount < 0 else "income"` 这条语句会覆盖已设置的 category，需用 `if amount != 0:` 包裹跳过 0 元交易。 |
| **ICBC 借记卡转换：日期被归一为 00:00:00，时间漏到 description** | `_parse_icbc_lines` 借记卡分支（`is_credit=False`）存在双重 bug：① 第 762 行 `date` 硬编码 `"00:00:00"`（对比信用卡分支正确提取了时间行）；② description 提取逻辑把时间字符串 `17:25:13`（≤10 字符且不在排除列表）误当作摘要捕获。**修复**：日期行后的下一行按 `\d{2}:\d{2}:\d{2}` 匹配提取为时间（fallback `00:00:00`），description 提取排除时间模式。修后需从原始 PDF 重新转换才能恢复历史数据的时间。详细调试追踪见 `references/icbc-debit-date-bug.md`。**注意：建行 CCB 借记卡也有 00:00:00，但那是数据源限制（XLS 只有 YYYYMMDD 无时间字段），不是 bug。** |
| **退款 CSV 的 account_name 要单独修正** | `ft convert` 生成的 `_refunds.csv` 使用与主 CSV 相同的 `account_name`，但当你用 `sed` 修正主 CSV 的 account_name（如 `工行信用卡` → `工行信用卡(1200)`），**退款 CSV 不会被自动修**，需单独跑一次 `sed`。主 CSV + 退款 CSV 都要修正后才能 `ft append`，否则 append 会抛出 `账户 'xxx(CNY)' 不存在` 的错误（错误信息中将货币代码拼接到账户名上）。 |
| **ICBC 信用卡 source 检测几乎缺失（98%+ 为"银行卡"）** | ICBC 信用卡转换器的 source 推断只覆盖了描述中含特定关键词的交易（京东→网银在线、描述带平台信息→支付宝/抖音/Apple Pay 等），但绝大部分交易（约 98%）被标为"银行卡"。京东(132行)、美团(118行)、拼多多(37行)等平台商户也未被正确推断。这是转换器代码层面的限制，非 mapping 问题。需要修改 `convert.py` 的 `_infer_payment_source` 函数或 `_parse_icbc_lines` 中的描述提取逻辑才能修复。当前可用作参考但不阻塞导入。 |
| **ICBC 信用卡 PDF 内含多卡交易 — 用 card_number 路由分流** | 工行信用卡历史明细 PDF 可能包含**多张卡**的交易记录（如主卡 `6225990041051200` 和附卡 `5276610034020851` 出现在同一份 PDF 中）。转换器已提取 `card_number`（卡号后4位）并尝试按 `{bill_type}_{card_num}` 匹配 mapping 规则（见 `do_convert` 第1133行）。例如卡号 1200 的规则是 `source: icbc_credit_1200`，0851 的规则是 `source: icbc_credit_0851`。**mapping.yaml 中需为每张卡分别添加规则**，否则全部走通用的 `icbc_credit` fallback 路由到同一账户。检测方法：`pdftotext PDF -upw PASS - | grep -oE '\d{16}' | sort -u` 可列出 PDF 中所有卡号。 |
2. **做空 SELL 后 snapshot 保留负股数（不再是跳过负股数）** | 2026-06-26 起 `repair_security()` 改为 `if p[\\\"shares\\\"] == 0: continue`（之前是 `<= 0`），做空产生的负股数会写入 snapshot。负股数的 avg_cost = 总成本 / 负股数 = 正数（做空均价）。手动查仓：`python3 -c \\\"import yaml; d=yaml.safe_load(open('snapshot.yaml')); print(d['accounts']['security']['IBKR']['positions'])\\\"` |
| **`ft stock checkin --cash` 的 CSV record 默认用 USD 币种** | 2026-06-28 发现：`ft stock checkin --account 港股证券 --cash 15049` 在 records/security CSV 中写入 `USD`（硬编码），即使账户是 HKD/CNY。snapshot.yaml 不受影响（正确存储数值），仅 CSV 审计记录币种有误。不影响查询或重建功能，可忽略或手动修正 CSV。 |
| **`ft stock list` yfinance 在国内被墙 → 需加 `HTTP_PROXY`** | 2026-06-28 修复：在 `_fetch_prices()` 中读取 `os.environ.get("HTTP_PROXY")`，构造带 proxy 的 `requests.Session` 传给 `yf.download(session=...)`。修复后 US/CN 股票可正常获取实时价。使用前确保 shell 中已设 `export HTTP_PROXY=http://127.0.0.1:7890`。 |
| **yfinance ticker format不兼容 ft 的存储格式** | 2026-06-28 修复：`_fetch_prices()` 前新增 `_normalize_ticker()`：`avgo.us`→`AVGO`、`00700.hk`→`0700.HK`（HK ticker 前补零）、`.SZ/.SS` 保留不变。**进一步坑：** yfinance 对 HK 股票的返回形状不稳定，单票/多票可能分别返回 Series/DataFrame/MultiIndex；实现时要用 `_extract_last_close()` 兼容解析，并在批量下载中把 HK 与非 HK 分开。详见 `references/yfinance-hk-price-fetch.md`。
| **yfinance 混批 US + A股会把美股价格拉成 NaN** | `ft stock list` 里若同时出现 `.US` 与 `.SZ/.SS`，不要把它们塞进同一个 `yf.download()` 批次。先按市场拆分：US、A股、HK 分开拉，再 merge 结果。已在 `references/yfinance-market-grouping.md` 记录复现与验证方法。 |
| **Polymarket 价格抓取依赖浏览器风格 User-Agent** | Polymarket Gamma API 可直连，但普通 urllib/裸 requests 容易 403；抓 `pm:<slug>:yes|no` 报价时要带浏览器风格 `User-Agent`。导入/校正 Polymarket 持仓时，`--shares` 统一走 `float`，`stock list` 保留 fractional shares。详见 `references/polymarket-holdings.md`。 |
| **Polymarket 账本现金为负** | `ft stock list` 里的 Polymarket cash 为负，通常表示 security ledger 缺少入金、提现、redeem/settlement 或 cash checkin 现金腿；不是平台真实现金为负的证据。`ft verify` 只证明 CSV ↔ snapshot 内部一致，不证明与 Polymarket 平台余额一致。不要凭空补 balancing entry；若用户要快速对齐，先让用户提供平台当前可用 USDC/cash，再做 `stock checkin --cash`；若要审计严谨，则补全 deposits/withdrawals/redemptions/settlements。详见 `references/polymarket-holdings.md`。 |
| **做空 SELL 后 snapshot 保留负股数（不再是跳过负股数）** | 2026-06-26 起 `repair_security()` 改为 `if p["shares"] == 0: continue`（之前是 `<= 0`），做空产生的负股数会写入 snapshot。负股数的 avg_cost = 总成本 / 负股数 = 正数（做空均价）。手动查仓：`python3 -c "import yaml; d=yaml.safe_load(open('snapshot.yaml')); print(d['accounts']['security']['IBKR']['positions'])"` |
| **`ft stock checkin` 无法归零/移除持仓（三缺一报错）** | `ft stock checkin` 要求 `--ticker`+`--shares`+`--avg-cost` **三者同时给**，否则报 `❌ 请指定 --ticker+--shares+--avg-cost 或 --cash`。因此**不能用 `--shares 0` 把一只票从账户移除**。搬迁/拆分后原账户的残留持仓块，只能直接编辑 `~/.ft/snapshot.yaml` 用 patch 删掉对应 `ticker:` 块（3行：ticker/avg_cost/shares；残留块常带 bug 值如 `-101.86` 可作唯一定位锚点）。`ft verify --fix` 是增量重建，不会自动删已无来源的持仓块。详见 `references/split-security-account.md`。 |
| **CCB 转换后 account_name 不带尾号（建行储蓄卡 → 建行储蓄卡(2820)/(0523)）** | `ft convert -s ccb-debit` 输出的 CSV 中 `account_name` 是 `建行储蓄卡`，但实际账户名是 `建行储蓄卡(2820)`（卡尾号 2820）和 `建行储蓄卡(0523)`。有两种修复方式：**①（推荐）加 mapping 规则自动分流** — CCB 转换器已提取 `card_number` 字段，在 `mapping.yaml` 中加规则；**②（传统）手动 sed**：`sed -i '' 's/,建行储蓄卡,/,建行储蓄卡(2820),/g'` 修正主 CSV 和退款 CSV。 |
| **account_name 大小写敏感 — ibkr 不等于 IBKR** | 导入 CSV 时 account_name 字符串精确匹配。若录入 `account_name=ibkr`（小写）而 accounts.yaml 中是 `IBKR`（大写），系统会创建两个独立账户。**修复**：`sed -i '' 's/,ibkr,/,IBKR,/g' records/security/*.csv` 后 `verify --fix` 合并。导入时统一用 `IBKR`。 |\n| **`_replay_security_csv` BUY 双四舍五入导致成本漂移** | 原代码 `avg = round((old_c + s*p) / new_s, 2)` 后再 `round(avg * new_s, 2)`，每次 BUY 约偏 +0.06，12 笔交易后累计偏 +$989。**修复**：改为直接 `h[\\\"total_cost\\\"] = round(old_c + s * p, 2)`，不再经 avg 再乘回。`repair_security` 在写入 snapshot 时由 `avg_cost = total_cost / shares` 即时计算。`verify --fix` 可修正已有残留。 |
| **批量导入历史证券交易注意做空记录** | 2026-06-26 起系统支持做空（负股数），沽空 SELL / 平仓 BUY 可直接导入 CSV 并用 `verify --fix` 重建。不再需要手动删除沽空→平仓对子。实现代码：`stock.py` 的 `do_sell`、`do_buy`、`_replay_security_csv`、`repair_security`、`verify_security` 全部适配负股数。|

## 转换器开发与维护

### 核心原则

**禁止静默丢弃数据。** 任何非预期数据格式、未知枚举值、解析失败都应抛出 `ValueError`（带完整上下文信息），而不是 `continue` 或 `return None` 静默跳过。详见 `references/convert-no-silent-drop.md`。

**变量定义顺序决定代码可维护性。** 在 `_read_wechat_raw` 等函数中，`txn_type`、`payment_method`、`counterparty`、`desc`、`date_raw`、`date_str` 等变量定义应该放在 `if direction:` 分支链**之前**，这样所有分支都能引用它们（包括新添加的中性交易分支）。原代码把变量定义放在分支链之后，导致新分支无法引用这些变量。

**ft 命令路径选择。** 修改 `convert.py` 或 `stock.py` 后，确保 `ft` 命令用的是修改后的代码，并清除 `__pycache__` 使改动生效。有两种方式：
- `~/bin/ft` — 开发模式，`uv run` 走 `~/.hermes/skills/finance/finance-tracker` 目录
- `which ft`（`/Users/huangwenlong/.hermes/hermes-agent/venv/bin/ft`）— 安装版，可能引用旧代码
推荐用 `~/bin/ft` 或临时通过 `PYTHONPATH` 指定。
