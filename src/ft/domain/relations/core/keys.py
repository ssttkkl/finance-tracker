from __future__ import annotations
from typing import Any, Mapping
from ft.domain.relations.core.types import OPEN_LEG_CANDIDATE_TOP_K, OPEN_LEG_ORDERED_B_SENTINEL, RelationKind, SUBTYPE_NONE
def ordered_fact_pair(fact_a: str, fact_b: str | None) -> tuple[str, str]:
    """Bilateral ordered pair. Open-leg uses empty secondary → (anchor, '')."""
    a = str(fact_a or "")
    if fact_b is None or fact_b == "":
        return (a, OPEN_LEG_ORDERED_B_SENTINEL)
    b = str(fact_b)
    return (a, b) if a <= b else (b, a)


def relation_business_key(
    workspace_id: str,
    kind: str,
    fact_a: str,
    fact_b: str | None,
    subtype: str = SUBTYPE_NONE,
) -> tuple[str, str, str, str, str]:
    left, right = ordered_fact_pair(fact_a, fact_b)
    return (workspace_id, kind, left, right, subtype or SUBTYPE_NONE)


def open_leg_business_key(
    workspace_id: str,
    kind: str,
    anchor_fact_id: str,
    subtype: str = SUBTYPE_NONE,
) -> tuple[str, str, str, str]:
    """Open-leg active key (workspace, kind, subtype, anchor)."""
    return (workspace_id, kind, subtype or SUBTYPE_NONE, str(anchor_fact_id))


def is_open_leg_relation(row: Mapping[str, Any] | None) -> bool:
    if not row:
        return False
    if row.get("secondary_fact_id") in (None, ""):
        return True
    evidence = row.get("evidence") or {}
    if isinstance(evidence, Mapping) and evidence.get("open_leg"):
        return True
    return False


def top_k_candidate_ids(
    candidate_ids: Sequence[str],
    *,
    k: int = OPEN_LEG_CANDIDATE_TOP_K,
) -> tuple[str, ...]:
    """Stable sorted top-K candidate fact ids for open-leg evidence."""
    ordered = sorted({str(cid) for cid in candidate_ids if cid})
    return tuple(ordered[: max(0, int(k))])


