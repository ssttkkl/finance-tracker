"""失败子集只复用已经证明的剩余次数，不重新认证来源完整性。"""
from collections import defaultdict
from dataclasses import asdict, replace

from ft.adapters.relational.cash_source_documents import CashSourceDocumentRepository
from ft.application.cash_import_source_plan import CashImportSourcePlanner
from ft.application.cash_source_candidate_sets import SourceCandidateSets, frozen_source_candidate_sets, source_candidate_ids
from ft.domain.cash_source_batch import SourceBatchGroup, SourceBatchPlan, SourceBatchResolution, _content_value
from ft.domain.cash_source_matching import (SourceCoverage, SourceDocumentEvidence,
    _digest, _index, _record_binding_keys)


def _group(source_type, fields, fallback=""):
    index = _index(fields)
    return _digest("retry-source-group-v1", source_type, _content_value(list(index)) if index else fallback)


def _signature(row):
    coverage = asdict(row.coverage)
    coverage.pop("complete"); coverage.pop("evidence")
    return _digest("retry-input-v1", row.occurrence_key, row.source_account_key,
        _content_value(dict(row.fields)), row.provider_binding_key, row.stable_evidence_binding_key,
        row.document_position, coverage)


def _record_signature(record):
    # 关系或问题修订不改变来源对应；真实字段、人工保护与来源证据均纳入。
    return _digest(record.transaction_id, record.source_type, record.source_account_key,
        _content_value(dict(record.fields)), sorted(record.protected_fields), sorted(_record_binding_keys(record)),
        sorted({_digest(asdict(value)) for value in record.coverages}),
        {key: sorted(value) for key, value in record.file_occurrences.items()})


def _document_state(session, workspace_id, prepared, witnesses):
    original = tuple(SourceDocumentEvidence(value["source_type"], value["parser_version"],
        SourceCoverage(**value["coverage"])) for value in witnesses)
    candidates = (*original, *(item for record in prepared.records for item in record.document_evidence))
    saved = CashSourceDocumentRepository(session, workspace_id).read(candidate_evidence=candidates)
    facts = defaultdict(set)
    for value in (*original, *saved):
        coverage = asdict(value.coverage)
        pages = coverage.pop("page_fingerprints")
        coverage.pop("complete"); coverage.pop("evidence")
        for page, fingerprint in pages.items():
            facts[_digest(value.namespace)].add(_digest(coverage, page, fingerprint))
    return {key: _digest(sorted(value)) for key, value in facts.items()}


def _record_groups(prepared):
    records = defaultdict(dict)
    for record in prepared.records:
        records[_group(record.source_type, record.fields)][record.transaction_id] = record
    return records


def build_retry_proofs(session, workspace_id, files, units, source_plan, validated):
    """只写失败行输入及成功正文的摘要；调用方仍持工作区锁。"""
    current = CashImportSourcePlanner(session, workspace_id).prepare(files)
    witnesses = { _digest(file.source_type, file.document.parser_version, asdict(file.document.coverage)):
        {"source_type": file.source_type, "parser_version": file.document.parser_version,
            "coverage": asdict(file.document.coverage)}
        for file in files if file.document.coverage.document_key}
    witnesses = list(witnesses.values())
    documents = _document_state(session, workspace_id, current, witnesses)
    records, groups = _record_groups(current), {}
    original = {item.row_ref: item for item in source_plan.plan.rows}
    original_groups = {item.group_ref: item for item in source_plan.plan.groups}
    candidate_sets = SourceCandidateSets()
    for reference, row in current.rows.items():
        key = _group(row.source_type, row.fields, row.occurrence_key)
        if key not in groups:
            related = records.get(key, {})
            scopes = {item.namespace for record in related.values() for item in record.document_evidence}
            groups[key] = {"records": {identifier: _record_signature(record) for identifier, record in related.items()},
                "documents": {_digest(scope): documents.get(_digest(scope)) for scope in scopes},
                "allowed_inputs": {}, "credit_units": {}}
        group = groups[key]
        group["allowed_inputs"][reference] = _signature(row)
        if row.coverage.document_key:
            scope = _digest((row.source_type, row.parser_version, row.coverage.document_key))
            group["documents"][scope] = documents.get(scope)
    for root, unit in units.items():
        reference = unit["source_ref"]
        resolution = original[reference]
        candidate_group = resolution.group_ref
        candidates = original_groups[resolution.group_ref].candidate_ids if resolution.group_ref in original_groups else resolution.candidate_ids
        row = current.rows[reference]
        key = _group(row.source_type, row.fields, row.occurrence_key)
        if resolution.action == "batch_duplicate":
            resolution = SourceBatchResolution(reference, "pending", candidate_ids=tuple(candidates), reason="source_identity_ambiguous")
        is_valid = (unit.get("operation_ref") in validated
            and (resolution.action not in {"match", "duplicate"} or resolution.transaction_id in groups[key]["records"]))
        unit.update(proof_key=key, validated=is_valid,
            resolution={"action": resolution.action, "transaction_id": resolution.transaction_id,
                "candidate_ids": [], "reason": resolution.reason, "bind": resolution.bind})
        candidate_sets.bind(unit["resolution"], candidates, candidate_group)
        unit.pop("operation_ref", None)
        if unit["validated"] and resolution.action == "new":
            groups[key]["credit_units"][root] = {"row_refs": unit["row_refs"],
                "binding_keys": sorted({binding for reference in unit["row_refs"] for binding in current.rows[reference].binding_keys})}
    return {"version": 1, "groups": groups, "document_witnesses": witnesses, "candidate_sets": candidate_sets.values}


