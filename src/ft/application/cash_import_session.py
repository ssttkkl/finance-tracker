"""Application orchestration for non-persistent cash-import sessions."""
from __future__ import annotations

import hashlib
import json
from typing import Any

from ft.application.cash_import_staging import (
    ImportSessionFile,
    ImportSessionNotFound,
    ImportSessionPasswordRequired,
    ImportStagingStore,
)


class CashImportSessionService:
    def __init__(self, cash_import, staging_store: ImportStagingStore, *, workspace_id: str, user_id: str):
        self._cash_import = cash_import
        self._staging = staging_store
        self._workspace_id = str(workspace_id)
        self._user_id = str(user_id)

    @staticmethod
    def _digest(content: bytes) -> str:
        return hashlib.sha256(content).hexdigest()

    @staticmethod
    def _batch_digest(files: list[dict]) -> str:
        canonical = [
            {
                "index": index,
                "filename": str(item.get("filename") or "statement"),
                "digest": str(item.get("digest") or ""),
                "size": int(item.get("size") or len(item.get("content") or b"")),
            }
            for index, item in enumerate(files)
        ]
        return hashlib.sha256(json.dumps(
            canonical, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
        ).encode("utf-8")).hexdigest()

    @staticmethod
    def _mapping_digest(mapping: list[dict] | None) -> str:
        """Return a stable digest for the preview's account choices.

        A cached relation plan is valid only for the mapped virtual facts used
        to create it.  The browser submits the mapping again at confirmation,
        but that copy is merely an assertion: the session owns the preview
        mapping and rejects a different set of choices before any write.
        """
        if mapping is None:
            canonical: object = None
        elif not isinstance(mapping, list):
            canonical = {"invalid": type(mapping).__name__}
        else:
            entries: list[dict[str, object]] = []
            for item in mapping:
                if not isinstance(item, dict):
                    entries.append({"invalid": type(item).__name__})
                    continue
                new_account = item.get("new_account")
                if isinstance(new_account, dict):
                    normalized_new_account: object = {
                        "draft_id": str(new_account.get("draft_id") or "").strip(),
                        "name": str(new_account.get("name") or "").strip(),
                        "type": str(new_account.get("type") or "").strip(),
                        "currencies": sorted({
                            str(value).upper()
                            for value in (new_account.get("currencies") or ())
                            if value not in (None, "")
                        }),
                    }
                elif new_account is None:
                    normalized_new_account = None
                else:
                    normalized_new_account = {"invalid": type(new_account).__name__}
                entries.append({
                    "group_id": str(item.get("group_id") or ""),
                    "account_id": (
                        None if item.get("account_id") in (None, "")
                        else str(item.get("account_id"))
                    ),
                    "mapping_revision": (
                        None if item.get("mapping_revision") is None
                        else str(item.get("mapping_revision"))
                    ),
                    "new_account": normalized_new_account,
                    "component_allocations": item.get("component_allocations"),
                    "allocations": item.get("allocations"),
                })
            canonical = sorted(
                entries,
                key=lambda item: (
                    str(item.get("group_id") or ""),
                    json.dumps(item, ensure_ascii=False, sort_keys=True, separators=(",", ":")),
                ),
            )
        encoded = json.dumps(
            canonical,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        return hashlib.sha256(encoded.encode("utf-8")).hexdigest()

    def _session_content(self, token: str) -> tuple[Any, bytes]:
        session = self._staging.get(token, workspace_id=self._workspace_id, user_id=self._user_id)
        content = self._staging.read_bytes(token, "source", workspace_id=self._workspace_id, user_id=self._user_id)
        if self._digest(content) != session.digest:
            raise ValueError("import_session_source_changed")
        return session, content

    def _session_files(self, token: str) -> tuple[Any, list[dict]]:
        session = self._staging.get(token, workspace_id=self._workspace_id, user_id=self._user_id)
        if not session.files:
            raise ValueError("import_session_not_batch")
        files: list[dict] = []
        for item in session.files:
            content = self._staging.read_bytes(
                token,
                f"source-{item.index}",
                workspace_id=self._workspace_id,
                user_id=self._user_id,
            )
            if self._digest(content) != item.digest:
                raise ValueError("import_session_source_changed")
            files.append({
                "index": item.index,
                "filename": item.filename,
                "digest": item.digest,
                "batch_digest": session.batch_digest,
                "content": content,
                "source": item.source,
                "channel": item.channel,
            })
        if self._batch_digest(files) != session.batch_digest:
            raise ValueError("import_session_source_changed")
        return session, files

    @staticmethod
    def _normalized_passwords(passwords: dict[str, str] | None) -> dict[str, str]:
        if passwords is None:
            return {}
        if not isinstance(passwords, dict):
            raise ValueError("import_passwords_invalid")
        normalized: dict[str, str] = {}
        for key, value in passwords.items():
            text = str(value or "")
            if text:
                normalized[str(key)] = text
        return normalized

    def _update_batch_scan(self, token: str, result: dict) -> dict:
        session = self._staging.get(token, workspace_id=self._workspace_id, user_id=self._user_id)
        returned_files = result.get("files") if isinstance(result.get("files"), list) else []
        by_index = {
            int(item.get("index")): item
            for item in returned_files
            if isinstance(item, dict) and str(item.get("index", "")).isdigit()
        }
        updated_files: list[ImportSessionFile] = []
        for item in session.files:
            status = by_index.get(item.index, {})
            updated_files.append(ImportSessionFile(
                index=item.index,
                filename=item.filename,
                digest=item.digest,
                size=item.size,
                source=item.source,
                channel=(str(status["channel"]) if status.get("channel") not in (None, "") else item.channel),
                status=str(status.get("status") or "error"),
                error_code=(str(status["error_code"]) if status.get("error_code") not in (None, "") else None),
            ))
        channels = sorted({item.channel for item in updated_files if item.channel})
        channel = channels[0] if len(channels) == 1 else ("mixed" if channels else None)
        self._staging.update(
            token,
            workspace_id=self._workspace_id,
            user_id=self._user_id,
            channel=channel,
            files=tuple(updated_files),
            batch_digest=session.batch_digest,
        )
        enriched = dict(result)
        enriched["files"] = [
            {
                "index": item.index,
                "name": item.filename,
                "filename": item.filename,
                "digest": item.digest,
                "size": item.size,
                "channel": item.channel,
                "status": item.status,
                **({"error_code": item.error_code} if item.error_code else {}),
            }
            for item in updated_files
        ]
        enriched["ready"] = bool(enriched.get("ready")) and all(item.status == "ready" for item in updated_files)
        enriched["channels"] = channels
        enriched["batch_digest"] = session.batch_digest
        enriched["channel"] = channel
        if len(channels) == 1:
            enriched["channel_label"] = enriched.get("channel_label") or channels[0]
        elif channels:
            enriched["channel_label"] = "多渠道"
        enriched["import_token"] = token
        return enriched

    def scan_import_batch(
        self,
        files: list[dict],
        *,
        currency: str | None = None,
        passwords: dict[str, str] | None = None,
    ) -> dict:
        if not isinstance(files, list) or not files:
            raise ValueError("import_session_file_count_invalid")
        normalized_files = []
        for item in files:
            if not isinstance(item, dict) or not item.get("content") is not None:
                raise ValueError("import_session_file_invalid")
            content = bytes(item.get("content") or b"")
            normalized_files.append({
                "filename": str(item.get("filename") or "statement"),
                "content": content,
                "digest": self._digest(content),
                "size": len(content),
                "source": str(item.get("source") or ""),
            })
        batch_digest = self._batch_digest(normalized_files)
        session = self._staging.create_batch(
            workspace_id=self._workspace_id,
            user_id=self._user_id,
            files=normalized_files,
            batch_digest=batch_digest,
            currency=currency,
        )
        try:
            result = self._cash_import.scan_import_batch(
                normalized_files,
                currency=currency,
                passwords=self._normalized_passwords(passwords),
            )
            result = self._update_batch_scan(session.token, dict(result))
            self._staging.write_json(
                session.token, "scan", result,
                workspace_id=self._workspace_id, user_id=self._user_id,
            )
            return result
        except Exception:
            self._staging.complete(session.token, workspace_id=self._workspace_id, user_id=self._user_id)
            raise

    def scan_import_batch_session(
        self,
        token: str,
        *,
        passwords: dict[str, str] | None = None,
    ) -> dict:
        session, files = self._session_files(token)
        result = self._cash_import.scan_import_batch(
            files,
            currency=session.currency,
            passwords=self._normalized_passwords(passwords),
        )
        result = self._update_batch_scan(token, dict(result))
        self._staging.write_json(
            token, "scan", result,
            workspace_id=self._workspace_id, user_id=self._user_id,
        )
        return result

    def _require_ready_batch(self, token: str) -> tuple[Any, list[dict]]:
        session, files = self._session_files(token)
        try:
            scan = self._staging.read_json(
                token, "scan", workspace_id=self._workspace_id, user_id=self._user_id,
            )
        except ImportSessionNotFound as exc:
            raise ValueError("import_files_not_ready") from exc
        if not isinstance(scan, dict) or scan.get("ready") is not True:
            raise ValueError("import_files_not_ready")
        if any(item.status != "ready" for item in session.files):
            raise ValueError("import_files_not_ready")
        return session, files

    def preview_import_batch_session(
        self,
        token: str,
        *,
        currency: str | None = None,
        passwords: dict[str, str] | None = None,
        mapping: list[dict] | None = None,
    ) -> dict:
        session, files = self._require_ready_batch(token)
        result = dict(self._cash_import.preview_import_batch(
            files,
            currency=currency or session.currency,
            passwords=self._normalized_passwords(passwords),
            mapping=mapping,
            cache_relation_plan=True,
        ))
        if result.get("batch_digest") != session.batch_digest:
            raise ValueError("import_preview_stale")
        relation_plan = result.pop("_relation_plan", None)
        if relation_plan is not None:
            if not isinstance(relation_plan, dict):
                raise ValueError("import_relation_reconfirmation_required")
            if str(relation_plan.get("plan_digest") or "") != str(result.get("relation_digest") or ""):
                raise ValueError("import_relation_reconfirmation_required")
        elif result.get("relation_digest"):
            raise ValueError("import_relation_reconfirmation_required")
        result["import_token"] = token
        staged_preview = dict(result)
        staged_preview["_mapping_digest"] = self._mapping_digest(mapping)
        if relation_plan is not None:
            staged_preview["_relation_plan"] = relation_plan
        self._staging.write_json(
            token, "preview", staged_preview,
            workspace_id=self._workspace_id, user_id=self._user_id,
        )
        return result

    def commit_import_batch_session(
        self,
        token: str,
        *,
        currency: str | None = None,
        passwords: dict[str, str] | None = None,
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
            existing = lookup(idempotency_key, idempotency_scope=idempotency_scope, user_id=self._user_id)
            if existing is not None:
                return existing
        session, files = self._require_ready_batch(token)
        try:
            staged_preview = self._staging.read_json(
                token, "preview", workspace_id=self._workspace_id, user_id=self._user_id,
            )
        except ImportSessionNotFound as exc:
            raise ValueError("import_relation_reconfirmation_required") from exc
        if not isinstance(staged_preview, dict):
            raise ValueError("import_relation_reconfirmation_required")
        cached_relation_plan = staged_preview.get("_relation_plan")
        staged_relation_digest = str(staged_preview.get("relation_digest") or "")
        if staged_preview.get("_mapping_digest") != self._mapping_digest(mapping):
            raise ValueError("import_relation_reconfirmation_required")
        if cached_relation_plan is not None:
            if not isinstance(cached_relation_plan, dict) or str(cached_relation_plan.get("plan_digest") or "") != str(preview_relation_digest or ""):
                raise ValueError("import_relation_reconfirmation_required")
        elif staged_relation_digest or preview_relation_digest:
            raise ValueError("import_relation_reconfirmation_required")
        kwargs = {
            "currency": currency or session.currency,
            "passwords": self._normalized_passwords(passwords),
            "preview_digest": preview_digest or session.batch_digest,
            "preview_relation_digest": preview_relation_digest,
            "preview_channel": preview_channel or session.channel,
            "relation_decisions": relation_decisions,
            "mapping": mapping,
            "cached_relation_plan": cached_relation_plan,
            "idempotency_key": idempotency_key,
            "idempotency_scope": idempotency_scope,
            "idempotency_user_id": self._user_id,
        }
        result = self._cash_import.commit_import_batch(files, **kwargs)
        self._staging.write_json(token, "result", result, workspace_id=self._workspace_id, user_id=self._user_id)
        self._staging.complete(token, workspace_id=self._workspace_id, user_id=self._user_id)
        return result

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
        result = dict(self._cash_import.preview_import(
            content,
            source=source or session.source,
            currency=currency or session.currency,
            filename=session.filename,
            password=password,
            mapping=mapping,
            cache_relation_plan=True,
        ))
        if (
            result.get("file", {}).get("digest") != session.digest
            or (session.channel and result.get("channel") != session.channel)
        ):
            raise ValueError("import_preview_stale")
        relation_plan = result.pop("_relation_plan", None)
        if relation_plan is not None:
            if not isinstance(relation_plan, dict):
                raise ValueError("import_relation_reconfirmation_required")
            cached_digest = str(relation_plan.get("plan_digest") or "")
            if not cached_digest or cached_digest != str(result.get("relation_digest") or ""):
                raise ValueError("import_relation_reconfirmation_required")
        elif result.get("relation_digest"):
            # A relation digest without its server-owned plan cannot safely be
            # confirmed: falling back would invoke the matcher a second time.
            raise ValueError("import_relation_reconfirmation_required")
        result["import_token"] = token
        staged_preview = dict(result)
        staged_preview["_mapping_digest"] = self._mapping_digest(mapping)
        if relation_plan is not None:
            staged_preview["_relation_plan"] = relation_plan
        self._staging.write_json(
            token, "preview", staged_preview, workspace_id=self._workspace_id, user_id=self._user_id,
        )
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
        try:
            staged_preview = self._staging.read_json(
                token, "preview", workspace_id=self._workspace_id, user_id=self._user_id,
            )
        except ImportSessionNotFound as exc:
            # A Web confirmation is only valid after this session has created
            # a server-owned preview plan.  Falling back to the legacy import
            # path here would run the matcher a second time.
            raise ValueError("import_relation_reconfirmation_required") from exc
        if not isinstance(staged_preview, dict):
            raise ValueError("import_relation_reconfirmation_required")
        cached_relation_plan = (
            staged_preview.get("_relation_plan")
            if isinstance(staged_preview, dict)
            else None
        )
        staged_relation_digest = str(staged_preview.get("relation_digest") or "")
        staged_mapping_digest = staged_preview.get("_mapping_digest")
        if (
            not isinstance(staged_mapping_digest, str)
            or staged_mapping_digest != self._mapping_digest(mapping)
        ):
            raise ValueError("import_relation_reconfirmation_required")
        if cached_relation_plan is not None:
            if not isinstance(cached_relation_plan, dict):
                raise ValueError("import_relation_reconfirmation_required")
            cached_digest = str(cached_relation_plan.get("plan_digest") or "")
            if not cached_digest or str(preview_relation_digest or "") != cached_digest:
                raise ValueError("import_relation_reconfirmation_required")
        elif staged_relation_digest or preview_relation_digest:
            raise ValueError("import_relation_reconfirmation_required")
        commit_kwargs = {
            "source": source or session.source,
            "currency": currency or session.currency,
            "filename": session.filename,
            "password": password,
            "preview_digest": preview_digest or session.digest,
            "preview_relation_digest": preview_relation_digest,
            "preview_channel": preview_channel or session.channel,
            "relation_decisions": relation_decisions,
            "mapping": mapping,
            "idempotency_key": idempotency_key,
            "idempotency_scope": idempotency_scope,
            "idempotency_user_id": self._user_id,
        }
        if cached_relation_plan is not None:
            commit_kwargs["cached_relation_plan"] = cached_relation_plan
        result = self._cash_import.commit_import(
            content,
            **commit_kwargs,
        )
        self._staging.write_json(token, "result", result, workspace_id=self._workspace_id, user_id=self._user_id)
        self._staging.complete(token, workspace_id=self._workspace_id, user_id=self._user_id)
        return result
