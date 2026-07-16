# Phase 1 Application Services

## Migrated Paths

- `ft acct add` now enters `ft.application.accounts.AccountService`.
- Account creation persistence now uses `ft.adapters.local_csv.LocalCsvUnitOfWork`.
- `accounts.yaml` access for account creation is owned by `ft.adapters.local_csv.LocalCsvAccountRepository`, with an explicit ledger root.
- CLI reconcile dispatch now goes through `ft.application.reconcile.ReconcileService`.

## New Boundaries

- `ft.domain` contains domain errors, `Money`, and account DTO/result types.
- `ft.repositories` contains runtime-checkable repository and unit-of-work protocols.
- `ft.application` owns account creation and the reconcile facade transaction boundary.
- `ft.adapters.local_csv` owns local YAML access for the migrated account path.

## Explicitly Unmigrated

- `ft acct list`, `rename`, `delete`, `activate`, and `deactivate`.
- `ft add`, `append`, `convert`, `verify`, `report`, `stock`, `transfer`, `commit`, `status`, and `reset`.
- Reconciliation rules and CSV rewrite behavior inside `ft.reconcile`.
- Snapshot, records CSV, stock, import, and reporting repositories.
