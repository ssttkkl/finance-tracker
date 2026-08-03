# Data Model: PostgreSQL and SQLite Runtime Parity

## Model Boundary

PostgreSQL and SQLite share the entities, relationships, constraints, validation, and migration head
below. Differences in native column types and locking are physical adapter details and do not change
the logical model. Every workspace-owned relationship includes `workspace_id` so a foreign key or
query cannot join facts across workspaces.

## Runtime Database Selection

This is immutable process configuration, not a persisted table.

| Field | Type | Rules |
|---|---|---|
| `database_url` | structured SQLAlchemy URL | Required; backend is `postgresql` or file `sqlite`; password/query/full SQLite path never rendered |
| `dialect` | enum | Derived once as `postgresql` or `sqlite`; never a fallback list |
| `workspace_id` | string | Required, 1-64 characters according to persisted workspace key |
| `connection_summary` | sanitized string/value object | Safe dialect-specific summary; contains no secret or complete SQLite path |

Validation occurs before creating services. A memory SQLite URL is valid only in explicit test
fixtures and is rejected by runtime settings.

## Runtime Notice

Immutable, non-persisted operational information carried by `ServiceBundle.notices`.

| Field | Type | Rules |
|---|---|---|
| `code` | enum-like string | Stable notice category such as `storage.permissions` |
| `message` | string | Controlled actionable text with no password, URL query, or full SQLite path |
| `connection_summary` | sanitized string/value object | Same safe summary used by storage errors |

Notices never change command success, are emitted at most once per service composition, and are not
stored as financial facts or mixed into domain operation results.

## Workspace

Root isolation boundary for all formal and audit state.

| Field | Logical type | Rules |
|---|---|---|
| `id` | string(64) | Primary key; required |
| `name` | string(255) | Required |
| `created_at` | UTC datetime | Required, timezone-aware at model boundary |

Deleting a workspace cascades only through its own rows. Normal runtime commands require an already
provisioned workspace and never create one implicitly.

## Account

| Field | Logical type | Rules |
|---|---|---|
| `id` | UUID string(36) | Primary key |
| `workspace_id` | string(64) | Required foreign key to Workspace |
| `name` | string(255) | Required; unique with workspace and currency |
| `type` | string(32) | Existing account type validation remains in Application Service/domain |
| `currency` | ISO-like uppercase string(3) | Required; same normalization on both backends |
| `active` | boolean | Required; defaults true |
| `metadata_json` | JSON object | Required; defaults empty object |
| `created_at`, `updated_at` | UTC datetime | Required |

Additional unique key `(workspace_id, id)` supports workspace-qualified foreign keys.

## Import Batch

Tracks one source document identity and its atomic publishing state.

| Field | Logical type | Rules |
|---|---|---|
| `id` | UUID string(36) | Primary key |
| `workspace_id` | string(64) | Required |
| `target_account_id` | UUID string(36) | Required account in the same workspace |
| `source_kind` | string(64) | Required parser/provider kind |
| `source_digest` | string(128) | Required deterministic content digest |
| `source_ref` | text | Required provenance reference; must not be emitted as database diagnostics |
| `status` | enum-like string(32) | `pending` then `completed`; rolled-back attempts leave no partial batch |
| `created_at`, `completed_at` | UTC datetime | Completion time is nullable until completed |

Unique `(workspace_id, source_kind, source_digest)` is the batch idempotency key.

## Raw File

| Field | Logical type | Rules |
|---|---|---|
| `id` | UUID string(36) | Primary key |
| `workspace_id`, `batch_id` | strings | Required same-workspace Import Batch relation |
| `source_path` | text | Required audit provenance; not permitted in runtime connection errors |
| `content_digest` | string(128) | Required |
| `size_bytes` | integer | Non-negative at input boundary |
| `media_type` | string(128) | Required |
| `created_at` | UTC datetime | Required |

Unique `(workspace_id, batch_id, content_digest)` prevents duplicate file registration within a
batch.

## Raw Record

Immutable provider/parser record used as formal-fact provenance.

| Field | Logical type | Rules |
|---|---|---|
| `id` | UUID string(36) | Primary key |
| `workspace_id`, `batch_id` | strings | Required same-workspace batch relation |
| `raw_file_id` | UUID string(36), nullable | When present, belongs to same workspace and batch |
| `source_type` | string(64) | Required |
| `source_identity` | string(512) | Required deterministic provider identity |
| `source_line` | integer, nullable | Optional audit location |
| `payload` | JSON object | Required immutable normalized raw payload |
| `created_at` | UTC datetime | Required |

