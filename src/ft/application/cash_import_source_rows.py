"""把已解析出现交给来源对应策略；这里只组合输入，不查询或写入账本。"""
from __future__ import annotations

from copy import deepcopy
from dataclasses import replace

from ft.domain.cash_source_matching import SourceOccurrence
from ft.domain.cash_source_batch import SourceBatchInput
from ft.domain.cash_source_scope import source_account_key


def source_batch_rows(files, *, resolved_fields=None):
    """组合已解析文件，不重新解析；补丁按文件与物理行位置隔离。"""
    files = tuple(files)
    result = SourceBatchInput(document_coverages=(file.document.coverage for file in files if file.document is not None))
    file_refs = set()
    coverages = _combined_coverages(files)
    for file in files:
        if file.file_ref in file_refs:
            raise ValueError("duplicate_source_file_ref")
        file_refs.add(file.file_ref)
        if file.file_ref in coverages:
            file = replace(file, document=replace(file.document, coverage=coverages[file.file_ref]))
        result.update(source_rows(file, resolved_fields=(resolved_fields or {}).get(file.file_ref)))
    return result


def _combined_coverages(files):
    """只组合认证解析证据；单文件暂存保持原样，不重读原文件。"""
    groups, result = {}, {}
    for file in files:
        if file.status == "parsed" and file.document is not None and file.document.coverage.document_key:
            document = file.document
            groups.setdefault((document.source_type, document.parser_version, document.coverage.document_key), []).append(file)
    for members in groups.values():
        pages, conflict = {}, False
        first = members[0].document.coverage
        for file in members:
            coverage = file.document.coverage
            if (coverage.page_total, coverage.start, coverage.end, coverage.filters, coverage.strategy_version) != (first.page_total, first.start, first.end, first.filters, first.strategy_version):
                conflict = True
            for page, fingerprint in coverage.page_fingerprints.items():
                conflict = conflict or pages.setdefault(page, fingerprint) != fingerprint
        complete = not conflict and set(pages) == {str(page) for page in range(1, first.page_total + 1)}
        for file in members:
            result[file.file_ref] = replace(file.document.coverage, complete=complete,
                evidence="icbc-pdf-export-pages-count-v1" if complete else "")
    return result


def source_rows(file, *, resolved_fields=None):
    if file.status != "parsed" or file.document is None:
        return {}
    document = file.document
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
