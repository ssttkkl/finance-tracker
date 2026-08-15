"""Mirror graph helpers used by projection (and re-exported by mirror pack).

Lives in core so projection does not depend on the mirror match pack (008 layering).
"""
from __future__ import annotations

from collections import defaultdict
from collections.abc import Iterable, Sequence

from ft.domain.relations.core.geometry import _text_blob
from ft.domain.relations.core.types import (
    BANK_CHANNEL_SOURCES,
    FactView,
    PAYMENT_PLATFORM_SOURCES,
)
from ft.domain.relations.core.keys import stable_fact_order_key


def platform_score(fact: FactView) -> int:
    """Higher = prefer as mirror canonical (platform text over bare bank channel)."""
    src = _text_blob(fact.bill_source, fact.source)
    if any(token in src for token in PAYMENT_PLATFORM_SOURCES):
        return 2
    if any(token in src for token in BANK_CHANNEL_SOURCES):
        return 1
    return 0


# Back-compat alias used inside former mirror module
_platform_score = platform_score


def canonical_mirror_fact(facts: Sequence[FactView]) -> FactView | None:
    if not facts:
        return None
    ranked = sorted(
        facts,
        key=lambda f: (
            platform_score(f),
            len(_text_blob(f.counterparty, f.note)),
            stable_fact_order_key(f),
        ),
        reverse=True,
    )
    top = ranked[0]
    if len(ranked) > 1:
        second = ranked[1]
        if (
            platform_score(top) == platform_score(second) == 2
            and len(_text_blob(top.counterparty, top.note))
            == len(_text_blob(second.counterparty, second.note))
        ):
            return None
    return top


def build_mirror_components(
    fact_ids: Iterable[str],
    accepted_mirror_pairs: Iterable[tuple[str, str]],
) -> list[set[str]]:
    parent: dict[str, str] = {}

    def find(x: str) -> str:
        parent.setdefault(x, x)
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a: str, b: str) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[rb] = ra

    for fid in fact_ids:
        parent.setdefault(fid, fid)
    for a, b in accepted_mirror_pairs:
        union(a, b)
    groups: dict[str, set[str]] = defaultdict(set)
    for fid in parent:
        groups[find(fid)].add(fid)
    return list(groups.values())
