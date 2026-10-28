"""多文件共同来源规划；重复结果引用共同操作，不合计重叠文件的次数。"""
from __future__ import annotations

from collections import Counter, defaultdict
from dataclasses import dataclass, replace
from datetime import datetime
from decimal import Decimal
from typing import Mapping, Sequence

from ft.domain.cash_record_policy import CashRecordPolicyError, prepare_cash_record_update
from ft.domain.cash_source_matching import (
    SourceOccurrence, SourceRecord, SourceResolution, _compatible, _digest, _index, _patch,
    _record_binding_keys, _resolve_source_rows, conflicting_source_document_pages,
)


@dataclass(frozen=True)
class SourceBatchResolution:
    row_ref: str
    action: str
    transaction_id: str | None = None
    candidate_ids: tuple[str, ...] = ()
    reason: str = ""
    bind: bool = False
    duplicate_of: str | None = None
    group_ref: str | None = None


@dataclass(frozen=True)
class SourceBatchGroup:
    group_ref: str
    row_refs: tuple[str, ...] = ()
    candidate_ids: tuple[str, ...] = ()


@dataclass(frozen=True)
class SourceBatchPlan:
    rows: tuple[SourceBatchResolution, ...]
    groups: tuple[SourceBatchGroup, ...] = ()


class SourceBatchInput(dict[str, SourceOccurrence]):
    """所有文件证据独立于行集合，空页也必须参与本批身份校验。"""

    def __init__(self, *args, document_coverages=(), document_evidence=(), file_metadata=None, **kwargs):
        super().__init__(*args, **kwargs)
        self.document_coverages = tuple(document_coverages)
        self.document_evidence = tuple(document_evidence)
        self.file_metadata = dict(file_metadata or {})


@dataclass(frozen=True)
class _Unit:
    ref: str
    refs: tuple[str, ...]
    row: SourceOccurrence
    content_key: str
    certain: bool
    targets: frozenset[str]
    files: frozenset[str]


