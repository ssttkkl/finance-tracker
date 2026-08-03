# Quickstart: PostgreSQL-Only Storage

## Feature-start statement matrix

| Source | Required input format | Existing parser evidence |
|---|---|---|
| Alipay | CSV | `convert._read_alipay_raw` and `tests/test_convert.py` |
| WeChat | XLSX | `convert._read_wechat_raw` and `tests/test_convert.py` |
| ICBC credit | encrypted PDF | `convert._read_icbc_raw` |
| ICBC debit | password PDF | `convert._read_icbc_debit_raw` |
| CCB debit | XLS | `importers.ccb_debit.read_ccb_debit` |
| DFZQ | PDF converted to text by qpdf/mutool | `importers.dfzq.parse_dfzq_text` |

JSON and YAML are not part of the required feature-start matrix.

## Prerequisites

```bash
export FT_DATABASE_URL='postgresql+psycopg://localhost/finance_tracker'
export FT_WORKSPACE_ID='default'
uv run alembic upgrade head
uv run python -c "import os; from sqlalchemy import create_engine; from ft.adapters.postgres import create_session_factory, ensure_workspace; ensure_workspace(create_session_factory(create_engine(os.environ['FT_DATABASE_URL'])), os.environ['FT_WORKSPACE_ID'])"
```

Workspace provisioning is an explicit development/admin step, not a side effect of ordinary CLI commands.

## Verify configuration and schema

```bash
uv run ft acct list
uv run alembic heads
```

Without `FT_DATABASE_URL`, `FT_WORKSPACE_ID`, current schema, or the workspace, `ft` must fail and must not
create `~/.ft`.

## Core smoke flow

```bash
uv run ft acct add Wallet --type cash --currency CNY
uv run ft add --amount -12.50 --counterparty Coffee --account Wallet --currency CNY
uv run ft list --account Wallet
uv run ft report
```

All four commands observe the same workspace data.

## Direct statement import

```bash
uv run ft import path/to/statement.csv --source alipay --account Wallet --currency CNY
```

The source digest, parsed raw records, formal facts, revisions, and projection commit atomically to PostgreSQL.

## Test gates

```bash
uv run pytest tests/test_storage_configuration.py tests/test_alembic_migration.py
uv run pytest tests/test_postgres_adapter.py tests/test_postgres_import_provenance.py
uv run pytest
FT_TEST_POSTGRES_URL='postgresql+psycopg://localhost/finance_tracker_test' \
  uv run pytest tests/test_postgres_live.py
uv run alembic heads
git diff --check
```

Legacy executable references must be zero:

```bash
rg -n 'build_local_services|LocalCsv|local_migration|MigrationService|shadow comparison|FT_STORAGE_BACKEND|FT_DIR|~/.ft|ft (migrate|commit|status|reset)' \
  src tests README.md docs SKILL.md references
```
