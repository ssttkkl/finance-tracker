# Quickstart / Validation: 015

## Prerequisites

- `uv` env; `FT_WORKSPACE_ID` set  
- Optional: `FT_TEST_POSTGRES_URL` for PG matrix  
- Spec: [spec.md](./spec.md), target schema: [database-schema.md](./database-schema.md)

## Automated tests (after implement)

```bash
# focused
uv run pytest tests/test_alembic_migration.py tests/test_postgres_adapter.py -q
uv run pytest tests/test_statement_import.py tests/test_investment_import.py -q  # names may vary
uv run pytest tests/ -q --tb=line  # full suite before declare done
```

Expect: migration from 014 head → 015; no raw/batch tables; cash without dead columns; inv without price; double import new_count=0.

## Dual backend

```bash
export FT_DATABASE_URL="sqlite+pysqlite:////tmp/ft-015-test.db"
export FT_WORKSPACE_ID=test-ws
uv run alembic upgrade head
# run import fixture twice — second new_rows=0

export FT_DATABASE_URL="$FT_TEST_POSTGRES_URL"
uv run alembic upgrade head
# same fixture outcomes
```

## One-shot ~/.ft upgrade (delivery gate)

```bash
# 1) backup
cp -a "$HOME/.ft/finance-tracker.db" \
  "$HOME/.ft/finance-tracker.db.bak-015-$(date +%Y%m%d%H%M%S)"

# 2) upgrade
export FT_DATABASE_URL="sqlite+pysqlite:////$HOME/.ft/finance-tracker.db"
export FT_WORKSPACE_ID="<your-workspace-id>"   # same as daily use
uv run alembic upgrade head

# 3) verify
sqlite3 "$HOME/.ft/finance-tracker.db" ".tables"   # no import_batches/raw_* / record_revisions / ...
sqlite3 "$HOME/.ft/finance-tracker.db" "PRAGMA table_info(cash_transactions);"
sqlite3 "$HOME/.ft/finance-tracker.db" "SELECT count(*) FROM cash_transactions;"
sqlite3 "$HOME/.ft/finance-tracker.db" "SELECT count(*) FROM investment_events;"
# CLI smoke: ft accounts / balances as you normally use
```

### Rollback

```bash
# stop processes using the db, then:
cp -a "$HOME/.ft/finance-tracker.db.bak-015-XXXXXXXX" "$HOME/.ft/finance-tracker.db"
```

## Success mapping

| SC | How to check |
|---|---|
| SC-001–004 | pytest import fixtures dual backend |
| SC-005–007,010–011 | migration tests + pragma/information_schema |
| SC-008 | wealth/category transfer tests |
| SC-012 | ~/.ft backup exists + upgrade + counts + CLI |


## Delivery evidence (2026-07-24)

- Full suite: `778 passed, 30 skipped` (`env -u FT_DATABASE_URL uv run pytest tests/ -q`)
- `~/.ft/finance-tracker.db` upgraded to alembic `20260724_08`
- Backup: `~/.ft/finance-tracker.db.bak-015-*`
- Fact counts post-upgrade: cash 11387, investment 974
