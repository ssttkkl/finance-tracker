# Contract: Surrogate keys (016)

## Internal

- In-scope table primary keys are integers assigned by the database.
- Application MUST NOT require callers to supply integer PKs on insert (except tests that set explicit ids if dialect allows).

## External / idempotent

- Import identity remains `(workspace_id, source_type, record_id)` for active facts.
- Account identity for users remains workspace-unique `name`.
- Integer PKs are not stable across restore-to-empty-db.

## Forbidden

- Dual UUID/int columns after cutover  
- Runtime uuid↔int mapping tables  
- Restoring 015-deleted tables  
