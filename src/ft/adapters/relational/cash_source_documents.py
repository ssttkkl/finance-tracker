"""有界读取当前来源页及认证空页；调用方管理工作区锁和保存点。"""
from dataclasses import asdict, replace
import json

from sqlalchemy import Text, and_, cast, select, tuple_

from ft.application.cash_import_source_rows import file_document_evidence
from ft.domain.cash_source_matching import SourceCoverage, SourceDocumentEvidence, _digest, conflicting_source_document_pages
from .models import CashSourceBindingModel, CashSourceEmptyPageModel, CashTransactionModel


def _declaration(coverage):
    return {key: value for key, value in asdict(coverage).items() if key not in {"complete", "evidence", "page_fingerprints"}}


class CashSourceDocumentRepository:
    def __init__(self, session, workspace_id):
        self.session, self.workspace_id = session, workspace_id

    def read(self, files=(), *, candidate_evidence=()):
        evidence = (*file_document_evidence(files), *candidate_evidence)
        namespaces = sorted({item.namespace for item in evidence if item.coverage.document_key})
        result = []
        for offset in range(0, len(namespaces), 150):
            chunk = namespaces[offset:offset + 150]
            scopes = [(source, parser, _digest("source-document-v1", source, parser, document))
                for source, parser, document in chunk]
            bindings = self.session.execute(select(CashSourceBindingModel.source_type, CashSourceBindingModel.strategy_version,
                cast(CashSourceBindingModel.coverage, Text)).join(CashTransactionModel, and_(
                    CashTransactionModel.workspace_id == CashSourceBindingModel.workspace_id,
                    CashTransactionModel.id == CashSourceBindingModel.transaction_id)).where(
                CashSourceBindingModel.workspace_id == self.workspace_id, CashTransactionModel.deleted_at.is_(None),
                CashSourceBindingModel.source_scope_kind == "file_local", CashSourceBindingModel.identity_kind == "file_occurrence",
                tuple_(CashSourceBindingModel.source_type, CashSourceBindingModel.strategy_version,
                    CashSourceBindingModel.source_scope_key).in_(scopes)).distinct())
            result.extend(SourceDocumentEvidence(source, parser, SourceCoverage(**json.loads(coverage)))
                for source, parser, coverage in bindings)
            empty = self.session.scalars(select(CashSourceEmptyPageModel).where(
                CashSourceEmptyPageModel.workspace_id == self.workspace_id,
                tuple_(CashSourceEmptyPageModel.source_type, CashSourceEmptyPageModel.parser_version,
                    CashSourceEmptyPageModel.document_key).in_(chunk)))
            result.extend(SourceDocumentEvidence(row.source_type, row.parser_version, SourceCoverage(**row.coverage)) for row in empty)
        return tuple(result)

    def validate_evidence(self, evidence):
        """保存时同样重验；不能在冲突空页已存在时写入矛盾业务页。"""
        from .cash_sources import SourceBindingConflict
        evidence = tuple(evidence)
        existing = self.read(candidate_evidence=evidence)
        combined = (*existing, *evidence)
        conflicts = conflicting_source_document_pages((), evidence=combined)
        declarations = {}
        for item in combined:
            if item.coverage.document_key:
                declaration = _declaration(item.coverage)
                if declarations.setdefault(item.namespace, declaration) != declaration:
                    raise SourceBindingConflict("source_document_conflict")
        if conflicts:
            raise SourceBindingConflict("source_document_conflict")
        return existing

    def save_empty_pages(self, files):
        files = tuple(files)
        existing = self.validate_evidence(file_document_evidence(files))
        known = {(*item.namespace, page) for item in existing for page in item.coverage.page_fingerprints}
        for file in files:
            if file.status != "parsed" or file.document is None:
                continue
            document, coverage = file.document, file.document.coverage
            if (not coverage.document_key or document.observed_count != len(document.rows)
                or any(row.document_position is None for row in document.rows)):
                continue
            occupied = {row.document_position.split(":", 1)[0][1:] for row in document.rows}
            for page, fingerprint in coverage.page_fingerprints.items():
                if page in occupied:
                    continue
                key = (document.source_type, document.parser_version, coverage.document_key, page)
                if key not in known:
                    known.add(key)
                    self.session.add(CashSourceEmptyPageModel(workspace_id=self.workspace_id, source_type=document.source_type,
                        parser_version=document.parser_version, document_key=coverage.document_key, page_number=int(page),
                        coverage=asdict(replace(coverage, complete=False, evidence="", page_fingerprints={page: fingerprint}))))
        self.session.flush()
