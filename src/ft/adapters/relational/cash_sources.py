"""来源绑定和当前问题存储；调用方持有工作区写锁并管理保存点。"""
from __future__ import annotations

from dataclasses import asdict
from collections import defaultdict
import re

from sqlalchemy import and_, delete, func, or_, select, tuple_

from ft.domain.cash_record_policy import CashRecordPolicyError, prepare_cash_record_update
from ft.domain.cash_source_matching import SourceBindingKey, SourceCoverage, SourceOccurrence, SourceRecord, provider_identity_key
from ft.domain.cash_source_scope import WECHAT_DEFAULT_SCOPE
from .models import (
    CashIssueCandidateModel, CashSourceBindingModel,
    CashTransactionIssueModel, CashTransactionModel,
    CashTransactionComponentModel,
)


class SourceBindingConflict(ValueError):
    """稳定原因码，不携带账号、来源内容或数据库异常正文。"""

    def __init__(self, code):
        self.code = code
        super().__init__(code)


def _json_object(value):
    return prepare_cash_record_update({"state": "draft"}, {"source_payload": value}).values.get("source_payload")


def _identifier(value):
    if type(value) is str and value.isascii() and value.isdecimal():
        value = int(value)
    if type(value) is not int or not 0 < value < 2 ** 63:
        raise SourceBindingConflict("source_target_unavailable")
    return value


def _legacy_wechat_provider(parent):
    payload = parent.source_payload
    raw = payload.get("交易单号") if isinstance(payload, dict) else None
    if (parent.source_type == "wechat" and isinstance(raw, str) and raw.strip() not in {"", "/", "-", "--"}
        and len(raw.strip()) <= 512 and parent.record_id == "wechat_" + raw):
        return raw.strip()
    return None


def _identity_parts(target, bindings):
    scopes = {binding.source_scope_key for binding in bindings if binding.source_scope_kind == "account"}
    providers = {binding.identity_key for binding in bindings if binding.identity_kind == "provider_id"}
    evidence = {binding.stable_evidence_key for binding in bindings if binding.stable_evidence_key}
    legacy = _legacy_wechat_provider(target) if not scopes else None
    if legacy:
        scopes.add(WECHAT_DEFAULT_SCOPE)
        providers.add(provider_identity_key(legacy))
    return scopes, providers, evidence


# 与 Python str.strip 使用同一空白集合，兼容旧读取器保留的原始交易号。
_ID_WHITESPACE = " \t\r\n\v\f\x1c\x1d\x1e\x1f\x85\xa0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000"


