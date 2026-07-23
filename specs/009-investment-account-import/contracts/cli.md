# CLI Contract: Investment Statement Import

**Feature**: 009-investment-account-import  
**Date**: 2026-07-23

## Command: `ft import`

### Signature

```bash
ft import <FILE|URL> --source <SOURCE> [OPTIONS]
```

### Arguments

| Argument | Required | Description |
|----------|----------|-------------|
| `<FILE\|URL>` | Yes | Path to statement file (PDF, CSV) or API endpoint identifier |

### Options

| Option | Required | Default | Description |
|--------|----------|---------|-------------|
| `--source <SOURCE>` | Yes | - | Data source identifier: `dfzq`, `binance`, `okx`, `polymarket` |
| `--account <NAME>` | Yes* | - | Target investment account name (*optional for DFZQ if account embedded in statement) |
| `--currency <CODE>` | No | Account default | Override currency (ISO 4217 code, e.g., `CNY`, `USD`) |
| `--since <DATE>` | No | - | Start date for API sync (format: `YYYY-MM-DD`, applies to exchange/polymarket sources) |
| `--until <DATE>` | No | Today | End date for API sync (format: `YYYY-MM-DD`) |
| `--dry-run` | No | false | Parse and validate without writing to database |
| `--password <PASS>` | No | - | PDF password (for encrypted DFZQ statements) |

### Source Types

#### File-Based Sources

**`dfzq`**: 东方证券 PDF statement
- File format: PDF (optionally password-protected)
- Requirements: `qpdf` (decrypt), `mutool` (text extraction)
- Account: Auto-detected from statement or override with `--account`
- Currency: CNY (hardcoded for DFZQ)

**Future** (out of scope for 009):
- `futu`: 富途证券 PDF/CSV
- `ib`: Interactive Brokers CSV
- `generic_csv`: Generic 10-column investment CSV

#### API-Based Sources

**`binance`**: Binance exchange (via ccxt)
- Requires: `--account`, `--since` (optional)
- Credentials: `FT_EXCHANGE_BINANCE_API_KEY`, `FT_EXCHANGE_BINANCE_API_SECRET` env vars or `~/.ft/credentials.json`
- Account type: Must be `crypto`

**`okx`**: OKX exchange (via ccxt)
- Same as Binance

**`polymarket`**: Polymarket prediction market
- Requires: `--account`
- Credentials: `FT_POLYMARKET_WALLET` env var (proxy wallet address)
- Account type: Must be `security`

### Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success (new import or idempotent duplicate) |
| 1 | User error (invalid arguments, missing file, account not found) |
| 2 | Parse error (unsupported format, corrupted file) |
| 3 | Validation error (negative balance, account type mismatch) |
| 4 | Storage error (database unavailable, transaction conflict) |

### Output

#### Success (New Import)

```
✅ Imported 42 records from dfzq_statement.pdf
   Batch ID: 550e8400-e29b-41d4-a716-446655440000
   Account: 东方证券 (CNY)
   Date range: 2026-06-01 to 2026-06-30
   Events:
     BUY: 15
     SELL: 8
     DEPOSIT: 2
     WITHDRAW: 1
     DIVIDEND: 3
     CHECKIN: 1
   Final balance: CNY 12,345.67
   Position summary: 5 tickers, total cost CNY 98,765.43
```

#### Success (Duplicate/Idempotent)

```
ℹ️  Already imported: dfzq_statement.pdf
   Batch ID: 550e8400-e29b-41d4-a716-446655440000
   Original import: 2026-07-15 14:32:10
   No changes made.
```

#### Error (Account Not Found)

```
❌ Account not found: 东方证券
   
   Available investment accounts:
     - Binance现货 (crypto, USD)
     - 富途证券 (security, HKD)
   
   Create account with: ft acct add 东方证券 --type security --currency CNY
```

#### Error (Parse Failure)

```
❌ Failed to parse dfzq_statement.pdf
   
   Page 3, line 127: unexpected format
   Raw text: "20260615 证券买入 600000 浦发银行 -- 12.50 ..."
   
   Expected 11 fields, found 10. Possible causes:
   - Broker updated statement format
   - PDF text extraction failed
   - File is corrupted
   
   Try manual extraction with: ft stock convert dfzq_statement.pdf --output preview.csv
```

#### Dry Run

```
🔍 Dry run: dfzq_statement.pdf

   Would import 42 records to 东方证券 (CNY):
     BUY: 15 (total: CNY 45,678.90)
     SELL: 8 (total: CNY 23,456.78)
     DEPOSIT: 2 (total: CNY 50,000.00)
     ...
   
   Projected final balance: CNY 12,345.67
   
   To commit, run without --dry-run.
```

### Examples

#### Example 1: Import DFZQ PDF

```bash
# Decrypt and import password-protected statement
ft import ~/Downloads/dfzq_202606.pdf \
  --source dfzq \
  --account 东方证券 \
  --password 123456

# Import unencrypted statement (account auto-detected)
ft import dfzq_202606.pdf --source dfzq
```

#### Example 2: Sync Binance Trades

```bash
# First-time sync (full history)
ft import --source binance --account Binance现货

# Incremental sync (since last month)
ft import --source binance --account Binance现货 --since 2026-06-01

# Dry run to preview
ft import --source binance --account Binance现货 --since 2026-06-01 --dry-run
```

#### Example 3: Sync Polymarket Activities

```bash
# Set credentials
export FT_POLYMARKET_WALLET=0x1234...abcd

# Sync all activities
ft import --source polymarket --account Polymarket
```

### Preconditions

