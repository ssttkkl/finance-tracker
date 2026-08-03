# Contract: MatchContext

**Feature**: 008-relations-kind-decouple  
**Audience**: domain packs + pipeline + tests

## Purpose

Carry **only** cross-kind collaboration data for one relation check run. Packs may read; pipeline writes between phases.

## Shape (logical JSON for tests/docs)

```json
{
  "workspace_id": "default",
  "used_fact_ids": ["fact-1", "fact-2"],
  "accepted_mirrors": [
    {"fact_a_id": "p1", "fact_b_id": "b1", "kind": "payment_mirror", "subtype": ""}
  ],
  "accepted_platform_refunds": [
    {"fact_a_id": "exp1", "fact_b_id": "ref1", "kind": "refund_offset", "subtype": ""}
  ],
  "accepted_transfers": [],
  "remaining_by_expense": {"exp1": "100.00"}
}
```

## Rules

1. Packs MUST NOT mutate context (treat as immutable snapshot per call; pipeline passes updated copies or controlled builder).
2. Diamond MUST use `accepted_mirrors` + `accepted_platform_refunds` only for chain discovery—MUST NOT call mirror matching APIs.
3. `used_fact_ids` MUST be respected for 1:1 allocation policies already defined in 006 for the kind being matched.
4. Context MUST NOT contain signal token lists or pack callables.

## Errors

Missing context fields required by a phase → fail closed (raise domain error); do not silently skip diamond with partial undefined context.
