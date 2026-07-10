# Unified Swap Accounting — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite ft's accounting model so all assets (including USD/USDT) are positions and all trades are swaps. No backward compatibility with old CSV/snapshot formats.

**Architecture:** Single unified position structure `{shares, avg_cost, cost_currency}` for all assets. All trades are `swap(from_ticker, to_ticker)`. Cash is not special. CSV format changes from 10-column to 12-column. Snapshot drops `cash`/`cash_map` fields.

**Tech Stack:** Python 3.11, PyYAML, ccxt, pytest

---

### Task 1: Update models.py — New CSV fields and actions

**Files:**
- Modify: `src/ft/models.py`

- [ ] **Step 1: Update CSV_FIELDS and VALID_ACTIONS**

```python
# In models.py, replace the CSV_FIELDS and VALID_ACTIONS sections:

# CSV fields for security trades (12 columns)
CSV_FIELDS = [
    "date", "action", "from_ticker", "to_ticker",
    "from_amount", "to_amount", "price", "commission",
    "commission_asset", "currency", "account_name", "note",
]

# Valid actions
VALID_ACTIONS = {"swap", "deposit", "withdraw", "dividend", "checkin"}
```

- [ ] **Step 2: Run existing tests to confirm they break (expected)**

Run: `cd ~/.hermes/skills/finance/finance-tracker && python -m pytest tests/test_stock.py -x --tb=short 2>&1 | tail -20`
Expected: FAIL — CSV_FIELDS changed

- [ ] **Step 3: Commit**

```bash
cd ~/.hermes/skills/finance/finance-tracker
git add src/ft/models.py
git commit -m "refactor: unified swap CSV_FIELDS and VALID_ACTIONS"
```

---

### Task 2: Update accounts.yaml — Add base_currencies

**Files:**
- Modify: `~/.ft/accounts.yaml` (production)

- [ ] **Step 1: Add base_currencies to each security/crypto account**

Edit `~/.ft/accounts.yaml`. For each security/crypto account, add `base_currencies`:

```yaml
# IBKR — multi-currency brokerage
- active: true
  currency: USD
  name: IBKR
  type: security
  base_currencies: [USD, HKD, CNY]

# 港股证券
- active: true
  currency: HKD
  name: 港股证券
  type: security
  base_currencies: [HKD]

# 东方证券
- active: true
  currency: CNY
  name: 东方证券
  type: security
  base_currencies: [CNY]

# Polymarket
- active: true
  currency: USD
  name: Polymarket
  type: security
  base_currencies: [USD]

# 盈立证券
- active: true
  currency: USD
  name: 盈立证券
  type: security
  base_currencies: [USD]

# 嘉信证券
- active: true
  currency: USD
  name: 嘉信证券
  type: security
  base_currencies: [USD]

# Kraken — crypto exchange with fiat + stablecoin
- active: true
  currency: USD
  name: kraken
  type: crypto
  base_currencies: [USD, USDT, USDG]
```

- [ ] **Step 2: Commit**

```bash
cd ~/.ft
git add accounts.yaml
git commit -m "refactor: add base_currencies to security/crypto accounts"
```

---

### Task 3: Update stock.py — Record trade with new CSV format

**Files:**
- Modify: `src/ft/stock.py:137-187` (record_trade function)
- Modify: `src/ft/stock.py:16-23` (CSV_FIELDS and VALID_ACTIONS imports)

- [ ] **Step 1: Update imports and constants**

In `stock.py`, at the top, update the imports from models:

```python
from . import models
# models.CSV_FIELDS and models.VALID_ACTIONS are now the new versions
CSV_FIELDS = models.CSV_FIELDS
VALID_ACTIONS = models.VALID_ACTIONS
```

- [ ] **Step 2: Rewrite record_trade for new 12-column format**

Replace the `record_trade` function (lines 137-187):

```python
def record_trade(
    date: str,
    action: str,
    from_ticker: str,
    to_ticker: str,
    from_amount: float,
    to_amount: float,
    price: float,
    commission: float,
    commission_asset: str,
    currency: str,
    account_name: str,
    note: str = "",
) -> dict:
    """Write a trade row to records/security/{date[:10]}.csv.
    Returns the row dict that was written.
    """
    _ensure_finite_values(from_amount=from_amount, to_amount=to_amount,
                          price=price, commission=commission)
    records_dir = models.RECORDS_DIR
    date_key = date[:10]
    security_dir = records_dir / "security"
    security_dir.mkdir(parents=True, exist_ok=True)
    day_path = security_dir / f"{date_key}.csv"

    existing_rows = []
    if day_path.exists():
        with day_path.open(encoding="utf-8") as f:
            reader = csv.DictReader(f)
            existing_rows = list(reader)

    new_row = {
        "date": date,
        "action": action,
        "from_ticker": from_ticker,
        "to_ticker": to_ticker,
        "from_amount": str(from_amount),
        "to_amount": str(to_amount),
        "price": str(price),
        "commission": str(commission),
        "commission_asset": commission_asset,
        "currency": currency,
        "account_name": account_name,
        "note": note,
    }

    all_rows = existing_rows + [new_row]
    all_rows.sort(key=lambda r: r.get("date", ""))
    _write_security_csv(day_path, all_rows)
    return new_row
```

- [ ] **Step 3: Run tests to confirm they break as expected**

Run: `cd ~/.hermes/skills/finance/finance-tracker && python -m pytest tests/test_stock.py -x --tb=short 2>&1 | tail -10`
Expected: FAIL — function signature changed

- [ ] **Step 4: Commit**

```bash
cd ~/.hermes/skills/finance/finance-tracker
git add src/ft/stock.py
git commit -m "refactor: record_trade uses new 12-column swap format"
```

---

### Task 4: Rewrite stock.py — Replay logic (core change)

**Files:**
- Modify: `src/ft/stock.py:1299-1461` (_replay_security_rows)

- [ ] **Step 1: Rewrite _replay_security_rows**

Replace the entire `_replay_security_rows` function (lines 1299-1461):

