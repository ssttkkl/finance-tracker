# Multi-Currency + Cross-Currency Transfer Design

## Context

Finance Tracker (`ft`) currently has a hardcoded 4-account model (现金/贷款/借款/证券) with all amounts assumed to be CNY. The user holds assets across CNY, USD, and HKD, and needs a flexible multi-account system that supports per-account currencies, cross-currency transfers (with exchange rates), and bill-import matching by account name + currency.

Existing data will be fully cleared — this is a fresh start.

## Data Model

### accounts

```sql
CREATE TABLE IF NOT EXISTS accounts (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT NOT NULL,                 -- e.g. "工行信用卡(1200)", "IBKR"
    type       TEXT NOT NULL CHECK(type IN ('cash','loan','lend','security')),
    currency   TEXT NOT NULL CHECK(currency IN ('CNY','USD','HKD')),
    created_at TEXT DEFAULT (datetime('now','localtime')),
    is_active  INTEGER DEFAULT 1,
    sort_order INTEGER DEFAULT 0,
    UNIQUE(name, currency)                   -- same name, different currency = different account
);
```

- No hardcoded default accounts.
- Users `ft acct add` their own accounts at setup.
- `UNIQUE(name, currency)` allows multi-currency cards like "工行信用卡(1200)" to have CNY + USD entries.

### transactions

```sql
CREATE TABLE IF NOT EXISTS transactions (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    date             TEXT NOT NULL,
    amount           REAL NOT NULL,            -- positive = inflow, negative = outflow (from account's perspective)
    account_id       INTEGER NOT NULL REFERENCES accounts(id),
    currency         TEXT NOT NULL,             -- derived from account (redundant for query convenience)
    category         TEXT NOT NULL CHECK(category IN ('income','expense','transfer','snapshot')),
    counterparty     TEXT DEFAULT '',
    description      TEXT DEFAULT '',
    payment_method   TEXT DEFAULT '',
    source_bill      TEXT DEFAULT '',
    source_file      TEXT DEFAULT '',
    transfer_pair_id INTEGER DEFAULT NULL,      -- binds both legs of a (cross-currency) transfer
    exchange_rate    REAL DEFAULT NULL,         -- for cross-currency transfer: e.g. 7.25 (1 USD = 7.25 CNY)
    snapshot_balance REAL DEFAULT NULL,         -- only non-NULL when category='snapshot'
    anomaly          TEXT DEFAULT NULL,
    created_at       TEXT DEFAULT (datetime('now','localtime'))
);
```

Key changes from current schema:
- `currency` added (derived from account on insert)
- `exchange_rate` added — populated only for cross-currency transfers
- `snapshot_balance` added — populated only for `category='snapshot'`
- `category` now includes `'snapshot'`
- `payment_method` preserved (used for import matching)
- `transfer_pair_id` kept as-is for paired transfer records

### import_log

```sql
CREATE TABLE IF NOT EXISTS import_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    source_bill TEXT NOT NULL,
    filename    TEXT DEFAULT '',
    imported_at TEXT DEFAULT (datetime('now','localtime')),
    total       INTEGER DEFAULT 0,
    new         INTEGER DEFAULT 0,
    skipped     INTEGER DEFAULT 0
);
```

Unchanged from current schema. A single bill file (e.g., 支付宝 CSV) may route transactions to multiple accounts (different payment_methods → different accounts), so `account_id` at the import_log level would be misleading.

### snapshots (REMOVED)

Functionality merged into `transactions` via `category='snapshot'` + `snapshot_balance` column. No separate snapshots table.

## CLI Commands

### `ft acct` — Account Management

```
ft acct add <name> --type <type> --currency <CUR>         # Create account
ft acct rename <name> <new-name> --currency <CUR>          # Rename (need currency to disambiguate)
ft acct delete <name> --currency <CUR>                     # Delete (requires no linked transactions)
ft acct list                                               # List all accounts with type/currency/balance
ft acct activate/deactivate <name> --currency <CUR>        # Toggle active status
```

### `ft import` — Bill Import (modified)

```
ft import file.csv --alipay                                # Match by payment_method + transaction currency
ft import file.xlsx --wechat
ft import file.pdf --icbc --password xxx
```