def apply_retry_proofs(session, workspace_id, files, data, successful):
    current = CashImportSourcePlanner(session, workspace_id).prepare(files)
    proofs = data["source_proofs"]
    frozen_candidates = frozen_source_candidate_sets(proofs["candidate_sets"])
    documents = _document_state(session, workspace_id, current, proofs["document_witnesses"])
    records = _record_groups(current)
    original = {item.row_ref: item for item in current.plan.rows}
    current_groups = {item.group_ref: item for item in current.plan.groups}
    output = dict(original)
    candidate_sets = SourceCandidateSets()
    candidate_members = defaultdict(list)
    by_binding = defaultdict(set)
    for record in current.records:
        for binding in _record_binding_keys(record):
            by_binding[binding].add(record.transaction_id)
    incoming = defaultdict(dict)
    signatures = {}
    for reference, row in current.rows.items():
        signatures[reference] = _signature(row)
        incoming[_group(row.source_type, row.fields, row.occurrence_key)][reference] = signatures[reference]
    unchanged_groups = {}
    for key, proof in proofs["groups"].items():
        related = records.get(key, {})
        live = {identifier: _record_signature(record) for identifier, record in related.items()}
        for descriptor in proof["credit_units"].values():
            for ref in descriptor["row_refs"]:
                result = successful.get(ref, {})
                identifier = result.get("transaction_id")
                record = related.get(identifier)
                if (identifier not in proof["records"] and record is not None
                    and result.get("outcome") in {"created_posted", "saved_draft"}
                    and set(descriptor["binding_keys"]).intersection(record.binding_keys)):
                    live.pop(identifier, None)
        inputs_unchanged = all(proof["allowed_inputs"].get(ref) == signature for ref, signature in incoming[key].items())
        unchanged_groups[key] = (live == proof["records"] and inputs_unchanged
            and all(documents.get(scope) == signature for scope, signature in proof["documents"].items()))
    for root, unit in data["units"].items():
        references = [ref for ref in unit["row_refs"] if ref in current.rows]
        if not references:
            continue
        key = unit["proof_key"]
        proof = proofs["groups"][key]
        candidate_group_key = None
        reason = ("retry_aliases_incomplete" if len(references) != len(unit["row_refs"]) else
            "retry_context_changed" if any(proof["allowed_inputs"].get(ref) != signatures[ref] for ref in references) else None)
        if reason:
            for reference in references:
                output[reference] = SourceBatchResolution(reference, "conflict", reason=reason)
            continue
        rows = [current.rows[ref] for ref in references]
        values = [original[ref] for ref in references]
        if any(value.duplicate_of is not None and value.duplicate_of not in references for value in values):
            continue
        strong = {identifier for row in rows for binding in (*row.binding_keys, row.stable_evidence_binding_key)
            for identifier in by_binding.get(binding, ())}
        if any(value.action == "conflict" for value in values):
            chosen = next(value for value in values if value.action == "conflict")
        elif strong:
            # 先服从当前强对应，旧新增信用绝不覆盖已经写入的物理出现。
            chosen = next((value for value in values if value.transaction_id in strong and value.action != "batch_duplicate"), None)
            if chosen is None:
                chosen = SourceBatchResolution(references[0], "pending", candidate_ids=tuple(sorted(strong)), reason="source_identity_ambiguous")
        else:
            if unit["validated"] and unchanged_groups[key]:
                resolution = {key: value for key, value in unit["resolution"].items() if key != "candidate_set_ref"}
                resolution["candidate_ids"] = source_candidate_ids(unit["resolution"], frozen_candidates)
                chosen = SourceBatchResolution(references[0], **resolution)
                candidate_group_key = unit["resolution"].get("candidate_set_ref")
            elif all(value.action == "new" for value in values):
                chosen = values[0]
            else:
                # 当前规划已完整核对作用域、编号和物理位置，复用其候选资格。
                value = next((item for item in values if item.action != "batch_duplicate"), values[0])
                candidates = value.candidate_ids or ((value.transaction_id,) if value.transaction_id else ())
                chosen = SourceBatchResolution(references[0], "pending", candidate_ids=candidates,
                    group_ref=value.group_ref, reason="source_identity_ambiguous")
        root_ref = references[0]
        if chosen.group_ref in current_groups:
            chosen = replace(chosen, candidate_ids=())
        elif chosen.candidate_ids:
            key = candidate_sets.reference(chosen.candidate_ids, candidate_group_key)
            candidate_members[key].append(root_ref)
            chosen = replace(chosen, candidate_ids=(), group_ref="retry_candidates_" + key)
        output[root_ref] = replace(chosen, row_ref=root_ref, duplicate_of=None)
        for reference in references[1:]:
            output[reference] = SourceBatchResolution(reference, "batch_duplicate", transaction_id=chosen.transaction_id,
                bind=chosen.bind, duplicate_of=root_ref)
    groups = (*current.plan.groups, *(SourceBatchGroup("retry_candidates_" + key, tuple(references), tuple(candidate_sets.values[key]))
        for key, references in candidate_members.items()))
    used_groups = {value.group_ref for value in output.values() if value.group_ref}
    return replace(current, plan=SourceBatchPlan(tuple(output[item.row_ref] for item in current.plan.rows),
        tuple(group for group in groups if group.group_ref in used_groups)))
