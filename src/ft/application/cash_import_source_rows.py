"""把已解析出现交给来源对应策略；这里只组合输入，不查询或写入账本。"""
from __future__ import annotations

from copy import deepcopy
from dataclasses import replace

from ft.domain.cash_source_matching import SourceDocumentEvidence, SourceOccurrence
from ft.domain.cash_source_batch import SourceBatchInput
from ft.domain.cash_source_documents import current_document_coverage, document_completeness
from ft.domain.cash_source_scope import source_account_key


def source_batch_rows(files, *, resolved_fields=None, known_document_evidence=()):
    """组合已解析文件，不重新解析；补丁按文件与物理行位置隔离。"""
    files = tuple(files)
    evidence = (*file_document_evidence(files), *known_document_evidence)
    result = SourceBatchInput(document_evidence=evidence,
        document_coverages=(item.coverage for item in evidence),
        file_metadata={file.file_ref: (index, file.last_modified_at_ms) for index, file in enumerate(files)})
    file_refs = set()
    coverages = _combined_coverages(files, evidence)
    for file in files:
        if file.file_ref in file_refs:
            raise ValueError("duplicate_source_file_ref")
        file_refs.add(file.file_ref)
        if file.file_ref in coverages:
            file = replace(file, document=replace(file.document, coverage=coverages[file.file_ref]))
        result.update(source_rows(file, resolved_fields=(resolved_fields or {}).get(file.file_ref)))
    return result


def file_document_evidence(files):
    return tuple(SourceDocumentEvidence(file.document.source_type, file.document.parser_version, file.document.coverage)
        for file in files if file.status == "parsed" and file.document is not None and not file.is_subset)


def _combined_coverages(files, evidence):
    """只组合认证解析证据；单文件暂存保持原样，不重读原文件。"""
    completeness, result = document_completeness(evidence), {}
    for file in files:
        if file.status == "parsed" and file.document is not None and file.document.coverage.document_key and not file.is_subset:
            result[file.file_ref] = current_document_coverage(SourceDocumentEvidence(
                file.document.source_type, file.document.parser_version, file.document.coverage), completeness)
    return result


def source_rows(file, *, resolved_fields=None):
    if file.status != "parsed" or file.document is None:
        return {}
    document = file.document
    if file.is_subset:
        document = replace(document, coverage=replace(document.coverage, complete=False, evidence=""))
    result = {}
    for row in document.rows:
        if row.disposition == "skipped" or row.fields is None:
            continue
        values = deepcopy(row.fields)
        values.update(deepcopy((resolved_fields or {}).get(row.position, {})))
        scope = source_account_key(document.source_type, row.source_account_identifier or document.source_account_identifier, scope_label=file.source_scope_label)
        result[f"{file.file_ref}:r{row.position}"] = SourceOccurrence(
            source_type=document.source_type, source_account_key=scope, file_digest=file.digest,
            parser_version=document.parser_version, table_key=document.table_key, position=row.position,
            fields=values, coverage=document.coverage, provider_id=row.provider_id,
            document_position=row.document_position,
        )
    return result
