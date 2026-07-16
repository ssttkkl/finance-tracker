# Stock 系统：数据模型与功能实现

> 本文依据当前 `src/ft/` 实现编写，描述的是代码的**实际行为**，不是未来设计草案。  
> 核心源码：`stock.py`、`models.py`、`snapshot.py`、`cli.py`、`exchange_sync.py`、`polymarket_sync.py`。

## 1. 系统边界与核心原则

Stock 系统统一处理：

- 股票、ETF、港股、A 股；
- 加密资产与交易所现金；
- Polymarket outcome token；
- 账户现金、外部入金/出金、股息、送股和校准。

它采用 **CSV 审计日志 + YAML 快照** 的架构：

```text
命令 / 导入 / 外部同步
        │
        ▼
统一的 security CSV 交易行
  ~/.ft/records/security/YYYY-MM-DD.csv     ← 权威事件来源
        │
        ▼  全量重放（replay）
~/.ft/snapshot.yaml                         ← 派生快照/查询加速
        │
        ▼
ft stock list / ft verify / 资产报表
```

- CSV 是证券域的事实来源；快照可以由 CSV 重建。
- 一个账户可为 `security` 或 `crypto`；两者共用证券交易行格式、重放引擎和快照结构。
- 所有资产均为 position。现金不是单独的 `cash` 字段，而是 ticker 为 `usd`、`cny`、`usdt` 等的 position。
- 系统没有 FX 换算引擎：不同币种的成本、市值与盈亏必须分开显示，不能相加。

## 2. 文件、模块与职责

| 模块 | 主要职责 |
|---|---|
| `models.py` | 路径、12 列 CSV schema、合法 action、账户/币种常量、加密 ticker 映射 |
| `stock.py` | 交易写入、CSV 导入、仓位计算、重放、验证、修复、行情与持仓展示 |
| `snapshot.py` | snapshot YAML 原子写入、Git 暂存、全账本重建入口 |
| `accounts.py` | `accounts.yaml` 的读取/写入与账户定位 |
| `cli.py` | `ft stock ...`、`ft verify ...` 命令解析与业务函数分发 |
| `exchange_sync.py` | CCXT 交易所成交、Kraken ledger 同步，标准化为 security CSV |
| `polymarket_sync.py` | Polymarket Public Activity 同步、行级去重、已结算 token 的自动结算 |
| `sync_common.py` | 同步 CSV 的完整行 identity、临时输出与导入协作逻辑 |

主要路径由 `models.py` 定义：

```text
~/.ft/accounts.yaml
~/.ft/records/security/YYYY-MM-DD.csv
~/.ft/snapshot.yaml
~/.ft/credentials.yaml              # 外部同步凭证，不应纳入 Git
```

## 3. 账户和币种模型

### 3.1 accounts.yaml

证券和加密账户在 `accounts.yaml` 中配置。例如：

```yaml
accounts:
  - name: IBKR
    type: security
    currency: USD
    base_currencies: [USD, HKD, CNY]
    active: true

  - name: Kraken
    type: crypto
    currency: USDT
    base_currencies: [USDT, USDG]
    active: true
```

字段含义：

- `type`：只有 `security`、`crypto` 可进入 stock 引擎。
- `currency`：账户的遗留/展示字段；**不表示全账户唯一结算币种**。
- `base_currencies`：该账户允许作为现金、结算腿的币种集合；这是现金 ticker 的权威注册表。
- `active`：账户查询时的活动状态信息；直接写入解析会优先选择 active 的同名候选账户。

`stock.resolve_security_account_currency()` 负责直接操作与 append 的账户/币种校验：

1. 账户必须存在且类型为 `security` 或 `crypto`；
2. 传入币种大小写无关，最终规范为大写；
3. 有 `base_currencies` 的新式账户，手动操作必须显式给 `--currency`；不会默认取旧 `currency` 或列表第一个元素；
4. 旧账户缺少 `base_currencies` 时，才回退为单元素 `[currency]`。

### 3.2 position 数据结构

快照中的证券账户结构如下：

```yaml
accounts:
  security:
    IBKR:
      currency: USD
      positions:
        usd:
          shares: 8000.0
          total_cost: 8000.0
          cost_currency: USD
        aapl.us:
          shares: 10.0
          total_cost: 1800.0
          cost_currency: USD
```

每个 position 的字段：

| 字段 | 含义 |
|---|---|
| `shares` | 持仓数量；股票可为整数或小数，crypto/Polymarket 通常为小数；允许负数表示空头或历史回放出的负仓位 |
| `total_cost` | 当前剩余仓位的总成本；并非“累计买入金额” |
| `cost_currency` | `total_cost` 与 `avg_cost` 的计价币种 |
| `avg_cost` | 不作为权威持久字段；需要时用 `total_cost / shares` 计算 |

