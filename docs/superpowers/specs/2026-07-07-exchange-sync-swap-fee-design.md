# 通用交易所同步 + 兑换/手续费账本原语 设计

日期：2026-07-07

## 目标

把 `ft stock sync polymarket` 泛化为通用 `ft stock sync <provider>`，通过 ccxt 支持加密货币交易所（kraken、okx 等）的私有成交记录同步，落库到 crypto 账户。为支持币币成交与多币种手续费，给账本新增两个原语：**SWAP（兑换）** 与 **FEE（手续费）**。

## 背景

现有 `ft stock sync polymarket`（`polymarket_sync.py`）是一条 ETL 流水线：校验账户 → 解析身份 → 分页拉公开 Activity → 映射为 stock CSV 行 → tx_hash+整行去重 → dry-run/`-o`/append 三态落库。Polymarket 是公开只读、无凭证、成交恒 0 手续费、用链上 tx_hash 去重。交易所与之有 5 处质变：需 API 密钥私有签名、去重键是 trade id、落到 crypto 账户（真实 ticker 而非 `pm:` 伪 ticker）、有真实多币种手续费、每家 API 形状不同（用 ccxt 抹平）。

## 核心设计决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 凭证存储 | `~/.ft/credentials.yaml` + gitignore + chmod 600 | 一次配置长期用；严格防误提交 |
| API 对接 | ccxt 统一库 | 加新交易所近零成本 |
| 落库模型 | 混合：USDT/USD 对走 BUY/SELL，币币对走 SWAP（保持 2a：USDT=现金） | USDT 对复用现有引擎；币币对不碰现金 |
| SWAP 成本 | 成本结转（A）：换出币释放的成本原样结转给换入币 | 不依赖任何外部价，纯账本操作，USD 总成本守恒 |
| SWAP 存储 | CSV 只记物理事实（换出/换入数量），成本 replay 时按均价法结转 | 与 BUY/SELL 同哲学（CSV 存事实、成本靠推导），无衍生量写死 |
| 手续费 | 现金币(USDT/USD) fee 走现有 commission 扣现金；持仓币 fee 记独立 FEE 行 | 第三币 fee 不再是特例，统一为"少了 X 个某币" |

## 架构

### 1. 命令泛化：`ft stock sync <provider>`

`sync` 子命令组做成 provider 分发。`polymarket` 分支**原样保留零回归**；ccxt 支持的交易所走一条通用路径。

```bash
ft stock sync polymarket --wallet 0x...            # 现有，不动
ft stock sync kraken --account 币安 --dry-run       # 新增
ft stock sync okx    --account OKX  --since 2026-01-01
```

新增交易所通用参数：
- `--account <名称>`：目标 crypto 账户（默认与 provider 同名或需显式指定）。
- `--since <YYYY-MM-DD>`：起始日期（转 ccxt 毫秒时间戳），增量同步用。
- `--dry-run`：只拉取/映射/去重/预览，不写入。
- `-o/--output <csv>`：新增记录写出为 stock CSV。
- `--symbol <PAIR>`（可选，可重复）：只同步指定交易对，调试用。

### 2. 新模块 `exchange_sync.py`

复刻 `polymarket_sync.py` 的流水线结构，函数级对应：

| 职责 | 函数 | 说明 |
|------|------|------|
| 校验账户 | `validate_crypto_account(account_name, currency="USD")` | 目标须是已存在的 **crypto** 类型账户 |
| 建客户端 | `build_client(provider, creds)` | `getattr(ccxt, provider)({...})`；provider 非法则报错 |
| 拉成交 | `fetch_trades(client, since=None, symbols=None)` | 循环 `client.fetch_my_trades(symbol, since, limit)` 分页（ccxt 用 `since` 游标翻页），合并去重 trade id |
| 映射 | `trade_to_rows(trade, account_name)` | 一笔 ccxt trade → 一或多条 stock CSV 行（见"映射规则"）；未知形状抛 ValueError |
| 去重 | `filter_new_rows(rows, records_dir, account_name)` | 用 note 里的 trade id + 整行精确匹配双重去重（复用 polymarket 同名逻辑的思路，键换成 tid） |
| 落库 | `sync_exchange(provider, account_name, since, dry_run, output, symbols)` | 顶层编排，三态落库 |

