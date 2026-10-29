"""当前来源对应规划；只接受适配器已验证的身份与范围证据。"""
from __future__ import annotations

from collections import defaultdict, deque
from dataclasses import dataclass, field
from datetime import datetime, timezone
from hashlib import sha256
from functools import cached_property
import json
import re
from typing import Any, Mapping, Sequence
from uuid import uuid4

from ft.domain.cash_record_policy import (
    CASH_FINANCIAL_FIELDS, MUTABLE_CASH_FIELDS, CashRecordPolicyError,
    prepare_cash_record_update,
)
from ft.domain.decimal import exact_decimal


_DIGEST_ENCODER = json.JSONEncoder(ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)


def _digest(*values: Any) -> str:
    return sha256(_DIGEST_ENCODER.encode(values).encode()).hexdigest()


def provider_identity_key(provider_id: str) -> str:
    return _digest(provider_id)


def _time(value: Any) -> datetime:
    result = value if isinstance(value, datetime) else datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    if result.tzinfo is None or result.utcoffset() is None:
        raise ValueError("source_time_requires_timezone")
    return result.astimezone(timezone.utc)


@dataclass(frozen=True)
class SourceCoverage:
    start: str | None = None
    end: str | None = None
    complete: bool = False
    evidence: str = ""
    filters: Mapping[str, Any] = field(default_factory=dict)
    start_inclusive: bool = True
    end_inclusive: bool = False
    time_precision: str = "second"
    strategy_version: str = "source-coverage-v1"
    document_key: str | None = None
    page_total: int | None = None
    page_fingerprints: Mapping[str, str] = field(default_factory=dict)

    def __post_init__(self):
        if self.document_key is None and self.page_total is None and self.page_fingerprints == {}:
            return
        if (not isinstance(self.document_key, str) or not re.fullmatch(r"[0-9a-f]{64}", self.document_key)
            or type(self.page_total) is not int or not 1 <= self.page_total <= 2000
            or not isinstance(self.page_fingerprints, Mapping) or not 1 <= len(self.page_fingerprints) <= self.page_total
            or any(not isinstance(key, str) or not re.fullmatch(r"[1-9][0-9]{0,3}", key)
                or int(key) > self.page_total or not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value)
                for key, value in self.page_fingerprints.items())):
            raise ValueError("invalid_source_document_evidence")

    def contains(self, value: Any) -> bool:
        if not self.complete or not self.evidence or self.start is None or self.end is None:
            return False
        try:
            start, end, when = _time(self.start), _time(self.end), _time(value)
            return start < end and (start <= when if self.start_inclusive else start < when) and (when <= end if self.end_inclusive else when < end)
        except (ValueError, TypeError):
            return False

    def comparable(self, other: SourceCoverage, when: Any) -> bool:
        return (
            self.contains(when) and other.contains(when)
            and self.filters == other.filters and self.time_precision == other.time_precision
            and self.strategy_version == other.strategy_version
        )


@dataclass(frozen=True)
class SourceDocumentEvidence:
    source_type: str
    parser_version: str
    coverage: SourceCoverage

    @property
    def namespace(self):
        return self.source_type, self.parser_version, self.coverage.document_key


@dataclass(frozen=True)
class SourceBindingKey:
    source_type: str
    scope_kind: str
    scope_key: str
    identity_kind: str
    identity_key: str

    @cached_property
    def key(self) -> str:
        return _digest(self.source_type, self.scope_kind, self.scope_key, self.identity_kind, self.identity_key)