```python
def _replay_security_rows(rows):
    """Replay security rows into unified position state.

    All trades are swaps between two assets. Positions track
    {shares, total_cost, cost_currency}.
    """
    positions = defaultdict(lambda: {"shares": 0.0, "total_cost": 0.0, "cost_currency": ""})

    def _normalize(h):
        if abs(h["shares"]) < 1e-9:
            h["shares"] = 0.0
            h["total_cost"] = 0.0
        elif abs(h["total_cost"]) < 1e-9:
            h["total_cost"] = 0.0

    def _validate(a, ticker):
        h = positions[(a, ticker)]
        _ensure_finite_values(
            **{f"{a}.{ticker}.shares": h["shares"],
               f"{a}.{ticker}.total_cost": h["total_cost"]}
        )

    for row in rows:
        act = row.get("action", "")
        if act not in VALID_ACTIONS or not row.get("account_name"):
            continue
        a = row["account_name"]

        try:
            from_amt = float(row.get("from_amount") or 0)
            to_amt = float(row.get("to_amount") or 0)
            price = float(row.get("price") or 0)
            commission = float(row.get("commission") or 0)
        except (ValueError, KeyError):
            continue
        _ensure_finite_values(from_amount=from_amt, to_amount=to_amt,
                              price=price, commission=commission)

        commission_asset = (row.get("commission_asset") or "").lower()

        if act == "swap":
            from_t = (row.get("from_ticker") or "").lower()
            to_t = (row.get("to_ticker") or "").lower()

            # Decrease from_ticker
            h_from = positions[(a, from_t)]
            avg_from = h_from["total_cost"] / h_from["shares"] if h_from["shares"] else 0
            released = round(from_amt * avg_from, 2) if h_from["shares"] else from_amt
            h_from["shares"] = round(h_from["shares"] - from_amt, 10)
            h_from["total_cost"] = round(h_from["total_cost"] - released, 2)
            _normalize(h_from)
            _validate(a, from_t)

            # Increase to_ticker
            h_to = positions[(a, to_t)]
            h_to["shares"] = round(h_to["shares"] + to_amt, 10)
            h_to["total_cost"] = round(h_to["total_cost"] + released, 2)
            h_to["cost_currency"] = from_t
            _normalize(h_to)
            _validate(a, to_t)

            # Commission: decrease commission_asset
            if commission > 0 and commission_asset:
                h_fee = positions[(a, commission_asset)]
                avg_fee = h_fee["total_cost"] / h_fee["shares"] if h_fee["shares"] else 0
                fee_released = round(commission * avg_fee, 2) if h_fee["shares"] else commission
                h_fee["shares"] = round(h_fee["shares"] - commission, 10)
                h_fee["total_cost"] = round(h_fee["total_cost"] - fee_released, 2)
                _normalize(h_fee)
                _validate(a, commission_asset)

        elif act == "deposit":
            to_t = (row.get("to_ticker") or "").lower()
            h = positions[(a, to_t)]
            h["shares"] = round(h["shares"] + to_amt, 10)
            h["total_cost"] = round(h["total_cost"] + to_amt, 2)
            h["cost_currency"] = to_t
            _validate(a, to_t)

        elif act == "withdraw":
            from_t = (row.get("from_ticker") or "").lower()
            h = positions[(a, from_t)]
            avg = h["total_cost"] / h["shares"] if h["shares"] else 0
            released = round(from_amt * avg, 2)
            h["shares"] = round(h["shares"] - from_amt, 10)
            h["total_cost"] = round(h["total_cost"] - released, 2)
            _normalize(h)
            _validate(a, from_t)

        elif act == "dividend":
            to_t = (row.get("to_ticker") or "").lower()
            h = positions[(a, to_t)]
            h["shares"] = round(h["shares"] + to_amt, 10)
            h["total_cost"] = round(h["total_cost"] + to_amt, 2)
            h["cost_currency"] = to_t
            _validate(a, to_t)

        elif act == "checkin":
            t = (row.get("from_ticker") or "").lower()
            shares = to_amt
            h = positions[(a, t)]
            h["shares"] = shares
            h["total_cost"] = round(shares * price, 2)
            h["cost_currency"] = (row.get("currency") or t).lower()
            _validate(a, t)

    return positions
```

- [ ] **Step 2: Update _replay_security_csv to use new replay**

The function `_replay_security_csv` (which reads CSV files and calls `_replay_security_rows`) needs to parse the new 12-column format. Find it and ensure it passes the new fields correctly. The key change is that it no longer needs to handle `ticker`/`shares`/`amount` from the old format — it now reads `from_ticker`/`to_ticker`/`from_amount`/`to_amount`.

```python
def _replay_security_csv(records_dir=None):
    """Read all security CSVs and replay into positions."""
    if records_dir is None:
        records_dir = models.RECORDS_DIR
    security_dir = Path(records_dir) / "security"
    if not security_dir.exists():
        return {}

    all_rows = []
    for csv_file in sorted(security_dir.glob("*.csv")):
        with csv_file.open(encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                all_rows.append(row)

    return _replay_security_rows(all_rows)
```

- [ ] **Step 3: Run tests**

Run: `cd ~/.hermes/skills/finance/finance-tracker && python -m pytest tests/test_stock.py -x --tb=short 2>&1 | tail -10`
Expected: FAIL — replay function signature changed

- [ ] **Step 4: Commit**

```bash
cd ~/.hermes/skills/finance/finance-tracker
git add src/ft/stock.py
git commit -m "refactor: unified swap replay logic — single path for all trades"
```

---

### Task 5: Rewrite stock.py — Verify and Repair

**Files:**
- Modify: `src/ft/stock.py:1464-1589` (verify_security, repair_security)

- [ ] **Step 1: Rewrite verify_security**

Replace the `verify_security` function:

```python
def verify_security(records_dir=None):
    """Replay security CSV and compare against snapshot.
    Returns (ok: bool, report_lines: list[str]).
    """
    snap = load_snapshot()
    csv_positions = _replay_security_csv(records_dir)
    lines = []
    ok = True

    if not csv_positions:
        lines.append("📭 无 security CSV 记录")
        return True, lines

    # Get snapshot positions
    sec_accounts = snap.get("accounts", {}).get("security", {})
    snap_positions = {}
    for acct_name, acct_data in sec_accounts.items():
        for ticker, sp in acct_data.get("positions", {}).items():
            snap_positions[(acct_name, ticker)] = sp

    # Compare
    all_keys = set(csv_positions.keys()) | set(snap_positions.keys())
    for key in sorted(all_keys):
        acct_name, ticker = key
        csv_p = csv_positions.get(key, {})
        snap_p = snap_positions.get(key, {})
        csv_shares = csv_p.get("shares", 0)
        snap_shares = snap_p.get("shares", 0)
        if abs(csv_shares - snap_shares) > 1e-6:
            lines.append(f"  ❌ {acct_name}/{ticker}: CSV={csv_shares} vs 快照={snap_shares}")
            ok = False

    return ok, lines
```