ticker 会通过 `_canonical_ticker()` 规范为小写，因此 `USD` 与 `usd`、`AAPL.US` 与 `aapl.us` 不会在快照中拆成不同仓位。

### 3.3 现金与非现金资产的区别

若 position ticker 属于 `base_currencies`，它是现金/结算 position：

```yaml
usdt:
  shares: 500.0
  total_cost: 500.0
  cost_currency: USDT
```

非现金 ticker（如 `btc`、`aapl.us`、`pm:...:yes`）可以拥有一个成本币种。对同一账户、同一非现金 ticker：

- 有非零仓位时，后续买入、卖出、swap 或现金股息不可改用另一成本币种；
- 清仓或零值 `checkin` 后，可以用另一合法结算币种重新建仓；
- 现金股息必须按该标的现有 `cost_currency` 结算；若券商实际换汇，应记为“股息入原币种 + 单独 swap”。

## 4. 证券 CSV：统一事件模型

### 4.1 标准 schema

`models.CSV_FIELDS` 定义 security CSV 的固定 12 列：

```text
date,action,from_ticker,to_ticker,from_amount,to_amount,
price,commission,commission_asset,currency,account_name,note
```

合法 `action` 只有：

```text
swap, deposit, withdraw, dividend, checkin
```

`do_append()` 对待导入文件要求列集合恰好是这 12 列。落盘日文件可能混有其他模块写入的转账审计行；重放时，缺少 `account_name` 或 action 不合法的行会被跳过，不参与证券仓位计算。

### 4.2 action 语义

| action | 表示 | `from_ticker` / `to_ticker` | 重放效果 |
|---|---|---|---|
| `swap` | 用资产 A 换资产 B | A → B | 减少 A、增加 B，并让 A 的释放成本结转到 B |
| `deposit` | 外部入金 | `EXTERNAL` → 现金 | 增加目标现金数量和成本 |
| `withdraw` | 外部出金 | 现金 → `EXTERNAL` | 减少来源现金数量和成本 |
| `dividend` | 现金股息或送股 | `DIV`/股票 → 现金或股票 | 现金股息增加现金；送股只增加股数 |
| `checkin` | 余额/持仓校准 | ticker → 空 | 覆盖指定资产的当前数量和成本 |

典型行：

```csv
# 美股买入：USD 换 AAPL
2026-07-01 10:00:00,swap,usd,aapl.us,1000,5,200,1,USD,USD,IBKR,buy

# 美股卖出：AAPL 换 USD
2026-07-02 10:00:00,swap,aapl.us,usd,2,450,225,1,USD,USD,IBKR,sell

# 外部入金
2026-07-01 09:00:00,deposit,EXTERNAL,usd,0,5000,1,0,,USD,IBKR,funding

# 现金股息
2026-07-03 09:00:00,dividend,DIV,usd,0,8,1,0,,USD,IBKR,aapl dividend

# 持仓校准
2026-07-04 16:00:00,checkin,aapl.us,,0,3,210,0,,USD,IBKR,reconcile
```

### 4.3 手续费契约

`commission` 与 `commission_asset` 是独立的手续费字段：

- CSV 的成交金额腿 `from_amount`、`to_amount` 表示**不含手续费的成交毛额**；
- 重放时从 `commission_asset` position 扣除手续费；
- 手续费资产与 `from_ticker` 相同时，其释放成本会计入收到资产的成本；
- 旧行若 `commission_asset` 为空，按历史兼容语义处理，不再额外扣一次。

因此，导入器不应同时把手续费塞入成交金额和 `commission`，否则现金或成本会被双扣。

## 5. 仓位计算与 replay

### 5.1 回放入口和顺序

- `_replay_security_csv()` 读取 `records/security/*.csv`；
- `_order_security_rows_for_replay()` 以 `date` 字符串稳定排序；
- 同一时间戳保持原始输入顺序：已存在日文件里的行优先于本次 append 的新行；
- `_replay_security_rows()` 将行重放为 `(account_name, ticker) -> position` 映射。

稳定排序很重要：历史补录在写入前会和已有记录合并后按同一规则预回放，避免“预校验顺序”和“最终落盘顺序”不同而产生假成本币种冲突。

### 5.2 swap 的成本结转

`_apply_swap_to_positions()` 是统一交换模型的核心。

对来源资产 A：

```text
释放成本 = A.total_cost × (转出数量 / A.原有数量)
A.shares     -= 转出数量
A.total_cost -= 释放成本
```

