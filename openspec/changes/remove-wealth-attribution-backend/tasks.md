## 1. Persistence and migration

- [x] 1.1 Move the frozen wealth table definitions used by historical migration 2 into migration-only metadata and remove those models from the application metadata.
- [x] 1.2 Move wealth source revision trigger helpers under migration support; keep historical revisions 35 and 36 executable without runtime adapter imports.
- [x] 1.3 Add revision 37 to drop wealth revision triggers and all 14 wealth-specific tables in FK-safe order; update runtime schema revision and required tables.
- [x] 1.4 Remove wealth-specific models and deletion logic from application workspace management and relational unit-of-work setup.

## 2. Runtime removal

- [x] 2.1 Remove the wealth service from `ServiceBundle` and the relational composition root, including calculation, rebuild, evidence, and result read paths.
- [x] 2.2 Remove account lifecycle, balance check-in, and cash import writes that only captured wealth attribution sources; retain each operation's ledger behavior.
- [x] 2.3 Move canonical JSON serialization to a neutral domain module and update cash projection imports without changing valid input bytes or digests.
- [x] 2.4 Remove wealth attribution domain, application, repository, and relational adapter modules and their public exports.

## 3. Current contracts and regression coverage

- [x] 3.1 Remove the retired wealth attribution main spec from current navigation and update current product documentation and indexes to state the present capability boundary.
- [x] 3.2 Remove tests for retired runtime behavior; retain historical migration compatibility coverage and update existing latest-schema assertions for revision 37.
- [x] 3.3 Search runtime, migrations, tests, specs, and docs for stale wealth attribution runtime references; preserve references to migration history, investment performance, portfolio valuation, cash projection serialization, and balance-product parsing.

## 4. Review and validation

- [x] 4.1 Run `openspec validate --all --strict`, `openspec doctor`, and `git diff --check`; record command results and final diff review. (`openspec validate --all --strict`: 32 passed; `openspec doctor`: ok; `git diff --check`: clean; `PYTHONDONTWRITEBYTECODE=1 uv run alembic history`: passed, with `20261018_37` as head. The first Alembic history load found an `ExactDecimal` type-factory error in migration-only schema; it was fixed and the command then passed. The first OpenSpec command attempt used unsupported `--change`; the all-artifacts validation is the successful validation. Final review found no remaining runtime wealth-source hooks; current parser references are for balance products, and migration drops only the 14 dedicated tables.)
- [ ] 4.2 Run SQLite migration and affected application regression tests, then run the same migration contract on a dedicated PostgreSQL database ending in `_test`; record any unavailable checks and residual risks.

## Validation limitation

At 2026-10-18 01:42 Asia/Shanghai, the isolated PR worktree was based on `origin/main` at `5fdd40f1f666b18bfb18a591c7804f9df6717279` (`HEAD` and comparison baseline before commit). `openspec validate --all --strict` passed (32 items), `openspec doctor` passed, `PYTHONDONTWRITEBYTECODE=1 uv run alembic history` passed with `20261018_37` as head, and staged/unstaged `git diff --check` passed. Tests and database migrations were not run because they were not requested. SQLite migration behavior, preservation of ledger data during deletion, PostgreSQL migration behavior, and affected application regressions remain unverified. Keep task 4.2 pending; do not archive until the migration and affected regression matrix has been run and reviewed against a dedicated PostgreSQL database ending in `_test`.
