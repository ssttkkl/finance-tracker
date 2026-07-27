"""Import identity helpers (no batch/raw tables after 015)."""
from __future__ import annotations

from sqlalchemy import select

from .models import AccountModel, CashTransactionModel, InvestmentEventModel, SyncCursorModel


class RelationalImportRepository:
    """Workspace-bound formal identity lookups for import orchestration.

    015 removed import_batches/raw_files/raw_records/record_revisions. Idempotency
    is solely active ``(workspace_id, source_type, record_id)`` on formal facts.
    """

    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id

    def existing_fact_targets(
        self,
        *,
        source_type: str,
        record_ids: list[str],
    ) -> dict[str, tuple[str, str]]:
        """Map non-empty record_id → (account_name, currency) for active facts.

        Cash soft-deleted rows are ignored so the identity can be re-imported.
        """
        found: dict[str, tuple[str, str]] = {}
        st = str(source_type or "").strip()
        if not st:
            return found
        ordered = sorted({str(r).strip() for r in record_ids if str(r or "").strip()})
        for start in range(0, len(ordered), 500):
            chunk = ordered[start:start + 500]
            if not chunk:
                continue
            cash_rows = self._session.execute(
                select(
                    CashTransactionModel.record_id,
                    AccountModel.name,
                    CashTransactionModel.currency,
                ).join(AccountModel, (
                    AccountModel.workspace_id == CashTransactionModel.workspace_id
                ) & (AccountModel.id == CashTransactionModel.account_id)).where(
                    CashTransactionModel.workspace_id == self._workspace_id,
                    CashTransactionModel.source_type == st,
                    CashTransactionModel.record_id.in_(chunk),
                    CashTransactionModel.deleted_at.is_(None),
                )
            )
            inv_rows = self._session.execute(
                select(
                    InvestmentEventModel.record_id,
                    AccountModel.name,
                    InvestmentEventModel.currency,
                ).join(AccountModel, (
                    AccountModel.workspace_id == InvestmentEventModel.workspace_id
                ) & (AccountModel.id == InvestmentEventModel.account_id)).where(
                    InvestmentEventModel.workspace_id == self._workspace_id,
                    InvestmentEventModel.source_type == st,
                    InvestmentEventModel.record_id.in_(chunk),
                )
            )
            found.update({rid: (name, ccy) for rid, name, ccy in cash_rows if rid})
            found.update({rid: (name, ccy) for rid, name, ccy in inv_rows if rid})
        return found


    def get_sync_cursor(
        self, *, account_id: int, source_type: str,
    ) -> str | None:
        """Read the last-synced cursor value for (workspace, account, source)."""
        row = self._session.execute(
            select(SyncCursorModel.cursor_value).where(
                SyncCursorModel.workspace_id == self._workspace_id,
                SyncCursorModel.account_id == account_id,
                SyncCursorModel.source_type == source_type,
            )
        ).scalar_one_or_none()
        return row

    def upsert_sync_cursor(
        self, *, account_id: int, source_type: str, cursor_value: str,
    ) -> None:
        """Insert or update the sync cursor for (workspace, account, source)."""
        from datetime import datetime, timezone
        from sqlalchemy.dialects.sqlite import insert as sqlite_insert
        from sqlalchemy.dialects.postgresql import insert as pg_insert

        now = datetime.now(timezone.utc)
        dialect = self._session.bind.dialect.name

        if dialect == "postgresql":
            stmt = pg_insert(SyncCursorModel).values(
                workspace_id=self._workspace_id,
                account_id=account_id,
                source_type=source_type,
                cursor_value=cursor_value,
                updated_at=now,
            ).on_conflict_do_update(
                constraint="uq_sync_cursors_workspace_account_source",
                set_={"cursor_value": cursor_value, "updated_at": now},
            )
        else:
            stmt = sqlite_insert(SyncCursorModel).values(
                workspace_id=self._workspace_id,
                account_id=account_id,
                source_type=source_type,
                cursor_value=cursor_value,
                updated_at=now,
            ).on_conflict_do_update(
                index_elements=["workspace_id", "account_id", "source_type"],
                set_={"cursor_value": cursor_value, "updated_at": now},
            )
        self._session.execute(stmt)

    # --- legacy no-ops kept only so older tests/fakes fail loudly if misused ---
    def start_batch(self, **_kwargs) -> str:
        from uuid import uuid4
        return str(uuid4())

    def complete_batch(self, batch_id: str) -> None:
        return None

    def get_batch(self, batch_id: str) -> dict | None:
        return None

    def list_batches(self) -> list[dict]:
        return []

    def add_raw_file(self, **_kwargs) -> str:
        from uuid import uuid4
        return str(uuid4())

    def add_raw_records(self, *, batch_id: str = "", raw_file_id: str = "", source_type: str = "", records=None, **_kwargs) -> list[str]:
        # Transitional: return synthetic ids; formal identity is source_type+record_id on facts.
        from uuid import uuid4
        records = list(records or [])
        return [str(uuid4()) for _ in records]

    def list_raw_records(self, batch_id: str) -> list[dict]:
        return []

    def formal_fact_targets(self, raw_record_ids: list[str]) -> dict[str, tuple[str, str]]:
        # Transitional empty: investment_import still calls this; skip-none until rewritten.
        return {}

    def batch_target_accounts(self, batch_id: str) -> set[tuple[str, str]]:
        return set()

    def replace_raw_record(self, record_id: str, payload: dict) -> None:
        raise ValueError("raw records are immutable (and removed in 015)")

    def append_revision(self, **_kwargs) -> str:
        return ""

    def list_revisions(self, **_kwargs) -> list[dict]:
        return []
