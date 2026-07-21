# Contract: Import Acceptance (No Silent Skip)

## Success result (conceptual)

```json
{
  "ok": true,
  "source_lines": 100,
  "published": 12,
  "idempotent_hits": 80,
  "skipped_unpaid_closed": 8,
  "failed": 0,
  "batch_id": "…"
}
```

**MUST**: when `ok=true` and `failed=0`, `source_lines == published + idempotent_hits + skipped_unpaid_closed`.

`skipped_unpaid_closed`: Alipay unpaid-closed (and equivalent) per FR-008a — documented skip only.

## Failure result

```json
{
  "ok": false,
  "error": "mapping unmatched | parse error | …",
  "source_ref": "file name",
  "source_line": 42,
  "detail": "human actionable message"
}
```

**MUST NOT**: return `ok=true` if any source transaction line was dropped without publish or idempotent hit.

## Mapping

- Unmatched account mapping → failure (not skip).

## Idempotency

- Duplicate file/line identity → `idempotent_hits += 1`, not error (unless conflicting account target — existing fail).