@dataclass(frozen=True)
class SourceOccurrence:
    source_type: str
    source_account_key: str | None
    file_digest: str
    parser_version: str
    table_key: str
    position: int
    fields: Mapping[str, Any]
    coverage: SourceCoverage = field(default_factory=SourceCoverage)
    provider_id: str | None = None
    stable_evidence: str | None = None
    document_position: str | None = None

    def __post_init__(self):
        required = ((self.source_type, 64), (self.file_digest, 64), (self.parser_version, 64), (self.table_key, 255))
        optional = ((self.source_account_key, 256), (self.provider_id, 512), (self.stable_evidence, 512))
        if any(not isinstance(value, str) or not value or len(value) > maximum for value, maximum in required) or any(value is not None and (not isinstance(value, str) or not value or len(value) > maximum) for value, maximum in optional) or type(self.position) is not int or self.position < 0 or not isinstance(self.fields, Mapping):
            raise ValueError("invalid_source_locator")
        if self.source_type == "wechat" and self.provider_id is None:
            raise ValueError("wechat_transaction_id_required")
        if self.document_position is not None and (not isinstance(self.document_position, str) or not re.fullmatch(r"p[1-9][0-9]{0,3}:t[0-9]{1,2}:r[1-9][0-9]{0,5}", self.document_position)):
            raise ValueError("invalid_source_locator")

    @property
    def document_scope_key(self) -> str | None:
        return _digest("source-document-v1", self.source_type, self.parser_version, self.coverage.document_key) if self.coverage.document_key else None

    @property
    def document_occurrence_key(self) -> str | None:
        if self.document_scope_key and self.document_position and self.document_position.split(":", 1)[0][1:] in self.coverage.page_fingerprints:
            return _digest(self.document_scope_key, self.table_key, self.document_position)
        return None

    @property
    def observation_scope_key(self) -> str:
        return self.document_scope_key if self.document_occurrence_key else self.file_scope_key

    @property
    def document_binding_key(self) -> str | None:
        if self.document_occurrence_key:
            return SourceBindingKey(self.source_type, "file_local", self.document_scope_key, "file_occurrence", self.document_occurrence_key).key
        return None

    @property
    def document_page(self) -> tuple[str, str] | None:
        if self.document_occurrence_key:
            return self.coverage.document_key, self.document_position.split(":", 1)[0][1:]
        return None

    @property
    def document_page_scope_key(self) -> str | None:
        return _digest("source-document-page-v1", self.document_scope_key, self.document_page[1]) if self.document_page else None

    @cached_property
    def occurrence_key(self) -> str:
        if self.source_type == "wechat" and self.source_account_key:
            return _digest(self.source_type, self.source_account_key, self.file_digest, self.parser_version, self.table_key, self.position)
        return _digest(self.source_type, self.file_digest, self.parser_version, self.table_key, self.position)

    @cached_property
    def file_scope_key(self) -> str:
        if self.source_type == "wechat" and self.source_account_key:
            return _digest(self.source_type, self.source_account_key, self.file_digest, self.parser_version)
        return _digest(self.source_type, self.file_digest, self.parser_version)

    @property
    def account_scope_key(self) -> str | None:
        return self.source_account_key or None

    @cached_property
    def file_binding_key(self) -> str:
        return SourceBindingKey(self.source_type, "file_local", self.file_scope_key, "file_occurrence", self.occurrence_key).key

    @cached_property
    def provider_identity(self):
        return provider_identity_key(self.provider_id) if self.provider_id else None

    @cached_property
    def provider_binding_key(self) -> str | None:
        if self.provider_id and self.account_scope_key:
            return next(spec.key for spec in self._identity_binding_specs if spec.identity_kind == "provider_id")
        return None

    @cached_property
    def stable_evidence_binding_key(self) -> str | None:
        if self.stable_evidence and self.source_account_key:
            return _digest(self.source_type, self.source_account_key, "verified", self.stable_evidence)
        return None

    @property
    def binding_keys(self) -> tuple[str, ...]:
        return tuple(spec.key for spec in self.binding_specs)

    @cached_property
    def _identity_binding_specs(self) -> tuple[SourceBindingKey, ...]:
        specs = []
        if self.account_scope_key:
            if self.provider_id:
                specs.append(SourceBindingKey(self.source_type, "account", self.account_scope_key, "provider_id", self.provider_identity))
            specs.append(SourceBindingKey(self.source_type, "account", self.account_scope_key, "file_occurrence", self.occurrence_key))
        specs.append(SourceBindingKey(self.source_type, "file_local", self.file_scope_key, "file_occurrence", self.occurrence_key))
        return tuple(specs)

    @property
    def binding_specs(self) -> tuple[SourceBindingKey, ...]:
        # 页面认证映射仍可变，只缓存由不可变身份字段组成的基础绑定。
        specs = list(self._identity_binding_specs)
        if self.document_occurrence_key:
            specs.append(SourceBindingKey(self.source_type, "file_local", self.document_scope_key, "file_occurrence", self.document_occurrence_key))
            specs.append(SourceBindingKey(self.source_type, "file_local", self.document_page_scope_key, "file_occurrence", self.document_occurrence_key))
        return tuple(specs)


