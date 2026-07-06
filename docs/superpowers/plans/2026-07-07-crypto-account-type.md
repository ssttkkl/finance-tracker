# 加密货币账户类型（crypto）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 `crypto` 账户类型，复用现有 security 引擎记录加密货币持仓（USDT=现金、BTC/ETH=持仓），并通过 CoinGecko 拉取实时价。

**Architecture:** crypto 账户在数据层完全复用 security（同 `accounts.security` 快照桶、同 `records/security/` CSV、同 `ft stock` 命令）。`crypto` 类型仅承担账户分类展示与价格路由。价格路由在 `_fetch_prices` 最前端按 `CRYPTO_IDS` 分流到 CoinGecko。

**Tech Stack:** Python 3.11、uv、pytest、urllib（CoinGecko `simple/price` 接口）。

## Global Constraints

- 运营模型固定为 2a：USDT=现金（1:1 USD），BTC/ETH=持仓，成本 USD 计价。
- 不新增 snapshot 桶、不新增 `records/crypto/` 目录、不新增 `ft crypto` 命令。
- crypto 账户统一 USD；非 USD 不在范围。
- 网络失败一律返回 `{}`（价格显示 N/A，不抛异常），与现有 yfinance/polymarket 行为一致。
- CoinGecko 请求 honor `HTTP_PROXY`/`HTTPS_PROXY`，带浏览器风格 `User-Agent`。
- 所有命令须经工作区 `~/.hermes/skills/finance/finance-tracker/.worktrees/crypto-account`，用 `uv run pytest` 跑测试。

---

### Task 1: 注册 crypto 账户类型 + CoinGecko 映射表

**Files:**
- Modify: `src/ft/models.py:17-30`（ACCOUNT_TYPES / LABELS / ICONS）
- Modify: `src/ft/models.py`（文件末尾追加 `CRYPTO_IDS`）
- Test: `tests/test_accounts.py`

**Interfaces:**
- Produces: `models.ACCOUNT_TYPES` 含 `"crypto"`；`models.CRYPTO_IDS: dict[str, str]`（symbol 小写 → CoinGecko id）。

- [ ] **Step 1: Write the failing test**

在 `tests/test_accounts.py` 末尾追加：

```python
def test_crypto_account_type_registered():
    from ft import models
    assert "crypto" in models.ACCOUNT_TYPES
    assert models.ACCOUNT_LABELS["crypto"] == "加密货币"
    assert models.ACCOUNT_ICONS["crypto"] == "🪙"


def test_crypto_ids_map():
    from ft import models
    assert models.CRYPTO_IDS["btc"] == "bitcoin"
    assert models.CRYPTO_IDS["eth"] == "ethereum"
    assert models.CRYPTO_IDS["usdt"] == "tether"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run pytest tests/test_accounts.py::test_crypto_account_type_registered tests/test_accounts.py::test_crypto_ids_map -v`
Expected: FAIL（`'crypto' in ...` AssertionError / `AttributeError: CRYPTO_IDS`）

- [ ] **Step 3: Write minimal implementation**

`src/ft/models.py`：在类型三张表加入 crypto 行——

```python
# 账户类型
ACCOUNT_TYPES = ("cash", "loan", "lend", "security", "crypto")
ACCOUNT_LABELS = {
    "cash": "现金",
    "loan": "贷款",
    "lend": "借款",
    "security": "证券",
    "crypto": "加密货币",
}
ACCOUNT_ICONS = {
    "cash": "💰",
    "loan": "💳",
    "lend": "📤",
    "security": "📈",
    "crypto": "🪙",
}
```

在 `src/ft/models.py` 文件末尾追加：

```python
# 加密货币符号 → CoinGecko coin id（新增币种在此补一行）
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

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run pytest tests/test_accounts.py::test_crypto_account_type_registered tests/test_accounts.py::test_crypto_ids_map -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/ft/models.py tests/test_accounts.py
git commit -m "feat: 注册 crypto 账户类型 + CoinGecko 映射表"
```

