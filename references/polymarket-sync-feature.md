# Polymarket Activity sync feature

Use this reference when adding or maintaining `ft stock sync polymarket` style integrations.

## Command shape

```bash
# Resolve profile/login wallet to proxy wallet, fetch Activity, dedupe, preview only
ft stock sync polymarket --wallet 0xYourProfileWallet --dry-run

# Write new official TRADE rows into records/security and rebuild security snapshot
ft stock sync polymarket --wallet 0xYourProfileWallet

# If proxy wallet is already known, skip profile parsing
ft stock sync polymarket --proxy-wallet 0xYourProxyWallet --dry-run -o /tmp/polymarket_new.csv
```

## Data source and mapping

- Use public Data API Activity, not CLOB auth: `GET https://data-api.polymarket.com/activity?user=<proxyWallet>&limit=500&offset=0`.
- Resolve profile wallet by fetching `https://polymarket.com/profile/<address>` with browser-like `User-Agent` and extracting `proxyAddress`.
- Import only `type == "TRADE"`.
- Map `BUY` / `SELL` to stock actions.
- Map `slug + outcome` to `pm:<slug>:yes|no`.
- `BUY` amount is negative `-usdcSize`; `SELL` amount is positive `+usdcSize`.
- `commission=0`, `currency=USD`, `account_name=Polymarket` by default.
- Store `transactionHash` in `note` as `polymarket tx:<hash>` for audit and idempotence.

## Incremental dedupe

Dedupe in this order:

1. Existing transaction hashes parsed from `note` for the same `account_name`; if a tx is already in records for that account, treat the official tx as already imported for idempotence.
2. Exact row identity across `CSV_FIELDS` against existing records for the same `account_name`.
3. Exact row identity within the current fetched batch.
4. Within a fresh batch, do not drop distinct row identities solely because they share one hash; one on-chain transaction can contain multiple fills/markets.

Do not infer duplicates by market title; old manual rows may use legacy slugs or approximate timestamps and should be handled by full replacement workflow instead.

## Safety and correctness rules

- Never ask for private key, seed phrase, API secret, browser cookie, or CLOB auth for this read-only sync.
- Unknown `side` or non-binary `outcome` must raise `ValueError`; do not silently skip ambiguous money/position data.
- Non-TRADE Activity rows may be ignored.
- Security records can contain transfer-style audit rows or malformed legacy rows in `records/security/`; sync/dedupe code must tolerate missing/`None` stock fields.
- Appending stock rows must preserve transfer-style audit columns in same-day `records/security/YYYY-MM-DD.csv` files; do not rewrite them as stock-only CSVs or lose `transfer_account` audit data.
- All writers that can touch `records/security/YYYY-MM-DD.csv` (`stock.record_trade`, `stock.do_append`, `transfer._write_transfer_row`, generic `append.do_append`) must use the security union-header writer when the target type/path is security. A one-way fix is insufficient: stock→transfer and transfer→stock both need to preserve the other schema's columns.
- Multi-day stock append must roll back already-written day files (and snapshot changes) if a later read/write or `repair_security()` fails; never leave records partially updated with an unrepaired snapshot.
- Direct stock operations and stock append must reject non-finite numeric values (`NaN`, `Infinity`), finite inputs whose derived values overflow, and cumulative replay/snapshot state that becomes non-finite before mutating snapshot or CSV.
- Direct stock operations that save snapshot and then write the audit row must restore the original snapshot and same-day CSV if either snapshot or CSV recording fails; snapshot and records must not diverge on ordinary write exceptions.
- Security CSV and snapshot writes should be temp-file + atomic replace so a write failure does not truncate the original file.
- Validate pagination (`limit > 0`, `max_pages > 0` when provided) before network calls.
- Reject malformed TRADE payload items and non-finite numeric values (`NaN`, `Infinity`) with `ValueError` before writing CSV.
- CLI validation failures must exit non-zero so automation cannot mistake a failed sync for success.
- Stock CSV imports must target accounts whose `accounts.yaml` type is `security`, matched by `(account_name, currency)`; never write stock rows to `records/security` for cash/loan/lend accounts that merely share a name.
- `sync polymarket` must pre-validate the target account before network fetches or `-o` output so bad account choices fail before producing import artifacts.
- Default first run should be `--dry-run` and report Activity count, converted trade count, and new-row count before any write.

## Test coverage to preserve

Add/keep tests for:

- extracting proxy wallet from escaped profile HTML payload
- converting a BUY/SELL Activity row into ft stock CSV fields
- rejecting unsupported outcomes loudly
- preserving distinct Polymarket fills/markets that share one `transactionHash`, while deduping exact duplicate rows
- scoping Polymarket transaction-hash dedupe by `account_name`, including custom `--account` dry-run rows
- preserving same-day transfer-style security audit rows when appending stock rows
- preserving same-day stock audit rows when `ft transfer` or generic `ft append` writes transfer-style rows to a security account
- rolling back multi-day `stock append` if a later day write or snapshot repair fails
- rolling back direct stock snapshot and same-day CSV changes if either snapshot or CSV audit-row write fails
- rejecting direct stock finite inputs whose derived `amount`/cash deltas overflow to `Infinity`
- rejecting cumulative overflow in stock append replay and direct buy/deposit state updates
- preserving snapshot contents if snapshot YAML writing fails mid-write
- rejecting invalid pagination before network calls
- rejecting non-object Activity items and `NaN`/`Infinity` numeric values
- returning non-zero CLI exit status on validation errors
- rejecting stock imports whose `(account_name, currency)` resolves to a non-security account
- rejecting `sync polymarket --account` when the target account is missing/non-security before any network call
- ignoring transfer-style/malformed rows under `records/security` during dedupe
- CLI help exposes `stock sync polymarket`

## Verification

After changes:

```bash
python -m pytest tests/test_stock.py -q
python -m py_compile src/ft/polymarket_sync.py src/ft/cli.py
ft stock sync polymarket --proxy-wallet 0xYourProxyWallet --dry-run --max-pages 1
ft verify
```

Full `pytest -q` may reveal unrelated legacy converter failures; report them separately rather than treating them as Polymarket sync regressions.
