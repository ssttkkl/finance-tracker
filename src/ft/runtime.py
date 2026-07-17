"""Dependency composition entry points.

This module is deliberately safe to import in server and test processes. Local
filesystem adapters are imported only when ``build_local_services`` is called.
"""
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class ServiceBundle:
    queries: Any = None
    cashflow_imports: Any = None
    verification: Any = None
    change_sets: Any = None
    investments: Any = None
    portfolio: Any = None
    connector_sync: Any = None
    reconciliation: Any = None
    accounts: Any = None
    cashflow: Any = None
    transfers: Any = None
    uow: Any = None


def build_local_services(ledger_root) -> ServiceBundle:
    from ft.adapters.local_runtime import build_local_services as build

    return build(ledger_root)


def build_services(settings) -> ServiceBundle:
    if settings.backend == "local":
        from ft.adapters.local_runtime import build_local_services as build
        return build(settings.ledger_root)
    from ft.adapters.postgres.runtime import build_postgres_services
    return build_postgres_services(settings)
