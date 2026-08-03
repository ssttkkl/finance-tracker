# Research: Multi-Currency Accounts

## Decision 1: Account identity is name-only (Scheme A)

**Decision**: Remove account-level `currency` as identity. Unique key becomes `(workspace_id, name)`. Balance pockets are keyed by fact/operation currency on the existing snapshot shape `accounts[type][name][currency]`.

**Rationale**: Matches “one physical card = one account”; no home currency; clean base for later FX/cross-currency transfer. User explicitly chose Scheme A and rejected home-currency / virtual-merge options.

**Alternatives rejected**:
- Scheme B (explicit `account_balances` table): dual source of truth with rebuildable snapshot.
- Scheme C (keep account.currency as home/default): conflicts with “no home currency” and leaves half-compat semantics.

## Decision 2: No compatibility layer

**Decision**: After migration, runtime APIs use name-only account lookup. No `find(name, currency)` ledger-book semantics, dual-write, or shadow accounts.

**Rationale**: User requirement. Constitution allows discarding discardable dev data; one-time audited migration is enough.

## Decision 3: One-time merge migration

**Decision**: Alembic (or equivalent one-shot data migration) merges same-name same-type account rows to a single survivor; rehang cash facts, investment events, lifecycle events, import FKs, and cash valuations; rebuild snapshot projection. Same-name different-type fails closed with diagnostic report.

**Survivor rule**: Prefer earliest `created_at`, then lowest `id` among same-name same-type rows.

**Rationale**: Deterministic, auditable, no silent type merging.

## Decision 4: Operation currency is required for cash writes

**Decision**: `add` / `checkin` / transfer sides use explicit operation currency as pocket key. Account create does not require permanent currency; optional create-time currency only seeds a zero pocket for display.

**Rationale**: No home currency means no implicit default for cash writes.

## Decision 5: Import resolves by account name only

**Decision**: Mapping still yields `account_name`. Row currency writes into that account’s fact/pocket. Drop “cash row currency must equal account.currency”. `formal_fact_targets` must return `(account_name, fact.currency)` not account row currency.

**Rationale**: 004 already routes by mapping; 005 only changes account identity attachment.

## Decision 6: Wealth cash identity is account + currency

**Decision**: `cash_account` valuation identity becomes per `(owner_account_id, currency)` (e.g. identity string `"{account_id}:{currency}"` with owner_account_id still the account id). Relax `ck_valuation_cash_owner_identity` that currently forces `identity = owner_account_id`.

**Rationale**: Multi-currency checkins must not overwrite each other.

## Decision 7: Investment accounts keep name uniqueness; drop account currency identity

**Decision**: security/crypto display names remain unique among investment accounts. Remove account-level currency identity. Portfolio quote/base currencies come from snapshot account payload + `metadata.base_currencies` / event currencies; if snapshot lacks quote currency after migration, derive from existing positions/events (prefer most common event currency, else first observed). Do not rebuild investment product semantics.

**Rationale**: Spec non-goal: no investment product rewrite; only remove currency from account identity.

## Decision 8: Same-account multi-currency transfer is generic transfer, not FX product

**Decision**: Transfer by account name + from/to operation currencies. Cross-currency requires `--to-amount`. Same account name with different currencies is allowed as generic two-pocket transfer; no FX event model, rates product, or P&L productization in this feature.

**Rationale**: Spec non-goal for formal FX; keeps path open for later FX feature.

## Decision 9: Dual-backend parity

**Decision**: Same logical schema and Application Service contracts on PostgreSQL and SQLite. One explicit `FT_DATABASE_URL`. No fallback, dual-write, or implicit cross-backend migration. Tests must cover both backends for merge migration and multi-currency cash paths.

**Rationale**: Constitution IV.
