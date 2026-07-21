# Data Model: Transaction Relations

## Active Formal Fact

Applies to `cash_transactions` (primary) and, where relevant, `investment_events` as transfer counterparties only.

| Concept | Definition |
|---|---|
| Active | Published formal fact **not** logically deleted |
| Logically deleted | Has deletion tombstone/event; excluded from current projections and matching |

### CashTransaction additions

| Field / object | Notes |
|---|---|
| existing fact columns | amount, currency, account_id, occurred_at, counterparty, description, bill_source, raw_record_id, … |
| `deleted_at` (nullable) **or** join to `fact_deletion_events` | Logical delete marker |
| `deleted_by` / `delete_reason` | Audit (on row or event) |
| active identity occupancy | At most one **active** formal fact per `(workspace, source_type, source_identity)` |

**Rules**:
- Logical delete keeps row, revisions, raw lineage.
- Re-import after delete creates a **new** active fact instance (new fact id); never clears `deleted_at` on old instance as the re-import path.
- File digest completed batches still short-circuit whole-file reimport.

### InvestmentEvent

- Unchanged core fields.
- May appear as `transfer_pair` counterparty for 银证-style legs only.
- Not a subject of payment_mirror / refund_offset / duplicate semantics.

## TransactionRelation

| Field | Notes |
|---|---|
| `id` | UUID |
| `workspace_id` | Isolation |
| `kind` | `payment_mirror` \| `transfer_pair` \| `refund_offset` |
| `subtype` | e.g. `credit_repayment` or empty |
| `primary_fact_id` | Role-mapped leg (e.g. expense / out / canonical); for open-leg may hold anchor or stay role-consistent per kind |
| `secondary_fact_id` | Other leg; **NULL allowed only** for open-leg `pending_review` on `refund_offset` / `transfer_pair` |
| `primary_fact_type` / `secondary_fact_type` | `cash` or `investment` (v1 mostly cash); secondary type null when secondary id null |
| `anchor_fact_id` (logical; may equal primary or explicit column) | Non-null anchor for open-leg and evidence; refund=refund leg; transfer=stronger signal leg |
| `status` | `pending_review` \| `accepted` \| `rejected` \| `superseded` |
| `rule_id` | Deterministic rule identifier |
| `confidence` | e.g. strong/weak or numeric 0–1 stored as decimal/string policy in impl |
| `evidence_json` | Structured snapshot (amount_delta, time_delta_seconds, signals, alias hits, …) |
| `created_by` | `system` \| user id/name |
| `created_at` | UTC |
| `decided_by` / `decided_at` / `decision_reason` | Human review |
| `superseded_by_id` | Nullable FK to newer relation |
| `revision` | Monotonic version for audit chain |

### Constraints

- **Bilateral** active uniqueness: unique among non-`superseded` rows on  
  `(workspace_id, kind, ordered_fact_pair, subtype)`  
  where ordered_fact_pair is a canonical ordering of the two fact ids (both non-null).
- **Open-leg** active uniqueness: unique among non-`superseded` open rows on  
  `(workspace_id, kind, subtype, anchor_fact_id, open)`  
  with `secondary_fact_id IS NULL` and status `pending_review` (rejected open keys also occupy to suppress auto re-open).
- `accepted` rows MUST have both fact ids non-null.
- `payment_mirror` rows MUST always have both fact ids non-null.
- Cross-kind compatibility enforced in application service (not only DB).
- Open-leg rows MUST be ignored by report projections.

### State transitions

```text
pending_review → accepted
pending_review → rejected
accepted → superseded
rejected → superseded   # only explicit reopen with new evidence/rule
```

`later` does not change status (remains `pending_review`).

## RelationEvidence (embedded)

Typical keys (not exhaustive):

```json
{
  "amount_delta": "0.00",
  "time_delta_seconds": 42,
  "same_currency": true,
  "card_tail_match": "1234",
  "account_alias_match": true,
  "counterparty_similarity": "麦当劳",
  "source_pair": ["alipay", "ccb_debit"],
  "rule_id": "payment_mirror.same_amount.card_tail.time_window.v1",
  "candidate_count": 1,
  "open_leg": true,
  "anchor_role": "refund",
  "candidate_fact_ids": ["uuid-1", "uuid-2"]
}
```

Open-leg evidence SHOULD set `open_leg=true`, `anchor_role`, `candidate_count`, and sorted `candidate_fact_ids` (top-K, default 20; empty list allowed).

## RelationCheckRun

| Field | Notes |
|---|---|
| `id` | UUID |
| `workspace_id` | Isolation |
| `trigger` | `import_batch` \| `manual_range` \| `full_recompute` |
| `seed_ref` | batch_id or range descriptor |
| `status` | `pending` \| `running` \| `completed` \| `failed` |
| `started_at` / `finished_at` | Timestamps |
| `error` | Fail-closed message if failed |
| `stats_json` | counts created/accepted/pending |

Import commits facts first; check run is independent and retriable.

## FactDeletionEvent

| Field | Notes |
|---|---|
| `id` | UUID |
| `workspace_id` | Isolation |
| `fact_id` / `fact_type` | Target formal fact |
| `actor` | User/operator |
| `reason` | Required free text or code |
| `created_at` | UTC |

Side effects (same atomic operation): mark fact deleted; supersede all active relations touching that fact.

## AccountAlias

| Field | Notes |
|---|---|
| `id` | UUID |
| `workspace_id` | Isolation |
| `alias_type` | `card_tail` \| `payment_method` \| `other` |
| `alias_value` | Normalized text |
| `account_id` | Target account |
| `created_at` / `updated_at` | Audit |

Conflicts (same alias → multiple accounts) are visible; matching must not silently pick one as sole truth.

## Report Projection (derived, not authoritative store)

Inputs: active formal facts + accepted relations.

| View | Rule |
|---|---|
| Balance | All active cash facts by account+currency |
| External expense/income | After mirror grouping, transfer exclusion, refund offset order |
| Net spend | Original expense − accepted refunds on that logical expense |

Pending/rejected/superseded relations do not affect current views.

## Legacy inline fields (non-authoritative)

`offset_*`, `proposed_action`, `transfer_account` on cash rows may remain for compatibility display only. Projection and relation authority do **not** read them for nets/pairing.

## Dual-database schema parity

| Object | PostgreSQL | SQLite |
|---|---|---|
| relations table + active business key uniqueness | yes | yes (dialect-specific unique strategy) |
| logical delete marker/event | yes | yes |
| active-only source identity occupancy | yes | yes |
| check run rows | yes | yes |
| account aliases | yes | yes |
| exact decimal in evidence/projections | NUMERIC / exact adapter | exact adapter |
| auto fallback / dual-write | forbidden | forbidden |


## Open-leg pending (logical shape)

| Field | Notes |
|---|---|
| kind | `refund_offset` or `transfer_pair` only |
| status | `pending_review` only while other leg empty |
| anchor | Non-null formal fact |
| other | Null until user bind+accept |
| suggestions | evidence only, not separate relation rows |

Accept path: provide `other_fact_id` → validate → bilateral `accepted` (or fail closed).