@dataclass(frozen=True)
class SourceRecord:
    transaction_id: str
    source_type: str
    source_account_key: str | None
    fields: Mapping[str, Any]
    coverages: tuple[SourceCoverage, ...] = ()
    binding_keys: frozenset[str] = frozenset()
    protected_fields: frozenset[str] = frozenset()
    stable_evidence: str | None = None
    provider_id: str | None = None
    provider_binding_key: str | None = None
    stable_evidence_binding_key: str | None = None
    row_revision: int = 1
    deleted: bool = False
    file_occurrences: Mapping[str, frozenset[str]] = field(default_factory=dict)
    document_evidence: tuple[SourceDocumentEvidence, ...] = ()


@dataclass(frozen=True)
class SourceResolution:
    occurrence_key: str
    action: str
    transaction_id: str | None = None
    candidate_ids: tuple[str, ...] = ()
    reason: str = ""
    bind: bool = False
    duplicate_of: str | None = None


def _index(fields: Mapping[str, Any]) -> tuple | None:
    try:
        if any(fields.get(key) is None for key in ("amount", "currency", "occurred_at")) or not isinstance(fields["currency"], str):
            return None
        # 名称、备注、类型、内部账户映射及内容散列都不是身份索引。
        return _time(fields["occurred_at"]), exact_decimal(fields["amount"]), fields["currency"]
    except (ValueError, TypeError, ArithmeticError):
        return None


def _patch(row: SourceOccurrence) -> dict:
    return {key: value for key, value in row.fields.items() if key in CASH_FINANCIAL_FIELDS | MUTABLE_CASH_FIELDS}


def _difference(row: SourceOccurrence, old: SourceRecord) -> tuple[bool, str]:
    try:
        update = prepare_cash_record_update(old.fields, _patch(row), protected_fields=old.protected_fields)
        return bool(update.values), ""
    except CashRecordPolicyError as error:
        return True, error.code


def _matched(row: SourceOccurrence, old: SourceRecord) -> SourceResolution:
    if (
        row.source_account_key and old.source_account_key and row.source_account_key != old.source_account_key
        or not _compatible(row, old)
    ):
        return SourceResolution(row.occurrence_key, "conflict", transaction_id=old.transaction_id, reason="source_identity_conflict")
    changed, error = _difference(row, old)
    return SourceResolution(
        row.occurrence_key, "conflict" if error else "match" if changed else "duplicate",
        transaction_id=old.transaction_id, reason=error, bind=not error,
    )


def _distinct_positions(row: SourceOccurrence, old: SourceRecord | SourceOccurrence) -> bool:
    if row.document_occurrence_key:
        if isinstance(old, SourceOccurrence):
            if (row.document_scope_key == old.document_scope_key and old.document_occurrence_key
                and row.document_occurrence_key != old.document_occurrence_key):
                return True
        elif (row.document_scope_key in old.file_occurrences
            and row.document_occurrence_key not in old.file_occurrences[row.document_scope_key]):
            return True
    # 普通文件位置只排除弱对应，不推翻可靠编号或认证行的同一性。
    keys = old.binding_keys
    if (row.provider_binding_key and (row.provider_binding_key == old.provider_binding_key or row.provider_binding_key in keys)
        or row.stable_evidence_binding_key and (row.stable_evidence_binding_key == old.stable_evidence_binding_key
            or row.stable_evidence_binding_key in keys)):
        return False
    if isinstance(old, SourceOccurrence):
        return row.file_scope_key == old.file_scope_key and row.occurrence_key != old.occurrence_key
    return bool(row.file_scope_key in old.file_occurrences
        and row.occurrence_key not in old.file_occurrences[row.file_scope_key])


def conflicting_document_pages(coverages: Sequence[SourceCoverage]) -> set[tuple[str, str]]:
    pages, conflicting, seen = {}, set(), set()
    for coverage in coverages:
        if coverage.document_key and id(coverage) not in seen:
            seen.add(id(coverage))
            for page, fingerprint in coverage.page_fingerprints.items():
                key = (coverage.document_key, page)
                if pages.setdefault(key, fingerprint) != fingerprint:
                    conflicting.add(key)
    return conflicting


