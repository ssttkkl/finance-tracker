# Contract: Import idempotency

## Key

```
identity = (workspace_id, source_type, record_id)
```

- `source_type`: non-empty import channel name  
- `record_id`: non-empty business row key  

## Rules

1. If active cash fact exists with identity → skip insert (new_count += 0 for that row).  
2. Soft-deleted cash with same identity does **not** block insert of a new active row.  
3. Investment: non-empty identity unique (no soft-delete).  
4. Empty `source_type` or empty `record_id` → not subject to identity unique (manual rows).  
5. File digest / batch id must not gate formalize.  
6. Different `source_type`, same `record_id` string → **two** identities.  

## Seeds for relations

After import: `seed_fact_ids: list[str]` of newly inserted fact ids only.
