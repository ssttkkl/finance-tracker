# Quickstart / Validation: Fact Field Unification

**Feature**: 014-fact-field-unify

## Commands

```bash
uv run pytest tests/test_fact_field_unify.py tests/test_alembic_migration.py -q
uv run pytest tests/ -q
# Optional Postgres matrix:
# FT_TEST_POSTGRES_URL=postgresql+psycopg://.../ft_test FT_REQUIRE_TEST_POSTGRES=1 uv run pytest tests/ -q
```

## Evidence nodes

- `tests/test_fact_field_unify.py::test_schema_end_state_has_note_and_action_not_legacy_names`
- `tests/test_fact_field_unify.py::test_cash_and_investment_public_rows_use_catalog_names`
- `tests/test_fact_field_unify.py::test_migration_conflict_fails_closed`
- Full suite regression after field renames

## Expected

- cash_transactions.note present; description absent
- investment_events.action + legs present; kind absent
- public cash keys: note, occurred_at
- conflict migration raises with fact id
