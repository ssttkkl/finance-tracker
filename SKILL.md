---
name: finance-tracker
category: finance
description: 使用场景：管理个人财务、导入银行/信用卡/支付宝/微信账单、记录股票买卖、管理多币种资产（USD/CNY/HKD）、验证财务数据一致性
design_spec: docs/superpowers/specs/2026-06-12-csv-only-design.md, docs/superpowers/specs/2026-06-12-stock-trading-design.md, docs/superpowers/specs/2026-06-12-unified-snapshot-design.md, docs/superpowers/specs/2026-06-13-dfzq-stock-converter-design.md, docs/superpowers/specs/2026-06-13-git-transactional-commit-design.md, docs/superpowers/specs/2026-07-09-unified-swap-design.md
design_plan: docs/superpowers/plans/2026-07-09-unified-swap-plan.md
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

convert 说明：`alipay`（支付宝 CSV）、`wechat`（微信 xlsx）、`icbc`（工行 PDF，需 --password，自动检测信用卡/借记卡，**支持多卡路由** — 见下方"ICBC 信用卡 PDF 内含多卡交易"陷阱）、`ccb-debit`（建行 xls）。

> **转换器路径注意：** `which ft` 指向的可能是 hermes-agent venv 中的 pip 安装版（`/Users/huangwenlong/.hermes/hermes-agent/venv/bin/ft`），而开发版在 `~/bin/ft`（uv run 模式）。开发版用了修改后的 `convert.py`（补丁/新功能），安装版用的是旧代码。**修改转换器代码后必须确保 `ft` 命令用的是开发版**。推荐用 `~/bin/ft`，或用 `PYTHONPATH` 指向开发目录后通过 venv python 调用。

AI 审查要点：按优先级 **P0(金额影响) > P1(source) > P2数据脱敏 > P3 counterparty** 逐项检查。每个转换后的 CSV 文件独立审查，每文件分配一个 subagent。详细审查清单见 `references/review-checklist.md`。跨支付渠道排查“状态/方向/中性交易”类转换器问题时，按 `references/payment-statement-direction-audit.md` 先只读重转到 `/tmp`、统计风险类、再和 records 精确匹配；不要把历史导入范围差异直接当 bug。

转换阶段：退款配对数学正确性（全额=0？部分=净额正确？）、source 正确性、数据脱敏、counterparty 规范化。注意 _pair_refunds 产生的孤退款行（orphan income）可能在 CSV 中残留，需检查过滤。

**关键：每转完一个文件就停下来让用户确认，不要一次转完所有文件再统一审查。**

reconcile 阶段：审计文件中每对 dedup_status=保留/去除 行必须成对出现；保留行必须仍存在于 records 中，去除行不应再出现在 records。漏删：同来源+同日+同金额+同 counterparty 的明显重复（注意同日不同时独立交易）。

**合并审查（步骤 ⑤）**：reconcile 后按 6 个月一批分给 subagent 并行审查（见 `references/reconcile-transfer-leakage-audit.md`）。审查输出保持极简：不要逐条展开所有“无需标注”，只统计无需标注数量/置信度分布；明细只列“未标注”和“误标注”，每条给 `置信度 + 判断 + 最小定位信息`。

### 账户管理

| 命令 | 说明 |
|------|------|
| `ft acct add <名称> --type cash|loan|lend|security|crypto --currency CNY|USD|HKD` | 新增 |
| `ft acct list` | 列表+余额 |
| `ft acct rename|delete|activate|deactivate` | 管理 |

#### 加密货币账户（crypto）

crypto 账户复用 security 引擎（同 records/security/ CSV、同 ft stock 命令）。稳定币、法币等都按账户 `base_currencies` 作为现金/结算 position；BTC/ETH 等为持仓。**不要假设 crypto 账户统一用 USD**：例如 Kraken 可配置 `[USDT, USDG]` 并在二者之间显式 swap。新增可报价加密资产时在 `models.py` 的 `CRYPTO_IDS` 补一行 `symbol → CoinGecko id`（已内置 btc/eth/usdt/usdc/sol/bnb/xrp/doge/ada）。

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

## 月度分析：
Report 的原始数字包含大量资金调拨（银证转账、基金赎回等），不反映真实收支。`report.py` 目前也**没有净收入指标**（income−expense），且收支求和不排除 transfer 污染。做月度收支/净收入/趋势分析或计划改 report 前，先看 `references/monthly-analysis.md`。

**月度过滤原则（用户明确要求）：只按 `category` 字段过滤 `transfer/transfer_in/transfer_out`，不做额外算法过滤。** 用户说"他人转账要算的，只有自己转自己标注成 transfer in/out"。诊断异常月份时才用辅助规则。

**已知脏数据模式（reconcile 未捕获）：** 跨渠道重复（铁路12306 vs 中国铁路网络、同账户跨源重复）因 counterparty 子串匹配失败而漏过。详见 `references/monthly-analysis.md`。转账识别分**配对型**（reconcile 原有，两腿都在 ft）与**单腿型**（对手方是基金公司/购汇/货基，永远配不上对，靠 `transfer_rules.py` 规则识别）；单腿转账的规则设计、真实数据假阳性陷阱（收益发放=真收入、蚂蚁搬家=真消费、desc='充值'全是假阳性）、TDD+预演落库流程见 `references/single-leg-transfer-detection.md`。

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

**暂存副作用（重要）：** `save_snapshot()` 路径（包括 `repair_security()` 与 `ft verify --fix`）同样会执行全工作区 `git add -A`；它不会只暂存本次修复涉及的 CSV 或 snapshot。真实账本已有无关未提交/已暂存改动时，先只读记录 `git status --short`，向用户说明该副作用并取得接受该写入范围的授权，再重建快照。不得在完成后为了“清理”而擅自 reset、checkout 或取消暂存用户原有改动；如自动暂存范围扩大，只报告当前 staged 状态并等待用户决定。

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

### 股票交易

> **多本位币与事件币种：** `base_currencies` 只声明账户可持有的现金币种，不允许把证券成本、股息或手续费任意改成另一种本位币。现金股息应先按证券实际结算/成本币种入账；后续换汇必须单独记录 `swap`。手工命令、CSV replay 与零 checkin 的完整契约见 [`references/security-currency-event-model.md`](references/security-currency-event-model.md)。

### 实时估值与法币 position

`ft stock list` 的价格收集必须从 `accounts.yaml` 的所有账户 `base_currencies` 动态得到货币 ticker（大小写无关），并将它们排除在 yfinance、CoinGecko 与 Polymarket 报价之外；不得硬编码 `USD/CNY/HKD`。这样 `USD` 不会误命中同名 ETF，`CNY` 不会产生“possibly delisted”告警，也能覆盖 `USDT/USDG` 等扩展本位币。列表展示中应按 `ticker.lower() == base_currency.lower()` 聚合历史大小写变体为现金，并从持仓行排除。

**配置是唯一的货币注册表。** 不要用 `ticker == cost_currency` 等 snapshot 元数据作为隐式“货币 ticker”判定并排除报价：这会绕过配置，错误地把未配置资产永久显示为 `N/A`。反过来，若一个已配置的 base-currency ticker 出现在另一账户，即使旧 snapshot 的 `cost_currency` 脏，也必须作为该 ticker 原生币种展示且不估值。UI 修正不能掩盖账本问题：完成只读展示验收后仍须运行 `ft verify`；若失败，明确分离并报告 replay/账本根因，不能擅自修改真实 records。

#### 多币种 security / crypto 账户契约