def conflicting_source_document_pages(rows, records=(), *, evidence=()):
    """冲突只发生于同渠道、解析命名空间和认证导出的同页。"""
    rows = tuple(rows)
    proofs = [SourceDocumentEvidence(row.source_type, row.parser_version, row.coverage) for row in rows]
    namespaces = defaultdict(set)
    for proof in proofs:
        if proof.coverage.document_key:
            namespaces[(proof.source_type, proof.coverage.document_key)].add(proof.parser_version)
    for record in records:
        if record.document_evidence:
            proofs.extend(record.document_evidence)
        else:
            # 仅兼容旧纯领域调用。生产读取始终提供明确命名空间。
            for coverage in record.coverages:
                versions = namespaces[(record.source_type, coverage.document_key)]
                if len(versions) == 1:
                    proofs.append(SourceDocumentEvidence(record.source_type, next(iter(versions)), coverage))
    for proof in evidence:
        if isinstance(proof, SourceDocumentEvidence):
            proofs.append(proof)
        else:
            # 旧内部调用只提供范围时，不能猜测多个解析空间的归属。
            for (source, document), versions in namespaces.items():
                if document == proof.document_key and len(versions) == 1:
                    proofs.append(SourceDocumentEvidence(source, next(iter(versions)), proof))
    groups = defaultdict(list)
    for proof in proofs:
        if proof.coverage.document_key:
            groups[proof.namespace].append(proof.coverage)
    return {(source, parser, document, page) for (source, parser, _document), coverages in groups.items()
        for document, page in conflicting_document_pages(coverages)}


def _compatible(row: SourceOccurrence, old: SourceRecord | SourceOccurrence) -> bool:
    return not (
        _distinct_positions(row, old)
        or row.provider_id and old.provider_id and row.provider_id != old.provider_id
        or row.provider_binding_key and old.provider_binding_key and row.provider_binding_key != old.provider_binding_key
        or row.stable_evidence and old.stable_evidence and row.stable_evidence != old.stable_evidence
        or row.stable_evidence_binding_key and old.stable_evidence_binding_key and row.stable_evidence_binding_key != old.stable_evidence_binding_key
    )


def _unchanged_assignment(rows: Sequence[SourceOccurrence], old: Sequence[SourceRecord]) -> bool:
    """只判定集合无需改动，不用这次匹配建立不确定逐行绑定。"""
    if len(rows) > len(old):
        return False
    # 相同出现次数可能很大；等价单元用线性检查，避免构建全连接图。
    common_protected = set.intersection(*(set(record.protected_fields) for record in old)) if old else set()
    compared = [{key: value for key, value in _patch(row).items() if key not in common_protected} for row in rows]
    if rows and all(values == compared[0] for values in compared):
        return sum(_difference(rows[0], record) == (False, "") for record in old) >= len(rows)
    by_payload: dict[str, list[int]] = defaultdict(list)
    for index, record in enumerate(old):
        by_payload[_digest(record.fields.get("source_payload"))].append(index)
    edges = [[index for index in (by_payload[_digest(row.fields["source_payload"])] if "source_payload" in row.fields and "source_payload" not in common_protected else range(len(old))) if _compatible(row, old[index]) and _difference(row, old[index]) == (False, "")] for row in rows]
    matched_old, matched_rows = {}, {}
    for first in range(len(rows)):
        queue, seen_rows, previous = deque([first]), {first}, {}
        free = None
        while queue and free is None:
            row_index = queue.popleft()
            for old_index in edges[row_index]:
                if old_index in previous:
                    continue
                previous[old_index] = row_index
                if old_index not in matched_old:
                    free = old_index
                    break
                following = matched_old[old_index]
                if following not in seen_rows:
                    seen_rows.add(following)
                    queue.append(following)
        if free is None:
            return False
        while free is not None:
            row_index = previous[free]
            next_free = matched_rows.get(row_index)
            matched_old[free], matched_rows[row_index] = row_index, free
            free = next_free
    return True


def _record_binding_keys(record: SourceRecord) -> set[str]:
    keys = set(record.binding_keys)
    if record.provider_id and record.source_account_key:
        keys.add(_digest(record.source_type, "account", record.source_account_key, "provider_id", provider_identity_key(record.provider_id)))
    if record.provider_binding_key:
        keys.add(record.provider_binding_key)
    if record.stable_evidence and record.source_account_key:
        keys.add(_digest(record.source_type, record.source_account_key, "verified", record.stable_evidence))
    if record.stable_evidence_binding_key:
        keys.add(record.stable_evidence_binding_key)
    return keys


