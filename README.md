# 📒 Finance Tracker (ft)

Multi-currency, CSV-based personal finance tracker with unified snapshot and stock trading support.

## Architecture

**Pure CSV + YAML snapshot — no database.**

```
~/.ft/
├── accounts.yaml       # Account metadata (name, type, currency, active)
├── mapping.yaml        # Payment method → account name mapping (for convert)
├── snapshot.yaml       # Unified snapshot (current balances + positions)
└── records/            # Daily transaction CSVs, sorted by account type
    ├── cash/2026-01-01.csv
    ├── loan/2026-01-15.csv
    └── security/2026-06-12.csv
```

**Dual-layer storage:**
- **CSV files** — immutable audit log, one file per day per account type
- **`snapshot.yaml`** — current state (balances + stock positions), queried instantly
- All writes (append/checkin/transfer/stock) update **both** CSV + snapshot
- All queries (report/acct list/stock list) read **only** snapshot — no CSV scanning

**Git version control:** `~/.ft/` is automatically a git repo. Every write operation auto-commits. Review history with `cd ~/.ft && git log`.

## Quick Start

```bash
ft acct list              # View accounts (auto-creates default accounts.yaml)
ft convert 支付宝.csv -s alipay -o alipay.csv       # Step 1: Bill → CSV
ft merge alipay.csv wechat.csv -o merged.csv        # Step 2: Dedup across sources
ft append merged.csv                                # Step 3: Append to daily records
ft report [--month 2026-06]                         # Net worth + expense + income
ft list [--account 支付宝余额] [--limit 10]         # Transaction history
ft checkin 支付宝余额 --balance 5000                 # Record balance snapshot
ft transfer --from 工行借记卡 --to 工行信用卡 --amount 3000  # Transfer between accounts
```

## Stock Trading

Security accounts use a separate CSV format for stock transactions.

```bash
ft stock buy --ticker nvda.us --shares 5 --price 120 --account IBKR
ft stock sell --ticker nvda.us --shares 2 --price 130 --account IBKR
ft stock dividend --ticker nvda.us --amount 10 --account IBKR
ft stock deposit --amount 1000 --account IBKR
ft stock init --ticker nvda.us --shares 45 --price 224.14 --account IBKR   # Initial position
ft stock checkin --account IBKR --ticker nvda.us --shares 45 --avg-cost 220  # Override position
ft stock list          # Portfolio with live prices from yfinance
```

**Average cost method:** Buy → weighted average. Sell → avg cost unchanged, cost deducted proportionally.

## Data Verification

```bash
ft verify                # Check CSV ↔ snapshot consistency
ft verify --fix          # Rebuild snapshot from CSV (full replay)
```

- **Security:** Replay all trades → compare positions + cash to snapshot
- **Cash/Loan/Lend:** Check all account_name entries are registered in accounts.yaml

## Multi-Currency & Cross-Currency

```bash
ft transfer --from 工行借记卡 --to IBKR --amount 36250 --to-amount 5000
```

Cross-currency transfers use explicit source/target amounts, not exchange rates. Reports group by currency separately — no forced conversion.

## Pipeline: Bill Import

```
① ft convert    →   ② AI review   →   ③ Manual fix   →   ④ ft merge   →   ⑤ AI review   →   ⑥ ft append
   Bill→CSV          Codex review        Fix errors        Dedup           Verify dedup       Daily CSVs
   +_refunds         per source                            +removed
```

## Install

```bash
git clone https://github.com/ssttkkl/finance-tracker.git
cd finance-tracker
uv sync
```

Dependencies: Python 3.11+, PyYAML, yfinance, openpyxl, qpdf, mutool (for PDF bills).

## License

MIT
