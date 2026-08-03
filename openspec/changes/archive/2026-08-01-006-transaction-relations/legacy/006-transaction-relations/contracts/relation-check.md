# Contract: Relation Check

## Purpose

After formal facts are committed, identify and persist relations among **active** formal facts without mutating fact amounts/accounts/categories.

## Triggers

| Trigger | Seeds | Candidates |
|---|---|---|
| Successful import batch | New active formal facts from that batch | Active facts in same workspace matching kind windows |
| Manual range re-run | User-selected facts/batch/date seeds only | May lie outside seed range if rules match |
| Full recompute | Explicit full-workspace seeds | Full workspace under bounded per-kind filters |

## Non-goals for this contract

- Re-parse or re-import source files
- Roll back import because matching failed
- Full unindexed table scans as correctness requirement
- Auto logical/physical delete of facts

## Input

```text
workspace_id
seed_fact_ids[] | seed_batch_id | seed_date_range
trigger: import_batch | manual_range | full_recompute
```

## Output

```text
check_run_id
status: completed | failed
created_or_updated_relations[]
  - id, kind, subtype, fact_ids, status, rule_id, confidence, evidence
stats: {pending, accepted, rejected_skipped, supersessions}
error?: operable message (import facts remain committed)
```

## Idempotency

- Re-running the same seeds must not create duplicate active relations for the same business key  
  `(workspace, kind, ordered_fact_pair, subtype)`.
- Rejected keys stay suppressed until explicit supersede reopen.

## Auto-accept gates (summary)

See [spec.md](../spec.md) FR-016…FR-020. Strict Decimal equality for same-currency expected-equal kinds; fixed time windows; unique candidate required for auto-accept.

## Failure contract

- Fail closed for unsafe auto-accept (multi-candidate, amount delta, window miss) → `pending_review` or no relation.
- Infrastructure failure → check_run `failed`, facts untouched, retryable.


## Open-leg emission rules

For `refund_offset` and `transfer_pair` only:

| Match outcome | Persist |
|---|---|
| Unique strong auto | bilateral `accepted` |
| Unique near-strong | bilateral `pending_review` |
| ≥2 legal candidates | **one** open-leg `pending_review` (anchor set, other null, evidence.candidate_fact_ids) |
| 0 candidates, anchor shape holds | **one** open-leg `pending_review` (empty candidates) |
| No anchor shape | skip |

MUST NOT: expense/other seeds fan out N bilateral pendings for the same multi-candidate anchor.  
MUST NOT: emit open-leg for `payment_mirror`.