---

### Task 2: CoinGecko 价格抓取

**Files:**
- Modify: `src/ft/stock.py`（在 `_fetch_polymarket_prices` 之后、`_fetch_prices` 之前新增两个函数）
- Test: `tests/test_stock.py`

**Interfaces:**
- Consumes: `models.CRYPTO_IDS`。
- Produces:
  - `_http_get_json(url: str, timeout: int = 15) -> dict`（带 proxy + UA 的 JSON GET；失败向上抛异常）。
  - `_fetch_crypto_prices(tickers: list[str]) -> dict[str, float]`（入参为 ft 原始 ticker 如 `["btc","eth"]`，返回 `{原始ticker: usd价}`；失败返回 `{}`）。

- [ ] **Step 1: Write the failing test**

在 `tests/test_stock.py` 末尾追加：

```python
def test_fetch_crypto_prices_maps_symbols_to_usd(monkeypatch):
    from ft import stock

    def fake_get(url, timeout=15):
        assert "api.coingecko.com/api/v3/simple/price" in url
        assert "vs_currencies=usd" in url
        assert "bitcoin" in url and "ethereum" in url
        return {"bitcoin": {"usd": 61000.0}, "ethereum": {"usd": 3000.0}}

    monkeypatch.setattr(stock, "_http_get_json", fake_get)
    prices = stock._fetch_crypto_prices(["btc", "eth"])
    assert prices == {"btc": pytest.approx(61000.0), "eth": pytest.approx(3000.0)}


def test_fetch_crypto_prices_unknown_symbol_ignored(monkeypatch):
    from ft import stock

    monkeypatch.setattr(stock, "_http_get_json",
                        lambda url, timeout=15: {"bitcoin": {"usd": 61000.0}})
    prices = stock._fetch_crypto_prices(["btc", "notacoin"])
    assert prices == {"btc": pytest.approx(61000.0)}


def test_fetch_crypto_prices_network_failure_returns_empty(monkeypatch):
    from ft import stock

    def boom(url, timeout=15):
        raise OSError("network down")

    monkeypatch.setattr(stock, "_http_get_json", boom)
    assert stock._fetch_crypto_prices(["btc"]) == {}


def test_fetch_crypto_prices_empty_input():
    from ft import stock
    assert stock._fetch_crypto_prices([]) == {}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run pytest tests/test_stock.py -k fetch_crypto_prices -v`
Expected: FAIL（`AttributeError: module 'ft.stock' has no attribute '_fetch_crypto_prices'`）

- [ ] **Step 3: Write minimal implementation**

在 `src/ft/stock.py` 中 `_fetch_polymarket_prices` 函数结束后、`def _fetch_prices` 之前插入：

```python
def _http_get_json(url: str, timeout: int = 15) -> dict:
    """GET JSON with browser-style UA and HTTP(S)_PROXY support. Raises on failure."""
    import json
    import os
    import urllib.request

    headers = {"User-Agent": "Mozilla/5.0", "Accept": "application/json"}
    proxy = os.environ.get("HTTP_PROXY") or os.environ.get("HTTPS_PROXY")
    if proxy:
        opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({"http": proxy, "https": proxy})
        )
    else:
        opener = urllib.request.build_opener()
    req = urllib.request.Request(url, headers=headers)
    with opener.open(req, timeout=timeout) as resp:
        return json.load(resp)


def _fetch_crypto_prices(tickers: list[str]) -> dict[str, float]:
    """Fetch USD prices for crypto tickers via CoinGecko simple/price.

    Input tickers are ft's stored symbols (e.g. ['btc','eth']).
    Returns {original_ticker: usd_price}; {} on failure.
    """
    if not tickers:
        return {}
    from urllib.parse import quote

    id_to_ticker = {}
    for t in tickers:
        cid = models.CRYPTO_IDS.get(str(t).strip().lower())
        if cid:
            id_to_ticker[cid] = t
    if not id_to_ticker:
        return {}

    ids = ",".join(sorted(id_to_ticker))
    url = (
        "https://api.coingecko.com/api/v3/simple/price"
        f"?ids={quote(ids)}&vs_currencies=usd"
    )
    try:
        data = _http_get_json(url)
    except Exception:
        return {}

    prices = {}
    if not isinstance(data, dict):
        return {}
    for cid, ticker in id_to_ticker.items():
        entry = data.get(cid)
        if isinstance(entry, dict) and "usd" in entry:
            try:
                prices[ticker] = float(entry["usd"])
            except (TypeError, ValueError):
                continue
    return prices
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run pytest tests/test_stock.py -k fetch_crypto_prices -v`
Expected: PASS（4 passed）

