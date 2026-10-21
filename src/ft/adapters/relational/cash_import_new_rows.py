"""全新正式流水的有界试写；失败由调用方回滚并逐条执行。"""
from dataclasses import asdict

from sqlalchemy import insert

from ft.domain.cash_record_policy import CASH_FINANCIAL_FIELDS, MUTABLE_CASH_FIELDS, _normalize
from .cash_sources import CashSourceRepository, SourceBindingConflict, _json_object
from .models import CashSourceBindingModel, CashTransactionComponentModel, CashTransactionModel


class CashImportNewRowsRepository:
    def __init__(self, session, workspace_id):
        self.session, self.workspace_id = session, workspace_id

    def insert(self, entries):
        if not 1 <= len(entries) <= 100:
            raise ValueError("new_import_batch_invalid")
        incoming = [row for operation, rows in entries for row in rows]
        if CashSourceRepository(self.session, self.workspace_id)._for_occurrences(incoming):
            raise SourceBindingConflict("source_identity_conflict")
        values = [{key: _normalize(key, value) for key, value in operation["values"].items()}
            for operation, _rows in entries]
        parents = []
        for (operation, _rows), value in zip(entries, values):
            parent = {key: item for key, item in value.items()
                if key in (MUTABLE_CASH_FIELDS | CASH_FINANCIAL_FIELDS | {"state", "source_type", "record_id"}) - {"components", "account_name"}}
            if parent.get("account_id") is not None:
                parent["account_id"] = int(parent["account_id"])
            parent.update(workspace_id=self.workspace_id,
                manual_overrides={field: True for field in operation["manual_fields"]},
                relation_metadata=operation["source_explanation"])
            parents.append(parent)
        identifiers = list(self.session.scalars(insert(CashTransactionModel).returning(
            CashTransactionModel.id, sort_by_parameter_order=True), parents))
        parts, part_keys = [], []
        for index, (identifier, value) in enumerate(zip(identifiers, values)):
            for part in value["components"]:
                parts.append({"workspace_id": self.workspace_id, "cash_transaction_id": identifier,
                    **{key: int(part[key]) if key == "account_id" else part[key]
                        for key in ("ordinal", "account_id", "amount", "currency")}})
                part_keys.append((index, part["ordinal"]))
        part_ids = list(self.session.scalars(insert(CashTransactionComponentModel).returning(
            CashTransactionComponentModel.id, sort_by_parameter_order=True), parts))
        actual_parts = dict(zip(part_keys, part_ids))
        bindings, evidence_owners = {}, {}
        for identifier, (_operation, rows) in zip(identifiers, entries):
            for row in rows:
                if row.coverage.document_key or row.document_page:
                    raise SourceBindingConflict("source_document_conflict")
                evidence = row.stable_evidence_binding_key
                if evidence and evidence_owners.setdefault(evidence, identifier) != identifier:
                    raise SourceBindingConflict("source_identity_conflict")
                coverage = _json_object(asdict(row.coverage))
                for spec in row.binding_specs:
                    if spec.key in bindings and bindings[spec.key]["transaction_id"] != identifier:
                        raise SourceBindingConflict("source_identity_conflict")
                    bindings[spec.key] = {"workspace_id": self.workspace_id, "transaction_id": identifier,
                        "source_type": spec.source_type, "source_scope_kind": spec.scope_kind,
                        "source_scope_key": spec.scope_key, "identity_kind": spec.identity_kind, "identity_key": spec.identity_key,
                        "identity_strength": "file_local" if spec.scope_kind == "file_local" else "provider" if spec.identity_kind == "provider_id" else "validated",
                        "strategy_version": row.parser_version, "coverage": coverage,
                        "stable_evidence_key": evidence if spec.scope_kind == "account" else None}
        if bindings:
            self.session.execute(insert(CashSourceBindingModel), list(bindings.values()))
        return [(identifier, {str(part["component_id"]): str(actual_parts[index, part["ordinal"]])
            for part in operation["preview_components"]}, None, operation["values"], set())
            for index, (identifier, (operation, _rows)) in enumerate(zip(identifiers, entries))]