- [ ] **Step 2: Rewrite repair_security**

Replace the `repair_security` function:

```python
def repair_security(records_dir=None):
    """Replay security CSV and write into unified snapshot accounts.security."""
    from datetime import datetime
    from .accounts import load_accounts

    positions = _replay_security_csv(records_dir)

    # Look up account metadata
    acct_meta = {a["name"]: a for a in load_accounts()
                 if a["type"] in ("security", "crypto")}

    accounts = {}
    for (acct_name, ticker), p in positions.items():
        if not ticker or p["shares"] == 0:
            continue
        if acct_name not in accounts:
            meta = acct_meta.get(acct_name, {})
            accounts[acct_name] = {
                "currency": meta.get("currency", ""),
                "base_currencies": meta.get("base_currencies", []),
                "positions": {},
            }
        accounts[acct_name]["positions"][ticker] = {
            "shares": round(p["shares"], 10),
            "avg_cost": round(p["total_cost"] / p["shares"], 2) if p["shares"] else 0.0,
            "cost_currency": p.get("cost_currency", ""),
        }

    snap = load_snapshot()
    snap.setdefault("accounts", {})["security"] = accounts

    # Clean up top-level duplicates
    top = snap["accounts"]
    for acct_name in list(accounts.keys()):
        if acct_name in top and acct_name != "security":
            del top[acct_name]

    snap["updated_at"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    save_snapshot(snap)
    pos_count = sum(len(a.get("positions", {})) for a in accounts.values())
    print(f"✅ 已从 CSV 重建快照: {len(accounts)} 个账户, {pos_count} 个标的")
```

- [ ] **Step 3: Run tests**

Run: `cd ~/.hermes/skills/finance/finance-tracker && python -m pytest tests/test_stock.py -x --tb=short 2>&1 | tail -10`
Expected: FAIL — function signatures changed

- [ ] **Step 4: Commit**

```bash
cd ~/.hermes/skills/finance/finance-tracker
git add src/ft/stock.py
git commit -m "refactor: verify/repair use unified position comparison"
```

---

### Task 6: Rewrite exchange_sync.py — All trades are swaps

**Files:**
- Modify: `src/ft/exchange_sync.py` (full rewrite of trade_to_rows)

- [ ] **Step 1: Rewrite trade_to_rows and supporting functions**

Replace the entire file content:

