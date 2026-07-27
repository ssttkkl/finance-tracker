"""Connector port protocol and value objects for investment sync."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol, runtime_checkable


class ConnectorError(Exception):
    """Connector recoverable error (after retries exhausted)."""


class ConnectorAuthError(ConnectorError):
    """Invalid credentials (401/403)."""


class ConnectorDataError(ConnectorError):
    """Data anomaly (missing required fields, format errors)."""


@dataclass(frozen=True)
class ConnectorResult:
    """Result of a connector fetch operation."""
    events: list[dict] = field(default_factory=list)
    next_cursor: str | None = None
    raw_count: int = 0


@runtime_checkable
class ConnectorPort(Protocol):
    """Domain-layer connector interface."""

    @property
    def source_type(self) -> str:
        """Return the source_type identifier for this connector."""
        ...

    def fetch_trades(
        self,
        *,
        since: str | None = None,
    ) -> ConnectorResult:
        """Fetch trades from external data source.

        Parameters
        ----------
        since:
            Incremental cursor value (last sync checkpoint). None means full fetch.

        Returns
        -------
        ConnectorResult:
            Contains standardized investment event list and next cursor.
        """
        ...
