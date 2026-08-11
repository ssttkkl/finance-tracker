"""Shared immutable DTOs returned by application services."""
from dataclasses import dataclass, field
from typing import Mapping


class RelationImpactRequired(ValueError):
    """当前修改会使已有的关联流水失效，需要使用者明确拆开。"""

    code = "relation_impact_required"

    def __init__(self, message: str, *, fact_ids: tuple[str, ...] = ()) -> None:
        super().__init__(message)
        self.fact_ids = fact_ids


@dataclass(frozen=True)
class TextFinding:
    code: str
    message: str
    severity: str = "error"
    details: Mapping[str, object] = field(default_factory=dict)


@dataclass(frozen=True)
class ExportPayload:
    rows: tuple[dict, ...]
    suggested_filename: str | None = None
    fieldnames: tuple[str, ...] = ()


@dataclass(frozen=True)
class OperationResult:
    ok: bool
    message: str = ""
    count: int = 0
    findings: tuple[TextFinding, ...] = ()
    export: ExportPayload | None = None
    details: Mapping[str, object] = field(default_factory=dict)