def _content_value(value):
    if isinstance(value, Decimal):
        # 不使用 normalize()，以免当前 Decimal 精度舍入合法的 38 位值。
        sign, digits, exponent = value.as_tuple()
        digits = list(digits)
        while digits and digits[-1] == 0:
            digits.pop()
            exponent += 1
        return {"decimal": [sign if digits else 0, digits, exponent if digits else 0]}
    if isinstance(value, datetime):
        return value.isoformat()
    if isinstance(value, dict):
        return {key: _content_value(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_content_value(item) for item in value]
    return value


def resolve_source_batch(
    rows: Mapping[str, SourceOccurrence], records: Sequence[SourceRecord], *, known_document_coverages=(),
) -> SourceBatchPlan:
    """保留每个会话行引用；只把确定身份或等价结果归并为共同写入。"""
    if any(not isinstance(ref, str) or not ref for ref in rows):
        raise ValueError("invalid_source_row_ref")
    active = [record for record in records if not record.deleted]
    by_binding, by_index = defaultdict(set), defaultdict(list)
    for record in active:
        for key in _record_binding_keys(record):
            by_binding[key].add(record.transaction_id)
        index = _index(record.fields)
        if index is not None:
            by_index[(record.source_type, index)].append(record)

    output: dict[str, SourceBatchResolution] = {}
    result_groups: dict[str, SourceBatchGroup] = {}
    group_cache = {}
    roots, proof_owner, contents, targets = {}, {}, {}, {}
    row_order = {ref: index for index, ref in enumerate(rows)}
    file_evidence = (rows.document_evidence or rows.document_coverages) if isinstance(rows, SourceBatchInput) else ()
    conflicting_pages = conflicting_source_document_pages(rows.values(), active,
        evidence=(*file_evidence, *known_document_coverages))

    def shared_group(refs=(), ids=()):
        # 调用者复用集合元组；不为每条行重新散列、复制大集合。
        cache_key = (id(refs), id(ids))
        if cache_key not in group_cache:
            key = _digest("source-results-v1", refs, ids)
            result_groups.setdefault(key, SourceBatchGroup(key, refs, ids))
            group_cache[cache_key] = (key, refs, ids)
        return group_cache[cache_key][0]

    def result(ref, value: SourceResolution):
        ids = value.candidate_ids
        group = shared_group(ids=ids) if len(ids) > 1 else None
        return SourceBatchResolution(ref, value.action, value.transaction_id, ids if not group else (), value.reason, value.bind, group_ref=group)

    def root(ref):
        while roots[ref] != ref:
            roots[ref] = roots[roots[ref]]
            ref = roots[ref]
        return ref

    def file_ref(ref):
        return ref.partition(":")[0]

    def snapshot_winner(refs):
        """在可靠身份组内选版本；混合／重复时间整体回退到会话文件顺序。"""
        files = [file_ref(ref) for ref in refs]
        if len(set(files)) != len(files):
            return None
        order_by_file = {}
        timestamp_by_file = {}
        for file in files:
            fallback_order = min(row_order[ref] for ref in refs if file_ref(ref) == file)
            metadata = rows.file_metadata.get(file, (fallback_order, None)) if isinstance(rows, SourceBatchInput) else (fallback_order, None)
            if (not isinstance(metadata, tuple) or len(metadata) != 2 or type(metadata[0]) is not int
                or metadata[0] < 0 or metadata[1] is not None and (type(metadata[1]) is not int or metadata[1] < 0)):
                return None
            order_by_file[file], timestamp_by_file[file] = metadata
        timestamps = [timestamp_by_file[file] for file in files]
        if all(value is not None for value in timestamps) and len(set(timestamps)) == len(timestamps):
            return min(refs, key=lambda ref: (-timestamp_by_file[file_ref(ref)], order_by_file[file_ref(ref)], row_order[ref]))
        return min(refs, key=lambda ref: (-order_by_file[file_ref(ref)], row_order[ref]))

    for ref, row in rows.items():
        try:
            patch = _patch(row)
            normalized = prepare_cash_record_update({"state": "draft"}, patch).normalized_values
        except CashRecordPolicyError as error:
            output[ref] = SourceBatchResolution(ref, "conflict", reason=error.code)
            continue
        contents[ref] = _digest(_content_value(normalized))
        roots[ref] = ref
        keys = (*row.binding_keys, row.stable_evidence_binding_key)
        targets[ref] = {target for key in keys if key for target in by_binding[key]}
        proofs = ["occurrence:" + row.occurrence_key]
        if row.document_binding_key:
            proofs.append(row.document_binding_key)
        proofs.extend(key for key in (row.provider_binding_key, row.stable_evidence_binding_key) if key)
        proofs.extend("target:" + target for target in targets[ref])
        for proof in proofs:
            previous = proof_owner.setdefault(proof, ref)
            roots[root(ref)] = root(previous)

    identified = defaultdict(list)
    for ref in roots:
        identified[root(ref)].append(ref)
    units: dict[str, _Unit] = {}
    rejected = defaultdict(dict)
    for refs in identified.values():
        refs.sort(key=row_order.__getitem__)
        first = rows[refs[0]]
        providers = {rows[ref].provider_id for ref in refs if rows[ref].provider_id}
        evidence = {rows[ref].stable_evidence for ref in refs if rows[ref].stable_evidence}
        ids = frozenset(target for ref in refs for target in targets[ref])
        sorted_ids = tuple(sorted(ids))
        failed = False
        different_contents = len({contents[ref] for ref in refs}) > 1
        same_patch_shape = len({tuple(sorted(rows[ref].fields)) for ref in refs}) == 1
        reliable_snapshot_group = (different_contents and (len(providers) == 1 or len(evidence) == 1)
            and len(providers) <= 1 and len(evidence) <= 1 and len(ids) <= 1
            and same_patch_shape
            and len({(rows[ref].source_type, rows[ref].source_account_key) for ref in refs}) == 1
            and not any(rows[ref].document_page and (rows[ref].source_type, rows[ref].parser_version,
                *rows[ref].document_page) in conflicting_pages for ref in refs))
        if reliable_snapshot_group:
            winner = snapshot_winner(refs)
            if winner is not None:
                refs = [winner, *(ref for ref in refs if ref != winner)]
                first = rows[winner]
            else:
                reliable_snapshot_group = False
        if ((different_contents and not reliable_snapshot_group) or len(providers) > 1 or len(evidence) > 1
            or any(rows[ref].document_page and (rows[ref].source_type, rows[ref].parser_version,
                *rows[ref].document_page) in conflicting_pages for ref in refs)
            or len({(rows[ref].source_type, rows[ref].source_account_key) for ref in refs}) > 1):
            group = shared_group(ids=sorted_ids) if len(sorted_ids) > 1 else None
            for ref in refs:
                output[ref] = SourceBatchResolution(ref, "conflict", candidate_ids=() if group else sorted_ids, reason="batch_identity_conflict", group_ref=group)
            failed = True
        elif len(ids) > 1:
            for ref in refs:
                output[ref] = SourceBatchResolution(ref, "pending", reason="source_identity_ambiguous", group_ref=shared_group(ids=sorted_ids))
            failed = True
        else:
            provider, proof = next(iter(providers), None), next(iter(evidence), None)
            combined = (first if (first.provider_id, first.stable_evidence) == (provider, proof)
                else replace(first, provider_id=provider, stable_evidence=proof))
            certain = bool(ids or combined.provider_binding_key or combined.stable_evidence_binding_key)
            unit = _Unit(refs[0], tuple(refs), combined, contents[refs[0]], certain, ids,
                         frozenset(rows[ref].observation_scope_key for ref in refs))
            units[unit.ref] = unit
        if failed:
            locations = frozenset(rows[ref].observation_scope_key for ref in refs)
            references = tuple(refs)
            for ref in refs:
                row = rows[ref]
                index = _index(row.fields)
                if index is not None:
                    rejected[(row.source_type, index)][(refs[0], row.source_account_key, row.provider_id, row.stable_evidence)] = (references, locations, row, ids)

    groups, scopes = defaultdict(list), defaultdict(list)
    canonical, destinations = {}, {}
    for unit in units.values():
        row = unit.row
        index = _index(row.fields)
        if index is None:
            canonical[unit.ref] = unit
            destinations[unit.ref] = (unit.ref,)
        else:
            groups[(row.source_type, row.source_account_key, index)].append(unit)
            scopes[(row.source_type, index)].append(unit)

    def defer(subjects, alternatives):
        unit = subjects[0]
        relevant = by_index[(unit.row.source_type, _index(unit.row.fields))]
        ids = {record.transaction_id for record in relevant
               if not unit.row.source_account_key or not record.source_account_key or record.source_account_key == unit.row.source_account_key}
        ids.update(target for other in alternatives for target in other.targets)
        related = tuple(sorted(other.ref for other in alternatives))
        group = shared_group(related, tuple(sorted(ids)))
        for subject in subjects:
            for ref in subject.refs:
                output[ref] = SourceBatchResolution(ref, "pending", reason="source_identity_ambiguous", group_ref=group)

    collision_cache = {}
    for unit in units.values():
        if unit.certain:
            continue
        key = (unit.row.source_type, _index(unit.row.fields), unit.row.source_account_key,
               unit.row.provider_id, unit.row.stable_evidence, unit.files)
        if key not in collision_cache:
            collisions = [entry for entry in rejected[key[:2]].values()
                          if not unit.files & entry[1] and (not unit.row.source_account_key or not entry[2].source_account_key
                              or unit.row.source_account_key == entry[2].source_account_key) and _compatible(unit.row, entry[2])]
            refs = tuple(sorted({ref for entry in collisions for ref in entry[0]}))
            ids = tuple(sorted({target for entry in collisions for target in entry[3]}))
            collision_cache[key] = shared_group(refs, ids) if collisions else None
        group = collision_cache[key]
        if group:
            for ref in unit.refs:
                output[ref] = SourceBatchResolution(ref, "pending", reason="source_identity_ambiguous", group_ref=group)

    # 不知本方来源的行不能因已知来源在另一个分组就自动复制正式记录。
    for members in scopes.values():
        known = [unit for unit in members if unit.row.source_account_key]
        alternatives_cache = {}
        for unit in members:
            if unit.row.source_account_key is None and not unit.certain:
                key = (unit.row.provider_id, unit.row.stable_evidence, unit.files)
                if key not in alternatives_cache:
                    alternatives = [other for other in known if len(other.files | unit.files) > 1
                        and not other.files & unit.files and _compatible(unit.row, other.row)]
                    if alternatives:
                        defer([unit], alternatives)
                        alternatives_cache[key] = output[unit.ref].group_ref
                    else:
                        alternatives_cache[key] = None
                group = alternatives_cache[key]
                if group:
                    for ref in unit.refs:
                        output[ref] = SourceBatchResolution(ref, "pending", reason="source_identity_ambiguous", group_ref=group)

    partitions = []
    for members in groups.values():
        # 即便本方账户未知，不同可靠编号／认证证据仍能排除互为同笔。
        field = ("provider_id" if all(unit.row.provider_id for unit in members) else
                 "stable_evidence" if all(unit.row.stable_evidence for unit in members) else None)
        if field:
            separated = defaultdict(list)
            for unit in members:
                separated[getattr(unit.row, field)].append(unit)
            partitions.extend(separated.values())
        else:
            partitions.append(members)
    for members in partitions:
        members = [unit for unit in members if unit.ref not in output]
        if not members:
            continue
        files = defaultdict(list)
        for unit in members:
            for file in unit.files:
                files[file].append(unit)
        if len(files) == 1 or all(unit.certain for unit in members):
            for unit in members:
                canonical[unit.ref] = unit
                destinations[unit.ref] = (unit.ref,)
            continue
        first = members[0].row
        comparable = first.source_account_key is not None and all(
            first.coverage.comparable(rows[ref].coverage, first.fields["occurred_at"])
            for unit in members for ref in unit.refs
        )
        counts = {file: Counter(unit.content_key for unit in values) for file, values in files.items()}
        equivalent = len({unit.content_key for unit in members}) == 1 or all(count == next(iter(counts.values())) for count in counts.values())
        if not comparable or not equivalent:
            weak = []
            for unit in members:
                has_distinct_same_file_occurrence = any(other is not unit and other.certain
                    and other.files & unit.files for other in members)
                if unit.certain or has_distinct_same_file_occurrence:
                    canonical[unit.ref] = unit
                    destinations[unit.ref] = (unit.ref,)
                else:
                    weak.append(unit)
            if weak:
                defer(weak, members)
            continue

        by_content = defaultdict(list)
        for unit in members:
            by_content[unit.content_key].append(unit)
        for content, values in by_content.items():
            chosen = [unit for unit in values if unit.certain]
            maximum = max(count[content] for count in counts.values())
            anchor = min(counts, key=lambda file: (-counts[file][content], file))
            weak = sorted((unit for unit in values if not unit.certain), key=lambda unit: (anchor not in unit.files, unit.ref))
            chosen.extend(weak[:max(0, maximum - len(chosen))])
            selected = {unit.ref for unit in chosen}
            for unit in chosen:
                canonical[unit.ref] = unit
                destinations[unit.ref] = (unit.ref,)
            eligibility = {}
            for unit in values:
                if unit.ref in selected:
                    continue
                # 同一文件中已经出现的确定单笔不能再次被其另一行消耗。
                if unit.files not in eligibility:
                    eligibility[unit.files] = tuple(sorted(other.ref for other in chosen if not other.files & unit.files))
                eligible = eligibility[unit.files]
                if eligible:
                    destinations[unit.ref] = eligible
                else:
                    defer([unit], values)

    ordered = sorted(canonical)
    known_targets = {unit.row.occurrence_key: unit.targets for unit in canonical.values() if unit.targets}
    # 这些规范单元已经通过上面的字段、身份、页面及次数检查。
    # 没有活动旧记录时不再重复执行同一单文件校验与空候选匹配。
    resolved = (dict(zip(ordered, _resolve_source_rows([canonical[ref].row for ref in ordered], active,
        targets_by_occurrence=known_targets))) if active else {
            ref: SourceResolution(canonical[ref].row.occurrence_key, "new", bind=True) for ref in ordered})
    evaluations = {}
    for unit in units.values():
        if unit.ref in output:
            continue
        options = destinations[unit.ref]
        if id(options) not in evaluations:
            values = [resolved[ref] for ref in options]
            failed = any(value.action in {"conflict", "pending"} for value in values)
            candidate_ids = tuple(sorted({candidate for value in values for candidate in value.candidate_ids}
                                         | {value.transaction_id for value in values if value.transaction_id})) if failed else ()
            group = shared_group(options, candidate_ids) if options != (unit.ref,) and (failed or len(options) > 1) else None
            evaluations[id(options)] = (values[0] if len(values) == 1 else None, failed, group)
        only, failed, group = evaluations[id(options)]
        if options == (unit.ref,):
            output[unit.ref] = result(unit.ref, only)
        for ref in unit.refs:
            if ref in output:
                continue
            if failed:
                if options == (unit.ref,):
                    # 本次出现之间的确定身份不受旧流水对应问题影响。
                    output[ref] = replace(result(ref, only), duplicate_of=unit.ref if only.action == "pending" else None)
                else:
                    output[ref] = SourceBatchResolution(ref, "pending", reason="source_identity_ambiguous", group_ref=group)
            else:
                output[ref] = SourceBatchResolution(
                    ref, "batch_duplicate", transaction_id=only.transaction_id if only else None,
                    bind=bool(only and only.bind), duplicate_of=options[0] if only else None,
                    group_ref=group,
                )
    used_groups = {value.group_ref for value in output.values() if value.group_ref}
    return SourceBatchPlan(tuple(output[ref] for ref in rows), tuple(value for key, value in result_groups.items() if key in used_groups))
