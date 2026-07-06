# 加密货币账户类型（crypto）设计

日期：2026-07-07

## 目标

新增账户类型 `crypto`，用于记录加密货币资产（BTC、ETH、USDT 等），支持持仓成本追踪与实时市值估算。

## 运营模型：稳定币计价（2a）

- **USDT = 账户现金余额**，按 1 USDT = 1 USD 计。
- **BTC / ETH 等 = 持仓**（positions），平均成本以 USD 计。
- 用 USDT 买入 BTC 即"花掉现金 + 增加持仓"，与现有 security 账户的买卖模型完全一致。
- 不做 crypto-to-crypto swap（2a 模型下 USDT 即现金，无需独立兑换操作）。

## 核心思路：crypto 作为 security 的"同引擎新类型"

crypto 账户在**数据层完全复用 security 引擎**：

| 复用项 | 说明 |
|--------|------|
| snapshot 桶 | 持仓存入 `accounts.security`，与股票账户同桶（不同账户名，互不干扰） |
| CSV 记录 | 写入 `records/security/YYYY-MM-DD.csv`，沿用 stock CSV schema |
| 命令 | 直接用 `ft stock buy/sell/deposit/withdraw/checkin/list` |
| 重建/校验 | `repair_security` / `verify_security` / `_replay_security_csv` 自动覆盖 |
| 余额/报表 | `acct._compute_balance`、`report`、`acct list` 扫 `accounts.security` 桶，零改动生效 |

`crypto` 类型只承担两个职责：**账户分类展示**（图标🪙 / 标签"加密货币"）与**价格路由信号**。

### 为什么复用 security 桶而非独立桶

复用桶后，`report` / `verify` / `acct list` / `_compute_balance` 全部零改动自动生效。代价是股票与 crypto 持仓在底层同一个 `accounts.security` 桶，但因账户名不同，展示与查询互不干扰。独立桶方案需要镜像 `repair_security`、`verify`、report 聚合、余额计算等大量逻辑，收益不成正比。

### 关键前提（已验证）

- 交互式 `ft stock buy/sell/deposit/withdraw` 通过 `_ensure_account`（stock.py:123）在 security 桶 get-or-create，**不校验账户类型** → crypto 账户零改动即可用这些命令。
- 这些命令的 `--currency` 默认 `USD`（cli.py:92 等），与 USD crypto 账户匹配。
- 唯一的类型校验在 `do_append`（stock.py:321，批量 CSV 导入），需放开以接受 crypto。

## 具体改动

### 1. `models.py` — 注册类型 + CoinGecko 映射表

- `ACCOUNT_TYPES` 追加 `"crypto"`。
- `ACCOUNT_LABELS["crypto"] = "加密货币"`。
- `ACCOUNT_ICONS["crypto"] = "🪙"`。
- 新增币种注册表 `CRYPTO_IDS`（symbol → CoinGecko coin id）：

```python
CRYPTO_IDS = {
    "btc": "bitcoin",
    "eth": "ethereum",
    "usdt": "tether",
    "usdc": "usd-coin",
    "sol": "solana",
    "bnb": "binancecoin",
    "xrp": "ripple",
    "doge": "dogecoin",
    "ada": "cardano",
}
```

新增币种时在此表补一行即可。

### 2. `stock.py` — CoinGecko 价格路由

- 新增 `_fetch_crypto_prices(tickers: list[str]) -> dict[str, float]`：
  - 将 ticker（小写）映射为 CoinGecko id，批量请求
    `https://api.coingecko.com/api/v3/simple/price?ids=<ids>&vs_currencies=usd`。
  - honor `HTTP_PROXY` / `HTTPS_PROXY` 环境变量（与现有 yfinance 路径一致）。
  - 带浏览器风格 `User-Agent`（与 Polymarket 抓价一致，降低被拒风险）。
  - 失败返回 `{}`（表现为价格 N/A，不抛异常，与 yfinance 失败行为一致）。
  - 返回 key 用 ft 存储的原始 ticker（如 `btc`），value 为 USD 价。
- 修改 `_fetch_prices`：在拆分 polymarket / 常规 ticker **之前**，先分流 crypto ticker（小写命中 `CRYPTO_IDS`）交给 `_fetch_crypto_prices`，其余照旧走 polymarket / yfinance。

### 3. `stock.py` — 放开 `do_append` 类型校验

- 第 321 行 `if account.get("type") != "security"` 改为接受 `("security", "crypto")`，允许批量导入 crypto 交易 CSV。

## 用法

```bash
ft acct add 币安 --type crypto --currency USD
ft stock deposit --amount 5000 --account 币安                 # 充 USDT(现金)
ft stock buy  --ticker btc --shares 0.05 --price 60000 --account 币安
ft stock sell --ticker eth --shares 1    --price 3000  --account 币安
ft stock list                                                # BTC/ETH 自动走 CoinGecko
ft verify                                                    # CSV ↔ snapshot 一致性
```

## 边界与约定

- **币种**：crypto 账户统一用 USD（CoinGecko `vs_currency=usd`）。非 USD 的 crypto 账户不在本次范围。
- **小数股数**：security 引擎已支持 float 股数（Polymarket 已用），0.05 BTC 直接可用。
- **USDT 作为持仓**：2a 下 USDT 是现金而非持仓；但 `usdt` 保留在 `CRYPTO_IDS` 中，万一出现 USDT 持仓也可按 ≈$1 估值。
- **ticker 冲突**：`CRYPTO_IDS` 中的符号（btc/eth/…）与用户实际股票持仓冲突概率极低；如遇冲突，以 crypto 路由优先。
- **CoinGecko 限流/网络失败**：返回 `{}`，价格显示 N/A，不阻塞。

## 不做的事（YAGNI）

- 不新增 snapshot 桶、不新增 `records/crypto/` 目录、不新增 `ft crypto` 命令。
- 不做 crypto-to-crypto swap。
- 不支持非 USD crypto 账户。

## 测试（TDD）

单元：
- `_fetch_crypto_prices`：mock CoinGecko 响应，验证 symbol→id 映射、USD 价解析、失败返回 `{}`。
- `_fetch_prices` 三路由分流：crypto→CoinGecko、pm:→polymarket、其余→yfinance（各路 mock）。
- `do_append` 类型校验接受 `crypto`、拒绝其它非法类型。

集成：
- `ft acct add --type crypto` 成功，出现在 `acct list`。
- `ft stock deposit/buy/sell` 作用于 crypto 账户，snapshot 与 CSV 正确更新。
- `ft verify --fix` 从 CSV 重建含 crypto 账户的 snapshot 一致。