对收到资产 B：

```text
B.shares     += 收到数量
B.total_cost += 释放成本
```

例外是目标 ticker 为配置现金币种：其成本按收到数量增加，使现金 position 维持接近 1:1 的自身币种成本。

卖出后的剩余成本会随成交价格而变化：卖出收入高于原平均成本时，剩余仓位 `total_cost` 会下降得更多；低价卖出则会使剩余平均成本上升。这是当前的平均成本结转规则，不是 FIFO/LIFO 税务批次模型。

### 5.3 其他 action 的重放

- `deposit`：目标 ticker 的 `shares`、`total_cost` 同增 `to_amount`。
- `withdraw`：来源 ticker 的 `shares`、`total_cost` 同减 `from_amount`。
- 现金 `dividend`：目标现金的 `shares`、`total_cost` 同增 `to_amount`。
- 送股/转增：当 dividend 的来源与目标都是同一非现金 ticker 时，增加 `shares`，保持 `total_cost` 不变。
- 现金 `checkin`：将现金数量覆盖为 `to_amount`，并令 `total_cost == shares`。
- 非现金 `checkin`：覆盖为 `shares=to_amount`、`total_cost=round(to_amount * price, 2)`。
- 零值 checkin：清除该 position，避免零仓位残留影响后续成本币种判断。

### 5.4 数值与可用性约束

- `_ensure_finite_values()` 拒绝非数值、NaN 和 Infinity。
- `_normalize_position()` 将绝对值小于 `1e-9` 的浮点残留归零。
- 直接 `do_swap()` 使用 `enforce_available=True`：来源资产或手续费资产不足会抛错。
- 回放使用 `enforce_available=False`，历史导入可以形成负仓位。
- `do_sell()` 支持卖空；`do_buy()`、`do_withdraw()` 不统一阻止现金为负。因此系统不是“全面禁止负余额”的风险控制系统。

## 6. 写入路径与失败补偿

### 6.1 直接命令

`do_buy()`、`do_sell()`、`do_swap()`、`do_deposit()`、`do_withdraw()`、`do_dividend()`、`do_checkin_ticker()`、`do_checkin_cash()` 的路径是：

```text
验证账户/币种/数值/成本币种
  → 读取 snapshot 并在内存修改 position
  → _save_snapshot_and_record_trade()
      → save_snapshot()
      → record_trade()
```

`_save_snapshot_and_record_trade()` 会预先保存 snapshot 与目标日期 CSV 的字节备份。若写快照或写 CSV 任一步异常，会恢复两份备份，并尝试重新暂存 Git。

### 6.2 批量 append

`do_append(file_path)` 的步骤：

1. 读取输入 CSV，拒绝空文件；
2. 校验 12 列 schema、合法 action、账户类型和 `base_currencies`；
3. 校验数值字段有限；
4. 合并“既有 security 行 + 新行”做全局时间序预回放；
5. 通过后按交易日期写入对应的日 CSV；
6. 调用 `repair_security()` 由完整 CSV 重建证券快照；
7. 尝试 `git add -A`。

它为每个被触及的日文件保存原始内容。任意日期文件写入失败，或 rebuild snapshot 失败，都会恢复已写入 CSV 与旧 snapshot。

### 6.3 原子性范围

- `_write_security_csv()` 与 `save_snapshot()` 都采用“同目录临时文件 + `replace()`”的单文件原子替换。
- 直接交易与 append 有异常补偿，但不是具备 journal、锁和 `fsync` 的数据库事务。
- 进程被强杀、系统掉电或多个进程并发写入时，CSV 和 snapshot 仍可能短暂不一致；此时应使用 `ft verify` / `ft verify --fix` 恢复。
- `save_snapshot()`、部分写入路径会调用 `git_stage()`；其语义是暂存，不自动 commit，且可能暂存工作区内无关改动。

## 7. 校验与修复

### 7.1 verify

`verify_security()` 将 CSV replay 结果与快照逐 position 对比：

- `shares`：容差 `1e-9`；
- `total_cost`：容差 `0.005`；
- `cost_currency`：严格一致；
- 同时报出“snapshot 有、CSV 无”与“CSV 有非零仓位、snapshot 无”。

CLI 的 `ft verify` 会汇总各账户域；`ft verify --fix` 通过 `snapshot.rebuild_snapshot_from_records()` 先全量重建，再做验证。

### 7.2 repair_security

`repair_security()`：

1. 从全部 security CSV 重放；
2. 直接替换 snapshot 的 `accounts.security` 分区；
3. 只写入非零 position；
4. 清理顶层遗留的旧式证券账户结构；
5. 从账户配置补齐展示 currency 与缺失的成本币种信息。

