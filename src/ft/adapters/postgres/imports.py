"""Import provenance repository with immutable raw facts and revisions."""
from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import select

from .models import (
    ImportBatchModel,
    RawFileModel,
    RawRecordModel,
    RecordRevisionModel,
)
from .repositories import _json_safe


class PostgresImportRepository:
    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id

    def start_batch(self, *, source_kind: str, source_digest: str, source_ref: str) -> str:
        existing = self._session.scalar(select(ImportBatchModel).where(
            ImportBatchModel.workspace_id == self._workspace_id,
            ImportBatchModel.source_kind == source_kind,
            ImportBatchModel.source_digest == source_digest,
        ))
        if existing is not None:
            return existing.id
        batch = ImportBatchModel(
            workspace_id=self._workspace_id,
            source_kind=source_kind,
            source_digest=source_digest,
            source_ref=source_ref,
            status="pending",
        )
        self._session.add(batch)
        self._session.flush()
        return batch.id

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
        self._session.add(raw_file)
        self._session.flush()
        return raw_file.id

    def add_raw_records(
        self, *, batch_id: str, raw_file_id: str | None,
        source_type: str, records: list[dict],
    ) -> list[str]:
        self._batch(batch_id)
        ids = []
        for item in records:
            identity = str(item["source_identity"])
            existing = self._session.scalar(select(RawRecordModel).where(
                RawRecordModel.workspace_id == self._workspace_id,
                RawRecordModel.source_type == source_type,
                RawRecordModel.source_identity == identity,
            ))
            if existing is not None:
                ids.append(existing.id)
                continue
            record = RawRecordModel(
                workspace_id=self._workspace_id,
                batch_id=batch_id,
                raw_file_id=raw_file_id,
                source_type=source_type,
                source_identity=identity,
                source_line=item.get("source_line"),
                payload=_json_safe(item.get("payload", {})),
            )
            self._session.add(record)
            self._session.flush()
            ids.append(record.id)
        return ids

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

    def replace_raw_record(self, record_id: str, payload: dict) -> None:
        raise ValueError("raw records are immutable")

    def append_revision(
        self, *, entity_type: str, entity_id: str, before: dict, after: dict,
        actor_type: str, reason: str,
    ) -> str:
        revision = RecordRevisionModel(
            workspace_id=self._workspace_id,
            entity_type=entity_type,
            entity_id=entity_id,
            before=_json_safe(before),
            after=_json_safe(after),
            actor_type=actor_type,
            reason=reason,
        )
        self._session.add(revision)
        self._session.flush()
        return revision.id

    def list_revisions(self, entity_type: str, entity_id: str) -> list[dict]:
        rows = self._session.scalars(
            select(RecordRevisionModel).where(
                RecordRevisionModel.workspace_id == self._workspace_id,
                RecordRevisionModel.entity_type == entity_type,
                RecordRevisionModel.entity_id == entity_id,
            ).order_by(RecordRevisionModel.created_at, RecordRevisionModel.id)
        )
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
