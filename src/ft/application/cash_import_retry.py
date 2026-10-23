"""收据授权的失败正文、派生登记与跨子会话成功消费。"""
from copy import deepcopy
from dataclasses import replace
from hashlib import sha256

from sqlalchemy import select

from ft.adapters.relational.cash_mutations import CashMutationConflict, CashMutationRepository
from ft.adapters.relational.models import AccountModel
from ft.application.cash_import_context import CashImportContextService
from ft.application.cash_import_funding import scan_cash_funding
from ft.application.cash_import_plans import CashImportPlanError, _reference
from ft.application.cash_import_retry_sources import build_retry_proofs
from ft.application.cash_import_retry_validation import retry_data as _data
from ft.application.cash_import_errors import (ImportSessionExpired, ImportSessionNotFound,
    ImportSessionStorageUnavailable)
from ft.domain.cash_source_matching import _digest


RETRYABLE_ROW_CODES = frozenset({"row_save_failed", "source_evidence_save_failed", "record_changed", "account_changed"})


def _subset_files(files, references):
    result = []
    for file in files:
        if file.document is None:
            continue
        rows = tuple(row for row in file.document.rows if f"{file.file_ref}:r{row.position}" in references)
        if rows:
            result.append(replace(file, is_subset=True, document=replace(file.document, rows=rows,
                declared_count=None, observed_count=len(rows), coverage=replace(file.document.coverage, complete=False, evidence=""))))
    return tuple(result)


def _inputs(inputs, files):
    references = {f"{file.file_ref}:r{row.position}" for file in files if file.document for row in file.document.rows}
    groups = {group.source_account_ref for group in scan_cash_funding(files).groups}
    return {"account_selections": [deepcopy(item) for item in inputs.get("account_selections", []) if item["source_account_ref"] in groups],
        "row_choices": [deepcopy(item) for item in inputs.get("row_choices", []) if item["row_ref"] in references],
        "relation_choices": []}


def prepare_retry_context(uow, store, user_id, current, plan, sources, rows, validated):
    failed = {row["item_ref"] for row in rows if row["outcome"] == "skipped" and row.get("code") in RETRYABLE_ROW_CODES}
    if not failed:
        return {}
    old_units = current.context_data.get("units", {}) if current.workflow_kind == "retry" else {}
    roots = {reference: root for root, unit in old_units.items() for reference in unit["row_refs"]}
    operations = {operation["operation_ref"]: operation for operation in plan["operations"]}
    grouped = {}
    for result in rows:
        if result["item_ref"] in failed:
            operation = operations.get(result.get("operation_ref"))
            references = [ref for ref in operation["row_refs"] if ref in failed] if operation else [result["item_ref"]]
            source_ref = references[0]
            root = next((roots[ref] for ref in references if ref in roots), source_ref)
            grouped.setdefault(root, {"row_refs": references, "source_ref": source_ref,
                "operation_ref": result.get("operation_ref")})
    files = _subset_files(current.files, failed)
    if not files or any(unit["source_ref"] not in sources.rows for unit in grouped.values()):
        return {"retry_context_status": "reselect_file"}
    session = uow._state().session
    proofs = build_retry_proofs(session, uow.workspace_id, files, grouped, sources, validated)
    data = {"version": 1, "units": grouped, "source_proofs": proofs, "derived_context_ids": []}
    if current.workflow_kind == "retry":
        data.update({name: current.context_data[name] for name in ("root_context_id", "root_receipt_id")})
    capsule = store.create(workspace_id=uow.workspace_id, user_id=user_id, files=files,
        workflow_kind="retry_capsule", context_data=data, preview_input=_inputs(current.preview_input, files),
        created_at=current.created_at, expires_at=current.expires_at)
    if current.workflow_kind == "retry":
        CashImportRetryService(uow, store, user_id=user_id)._register(uow, data["root_context_id"], capsule.session_id)
    return {"retry_context_status": "available", "retry_context_id": capsule.session_id,
        "retry_context_expires_at": capsule.expires_at.isoformat()}


