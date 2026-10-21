"""把已解析出现交给来源对应策略；这里只组合输入，不查询或写入账本。"""
from __future__ import annotations

from copy import deepcopy

from ft.domain.cash_source_matching import SourceOccurrence
from ft.domain.cash_source_scope import source_account_key


def source_rows(file, *, resolved_fields=None):
    if file.status != "parsed" or file.document is None:
        return {}
    document = file.document
    scope = source_account_key(document.source_type, document.source_account_identifier, scope_label=file.source_scope_label)
    result = {}
    for row in document.rows:
        if row.disposition == "skipped" or row.fields is None:
            continue
        values = deepcopy(row.fields)
        values.update(deepcopy((resolved_fields or {}).get(row.position, {})))
        result[f"{file.file_ref}:r{row.position}"] = SourceOccurrence(
            source_type=document.source_type, source_account_key=scope, file_digest=file.digest,
            parser_version=document.parser_version, table_key=document.table_key, position=row.position,
            fields=values, coverage=document.coverage, provider_id=row.provider_id,
        )
    return result
