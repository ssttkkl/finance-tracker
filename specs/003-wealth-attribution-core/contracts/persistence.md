# Persistence and Error Contract

## Tables

The additive Alembic revision creates workspace-qualified tables for `valuation_observations`, `account_lifecycle_events`, `wealth_source_manifests`, `wealth_source_manifest_items`, `wealth_generations`, `wealth_generation_days`, `wealth_daily_results`, `wealth_active_manifests`, `wealth_components`, `wealth_evidence_manifests`, `wealth_evidence_items`, `wealth_evidence_manifest_items` and `wealth_coverage_dispositions`. Content/result/build/evidence identities are deterministic hashes or revisions, never database-generated business IDs.

`valuation_observations.owner_account_id` is a same-workspace composite FK for account-owned kinds. Database checks require it for `cash_account`/`position`, require cash owner to equal the cash identity, and require it to be null for `instrument_quote`/`currency_pair`. Owned coverage/disposition identities include `(workspace_id, owner_account_id, identity_kind, identity)` so the same ticker in two accounts cannot collide. The migration creates no guessed ownership rows: existing formal investment facts are interpreted through their immutable `account_id` plus canonical ticker/position payload fields, while any ownerless/conflicting account-owned valuation fails closed until a corrected formal revision is appended.

## Transactions

1. Formal valuation observation and its source/provenance write commit atomically with the originating command.
2. A rebuild atomically captures an immutable source manifest/watermark, reads only those enumerated revisions, writes immutable staging rows, validates the full generation date index, then publishes the active manifest in one short fenced transaction.
3. Any calculation, validation, database, or publish error rolls back the current transaction. Inactive staging rows may remain for diagnosis/retry; the prior active manifest remains visible.
4. Repeating the same observation or rebuild is idempotent by deterministic identity and canonical payload digest.
5. Source capture includes the ownership inputs used to build the expected universe. A valuation owner that is absent, cross-workspace or inconsistent with a formal investment fact aborts complete attribution and is published only as unsupported coverage/evidence.

## PostgreSQL/SQLite Equivalence

| Contract | PostgreSQL | SQLite | Allowed difference |
|---|---|---|---|
| Decimal/time | NUMERIC input + UTC-aware timestamp | canonical text decimal + UTC-normalized timestamp | driver representation only |
| Source capture | revision-bounded consistent read | revision-bounded read before writer reservation | lock primitive |
| Publish | row lock + conditional active-manifest update | `BEGIN IMMEDIATE` + conditional update | busy/lock timing |
| Unique race | map unique/serialization/deadlock to stable wealth/storage error | map unique/busy/readonly to same stable category | native error text/retry policy |
| Query | same workspace/range/order predicates | same predicates and ordering | query plan/performance |
| Visibility | one committed manifest and complete generation | one committed manifest and complete generation | physical WAL/connection behavior |

Both backends must run the same Application Service and canonical comparator. No automatic fallback, dual write, shadow compare or implicit cross-backend migration is permitted.

## Workspace Isolation

Every query predicate and relationship is workspace-qualified. Account-owned valuation and coverage relationships also use the composite workspace/account key; PostgreSQL and SQLite must reject cross-workspace owner references. A component, evidence cursor, build revision or active manifest from another workspace is indistinguishable from not-found to the caller and must not leak data.

## Safe Errors

Adapters map dialect-native connection, busy, readonly, unique, serialization and stale-CAS failures to stable `storage.*` or `wealth.*` codes. Logs and raised errors omit database URLs, credentials, full local paths, raw fact payloads and evidence contents.