```python
"""Generic ccxt exchange private-trades sync → ft crypto records."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
import csv
import tempfile
from pathlib import Path

from .accounts import find_account
from .stock import CSV_FIELDS
from . import sync_common
from .stock import do_append
from .credentials import load_credentials, ensure_credentials_gitignored

UTC_PLUS_8 = timezone(timedelta(hours=8))


def _num(value) -> str:
    """Format a number as a normalized plain-decimal string."""
    try:
        d = Decimal(str(value))
    except (InvalidOperation, ValueError) as exc:
        raise ValueError(f"invalid numeric value: {value!r}") from exc
    if not d.is_finite():
        raise ValueError(f"invalid numeric value: {value!r}")
    text = format(d.normalize(), "f")
    return "0" if text == "-0" else text


def _format_trade_timestamp(ms) -> str:
    try:
        seconds = int(ms) / 1000
    except (TypeError, ValueError) as exc:
        raise ValueError(f"invalid trade timestamp: {ms!r}") from exc
    return (datetime.fromtimestamp(seconds, tz=timezone.utc)
            .astimezone(UTC_PLUS_8).strftime("%Y-%m-%d %H:%M:%S"))


def _blank_row(account_name: str) -> dict:
    row = {field: "" for field in CSV_FIELDS}
    row["currency"] = "USD"
    row["account_name"] = account_name
    return row


def _make_swap_row(account_name, date, from_ticker, to_ticker,
                   from_amount, to_amount, commission=0, commission_asset="",
                   note=""):
    """Create a single swap CSV row."""
    r = _blank_row(account_name)
    r["date"] = date
    r["action"] = "swap"
    r["from_ticker"] = from_ticker
    r["to_ticker"] = to_ticker
    r["from_amount"] = _num(from_amount)
    r["to_amount"] = _num(to_amount)
    r["price"] = ""
    r["commission"] = _num(commission) if commission else "0"
    r["commission_asset"] = commission_asset
    r["note"] = note
    return r


def _load_base_currencies(account_name: str) -> list[str]:
    """Load base_currencies for an account from accounts.yaml."""
    from .accounts import load_accounts
    for a in load_accounts():
        if a["name"] == account_name:
            return [c.lower() for c in a.get("base_currencies", [])]
    return []


def trade_to_rows(trade: dict, account_name: str, provider: str) -> list[dict]:
    """Map one ccxt trade to 1 ft stock CSV row (swap)."""
    tid = trade.get("id")
    if tid is None or str(tid) == "":
        raise ValueError(f"trade 缺少 id: {trade!r}")
    tid = str(tid)

    symbol = str(trade.get("symbol", ""))
    if "/" not in symbol:
        raise ValueError(f"无法拆分 trade symbol '{symbol}'（应形如 BASE/QUOTE）")
    base, quote = (part.strip().lower() for part in symbol.split("/", 1))
    if not base or not quote:
        raise ValueError(f"无法拆分 trade symbol '{symbol}'")

    side = str(trade.get("side", "")).lower()
    if side not in {"buy", "sell"}:
        raise ValueError(f"未知 trade side: {trade.get('side')!r}")

    date = _format_trade_timestamp(trade.get("timestamp"))
    price = trade.get("price")
    amount = trade.get("amount")
    cost = trade.get("cost")
    if cost is None:
        cost = Decimal(str(price)) * Decimal(str(amount))

    fee = trade.get("fee") or {}
    fee_cost = fee.get("cost")
    fee_ccy = str(fee.get("currency", "")).lower()
    has_fee = fee_cost is not None and Decimal(str(fee_cost)) != 0

    note = f"{provider} tid:{tid}"
    base_currencies = _load_base_currencies(account_name)

    # Determine swap direction
    if side == "buy":
        from_ticker, from_amount = quote, cost
        to_ticker, to_amount = base, amount
    else:
        from_ticker, from_amount = base, amount
        to_ticker, to_amount = quote, cost

    row = _make_swap_row(
        account_name, date,
        from_ticker=from_ticker, to_ticker=to_ticker,
        from_amount=from_amount, to_amount=to_amount,
        commission=fee_cost if has_fee else 0,
        commission_asset=fee_ccy if has_fee else "",
        note=note,
    )
    return [row]


def validate_crypto_account(account_name: str, currency: str = "USD") -> None:
    account = find_account(account_name, currency=currency)
    if account is None:
        raise ValueError(f"未知账户 '{account_name}' ({currency})，请先 ft acct add")
    if account.get("type") != "crypto":
        raise ValueError(
            f"账户 '{account_name}' ({currency}) 不是 crypto 类型，不能同步交易所成交"
        )


def build_client(provider: str, creds: dict):
    import ccxt
    if not hasattr(ccxt, provider):
        raise ValueError(f"ccxt 不支持交易所 '{provider}'")
    params = {"apiKey": creds["api_key"], "secret": creds["api_secret"]}
    if creds.get("password"):
        params["password"] = creds["password"]
    return getattr(ccxt, provider)(params)


def fetch_trades(client, since=None, symbols=None, limit=1000) -> list[dict]:
    """Paginate client.fetch_my_trades over symbols; merge & dedupe by trade id."""
    targets = list(symbols) if symbols else [None]
    seen: set[str] = set()
    out: list[dict] = []
    for symbol in targets:
        cursor = since
        while True:
            batch = client.fetch_my_trades(symbol, cursor, limit)
            if not batch:
                break
            fresh = 0
            for tr in batch:
                tid = str(tr.get("id"))
                if tid in seen:
                    continue
                seen.add(tid)
                out.append(tr)
                fresh += 1
            if len(batch) < limit:
                break
            last_ts = batch[-1].get("timestamp")
            if last_ts is None or fresh == 0:
                break
            cursor = int(last_ts) + 1
    return out


def _since_to_ms(since: str | None) -> int | None:
    if not since:
        return None
    dt = datetime.strptime(since, "%Y-%m-%d").replace(tzinfo=UTC_PLUS_8)
    return int(dt.timestamp() * 1000)


def filter_new_rows(rows, records_dir=None, account_name=None) -> list[dict]:
    return sync_common.filter_new_rows(
        rows, records_dir=records_dir, account_name=account_name, prefix="tid"
    )


def sync_exchange(provider, account_name, since=None, dry_run=False,
                  output=None, symbols=None, _client=None) -> list[dict]:
    """Fetch private trades via ccxt, map, dedupe, and (unless dry-run) append."""
    validate_crypto_account(account_name)

    client = _client
    if client is None:
        creds = load_credentials(provider)
        ensure_credentials_gitignored()
        client = build_client(provider, creds)

    trades = fetch_trades(client, since=_since_to_ms(since), symbols=symbols)
    rows: list[dict] = []
    for trade in trades:
        rows.extend(trade_to_rows(trade, account_name, provider))
    rows.sort(key=lambda r: r["date"])
    new_rows = filter_new_rows(rows, account_name=account_name)

    print(f"交易所: {provider}; 账户: {account_name}")
    print(f"成交: {len(trades)}; 映射行: {len(rows)}; 新增行: {len(new_rows)}")

    if output:
        sync_common.write_stock_csv(new_rows, output)
        print(f"✅ 已写出待导入 CSV: {output}")
        return new_rows

    if dry_run or not new_rows:
        if dry_run:
            print("DRY-RUN: 未写入 ft records")
        elif not new_rows:
            print("✅ 没有新增成交")
        return new_rows

    with tempfile.NamedTemporaryFile("w", newline="", encoding="utf-8",
                                     suffix=".csv", delete=False) as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerows(new_rows)
        tmp_path = f.name
    try:
        if not do_append(tmp_path):
            raise ValueError("交易所成交 append 失败")
    finally:
        Path(tmp_path).unlink(missing_ok=True)
    return new_rows
```

- [ ] **Step 2: Run tests**

Run: `cd ~/.hermes/skills/finance/finance-tracker && python -m pytest tests/test_exchange_sync.py -x --tb=short 2>&1 | tail -10`
Expected: FAIL — old tests expect BUY/SELL/SWAP_OUT/SWAP_IN

- [ ] **Step 3: Commit**

```bash
cd ~/.hermes/skills/finance/finance-tracker
git add src/ft/exchange_sync.py
git commit -m "refactor: exchange_sync produces unified swap rows"
```

---

### Task 7: Rewrite polymarket_sync.py — All trades are swaps

**Files:**
- Modify: `src/ft/polymarket_sync.py:96-146` (activity_to_stock_row)
- Modify: `src/ft/polymarket_sync.py:249-289` (_settlement_rows_for_open_positions)

- [ ] **Step 1: Rewrite activity_to_stock_row**

Replace the `activity_to_stock_row` function:

```python
def activity_to_stock_row(activity: dict, account_name: str = "Polymarket") -> dict | None:
    """Convert one Polymarket activity item to a ft stock CSV swap row.
    Returns None for non-TRADE activity.
    """
    if not isinstance(activity, dict):
        raise ValueError(f"activity item must be object: {type(activity).__name__}")
    if str(activity.get("type", "")).upper() != "TRADE":
        return None

    side = str(activity.get("side", "")).upper()
    if side not in {"BUY", "SELL"}:
        raise ValueError(f"unsupported Polymarket side: {activity.get('side')!r}")

    outcome = str(activity.get("outcome", "")).strip().lower()
    if outcome not in {"yes", "no"}:
        raise ValueError(f"unsupported Polymarket outcome: {activity.get('outcome')!r}")

    slug = str(activity.get("slug", "")).strip()
    if not slug:
        raise ValueError(f"missing Polymarket slug: {activity!r}")

    size = _decimal_text(activity.get("size"))
    price = _decimal_text(activity.get("price"))
    usdc_size = activity.get("usdcSize")
    if usdc_size is None:
        usdc_size = Decimal(size) * Decimal(price)
    else:
        usdc_size = Decimal(_decimal_text(usdc_size))

    tx_hash = activity.get("transactionHash") or activity.get("transaction_hash") or ""
    if not tx_hash:
        raise ValueError(f"missing Polymarket transactionHash: {activity!r}")

    ticker = f"pm:{slug}:{outcome}"
    usdc_amount = Decimal(str(usdc_size))

    # Swap: BUY = USD→token, SELL = token→USD
    if side == "BUY":
        from_ticker, from_amount = "USD", str(usdc_amount)
        to_ticker, to_amount = ticker, size
    else:
        from_ticker, from_amount = ticker, size
        to_ticker, to_amount = "USD", str(usdc_amount)

    return {
        "date": _format_activity_timestamp(activity.get("timestamp")),
        "action": "swap",
        "from_ticker": from_ticker,
        "to_ticker": to_ticker,
        "from_amount": from_amount,
        "to_amount": to_amount,
        "price": price,
        "commission": "0",
        "commission_asset": "",
        "currency": "USD",
        "account_name": account_name,
        "note": f"polymarket tx:{tx_hash}",
    }
```

