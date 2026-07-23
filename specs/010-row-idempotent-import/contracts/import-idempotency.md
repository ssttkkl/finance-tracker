# Contract: Import Idempotency (Cash + Investment)

**Feature**: 010-row-idempotent-import

## CLI / service outcome

`ft import` (cash sources via StatementImportService; investment sources via InvestmentImportService):

| Outcome | ok | count | details |
|---------|----|-------|---------|
| New rows applied | true | N > 0 | batch_id, not full-file duplicate |
| Full overlap / same file re-import | true | 0 | message indicates no new rows; optional `duplicate` or `new_rows: 0` |
| Parse / validation / wrong account | false | — | no partial formal facts |

## Forbidden

- Return “already imported” **only** because `source_digest` matches a completed batch **without** performing identity classification (regression: SC-005).
- Apply `apply_investment_event` / cash formalize for raw_ids that already have formal facts.

## Required service behavior

### StatementImportService

1. Parse always (subject to size limits).
2. `start_batch` may reuse digest → same batch_id.
3. **Do not** return early solely on `status==completed`.
4. `add_raw_records` + `formal_fact_targets` + formalize only novel.

### InvestmentImportService

1. Compute digest for batch metadata only.
2. **Do not** `_find_existing_batch` early return that skips import work.
3. Parse → start_batch → add_raw_records → **skip raw_ids in formal_fact_targets** → map/apply/add only novel → validate snapshot → complete_batch.

## Dual-backend

Same fixture sequences → same N new / same final balances or positions on SQLite and PostgreSQL (`FT_TEST_POSTGRES_URL` Docker :55432).
