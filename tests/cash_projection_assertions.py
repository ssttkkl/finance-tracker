"""收支投影的去标识化共享场景。"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from zoneinfo import ZoneInfo


_UTC = ZoneInfo("UTC")


@dataclass(frozen=True)
class ProjectionFact:
    id: int
    account_id: int
    occurred_at: datetime
    amount: Decimal
    currency: str = "CNY"
    counterparty: str = "示例商户"
    category_id: str | None = "category-fixture"
    note: str = ""
    source_type: str = "fixture"
    record_id: str = ""


@dataclass(frozen=True)
class ProjectionRelation:
    id: int
    kind: str
    primary_fact_id: int
    secondary_fact_id: int
    status: str = "accepted"
    subtype: str = ""


@dataclass(frozen=True)
class ProjectionScenario:
    facts: tuple[ProjectionFact, ...]
    relations: tuple[ProjectionRelation, ...]


def projection_scenarios() -> dict[str, ProjectionScenario]:
    """返回覆盖收支投影规则的固定场景，供领域和双后端测试复用。"""
    def fact(identifier: int, amount: str, day: int, *, account_id: int = 101) -> ProjectionFact:
        return ProjectionFact(
            id=identifier,
            account_id=account_id,
            occurred_at=datetime(2026, 1, day, 9, tzinfo=_UTC),
            amount=Decimal(amount),
            record_id=f"cash-{identifier}",
        )

    return {
        "single": ProjectionScenario((fact(1, "-20", 1), fact(2, "30", 2), fact(3, "0", 3)), ()),
        "payment_mirror": ProjectionScenario(
            (fact(10, "-100", 4), fact(11, "-100", 4)),
            (ProjectionRelation(101, "payment_mirror", 10, 11),),
        ),
        "partial_refund": ProjectionScenario(
            (fact(20, "-100", 5), fact(21, "30", 8)),
            (ProjectionRelation(102, "refund_offset", 20, 21),),
        ),
        "full_refund": ProjectionScenario(
            (fact(30, "-50", 6), fact(31, "50", 9)),
            (ProjectionRelation(103, "refund_offset", 30, 31),),
        ),
        "transfer": ProjectionScenario(
            (fact(40, "-200", 7, account_id=101), fact(41, "200", 7, account_id=102)),
            (ProjectionRelation(104, "transfer_pair", 40, 41, subtype="ordinary_transfer"),),
        ),
        "inactive_relation": ProjectionScenario(
            (fact(50, "-12", 10), fact(51, "-12", 10)),
            (ProjectionRelation(105, "payment_mirror", 50, 51, status="pending_review"),),
        ),
        "illegal_refund": ProjectionScenario(
            (fact(60, "-20", 11), fact(61, "30", 12)),
            (ProjectionRelation(106, "refund_offset", 60, 61),),
        ),
    }
