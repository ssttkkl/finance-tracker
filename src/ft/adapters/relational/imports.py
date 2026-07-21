"""Import provenance repository with immutable raw facts and revisions."""
from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from .models import (
    AccountModel,
    CashTransactionModel,
    ImportBatchModel,
    InvestmentEventModel,
    RawFileModel,
    RawRecordModel,
    RecordRevisionModel,
)
from .repositories import _json_safe


class RelationalImportRepository:
    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id

    def start_batch(
        self, *, source_kind: str, source_digest: str, source_ref: str,
        target_account_name: str | None = None,
        target_account_currency: str | None = None,
    ) -> str:
        target_account_id = None
        if target_account_name is not None:
            target_account_id = self._session.scalar(select(AccountModel.id).where(
                AccountModel.workspace_id == self._workspace_id,
                AccountModel.name == target_account_name,
            ))
            if target_account_id is None:
                raise ValueError(f"target account not found: {target_account_name}")
        existing = self._session.scalar(select(ImportBatchModel).where(
            ImportBatchModel.workspace_id == self._workspace_id,
            ImportBatchModel.source_kind == source_kind,
            ImportBatchModel.source_digest == source_digest,
        ))
        if existing is not None:
            return existing.id
        batch = ImportBatchModel(
            workspace_id=self._workspace_id,
            target_account_id=target_account_id,
            source_kind=source_kind,
            source_digest=source_digest,
            source_ref=source_ref,
            status="pending",
        )
        if self._session.bind.dialect.name == "sqlite":
            self._session.add(batch)
            self._session.flush()
            return batch.id
        try:
            with self._session.begin_nested():
                self._session.add(batch)
                self._session.flush()
            return batch.id
        except IntegrityError:
            existing = self._session.scalar(select(ImportBatchModel).where(
                ImportBatchModel.workspace_id == self._workspace_id,
                ImportBatchModel.source_kind == source_kind,
                ImportBatchModel.source_digest == source_digest,
            ))
            if existing is None:
                raise
            return existing.id

    def complete_batch(self, batch_id: str) -> None:
        batch = self._batch(batch_id)
        batch.status = "completed"
        batch.completed_at = datetime.now(timezone.utc)

    def get_batch(self, batch_id: str) -> dict | None:
        batch = self._session.scalar(select(ImportBatchModel).where(
            ImportBatchModel.workspace_id == self._workspace_id,
            ImportBatchModel.id == batch_id,
        ))
        return None if batch is None else self._batch_dict(batch)

    def list_batches(self) -> list[dict]:
        rows = self._session.scalars(
            select(ImportBatchModel)
            .where(ImportBatchModel.workspace_id == self._workspace_id)
            .order_by(ImportBatchModel.created_at, ImportBatchModel.id)
        )
        return [self._batch_dict(row) for row in rows]

    def add_raw_file(
        self, *, batch_id: str, source_path: str, content_digest: str,
        size_bytes: int, media_type: str,
    ) -> str:
        self._batch(batch_id)
        existing = self._session.scalar(select(RawFileModel).where(
            RawFileModel.workspace_id == self._workspace_id,
            RawFileModel.batch_id == batch_id,
            RawFileModel.content_digest == content_digest,
        ))
        if existing is not None:
            return existing.id
        raw_file = RawFileModel(
            workspace_id=self._workspace_id,
            batch_id=batch_id,
            source_path=source_path,
            content_digest=content_digest,
            size_bytes=size_bytes,
            media_type=media_type,
        )
        if self._session.bind.dialect.name == "sqlite":
            self._session.add(raw_file)
            self._session.flush()
            return raw_file.id
        try:
            with self._session.begin_nested():
                self._session.add(raw_file)
                self._session.flush()
            return raw_file.id
        except IntegrityError:
            existing = self._session.scalar(select(RawFileModel).where(
                RawFileModel.workspace_id == self._workspace_id,
                RawFileModel.batch_id == batch_id,
                RawFileModel.content_digest == content_digest,
            ))
            if existing is None:
                raise
            return existing.id

    def add_raw_records(
        self, *, batch_id: str, raw_file_id: str | None,
        source_type: str, records: list[dict],
    ) -> list[str]:
        self._batch(batch_id)
        identities = [str(item["source_identity"]) for item in records]
        ids_by_identity: dict[str, str] = {}
        for start in range(0, len(identities), 500):
            chunk = identities[start:start + 500]
            existing_rows = self._session.scalars(select(RawRecordModel).where(
                RawRecordModel.workspace_id == self._workspace_id,
                RawRecordModel.source_type == source_type,
                RawRecordModel.source_identity.in_(chunk),
            ))
            ids_by_identity.update({row.source_identity: row.id for row in existing_rows})

        first_by_identity = {
            str(item["source_identity"]): item for item in reversed(records)
        }
        new_rows = [RawRecordModel(
                workspace_id=self._workspace_id,
                batch_id=batch_id,
                raw_file_id=raw_file_id,
                source_type=source_type,
                source_identity=identity,
                source_line=item.get("source_line"),
                payload=_json_safe(item.get("payload", {})),
            ) for identity, item in sorted(first_by_identity.items())
            if identity not in ids_by_identity]
        if not new_rows:
            return [ids_by_identity[identity] for identity in identities]

        if self._session.bind.dialect.name == "sqlite":
            self._session.add_all(new_rows)
            self._session.flush()
            ids_by_identity.update({row.source_identity: row.id for row in new_rows})
            return [ids_by_identity[identity] for identity in identities]

        try:
            with self._session.begin_nested():
                self._session.add_all(new_rows)
                self._session.flush()
            ids_by_identity.update({row.source_identity: row.id for row in new_rows})
        except IntegrityError:
            raced_identities = [row.source_identity for row in new_rows]
            raced_rows = self._session.scalars(select(RawRecordModel).where(
                RawRecordModel.workspace_id == self._workspace_id,
                RawRecordModel.source_type == source_type,
                RawRecordModel.source_identity.in_(raced_identities),
            ))
            ids_by_identity.update({row.source_identity: row.id for row in raced_rows})
            for row in new_rows:
                if row.source_identity in ids_by_identity:
                    continue
                replacement = RawRecordModel(
                    workspace_id=row.workspace_id, batch_id=row.batch_id,
                    raw_file_id=row.raw_file_id, source_type=row.source_type,
                    source_identity=row.source_identity, source_line=row.source_line,
                    payload=row.payload,
                )
                try:
                    with self._session.begin_nested():
                        self._session.add(replacement)
                        self._session.flush()
                    ids_by_identity[replacement.source_identity] = replacement.id
                except IntegrityError:
                    existing_id = self._session.scalar(select(RawRecordModel.id).where(
                        RawRecordModel.workspace_id == self._workspace_id,
                        RawRecordModel.source_type == source_type,
                        RawRecordModel.source_identity == replacement.source_identity,
                    ))
                    if existing_id is None:
                        raise
                    ids_by_identity[replacement.source_identity] = existing_id
        return [ids_by_identity[identity] for identity in identities]

    def list_raw_records(self, batch_id: str) -> list[dict]:
        rows = self._session.scalars(
            select(RawRecordModel).where(
                RawRecordModel.workspace_id == self._workspace_id,
                RawRecordModel.batch_id == batch_id,
            ).order_by(RawRecordModel.source_line, RawRecordModel.id)
        )
        return [{
            "id": row.id,
            "source_identity": row.source_identity,
            "source_line": row.source_line,
            "payload": row.payload,
        } for row in rows]

    def formal_fact_targets(
        self, raw_record_ids: list[str],
    ) -> dict[str, tuple[str, str]]:
        found: dict[str, tuple[str, str]] = {}
        ordered_ids = sorted(set(raw_record_ids))
        for start in range(0, len(ordered_ids), 500):
            chunk = ordered_ids[start:start + 500]
            if not chunk:
                continue
            self._session.scalars(
                select(RawRecordModel.id).where(
                    RawRecordModel.workspace_id == self._workspace_id,
                    RawRecordModel.id.in_(chunk),
                ).order_by(RawRecordModel.id).with_for_update()
            ).all()
            cash_rows = self._session.execute(
                select(
                    CashTransactionModel.raw_record_id,
                    AccountModel.name,
                    CashTransactionModel.currency,
                ).join(AccountModel, (
                    AccountModel.workspace_id == CashTransactionModel.workspace_id
                ) & (AccountModel.id == CashTransactionModel.account_id)).where(
                    CashTransactionModel.workspace_id == self._workspace_id,
                    CashTransactionModel.raw_record_id.in_(chunk),
                    CashTransactionModel.deleted_at.is_(None),
                )
            )
            investment_rows = self._session.execute(
                select(
                    InvestmentEventModel.raw_record_id,
                    AccountModel.name,
                    InvestmentEventModel.currency,
                ).join(AccountModel, (
                    AccountModel.workspace_id == InvestmentEventModel.workspace_id
                ) & (AccountModel.id == InvestmentEventModel.account_id)).where(
                    InvestmentEventModel.workspace_id == self._workspace_id,
                    InvestmentEventModel.raw_record_id.in_(chunk),
                )
            )
            found.update({raw_id: (name, currency) for raw_id, name, currency in cash_rows})
            found.update({raw_id: (name, currency) for raw_id, name, currency in investment_rows})
        return found

    def batch_target_accounts(self, batch_id: str) -> set[tuple[str, str]]:
        batch = self._batch(batch_id)
        if batch.target_account_id is None:
            # Multi-account batch: derive targets from formal facts linked to batch raw records.
            cash_rows = self._session.execute(
                select(AccountModel.name, CashTransactionModel.currency)
                .join(CashTransactionModel, (
                    CashTransactionModel.workspace_id == AccountModel.workspace_id
                ) & (CashTransactionModel.account_id == AccountModel.id))
                .join(RawRecordModel, (
                    RawRecordModel.workspace_id == CashTransactionModel.workspace_id
                ) & (RawRecordModel.id == CashTransactionModel.raw_record_id))
                .where(
                    RawRecordModel.workspace_id == self._workspace_id,
                    RawRecordModel.batch_id == batch_id,
                )
            )
            inv_rows = self._session.execute(
                select(AccountModel.name, InvestmentEventModel.currency)
                .join(InvestmentEventModel, (
                    InvestmentEventModel.workspace_id == AccountModel.workspace_id
                ) & (InvestmentEventModel.account_id == AccountModel.id))
                .join(RawRecordModel, (
                    RawRecordModel.workspace_id == InvestmentEventModel.workspace_id
                ) & (RawRecordModel.id == InvestmentEventModel.raw_record_id))
                .where(
                    RawRecordModel.workspace_id == self._workspace_id,
                    RawRecordModel.batch_id == batch_id,
                )
            )
            return {(name, currency) for name, currency in cash_rows} | {
                (name, currency) for name, currency in inv_rows
            }
        target = self._session.execute(
            select(AccountModel.name)
            .where(
                AccountModel.workspace_id == self._workspace_id,
                AccountModel.id == batch.target_account_id,
            )
        ).one_or_none()
        if target is None:
            return set()
        currencies = self._session.scalars(select(CashTransactionModel.currency).where(
            CashTransactionModel.workspace_id == self._workspace_id,
            CashTransactionModel.account_id == batch.target_account_id,
        )).all()
        return {(target.name, currency) for currency in currencies}

    def replace_raw_record(self, record_id: str, payload: dict) -> None:
        raise ValueError("raw records are immutable")

    def append_revision(
        self, *, cash_transaction_id: str | None = None,
        investment_event_id: str | None = None, before: dict, after: dict,
        actor_type: str, reason: str,
    ) -> str:
        if (cash_transaction_id is None) == (investment_event_id is None):
            raise ValueError("revision requires exactly one formal fact target")
        if cash_transaction_id is not None:
            target = self._session.scalar(select(CashTransactionModel.id).where(
                CashTransactionModel.workspace_id == self._workspace_id,
                CashTransactionModel.id == cash_transaction_id,
            ))
        else:
            target = self._session.scalar(select(InvestmentEventModel.id).where(
                InvestmentEventModel.workspace_id == self._workspace_id,
                InvestmentEventModel.id == investment_event_id,
            ))
        if target is None:
            raise ValueError("revision target not found in workspace")
        revision = RecordRevisionModel(
            workspace_id=self._workspace_id,
            cash_transaction_id=cash_transaction_id,
            investment_event_id=investment_event_id,
            before=_json_safe(before),
            after=_json_safe(after),
            actor_type=actor_type,
            reason=reason,
        )
        self._session.add(revision)
        self._session.flush()
        return revision.id

    def list_revisions(
        self, *, cash_transaction_id: str | None = None,
        investment_event_id: str | None = None,
    ) -> list[dict]:
        if (cash_transaction_id is None) == (investment_event_id is None):
            raise ValueError("revision query requires exactly one formal fact target")
        statement = select(RecordRevisionModel).where(
            RecordRevisionModel.workspace_id == self._workspace_id
        )
        if cash_transaction_id is not None:
            statement = statement.where(RecordRevisionModel.cash_transaction_id == cash_transaction_id)
        else:
            statement = statement.where(RecordRevisionModel.investment_event_id == investment_event_id)
        rows = self._session.scalars(statement.order_by(
            RecordRevisionModel.created_at, RecordRevisionModel.id
        ))
        return [{
            "id": row.id,
            "before": row.before,
            "after": row.after,
            "actor_type": row.actor_type,
            "reason": row.reason,
        } for row in rows]

    def _batch(self, batch_id: str) -> ImportBatchModel:
        batch = self._session.scalar(select(ImportBatchModel).where(
            ImportBatchModel.workspace_id == self._workspace_id,
            ImportBatchModel.id == batch_id,
        ))
        if batch is None:
            raise ValueError(f"import batch not found: {batch_id}")
        return batch

    @staticmethod
    def _batch_dict(batch: ImportBatchModel) -> dict:
        return {
            "id": batch.id,
            "source_kind": batch.source_kind,
            "source_digest": batch.source_digest,
            "source_ref": batch.source_ref,
            "status": batch.status,
        }