**Import matching logic (for each transaction):**
1. Parse bill → extract `payment_method` string and **transaction currency** (from bill fields like 交易币种/入账币种)
2. Query `accounts` table: `WHERE name=? AND currency=?`
3. **Exactly 1 match** → write to that account
4. **Zero matches** → error, skip this record
5. **Multiple matches** → error, skip (shouldn't happen with UNIQUE(name,currency) constraint)

**Special handling:**
- Keywords like "购汇"/"跨境" in description → auto-mark as cross-currency transfer
- Credit card repayment/purchase routing removed (now handled by account matching)

### `ft checkin` — Balance Calibration (new implementation)

```
ft checkin "工行借记卡" --balance 12345.67                  # Today's calibration
ft checkin "工行借记卡" --balance 12000.00 --date 2026-06-01  # Past calibration
```

**Implementation:**
- Inserts into `transactions` table with:
  - `amount = 0`
  - `category = 'snapshot'`
  - `snapshot_balance = <balance>`
  - `account_id` resolved from account name
- Shows up in `ft list` timeline naturally
- Report queries filter `WHERE category != 'snapshot'` for financial calculations

### `ft transfer` — Transfer / Currency Exchange (new)

```
# Same-currency transfer
ft transfer --from "工行借记卡" --to "支付宝余额" --amount 5000 \
  --date 2026-06-10 --description "充值支付宝"

# Cross-currency transfer (exchange)
ft transfer --from "工行借记卡" --to "IBKR" --amount 5000 \
  --rate 7.25 --date 2026-06-10 --description "入金IBKR"
```

**Implementation:**
- Splits into 2 `transactions` records, bound by `transfer_pair_id`
- Record A (from account): `amount = -5000`, `category='transfer'`, `exchange_rate = NULL` (or 7.25 for cross-currency)
- Record B (to account, auto-calculated):
  - Same-currency: `amount = +5000`
  - Cross-currency: `amount = +5000/7.25 = +689.66`
- `--rate` is **required for cross-currency** (when from/to currencies differ), forbidden for same-currency
- `--amount` is **the amount leaving the source account**

### `ft report` — Reporting (modified)

Multi-currency display, grouped by currency. No cross-currency conversion.

```
📈 资产负债总览
═══════════════════════════════════════
💰 工行借记卡 (cash · CNY)         ¥12,345.67
💰 支付宝余额 (cash · CNY)         ¥3,210.00
💳 工行信用卡(1200) (loan · CNY)   ¥-1,500.00
────────────────────────────────────
  cash · CNY 合计                   ¥15,555.67
  loan · CNY 合计                   ¥-1,500.00

📈 IBKR (security · USD)           $12,500.00
────────────────────────────────────
  security · USD 合计               $12,500.00

📈 富途 (security · HKD)           HK$80,000.00
────────────────────────────────────
  security · HKD 合计               HK$80,000.00
```

### `ft list` — List Transactions (modified)

Shows transactions in chronological order. Snapshot rows appear naturally.

```
#   日期         账户       金额       类型    说明
───── ─────────── ───────── ──────── ──────── ──────────────────
  42  2026-01-01  工行借记卡  ¥0.00   📸校准  余额: ¥12,345.67
  43  2026-01-02  工行借记卡  ¥-15.50 支出    星巴克
```

### `ft income` / `ft export` / `ft log`

Minor modifications to use account names instead of hardcoded account types. Filtering and export include currency field.

## Cross-Currency Transfer Design Details

### When a cross-currency transfer is recorded

1. User runs: `ft transfer --from "工行借记卡" --to "IBKR" --amount 5000 --rate 7.25`
2. System resolves both accounts:
   - From: `工行借记卡` (cash, CNY)
   - To: `IBKR` (security, USD)
3. Currencies differ → `--rate` required
4. Creates two records:

**Record A (debit leg):**
```
date: 2026-06-10
amount: -5000.00
account_id: 工行借记卡.id
currency: CNY
category: transfer
description: "入金IBKR"
transfer_pair_id: 1
exchange_rate: 7.25
```

**Record B (credit leg, separate INSERT):**
```
date: 2026-06-10
amount: +689.66          (5000 / 7.25)
account_id: IBKR.id
currency: USD
category: transfer
description: "入金IBKR(from 工行借记卡)"
transfer_pair_id: 1       (same)
exchange_rate: NULL       (only populate on the debit leg — the "outgoing" record)
```

5. `ft list` shows both entries. `ft report` shows -5000 CNY in cash section, +689.66 USD in security section.

### Import-time cross-currency detection

For bills from alipay/wechat/icbc, "购汇" / "跨境" keywords trigger automatic cross-currency transfer marking:
- The debit side is already in the source bill (CNY leaving)
- The credit side (USD/HKD arriving) is logged in the other account's bill separately
- Import creates only the debit side with `exchange_rate` if detectable from the bill text
- User can optionally record the matching credit side manually

## Migration Strategy

Existing data will be fully cleared. Steps:
1. Drop all existing tables
2. Recreate with new schema
3. Print setup instructions for user to add accounts

## Implementation Order

1. `models.py` — rewrite constants, add currency/account definitions
2. `db.py` — rewrite init_db with new schema, remove hardcoded 4 accounts
3. `acct` module (new) — account CRUD CLI
4. `importers/*.py` — remove hardcoded account_id routing, implement name+currency matching
5. `transactions` module — rewrite insert logic with currency derivation
6. `cli.py` — restructure with acct subcommand, modify checkin/transfer/report
7. `report.py` — multi-currency grouped display with currency symbols
8. `transfer` module (new) — cross-currency transfer logic with exchange_rate
9. Tests — rewrite for new schema
