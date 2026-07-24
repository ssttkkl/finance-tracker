# Contract: Relation endpoints (016)

## Storage

- `primary_fact_id`, `secondary_fact_id`, `anchor_fact_id`, `ordered_fact_a`, `ordered_fact_b` are integers.
- `primary_fact_type` / `secondary_fact_type` remain required discriminators.

## Semantics

- Same as pre-016 relation kinds/status; only id representation changes.
- Open-leg rules unchanged.
