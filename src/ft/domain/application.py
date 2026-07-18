"""Shared immutable DTOs returned by application services."""
from dataclasses import dataclass, field
from typing import Mapping


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
