## Why

Alipay exports successful `信用借还` rows as `不计收支` with a positive amount, while the listed payment methods identify cash accounts that funded the repayment. The importer currently treats those amounts as inflows, which reverses cash balances and produces incorrectly signed payment components. Matching bank debits confirm the source-account direction for the affected bill rows.

## What Changes

- Normalize Alipay `信用借还` rows whose source direction is `不计收支` as cash outflows, while preserving the original source payload and `repayment` record type. Keep zero-value rows unchanged and retain existing failed-repayment handling.
- Correct matching positive historical Alipay repayment facts already imported into the active local SQLite database through the existing ledger command service. Preserve their source snapshots, IDs, account mappings, and allocation magnitudes; record the amount correction as an auditable manual override.
- Re-scan and preview the remaining Alipay bill with negative repayment totals and components. Keep the single composite transaction without source-backed split amounts out of this import until its allocation can be confirmed.
- Leave investment statements and unrelated cash rows outside this change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `import/statement-import`: define the cash direction for Alipay credit repayments exported as `不计收支`.

## Impact

- Affected code: Alipay normalization in `src/ft/convert.py`; import preview/commit consumes the normalized direction through the existing API and application service.
- Affected data: matching historical facts in `~/.ft/finance-tracker-bills-import-20261014.db`; the source snapshot remains immutable and the application service records the correction.
- Cross-platform impact: the shared backend behavior applies to Compose Web and Native import clients; no client UI or API shape changes.
- Rollback: take a restricted SQLite backup before correcting historical facts. If the preview or post-write audit fails, stop the backend and restore that backup; do not delete source facts or alter the original bill.