`base_currencies` 是 security / crypto 账户**唯一**的现金与结算币种注册表（更准确的概念是 `cash_currencies` / `settlement_currencies`）。账户没有“主币种”、默认币种或统一报告币种：所有现金、持仓市值、成本和盈亏必须按币种独立展示，绝不跨币种合计。兼容旧账户时，只有在 `base_currencies` 缺失的情况下，才临时以旧 `currency` 推导单元素集合。

- 任何现金 ticker 判定都必须从目标账户的 `base_currencies` 动态得到（大小写无关），覆盖 direct 命令、CSV append、replay、repair、手续费和估值；不得保留 `CNY/USD/HKD` 等硬编码列表。
- 手工 stock 命令若币种无法由该操作本身唯一确定，必须显式要求 `--currency`，并在写入前校验属于该账户 `base_currencies`；不得默认 USD、旧 `currency` 或列表第一个币种。CLI 参数也不得用硬编码 `choices` 限制币种。
- 任意两个已配置现金币种（例如 `USDT` 与 `USDG`）之间的 deposit/withdraw/swap/手续费是合法现金操作，不得被当成证券的跨成本币种冲突。
- 非现金 ticker 的已有非零持仓仍只允许一个 `cost_currency`。用另一币种继续买卖时必须在写 records/snapshot 前抛 `ValueError`，且保证零副作用。归零后重新建仓的币种语义须由 direct、append、replay、repair 一致实现并测试。
- 现金股息的结算币种必须与已有非零证券持仓的 `cost_currency` 一致。若券商或用户将股息换为另一现金币种，账本必须写两步：`dividend → 原结算币种`，再独立 `swap → 目标币种`；不得把换汇伪装成股息。
- `checkin` 的零值语义同样必须在 direct、CSV append、replay、repair、verify 中一致；不得留下只能由某一路径看见的零 position。

实现这类变更必须先用隔离账本做 RED 测试，并至少覆盖：无硬编码 CLI help、无默认币种、动态 USDT/USDG 现金换汇的 direct + append/replay、非现金成本币种冲突的零副作用、股息后独立换汇、零现金/零股票 checkin 的一致性。

账户内的非本位币 currency position 没有 FX 换算模块时，必须标为 `N/A` 且不计入本位币合计；绝不能显示 0、NaN，或把它当证券估值。**已由 `base_currencies` 识别出的 currency ticker，显示均价/成本时以 ticker 自身币种为准**（例如 `cny` 不得显示为 `HK$`，即使旧 snapshot 的 `cost_currency` 脏）；其他 position 显示成本/均价时使用自身 `cost_currency`，绝不能用账户本位币 symbol 包装另一币种的数值。可在现金区单列“非本位币”，但不得混入证券估值或本位币合计。负股数也可估值：只要报价存在，应计算 `market_value = shares × price`（负值）及其盈亏，不能以 `shares > 0` 为条件将做空/反向仓位显示成 `N/A`。

修改 list/估值逻辑必须先写 RED 测试，至少覆盖：① 大小写本位币现金合并；② 任一 `accounts.yaml` 已配置的 `base_currencies` 都绝不调用 yfinance、CoinGecko 或 Polymarket；③ 多本位币账户逐币种显示且不跨币种合计；④ 非本位币 currency position 为 `N/A`、不计总额、且按其 ticker 自身币种显示；⑤ 配置为 base currency 的 USDT/USDG 等现金显示清晰币种单位；⑥ 带报价的负仓显示负市值。验收顺序：先运行 `ft verify`，校验失败时不得把 `ft stock list` 的数值报告为可信对账结果；修复账本根因后再跑相关单测、全量测试、`ft verify` 和真实只读 `ft stock list`。

security 类型账户使用统一 swap CSV 格式（12 列），支持 A 股（`159740.sz`）、美股（`mu.us`）、港股（`00700.hk`）、加密货币。**所有交易统一为 swap**：从一个资产换到另一个资产，包括用法币买股票。USD/USDT 等本位币也是 position，与股票/加密平级。

### CSV 格式（12 列）

```
date, action, from_ticker, to_ticker, from_amount, to_amount, price, commission, commission_asset, currency, account_name, note
```

| action | 语义 | from_ticker | to_ticker | from_amount | to_amount |
|--------|------|-------------|-----------|-------------|-----------|
| `swap` | 资产 A → 资产 B | 给出的资产 | 收到的资产 | 给出量 | 收到量 |
| `deposit` | 外部入金 | `EXTERNAL` | 币种 | 0 | 金额 |
| `withdraw` | 出金 | 币种 | `EXTERNAL` | 金额 | 0 |
| `dividend` | 分红 | `DIV` | 币种 | 0 | 金额 |
| `checkin` | 快照校准 | 资产 | — | 0 | 股数 |

手续费内嵌在 swap 行：`commission`=手续费金额，`commission_asset`=扣费资产。不再有独立 FEE 行。

### Snapshot 结构

```yaml
security:
  IBKR:
    base_currencies: [USD, HKD, CNY]  # 本位币列表
    positions:
      USD:   {shares: 8158.9, avg_cost: 1.0, cost_currency: USD}    # 现金=position
      NVDA:  {shares: 25, avg_cost: 205.9, cost_currency: USD}      # 股票
      BTC:   {shares: 0.02, avg_cost: 63710, cost_currency: USD}    # 加密
```

- `base_currencies`：账户的本位币列表（在 `accounts.yaml` 中配置）
- 所有资产（USD/NVDA/BTC）统一为 position `{shares, avg_cost, cost_currency}`
- 本位币 position 的 `avg_cost` 固定为 1.0
- **没有 `cash`/`cash_map` 字段**——已被 position 取代

### 平均成本计算

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

做空 cost 计算示例（统一 swap 模型）：
```
做空前: 0 股
沽空 5 股 @ $220 → swap(usd, nvda, 1100, 5): shares=-5, total_cost=-$1,100, avg_cost=$220
回补 2 股 @ $215 → swap(nvda, usd, 2, 430): shares=-3, total_cost=-$670, avg_cost=$223.17
```

要点：
- 做空 SELL 产生负 shares，avg_cost 为正数（做空价格）
- 平仓 BUY 减少负 shares 的绝对值，剩余做空均价上移/下移取决于平仓价
- 做空记录参与 `verify --fix` 全量重放和 snapshot 校验
- `repair_security` 保留负 shares（不再 `<= 0: continue`）