class CashImportRetryService(CashImportContextService):
    def _root(self, uow, current, *, receipt_id=None, data=None):
        data = _data(current.context_data) if data is None else data
        root_id = data.get("root_context_id", current.session_id)
        root_receipt_id = data.get("root_receipt_id", receipt_id)
        repository = CashMutationRepository(uow._state().session, self._workspace_id)
        root_receipt = repository.get(self._user_id, root_receipt_id) if root_receipt_id else None
        if root_receipt is None or root_receipt.get("retry_context_id") != root_id or root_receipt["operation_kind"] != "import":
            raise ImportSessionNotFound
        root = current if root_id == current.session_id else self._staging.get(root_id,
            workspace_id=self._workspace_id, user_id=self._user_id)
        if root.workflow_kind != "retry_capsule" or root.cancelled or root.created_at != current.created_at or root.expires_at != current.expires_at:
            raise ImportSessionNotFound
        if root is not current:
            _data(root.context_data)
        if current.session_id != root_id and current.session_id not in root.context_data["derived_context_ids"]:
            raise ImportSessionNotFound
        return root, root_receipt

    def _successful(self, uow, current, root, *, data=None):
        data = _data(current.context_data) if data is None else data
        references = {ref for unit in data["units"].values() for ref in unit["row_refs"]}
        references.update(ref for proof in data["source_proofs"]["groups"].values() for ref in proof["allowed_inputs"])
        return CashMutationRepository(uow._state().session, self._workspace_id).successful_import_rows(
            self._user_id, root.context_data["derived_context_ids"], references)

    def _prune(self, current, successful):
        consumed = {ref for unit in current.context_data["units"].values()
            if all(reference in successful for reference in unit["row_refs"]) for ref in unit["row_refs"]}
        if not consumed:
            return current
        files = []
        for file in current.files:
            if file.document is None:
                files.append(file)
                continue
            remaining = tuple(row for row in file.document.rows if f"{file.file_ref}:r{row.position}" not in consumed)
            if len(remaining) == len(file.document.rows):
                files.append(file)
            elif remaining:
                files.append(replace(file, is_subset=True, document=replace(file.document, rows=remaining,
                    observed_count=len(remaining), declared_count=None, coverage=replace(file.document.coverage, complete=False, evidence=""))))
        if tuple(files) == current.files:
            return current
        return self._staging.replace(current.session_id, workspace_id=self._workspace_id, user_id=self._user_id,
            expected_revision=current.revision, files=tuple(files), preview_input=_inputs(current.preview_input, tuple(files)),
            plan=None)

    def refresh(self, uow, current):
        if current.workflow_kind != "retry":
            raise ImportSessionNotFound
        if not all(isinstance(current.context_data.get(key), str) for key in (
            "derivation_key", "derivation_digest", "root_context_id", "root_receipt_id")):
            raise ImportSessionNotFound
        data = _data(current.context_data)
        repository = CashMutationRepository(uow._state().session, self._workspace_id)
        created = repository.by_key(self._user_id, data.get("derivation_key"))
        if (created is None or created["operation_kind"] != "retry_session"
            or created["request_digest"] != data.get("derivation_digest")
            or created.get("retry_context_id") != current.session_id):
            raise ImportSessionNotFound
        root, _ = self._root(uow, current, data=data)
        successful = self._successful(uow, current, root, data=data)
        current = self._prune(current, successful)
        if successful and not current.files and not current.cancelled:
            raise CashMutationConflict("retry_already_processed", next(iter(successful.values()))["receipt_id"])
        return replace(current, context_data={**current.context_data, "_successful": successful})

    def _register(self, uow, root_id, child_id, root=None):
        root = self._staging.get(root_id, workspace_id=self._workspace_id, user_id=self._user_id) if root is None else root
        data = deepcopy(_data(root.context_data))
        if (root.session_id != root_id or root.workspace_id != self._workspace_id or root.user_id != self._user_id
            or root.workflow_kind != "retry_capsule" or root.cancelled):
            raise ImportSessionNotFound
        if child_id in data["derived_context_ids"]:
            return
        if len(data["derived_context_ids"]) >= 256:
            raise CashImportPlanError("retry_context_limit")
        data["derived_context_ids"].append(child_id)
        self._staging.replace(root_id, workspace_id=self._workspace_id, user_id=self._user_id,
            expected_revision=root.revision, files=root.files, preview_input=root.preview_input, plan=root.plan, context_data=data)

    def derive(self, receipt_id, *, row_refs=None, idempotency_key):
        if row_refs is not None and (not isinstance(row_refs, list) or not 1 <= len(row_refs) <= 100_000
            or any(not _reference(ref) for ref in row_refs) or len(set(row_refs)) != len(row_refs)):
            raise CashImportPlanError("retry_rows_invalid")
        digest = _digest("retry-session-v1", receipt_id, sorted(row_refs) if row_refs is not None else None)
        try:
            with self._uow as uow:
                self._authorize(uow)
                repository = CashMutationRepository(uow._state().session, self._workspace_id)
                replay = repository.replay(self._user_id, idempotency_key, request_digest=digest, operation_kind="retry_session")
                if replay is not None:
                    consumed = repository.for_context(self._user_id,
                        session_digest=sha256(replay["retry_context_id"].encode()).hexdigest())
                    if consumed is not None:
                        raise CashMutationConflict("retry_already_processed", consumed["receipt_id"])
                    return self._read_context(uow, replay["retry_context_id"])
                receipt = repository.get(self._user_id, receipt_id)
                if receipt is None or receipt["operation_kind"] not in {"import", "legacy_import"}:
                    raise CashImportPlanError("receipt_not_found")
                if not receipt.get("retry_context_id"):
                    raise CashImportPlanError("retry_context_unavailable")
                capsule = self._staging.get(receipt["retry_context_id"], workspace_id=self._workspace_id, user_id=self._user_id)
                if capsule.workflow_kind != "retry_capsule" or capsule.cancelled:
                    raise ImportSessionNotFound
                data = _data(capsule.context_data)
                root, root_receipt = self._root(uow, capsule, receipt_id=receipt_id, data=data)
                successful = self._successful(uow, capsule, root, data=data)
                capsule = self._prune(capsule, successful)
                if root.session_id == capsule.session_id:
                    root = capsule
                available = {ref for file in capsule.files for row in file.document.rows for ref in (f"{file.file_ref}:r{row.position}",)}
                selected = set(row_refs) if row_refs is not None else available
                known = {ref for unit in data["units"].values() for ref in unit["row_refs"]}
                if selected - known:
                    raise CashImportPlanError("retry_rows_invalid")
                if selected.intersection(successful) or not selected and successful:
                    raise CashMutationConflict("retry_already_processed", next(iter(successful.values()))["receipt_id"])
                if not selected or selected - available:
                    raise CashImportPlanError("retry_context_unavailable")
                units = {key: deepcopy(unit) for key, unit in data["units"].items() if selected.intersection(unit["row_refs"])}
                required = {ref for unit in units.values() for ref in unit["row_refs"]}
                files = _subset_files(capsule.files, required)
                inputs = _inputs(capsule.preview_input, files)
                account_ids = {int(item["account_id"]) for item in inputs["account_selections"] if item.get("account_id")}
                revisions = {}
                identifiers = sorted(account_ids)
                for start in range(0, len(identifiers), 400):
                    revisions.update({str(account.id): account.revision for account in uow._state().session.scalars(select(AccountModel).where(
                        AccountModel.workspace_id == self._workspace_id, AccountModel.id.in_(identifiers[start:start + 400])))})
                for item in inputs["account_selections"]:
                    if item.get("account_id") in revisions:
                        item["expected_account_revision"] = revisions[item["account_id"]]
                child_data = {"version": 1, "units": units, "source_proofs": deepcopy(data["source_proofs"]),
                    "derived_context_ids": [], "root_context_id": root.session_id, "root_receipt_id": root_receipt["receipt_id"],
                    "parent_receipt_id": receipt_id, "derivation_key": idempotency_key, "derivation_digest": digest}
                child = self._staging.create(workspace_id=self._workspace_id, user_id=self._user_id, files=files,
                    workflow_kind="retry", context_data=child_data, preview_input=inputs,
                    created_at=capsule.created_at, expires_at=capsule.expires_at)
                self._register(uow, root.session_id, child.session_id, root)
                repository.create(user_id=self._user_id, idempotency_key=idempotency_key, request_digest=digest,
                    operation_kind="retry_session", result_summary={"retry_context_id": child.session_id,
                        "retry_context_expires_at": child.expires_at.isoformat()}, results=[])
                uow.commit()
                return child
        except (ImportSessionExpired, ImportSessionNotFound):
            raise CashImportPlanError("retry_context_expired") from None

    def cleanup(self, current):
        if current.workflow_kind != "retry":
            return
        with self._uow as uow:
            self._authorize(uow)
            root, _ = self._root(uow, current)
            for identifier in (root.session_id, *root.context_data["derived_context_ids"][:32]):
                try:
                    item = self._staging.get(identifier, workspace_id=self._workspace_id, user_id=self._user_id)
                    if not item.cancelled and item.workflow_kind in {"retry", "retry_capsule"}:
                        self._prune(item, self._successful(uow, item, root))
                except (ImportSessionNotFound, ImportSessionExpired):
                    continue
