from __future__ import annotations
from typing import Iterable
from ft.domain.relations.core.types import RelationKind

def cross_kind_compatible(existing_kinds: Iterable[str], new_kind: str) -> bool:
    kinds = set(existing_kinds) | {new_kind}
    return not {
        RelationKind.TRANSFER_PAIR.value,
        RelationKind.REFUND_OFFSET.value,
    }.issubset(kinds)