def resolve_source_file(rows: Sequence[SourceOccurrence], records: Sequence[SourceRecord]) -> list[SourceResolution]:
    """一份文件内一对一消耗候选；调用方合并多文件计划，不能相加重复次数。"""
    if len({(row.source_type, row.file_digest, row.parser_version) for row in rows}) > 1:
        raise ValueError("one_source_file_required")
    if len({row.occurrence_key for row in rows}) != len(rows):
        raise ValueError("duplicate_source_locator")
    return _resolve_source_rows(rows, records)


def _resolve_source_rows(
    rows: Sequence[SourceOccurrence], records: Sequence[SourceRecord], *,
    targets_by_occurrence: Mapping[str, frozenset[str]] | None = None,
) -> list[SourceResolution]:
    """消耗一个逻辑集合；多文件调用前必须先完成共同集合及冲突规划。"""
    active = [record for record in records if not record.deleted]
    by_id = {record.transaction_id: record for record in active}
    targets_by_occurrence = targets_by_occurrence or {}
    conflicting_pages = conflicting_source_document_pages(rows, active)
    by_binding: dict[str, list[SourceRecord]] = defaultdict(list)
    by_index: dict[tuple, list[SourceRecord]] = defaultdict(list)
    by_unscoped_index: dict[tuple, list[SourceRecord]] = defaultdict(list)
    for record in active:
        for key in _record_binding_keys(record):
            by_binding[key].append(record)
        key = _index(record.fields)
        if key is not None:
            by_unscoped_index[(record.source_type, key)].append(record)
        if record.source_account_key and key is not None:
            by_index[(record.source_type, record.source_account_key, key)].append(record)
        elif record.fields.get("state", "posted") == "posted" and key is not None:
            by_index[(record.source_type, None, key)].append(record)
    results: dict[str, SourceResolution] = {}
    used: set[str] = set()
    groups: dict[tuple, list[SourceOccurrence]] = defaultdict(list)
    certified_rows = []
    roots: dict[str, str] = {}
    proof_owner: dict[str, str] = {}

    def root(key):
        while roots[key] != key:
            roots[key] = roots[roots[key]]
            key = roots[key]
        return key

    for row in rows:
        if row.document_page and (row.source_type, row.parser_version, *row.document_page) in conflicting_pages:
            results[row.occurrence_key] = SourceResolution(row.occurrence_key, "conflict", reason="batch_identity_conflict")
            continue
        try:
            prepare_cash_record_update({"state": "draft"}, _patch(row))
        except CashRecordPolicyError as error:
            results[row.occurrence_key] = SourceResolution(row.occurrence_key, "conflict", reason=error.code)
            continue
        proofs = tuple(key for key in (row.provider_binding_key, row.stable_evidence_binding_key) if key)
        proofs += tuple("transaction:" + record.transaction_id for proof in proofs for record in by_binding[proof])
        proofs += tuple("transaction:" + target for target in targets_by_occurrence.get(row.occurrence_key, ()))
        if proofs:
            roots[row.occurrence_key] = row.occurrence_key
            certified_rows.append(row)
            for proof in proofs:
                previous = proof_owner.setdefault(proof, row.occurrence_key)
                roots[root(row.occurrence_key)] = root(previous)
    certified_groups: dict[str, list[SourceOccurrence]] = defaultdict(list)
    for row in certified_rows:
        certified_groups[root(row.occurrence_key)].append(row)
    for values in certified_groups.values():
        if (
            any(_patch(row) != _patch(values[0]) for row in values[1:])
            or len({row.provider_binding_key for row in values if row.provider_binding_key}) > 1
            or len({row.stable_evidence_binding_key for row in values if row.stable_evidence_binding_key}) > 1
        ):
            for row in values:
                results[row.occurrence_key] = SourceResolution(row.occurrence_key, "conflict", reason="batch_identity_conflict")
        else:
            for row in values[1:]:
                results[row.occurrence_key] = SourceResolution(row.occurrence_key, "batch_duplicate", duplicate_of=values[0].occurrence_key)
    for row in rows:
        if row.occurrence_key in results:
            continue
        proof_keys = list(row.binding_keys)
        if row.stable_evidence_binding_key:
            proof_keys.append(row.stable_evidence_binding_key)
        targets = {record.transaction_id: record for key in proof_keys for record in by_binding[key]}
        targets.update((target, by_id[target]) for target in targets_by_occurrence.get(row.occurrence_key, ()))
        if targets:
            if len(targets) == 1 and (row.provider_binding_key or next(iter(targets)) not in used):
                target = next(iter(targets.values()))
                results[row.occurrence_key] = _matched(row, target)
                if results[row.occurrence_key].action != "conflict":
                    used.add(target.transaction_id)
            else:
                results[row.occurrence_key] = SourceResolution(row.occurrence_key, "pending", candidate_ids=tuple(sorted(targets)), reason="source_identity_ambiguous")
            continue
        key = _index(row.fields)
        if key is not None:
            scope_index = (row.source_type, row.source_account_key, key)
            groups[scope_index].append(row)
        else:
            results[row.occurrence_key] = SourceResolution(row.occurrence_key, "new", bind=True)
    for key, members in groups.items():
        scoped_records = by_index[key] + by_index[(key[0], None, key[2])] if key[1] else by_unscoped_index[(key[0], key[2])]
        possible = [record for record in scoped_records if any(_compatible(row, record) for row in members)]
        candidates = [record for record in possible if record.transaction_id not in used]
        if not candidates:
            for row in members:
                # 有直接绑定的旧出现已被消耗；额外出现仍须完整范围证明。
                if possible and (key[1] is None or any(record.source_account_key is None for record in possible) or not any(row.coverage.comparable(cov, row.fields["occurred_at"]) for record in possible for cov in record.coverages)):
                    results[row.occurrence_key] = SourceResolution(row.occurrence_key, "pending", candidate_ids=tuple(sorted(record.transaction_id for record in possible)), reason="source_identity_ambiguous")
                else:
                    results[row.occurrence_key] = SourceResolution(row.occurrence_key, "new", bind=True)
            continue
        if key[1] is None:
            comparable = False
        elif not any(row.provider_id or row.stable_evidence for row in members) and all(row.coverage == members[0].coverage for row in members):
            # 同文件同索引的无编号行共享范围；按旧对象核对一次即可。
            comparable = all(record.source_account_key is not None and any(
                members[0].coverage.comparable(cov, members[0].fields["occurred_at"]) for cov in record.coverages
            ) for record in candidates)
        else:
            comparable = all(
                record.source_account_key is not None and _compatible(row, record) and (
                    row.stable_evidence and row.stable_evidence == record.stable_evidence
                    or any(row.coverage.comparable(cov, row.fields["occurred_at"]) for cov in record.coverages)
                )
                for row in members for record in candidates
            )
        if comparable and len(members) == len(candidates) == 1:
            results[members[0].occurrence_key] = _matched(members[0], candidates[0])
            continue
        if comparable and _unchanged_assignment(members, candidates):
            for row in members:
                results[row.occurrence_key] = SourceResolution(row.occurrence_key, "duplicate")
            continue
        # 同内容且所有旧对象都无需更新：增加的次数才有等价的新增结果。
        uniform = (
            comparable and len(members) >= len(candidates)
            and all(row.fields == members[0].fields for row in members)
            and all((row.provider_id, row.stable_evidence) == (members[0].provider_id, members[0].stable_evidence) for row in members)
            and all(not _difference(members[0], record)[1] for record in candidates)
        )
        if uniform:
            candidates.sort(key=lambda record: record.transaction_id)
            changed = any(_difference(members[0], record)[0] for record in candidates)
            for index, row in enumerate(members):
                if changed and index < len(candidates):
                    results[row.occurrence_key] = _matched(row, candidates[index])
                else:
                    results[row.occurrence_key] = SourceResolution(row.occurrence_key, "duplicate" if index < len(candidates) else "new", bind=index >= len(candidates))
            continue
        ids = tuple(sorted(record.transaction_id for record in candidates))
        for row in members:
            results[row.occurrence_key] = SourceResolution(row.occurrence_key, "pending", candidate_ids=ids, reason="source_identity_ambiguous")
    return [results[row.occurrence_key] for row in rows]


def allocate_source_record_id(row: SourceOccurrence) -> str:
    """计划新增时分配一次；无编号身份不随之后的来源内容变化。"""
    if row.provider_binding_key:
        return "provider-v1:" + row.provider_binding_key
    return "import-v1:" + str(uuid4())
