"""统一准备计划／提交所需的当前来源对应；预览不写入业务状态。"""
from dataclasses import dataclass, replace

from ft.application.cash_import_source_rows import file_document_evidence, source_batch_rows
from ft.domain.cash_source_batch import resolve_source_batch
from ft.domain.cash_source_documents import current_document_coverage, document_completeness


@dataclass(frozen=True)
class PreparedCashSourcePlan:
    rows: dict
    records: tuple
    plan: object
    evidence: tuple


class CashImportSourcePlanner:
    def __init__(self, session, workspace_id):
        from ft.adapters.relational.cash_sources import CashSourceRepository
        from ft.adapters.relational.cash_source_documents import CashSourceDocumentRepository
        self._sources = CashSourceRepository(session, workspace_id)
        self._documents = CashSourceDocumentRepository(session, workspace_id)

    def prepare(self, files, *, resolved_fields=None):
        files = tuple(files)
        rows = source_batch_rows(files, resolved_fields=resolved_fields)
        records = self._sources.records_for_file(tuple(rows.values()))
        candidates = tuple(item for record in records for item in record.document_evidence)
        saved = self._documents.read(files, candidate_evidence=candidates)
        evidence = (*file_document_evidence(files), *saved, *candidates)
        completeness = document_completeness(evidence)
        if saved:
            rows = source_batch_rows(files, resolved_fields=resolved_fields, known_document_evidence=saved)
        lifted = []
        for record in records:
            regular = tuple(coverage for coverage in record.coverages if not coverage.document_key)
            current = tuple(current_document_coverage(item, completeness) for item in record.document_evidence)
            # 缺少命名空间的旧认证标志不能单独授予完整证明。
            unproven = tuple(replace(coverage, complete=False, evidence="") for coverage in record.coverages
                if coverage.document_key) if not record.document_evidence else ()
            lifted.append(replace(record, coverages=(*regular, *current, *unproven)))
        return PreparedCashSourcePlan(rows, tuple(lifted), resolve_source_batch(rows, lifted,
            known_document_coverages=evidence), evidence)
