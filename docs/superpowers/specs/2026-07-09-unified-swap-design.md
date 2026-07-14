# ft unified swap accounting — design spec

**Date:** 2026-07-09
**Status:** Approved
**Scope:** stock.py, exchange_sync.py, snapshot.py, models.py, report.py, polymarket_sync.py, append.py, tests

## Problem

Current accounting treats cash and positions as two separate concepts:
- BUY/SELL directly modify a `cash` field while also updating positions
- SWAP uses a `pending_swaps` dict to transfer cost between two positions
- A `FIAT` set hack in replay routes fiat currencies to cash instead of positions
- `cash_legacy` and `cash_map` add extra comparison paths in verify/repair
- Kraken's multi-currency cash (USD + USDT) broke the original single-cash model, requiring repeated patches

The root cause: cash is special-cased instead of being treated as an asset.

## Design

### Core Principle

All assets — USD, USDT, BTC, NVDA, shares of anything — are positions with the same structure. All trades are swaps between two positions. Cash has no special status.

### Snapshot Structure

```yaml
accounts:
  security:
    IBKR:
      base_currencies: [USD, HKD, CNY]
      positions:
        USD:   {shares: 8158.9, avg_cost: 1.0, cost_currency: USD}
        HKD:   {shares: 0, avg_cost: 1.0, cost_currency: HKD}
        NVDA:  {shares: 25, avg_cost: 205.9, cost_currency: USD}
        GOOG:  {shares: 16, avg_cost: 347.09, cost_currency: USD}
    Kraken:
      base_currencies: [USD, USDT]
      positions:
        USD:   {shares: 0, avg_cost: 1.0, cost_currency: USD}
        USDT:  {shares: 1316.5, avg_cost: 1.0, cost_currency: USDT}
        USDG:  {shares: 4.05, avg_cost: 1.0, cost_currency: USDG}
        BTC:   {shares: 0.02049, avg_cost: 63710, cost_currency: USD}
        ETH:   {shares: 0.2028, avg_cost: 2692, cost_currency: USD}
```

- **`base_currencies`**: list of fiat/stablecoin tickers for this account. Determines what counts as "cash" for display and what triggers BUY vs SWAP in exchange_sync.
- **Position**: `{shares: float, avg_cost: float, cost_currency: str}`. All assets share this shape.
- **Base currency positions**: `avg_cost` is always 1.0, `cost_currency` = self. Shares represent the balance.
- **Non-base positions**: `avg_cost` and `cost_currency` track the acquisition cost in whatever currency was used.
- **`cash` field**: deleted. `cash_map`: deleted. `cash_legacy`: deleted.

### CSV Format

New 12-column CSV:

```
date, action, from_ticker, to_ticker, from_amount, to_amount, price, commission, commission_asset, currency, account_name, note
```

**Actions:**

| Action | Semantics | from_ticker | to_ticker | from_amount | to_amount |
|--------|-----------|-------------|-----------|-------------|-----------|
| `swap` | Exchange asset A for asset B | asset given | asset received | amount given | amount received |
| `deposit` | External money in | `EXTERNAL` | currency | 0 | amount |
| `withdraw` | Money out | currency | `EXTERNAL` | amount | 0 |
| `dividend` | Dividend received | `DIV` | currency | 0 | amount |
| `checkin` | Snapshot reconciliation | asset | — | 0 | shares |

**Commission**: `from_amount` / `to_amount` always record the **gross trade consideration, excluding commission**. `commission` is the separately charged fee, and `commission_asset` identifies the asset it is deducted from. No separate FEE action.

> Backward compatibility: rows with an empty `commission_asset` are legacy net-leg rows whose cash leg already includes the fee; replay must not charge them again.

**Examples:**