- [ ] **Step 2: Rewrite _settlement_rows_for_open_positions**

Replace the `_settlement_rows_for_open_positions` function:

```python
def _settlement_rows_for_open_positions(account_name: str = "Polymarket") -> list[dict]:
    """Create swap rows for resolved Polymarket positions (token→USD at $1)."""
    from .snapshot import load_snapshot
    from .stock import _fetch_polymarket_prices

    snap = load_snapshot()
    account = snap.get("accounts", {}).get("security", {}).get(account_name, {})
    positions = account.get("positions", {}) if isinstance(account, dict) else {}
    tickers = [
        ticker for ticker, pos in positions.items()
        if str(ticker).startswith("pm:") and Decimal(str(pos.get("shares", 0) or 0)) > 0
    ]
    if not tickers:
        return []

    prices = _fetch_polymarket_prices(tickers)
    rows: list[dict] = []
    for ticker in sorted(tickers):
        if ticker not in prices:
            continue
        price = float(prices[ticker])
        if price not in (0.0, 1.0):
            continue
        shares = Decimal(str(positions[ticker].get("shares", 0)))
        price_dec = Decimal(str(int(price)))
        usd_amount = shares * price_dec
        rows.append({
            "date": _today_iso(),
            "action": "swap",
            "from_ticker": ticker,
            "to_ticker": "USD",
            "from_amount": _decimal_text(shares),
            "to_amount": _decimal_text(usd_amount),
            "price": _decimal_text(price_dec),
            "commission": "0",
            "commission_asset": "",
            "currency": "USD",
            "account_name": account_name,
            "note": "polymarket settlement",
        })
    return rows
```

- [ ] **Step 3: Update _existing_polymarket_identities**

The dedup logic needs to check `from_ticker`/`to_ticker` instead of `ticker`:

```python
def _existing_polymarket_identities(
    records_dir: Path | None = None,
    account_name: str | None = None,
) -> tuple[set[str], set[tuple[str, ...]]]:
    from .stock import _shared_row_identity
    if records_dir is None:
        records_dir = models.RECORDS_DIR
    security_dir = Path(records_dir) / "security"
    tx_hashes: set[str] = set()
    exact_rows: set[tuple[str, ...]] = set()
    if not security_dir.exists():
        return tx_hashes, exact_rows

    for path in sorted(security_dir.glob("*.csv")):
        with path.open(encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                if "action" not in row:
                    continue
                from_ticker = row.get("from_ticker") or row.get("ticker") or ""
                row_account_name = row.get("account_name") or ""
                if account_name is not None:
                    if row_account_name != account_name:
                        continue
                elif row_account_name != "Polymarket" and not from_ticker.startswith("pm:"):
                    continue
                tx = _tx_hash_from_note(row.get("note", ""))
                if tx:
                    tx_hashes.add(tx)
                exact_rows.add(_shared_row_identity(row))
    return tx_hashes, exact_rows
```

- [ ] **Step 4: Run tests**

Run: `cd ~/.hermes/skills/finance/finance-tracker && python -m pytest tests/ -x --tb=short 2>&1 | tail -15`
Expected: FAIL — old tests expect BUY/SELL actions

- [ ] **Step 5: Commit**

```bash
cd ~/.hermes/skills/finance/finance-tracker
git add src/ft/polymarket_sync.py
git commit -m "refactor: polymarket_sync produces unified swap rows"
```

---

### Task 8: Update snapshot.py — New structure

**Files:**
- Modify: `src/ft/snapshot.py:14-22` (DEFAULT)
- Modify: `src/ft/snapshot.py:219-271` (rebuild_snapshot_from_records)

- [ ] **Step 1: Update DEFAULT snapshot structure**

```python
DEFAULT = {
    "updated_at": "",
    "accounts": {
        "cash": {},
        "loan": {},
        "lend": {},
        "security": {},
    },
}
```

Note: The structure stays the same (cash/loan/lend/security keys). The change is inside `security` accounts — they no longer have `cash`/`cash_map`, only `positions`.

- [ ] **Step 2: Update rebuild_snapshot_from_records**

The `rebuild_snapshot_from_records` function currently calls `repair_security()`. Since we already rewrote `repair_security` in Task 5, this function should work as-is. Just verify it still calls `repair_security()` correctly.

```python
def rebuild_snapshot_from_records(records_dir=None):
    """Rebuild cash/loan/lend balances from CSV records."""
    if records_dir is None:
        records_dir = models.RECORDS_DIR

    from collections import defaultdict
    import csv
    import re
    from .stock import repair_security

    repair_security()  # This now writes unified positions

    snap = load_snapshot()
    for typ in ("cash", "loan", "lend"):
        typedir = Path(records_dir) / typ
        if not typedir.exists():
            continue
        acct_records = defaultdict(list)
        for csv_file in sorted(typedir.glob("*.csv")):
            with open(csv_file, encoding="utf-8") as f:
                for row in csv.DictReader(f):
                    acct = row.get("account_name", "").strip()
                    currency = row.get("currency", "").strip() or "CNY"
                    if acct:
                        acct_records[(acct, currency)].append(row)

        for (acct_name, currency), records in acct_records.items():
            records.sort(key=lambda r: r["date"])
            last_ci = -1
            for i, r in enumerate(records):
                if r.get("category") == "checkin":
                    last_ci = i
            if last_ci >= 0:
                desc = records[last_ci].get("description", "")
                m = re.search(r"[\d,]+\.?\d*", desc.replace(",", ""))
                bal = float(m.group()) if m else 0.0
                start = last_ci + 1
            else:
                bal = 0.0
                start = 0
            for r in records[start:]:
                cat = r.get("category", "")
                if cat in ("checkin", "transfer", "transfer_in", "transfer_out"):
                    continue
                try:
                    bal += float(r["amount"])
                except (ValueError, KeyError):
                    pass
            set_balance(snap, acct_name, typ, currency, round(bal, 2))

    snap["updated_at"] = "rebuilt"
    save_snapshot(snap)
    return snap
```