1. **Workspace active**: `FT_WORKSPACE_ID` env var set or `~/.ft/workspace` exists
2. **Database reachable**: `FT_DATABASE_URL` points to valid PostgreSQL/SQLite
3. **Account exists**: Target account created via `ft acct add` with correct type
4. **Credentials configured** (for API sources):
   - Environment variables: `FT_EXCHANGE_<PROVIDER>_API_KEY`, etc.
   - OR config file: `~/.ft/credentials.json` with correct provider keys
5. **External tools installed** (for DFZQ):
   - `qpdf` in PATH (for encrypted PDFs)
   - `mutool` in PATH (for text extraction)

### Postconditions (Success)

1. **ImportBatch created**: status='completed', unique source_digest
2. **RawRecords persisted**: One per parsed transaction, unique source_identity
3. **InvestmentEvents created**: One per RawRecord (linked via raw_record_id)
4. **Snapshot updated**: Account positions reflect all imported events
5. **Snapshot validated**: No NaN/Infinity, all values finite
6. **Transaction committed**: Atomic all-or-nothing (no partial facts)

### Postconditions (Idempotent Duplicate)

1. **No new records**: Existing batch returned, no writes
2. **No snapshot change**: Positions unchanged
3. **Success status**: Exit code 0, user informed of duplicate

### Postconditions (Failure)

1. **Transaction rolled back**: No ImportBatch, RawRecords, or InvestmentEvents persisted
2. **Snapshot unchanged**: Previous state preserved
3. **Error reported**: Specific failure reason, location (page/line for parse errors), actionable advice

## Command: `ft stock convert` (Legacy, Maintained for Preview)

### Signature

```bash
ft stock convert <FILE> --source <SOURCE> --output <CSV_PATH> [OPTIONS]
```

### Purpose

Convert broker statement to 10-column CSV preview without importing to database. Useful for manual review or when import fails.

### Arguments

| Argument | Required | Description |
|----------|----------|-------------|
| `<FILE>` | Yes | Path to statement file |
| `--source <SOURCE>` | Yes | Parser identifier (currently only `dfzq`) |
| `--output <CSV_PATH>` | Yes | Output CSV file path |

### Options

| Option | Default | Description |
|--------|---------|-------------|
| `--password <PASS>` | - | PDF password |
| `--account <NAME>` | "东方证券" | Account name (metadata only, not validated) |
| `--currency <CODE>` | "CNY" | Currency code (metadata only) |

### Output Format

CSV with 10 columns (main branch legacy format):
```
date,action,ticker,shares,price,amount,commission,currency,account_name,note
2026-06-12 00:00:00,BUY,600000.sh,100,12.50,1251.00,1.00,CNY,东方证券,印花税0.50 过户费0.50
```

### Example

```bash
ft stock convert ~/Downloads/dfzq.pdf \
  --source dfzq \
  --output preview.csv \
  --password 123456

# Review CSV manually
cat preview.csv

# If satisfied, import via generic CSV parser (future feature)
# ft import preview.csv --source generic_csv --account 东方证券
```

## Integration with Existing CLI

### Dispatch Logic

The CLI router must distinguish investment imports from cash transaction imports based on `--source`:

**Investment Sources** → `ft.application.investment_import.InvestmentImportService`:
- `dfzq`, `binance`, `okx`, `polymarket`

**Cash Transaction Sources** → `ft.application.statement_import.StatementImportService`:
- `alipay`, `wechat`, `icbc`, `ccb` (existing cash account parsers from 007)

**Shared Options**: Both flows use ImportBatch → RawRecords provenance model

### CLI Module Structure

```
src/ft/cli/
├── __init__.py           # CLI app entry point
├── import_cmd.py         # ft import (dispatches to investment or cash)
├── investment.py         # Investment-specific subcommands (ft stock)
├── account.py            # ft acct
└── query.py              # ft query
```

## Error Messages Specification

### Account Type Mismatch

```
❌ Account type mismatch
   
   Account '东方证券' has type 'cash', but investment imports require type 'security' or 'crypto'.
   
   Fix:
   1. Create a new investment account:
      ft acct add 东方证券_投资 --type security --currency CNY
   
   2. Or import to existing investment account:
      ft import dfzq.pdf --source dfzq --account <investment_account_name>
```

### Missing Credentials

```
❌ Credentials not found for 'binance'
   
   API-based imports require credentials. Set environment variables:
     export FT_EXCHANGE_BINANCE_API_KEY="your_api_key"
     export FT_EXCHANGE_BINANCE_API_SECRET="your_api_secret"
   
   Or create ~/.ft/credentials.json:
     {
       "binance": {
         "api_key": "your_api_key",
         "api_secret": "your_api_secret"
       }
     }
   
   WARNING: Keep credentials.json private (chmod 600)
```

### Tool Not Found (DFZQ)

```
❌ Required tool not found: mutool
   
   DFZQ PDF parsing requires:
     - qpdf (for decryption)
     - mutool (for text extraction)
   
   Install on macOS:
     brew install qpdf mupdf-tools
   
   Install on Ubuntu/Debian:
     apt-get install qpdf mupdf-tools
```

### Currency Mismatch

```
❌ Currency mismatch
   
   Statement currency 'USD' does not match account '东方证券' base currencies ['CNY'].
   
   Fix:
   1. Update account to support USD:
      ft acct update 东方证券 --add-currency USD
   
   2. Or force import with currency override (if statement parsing is incorrect):
      ft import dfzq.pdf --source dfzq --account 东方证券 --currency CNY
```

### Snapshot Validation Failure

```
❌ Snapshot validation failed after import
   
   Position '600000.sh' in account '东方证券' has invalid state:
     shares: -100 (negative shares not allowed for non-short positions)
     total_cost: NaN (must be finite)
   
   This indicates a bug in the parser or domain logic. Transaction rolled back.
   
   Please report this issue with the statement file (redact sensitive data).
```