```
# Buy 10 NVDA at $150, commission $1: USD outflow = $1500 + $1
2026-07-09, swap, USD, NVDA, 1500, 10, 150, 1, USD, IBKR, ibkr

# Sell 5 NVDA at $200, commission $1: USD inflow = $1000 - $1
2026-07-09, swap, NVDA, USD, 5, 1000, 200, 1, USD, IBKR, ibkr

# BTC→USDT swap on Kraken
2026-07-09, swap, BTC, USDT, 0.01, 1316.5, 131650, 0, USDT, Kraken, kraken

# Deposit $3000 to IBKR
2026-07-09, deposit, EXTERNAL, USD, 0, 3000, 1, 0, USD, IBKR, ibkr

# IBKR dividend $15.50
2026-07-09, dividend, DIV, USD, 0, 15.5, 1, 0, USD, IBKR, ibkr

# Checkin Kraken positions
2026-07-09, checkin, BTC, BTC, 0, 0.02049, 64000, 0, USD, Kraken, checkin
2026-07-09, checkin, USDT, USDT, 0, 1316.5, 1, 0, USDT, Kraken, checkin
```

### Replay Logic

Single unified path — no branching on BUY/SELL/SWAP:

```python
def _replay_security_rows(rows):
    positions = defaultdict(lambda: {"shares": 0.0, "total_cost": 0.0, "cost_currency": ""})

    for row in rows:
        act = row["action"]
        a = row["account_name"]

        if act == "swap":
            from_t = row["from_ticker"].lower()
            to_t = row["to_ticker"].lower()
            from_amt = float(row["from_amount"])
            to_amt = float(row["to_amount"])
            commission = float(row["commission"] or 0)
            commission_asset = (row.get("commission_asset") or "").lower()

            # Decrease from_ticker
            h = positions[(a, from_t)]
            avg = h["total_cost"] / h["shares"] if h["shares"] else 0
            released = round(from_amt * avg, 2) if h["shares"] else from_amt
            h["shares"] = round(h["shares"] - from_amt, 10)
            h["total_cost"] = round(h["total_cost"] - released, 2)
            _normalize(h)

            # Increase to_ticker
            h2 = positions[(a, to_t)]
            h2["shares"] = round(h2["shares"] + to_amt, 10)
            h2["total_cost"] = round(h2["total_cost"] + released, 2)
            h2["cost_currency"] = from_t  # cost denominated in what was given
            _normalize(h2)

            # Commission: decrease commission_asset position
            if commission > 0 and commission_asset:
                hc = positions[(a, commission_asset)]
                avg_c = hc["total_cost"] / hc["shares"] if hc["shares"] else 0
                fee_released = round(commission * avg_c, 2) if hc["shares"] else commission
                hc["shares"] = round(hc["shares"] - commission, 10)
                hc["total_cost"] = round(hc["total_cost"] - fee_released, 2)
                _normalize(hc)

        elif act == "deposit":
            to_t = row["to_ticker"].lower()
            to_amt = float(row["to_amount"])
            h = positions[(a, to_t)]
            h["shares"] = round(h["shares"] + to_amt, 10)
            h["total_cost"] = round(h["total_cost"] + to_amt, 2)
            h["cost_currency"] = to_t

        elif act == "withdraw":
            from_t = row["from_ticker"].lower()
            from_amt = float(row["from_amount"])
            h = positions[(a, from_t)]
            avg = h["total_cost"] / h["shares"] if h["shares"] else 0
            released = round(from_amt * avg, 2)
            h["shares"] = round(h["shares"] - from_amt, 10)
            h["total_cost"] = round(h["total_cost"] - released, 2)
            _normalize(h)

        elif act == "dividend":
            to_t = row["to_ticker"].lower()
            to_amt = float(row["to_amount"])
            h = positions[(a, to_t)]
            h["shares"] = round(h["shares"] + to_amt, 10)
            h["total_cost"] = round(h["total_cost"] + to_amt, 2)
            h["cost_currency"] = to_t

        elif act == "checkin":
            t = row["from_ticker"].lower()
            shares = float(row["to_amount"])
            price = float(row["price"] or 0)
            h = positions[(a, t)]
            h["shares"] = shares
            h["total_cost"] = round(shares * price, 2)
            # cost_currency: use currency field if set, otherwise infer from context
            h["cost_currency"] = (row.get("currency") or t).lower()

    return positions
```

