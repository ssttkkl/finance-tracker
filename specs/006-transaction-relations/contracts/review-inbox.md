# Contract: Review Inbox

## Purpose

Human review surface for `pending_review` relations. MVP is CLI/query; full Web UI optional.

## List pending

### Input

```text
workspace_id
optional filters: kind, account, date_range, min_confidence
```

### Output rows

```text
relation_id
kind
subtype
status (= pending_review)
primary_fact_summary
secondary_fact_summary
evidence
confidence
rule_id
created_at
later_marker?   # optional intent timestamp; status still pending_review
```

## Accept

### Input

```text
relation_id
actor
reason? (optional)
```

### Output

```text
status=accepted
decided_by, decided_at, decision_reason
projection effects begin for this relation
```

### Guards

- Target must be `pending_review`.
- Cross-kind conflicts must be resolved (supersede conflicting accepted relations first) or accept fails closed.

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
same business key not auto-reopened as pending
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
does not affect reports
```

## Supersede (system or explicit admin flow)

Creates a new relation version; old becomes `superseded` with link; preserves audit chain. Automatic rules must not silently overwrite human accepted/rejected without this path.