因此它是“CSV → 快照”的恢复路径，而不是对某条 CSV 的业务纠错工具。若 CSV 源记录本身错误，`repair` 会稳定地重建同一个错误；应先更正或替换可审计的来源行。

## 8. 命令层功能

`cli.py` 将 `ft stock` 分派为以下功能：

| 命令 | 实现 | 主要作用 |
|---|---|---|
| `stock buy` | `do_buy()` | 写 `现金 → 标的` swap；手续费计入标的成本 |
| `stock sell` | `do_sell()` | 写 `标的 → 现金` swap；允许从零持仓开始卖空 |
| `stock swap` | `do_swap()` | 任意 position 间兑换并结转来源成本 |
| `stock deposit` / `withdraw` | `do_deposit()` / `do_withdraw()` | 外部现金入金/出金 |
| `stock dividend` | `do_dividend()` | 现金股息；股息币种受持仓成本币种约束 |
| `stock checkin` | `do_checkin_ticker()` / `do_checkin_cash()` | 将某一个 ticker 或现金 position 校准为指定值 |
| `stock append` | `do_append()` | 导入标准 security CSV，写 records 并重建快照 |
| `stock convert` | `do_convert()` | 券商对账单转换为待审查 CSV |
| `stock sync` | 外部同步模块 | 同步 Polymarket 或受支持交易所的外部活动 |
| `stock list` | `do_list()` | 从快照读取并拉取行情，分币种展示持仓 |

目前 `do_swap()` 函数支持手续费参数，但 CLI 的 `stock swap` 参数未公开手续费选项；通过 CLI 手工 swap 实际为零手续费。标准 CSV 导入与外部同步可以写手续费列。

## 9. 行情、估值与展示

### 9.1 行情来源

`do_list()` 只读 snapshot，随后调用 `_fetch_prices()`：

| 资产类型 | 数据来源 | 实现 |
|---|---|---|
| 普通股票/ETF | yfinance | `_normalize_ticker()` 转 Yahoo 格式；按美股、A 股、港股分组；港股逐只请求；缺失结果单票重试 |
| crypto ticker | CoinGecko | `_fetch_crypto_prices()`；仅对 `models.CRYPTO_IDS` 已登记 ticker 取价 |
| `pm:<slug>:yes/no` | Polymarket Gamma API | `_fetch_polymarket_prices()`；优先 outcome price，并有 last trade / best bid/ask 等降级路径 |

网络、第三方 API、解析失败大多会被行情函数吞掉并返回部分结果或空字典。因此 `N/A` 通常表示“本次未取得价格”，不等于标的价值为零。

### 9.2 多币种展示规则

`do_list()` 从 `accounts.yaml` 读取全局已配置的 `base_currencies`：

- 任一已配置货币 ticker 都不会发送给 yfinance、CoinGecko 或 Polymarket 报价；
- 作为当前账户 `base_currencies` 的 ticker 显示为现金；
- 配置为其他账户现金币种、但出现在本账户的旧/异常 position，会以其自身币种显示为 `N/A`，不会被误当成证券报价，也不会以别的 `cost_currency` 符号展示；
- 有可用报价时：
  - `market_value = shares × current_price`
  - `profit_loss = market_value - total_cost`
- 负股数同样按报价计算负市值，不会因 `shares <= 0` 直接显示 N/A；
- 多币种并列展示，输出“合计：多币种，未合并”；仅在可合并值和现金都属于同一个币种时显示 `合计 [币种]`。

估值是面向查询的近似市场价格，不是成交保证、清算价格或 FX 折算后的净资产。

## 10. 交易所同步（CCXT）

`ft stock sync kraken|okx|binance|coinbase|bybit` 进入 `exchange_sync.sync_exchange()`。

```text
credentials.yaml
  → CCXT client
  → 私有成交分页（Kraken 另取 ledger）
  → 统一 12 列 security rows
  → 以 tid/lid + 完整行去重
  → dry-run / 输出 CSV / do_append()
```

### 10.1 成交映射

`trade_to_rows()` 将 CCXT trade 转为一条 `swap`：

- BUY：`quote → base`；
- SELL：`base → quote`；
- note 记录 `<provider> tid:<trade-id>`；
- 手续费由原始 trade 的 fee currency 写入 `commission_asset`。

实际 quote 来自交易对，例如 `BTC/USDT` 必须扣减 `USDT`，不能用账户展示 currency 替代。

### 10.2 Kraken ledger

只有 Kraken 额外拉取 ledger：

