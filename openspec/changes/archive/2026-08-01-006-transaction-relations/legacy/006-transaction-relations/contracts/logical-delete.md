# Contract: Logical Delete & Re-import

## Logical delete formal fact

### Input

```text
workspace_id
fact_id
actor
reason
```

### Effects (single atomic operation)

1. Append deletion event / set logical delete marker on the **fact instance**.
2. Exclude fact from balance and P&L projections.
3. Supersede all active relations that reference the fact.
4. Keep Formal Fact row, RawRecord, revisions, source identity string.

### Forbidden

- Physical delete of fact/raw/revisions
- Automatic delete by relation check
- Creating `duplicate_of`
- Permanently banning the source identity

## Re-import after logical delete

### Preconditions

- No other **active** formal fact with same `(workspace, source_type, source_identity)`.
- File-level digest: if the exact file digest already completed, whole-file path still returns already-imported (digest idempotency unchanged). Re-import of the identity typically comes from another file or a non-short-circuited path that still carries the identity.

### Effects

```text
publish NEW active formal fact instance (new fact id)
do NOT clear deletion on old instance
old tombstone remains auditable
new fact enters projections and may participate in relation checks
```

## Row-level active idempotency

| Existing state | Import same identity | Result |
|---|---|---|
| Active fact exists | again | no second active fact |
| Only deleted instance(s) | again | new active fact allowed |
| Active + deleted history | again | still blocked (active occupies identity) |

## Query audit

Must be able to show: deleted instance, deletion actor/time/reason, supersede relations, and any later new active instance for same identity.