`ft stock buy/sell` 自动扣减/增加现金并更新持仓，`ft stock checkin` 用于初始导入或校正持仓/现金（不涉及现金变动，支持 Polymarket 这类 fractional shares）。**把一个混着多券商持仓的 security 账户拆成多个真实券商账户**（同一只票分散在多家券商、股数对不上单一 App）的完整流程见 `references/split-security-account.md`（含：从盈亏反推均价、股数闭合核对、checkin 无法归零持仓需手改 snapshot.yaml、已清仓票记卖出留痕）。外部平台开户同步命令采用可扩展层级 `ft stock sync <provider>`，不要再新增 `sync-xxx` 平铺命令；变更命令形状时先写 CLI RED 测试覆盖新路径分发和旧路径移除，再更新 help/文档并用真实 `--help` + `--dry-run` 验证。`ft stock sync polymarket --wallet <profile_wallet>` 可通过 Polymarket 公开 Activity API 增量同步官方成交记录：先校验目标账户是 USD security 账户，再解析 proxy wallet、拉取 `type=TRADE` activity；跨批次不能把 bare `transactionHash` 当作整笔交易的唯一键：用 tx hash + ticker/token + side + size + USDC size + timestamp（或平台 fill/activity ID）作为行级业务身份，只跳过完全相同的 fill；同批也只折叠 exact duplicate rows，避免丢同一链上 tx 的多 fill/多 market 成交，再写入标准 stock CSV/records；也可用 `--proxy-wallet <0x...>` 跳过 profile 解析、`--account <账户名>` 指定另一个 Polymarket security 账户、`--dry-run` 只预览、`-o <csv>` 输出待导入 CSV。执行用户要求的 Polymarket 同步时，推荐顺序是：先 `--dry-run` 确认 `new rows`，再跑真实写入（即使 dry-run 显示 0，也可跑真实命令验证“没有新增”），随后必须跑 `ft verify`，再用带代理的 `ft stock list` 报最新持仓。同步命令会在 stdout 打印 proxy wallet；最终回复和日志摘要中一律把 wallet/proxy wallet 写成 `[REDACTED]`，不要复述真实地址。用户明确要求中文沟通，财务/Polymarket 汇报默认用中文、先给结果数字和是否写入，再给必要明细。`ft stock list` 实时拉取 yfinance 市值；Polymarket 持仓支持 `pm:<slug>:yes|no` 伪 ticker，并通过 Polymarket gamma API 拉取最新报价，且会保留小数股数显示。账户/报表估值会按当前市价计算，不再只看成本价。Polymarket 持仓的具体约定见 `references/polymarket-holdings.md`（含负现金解释/校正规则）；内置增量同步命令的设计/测试/维护要点见 `references/polymarket-sync-feature.md`；当处理 resolved token、真实 SELL、合成 settlement、同 tx 多 fill 或 Positions API 精度差异时，必须按 `references/polymarket-settlement-reconciliation.md` 做投影仓位结算、行级去重和精度感知对账；实现/审查任何 security 外部同步或 stock import 功能时，先按 `references/security-sync-hardening-checklist.md` 检查账户定位、去重、mixed CSV schema、原子写入和回滚测试；用公开 Data API 从钱包 Activity 全量刷新历史交易见 `references/polymarket-public-history-import.md`；用官方 Polymarket Activity 全量替换手工/半自动记录的详细流程见 `references/polymarket-official-activity-import.md`；当 Activity 含 `REDEEM`/`YIELD`，或用户将交易所“提币”明确为 Polymarket 入金时，执行 `references/polymarket-full-reimport-and-funding.md` 的逐类建模、临时重放、替换旧 deposit 与 API 对账流程（解析 proxy wallet、优先 `/activity` 而非 `/trades`、全量覆盖时同时处理 TRADE/REDEEM/YIELD、备份不要放进 `~/.ft` git、保留或依据证据重建外部换汇的 `deposit` 供 security replay 计现金，并以 Positions API 清理仅有的微小残余）；重复导入去重流程见 `references/polymarket-import-dedupe.md`；Gamma 字段兼容坑见 `references/polymarket-gamma-field-quirks.md`；security 校验的转账审计行/浮点残差坑见 `references/security-verify-pitfalls.md`；完整做空迁移记录见 `references/short-selling-support.md`。长时间多轮 Codex/review 后，向用户汇报时先给“到底改了啥”的短 changelog：用户可见功能、关键安全修复、验证命令/结果、是否写入真实数据；不要先展开冗长过程叙事。 

