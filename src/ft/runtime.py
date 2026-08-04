"""Dependency composition entry point for the selected relational database."""
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class ServiceBundle:
    queries: Any = None
    investments: Any = None
    portfolio: Any = None
    accounts: Any = None
    cashflow: Any = None
    transfers: Any = None
    statement_import: Any = None
    relations: Any = None
    funding_relations: Any = None
    wealth: Any = None
    uow: Any = None
    notices: tuple[str, ...] = ()

def build_services(settings) -> ServiceBundle:
    from ft.adapters.relational.runtime import build_relational_services
    return build_relational_services(settings)
