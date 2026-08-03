# Contract: 关系审查列表

## Purpose

Human review surface for `pending_review` relations, including **open-leg** pending
(`refund_offset` / `transfer_pair` with null other leg). MVP is CLI/query; full Web UI optional.

## List pending

### Input

```text
workspace_id
optional filters: kind, account, date_range, min_confidence, open_leg_only?
```

### Output rows

```text
relation_id
kind
subtype
status (= pending_review)
open_leg (bool)
anchor_fact_summary          # always present for open-leg; also usable for bilateral
primary_fact_summary         # may mirror anchor or role mapping
secondary_fact_summary       # empty/null when open_leg
evidence                     # includes candidate_fact_ids, candidate_count, anchor_role when open
confidence
rule_id
created_at
later_marker?   # optional intent timestamp; status still pending_review
```

## Accept

### Input (bilateral pending)

```text
relation_id
actor
reason? (optional)
```

### Input (open-leg pending)

```text
relation_id
actor
other_fact_id   # REQUIRED
reason? (optional)
```

### Output

```text
status=accepted
both fact ids non-null
decided_by, decided_at, decision_reason
projection effects begin for this relation
```

### Guards

- Target must be `pending_review`.
- Open-leg accept without `other_fact_id` fails closed.
- `other_fact_id` must pass kind-specific legality (sign, currency, time order, refund remaining, transfer direction/signals as applicable) and cross-kind compatibility.
- Cross-kind conflicts must be resolved (supersede conflicting accepted relations first) or accept fails closed.
- Must not accept with null other leg.

## Reject

### Input

```text
relation_id
actor
reason (required recommended)
```

### Output

```text
status=rejected
decided_by, decided_at, decision_reason
bilateral key OR open-leg anchor key not auto-reopened as pending
```

## Later / ignore

### Input

```text
relation_id
actor
```

### Output

```text
status remains pending_review
optional later_marker set
still listed in inbox
does not affect reports (including open-leg)
```

## Supersede (system or explicit admin flow)

Creates a new relation version; old becomes `superseded` with link; preserves audit chain. Automatic rules must not silently overwrite human accepted/rejected without this path. Automatic rules must not insert a second open-leg pending for an anchor that already has active open pending or reject occupancy.