- [ ] **Step 5: Commit**

```bash
git add src/ft/stock.py tests/test_stock.py
git commit -m "feat: 新增 CoinGecko 加密货币价格抓取"
```

---

### Task 3: 在 `_fetch_prices` 中路由 crypto

**Files:**
- Modify: `src/ft/stock.py:940-960`（`_fetch_prices` 分流逻辑）
- Test: `tests/test_stock.py`

**Interfaces:**
- Consumes: `_fetch_crypto_prices`、`models.CRYPTO_IDS`。
- Produces: `_fetch_prices` 对 crypto ticker 走 CoinGecko、pm: 走 polymarket、其余走 yfinance。

- [ ] **Step 1: Write the failing test**

在 `tests/test_stock.py` 末尾追加：

```python
def test_fetch_prices_routes_crypto_to_coingecko(monkeypatch):
    """crypto ticker 走 CoinGecko，股票 ticker 走 yfinance，互不串。"""
    from ft import stock

    called = {}

    def fake_crypto(tickers):
        called["crypto"] = list(tickers)
        return {"btc": 61000.0}

    monkeypatch.setattr(stock, "_fetch_crypto_prices", fake_crypto)

    def fake_download(tickers, period=None, progress=False, auto_adjust=False):
        assert "BTC" not in tickers  # crypto 不应流入 yfinance
        cols = pd.MultiIndex.from_tuples([("Close", "AAPL")])
        return pd.DataFrame([[195.0], [196.5]], columns=cols,
                            index=pd.Index(["2026-06-12", "2026-06-13"]))

    fake_yf = type("FakeYF", (), {"download": staticmethod(fake_download)})
    monkeypatch.setitem(sys.modules, "yfinance", fake_yf)
    monkeypatch.setattr(time, "sleep", lambda *_: None)

    prices = stock._fetch_prices(["btc", "aapl.us"])
    assert called["crypto"] == ["btc"]
    assert prices["btc"] == pytest.approx(61000.0)
    assert prices["aapl.us"] == pytest.approx(196.5)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run pytest tests/test_stock.py::test_fetch_prices_routes_crypto_to_coingecko -v`
Expected: FAIL（`assert "BTC" not in tickers` 触发，或 `called` 无 "crypto" 键 → KeyError）

- [ ] **Step 3: Write minimal implementation**

`src/ft/stock.py`，把当前的：

```python
    pm_tickers = [nt for nt in normalized if nt.startswith("pm:")]
    regular_tickers = [nt for nt in normalized if not nt.startswith("pm:")]
```

改为：

```python
    crypto_tickers = [nt for nt in normalized if nt.lower() in models.CRYPTO_IDS]
    pm_tickers = [nt for nt in normalized if nt.startswith("pm:")]
    regular_tickers = [
        nt for nt in normalized
        if not nt.startswith("pm:") and nt.lower() not in models.CRYPTO_IDS
    ]
```

然后把当前的：

```python
    prices = _fetch_polymarket_prices(pm_tickers)
```

改为：

```python
    prices = _fetch_crypto_prices([ticker_map[nt] for nt in crypto_tickers])
    prices.update(_fetch_polymarket_prices(pm_tickers))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run pytest tests/test_stock.py::test_fetch_prices_routes_crypto_to_coingecko -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/ft/stock.py tests/test_stock.py
git commit -m "feat: _fetch_prices 分流 crypto 到 CoinGecko"
```

