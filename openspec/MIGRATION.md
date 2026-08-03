# OpenSpec migration manifest

Initial migration date: 2026-08-01
PR28 migration date: 2026-08-03

## Active changes
- `020-cash-ledger-browser-web`
- `022-investment-ledger-browser-web`
- `023-icbc-refund-pairing`

## Archived changes
- `2026-08-01-001-postgres-only-storage`
- `2026-08-01-002-dual-database-runtime`
- `2026-08-01-003-wealth-attribution-core`
- `2026-08-01-004-mapping-import-open-currency`
- `2026-08-01-005-multi-currency-accounts`
- `2026-08-01-006-transaction-relations`
- `2026-08-01-007-closed-trade-refund-import`
- `2026-08-01-008-relations-kind-decouple`
- `2026-08-01-009-investment-account-import`
- `2026-08-01-010-row-idempotent-import`
- `2026-08-01-011-usmart-hk-import`
- `2026-08-01-012-investment-base-currency-cost`
- `2026-08-01-013-investment-cash-event-kinds`
- `2026-08-01-014-fact-field-unify`
- `2026-08-01-015-inline-row-provenance`
- `2026-08-01-016-bigint-surrogate-ids`
- `2026-08-01-017-asset-valuation-quote`
- `2026-08-01-018-investment-connector-sync`
- `2026-08-01-019-portfolio-quote-orchestration`
- `2026-08-03-024-normalized-cash-record-type`
- `2026-08-03-025-record-type-relation-gates`

Each change contains a `legacy/` copy of its original feature directory.

The source repository contained 24 feature directories after PR28 was included: `001`–`020`, `022`–`025`.
No `021` directory existed at migration time, so there is no `021` capability to migrate;
historical references to it remain only inside preserved legacy evidence.

PR28 also supplied the three database migrations, implementation changes and regression
tests for the `record_type` import contract and relation gates. Those runtime changes are
kept in the repository; the original feature artifacts for 024 and 025 are preserved
under their archived `legacy/` directories.