- [ ] **Step 3: Commit**

```bash
cd ~/.hermes/skills/finance/finance-tracker
git add src/ft/snapshot.py
git commit -m "refactor: snapshot structure supports unified positions"
```

---

### Task 9: Update report.py — Read positions including base currencies

**Files:**
- Modify: `src/ft/report.py` (report_networth function)

- [ ] **Step 1: Update report_networth to read positions**

The `report_networth` function needs to read `positions` from security accounts (including base currency positions like USD/USDT). Find the section that handles `security` accounts and update it:

```python
# In report_networth, the security section should iterate positions:
for acct_name, acct_data in sec_accounts.items():
    positions = acct_data.get("positions", {})
    for ticker, pos in positions.items():
        shares = pos.get("shares", 0)
        if shares == 0:
            continue
        # This includes USD/USDT positions (base currencies)
        # as well as stock/crypto positions
        ...
```

The exact change depends on how the report formats the output. The key point is: don't skip base currency positions (USD/USDT). They're positions now, not separate `cash` fields.

- [ ] **Step 2: Run tests**

Run: `cd ~/.hermes/skills/finance/finance-tracker && python -m pytest tests/test_report_csv.py -x --tb=short 2>&1 | tail -10`
Expected: May need updates

- [ ] **Step 3: Commit**

```bash
cd ~/.hermes/skills/finance/finance-tracker
git add src/ft/report.py
git commit -m "refactor: report reads unified positions including base currencies"
```

---

### Task 10: Update append.py — New CSV format

**Files:**
- Modify: `src/ft/append.py` (do_append function)

- [ ] **Step 1: Update do_append field validation**

The `do_append` function validates CSV fields. Update it to check for the new 12-column format:

```python
def do_append(file_path):
    """Import stock CSV batch to records/security/."""
    with open(file_path, encoding="utf-8") as f:
        reader = csv.DictReader(f)
        rows = list(reader)

    if not rows:
        print("❌ CSV 为空")
        return False

    actual_fields = reader.fieldnames or list(rows[0].keys())
    if set(actual_fields) != set(CSV_FIELDS):
        missing = set(CSV_FIELDS) - set(actual_fields)
        extra = set(actual_fields) - set(CSV_FIELDS)
        msg = []
        if missing:
            msg.append(f"缺少字段: {', '.join(sorted(missing))}")
        if extra:
            msg.append(f"多余字段: {', '.join(sorted(extra))}")
        print(f"❌ CSV 字段不匹配: {'; '.join(msg)}")
        return False

    # ... rest of validation using new fields
```

- [ ] **Step 2: Commit**

```bash
cd ~/.hermes/skills/finance/finance-tracker
git add src/ft/append.py
git commit -m "refactor: append validates new 12-column swap CSV format"
```

---

### Task 11: Rewrite tests — test_stock.py

**Files:**
- Rewrite: `tests/test_stock.py`

- [ ] **Step 1: Write new test_stock.py with swap-based tests**

