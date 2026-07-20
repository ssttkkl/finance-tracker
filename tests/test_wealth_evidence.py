from datetime import datetime, timezone
from decimal import Decimal

import pytest


def test_evidence_is_totally_ordered_folded_and_cursor_bound() -> None:
    from ft.domain.wealth import WealthError
    from ft.domain.wealth_calculation import EvidenceItem, page_evidence

    first = EvidenceItem("e2", "source", "r", datetime(2026, 7, 1, tzinfo=timezone.utc), "fact", Decimal("1"), "same")
    second = EvidenceItem("e1", "source", "r", datetime(2026, 7, 1, tzinfo=timezone.utc), "fact", Decimal("2"), "same")
    third = EvidenceItem("e3", "source", "r", datetime(2026, 7, 2, tzinfo=timezone.utc), "fact", Decimal("4"), "other")
    page = page_evidence("component", "result", (first, second, third), limit=1)
    assert page.items[0].evidence_identity == "e1"
    assert page.items[0].contribution == Decimal("3")
    assert page.next_cursor
    assert page_evidence("component", "result", (first, second, third), cursor=page.next_cursor, limit=1).items[0].contribution == Decimal("4")
    with pytest.raises(WealthError, match="wealth.evidence_cursor_invalid"):
        page_evidence("other", "result", (first,), cursor=page.next_cursor, limit=1)


def test_application_evidence_delegates_to_immutable_component_scope() -> None:
    from ft.application.wealth import WealthChangeService
    from ft.domain.wealth_calculation import EvidenceItem

    class Facts:
        def component_evidence(self, component_id, result_revision):
            assert (component_id, result_revision) == ("c", "r")
            return (EvidenceItem("e", "s", "r", datetime(2026, 7, 1, tzinfo=timezone.utc), "fact", Decimal("1"), "e"),)

    assert WealthChangeService(Facts()).evidence("c", "r").items[0].evidence_identity == "e"