---

### Task 4: `do_append` 类型校验放开 crypto

**Files:**
- Modify: `src/ft/stock.py:321-323`
- Test: `tests/test_stock.py`

**Interfaces:**
- Produces: `do_append` 接受 `security` 与 `crypto` 类型账户，其余仍拒绝。

- [ ] **Step 1: Write the failing test**

在 `tests/test_stock.py` 末尾追加：

```python
def test_stock_append_accepts_crypto_account(tmp_env):
    """crypto 类型账户可导入股票风格记录。"""
    from ft.accounts import save_accounts
    from ft.stock import CSV_FIELDS, do_append
    from ft import models

    save_accounts([
        {"name": "币安", "type": "crypto", "currency": "USD", "active": True},
    ], models.ACCOUNTS_PATH)
    csv_path = tmp_env / "binance_crypto.csv"
    with csv_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerow({
            "date": "2026-07-07 10:00:00", "action": "BUY", "ticker": "btc",
            "shares": "0.05", "price": "60000", "amount": "-3000", "commission": "0",
            "currency": "USD", "account_name": "币安", "note": "crypto buy",
        })

    assert do_append(csv_path) is True
    assert (models.RECORDS_DIR / "security" / "2026-07-07.csv").exists()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run pytest tests/test_stock.py::test_stock_append_accepts_crypto_account -v`
Expected: FAIL（打印"不是 security 类型"，`do_append` 返回 False）

- [ ] **Step 3: Write minimal implementation**

`src/ft/stock.py`，把：

```python
        if account.get("type") != "security":
            print(f"❌ 第 {i} 行: 账户 '{row['account_name']}' ({row['currency']}) 不是 security 类型，不能导入股票记录")
            return False
```

改为：

```python
        if account.get("type") not in ("security", "crypto"):
            print(f"❌ 第 {i} 行: 账户 '{row['account_name']}' ({row['currency']}) 不是 security/crypto 类型，不能导入股票记录")
            return False
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run pytest tests/test_stock.py::test_stock_append_accepts_crypto_account tests/test_stock.py::test_stock_append_rejects_non_security_account -v`
Expected: PASS（2 passed —— 既接受 crypto 又仍拒绝 cash）

- [ ] **Step 5: Commit**

```bash
git add src/ft/stock.py tests/test_stock.py
git commit -m "feat: do_append 放开 crypto 类型账户"
```

---

### Task 5: 端到端集成测试

**Files:**
- Test: `tests/test_stock.py`

**Interfaces:**
- Consumes: Task 1-4 全部产出（crypto 类型、CoinGecko 路由、do_append 放开）。

- [ ] **Step 1: Write the failing test**

在 `tests/test_stock.py` 末尾追加：

```python
def test_crypto_account_buy_sell_verify_end_to_end(tmp_env, monkeypatch):
    """crypto 账户走 ft stock deposit/buy/sell → snapshot 与 CSV 一致。"""
    from ft.accounts import save_accounts
    from ft import models
    from ft.stock import (
        do_deposit, do_buy, do_sell, load_snapshot, verify_security,
    )

    save_accounts([
        {"name": "币安", "type": "crypto", "currency": "USD", "active": True},
    ], models.ACCOUNTS_PATH)

    do_deposit(amount=5000, currency="USD", account_name="币安",
               date="2026-07-07 09:00:00")
    do_buy(ticker="btc", shares=0.05, price=60000, commission=0,
           currency="USD", account_name="币安", date="2026-07-07 10:00:00")
    do_sell(ticker="btc", shares=0.02, price=62000, commission=0,
            currency="USD", account_name="币安", date="2026-07-07 11:00:00")

    snap = load_snapshot()
    acct = snap["accounts"]["security"]["币安"]
    # 现金: 5000 - 0.05*60000 + 0.02*62000 = 5000 - 3000 + 1240 = 3240
    assert acct["cash"] == pytest.approx(3240.0)
    assert acct["positions"]["btc"]["shares"] == pytest.approx(0.03)
    # verify_security 返回 (ok: bool, report_lines: list[str])
    ok, _lines = verify_security()
    assert ok is True
```

