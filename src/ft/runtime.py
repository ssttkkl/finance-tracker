"""PostgreSQL-only dependency composition entry point."""
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
    uow: Any = None

def build_services(settings) -> ServiceBundle:
    from ft.adapters.postgres.runtime import build_postgres_services
    return build_postgres_services(settings)