Unique `(workspace_id, source_type, source_identity)` is the provider-record idempotency key.

## Cash Transaction

Formal cash fact.

| Field group | Logical type and rules |
|---|---|
| Identity | UUID `id`; required `workspace_id`; required same-workspace `account_id`; optional unique same-workspace `raw_record_id` |
| Time | Required timezone-aware `occurred_at`, persisted/restored as UTC |
| Money | Required exact `amount`, maximum 38 total / 18 fractional digits; required currency string(3); never float |
| Business detail | Existing record ID, counterparty, description, category, source, bill source, transfer and offset fields retain shared defaults/validation |
| Audit | Integer `revision` and required UTC `created_at` |

PostgreSQL stores amount as `NUMERIC(38,18)`; SQLite stores the validated canonical decimal string.

## Investment Event

Append-oriented formal investment fact.

| Field group | Logical type and rules |
|---|---|
| Identity | UUID `id`; required workspace/account; optional unique same-workspace raw record |
| Event | Required UTC `occurred_at`, event `kind`, currency, and JSON payload |
| Exact values | All monetary/quantity values inside validated payloads are serialized canonically and originate from `Decimal`, never float |
| Audit | Integer `revision` and required UTC `created_at` |

## Ledger Snapshot

Materialized projection scoped to one workspace.

| Field | Logical type | Rules |
|---|---|---|
| `workspace_id` | string(64) | Primary and foreign key to Workspace |
| `payload` | JSON object | Required; account balances/positions use canonical decimal strings |
| `version` | integer | Required monotonic update counter |
| `created_at`, `updated_at` | UTC datetime | Required |

### Projection transition

1. Command UoW obtains the backend's write/row lock before reading the snapshot.
2. Existing payload is mapped from stable account IDs to names for Application Service use.
3. The service applies the deterministic fact delta using exact decimals.
4. Save maps names back to account IDs and increments `version`.
5. Fact and snapshot commit together; any failure rolls back both.

For SQLite, step 1 is transaction-wide writer reservation. For PostgreSQL, it is workspace/snapshot
row locking. Concurrent outcomes may be success-success or success-busy on SQLite, but never a lost
committed delta.

## Record Revision

Append-only audit record for a formal fact change.

| Field | Logical type | Rules |
|---|---|---|
| `id` | UUID string(36) | Primary key |
| `workspace_id` | string(64) | Required |
| `cash_transaction_id` | UUID, nullable | Same-workspace fact target |
| `investment_event_id` | UUID, nullable | Same-workspace fact target |
| `before`, `after` | JSON objects | Required snapshots of the audited change |
| `actor_type` | string | Required |
| `reason` | text/string | Required audit reason |
| `created_at` | UTC datetime | Required |

Exactly one of the two target IDs must be non-null. Revisions are appended, never overwritten.

## Exact Decimal Contract

- Input must convert directly to finite `Decimal` without a binary-float intermediate.
- Fractional scale must be at most 18 and total supported precision at most 38.
- PostgreSQL physical type: `NUMERIC(38,18)`.
- SQLite physical type: text containing the non-exponent canonical decimal representation.
- Reads always return `Decimal`; projection JSON stores canonical strings.
- Equality tests compare decimal value and canonical normalized output, not database storage class.

## Time Contract

- Naive persisted datetimes are rejected at the model boundary.
- Aware inputs are converted to UTC before binding.
- SQLite values that return without `tzinfo` are explicitly restored as UTC.
- User date/time parsing and Asia/Shanghai day/month bucketing remain in shared domain/application
  code and are tested through both databases.

## Transaction and State Invariants

- Unknown workspace fails before any command mutation.
- A successful import moves `pending` to `completed` in the same transaction as raw/file/formal facts,
  revisions, and projection updates.
- A failed import leaves no newly visible batch, raw record, formal fact, revision, or projection delta.
- Duplicate idempotency keys return the existing logical result or a stable rejection; they do not
  create a second fact.
- Every persisted/query row is filtered by workspace; generated IDs are not user-visible parity keys.
- Schema revision must equal the single expected Alembic head before runtime services are returned.
