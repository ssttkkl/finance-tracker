"""Application orchestration for non-persistent cash-import sessions."""
from __future__ import annotations

import hashlib
from typing import Any

from ft.application.cash_import_staging import ImportSessionPasswordRequired, ImportStagingStore


class CashImportSessionService:
    def __init__(self, cash_import, staging_store: ImportStagingStore, *, workspace_id: str, user_id: str):
        self._cash_import = cash_import
        self._staging = staging_store
        self._workspace_id = str(workspace_id)
        self._user_id = str(user_id)

    @staticmethod
    def _digest(content: bytes) -> str:
        return hashlib.sha256(content).hexdigest()

    def _session_content(self, token: str) -> tuple[Any, bytes]:
        session = self._staging.get(token, workspace_id=self._workspace_id, user_id=self._user_id)
        content = self._staging.read_bytes(token, "source", workspace_id=self._workspace_id, user_id=self._user_id)
        if self._digest(content) != session.digest:
            raise ValueError("import_session_source_changed")
        return session, content

    def scan_import(self, content: bytes, *, filename: str, currency: str | None = None, password: str | None = None) -> dict:
        session = self._staging.create(
            workspace_id=self._workspace_id,
            user_id=self._user_id,
            filename=filename,
            digest=self._digest(content),
            content=content,
            currency=currency,
        )
        try:
            result = self._cash_import.scan_import(
                content, filename=filename, currency=currency, password=password,
            )
        except Exception as exc:
            # Password-required files are deliberately retained for a retry;
            # ordinary parse failures must not leave a usable staged source.
            from ft.importers.pdf_tools import PDFPasswordRequiredError

            if isinstance(exc, PDFPasswordRequiredError):
                if exc.__class__.__name__ == "PDFPasswordRequiredError":
                    raise ImportSessionPasswordRequired(session.token) from exc
                raise
            self._staging.complete(session.token, workspace_id=self._workspace_id, user_id=self._user_id)
            raise
        self._staging.update(
            session.token,
            workspace_id=self._workspace_id,
            user_id=self._user_id,
            channel=result.get("channel"),
        )
        result = dict(result)
        result["import_token"] = session.token
        self._staging.write_json(
            session.token, "scan", result, workspace_id=self._workspace_id, user_id=self._user_id,
        )
        return result

    def scan_import_session(self, token: str, *, password: str | None = None) -> dict:
        session, content = self._session_content(token)
        result = self._cash_import.scan_import(
            content, filename=session.filename, currency=session.currency, password=password,
        )
        self._staging.update(
            token,
            workspace_id=self._workspace_id,
            user_id=self._user_id,
            channel=result.get("channel"),
        )
        result = dict(result)
        result["import_token"] = token
        self._staging.write_json(token, "scan", result, workspace_id=self._workspace_id, user_id=self._user_id)
        return result

    def preview_import_session(
        self,
        token: str,
        *,
        source: str = "",
        currency: str | None = None,
        password: str | None = None,
        mapping: list[dict] | None = None,
    ) -> dict:
        session, content = self._session_content(token)
        result = self._cash_import.preview_import(
            content,
            source=source or session.source,
            currency=currency or session.currency,
            filename=session.filename,
            password=password,
            mapping=mapping,
        )
        if (
            result.get("file", {}).get("digest") != session.digest
            or (session.channel and result.get("channel") != session.channel)
        ):
            raise ValueError("import_preview_stale")
        result = dict(result)
        result["import_token"] = token
        self._staging.write_json(token, "preview", result, workspace_id=self._workspace_id, user_id=self._user_id)
        return result

    def commit_import_session(
        self,
        token: str,
        *,
        source: str = "",
        currency: str | None = None,
        password: str | None = None,
        preview_digest: str | None = None,
        preview_relation_digest: str | None = None,
        preview_channel: str | None = None,
        relation_decisions: list[dict] | None = None,
        mapping: list[dict] | None = None,
        idempotency_key: str,
    ) -> dict:
        if not str(idempotency_key or "").strip():
            raise ValueError("import_idempotency_key_required")
        idempotency_scope = self._digest(str(token).encode("utf-8"))
        lookup = getattr(self._cash_import, "get_import_commit_result", None)
        if lookup is not None:
            existing = lookup(
                idempotency_key,
                idempotency_scope=idempotency_scope,
                user_id=self._user_id,
            )
            if existing is not None:
                return existing
        session, content = self._session_content(token)
        if session.channel and preview_channel and preview_channel != session.channel:
            raise ValueError("import_preview_stale")
        result = self._cash_import.commit_import(
            content,
            source=source or session.source,
            currency=currency or session.currency,
            filename=session.filename,
            password=password,
            preview_digest=preview_digest or session.digest,
            preview_relation_digest=preview_relation_digest,
            preview_channel=preview_channel or session.channel,
            relation_decisions=relation_decisions,
            mapping=mapping,
            idempotency_key=idempotency_key,
            idempotency_scope=idempotency_scope,
            idempotency_user_id=self._user_id,
        )
        self._staging.write_json(token, "result", result, workspace_id=self._workspace_id, user_id=self._user_id)
        self._staging.complete(token, workspace_id=self._workspace_id, user_id=self._user_id)
        return result