```python
"""Tests for stock trading module — unified swap format."""
import csv
import sys
import tempfile
import time
from pathlib import Path

import pytest


@pytest.fixture
def tmp_env():
    """Setup temp .ft environment"""
    d = Path(tempfile.mkdtemp())
    records_dir = d / "records"

    from ft import models
    import ft.snapshot as snapshot_mod
    old_snapshot_path = snapshot_mod.SNAPSHOT_PATH
    old_ft = models.FT_DIR
    old_records = models.RECORDS_DIR
    old_accounts_path = models.ACCOUNTS_PATH
    models.FT_DIR = d
    models.RECORDS_DIR = records_dir
    models.ACCOUNTS_PATH = d / "accounts.yaml"
    snapshot_mod.SNAPSHOT_PATH = d / "snapshot.yaml"

    yield d

    snapshot_mod.SNAPSHOT_PATH = old_snapshot_path
    models.FT_DIR = old_ft
    models.RECORDS_DIR = old_records
    models.ACCOUNTS_PATH = old_accounts_path
    import shutil
    shutil.rmtree(d, ignore_errors=True)


def _swap_row(date="2026-07-09", from_ticker="USD", to_ticker="NVDA",
              from_amount=1500, to_amount=10, commission=0, commission_asset="",
              account_name="IBKR", currency="USD", note="test"):
    """Helper: create a swap CSV row dict."""
    return {
        "date": date, "action": "swap",
        "from_ticker": from_ticker, "to_ticker": to_ticker,
        "from_amount": str(from_amount), "to_amount": str(to_amount),
        "price": "", "commission": str(commission),
        "commission_asset": commission_asset,
        "currency": currency, "account_name": account_name, "note": note,
    }


def test_snapshot_empty(tmp_env):
    from ft.snapshot import DEFAULT
    from ft.stock import load_snapshot
    snap = load_snapshot()
    assert snap == DEFAULT


def test_replay_swap_buy(tmp_env):
    """Swap USD→NVDA creates NVDA position and reduces USD."""
    from ft.stock import _replay_security_rows
    rows = [_swap_row(from_ticker="USD", to_ticker="NVDA",
                      from_amount=1500, to_amount=10)]
    positions = _replay_security_rows(rows)
    assert positions[("IBKR", "nvda")]["shares"] == 10
    assert positions[("IBKR", "nvda")]["total_cost"] == 1500
    assert positions[("IBKR", "nvda")]["cost_currency"] == "usd"
    assert positions[("IBKR", "usd")]["shares"] == -1500


def test_replay_swap_sell(tmp_env):
    """Swap NVDA→USD creates USD position and reduces NVDA."""
    from ft.stock import _replay_security_rows
    # First buy, then sell
    rows = [
        _swap_row(from_ticker="USD", to_ticker="NVDA",
                  from_amount=1500, to_amount=10),
        _swap_row(from_ticker="NVDA", to_ticker="USD",
                  from_amount=5, to_amount=1000),
    ]
    positions = _replay_security_rows(rows)
    assert positions[("IBKR", "nvda")]["shares"] == 5
    assert positions[("IBKR", "usd")]["shares"] == -500  # -1500 + 1000


def test_replay_swap_with_commission(tmp_env):
    """Swap with commission deducts from commission_asset."""
    from ft.stock import _replay_security_rows
    rows = [_swap_row(from_ticker="USD", to_ticker="NVDA",
                      from_amount=1501, to_amount=10,
                      commission=1, commission_asset="USD")]
    positions = _replay_security_rows(rows)
    assert positions[("IBKR", "nvda")]["shares"] == 10
    assert positions[("IBKR", "usd")]["shares"] == -1501  # -1500 - 1 commission


def test_replay_asset_to_asset_swap(tmp_env):
    """Swap BTC→USDT."""
    from ft.stock import _replay_security_rows
    rows = [_swap_row(from_ticker="BTC", to_ticker="USDT",
                      from_amount=0.01, to_amount=1316.5,
                      account_name="kraken")]
    positions = _replay_security_rows(rows)
    assert positions[("kraken", "btc")]["shares"] == -0.01
    assert positions[("kraken", "usdt")]["shares"] == 1316.5
    assert positions[("kraken", "usdt")]["cost_currency"] == "btc"


def test_replay_deposit(tmp_env):
    """Deposit adds to position."""
    from ft.stock import _replay_security_rows
    rows = [{"date": "2026-07-09", "action": "deposit",
             "from_ticker": "EXTERNAL", "to_ticker": "USD",
             "from_amount": "0", "to_amount": "3000",
             "price": "", "commission": "0", "commission_asset": "",
             "currency": "USD", "account_name": "IBKR", "note": "test"}]
    positions = _replay_security_rows(rows)
    assert positions[("IBKR", "usd")]["shares"] == 3000
    assert positions[("IBKR", "usd")]["total_cost"] == 3000


def test_replay_dividend(tmp_env):
    """Dividend adds to position."""
    from ft.stock import _replay_security_rows
    rows = [{"date": "2026-07-09", "action": "dividend",
             "from_ticker": "DIV", "to_ticker": "USD",
             "from_amount": "0", "to_amount": "15.5",
             "price": "", "commission": "0", "commission_asset": "",
             "currency": "USD", "account_name": "IBKR", "note": "test"}]
    positions = _replay_security_rows(rows)
    assert positions[("IBKR", "usd")]["shares"] == 15.5


def test_replay_checkin(tmp_env):
    """Checkin sets position directly."""
    from ft.stock import _replay_security_rows
    rows = [{"date": "2026-07-09", "action": "checkin",
             "from_ticker": "BTC", "to_ticker": "",
             "from_amount": "0", "to_amount": "0.02049",
             "price": "64000", "commission": "0", "commission_asset": "",
             "currency": "USD", "account_name": "kraken", "note": "checkin"}]
    positions = _replay_security_rows(rows)
    assert positions[("kraken", "btc")]["shares"] == 0.02049
    assert positions[("kraken", "btc")]["total_cost"] == round(0.02049 * 64000, 2)


def test_replay_round_trip(tmp_env):
    """Buy then sell leaves correct residual."""
    from ft.stock import _replay_security_rows
    rows = [
        _swap_row(from_ticker="USD", to_ticker="NVDA",
                  from_amount=1500, to_amount=10),
        _swap_row(from_ticker="NVDA", to_ticker="USD",
                  from_amount=10, to_amount=2000),
    ]
    positions = _replay_security_rows(rows)
    assert positions[("IBKR", "nvda")]["shares"] == 0
    assert positions[("IBKR", "usd")]["shares"] == 500  # -1500 + 2000


def test_record_trade_writes_csv(tmp_env):
    """Trade creates CSV in security/ dir with new 12-column format."""
    from ft.stock import record_trade

    row = record_trade(
        date="2026-07-09 10:00:00",
        action="swap",
        from_ticker="USD",
        to_ticker="NVDA",
        from_amount=1500,
        to_amount=10,
        price=0,
        commission=1,
        commission_asset="USD",
        currency="USD",
        account_name="IBKR",
        note="test",
    )
    assert row["action"] == "swap"
    assert row["from_ticker"] == "USD"
    assert row["to_ticker"] == "NVDA"

    csv_path = tmp_env / "records" / "security" / "2026-07-09.csv"
    assert csv_path.exists()
    with csv_path.open() as f:
        reader = csv.DictReader(f)
        rows = list(reader)
    assert len(rows) == 1
    assert rows[0]["action"] == "swap"
```

- [ ] **Step 2: Run new tests**

Run: `cd ~/.hermes/skills/finance/finance-tracker && python -m pytest tests/test_stock.py -v 2>&1 | tail -25`
Expected: All new tests PASS

- [ ] **Step 3: Commit**

```bash
cd ~/.hermes/skills/finance/finance-tracker
git add tests/test_stock.py
git commit -m "test: rewrite test_stock.py for unified swap format"
```

---

### Task 12: Rewrite tests — test_exchange_sync.py

**Files:**
- Rewrite: `tests/test_exchange_sync.py`

- [ ] **Step 1: Write new test_exchange_sync.py**

