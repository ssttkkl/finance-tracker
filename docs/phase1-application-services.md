# Phase 1 Application Services

## Migrated Paths

- `ft acct add/list/rename/delete/activate/deactivate` enter `ft.application.accounts.AccountService`.
- `ft add` enters `ft.application.cashflow.CashflowService.add_manual_transaction`.
- `ft checkin` enters `ft.application.cashflow.CashflowService.checkin_balance`.
- `ft transfer` enters `ft.application.cashflow.TransferService.transfer` for the existing cash/loan/lend-style transfer rows.
- `ft reconcile` enters `ft.application.reconcile.ReconcileService`.

## New Boundaries

- `ft.domain` contains structured domain errors and DTO/result types for accounts and cashflow use cases.
- `ft.repositories` defines account, cashflow, snapshot, and unit-of-work protocols.
- `ft.adapters.local_csv.LocalCsvUnitOfWork` accepts an explicit ledger root and owns `accounts.yaml`, cash record CSV, and `snapshot.yaml` persistence for migrated paths.
- Reconciliation accepts an explicit ledger root, reads and writes records/audit/snapshot through that root, and returns a structured result for CLI rendering.
- CLI handlers for migrated commands parse arguments, construct `LocalCsvUnitOfWork(models.FT_DIR)` at the boundary, call application services, and render results.

## Still Outside This Slice

- Security-specific stock commands, converters, external sync, `append`, `verify`, `report`, `list`, `commit`, `status`, and `reset`.
- Full investment repository extraction and external provider adapters.

## Breaking Cleanup Applied In Phase 1

Historical ledger compatibility was removed instead of being carried forward:

- `snapshot.yaml` cash/loan/lend balances must use the nested `accounts -> type -> account -> currency -> numeric balance` shape. Scalar account balances are rejected as invalid snapshot schema.
- Every security/crypto account in `accounts.yaml` must define `base_currencies` as a nonempty sequence. Runtime stock operations, CSV append/replay, display grouping, and price exclusion no longer fall back to the legacy account `currency`.
- Security ledger CSV files use the unified 12-column swap schema: `date, action, from_ticker, to_ticker, from_amount, to_amount, price, commission, commission_asset, currency, account_name, note`. Old 10-column `ticker/shares/amount` stock records are rejected. Current transfer audit rows in `records/security` remain supported as transfer audit rows and are skipped by security replay.

Migration preconditions before applying this code to a ledger:

- Existing `snapshot.yaml` has no top-level or account-level scalar balance buckets for cash/loan/lend accounts.
- Every security/crypto account has nonempty `base_currencies`.
- Non-empty historical security trade CSVs have already been converted to the unified 12-column header. Empty old-format files may be removed or migrated separately before replay.
