# Quickstart: Row-Level Idempotent Import

**Feature**: 010-row-idempotent-import  
**Date**: 2026-07-23

## Prerequisites

```bash
export FT_DATABASE_URL="sqlite+pysqlite:////tmp/ft-010.db"
export FT_WORKSPACE_ID=default
# migrate / ensure_workspace as usual

# Dual-backend tests: Docker container finance-tracker-postgres-test on host port 55432
export FT_TEST_POSTGRES_URL='postgresql+psycopg://finance_tracker:finance_tracker_test@127.0.0.1:55432/finance_tracker_test'
docker start finance-tracker-postgres-test 2>/dev/null || true
```

## Dual-backend notes (T001)

| Item | Value |
|------|--------|
| Container | `finance-tracker-postgres-test` |
| Host port | `55432` |
| URL | `postgresql+psycopg://finance_tracker:finance_tracker_test@127.0.0.1:55432/finance_tracker_test` |
| Contract tests | `tests/contract/test_row_idempotent_import.py` parametrized `sqlite` + `postgresql` |

## Scenario A — Same investment file twice

```bash
ft acct add 盈透 --type security --currency USD
ft import tests/fixtures/ibkr/transactions_1y_sample.csv --source ibkr --account 盈透
# first: count = 39 (or fixture event count)
ft import tests/fixtures/ibkr/transactions_1y_sample.csv --source ibkr --account 盈透
# second: count = 0; positions unchanged
```

## Scenario B — Overlap (construct or synthetic)

1. Import fixture slice A (identities S1…Sk).
2. Import fixture slice B sharing S1…Sj and adding new Sk+1…
3. Expect: final events = |union|; second import count = |new only|.

Automated: pytest contract tests under this feature.

## Scenario C — Cash same file twice

```bash
# any supported cash source fixture used in 007 tests
# second import count = 0; balances unchanged
```

## Dual-backend

```bash
FT_REQUIRE_TEST_POSTGRES=1 FT_TEST_POSTGRES_URL="$FT_TEST_POSTGRES_URL" \
  uv run pytest tests/contract/test_row_idempotent_import.py -q
```

Expected: pass on sqlite + postgresql params; same new/skip counts.
