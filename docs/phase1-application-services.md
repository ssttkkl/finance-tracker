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
- Removing legacy compatibility helpers that are still used by unmigrated commands and tests.