class CashSourceRepository:
    def __init__(self, session, workspace_id: str):
        self.session = session
        self.workspace_id = workspace_id

    def _target(self, transaction_id):
        transaction_id = _identifier(transaction_id)
        target = self.session.scalar(select(CashTransactionModel).where(
            CashTransactionModel.workspace_id == self.workspace_id,
            CashTransactionModel.id == transaction_id, CashTransactionModel.deleted_at.is_(None),
        ))
        if target is None:
            raise SourceBindingConflict("source_target_unavailable")
        return target

    def _for_occurrence(self, row: SourceOccurrence):
        return self._for_occurrences([row])

    def _for_occurrences(self, rows):
        keys = sorted({(spec.source_type, spec.scope_kind, spec.scope_key, spec.identity_kind, spec.identity_key) for row in rows for spec in row.binding_specs})
        evidence_keys = sorted({row.stable_evidence_binding_key for row in rows if row.stable_evidence_binding_key})
        result = []
        for offset in range(0, max(len(keys), len(evidence_keys)), 150):
            result.extend(self.session.scalars(select(CashSourceBindingModel).where(
                CashSourceBindingModel.workspace_id == self.workspace_id,
                or_(tuple_(CashSourceBindingModel.source_type, CashSourceBindingModel.source_scope_kind,
                       CashSourceBindingModel.source_scope_key, CashSourceBindingModel.identity_kind,
                       CashSourceBindingModel.identity_key).in_(keys[offset:offset + 150]),
                    CashSourceBindingModel.stable_evidence_key.in_(evidence_keys[offset:offset + 150])),
            )))
        return list({binding.id: binding for binding in result}.values())

    def find(self, row: SourceOccurrence) -> int | None:
        ids = {binding.transaction_id for binding in self._for_occurrence(row)}
        if not ids:
            return None
        active = self.session.scalars(select(CashTransactionModel.id).where(
            CashTransactionModel.workspace_id == self.workspace_id,
            CashTransactionModel.id.in_(ids), CashTransactionModel.deleted_at.is_(None),
        )).all()
        if len(active) > 1:
            raise SourceBindingConflict("source_binding_ambiguous")
        if active:
            self._validate_scope(self._target(active[0]), row)
            return active[0]
        return None

    def bindings_for(self, transaction_id):
        transaction_id = _identifier(transaction_id)
        return self.session.scalars(select(CashSourceBindingModel).where(
            CashSourceBindingModel.workspace_id == self.workspace_id,
            CashSourceBindingModel.transaction_id == transaction_id,
        ).order_by(CashSourceBindingModel.id)).all()

    def _validate_scope(self, target, row):
        bindings = self.bindings_for(target.id)
        scopes, providers, evidence = _identity_parts(target, bindings)
        incoming_provider = next((spec.identity_key for spec in row.binding_specs if spec.identity_kind == "provider_id"), None)
        if target.source_type != row.source_type or row.source_account_key and scopes and scopes != {row.source_account_key} or incoming_provider and providers and incoming_provider not in providers or row.stable_evidence_binding_key and evidence and row.stable_evidence_binding_key not in evidence:
            raise SourceBindingConflict("source_identity_conflict")

    def records_for_file(self, rows):
        """只读直接绑定及同时间候选；不以内容散列或内部账户建立来源身份。"""
        if not rows:
            return []
        from datetime import datetime
        direct_ids = sorted({binding.transaction_id for binding in self._for_occurrences(rows)})
        legacy_numbers = sorted({row.provider_id for row in rows
            if row.source_type == "wechat" and row.source_account_key == WECHAT_DEFAULT_SCOPE and row.provider_id})
        times = set()
        for row in rows:
            value = row.fields.get("occurred_at")
            try:
                when = value if isinstance(value, datetime) else datetime.fromisoformat(str(value).replace("Z", "+00:00"))
                if when.tzinfo is not None:
                    times.add(when)
            except (ValueError, TypeError):
                pass
        # 旧数据的未知账户候选也读取，规划器会保留真实碰撞待处理，不能复制正式组。
        times = sorted(times)
        by_id = {}
        for offset in range(0, max(len(direct_ids), len(times), len(legacy_numbers)), 250):
            for parent in self.session.scalars(select(CashTransactionModel).where(
                CashTransactionModel.workspace_id == self.workspace_id, CashTransactionModel.deleted_at.is_(None),
                CashTransactionModel.source_type.in_({row.source_type for row in rows}),
                or_(CashTransactionModel.id.in_(direct_ids[offset:offset + 250]), CashTransactionModel.occurred_at.in_(times[offset:offset + 250]),
                    and_(CashTransactionModel.source_type == "wechat", CashTransactionModel.record_id.startswith("wechat_", autoescape=True),
                        func.trim(func.substr(CashTransactionModel.record_id, 8), _ID_WHITESPACE).in_(legacy_numbers[offset:offset + 250]))),
            )):
                by_id[parent.id] = parent
        parents = list(by_id.values())
        if not parents:
            return []
        ids = [parent.id for parent in parents]
        bindings, children = defaultdict(list), defaultdict(list)
        for offset in range(0, len(ids), 400):
            for binding in self.session.scalars(select(CashSourceBindingModel).where(
                CashSourceBindingModel.workspace_id == self.workspace_id, CashSourceBindingModel.transaction_id.in_(ids[offset:offset + 400]),
            )):
                bindings[binding.transaction_id].append(binding)
            for child in self.session.scalars(select(CashTransactionComponentModel).where(
                CashTransactionComponentModel.workspace_id == self.workspace_id, CashTransactionComponentModel.cash_transaction_id.in_(ids[offset:offset + 400]),
            ).order_by(CashTransactionComponentModel.ordinal)):
                children[child.cash_transaction_id].append(child)
        result = []
        for parent in parents:
            aliases = bindings[parent.id]
            scopes = {binding.source_scope_key for binding in aliases if binding.source_scope_kind == "account"}
            if len(scopes) > 1:
                raise SourceBindingConflict("source_binding_ambiguous")
            keys = [SourceBindingKey(binding.source_type, binding.source_scope_kind, binding.source_scope_key, binding.identity_kind, binding.identity_key) for binding in aliases]
            fields = {name: getattr(parent, name) for name in (
                "state", "amount", "occurred_at", "currency", "account_id", "cash_granularity",
                "counterparty", "counterparty_account", "counterparty_account_attrs", "note", "category_id",
                "record_type", "record_subtype", "source_payload",
            )}
            fields["components"] = [{"component_id": child.id, "account_id": child.account_id, "amount": child.amount, "currency": child.currency, "ordinal": child.ordinal} for child in children[parent.id]]
            legacy_provider = _legacy_wechat_provider(parent) if not scopes else None
            result.append(SourceRecord(
                transaction_id=str(parent.id), source_type=parent.source_type,
                source_account_key=next(iter(scopes), WECHAT_DEFAULT_SCOPE if legacy_provider else None), fields=fields,
                provider_id=legacy_provider,
                coverages=tuple(SourceCoverage(**binding.coverage) for binding in aliases),
                binding_keys=frozenset(key.key for key in keys),
                provider_binding_key=next((key.key for key in keys if key.identity_kind == "provider_id"), None),
                stable_evidence_binding_key=next((binding.stable_evidence_key for binding in aliases if binding.stable_evidence_key), None),
                row_revision=parent.row_revision,
                protected_fields=frozenset(name for name in ("note", "category_id") if (parent.manual_overrides or {}).get(name)),
            ))
        return result

    def save_occurrence(self, transaction_id, row: SourceOccurrence, *, uncertain=False):
        target = self._target(transaction_id)
        if target.source_type != row.source_type or uncertain and target.state != "draft":
            raise SourceBindingConflict("source_target_incompatible")
        self._validate_scope(target, row)
        specs = [spec for spec in row.binding_specs if not uncertain or spec.scope_kind == "file_local"]
        existing = {
            (binding.source_type, binding.source_scope_kind, binding.source_scope_key, binding.identity_kind, binding.identity_key): binding
            for binding in self._for_occurrence(row)
        }
        # 在修改任何一个别名之前校验全部现有活动归属。
        for binding in existing.values():
            if binding.transaction_id != target.id:
                previous = self.session.get(CashTransactionModel, binding.transaction_id)
                if previous is not None and previous.deleted_at is None:
                    raise SourceBindingConflict("active_source_binding")
        try:
            coverage = _json_object(asdict(row.coverage))
        except (CashRecordPolicyError, TypeError, ValueError):
            raise SourceBindingConflict("invalid_source_coverage") from None
        for spec in specs:
            key = (spec.source_type, spec.scope_kind, spec.scope_key, spec.identity_kind, spec.identity_key)
            binding = existing.get(key)
            strength = "file_local" if spec.scope_kind == "file_local" else "provider" if spec.identity_kind == "provider_id" else "validated"
            if binding is None:
                binding = CashSourceBindingModel(
                    workspace_id=self.workspace_id, transaction_id=target.id,
                    source_type=spec.source_type, source_scope_kind=spec.scope_kind,
                    source_scope_key=spec.scope_key, identity_kind=spec.identity_kind,
                    identity_key=spec.identity_key, identity_strength=strength,
                    strategy_version=row.parser_version, coverage=coverage,
                    stable_evidence_key=row.stable_evidence_binding_key if not uncertain and spec.scope_kind == "account" else None,
                )
                self.session.add(binding)
            else:
                binding.transaction_id = target.id
                binding.identity_strength = strength
                binding.strategy_version = row.parser_version
                binding.coverage = coverage
                if not uncertain and spec.scope_kind == "account" and row.stable_evidence_binding_key:
                    binding.stable_evidence_key = row.stable_evidence_binding_key
        self.session.flush()

    def issues_for(self, transaction_id):
        transaction_id = _identifier(transaction_id)
        return self.session.scalars(select(CashTransactionIssueModel).where(
            CashTransactionIssueModel.workspace_id == self.workspace_id,
            CashTransactionIssueModel.transaction_id == transaction_id,
        ).order_by(CashTransactionIssueModel.id)).all()

    def save_issue(self, transaction_id, issue_key, *, code, field_path=None,
                   pending_source_payload=None, pending_fields=None, origin=None, candidates=None):
        transaction_id = self._target(transaction_id).id
        try:
            if not isinstance(code, str) or re.fullmatch(r"[a-z][a-z0-9_]{0,63}", code) is None or not isinstance(issue_key, str) or not issue_key or len(issue_key) > 128:
                raise ValueError("invalid issue identity")
            payload = _json_object(pending_source_payload)
            fields = _json_object(pending_fields)
            location = _json_object(origin or {})
            if set(location) - {"file_digest", "table_key", "position", "source_type", "source_scope_kind", "source_scope_key", "parser_version"}:
                raise ValueError("invalid origin")
        except (CashRecordPolicyError, TypeError, ValueError):
            raise SourceBindingConflict("invalid_source_issue") from None
        normalized_candidates = {}
        for candidate_id, revision in (candidates or {}).items():
            candidate = self._target(candidate_id)
            if candidate.id == transaction_id or type(revision) is not int or revision != candidate.row_revision:
                raise SourceBindingConflict("source_candidate_changed")
            normalized_candidates[candidate.id] = revision
        candidates = normalized_candidates
        issue = self.session.scalar(select(CashTransactionIssueModel).where(
            CashTransactionIssueModel.workspace_id == self.workspace_id,
            CashTransactionIssueModel.transaction_id == transaction_id,
            CashTransactionIssueModel.issue_key == issue_key,
        ))
        if issue is None:
            issue = CashTransactionIssueModel(workspace_id=self.workspace_id, transaction_id=transaction_id, issue_key=issue_key, code=code)
            self.session.add(issue)
        else:
            previous_candidates = {row.candidate_transaction_id: row.expected_row_revision for row in self.session.scalars(select(CashIssueCandidateModel).where(
                CashIssueCandidateModel.workspace_id == self.workspace_id,
                CashIssueCandidateModel.issue_id == issue.id,
            ))}
            if previous_candidates != candidates:
                # 子表变化也改变用户确认的内容；与父字段一起只递增一次。
                issue.revision += 1
        issue.code, issue.field_path = code, field_path
        issue.pending_source_payload, issue.pending_fields, issue.origin = payload, fields, location
        self.session.flush()
        self.session.execute(delete(CashIssueCandidateModel).where(
            CashIssueCandidateModel.workspace_id == self.workspace_id,
            CashIssueCandidateModel.issue_id == issue.id,
        ))
        self.session.add_all(CashIssueCandidateModel(
            workspace_id=self.workspace_id, issue_id=issue.id,
            candidate_transaction_id=candidate_id, expected_row_revision=revision,
        ) for candidate_id, revision in candidates.items())
        self.session.flush()
        return issue

    def clear_issue(self, transaction_id, issue_key):
        transaction_id = _identifier(transaction_id)
        self.session.execute(delete(CashTransactionIssueModel).where(
            CashTransactionIssueModel.workspace_id == self.workspace_id,
            CashTransactionIssueModel.transaction_id == transaction_id,
            CashTransactionIssueModel.issue_key == issue_key,
        ))
        self.session.flush()

    def transfer_draft_bindings(self, draft_id, target_id):
        draft, target = self._target(draft_id), self._target(target_id)
        if draft.state != "draft" or draft.id == target.id or draft.source_type != target.source_type:
            raise SourceBindingConflict("source_merge_requires_draft")
        draft_bindings, target_bindings = self.bindings_for(draft.id), self.bindings_for(target.id)
        draft_identity, target_identity = _identity_parts(draft, draft_bindings), _identity_parts(target, target_bindings)
        scopes, providers, evidence = (left | right for left, right in zip(draft_identity, target_identity))
        if len(scopes) > 1 or len(providers) > 1 or len(evidence) > 1:
            raise SourceBindingConflict("source_identity_conflict")
        for binding in draft_bindings:
            binding.transaction_id = target.id
        self.session.flush()