**代码复用**：`filter_new_rows`、`write_stock_csv`、`_row_identity`、三态落库骨架与 polymarket 高度相同。实现时抽公共部分到一个共享 helper（如 `sync_common.py`），polymarket 与 exchange 都引用，避免复制粘贴；共享 helper 的去重按"note 中的通用 id token"工作（tx: 或 tid: 前缀均可）。

### 3. ccxt trade → stock CSV 映射规则

ccxt 统一 trade 结构关键字段：`id`、`timestamp`、`symbol`（如 `BTC/USDT`、`ETH/BTC`）、`side`（buy/sell）、`price`、`amount`（base 数量）、`cost`（quote 数量 = price×amount）、`fee`（`{cost, currency}`）。

拆 `symbol` 为 `base/quote`（都转小写做 ticker），按 quote 分流：

**A. quote ∈ {usdt, usd}（现金计价对）→ BUY/SELL**

- `side=buy`：`BUY base，shares=amount，price=price，amount=-cost，account 币种 USD`
- `side=sell`：`SELL base，shares=amount，price=price，amount=+cost`
- 现金腿由现有引擎处理（`cash += amount - commission`）。USDT 视为 USD 现金（1:1）。

**B. quote 为其它币（币币对）→ SWAP**

一笔成交 = 一次持仓换持仓，写两条行：
- `side=buy`（用 quote 买 base，例 `ETH/BTC` buy = 用 BTC 买 ETH）：
  - `SWAP_OUT，ticker=quote，shares=cost`（换出的 quote 数量）
  - `SWAP_IN，ticker=base，shares=amount`（换入的 base 数量）
- `side=sell`（卖 base 得 quote）：
  - `SWAP_OUT，ticker=base，shares=amount`
  - `SWAP_IN，ticker=quote，shares=cost`
- 两行共享 `note="<provider> tid:<id> swap:<id>"`；`price`/`amount`/`commission` 列留空。

### 4. SWAP 账本原语

**存储**：CSV 只记物理事实（换出/换入数量），不存任何 USD 金额。

```
date, action,   ticker, shares, price, amount, commission, currency, account_name, note
..., SWAP_OUT,  btc,    0.5,    ,      ,       ,           USD,      币安,          <provider> tid:T1 swap:T1
..., SWAP_IN,   eth,    10,     ,      ,       ,           USD,      币安,          <provider> tid:T1 swap:T1
```

**replay**（`_replay_security_rows` 新增分支）：
- `SWAP_OUT ticker=X shares=s`：`released = round(s × X当前avg_cost, 2)`（重放到此刻的均价）；`X.shares -= s`（`round(,10)`）、`X.total_cost -= released`；将 `released` 按 note 中的 `swap:<id>` 存入局部暂存表 `pending_swaps[id]`。
- `SWAP_IN ticker=Y shares=s`：`Y.shares += s`（`round(,10)`）、`Y.total_cost += pending_swaps.pop(id)`。
- 两腿必然同 `swap:<id>` 配对；SWAP_OUT 必在 SWAP_IN 之前（写库时保证顺序，且同秒时靠 note 关联而非纯时间排序——见"排序保证"）。
- 结果：USD 总成本守恒，不碰现金，不需任何外部价，`verify --fix` 确定性可重建。

**排序保证**：SWAP_OUT/SWAP_IN 同一秒 timestamp，现有 `all_rows.sort(key=date)` 是稳定排序，写入时按 OUT→IN 顺序 append 即可保持。为稳健，replay 时若遇到 SWAP_IN 但 `pending_swaps` 无对应 id，抛 ValueError（不静默）。

**下单命令**：新增 `do_swap(account, from_ticker, from_shares, to_ticker, to_shares, date, note)` 与 `ft stock swap` CLI，供手工记账（如 App 上的币币兑换）。

### 5. FEE 账本原语

