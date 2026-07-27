"""Connector sync orchestration service.

Loads credentials → validates account → reads cursor → calls connector →
batch-imports events (reusing _import_transactions pattern) → upserts cursor → reports.

Constitution I: fail-closed — any bad record rolls back the batch.
Constitution IV: identical behavior on PostgreSQL and SQLite.
"""
from __future__ import annotations

from ft.domain.application import OperationResult
from ft.domain.connector_port import (
    ConnectorAuthError,
    ConnectorDataError,
    ConnectorError,
    ConnectorPort,
)
from ft.domain.investment_projection import apply_investment_event, normalize_base_tickers
from ft.domain.investment_validation import validate_investment_snapshot


# source_type names for each provider
EXCHANGE_PROVIDERS = {"binance", "kraken", "okx"}
POLYMARKET_PROVIDER = "polymarket"
ALL_SYNC_PROVIDERS = EXCHANGE_PROVIDERS | {POLYMARKET_PROVIDER}

# Account type requirements per provider category
EXCHANGE_ACCOUNT_TYPES = {"crypto"}
POLYMARKET_ACCOUNT_TYPES = {"security", "crypto"}

DEFAULT_BATCH_SIZE = 500


def _source_type_for_provider(provider: str) -> str:
    """Return the source_type string for a given provider name."""
    return f"{provider}_api"


