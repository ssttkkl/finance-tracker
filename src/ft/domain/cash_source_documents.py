"""当前认证页的完整性派生；不修改单行来源或保存历史完整标志。"""
from dataclasses import asdict, replace

from ft.domain.cash_source_matching import _time


def document_completeness(evidence):
    groups = {}
    for item in evidence:
        if item.coverage.document_key:
            groups.setdefault(item.namespace, []).append(item.coverage)
    result = {}
    for namespace, coverages in groups.items():
        first, pages, consistent = coverages[0], {}, True
        declaration = {key: value for key, value in asdict(first).items()
            if key not in {"complete", "evidence", "page_fingerprints"}}
        for coverage in coverages:
            consistent = consistent and declaration == {key: value for key, value in asdict(coverage).items()
                if key not in {"complete", "evidence", "page_fingerprints"}}
            for page, fingerprint in coverage.page_fingerprints.items():
                consistent = consistent and pages.setdefault(page, fingerprint) == fingerprint
        try:
            bounded = _time(first.start) < _time(first.end)
        except (ValueError, TypeError):
            bounded = False
        result[namespace] = bool(consistent and bounded and set(pages) == {str(page) for page in range(1, first.page_total + 1)})
    return result


def current_document_coverage(item, completeness):
    coverage = item.coverage
    if not coverage.document_key:
        return coverage
    complete = completeness.get(item.namespace, False)
    return replace(coverage, complete=complete, evidence="icbc-pdf-export-pages-count-v1" if complete else "")
