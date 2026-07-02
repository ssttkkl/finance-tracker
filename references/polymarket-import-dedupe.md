# Polymarket import dedupe workflow

When importing a fresh Polymarket transaction export into `ft`, first remove rows that already exist in `records/security/` or are already represented in the snapshot.

## Recommended workflow

1. Inspect existing Polymarket rows in security records:
   - look for `pm:` tickers under `~/.ft/records/security/*.csv`
   - confirm the same `ticker + action + account_name + date` combination is not already present
2. Keep already-imported `CHECKIN` rows out of the new import if they came from the same prior snapshot.
3. Build a filtered CSV containing only new rows.
4. Append the filtered CSV with `ft stock append <file>`.
5. Verify with `ft verify` and `ft stock list`.

## Practical rule

If the source export overlaps a previous import, dedupe on the exact stored row identity first:
- `date`
- `action`
- `ticker`
- `shares`
- `price`
- `amount`
- `account_name`
- `note`

This is safer than trying to infer duplicates only from market title.

## Why this matters

Polymarket exports often contain a mix of:
- new trades
- repeated rows from an earlier export window
- prior imported position snapshots (`CHECKIN` rows)

Filtering before append keeps the security ledger idempotent and avoids double-counting.