- [ ] **Step 2: Run test to verify it fails (baseline sanity)**

Run: `uv run pytest tests/test_stock.py::test_crypto_account_buy_sell_verify_end_to_end -v`
Expected: 若 Task 1-4 已实现则可能直接 PASS；否则按报错定位。先跑确认行为。

- [ ] **Step 3: 若断言与实际契约不符则修正测试**

依据 `verify_security` / `do_deposit` 的真实签名与返回值微调断言（不改产品代码——本任务是纯验证）。

- [ ] **Step 4: Run full suite**

Run: `uv run pytest -q`
Expected: 全绿（原 360 passed + 新增用例，1 skipped 不变）

- [ ] **Step 5: Commit**

```bash
git add tests/test_stock.py
git commit -m "test: crypto 账户买卖 + verify 端到端"
```

---

### Task 6: 手动冒烟 + SKILL.md 文档

**Files:**
- Modify: `SKILL.md`（在"账户管理"或"股票交易"节补 crypto 说明）
- 手动验证：真实 `~/bin/ft` 命令（**只在临时账户上，不污染真实数据**）

**Interfaces:**
- Consumes: 完整功能。

- [ ] **Step 1: 手动冒烟（dry 验证 CLI 分发，不写真实账户）**

Run（在 worktree 内用 uv 跑，指向临时 FT_DIR 避免污染 `~/.ft`）：

```bash
FT_DIR=/tmp/ft_crypto_smoke uv run python -c "
from ft import models
from pathlib import Path
models.FT_DIR = Path('/tmp/ft_crypto_smoke')
models.RECORDS_DIR = models.FT_DIR / 'records'
models.ACCOUNTS_PATH = models.FT_DIR / 'accounts.yaml'
import ft.snapshot as s; s.SNAPSHOT_PATH = models.FT_DIR / 'snapshot.yaml'
from ft.accounts import add_account
add_account('SmokeCrypto','crypto','USD')
from ft.stock import do_deposit, do_buy, do_list
do_deposit(amount=1000, currency='USD', account_name='SmokeCrypto', date='2026-07-07 09:00:00')
do_buy(ticker='btc', shares=0.01, price=60000, commission=0, currency='USD', account_name='SmokeCrypto', date='2026-07-07 10:00:00')
do_list()
"
rm -rf /tmp/ft_crypto_smoke
```

Expected: 打印持仓表含 `btc` 行（价格可能 N/A，取决于网络），无异常。

- [ ] **Step 2: 更新 SKILL.md**

在 SKILL.md 的"账户管理"表后补一段：

```markdown
### 加密货币账户（crypto）

`crypto` 类型账户复用 security 引擎：USDT=现金（1:1 USD），BTC/ETH 等为持仓，用 `ft stock` 命令操作，价格走 CoinGecko（honor `HTTP_PROXY`）。新增币种在 `models.py` 的 `CRYPTO_IDS` 补一行 symbol→CoinGecko id。

```bash
ft acct add 币安 --type crypto --currency USD
ft stock deposit --amount 5000 --account 币安        # 充 USDT(现金)
ft stock buy  --ticker btc --shares 0.05 --price 60000 --account 币安
ft stock sell --ticker eth --shares 1    --price 3000  --account 币安
ft stock list                                        # BTC/ETH 自动走 CoinGecko
```
```

- [ ] **Step 3: Commit**

```bash
git add SKILL.md
git commit -m "docs: SKILL.md 补充 crypto 账户用法"
```

---

## 完成标准

- `uv run pytest -q` 全绿。
- crypto 账户可 `acct add` / `ft stock deposit/buy/sell/list` / `verify`。
- `ft stock list` 中 BTC/ETH 走 CoinGecko 拉价，股票仍走 yfinance，互不干扰。