规则：
- **fee 币 ∈ {usdt, usd}（现金币），且成交是 BUY/SELL（有现金腿）**：走现有 `commission` 字段，成交主行 `commission=fee.cost`，现有 `cash -= com` 逻辑处理，不新增行。
- **fee 币为持仓币（BNB/BTC/ETH 等），或成交是 SWAP（无现金腿）**：新增一条独立 FEE 行，`ticker=fee币`，`shares=fee.cost`，其余金额列留空。SWAP 若 fee 恰为 usdt/usd，则该 usdt 作为**持仓**记 FEE 行减 usdt 持仓（SWAP 语境下 usdt 也当持仓核销，避免去动本不该碰的现金腿）。

```
..., FEE, bnb, 0.001, , , , USD, 币安, <provider> tid:T1 fee
```

**replay** `FEE ticker=Z shares=s`：`Z.shares -= s`（`round(,10)`）、`Z.total_cost -= round(s × Z当前avg_cost, 2)`（按均价法核销）。

对 SWAP 成交若带持仓币 fee，同样追加一条 FEE 行（与两条 SWAP 行共享 note tid）。

### 6. 凭证 `credentials.py`

`~/.ft/credentials.yaml` 结构：
```yaml
kraken:
  api_key: "..."
  api_secret: "..."
okx:
  api_key: "..."
  api_secret: "..."
  password: "..."   # OKX passphrase
```

- `load_credentials(provider) -> dict`：读文件、取 provider 段；文件缺失或段缺失时抛 ValueError 引导配置（提示路径与所需字段）。
- `ensure_credentials_gitignored()`：确保 `~/.ft/.gitignore` 含 `credentials.yaml`；文件存在时 `chmod 600`。sync 交易所前调用。
- 密钥永不进 stdout/日志；同步命令打印 provider 与账户，绝不打印 key/secret。

### 7. 依赖

`pyproject.toml` dependencies 增加 `ccxt`。

## 数据模型改动

- `stock.py` `VALID_ACTIONS` 增加 `SWAP_OUT`、`SWAP_IN`、`FEE`。
- `_replay_security_rows`：新增 `SWAP_OUT`/`SWAP_IN`/`FEE` 三分支（如上）。
- CSV schema 10 列**不变**——SWAP/FEE 复用现有列，金额类列留空。
- `do_append` 的类型校验此前已放开 `("security","crypto")`（crypto 功能已实现），无需再改。

## 错误处理

- provider 不在 ccxt 中 → ValueError 列出。
- credentials 缺失/字段缺失 → ValueError 引导。
- ccxt 网络/认证失败 → 向上抛，CLI 打印 `❌ <msg>` 并 exit 1（不静默吞）。
- trade 缺 id/symbol 无法拆 base/quote/未知 side → ValueError（禁止静默丢弃）。
- SWAP_IN 找不到配对 released → ValueError。

## 测试（TDD）

全部 mock ccxt（`fetch_my_trades` 返回构造 trade）与文件系统，不碰真实交易所/网络。

单元：
- `trade_to_rows`：USDT 对 buy→BUY 行、USDT 对 sell→SELL 行、币币对 buy→SWAP_OUT+SWAP_IN、币币对 sell→反向、现金币 fee→commission、持仓币 fee→FEE 行、缺字段/未知 side→ValueError。
- `_replay_security_rows`：SWAP 两腿成本守恒（换出释放 = 换入接收）、SWAP 后 `verify` 一致、SWAP_IN 缺配对→ValueError、FEE 按均价减持仓与成本。
- `load_credentials`：正常读取、文件缺失报错、provider 段缺失报错。
- `filter_new_rows`：trade id 去重、整行去重。

集成：
- `sync_exchange` 全流程（mock client）：USDT 对 + 币币对 + 带 fee 混合 → dry-run 预览计数正确 → 真实 append → snapshot/CSV 一致 → 重复 sync 幂等 0 新增。
- `ft stock swap` 手工兑换 → snapshot 两持仓正确、总成本守恒、`verify` 通过。
- crypto 账户经 SWAP/FEE 后 `verify_security` 返回 ok。

## YAGNI（不做）

- 不做实时下单/撤单，只读同步历史成交。
- 不做出入金/staking/理财/convert 的自动识别（非 trade 类型跳过或报错）。
- 不改 polymarket 现有逻辑（仅抽公共 helper，行为不变）。
- 不做 crypto-to-crypto 成交的市价实现盈亏（B 方案），只做成本结转（A）。