```bash
# 日常买卖（底层都是 swap）
ft stock buy --ticker nvda.us --shares 5 --price 120 --commission 0.35 --account IBKR
ft stock sell --ticker nvda.us --shares 2 --price 130 --commission 0.15 --account IBKR

# 现金操作（底层都是 swap/deposit/withdraw）
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

#### 手工交易命令的币种校验与多本位币账户

处理或重构 `ft stock buy/sell/swap/deposit/withdraw/dividend/checkin` 时，**不得**让 CLI 用硬编码的 `CNY/USD/HKD` choices 或固定 `USD` 默认值决定交易币种。币种注册表必须来自目标账户在 `accounts.yaml` 的 `base_currencies`；若旧账户缺少该字段，才明确回退到其 `currency`。因此未来配置的 `USDT`、`USDG` 等本位币无需改 CLI parser 就能使用。

写入前必须统一校验（适用于所有会写 `currency` 的手工 stock 命令）：

1. 账户必须已存在于 `accounts.yaml`，且类型为 `security` 或 `crypto`；禁止仅在 snapshot 中隐式创建未知账户。
2. 显式 `--currency` 大小写无关地规范化，并验证属于该账户允许的本位币；省略时动态采用账户配置的默认本位币，不能固定 USD。
3. 生成的 `from_ticker`/`to_ticker`、`commission_asset` 与 `currency` 必须符合该动作的资金腿语义并使用同一规范化币种；例如现金买入 `usd → goog.us` 的手续费资产不能写成 `cny`。
4. 对会建立或改变证券成本的动作，若同一非货币 ticker 已有非零持仓，新的成本币种必须与已有 `cost_currency` 一致；不一致时在**任何 snapshot/records 写入前**抛 `ValueError`。这防止先污染 CSV、之后才由 `ft verify` 报 `cost_currency mismatch`。
5. 多本位币账户可合法使用各自配置币种；不能为了阻止脏数据而错误限制为单一 USD。清仓后以另一合法币种重新建仓的规则必须以行为测试明确。

修改此逻辑按 TDD 覆盖：动态币种（含 USDT/USDG）、未配置币种拒绝、未知/错误账户类型拒绝、动态默认值、大小写规范化、同 ticker 跨币种冲突的零副作用阻断、合法多本位币路径，以及 CLI help 不显示硬编码币种 choices。实现后跑聚焦测试、全量 `pytest` 和 `ft verify`；若真实账本已有其他未提交/暂存改动，重建 snapshot 可能自动 stage 全部改动，先记录状态，绝不擅自 reset/commit 他人内容。

#### 手动追加券商成交（从截图）

完整的截图 OCR、去重、佣金现金腿、股息/JRN、现金 CHECKIN 与外部同步顺序见 [`references/broker-screenshot-import.md`](references/broker-screenshot-import.md)。

当用户提供 IBKR、嘉信等券商截图且 ft 里缺少对应记录时：

1. 先用 `grep -i <ticker> ~/.ft/records/security/*.csv` 检查是否已存在（截图可能覆盖已录入的日期范围）
2. 缺失的记录直接追加到对应日期 CSV（12 列格式：`date,action,from_ticker,to_ticker,from_amount,to_amount,price,commission,commission_asset,currency,account_name,note`）
3. 追加后运行 `ft verify --fix` 全量重建 snapshot，再 `ft verify` 确认一致
4. 最后 `ft commit` 提交

**不要用 `ft stock buy/sell` 命令**——那会额外扣减/增加现金，而截图里的券商现金余额已含这些交易的影响。直接追加 CSV 行 + `verify --fix` 重放更安全。

**拆股/送股等公司行为**：`dividend` action 已支持送股/转增——当 `to_ticker` 是股票代码时只加 shares 不加 total_cost。送股用 `dividend`（to_ticker=股票，to_amount=送股数），配股用 `swap`（用钱买）。dfzq 转换器自动转出红利入账/红股入账/银证转账。详见 `references/corporate-actions.md`。

**CSV 行格式示例：**
```
# 买入：USD → NVDA
2026-07-09,swap,usd,nvda.us,1501,10,,1,USD,USD,IBKR,ibkr

# 卖出：NVDA → USD
2026-07-09,swap,nvda.us,usd,10,2000,,1,USD,USD,IBKR,ibkr
```

#### 券商截图成交与活动记录导入（IBKR / 嘉信等）

截图导入遵守“先识别、后写入、再重放”的流程：

1. **先确认券商与账户归属**：不要根据界面风格猜测。截图中出现的券商名称、账户尾号、活动代码（例如嘉信 `TRD` / `DOI` / `JRN`）必须原样核对；用户纠正归属时，立即按其指定的券商账户落库。
2. **逐行提取真实字段**：用截图的日期、时间、ticker、买卖方向、股数、成交价、金额、活动编号交叉核对。若视觉文本有歧义，优先用可重复的 OCR 再读一次，不要把成交额误作单价或凭常识改写数值。
   - macOS 上可用 Vision 的 `VNRecognizeTextRequest` 对本地图片 OCR，适合读取细小的券商流水文字；OCR 输出仍需与像素截图逐行复核。
3. **先查同账户同日同 ticker/价格/股数是否已有行**：已有成交时不要再追加；补写截图中缺失的精确时间和券商活动编号到原行。截图带来的新成交才新增 swap 行。
4. **活动类型建模**：
   - `TRD` 买卖：写标准 `swap`；金额腿为成交毛额，佣金按统一手续费契约单列。
   - `DOI` 正现金分红：写 `dividend,DIV,<currency>,0,<amount>`，note 保留证券名与分红类型。
   - `JRN` 或负利息等现金扣款：没有专属 action 时写 `withdraw,<currency>,EXTERNAL,<amount>,0`，note 原样保留活动代码和说明；不得擅自断言扣款性质（如预扣税），除非截图明确写明。
5. **写入后**：`ft verify --fix && ft verify`，再用 `ft stock list` 检查新持仓或已平仓成交未产生异常，最后 `ft commit`。写入前若仓库已有无关未提交改动，先停下确认来源；本次任务产生的 CSV 与 snapshot 改动才可提交。

#### 持仓成本 / 现价 / 市值 / 盈亏查询

当用户问“持仓成本”“市值、成本、现价、盈亏”这类只读查询时：
1. 先运行 `ft verify`，确认 Security CSV ↔ Snapshot 对齐；不要在校验失败时直接报数。
2. 再运行 `HTTP_PROXY=${HTTP_PROXY:-http://127.0.0.1:7890} HTTPS_PROXY=${HTTPS_PROXY:-http://127.0.0.1:7890} ~/bin/ft stock list` 拉取实时估值。
3. `ft stock list` 表格通常显示：股数、均价、成本、市值、盈亏、涨幅；若用户要求“现价”，用 `现价 = 市值 / 股数` 计算，不要把均价误当现价。
4. 按账户/币种分组展示，**不要跨币种合计盈亏**；每组可列：标的、股数、现价、成本价、总成本、市值、盈亏、盈亏率。
5. 对 `成本=0` 或 `负成本` 的标的，说明盈亏率可能无参考意义，这是平均成本法/历史卖出摊低成本导致，不一定是错误。
6. 当用户质疑“股数/市值看着不对”“是不是有脏数据”时，不要只重复 `ft stock list`。按 `references/security-position-sanity-audit.md` 做专项排查：扫描 zero-cost/negative-cost/tiny-shares，追溯 records 来源，Polymarket 用官方 `data-api.polymarket.com/positions` 对比当前真实持仓，另扫 security CSV 币种字段与账户币种不一致。
7. **用户给成交截图要求记账时，先查 records 是否已存在同 ticker/账户/日期/价格/股数的成交**，尤其 IBKR/券商截图可能是"回看已成交"。不要直接补一笔只有日期的 `ft stock sell/buy`。如果已存在带精确时间的记录，解释持仓如何由最近 `CHECKIN` + 后续成交重放得到；避免把当前持仓误判为卖出前仓位。**⚠️ 关键陷阱：** CSV 中可能残留历史导入的 CHECKIN 记录（如初始迁移时写入的 checkin 行），这些 CHECKIN 会**覆盖**实际 BUY/SELL 计算出的持仓。当用户以截图为准要求修正时，先 `grep -i <ticker> records/security/*.csv` 追溯所有同 ticker 行，逐行核对是否与截图一致；不一致的 CHECKIN（价格/股数/日期对不上）必须删除，缺失的交易必须补入，然后 `ft verify --fix` 重建。详见 `references/security-verify-pitfalls.md` 第3节。

**全量覆盖流程（用户说"用截图覆盖/所有记录都删掉"时）：** 删除该券商所有 CSV 行 → 只从截图补入 BUY/SELL → 补回 cash CHECKIN（无 ticker，设置初始现金余额）→ `ft verify --fix`。**不要保留任何带 ticker 的 CHECKIN**，否则会覆盖实际交易。详见 `references/security-verify-pitfalls.md` 第3b节。

**⚠️ cash CHECKIN 余额反算：** 删除所有带 ticker 的 CHECKIN 后，cash CHECKIN 的金额需要从截图中的账户总值反算。计算方法：final_cash = 初始cash - Σ(买入成本) + Σ(卖出回收)。如果不知道初始 cash，可以用截图中的 Net Liq - Stock 值作为当前 cash。不要沿用旧的 cash CHECKIN 值——它可能是基于错误持仓算出来的。

**⚠️ 旧 snapshot 的 cash 不是初始入金（2026-07-10 教训）：** 旧模型中 `cash` 字段是某个历史时点的余额，不是账户初始入金。迁移时如果直接用旧 snapshot 的 `cash` 值作为 CHECKIN，replay 后余额会不对。正确做法：从用户确认的当前余额反推初始入金 = 当前余额 + Σ(买入成本) − Σ(卖出回收) − Σ(入金)。示例：IBKR 用户确认当前现金 $3,174，CSV 显示总买入 $32,217、总卖出 $6,913、入金 $4,757，则初始入金 = 3174 + 32217 - 6913 - 4757 = $23,721.29。验证：23721.29 - 32217.46 + 6913.17 + 4757.00 = 3174.00 ✓。如果用户不需要初始入金记录，也可以删除 CHECKIN 让余额纯靠交易记录计算（会显示为负）。

**跨账户同标的合并分析：** 当同一只票分散在多个券商账户（如 IBKR + 嘉信 + 盈立），用户要求合并查看时：
1. 各账户的 avg_cost 和 shares 保持独立（均摊法下各账户成本不同）
2. 合并均价 = Σ(各账户 total_cost) / Σ(各账户 shares)
3. 不要把不同账户的均价简单平均——必须按股数加权
4. `ft stock list` 按账户分组显示，不会自动合并；合并计算需在 prompt/分析层面完成
5. 典型场景：IBKR 3股@$917 + 嘉信 3股@$1055 → 合并6股 均价$986（不是简单平均 $986）
8. 如果误写了重复记录导致 `ft verify` 不一致，先定位并删除重复 CSV 行，再 `git add` 相关 CSV，最后 `ft verify --fix && ft verify`。不要凭 snapshot 表象继续追加修正交易。
9. **跨券商重复持仓排查**：当用户指出“某标的只在 A 券商有，却在 B 券商也显示”或“股数多出来”，不要只看 `ft stock list`；按 ticker 追溯 `records/security/*.csv` 中所有同 ticker 行，逐行核对 `account_name/action/date/shares/price/note`。若错误来自旧账户的 `CHECKIN/BUY/SELL` 链，删除旧账户对应 ticker 的整条来源链，只保留真实券商记录；再跑 `ft verify --fix && ft verify && ft stock list`。异常文件名如 `records/security/today.csv`（date=`today`）不是规范日文件，若用户确认无效应整文件删除。修正后只暂存本任务相关 records/snapshot；若发现 `.gitignore`、credentials 等无关改动已暂存，先 `git reset <file>` 取消暂存，避免混入账本修正。

#### Polymarket 结算盈亏计算

Polymarket settlement 记录（`polymarket settlement`）只记录了结算事件本身，**不直接显示盈亏**。盈亏必须对比买入成本：

```
结算盈亏 = 结算收入(to_amount) - 买入成本(Σ from_amount where action=swap and from_ticker=usd)
```

**错误做法**：只看 settlement 行的 to_amount 就当成盈亏。
**正确做法**：`grep -i "<slug>" ~/.ft/records/security/*.csv` 追溯所有同 ticker 的买入/卖出记录，累加成本后与结算收入对比。

#### Polymarket 负仓 / 重复结算排查

出现 Polymarket **负股数**时，不要先当成做空或直接补 `checkin`。按每个 ticker 从 CSV 的最早记录顺序重放 `swap`：买入（`usd → pm:*`）增加 shares，卖出/结算（`pm:* → USD/usd`）减少 shares。若一批真实成交已经把仓位卖为 0，而后面又有同数量的 `note=polymarket settlement`，这是**重复结算**：删除该 settlement 行，而不是追加反向交易；否则会同时留下假负仓并虚增结算现金。

安全修复流程：
1. 先在账本外 `/tmp` 副本删除候选 settlement 行、重建快照；确认目标仓归零、其他仓位不变后才写回真实 CSV。
2. `ft verify --fix && ft verify` 后，用官方 Positions API 对比未平仓 outcome token；该 API **不返回现金**，所以 `USD/usd` 差异不能据此校正。
3. API `size=0` 但账本仅残留极小浮点 shares（例如 `<0.01`）时，可追加带原因的 `checkin` 归零；这不应用于有实质金额的负仓。
4. 运行 `ft stock sync polymarket --dry-run`，确认不存在等待重新导入的官方成交，再提交。

示例（Djokovic Wimbledon No）：
- 买入：71股@$0.845=$59.99 + 36.4股@$0.889=$32.47 = 成本$92.46
- 结算：107.4股@$1.00 = 收入$107.40
- 盈利 = $107.40 - $92.46 = **+$14.94**（不是亏损$107.40）

#### Polymarket resolved/closed 持仓估值坑

当 Polymarket 持仓在 `ft stock list` 中显示市值 `$0.00` / `N/A` 时，**先查 Gamma API 状态，不要直接按 0 价格卖出归零**。具体步骤：

```bash
curl -s -H 'User-Agent: Mozilla/5.0' \
  'https://gamma-api.polymarket.com/markets?slug=<market-slug>'
curl -s -H 'User-Agent: Mozilla/5.0' \
  'https://gamma-api.polymarket.com/public-search?q=<keywords>'
```

若父事件中子 market 显示 `closed: true`、`acceptingOrders: false`、`umaResolutionStatus: resolved`，并且 `outcomePrices` 为 `["0","1"]` / `["1","0"]`，要按持仓 side 选择结算价：持 `No` 时取第二个价格，持 `Yes` 时取第一个价格。赢的一边应按 `$1/股` 现金结算，输的一边才是 `$0/股`。若已经误写 `SELL @0` 清掉赢方，改成对应的 `SELL @1.00` 后再 `ft verify --fix`。

实现/维护要点：`ft stock list` 对 `pm:<slug>:yes|no` 取价时，如果 `/markets?slug=<slug>` 查不到子盘，要用 `public-search` 找父事件并遍历 `events[].markets[]` 精确匹配 slug；resolved/closed 市场不能因 direct slug 查不到就显示 0。`ft stock sync polymarket` 的 synthetic settlement 必须先以“当前 CSV 重放仓位 + 本批确认写入的真实 Activity”计算 projected positions；只对 projected position 仍为正、且 Gamma 返回明确 `closed/resolved` 元数据与对应 outcome payout 的 token 生成 settlement。不得把普通行情报价恰为 0/1 当作结算事实；真实 SELL 已在本批平仓时不得再结算，部分 SELL 仅结算剩余仓位。settlement note 必须携带稳定的 token/结算标识，保证跨批幂等。改这类逻辑至少覆盖：同批全卖不再 settlement、部分卖只结算余量、后续重跑不重复结算、live market 的 0/1 报价不触发 settlement。

### 证券对账单批量导入

```bash
# 步骤① 转换：券商PDF → stock CSV
ft stock convert 电子对账单.pdf -s dfzq --password <password> -o dfzq_stock.csv

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

通常不应清空整个 `records/security/`，也不应靠简单字符串/set 去重。只处理指定账户和已确认的对账单日期范围；仓库中可能混有其他券商、cash-style 转账审计行、旧10列空文件以及“表头粘入一条交易”的损坏文件。完整安全流程见 `references/security-canonical-reimport-dedupe.md`。

```bash
# ① 在 ~/.ft Git 仓库外备份（避免被 git add -A 暂存）
backup="/tmp/ft-security-backup-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$backup"
cp -R ~/.ft/records/security "$backup/security"
cp ~/.ft/snapshot.yaml "$backup/snapshot.yaml"

# ② 只读转换为规范源，不直接 append
ft stock convert 电子对账单.pdf -s dfzq --password <password> -o /tmp/clean.csv

# ③ 审查规范源，并在 /tmp 的 records 副本演练“目标账户+截止日”替换
# 必须证明：其他账户不变、截止日后记录不变、二次运行幂等

# ④ 用户确认后才原子写回真实 records；不要清空整个 security 目录

# ⑤ 验证
ft verify --fix
ft verify
```

不要使用按 `account_name` 直接过滤全目录的短脚本作为默认方案：它无法安全处理 mixed schema、异常表头、CRLF，以及需要保留的截止日后手工交易。

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
| **`ft stock sync kraken` 需要 `crypto` 账户类型 + `ccxt` 依赖** | CLI `acct add --type` 原来只接受 `cash/loan/lend/security`，不含 `crypto`。需先在 `cli.py` 的 choices 列表中加 `"crypto"`（`choices=["cash", "loan", "lend", "security", "crypto"]`）。创建账户：`ft acct add kraken --type crypto --currency USD`。此外 `ccxt` 模块需手动安装到 ft 的 Python 环境：`<venv>/bin/python3 -m pip install ccxt`。同步命令：`ft stock sync kraken --account kraken --dry-run`（先 dry-run 确认新增行数）。crypto 账户复用 security 引擎（同 `records/security/` CSV、同 `ft stock` 命令），USDT=现金 1:1 USD，BTC/ETH 等为持仓。 |
| **`ft stock checkin --cash` 现在创建 position（非 cash 字段）** | 统一 swap 模型下，`ft stock checkin --account IBKR --cash 8158.9` 创建 `positions.usd = {shares: 8158.9, avg_cost: 1.0, cost_currency: USD}`。不再有独立 `cash` 字段。CSV 中写入 `checkin,usd,,0,8158.9,1,0,,USD,IBKR,...`。 |
| **`ft stock list` yfinance 在国内被墙 → 需加 `HTTP_PROXY`** | 2026-06-28 修复：在 `_fetch_prices()` 中读取 `os.environ.get("HTTP_PROXY")`，构造带 proxy 的 `requests.Session` 传给 `yf.download(session=...)`。修复后 US/CN 股票可正常获取实时价。使用前确保 shell 中已设 `export HTTP_PROXY=http://127.0.0.1:7890`。 |
| **yfinance `longName` 对中国ETF名称不准确** | yfinance 返回的 `longName` 对部分中国基金/ETF 的英文翻译有误。已知案例：159330.SZ 返回 "Tibet Eastmoney CSI Securities House 300 ETF"（含 "Securities House"），实际是沪深300指数ETF。`generate_vibe_prompt.py` 依赖 longName 自动取名，会导致 vibe-trading AI 误判标的类型。**修复方式**：在脚本中加 `name_overrides` 字典对已知不准的 ticker 做中文修正，或在 prompt 正文中手动标注正确名称。不改 yfinance 本身。 |
| **yfinance ticker format不兼容 ft 的存储格式** | 2026-06-28 修复：`_fetch_prices()` 前新增 `_normalize_ticker()`：`avgo.us`→`AVGO`、`00700.hk`→`0700.HK`（HK ticker 前补零）、`.SZ/.SS` 保留不变。**进一步坑：** yfinance 对 HK 股票的返回形状不稳定，单票/多票可能分别返回 Series/DataFrame/MultiIndex；实现时要用 `_extract_last_close()` 兼容解析，并在批量下载中把 HK 与非 HK 分开。详见 `references/yfinance-hk-price-fetch.md`。
| **yfinance 混批 US + A股会把美股价格拉成 NaN** | `ft stock list` 里若同时出现 `.US` 与 `.SZ/.SS`，不要把它们塞进同一个 `yf.download()` 批次。先按市场拆分：US、A股、HK 分开拉，再 merge 结果。已在 `references/yfinance-market-grouping.md` 记录复现与验证方法。 |
| **Polymarket 价格抓取依赖浏览器风格 User-Agent** | Polymarket Gamma API 可直连，但普通 urllib/裸 requests 容易 403；抓 `pm:<slug>:yes|no` 报价时要带浏览器风格 `User-Agent`。导入/校正 Polymarket 持仓时，`--shares` 统一走 `float`，`stock list` 保留 fractional shares。详见 `references/polymarket-holdings.md`。 |
| **Polymarket 账本现金为负** | `ft stock list` 里的 Polymarket cash 为负，通常表示 security ledger 缺少入金、提现、redeem/settlement 或 cash checkin 现金腿；不是平台真实现金为负的证据。`ft verify` 只证明 CSV ↔ snapshot 内部一致，不证明与 Polymarket 平台余额一致。不要凭空补 balancing entry；若用户要快速对齐，先让用户提供平台当前可用 USDC/cash，再做 `stock checkin --cash`；若要审计严谨，则补全 deposits/withdrawals/redemptions/settlements。详见 `references/polymarket-holdings.md`。 |
| **做空 SELL 后 snapshot 保留负股数（不再是跳过负股数）** | 2026-06-26 起 `repair_security()` 改为 `if p["shares"] == 0: continue`（之前是 `<= 0`），做空产生的负股数会写入 snapshot。负股数的 avg_cost = 总成本 / 负股数 = 正数（做空均价）。手动查仓：`python3 -c "import yaml; d=yaml.safe_load(open('snapshot.yaml')); print(d['accounts']['security']['IBKR']['positions'])"` |
| **`ft stock checkin` 无法归零/移除持仓（三缺一报错）** | `ft stock checkin` 要求 `--ticker`+`--shares`+`--avg-cost` **三者同时给**，否则报 `❌ 请指定 --ticker+--shares+--avg-cost 或 --cash`。因此**不能用 `--shares 0` 把一只票从账户移除**。搬迁/拆分后原账户的残留持仓块，只能直接编辑 `~/.ft/snapshot.yaml` 用 patch 删掉对应 `ticker:` 块（3行：ticker/avg_cost/shares；残留块常带 bug 值如 `-101.86` 可作唯一定位锚点）。`ft verify --fix` 是增量重建，不会自动删已无来源的持仓块。详见 `references/split-security-account.md`。 |
| **CCB 转换后 account_name 不带尾号（建行储蓄卡 → 建行储蓄卡(2820)/(0523)）** | `ft convert -s ccb-debit` 输出的 CSV 中 `account_name` 是 `建行储蓄卡`，但实际账户名是 `建行储蓄卡(2820)`（卡尾号 2820）和 `建行储蓄卡(0523)`。有两种修复方式：**①（推荐）加 mapping 规则自动分流** — CCB 转换器已提取 `card_number` 字段，在 `mapping.yaml` 中加规则；**②（传统）手动 sed**：`sed -i '' 's/,建行储蓄卡,/,建行储蓄卡(2820),/g'` 修正主 CSV 和退款 CSV。 |
| **CNY 大小写导致重复现金位置** | 转换器输出的 `from_ticker`/`to_ticker` 可能是大写 `CNY`，但系统期望小写 `cny`。大写 `CNY` 会被视为独立资产，导致 snapshot 中出现两个现金位置（`CNY` 和 `cny`）。**修复**：导入后检查 `from_ticker`/`to_ticker` 列，将大写 `CNY` 替换为小写 `cny`：`sed -i '' 's/,CNY,/,cny,/g`。注意不要改 `currency` 列（该列保持大写） |
| **同一证券的 `cost_currency mismatch`** | `ft verify` 全量重放时，同一账户+ticker 的所有来源行必须使用同一种成本币种；例如一笔 `usd → goog.us` 被错误写成 `currency=CNY`，而既有 `goog.us` 成本为 USD，会报 `goog.us cost_currency mismatch: 'USD' vs 'CNY'`。先只读追溯该 ticker 的全部 security CSV 行，按日期排序比对 `from_ticker`、`to_ticker`、`currency`、`commission_asset`、成交价和账户本位币。若交易腿、手续费资产和价格均为 USD 而仅 `currency` 为 CNY，属于源记录币种误标；先在账本外副本把候选行改为 USD 并重放验证，再经用户授权写回真实 CSV，运行 `ft verify --fix && ft verify`。不得通过修改 snapshot 或让同一 ticker 混用两种成本币种来绕过报错。 |
| **`ft stock append` action 大小写敏感** | 转换器输出的 action 可能是大写（`DEPOSIT`/`WITHDRAW`/`DIVIDEND`），但 `ft stock append` 只接受小写（`deposit`/`withdraw`/`dividend`）。**修复**：`sed -i '' 's/,DEPOSIT,/,deposit,/g; s/,WITHDRAW,/,withdraw,/g; s/,DIVIDEND,/,dividend,/g'` |
| **PDF 导入后需补入 PDF 截止日后的交易** | PDF 对账单有日期范围（如 2024-07~2026-06），之后的交易需从截图补录。导入 PDF 后用 `grep -h "东方证券" ~/.ft/records/security/*.csv | tail -5` 检查最新记录日期，对比截图确认无遗漏 |
| **东方财富证券 PDF 密码** | 东方财富对账单 PDF 通常用身份证后6位加密。转换命令：`ft stock convert -s dfzq --password <密码> -o output.csv` |
| **券商 CSV 现金流水加总 ≠ 实际账户余额** | `ft verify --fix` 从 CSV 重放计算现金，但券商实际余额含利息、汇率调整、股息、手续费折扣等未逐笔记入 CSV 的项目。当用户给出实际余额（如"IBKR 现金是 $3,174"）时，直接编辑 `~/.ft/snapshot.yaml` 中对应账户的 `cash` 字段为正确值，再 `ft commit`。不要试图通过补 CSV 行来凑平差额。**统一 swap 模型下的等价操作**：修正 CHECKIN 的 `to_amount` 为正确值（从当前余额 + Σ买入成本 − Σ卖出回收 − Σ入金 反推初始入金），然后 `ft verify --fix` 重建 snapshot。 |
| **ccxt Kraken `fetch_orders()` 不支持** | Kraken exchange 在 ccxt 中未实现 `fetch_orders()`，调用会抛 `NotSupported`。查挂单用 `fetch_open_orders()`，查历史用 `fetch_closed_orders()`。 |
| **Kraken 同步涵盖成交与资金流水** | `ft stock sync kraken` 同时调用 `fetch_my_trades()` 与全量 `fetch_ledger(None, None, 1000)`：ledger 的 `trade` 唯一允许忽略（成交由前者处理）；`transaction`、`transfer`、`derivativescrossexchangetransfer` 按 `in/out` 映射为 `deposit/withdraw`，即使跨子账户的 in/out 最终净额为零也必须保留审计行；`reward`、`staking` 的入账映射为 `dividend`，不能误当外部入金。所有 ledger 行以 `kraken lid:<ledger-id>` 去重，和成交的 `tid:` 命名空间隔离。未知余额影响类型、缺失/非法 direction 或不合理 amount/fee 必须抛带 ledger 上下文的 `ValueError`，绝不静默跳过。CCXT 的 reward/staking `amount` 按净入账数量重放，fee 仅记录在 `commission + commission_asset` 审计列，不能再从 shares 双扣。改同步器后，必须用 fake client 的完整 ledger fixture 覆盖上述类型、手续费和 lid 幂等；再运行真实 `ft stock sync kraken --account kraken --dry-run`，确认输出 ledger 数、映射数、新增数且不写 `~/.ft`。 |
| **Kraken 限价单：用户现金是 USDT 不是 USD** | 用户 Kraken 账户余额为 USDT（非 USD）。`BTC/USD` 对需要 USD 余额，会报 `EOrder:Insufficient funds`。必须用 `BTC/USDT` 对下单。凭证在 `~/.ft/credentials.yaml`，用 ccxt 下单（非 krakenex）。总成本 = price × amount，确保 USDT 余额足够。 |
| **Kraken USDT 是 position（2026-07-10 重构后）** | 统一 swap 模型下，USDT 在 snapshot 中是 position `{shares: 1316.5, avg_cost: 1.0, cost_currency: USDT}`。不再有 `cash_map` 或 legacy `cash` 字段。所有交易所交易（BTC/USDT、ETH/USDT）统一为 swap 行。 |
| **`_replay_security_rows` 统一 swap 路径（2026-07-10 重构）** | replay 逻辑已完全重写：所有 action（swap/deposit/withdraw/dividend/checkin）走单一路径。不再有 `FIAT` 集合、`cash_legacy`、`cash_map`、`pending_swaps`。position 跟踪 `{shares, total_cost, cost_currency}`。`repair_security` 直接写 positions 到 snapshot。**⚠️ 统一 swap 手续费契约**：新格式的 `from_amount` / `to_amount` 必须是**不含手续费的成交毛额**，`commission` 为单独费用，且 `commission_asset` 必须写实际扣费资产；replay 必须从该资产扣费。若手续费从 `from_ticker` 扣除（典型现金买入），手续费应计入接收标的成本；典型现金卖出则降低收到现金。**兼容旧行**：`commission_asset` 为空表示历史净额现金腿已含手续费，replay 不得再扣，避免双扣。东方证券等导入器若输出毛额，必须设置 `commission_asset=currency`。修复既有导入记录前，必须先在 `records/security/` 临时副本重放并和券商余额闭合，经用户确认后才写回；详见 `references/unified-swap-commission-contract.md`。

**Kraken/交易所旧记录迁移专项：不得用账户本位币替代成交 quote 币种。** 将旧 `BUY/SELL` 行迁成 `swap` 时，必须从原始 ccxt `symbol`（`BASE/QUOTE`）或旧行 `note` 的 `quote:<currency>` 取得实际 quote：买入写 `from_ticker=quote → to_ticker=base`，卖出反向；`commission_asset` 必须使用交易所原始 `fee.currency`。即使 crypto 账户本位币配置为 USD，`BTC/USDT`、`ETH/USDT` 买入也必须扣 USDT，绝不能写成 USD。迁移后对每笔抽样用交易所私有成交 API 核对 `symbol/side/cost/fee.currency`；同时断言 `from_amount=cost`（不含手续费），避免把手续费同时塞入 `from_amount` 和 `commission` 造成双扣。只有确认资金来源后才补对应 quote/base 的 `deposit`，不要用虚构入金掩盖迁移错误。 |
| **空 CHECKIN 会覆盖现金余额** | `_replay_security_rows` 中 CHECKIN 执行 `cash[(a, ccy)] = amt`（**赋值**而非累加）。如果 CSV 中残留一条 `CHECKIN,,0,0,0.0,0,USD,<account>`（ticker/shares/amount 全为0），会把前面所有交易累计的该币种现金直接清零。排查方法：`grep "CHECKIN" ~/.ft/records/security/*.csv` 检查是否有空 CHECKIN（ticker 和 amount 都为空/0）；有则删除后 `ft verify --fix` 重建。注意空 CHECKIN 只清零其 `quote:XXX` 对应币种的 `cash_map` 条目，不影响其他币种。 |
| **截图对账原则：ft 多截图无 = ft 错** | 用户明确要求：当用券商截图核对 ft 记录时，ft 里有但截图里没有的记录，优先认为是 ft 的错误（可能是历史导入残留、误录、或 CHECKIN 产生的假记录），不要认为是"截图没显示全"。修正流程：① 截图中可见交易确认存在 → 保留 ② ft 有但截图无 → 追溯 CSV 链确认是否为真实交易（如 checkin 残留、重复导入）→ 非真实则删除 ③ `ft verify --fix` 重建 ④ 核对 snapshot 持仓与截图一致。 |
| **A股 currency 标错导致成本膨胀** | 人民币计价资产（如159330/159740）的 CSV 记录中 currency 字段被标为 USD，replay 按 USD 计算导致成本膨胀约7倍。检测：grep 159330 records 并过滤 USD。修复：改 currency 字段为 CNY 后 verify --fix。详见 references/security-verify-pitfalls.md 第4节 |
| **CSV 重复记录导致仓位翻倍（混合行尾/尾部逗号/大小写）** | `records/security/` 中的 CSV 可能存在重复行，变体包括：① `\r\n` vs `\n` 行尾 ② 尾部多余逗号（`,,,,,`）③ `cny` vs `CNY` 大小写。replay 会把它们当多笔交易，后置 CHECKIN 又可能掩盖错误。**禁止直接对全账本做 `set()` 去重，也不要清空整个 `records/security/`**：同日同价同量的两笔成交可能都是真实交易，目录中还可能混有其他券商和 cash-style 转账审计行。若能重新生成可信 PDF/官方导出结果，应按“目标账户 + 对账单截止日”用规范源替换受污染历史区间，保留其他账户和截止日后手工记录；先在 `/tmp` 副本演练并断言非目标记录不变、二次运行幂等，再原子写回。完整流程及混合12列/旧10列/表头粘行处理见 `references/security-canonical-reimport-dedupe.md`。CHECKIN 与 CRLF 细节另见 `references/security-verify-pitfalls.md` 第7–8节。**根因**：`ft stock append` 会追加而非替换，历史重复导入不会自动清理。 |
| **CHECKIN 是校准机制不是脏数据** | 当历史 CSV 记录不完整（缺卖出记录）导致 replay 计算的持仓远超实际时，CHECKIN 是正确的解决方案。CHECKIN 和历史记录共存：历史记录是审计轨迹，CHECKIN 校准最终状态。不要为了用 CHECKIN 而删除历史记录 |
| **用户截图日期范围外的交易不是重复** | 用户提供的截图通常只覆盖一段日期范围（如"2026-07-02 ~ 2026-07-09"）。同一标的在截图范围外可能有真实交易（如06-30卖出），**不能仅因同 ticker 出现多次就判定为重复并删除**。删除前必须：① 追溯 `records/security/*.csv` 中该标的的完整 BUY/SELL 链；② 验证删除后持仓是否归零或与 CHECKIN 一致；③ 若删除导致持仓异常（如从0变正），说明该记录是真实交易，必须恢复。**典型陷阱**：截图只显示部分日期 → agent 误判早期记录为重复 → 删除后持仓错位。 |\n| **`_replay_security_csv` BUY 双四舍五入导致成本漂移** | 原代码 `avg = round((old_c + s*p) / new_s, 2)` 后再 `round(avg * new_s, 2)`，每次 BUY 约偏 +0.06，12 笔交易后累计偏 +$989。**修复**：改为直接 `h[\\\"total_cost\\\"] = round(old_c + s * p, 2)`，不再经 avg 再乘回。`repair_security` 在写入 snapshot 时由 `avg_cost = total_cost / shares` 即时计算。`verify --fix` 可修正已有残留。 |
| **批量导入历史证券交易注意做空记录** | 2026-06-26 起系统支持做空（负股数），沽空 SELL / 平仓 BUY 可直接导入 CSV 并用 `verify --fix` 重建。不再需要手动删除沽空→平仓对子。实现代码：`stock.py` 的 `do_sell`、`do_buy`、`_replay_security_csv`、`repair_security`、`verify_security` 全部适配负股数。|
| **dfzq 红利入账 ≠ 红股入账：PDF 中"红利入账"的 shares 是参与分红的股数，不是额外送股** | 东方财富 PDF 中「红利入账」包含 shares 字段（参与分红的股数）和 price（每股分红金额），但这是**现金分红**，不是送股。解析器 `_make_txn` 用 `shares > 0` 判断是否送股会导致红利入账被错误当成送股，凭空多出仓位。**修复**：`is_stock_dividend = action_raw == "红股入账"` 而非 `action == "DIVIDEND" and shares > 0`。replay 逻辑也需对应修改：`not any(c.isdigit() for c in to_ticker)` 区分现金（CNY/USD）和送股（002594.sz）。验证：`grep dividend /tmp/dfzq_fixed.csv` 确认红利入账的 to_ticker 是 CNY（现金），红股入账的 to_ticker 是股票代码。详见 `references/corporate-actions.md` |
| **送股/拆股用 `dividend` action，不改历史记录** | ft 没有 split action。`dividend` action 已支持送股/转增（2026-07-13 修复）：to_ticker 是股票时只加 shares 不加 total_cost。**用户明确偏好：不修改历史交易记录来适配拆股，应加一行独立记录。** 计算：送股数 = 原持仓 × (拆股倍数-1)，如200股1拆3 → 加400股。CSV：`dividend,002594.sz,002594.sz,0,400,0,0,,CNY,东方证券,1拆3`。dfzq 转换器自动转出红股入账/红利入账。**⚠️ `ft stock checkin` 历史日期快照陷阱：** checkin 会同时更新 snapshot 到 checkin 值，不考虑后续交易。checkin 后如有卖出需手动编辑 snapshot.yaml 修正。详见 `references/corporate-actions.md` |
| **删除卖出记录前必须先追溯持仓计算链** | 2026-07-09 教训：发现 `2026-06-30.csv` 有一笔 159740 卖出记录（价格/币种与07-02的不同），误判为重复直接删除。删除后 snapshot 显示持仓从0变成47600股——因为该记录其实是真实交易（两天分两笔清仓）。**修复**：恢复记录并修正币种（USD→CNY）。**铁律**：删除任何 SELL 记录前，必须用 Python 脚本从头 replay 全部同 ticker 的 BUY/SELL 链计算最终持仓，确认删除后持仓与截图/用户意图一致。不能只看"看起来像重复"就删。 |
| **CHECKIN 校准 vs 删除历史记录** | 当历史 CSV 有数据质量问题（如 currency 标错导致成本膨胀）时，**不要删除历史记录**。正确做法：① 保留历史记录作为审计轨迹 ② 在最新日期 CSV 末尾追加 CHECKIN 行，设定正确的当前持仓（shares/avg_cost）③ `ft verify --fix` 重建 snapshot。CHECKIN 会覆盖历史 replay 结果，校准到正确状态。**铁律**：用户明确说过"你自己从git恢复"——删除历史记录是破坏性操作，即使数据有问题也应保留。 |
| **截图对账原则：ft 多截图无 = ft 错** |
| **迁移后 verify mismatch 是预期行为** | 旧 CSV 转换为新格式后，replay 计算出的持仓与 snapshot 不一致（因为旧 CSV 缺少 DEPOSIT/CHECKIN 记录来建立初始余额）。snapshot 应从旧数据直接重建（而非从转换后的 CSV replay）。`ft verify` 会报 mismatch，但不影响使用——新交易从当前 snapshot 开始正确记录。 |
| **迁移时 CHECKIN 重复计入** | 迁移脚本可能同时添加 CHECKIN（从旧 snapshot 的 cash_map）和转换 DEPOSIT（从旧 CSV 的 DEPOSIT 记录），导致同一笔入金被计入两次。排查：`grep "checkin\|deposit" ~/.ft/records/security/*.csv \| grep <账户名>` 检查重复。修复：删除迁移添加的 CHECKIN，保留原始 DEPOSIT。 |
| **`CASH_CSV_FIELDS` vs `CSV_FIELDS`** | `models.py` 中 `CASH_CSV_FIELDS` 是现金记录的 11 列格式（`date,amount,currency,...`），`CSV_FIELDS` 是证券记录的 12 列格式（`date,action,from_ticker,...`）。`append.py`/`transfer.py`/`reconcile.py`/`cli.py` 用 `CASH_CSV_FIELDS`，`stock.py`/`exchange_sync.py`/`polymarket_sync.py` 用 `CSV_FIELDS`。不要混用。 |

## 转换器开发与维护

### 核心原则

**禁止静默丢弃数据。** 任何非预期数据格式、未知枚举值、解析失败都应抛出 `ValueError`（带完整上下文信息），而不是 `continue` 或 `return None` 静默跳过。详见 `references/convert-no-silent-drop.md`。

**变量定义顺序决定代码可维护性。** 在 `_read_wechat_raw` 等函数中，`txn_type`、`payment_method`、`counterparty`、`desc`、`date_raw`、`date_str` 等变量定义应该放在 `if direction:` 分支链**之前**，这样所有分支都能引用它们（包括新添加的中性交易分支）。原代码把变量定义放在分支链之后，导致新分支无法引用这些变量。

**ft 命令路径选择。** 修改 `convert.py` 或 `stock.py` 后，确保 `ft` 命令用的是修改后的代码，并清除 `__pycache__` 使改动生效。有两种方式：
- `~/bin/ft` — 开发模式，`uv run` 走 `~/.hermes/skills/finance/finance-tracker` 目录
- `which ft`（`/Users/huangwenlong/.hermes/hermes-agent/venv/bin/ft`）— 安装版，可能引用旧代码
推荐用 `~/bin/ft` 或临时通过 `PYTHONPATH` 指定。