- `transaction`、`transfer`、`derivativescrossexchangetransfer` 根据 in/out 映射为 `deposit` / `withdraw`；
- `reward`、`staking` 映射为 `dividend`；
- note 使用 `lid:<ledger-id>`，与成交 `tid:` 命名空间隔离；
- 未识别但影响余额的 ledger 类型会抛 `ValueError`，不会静默遗漏。

同步支持 `--dry-run` 与 `-o`：前者仅显示统计，后者仅生成待审查 CSV；没有这两个选项时才调用 `do_append()` 写入账本。

限制：不同交易所的 CCXT 分页、全市场 `fetch_my_trades` 支持度不同；ledger 目前仅覆盖 Kraken。

## 11. Polymarket 同步与结算

### 11.1 Activity 到 CSV

`sync_polymarket()` 的数据流：

```text
profile wallet / proxy wallet
  → Polymarket Data API /activity 分页
  → TRADE activity
  → pm:<market-slug>:yes|no ticker 的 swap 行
  → activity ID / 完整行去重
  → 可选 resolved token settlement
  → do_append()
```

`activity_to_stock_row()` 只接受 `TRADE`：

- BUY：`USD → pm:<slug>:yes|no`；
- SELL：`pm:<slug>:yes|no → USD`；
- `usdcSize` 缺失时用 `size × price` 推导；
- note 优先记录 `polymarket id:<activity-id> tx:<hash>`。

活动的 side、outcome、slug、交易 hash、数量或价格不合法时会抛 `ValueError`，而不是生成含糊的资金记录。

### 11.2 去重

去重不是以裸 `transactionHash` 为唯一键：一笔链上交易可含多个 fill。系统结合：

1. 历史 note 中的 `activity id`；
2. 规范化后的完整 CSV 行 identity；
3. 当前同步批次内已见 identity。

这样同 tx 的不同 fill 可分别写入，但完全重复的 activity/行会跳过。

### 11.3 自动结算

对同步后仍持有的正数 Polymarket position：

1. `_project_pm_positions()` 用 snapshot 加本批新增活动推演仓位；
2. 查询 Gamma 市场元数据；
3. 仅在市场 closed 且具有 resolved/finalized 语义、outcome payout 明确为 0 或 1 时生成 settlement；
4. 写 `pm token → USD` 的 swap，note 带 `polymarket settlement token:...`；
5. 已有 settlement token 不再重复生成。

限制：只自动结算正仓；结算日期是本机当前日期而非官方结算时间；Gamma 网络或元数据失败时该市场会跳过；快照若未先与 CSV 对齐，投影数量也可能不可信。因此真实同步前应先 `ft verify`。

## 12. 关键限制与运维建议

1. **快照派生而非真相**：先修 CSV，再 `verify --fix`；不要只改 snapshot 伪装账本已修复。
2. **没有 FX 模块**：不要把 USD、HKD、CNY、USDT 等持仓盈亏直接相加。
3. **直接命令与历史回放的风险规则不同**：历史导入可以重放负仓，`do_swap` 则要求来源足额；需明确这是导入兼容与交互约束的差异。
4. **行情失败是软失败**：`N/A` 需要区分为“无报价/网络失败/不支持 ticker”，不能当作零价值。
5. **同步写入前先 dry-run**：交易所和 Polymarket 同步都支持 dry-run 或输出 CSV，应先审查新增行、去重统计和币种腿，再真正 append。
6. **Git 暂存范围要注意**：stock 写入和 snapshot 写入可能触发 `git add -A`；真实账本有其他未提交内容时，先记录 `git status`，不要假设只会暂存本次行。
7. **并发不是设计目标**：无锁、无数据库事务；避免两个 `ft` 写入命令同时操作同一账本目录。

## 13. 测试覆盖的代表性行为

`tests/test_stock.py`、`tests/test_snapshot.py` 覆盖了该系统的关键契约，包括：

- 单文件 YAML/CSV 写失败不截断旧文件；
- 直接交易与多日期 append 的异常回滚；
- 同 ticker 成本币种冲突的零副作用阻断；
- 归零 checkin 后换成本币种重新建仓；
- 历史 backfill 的全局时间序重放；
- 动态 `base_currencies`，例如 `USDT/USDG` 现金兑换；
- 已配置货币不进入报价 API、多币种不跨币种合计；
- 非本账户货币 position 的 N/A 展示；
- 空头持仓的负市值展示；
- CSV 与 snapshot 的 shares、成本和成本币种一致性验证。

这些测试说明系统的边界条件已被显式建模；但它们不等价于券商、交易所或 Polymarket 的外部余额对账。外部对账仍需用官方对账单/API 作为独立证据。
