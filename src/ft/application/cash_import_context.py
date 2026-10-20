"""现金导入解析上下文：逐文件容错、权限重验与工作区内发布。"""
from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass, field, replace
from hashlib import sha256
import re
from uuid import uuid4

from sqlalchemy import select

from ft.adapters.cash_statement_document import StatementDocumentError, read_platform_document
from ft.adapters.cash_statement_rows import ParsedCashDocument, normalize_platform_document
from ft.adapters.relational.cash_mutations import CashMutationConflict, CashMutationRepository
from ft.adapters.relational.models import WorkspaceMembershipModel
from ft.application.cash_import_staging import ImportSessionNotFound
from ft.application.parsed_import_staging import (
    ImportSessionClosed, ImportSessionRevisionConflict, ParsedImportDataError,
    ParsedImportFile, ParsedImportSession,
)


_MAX_UPLOAD_BYTES = 100 * 1024 * 1024
_FILE_ERRORS = frozenset({"statement_source_unsupported", "statement_header_not_found", "statement_header_invalid",
    "statement_rows_too_large", "statement_input_invalid", "statement_unreadable", "statement_encoding_unsupported"})


class CashImportPermissionDenied(ValueError):
    def __init__(self):
        super().__init__("import_not_allowed")


class CashImportPasswordRequired(ValueError):
    def __init__(self, *, incorrect=False):
        self.code = "password_incorrect" if incorrect else "password_required"
        super().__init__(self.code)


@dataclass(frozen=True)
class CashImportUpload:
    client_file_ref: str
    filename: str
    content: bytes = field(repr=False)
    password: str | None = field(default=None, repr=False)
    source_hint: str | None = None


@dataclass(frozen=True)
class CashImportContext:
    session: ParsedImportSession | None
    receipt: dict | None = None


def _parse_platform(upload):
    return normalize_platform_document(read_platform_document(upload.content, source=upload.source_hint))


