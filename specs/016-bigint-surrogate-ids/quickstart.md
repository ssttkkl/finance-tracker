# Quickstart / Validation: 016

## Prerequisites

- 015 applied (`20260724_08` or later)
- `uv`; optional `FT_TEST_POSTGRES_URL`

## Automated

```bash
env -u FT_DATABASE_URL uv run pytest tests/test_016_*.py tests/test_alembic_migration.py -q
env -u FT_DATABASE_URL uv run pytest tests/ -q
```

## Dual backend

```bash
export FT_DATABASE_URL="sqlite+pysqlite:////tmp/ft-016.db"
export FT_WORKSPACE_ID=test-ws
uv run alembic upgrade head
# inspect PK types; import twice → new_rows=0

export FT_DATABASE_URL="$FT_TEST_POSTGRES_URL"
uv run alembic upgrade head
```

## ~/.ft (optional)

```bash
cp -a "$HOME/.ft/finance-tracker.db" "$HOME/.ft/finance-tracker.db.bak-016-$(date +%Y%m%d%H%M%S)"
export FT_DATABASE_URL="sqlite+pysqlite:////$HOME/.ft/finance-tracker.db"
export FT_WORKSPACE_ID=default
uv run alembic upgrade head
sqlite3 "$HOME/.ft/finance-tracker.db" "SELECT typeof(id) FROM accounts LIMIT 1;"
```

## SC mapping

| SC | Check |
|---|---|
| SC-001 | pragma / information_schema integer PKs |
| SC-002–005 | projection + import fixtures dual backend |
| SC-006–007 | SCHEMA_REVISION + no default=_uuid |
| SC-008 | no import_batches/raw_* tables |
