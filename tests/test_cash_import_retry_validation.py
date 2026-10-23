"""损坏的私有证明不得降级为新流水或透出未经验证的正文。"""
from copy import deepcopy
from dataclasses import asdict

import pytest

from ft.application.cash_import_retry_validation import retry_data
from ft.application.cash_import_errors import ImportSessionStorageUnavailable
from ft.domain.cash_source_matching import SourceCoverage


def _valid():
    return {"version": 1, "derived_context_ids": [], "units": {"file_a:r1": {
        "source_ref": "file_a:r1", "row_refs": ["file_a:r1"], "validated": True, "proof_key": "a" * 64,
        "resolution": {"action": "new", "transaction_id": None, "candidate_ids": [], "reason": "", "bind": True}}},
        "source_proofs": {"version": 1, "document_witnesses": [], "candidate_sets": {}, "groups": {"a" * 64: {
            "records": {}, "documents": {}, "allowed_inputs": {"file_a:r1": "b" * 64},
            "credit_units": {"file_a:r1": {"row_refs": ["file_a:r1"], "binding_keys": ["c" * 64]}}}}}}


@pytest.mark.parametrize("path,bad", [
    (("version",), True),
    (("source_proofs", "version"), True),
    (("derived_context_ids",), [{}]),
    (("root_context_id",), "bad"),
    (("source_proofs", "groups", "a" * 64, "records"), {"0": "d" * 64}),
    (("source_proofs", "groups", "a" * 64, "records"), {str(2 ** 63): "d" * 64}),
    (("source_proofs", "groups", "a" * 64, "documents"), {"a" * 64: 1}),
    (("source_proofs", "groups", "a" * 64, "credit_units"), {"file_a:r1": None}),
    (("source_proofs", "groups", "a" * 64, "credit_units", "file_a:r1", "row_refs"), ["unrelated:r1"]),
    (("source_proofs", "groups", "a" * 64, "credit_units", "file_a:r1", "binding_keys"), []),
    (("units", "file_a:r1", "resolution"), {"action": "new"}),
    (("units", "file_a:r1", "resolution", "action"), []),
    (("units", "file_a:r1", "resolution", "bind"), 1),
    (("units", "file_a:r1", "resolution", "transaction_id"), "0"),
    (("units", "file_a:r1", "resolution", "candidate_ids"), [True]),
    (("units", "file_a:r1", "resolution", "candidate_ids"), ["1", "1"]),
    (("units", "file_a:r1", "resolution", "reason"), "private exception detail"),
    (("units", "file_a:r1", "row_refs"), ["unrelated:r1"]),
    (("units", "file_a:r1", "validated"), 1),
    (("units", "file_a:r1", "resolution", "candidate_set_ref"), "f" * 64),
    (("source_proofs", "candidate_sets"), {"f" * 64: ["1"]}),
    (("source_proofs", "candidate_sets"), {"f" * 64: [True]}),
    (("source_proofs", "document_witnesses"), [{}]),
])
def test_invalid_proof_is_uniformly_unavailable_without_mutating_input(path, bad):
    value = _valid()
    parent = value
    for name in path[:-1]:
        parent = parent[name]
    parent[path[-1]] = bad
    before = deepcopy(value)
    with pytest.raises(ImportSessionStorageUnavailable):
        retry_data(value)
    assert value == before


@pytest.mark.parametrize("field,bad", [("page_total", True), ("page_fingerprints", {"2": "b" * 64}),
    ("complete", 1), ("start", []), ("filters", []), ("document_key", "not-a-digest")])
def test_malformed_document_witness_cannot_reach_document_reader(field, bad):
    value = _valid()
    coverage = asdict(SourceCoverage(document_key="d" * 64, page_total=1, page_fingerprints={"1": "e" * 64}))
    coverage[field] = bad
    value["source_proofs"]["document_witnesses"] = [{"source_type": "icbc", "parser_version": "fixture-v1", "coverage": coverage}]
    with pytest.raises(ImportSessionStorageUnavailable):
        retry_data(value)