class CashImportContextService:
    def __init__(self, uow, staging_store, *, user_id, parser=_parse_platform):
        self._uow, self._staging, self._user_id, self._parser = uow, staging_store, user_id, parser
        self._workspace_id = uow.workspace_id

    def _authorize(self, uow, *, write=True):
        session = uow._state().session
        query = select(WorkspaceMembershipModel).where(WorkspaceMembershipModel.workspace_id == self._workspace_id,
            WorkspaceMembershipModel.user_id == self._user_id)
        if write and session.bind.dialect.name == "postgresql":
            query = query.with_for_update()
        member = session.scalar(query)
        if member is None or write and member.role not in {"admin", "editor"}:
            raise CashImportPermissionDenied

    def _receipt(self, uow, session_id):
        if not isinstance(session_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", session_id):
            raise ImportSessionNotFound
        return CashMutationRepository(uow._state().session, self._workspace_id).for_context(self._user_id,
            session_digest=sha256(session_id.encode()).hexdigest())

    def _current(self, uow, session_id, expected_revision):
        self._authorize(uow)
        receipt = self._receipt(uow, session_id)
        if receipt:
            raise CashMutationConflict("session_committed", receipt["receipt_id"])
        current = self._staging.get(session_id, workspace_id=self._workspace_id, user_id=self._user_id)
        if type(expected_revision) is not int or expected_revision != current.revision:
            raise ImportSessionRevisionConflict
        if current.cancelled:
            raise ImportSessionClosed
        return current

    @staticmethod
    def _uploads(uploads):
        if not isinstance(uploads, tuple) or not 1 <= len(uploads) <= 20:
            raise ValueError("import_files_invalid")
        refs = set()
        for upload in uploads:
            if (not isinstance(upload, CashImportUpload) or not isinstance(upload.client_file_ref, str)
                or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", upload.client_file_ref) or upload.client_file_ref in refs
                or not isinstance(upload.filename, str) or upload.source_hint is not None and not isinstance(upload.source_hint, str)
                or upload.password is not None and not isinstance(upload.password, str)):
                raise ValueError("import_files_invalid")
            refs.add(upload.client_file_ref)

    def _parse(self, upload):
        raw = upload.content if isinstance(upload.content, bytes) else b""
        filename = (upload.filename.replace("\\", "/").rsplit("/", 1)[-1] or "账单").encode("utf-8", errors="replace").decode()[:255]
        hint = upload.source_hint if upload.source_hint and re.fullmatch(r"[a-z][a-z0-9_-]{0,63}", upload.source_hint) else None
        file = ParsedImportFile("f_" + uuid4().hex, upload.client_file_ref, filename, sha256(raw).hexdigest(),
            len(raw), hint, "failed", error_code="statement_input_invalid")
        if not raw or len(raw) > _MAX_UPLOAD_BYTES:
            return file
        try:
            document = self._parser(upload)
            if not isinstance(document, ParsedCashDocument):
                raise ValueError("unexpected_document_type")
            parsed = replace(file, source_type=document.source_type, status="parsed", document=document, error_code=None)
            self._staging.validate_file(parsed)
            return parsed
        except CashImportPasswordRequired as error:
            return replace(file, status="needs_password", error_code=error.code)
        except StatementDocumentError as error:
            return replace(file, error_code=error.code if error.code in _FILE_ERRORS else "statement_unreadable")
        except ParsedImportDataError as error:
            return replace(file, error_code="statement_rows_too_large" if str(error) == "import_session_draft_too_large" else "statement_unreadable")
        except Exception:
            return replace(file, error_code="statement_unreadable")

    @staticmethod
    def _invalidate_input(current, obsolete_refs):
        inputs = deepcopy(current.preview_input)
        if isinstance(inputs.get("row_choices"), list):
            inputs["row_choices"] = [choice for choice in inputs["row_choices"] if isinstance(choice, dict)
                and not any(str(choice.get("row_ref", "")).startswith(ref + ":") for ref in obsolete_refs)]
        if "previous_plan_id" in inputs:
            inputs["previous_plan_id"] = None
        if "expected_session_revision" in inputs:
            inputs["expected_session_revision"] = current.revision + 1
        # 其余选择由新计划按当前候选重验；不在文件操作中擅自替换对侧。
        return inputs

    @staticmethod
    def _fit(write, files, new_refs):
        files = list(files)
        while True:
            try:
                return write(tuple(files))
            except ParsedImportDataError as error:
                if str(error) != "import_session_draft_too_large":
                    raise
                index = next((index for index in reversed(range(len(files))) if files[index].file_ref in new_refs and files[index].document is not None), None)
                if index is None:
                    raise
                files[index] = replace(files[index], status="failed", document=None, error_code="statement_rows_too_large")

    def create(self, uploads):
        self._uploads(uploads)
        with self._uow as uow:
            self._authorize(uow)
        files = tuple(self._parse(upload) for upload in uploads)
        with self._uow as uow:
            self._authorize(uow)
            result = self._fit(lambda items: self._staging.create(workspace_id=self._workspace_id, user_id=self._user_id, files=items),
                files, {file.file_ref for file in files})
            uow.commit()
            return result

    def get(self, session_id):
        with self._uow as uow:
            self._authorize(uow, write=False)
            receipt = self._receipt(uow, session_id)
            if receipt:
                return CashImportContext(None, receipt)
            return CashImportContext(self._staging.get(session_id, workspace_id=self._workspace_id, user_id=self._user_id))

    def add_files(self, session_id, uploads, *, expected_revision):
        self._uploads(uploads)
        with self._uow as uow:
            current = self._current(uow, session_id, expected_revision)
            if len(current.files) + len(uploads) > 20 or {file.client_file_ref for file in current.files} & {upload.client_file_ref for upload in uploads}:
                raise ValueError("import_files_invalid")
        added = tuple(self._parse(upload) for upload in uploads)
        with self._uow as uow:
            current = self._current(uow, session_id, expected_revision)
            result = self._fit(lambda files: self._staging.replace(session_id, workspace_id=self._workspace_id, user_id=self._user_id,
                expected_revision=expected_revision, files=files, preview_input=self._invalidate_input(current, ()), plan=None),
                (*current.files, *added), {file.file_ref for file in added})
            uow.commit()
            return result

    def replace_file(self, session_id, file_ref, upload, *, expected_revision):
        self._uploads((upload,))
        with self._uow as uow:
            current = self._current(uow, session_id, expected_revision)
            old = next((file for file in current.files if file.file_ref == file_ref), None)
            if old is None:
                raise ValueError("import_file_not_found")
        parsed = self._parse(replace(upload, client_file_ref=old.client_file_ref))
        with self._uow as uow:
            current = self._current(uow, session_id, expected_revision)
            result = self._fit(lambda files: self._staging.replace(session_id, workspace_id=self._workspace_id, user_id=self._user_id,
                expected_revision=expected_revision, files=files, preview_input=self._invalidate_input(current, (file_ref,)), plan=None),
                tuple(parsed if file.file_ref == file_ref else file for file in current.files), {parsed.file_ref})
            uow.commit()
            return result

    def remove_file(self, session_id, file_ref, *, expected_revision):
        with self._uow as uow:
            current = self._current(uow, session_id, expected_revision)
            if not any(file.file_ref == file_ref for file in current.files):
                raise ValueError("import_file_not_found")
            result = self._staging.replace(session_id, workspace_id=self._workspace_id, user_id=self._user_id,
                expected_revision=expected_revision, files=tuple(file for file in current.files if file.file_ref != file_ref),
                preview_input=self._invalidate_input(current, (file_ref,)), plan=None)
            uow.commit()
            return result

    def cancel(self, session_id):
        with self._uow as uow:
            self._authorize(uow)
            receipt = self._receipt(uow, session_id)
            if receipt:
                raise CashMutationConflict("session_committed", receipt["receipt_id"])
            current = self._staging.get(session_id, workspace_id=self._workspace_id, user_id=self._user_id)
            result = self._staging.cancel(session_id, workspace_id=self._workspace_id, user_id=self._user_id, expected_revision=current.revision)
            uow.commit()
            return result
