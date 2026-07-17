"""Application-facing ports for external data and configuration."""
from __future__ import annotations

from typing import Protocol, runtime_checkable


@runtime_checkable
class CashflowImporter(Protocol):
    def convert(self, command: object) -> list[dict]:
        ...


@runtime_checkable
class InvestmentImporter(Protocol):
    def convert(self, command: object) -> list[dict]:
        ...


@runtime_checkable
class ExternalConnector(Protocol):
    def fetch(self, command: object, *, credentials: dict, mapping: object) -> list[dict]:
        ...


@runtime_checkable
class ConnectorRegistry(Protocol):
    def get_connector(self, provider: str) -> ExternalConnector:
        ...


@runtime_checkable
class SecretStore(Protocol):
    def get_secret(self, provider: str, account: str | None = None) -> dict:
        ...


@runtime_checkable
class MappingProvider(Protocol):
    def get_mapping(self, name: str) -> object:
        ...


@runtime_checkable
class MarketDataProvider(Protocol):
    def get_prices(self, tickers: list[str], *, quote_currency: str) -> dict[str, object]:
        ...