```python
"""Tests for exchange sync module — unified swap format."""
import pytest


def test_ccxt_is_importable():
    import ccxt
    assert hasattr(ccxt, "kraken")


def _base_trade(**over):
    t = {"id": "T1", "timestamp": 1751852400000, "symbol": "BTC/USDT",
         "side": "buy", "price": 60000.0, "amount": 0.05, "cost": 3000.0,
         "fee": None}
    t.update(over)
    return t


def test_trade_to_rows_buyswap():
    """BTC/USDT buy → single swap row: USDT→BTC."""
    from ft.exchange_sync import trade_to_rows
    rows = trade_to_rows(_base_trade(), account_name="kraken", provider="kraken")
    assert len(rows) == 1
    assert rows[0]["action"] == "swap"
    assert rows[0]["from_ticker"] == "usdt"
    assert rows[0]["to_ticker"] == "btc"
    assert rows[0]["from_amount"] == "3000"
    assert rows[0]["to_amount"] == "0.05"


def test_trade_to_rows_sellswap():
    """BTC/USDT sell → single swap row: BTC→USDT."""
    from ft.exchange_sync import trade_to_rows
    rows = trade_to_rows(_base_trade(side="sell"), account_name="kraken", provider="kraken")
    assert len(rows) == 1
    assert rows[0]["action"] == "swap"
    assert rows[0]["from_ticker"] == "btc"
    assert rows[0]["to_ticker"] == "usdt"
    assert rows[0]["from_amount"] == "0.05"
    assert rows[0]["to_amount"] == "3000"


def test_trade_to_rows_eth_btc():
    """ETH/BTC buy → single swap row: BTC→ETH."""
    from ft.exchange_sync import trade_to_rows
    t = _base_trade(symbol="ETH/BTC", side="buy", price=0.05, amount=10.0, cost=0.5)
    rows = trade_to_rows(t, account_name="kraken", provider="kraken")
    assert len(rows) == 1
    assert rows[0]["action"] == "swap"
    assert rows[0]["from_ticker"] == "btc"
    assert rows[0]["to_ticker"] == "eth"
    assert rows[0]["from_amount"] == "0.5"
    assert rows[0]["to_amount"] == "10"


def test_trade_to_rows_with_fee():
    """Fee is embedded in swap row as commission."""
    from ft.exchange_sync import trade_to_rows
    t = _base_trade(fee={"cost": 1.5, "currency": "USDT"})
    rows = trade_to_rows(t, account_name="kraken", provider="kraken")
    assert len(rows) == 1
    assert rows[0]["commission"] == "1.5"
    assert rows[0]["commission_asset"] == "usdt"


def test_trade_to_rows_non_cash_fee():
    """Fee in non-cash asset (BNB) embedded in swap row."""
    from ft.exchange_sync import trade_to_rows
    t = _base_trade(fee={"cost": 0.001, "currency": "BNB"})
    rows = trade_to_rows(t, account_name="kraken", provider="kraken")
    assert len(rows) == 1
    assert rows[0]["commission"] == "0.001"
    assert rows[0]["commission_asset"] == "bnb"
```

- [ ] **Step 2: Run tests**

Run: `cd ~/.hermes/skills/finance/finance-tracker && python -m pytest tests/test_exchange_sync.py -v 2>&1 | tail -15`
Expected: All PASS

- [ ] **Step 3: Commit**

```bash
cd ~/.hermes/skills/finance/finance-tracker
git add tests/test_exchange_sync.py
git commit -m "test: rewrite test_exchange_sync.py for unified swap format"
```

---

### Task 13: Data migration — Regenerate snapshot and CSVs

**Files:**
- Script: `scripts/migrate_to_swap.py` (temporary, delete after)

- [ ] **Step 1: Write migration script**

```python
#!/usr/bin/env python3
"""One-time migration: regenerate snapshot from current state.

Reads existing snapshot positions + cash, writes new format.
No CSV regeneration needed — repair_security will rebuild from CSVs.
"""
import sys
sys.path.insert(0, "src")

from ft import models
from ft.snapshot import load_snapshot, save_snapshot
from ft.stock import repair_security

snap = load_snapshot()
sec = snap.get("accounts", {}).get("security", {})

for acct_name, acct_data in sec.items():
    if not isinstance(acct_data, dict):
        continue

    # Convert cash/cash_map to positions
    cash = acct_data.pop("cash", 0.0)
    cash_map = acct_data.pop("cash_map", {})

    positions = acct_data.setdefault("positions", {})

    # Add base currency positions from cash_map
    for ccy, amount in cash_map.items():
        if amount != 0 and ccy not in positions:
            positions[ccy] = {
                "shares": round(amount, 2),
                "avg_cost": 1.0,
                "cost_currency": ccy,
            }

    # Fallback: if no cash_map but has cash value
    if not cash_map and cash != 0:
        # Infer currency from account
        currency = acct_data.get("currency", "USD").lower()
        if currency not in positions:
            positions[currency] = {
                "shares": round(cash, 2),
                "avg_cost": 1.0,
                "cost_currency": currency,
            }

    # Add cost_currency to existing positions that lack it
    for ticker, pos in positions.items():
        if "cost_currency" not in pos:
            # Infer: base currencies cost in themselves, others in account currency
            from ft.accounts import load_accounts
            acct_meta = {a["name"]: a for a in load_accounts()}
            meta = acct_meta.get(acct_name, {})
            base_currencies = [c.lower() for c in meta.get("base_currencies", [])]
            if ticker.lower() in base_currencies:
                pos["cost_currency"] = ticker
            else:
                pos["cost_currency"] = acct_data.get("currency", "USD").lower()

    # Add base_currencies from accounts.yaml
    from ft.accounts import load_accounts
    acct_meta = {a["name"]: a for a in load_accounts()}
    meta = acct_meta.get(acct_name, {})
    if "base_currencies" not in acct_data and meta.get("base_currencies"):
        acct_data["base_currencies"] = meta["base_currencies"]

save_snapshot(snap)
print(f"✅ Migration complete. Security accounts: {list(sec.keys())}")
```

- [ ] **Step 2: Run migration**

Run: `cd ~/.hermes/skills/finance/finance-tracker && python scripts/migrate_to_swap.py`

- [ ] **Step 3: Verify with repair**

Run: `cd ~/.hermes/skills/finance/finance-tracker && python -m ft stock repair`
Expected: Positions match after migration

- [ ] **Step 4: Delete migration script**

```bash
rm ~/.hermes/skills/finance/finance-tracker/scripts/migrate_to_swap.py
```

- [ ] **Step 5: Commit**

```bash
cd ~/.hermes/skills/finance/finance-tracker
git add -A
git commit -m "chore: migrate snapshot to unified swap format"
```

---

### Task 14: Run all tests and verify

- [ ] **Step 1: Run full test suite**

Run: `cd ~/.hermes/skills/finance/finance-tracker && python -m pytest tests/ -v 2>&1 | tail -30`
Expected: All tests PASS

- [ ] **Step 2: Run verify on production data**

Run: `cd ~/.hermes/skills/finance/finance-tracker && python -m ft stock verify`
Expected: OK (or minor diffs to reconcile)

- [ ] **Step 3: Final commit if needed**

```bash
cd ~/.hermes/skills/finance/finance-tracker
git add -A
git commit -m "chore: all tests pass, unified swap accounting complete"
```
