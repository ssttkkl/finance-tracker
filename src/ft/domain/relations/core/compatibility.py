from __future__ import annotations
from typing import Iterable
from ft.domain.relations.core.types import RelationKind

def cross_kind_compatible(existing_kinds: Iterable[str], new_kind: str) -> bool:
    kinds = set(existing_kinds) | {new_kind}
    if RelationKind.TRANSFER_PAIR.value in kinds and (
        RelationKind.PAYMENT_MIRROR.value in kinds or RelationKind.REFUND_OFFSET.value in kinds
    ):
        return False
    return True


