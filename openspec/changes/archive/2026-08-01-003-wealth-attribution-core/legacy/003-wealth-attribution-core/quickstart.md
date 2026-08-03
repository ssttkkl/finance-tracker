# Wealth Attribution Core Quickstart

This guide validates the feature without HTTP/Web. Run every storage command from the repository root with Python 3.11+ and `uv`.

## SQLite Contract

```bash
DB="$PWD/.tmp/wealth-quickstart.sqlite"
rm -f "$DB"
mkdir -p .tmp
FT_DATABASE_URL="sqlite+pysqlite:///$DB" FT_WORKSPACE_ID="wealth-quickstart" \
  uv run alembic upgrade head
FT_DATABASE_URL="sqlite+pysqlite:///$DB" FT_WORKSPACE_ID="wealth-quickstart" \
  uv run pytest -q tests/test_wealth_calculation.py tests/test_application_wealth.py \
    tests/test_relational_wealth_contract.py tests/test_wealth_rebuild_concurrency.py
```

Expected: all wealth golden, failure, idempotency, workspace-isolation and SQLite contract tests pass; no test silently skips PostgreSQL when the required mode is enabled.

## Real PostgreSQL Contract

Provide a dedicated database whose name ends in `_test`; do not use a production database.

```bash
export FT_TEST_POSTGRES_URL="postgresql+psycopg://USER:PASSWORD@HOST:PORT/finance_wealth_test"
FT_REQUIRE_TEST_POSTGRES=1 FT_TEST_POSTGRES_URL="$FT_TEST_POSTGRES_URL" \
  uv run pytest -q tests/test_wealth_calculation.py tests/test_application_wealth.py \
    tests/test_relational_wealth_contract.py tests/test_wealth_rebuild_concurrency.py
```

The fixture downgrades/upgrades the dedicated database around the run. If PostgreSQL is absent or unreachable, required mode fails rather than treating SQLite as equivalent evidence.

## Canonical and Full Validation

```bash
uv run pytest -q tests/test_wealth_performance.py
uv run alembic heads
uv build
git diff --check
```

The performance test uses the fixed seed and fixture digest documented in the test, performs 3 warmups and 20 measured samples for cold rebuild/query and validated active-cache hit, reports nearest-rank p95 and environment metadata, and enforces the same `<5s` cold and `<300ms` hot budgets on both backends.

## Failure/Recovery Checks

The rebuild test injects failures before result write, after generation validation, before active-manifest CAS and after CAS. The active manifest must remain the previous complete generation for failures before CAS; after a committed CAS, the new complete generation is authoritative. Concurrent builders and facts arriving after the source watermark must produce `wealth.build_stale` or a subsequent generation, never a regressed pointer.

## Artifact References

- Domain/query fields and stable errors: [contracts/wealth-query.md](contracts/wealth-query.md)
- Evidence ordering, folding and cursors: [contracts/evidence.md](contracts/evidence.md)
- Schema, transactions and parity: [contracts/persistence.md](contracts/persistence.md)
- Entities and invariants: [data-model.md](data-model.md)
