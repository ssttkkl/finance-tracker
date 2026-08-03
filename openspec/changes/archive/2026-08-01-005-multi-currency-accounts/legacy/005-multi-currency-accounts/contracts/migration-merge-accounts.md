# Contract: One-Time Account Merge Migration

## Preconditions

- Applied once per database as part of this feature’s schema change.
- Not a long-lived dual-model compatibility path.

## Rules

1. Group accounts by `(workspace_id, name)`.
2. If group has >1 distinct `type` → **abort migration** with human-readable conflict report (workspace, name, types, ids). No partial merge of that workspace’s conflicting group (whole migration transaction fails closed).
3. Else survivor = earliest `created_at`, then lowest `id`.
4. Rehang all dependent FKs from loser ids → survivor id.
5. Rewrite cash valuation identities to account+currency form; owner remains survivor id.
6. Delete losers; enforce `UNIQUE(workspace_id, name)`; drop account `currency` column.
7. Rebuild or merge cash snapshot maps under single account name with all currencies preserved.
8. Investment snapshot quote currency: if previously taken from account.currency, set from snapshot payload / metadata.base_currencies / first event currency without inventing new investment semantics.

### SQLite schema-change procedure

SQLite must keep the migration atomic.  Because an Alembic upgrade has already opened
its transaction, it MUST NOT rely on toggling `PRAGMA foreign_keys=OFF` to drop the
referenced `accounts` table.  Instead, the revision must, in that same transaction:

1. identify every table with a direct foreign key to `accounts`;
2. preserve and rebuild those dependent tables (including all rows and equivalent
   constraints) so `accounts` can be rebuilt without `currency`;
3. recreate their foreign keys to the rebuilt `accounts` table; and
4. run `PRAGMA foreign_key_check` before the transaction commits.

The procedure may not introduce a commit/reopen boundary or disable enforcement as a
durable migration state.  Any copy, constraint, or foreign-key failure aborts the
entire migration.

## Outcomes

| Case | Result |
|---|---|
| Same name, same type, multi currency | 1 account; multi pockets; facts intact |
| Same name, different type | Fail + diagnostics |
| Single-currency accounts | Currency column removed; pockets still work via facts |
| Re-run after success | No-op / already at target schema (not dual-read) |

## Verification

- SQLite and PostgreSQL migration tests with fixture data.
- Post-migration: name lookup unique; multi-currency checkin non-overlapping; import by name works.