### Verify & Repair

Both become straightforward comparison of replayed positions vs snapshot positions:

```python
def verify_security():
    csv_positions = replay_from_csv()
    snap_positions = load_snapshot_positions()
    for key in set(csv_positions) | set(snap_positions):
        csv_p = csv_positions.get(key, {})
        snap_p = snap_positions.get(key, {})
        if abs(csv_p.get("shares", 0) - snap_p.get("shares", 0)) > 1e-6:
            report mismatch

def repair_security():
    csv_positions = replay_from_csv()
    write_to_snapshot(csv_positions)
```

No more cash/cash_map/legacy three-way comparison.

### exchange_sync Changes

- Delete `CASH_QUOTES` — base_currencies read from account config
- `trade_to_rows` always produces `swap` rows
- `is_cash_asset(symbol)` checks if symbol is in account's `base_currencies`
- Fee handling: single `commission` + `commission_asset` fields, no FEE row

```python
def trade_to_rows(trade, account_name, provider, base_currencies):
    base, quote = symbol.split("/")
    is_quote_cash = quote.lower() in [c.lower() for c in base_currencies]
    
    if side == "buy":
        if is_quote_cash:
            # USD → BTC
            row = _make_swap(from_ticker=quote, to_ticker=base,
                           from_amount=cost, to_amount=amount)
        else:
            # BTC → USDT
            row = _make_swap(from_ticker=quote, to_ticker=base,
                           from_amount=cost, to_amount=amount)
    else:  # sell
        if is_quote_cash:
            # BTC → USD
            row = _make_swap(from_ticker=base, to_ticker=quote,
                           from_amount=amount, to_amount=cost)
        else:
            # USDT → BTC
            row = _make_swap(from_ticker=base, to_ticker=quote,
                           from_amount=amount, to_amount=cost)
    
    # Fee embedded in row
    if has_fee:
        row["commission"] = fee_cost
        row["commission_asset"] = fee_currency
    return [row]
```

### report.py Changes

- Report reads `positions` from snapshot (including USD/USDT)
- Cash display: sum of `base_currencies` positions' shares
- Position display: all non-base positions with market value
- No more `cash` vs `positions` branching

### accounts.yaml Changes

- Add `base_currencies` field to each security/crypto account
- Example:

```yaml
- name: IBKR
  type: security
  currency: USD
  base_currencies: [USD, HKD, CNY]
- name: Kraken
  type: crypto
  currency: USD
  base_currencies: [USD, USDT]
- name: 东方财富
  type: security
  currency: CNY
  base_currencies: [CNY]
```

### Migration

No backward compatibility. All existing CSVs must be regenerated from current snapshot + transaction history. Steps:

1. Update accounts.yaml with `base_currencies`
2. Rewrite snapshot with new format (cash → positions)
3. Regenerate all security CSVs from current positions + known transactions
4. Verify: replay new CSVs → match new snapshot

### Deleted Concepts

- `cash` field in snapshot → replaced by base currency positions
- `cash_map` → deleted
- `cash_legacy` → deleted
- `FIAT` set → replaced by `base_currencies` from account config
- `pending_swaps` dict → deleted (cost transfer inline in swap)
- `_extract_quote_currency` → deleted
- `quote:XXX` note hack → deleted
- `FEE` action → embedded in swap commission fields
- `BUY` / `SELL` action → replaced by `swap`

### Test Plan

- test_stock.py: rewrite all tests with new CSV format
  - swap: buy asset, sell asset, asset-to-asset swap
  - swap with commission
  - deposit, withdraw, dividend
  - checkin
  - replay → verify → repair round-trip
- test_exchange_sync.py: rewrite with new trade_to_rows
  - quote=base_currency (USD→BTC)
  - quote=non-base (BTC→USDT)
  - fee in different assets
- test_snapshot.py: new structure with base_currencies
- test_report.py: adapted to read positions including base currencies