class SyncService:
    """Application service for connector-based sync.

    Orchestrates: credentials → account validation → cursor → connector fetch →
    batch import → cursor update → report.
    """

    def __init__(self, unit_of_work):
        self._uow = unit_of_work

    def sync(
        self,
        *,
        provider: str,
        account_name: str,
        full: bool = False,
        batch_size: int = DEFAULT_BATCH_SIZE,
        connector: ConnectorPort | None = None,
    ) -> OperationResult:
        """Execute a sync operation for the given provider and account.

        Parameters
        ----------
        provider:
            Provider name (e.g., 'binance', 'polymarket').
        account_name:
            Target account name in the workspace.
        full:
            If True, ignore saved cursor and fetch all trades.
        batch_size:
            Maximum events per batch transaction.
        connector:
            Pre-built connector instance. If None, one is created from credentials.
        """
        if provider not in ALL_SYNC_PROVIDERS:
            return OperationResult(
                ok=False,
                message=f"Unknown sync provider: {provider}. Supported: {', '.join(sorted(ALL_SYNC_PROVIDERS))}",
            )
        if batch_size <= 0:
            return OperationResult(ok=False, message="batch_size must be positive")

        source_type = _source_type_for_provider(provider)

        # Validate account exists and has correct type
        with self._uow as uow:
            account = uow.accounts.find(account_name)
            if account is None:
                uow.rollback()
                return OperationResult(
                    ok=False,
                    message=f"Account not found: {account_name}",
                )
            allowed_types = (
                EXCHANGE_ACCOUNT_TYPES if provider in EXCHANGE_PROVIDERS
                else POLYMARKET_ACCOUNT_TYPES
            )
            if account.type not in allowed_types:
                uow.rollback()
                return OperationResult(
                    ok=False,
                    message=(
                        f"Account '{account_name}' is type '{account.type}'; "
                        f"{provider} sync requires {' or '.join(sorted(allowed_types))}"
                    ),
                )
            account_type = account.type
            # AccountDTO has no .id; look up the surrogate PK from the DB model.
            account_id = self._resolve_account_id(uow, account_name)
            uow.rollback()

        # Connector must be provided by caller (CLI layer builds from credentials)
        if connector is None:
            return OperationResult(
                ok=False,
                message=f"No connector provided for {provider}",
            )

        # Read cursor (unless --full)
        since: str | None = None
        if not full:
            with self._uow as uow:
                since = uow.imports.get_sync_cursor(
                    account_id=account_id, source_type=source_type,
                )
                uow.rollback()

        # Fetch trades from connector
        try:
            result = connector.fetch_trades(since=since)
        except ConnectorAuthError as exc:
            return OperationResult(
                ok=False,
                message=f"Authentication failed for {provider}: {exc}",
            )
        except ConnectorDataError as exc:
            return OperationResult(
                ok=False,
                message=f"Data error from {provider}: {exc}",
            )
        except ConnectorError as exc:
            if since is None:
                return OperationResult(
                    ok=False,
                    message=f"Connector error for {provider}: {exc}",
                )
            try:
                result = connector.fetch_trades(since=None)
            except ConnectorAuthError as retry_exc:
                return OperationResult(
                    ok=False,
                    message=f"Authentication failed for {provider}: {retry_exc}",
                )
            except ConnectorDataError as retry_exc:
                return OperationResult(
                    ok=False,
                    message=f"Data error from {provider}: {retry_exc}",
                )
            except ConnectorError as retry_exc:
                return OperationResult(
                    ok=False,
                    message=(
                        f"Connector error for {provider}; stale cursor fallback failed: {retry_exc}"
                    ),
                )

        events = result.events
        if not events:
            # A connector can complete a legitimate empty scan (notably a
            # pUSD window with no external transfers).  Its checkpoint still
            # belongs to the same atomic cursor contract as a non-empty batch.
            try:
                self._import_all_batches(
                    batches=[],
                    account_name=account_name,
                    account_id=account_id,
                    account_type=account_type,
                    source_type=source_type,
                    next_cursor=result.next_cursor,
                )
            except Exception as exc:
                return OperationResult(
                    ok=False,
                    message=f"Sync failed: {exc}. No events, snapshot, or cursor were written.",
                    details={"raw_count": result.raw_count, "new_count": 0, "skipped_count": 0},
                )
            return OperationResult(
                ok=True,
                count=0,
                message=f"No new trades from {provider}",
                details={
                    "raw_count": result.raw_count,
                    "new_count": 0,
                    "skipped_count": 0,
                    "batch_count": 0,
                },
            )

        batches = [events[start:start + batch_size] for start in range(0, len(events), batch_size)]
        try:
            total_new, total_skipped = self._import_all_batches(
                batches=batches,
                account_name=account_name,
                account_id=account_id,
                account_type=account_type,
                source_type=source_type,
                next_cursor=result.next_cursor,
            )
        except Exception as exc:
            return OperationResult(
                ok=False,
                message=f"Sync failed: {exc}. No events, snapshot, or cursor were written.",
                details={
                    "raw_count": result.raw_count,
                    "new_count": 0,
                    "skipped_count": 0,
                },
            )

        return OperationResult(
            ok=True,
            count=total_new,
            message=(
                f"Synced {total_new} new events from {provider}"
                + (f" ({total_skipped} duplicates skipped)" if total_skipped else "")
            ),
            details={
                "raw_count": result.raw_count,
                "new_count": total_new,
                "skipped_count": total_skipped,
                "batch_count": len(batches),
            },
        )


    @staticmethod
    def _resolve_account_id(uow, account_name: str) -> int:
        """Look up the integer account PK through the session."""
        from sqlalchemy import select
        from ft.adapters.relational.models import AccountModel

        state = uow._state()
        row = state.session.scalar(
            select(AccountModel.id).where(
                AccountModel.workspace_id == uow.workspace_id,
                AccountModel.name == account_name,
            )
        )
        if row is None:
            raise ValueError(f"Account not found: {account_name}")
        return row



    def _import_all_batches(
        self,
        *,
        batches: list[list[dict]],
        account_name: str,
        account_id: int,
        account_type: str,
        source_type: str,
        next_cursor: str | None,
    ) -> tuple[int, int]:
        """Import every processing chunk in one UnitOfWork transaction.

        Chunks bound processing work only; an error in any chunk rolls back
        all events, the projected snapshot, and the cursor together.
        """
        with self._uow as uow:
            record_ids = [
                str(event.get("record_id", "")).strip()
                for batch in batches
                for event in batch
            ]

            existing = uow.imports.existing_fact_targets(
                source_type=source_type, record_ids=record_ids,
            )

            snapshot = uow.snapshot.load(lock=True)
            new_count = 0
            skipped = 0

            # Resolve base tickers for projection
            base_tickers = normalize_base_tickers(None)

            for batch in batches:
                for event in batch:
                    record_id = str(event.get("record_id", "")).strip()
                    if record_id in existing:
                        skipped += 1
                        continue

                    inv_event = dict(event)
                    inv_event["source_type"] = source_type
                    inv_event["record_id"] = record_id
                    inv_event["account_name"] = account_name

                    currency = str(event.get("currency", "USD")).upper()
                    apply_investment_event(
                        snapshot, inv_event,
                        default_currency=currency,
                        base_tickers=base_tickers,
                    )

                    uow.investments.add(account_type, inv_event)
                    existing[record_id] = (account_name, currency)
                    new_count += 1

            if new_count:
                # Validate and save snapshot
                validate_investment_snapshot(snapshot)
                uow.snapshot.save(snapshot)

            if next_cursor is not None:
                uow.imports.upsert_sync_cursor(
                    account_id=account_id,
                    source_type=source_type,
                    cursor_value=next_cursor,
                )

            uow.commit()

        return new_count, skipped
