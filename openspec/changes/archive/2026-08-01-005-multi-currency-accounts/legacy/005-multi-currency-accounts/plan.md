# Implementation Plan: Multi-Currency Accounts

**Branch**: `multi-currency-accounts` | **Date**: 2026-07-20 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-multi-currency-accounts/spec.md`

## Summary

Change account modeling from **one account row per (name, currency)** to **one account per name** with **multi-currency balance pockets**. Remove account-level currency identity; require explicit operation currency for cash writes; import resolves by account name and writes row currency into that account; one-time merge migration with no runtime compatibility layer; wealth cash valuation identity becomes account+currency. Dual-backend (PostgreSQL + SQLite) behavior remains equivalent.

## Technical Context

**Language/Version**: Python 3.11+  
**Primary Dependencies**: SQLAlchemy, Alembic, psycopg, uv, pytest  
**Storage**: PostgreSQL and file SQLite via explicit `FT_DATABASE_URL` (no fallback/dual-write)  
**Testing**: pytest; SQLite automation + real PostgreSQL matrix for persistence/migration  
**Target Platform**: macOS/Linux CLI  
**Project Type**: CLI + Application Service + relational adapters  
**Performance Goals**: Personal finance scale; single-file import transaction; one-shot migration  
**Constraints**: Exact decimal; fail-closed migration on type conflict; no home currency; no long-lived compat API  
**Scale/Scope**: Account identity, cashflow, import, snapshot, wealth cash checkin, CLI/docs

## Constitution Check

*GATE: pre-research and post-design*

| Principle | Status |
|---|---|
| I 财务正确性与可审计性 | PASS — fact-level currency; exact decimal; merge rehangs FKs; fail-closed conflicts; idempotent import retained |
| II Spec Kit 规格驱动 | PASS — 005 artifacts drive change; no product code in main session until tasks/analyze gate |
| III 测试先行与验证证据 | PASS — failing tests before impl for identity, multi-pocket, import, migration, dual backend |
| IV 显式数据库选择与行为等价 | PASS — parity matrix below; no fallback/dual-write/implicit cross-backend migration |
| V 清晰边界与最小复杂度 | PASS — Scheme A reuses snapshot pockets; no new balances table; no FX product scope |

### Parity Matrix (PostgreSQL / SQLite)

| Dimension | PostgreSQL | SQLite | Notes |
|---|---|---|---|
| Schema: `UNIQUE(workspace_id, name)` | yes | yes | replaces name+currency |
| Schema: no account.currency identity | yes | yes | column dropped after data merge |
| Cash multi-pocket read/write | exact decimal | exact decimal adapter | same Application Service |
| Import name resolve + row currency | single txn | single txn | same fail/idempotent contract |
| Merge migration | fail-closed | fail-closed | same survivor rule & conflict report |
| Cash valuation identity | account+currency | account+currency | checkins do not clobber |
| Auto fallback / dual-write / implicit cross-backend migrate | forbidden | forbidden | constitution |

**Permitted operational differences**: lock implementation, concurrency throughput, driver error text — must not fork ledger results or account identity.

### SQLite migration implementation boundary

SQLite cannot safely drop/recreate `accounts` while tables directly reference it after
the Alembic transaction has begun: changing `PRAGMA foreign_keys` at that point is not
a valid escape hatch.  The SQLite revision MUST therefore preserve atomicity by
rebuilding, within the same Alembic transaction, every table that directly references
`accounts` (and its required data) around the `accounts` table rebuild.  It MUST copy
all rows, recreate the equivalent foreign-key and uniqueness constraints, and run
`PRAGMA foreign_key_check` before commit.  A commit-before-rebuild/disable-FK boundary
is prohibited because it would violate the feature's fail-closed migration contract.

## Project Structure

### Documentation (this feature)

```text
specs/005-multi-currency-accounts/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── cli-multi-currency-accounts.md
│   └── migration-merge-accounts.md
├── checklists/requirements.md
└── tasks.md            # via /speckit-tasks
```

### Source Code (impact surface)

```text
src/ft/domain/accounts.py
src/ft/domain/queries.py
src/ft/application/accounts.py
src/ft/application/cashflow.py
src/ft/application/statement_import.py
src/ft/application/queries.py
src/ft/application/investment.py          # residual quote currency only
src/ft/adapters/relational/models.py
src/ft/adapters/relational/repositories.py
src/ft/adapters/relational/imports.py
src/ft/adapters/relational/queries.py
src/ft/adapters/relational/wealth_facts.py
src/ft/adapters/relational/investments.py
src/ft/repositories/wealth.py
src/ft/acct.py
src/ft/cli.py
src/ft/report.py
src/ft/schema.py
migrations/versions/20260720_04_multi_currency_accounts.py   # planned
tests/test_multi_currency_accounts.py                        # planned
tests/test_multi_currency_migration.py                       # planned
tests/test_statement_import_mapping.py                       # update
tests/test_relational_contract.py                            # update
tests/test_relational_wealth_facts.py                        # update
README.md
```

**Structure Decision**: Single-project CLI layout; change domain DTO + application services + relational adapters + Alembic data/schema migration; no new packages.

## Implementation Approach

1. **Red tests**: name-unique accounts; multi-currency add/checkin; import without booklet accounts; transfer by name+op currency; merge migration success/conflict; wealth multi-currency checkin; dual backend.
2. **Domain/API**: `AccountDTO` without currency; `find(name)` only; lifecycle by name; `AccountBalanceDTO` remains one row per pocket for list (emit multiple rows per account name).
3. **Schema/migration**: merge data then drop currency uniqueness/column; rewrite cash valuation identities.
4. **Cashflow/import/queries/CLI**: operation currency required; remove account.currency reads; import cache by name; formal_fact_targets use fact currency.
5. **Wealth**: record_cash_checkin by name; identity `{account_id}:{currency}`.
6. **Investment residual**: stop requiring account.currency; portfolio base currencies from metadata + snapshot/event-derived quote without product rewrite.
7. **Docs + green full suite**.

## Complexity Tracking

None. No constitution violations requiring justification.

## Post-Design Constitution Check

Re-evaluated after research/data-model/contracts/quickstart: **PASS**. Scope stays within Scheme A; dual-backend parity specified; no FX product creep; migration is explicit one-shot not runtime dual model.

## Eng / Analyze Notes (2026-07-20)

Non-interactive eng sanity + `$speckit-analyze` (gstack `plan-eng-review` CLI not on PATH; review performed against artifacts + call-site inventory):

- **Adopted**: Explicit tasks for `AccountRepository` protocol (`protocols.py`), relational query adapter multi-pocket DTO, wealth identity rewrite, Alembic revision list test update, import protocol semantics.
- **No architecture change**: Keep Scheme A (snapshot pockets), no new balances table, no FX product, one-shot merge only.
- **Residual process risk**: `FT_TEST_POSTGRES_URL` currently unset in this environment — implementer MUST still leave SQLite evidence and report missing PG matrix rather than claim dual-backend complete.
