"""稳定收支对象的当前公共属性与成员；不保存历史版本。"""
from copy import deepcopy
from uuid import uuid4

from sqlalchemy import and_, delete, select

from ft.adapters.relational.models import CashEntryMemberModel, CashEntryModel, CashTransactionModel
from ft.domain.cash_record_policy import COMMON_CASH_FIELDS, CashRecordPolicyError, _normalize
from ft.domain.cash_source_matching import _digest


def public_values(row):
    return {field: deepcopy(getattr(row, field)) for field in sorted(COMMON_CASH_FIELDS)}


class CashEntryRepository:
    def __init__(self, session, workspace_id):
        self.session, self.workspace_id = session, workspace_id

    def for_transactions(self, identifiers):
        identifiers = list({int(value) for value in identifiers})
        if not identifiers:
            return {}, {}
        if len(identifiers) <= 400 and self.session.autoflush:
            rows = list(self.session.execute(select(CashEntryMemberModel, CashEntryModel).outerjoin(CashEntryModel, and_(
                CashEntryModel.workspace_id == self.workspace_id, CashEntryModel.id == CashEntryMemberModel.entry_id)).where(
                    CashEntryMemberModel.workspace_id == self.workspace_id,
                    CashEntryMemberModel.transaction_id.in_(identifiers))))
            members = {member.transaction_id: member for member, _ in rows}
            if all(entry is not None and member.entry_id == entry.id for member, entry in rows):
                return members, {entry.id: entry for _, entry in rows}
        else:
            members = {}
            for offset in range(0, len(identifiers), 400):
                members.update((row.transaction_id, row) for row in self.session.scalars(select(CashEntryMemberModel).where(
                    CashEntryMemberModel.workspace_id == self.workspace_id,
                    CashEntryMemberModel.transaction_id.in_(identifiers[offset:offset + 400]))))
        entries = {}
        wanted = list({member.entry_id for member in members.values()})
        for offset in range(0, len(wanted), 400):
            entries.update((row.id, row) for row in self.session.scalars(select(CashEntryModel).where(
                CashEntryModel.workspace_id == self.workspace_id, CashEntryModel.id.in_(wanted[offset:offset + 400]))))
        return members, entries

    def snapshot(self, identifiers):
        members, entries = self.for_transactions(identifiers)
        return self.snapshot_values(members, entries)

    @staticmethod
    def snapshot_values(members, entries):
        return tuple(sorted((member.transaction_id, member.entry_id, entries[member.entry_id].revision,
            _digest(public_values(entries[member.entry_id]), entries[member.entry_id].manual_fields, entries[member.entry_id].identity_transaction_id,
                member.standalone_values, member.standalone_manual_fields)) for member in members.values()))

    def current(self, identifier):
        members, entries = self.for_transactions({identifier})
        member = members.get(int(identifier))
        return entries.get(member.entry_id) if member else None

    def split_field_diffs(self, projections):
        projections = tuple(projections)
        ids = {int(value) for projection in projections for value in projection.member_ids}
        members, entries = self.for_transactions(ids)
        old_groups = {}
        for member in members.values():
            old_groups.setdefault(member.entry_id, set()).add(member.transaction_id)
        diffs = []
        for projection in projections:
            group = {int(value) for value in projection.member_ids}
            root = int(projection.primary_record.id)
            source = self.session.get(CashTransactionModel, root)
            restored = public_values(source)
            member = members.get(root)
            if member:
                restored.update({field: deepcopy(member.standalone_values[field]) for field in member.standalone_manual_fields})
            for entry_id in {members[value].entry_id for value in group if value in members}:
                if not old_groups[entry_id] - group:
                    continue
                entry = entries[entry_id]
                for field in sorted(entry.manual_fields):
                    current = getattr(entry, field)
                    if current != restored[field]:
                        diffs.append({"field": field, "current": deepcopy(current), "restored": deepcopy(restored[field]),
                            "member_refs": [{"transaction_id": str(value)} for value in sorted(group)]})
        return diffs

    def patch(self, identifier, patch, *, expected_revision):
        entry = self.current(identifier)
        if entry is None or entry.revision != expected_revision:
            raise CashRecordPolicyError('entry_changed', ())
        if not isinstance(patch, dict) or patch.keys() - COMMON_CASH_FIELDS:
            raise CashRecordPolicyError('field_not_editable', ())
        values = {field: _normalize(field, value) for field, value in patch.items()}
        changed = any(getattr(entry, field) != value or field not in entry.manual_fields for field, value in values.items())
        for field, value in values.items():
            setattr(entry, field, deepcopy(value))
        if changed:
            entry.manual_fields = sorted(set(entry.manual_fields) | values.keys())
            entry.revision += 1
            members = list(self.session.scalars(select(CashEntryMemberModel).where(
                CashEntryMemberModel.workspace_id == self.workspace_id, CashEntryMemberModel.entry_id == entry.id)))
            if len(members) == 1:
                member = members[0]
                member.standalone_values = {**member.standalone_values, **deepcopy(values)}
                member.standalone_manual_fields = sorted(set(member.standalone_manual_fields) | values.keys())
        return entry

    def apply_edit(self, identifier, edit):
        """首次导入可创建公共属性；既有对象仍严格校验修订。"""
        entry = self.current(identifier)
        if entry is None:
            if edit["revision"] is not None:
                raise CashRecordPolicyError('entry_changed', ())
            from ft.adapters.relational.projections import RelationalCashProjectionRepository
            from ft.domain.cash_projection import build_cash_projections
            source = self.session.get(CashTransactionModel, int(identifier))
            if source is None or source.workspace_id != self.workspace_id:
                raise CashRecordPolicyError('entry_changed', ())
            fact = RelationalCashProjectionRepository(self.session, self.workspace_id)._fact_from_model(source)
            self.reconcile(build_cash_projections((fact,), ()).projections)
            entry = self.current(identifier)
            revision = entry.revision
        else:
            revision = edit['revision']
        return self.patch(identifier, edit['patch'], expected_revision=revision)

    def reconcile(self, projections):
        projections = tuple(projections)
        identifiers = {int(member.id) for projection in projections for member, _ in projection.members}
        members, entries = self.for_transactions(identifiers)
        sources = {}
        ids = list(identifiers)
        for offset in range(0, len(ids), 400):
            sources.update((row.id, row) for row in self.session.scalars(select(CashTransactionModel).where(
                CashTransactionModel.workspace_id == self.workspace_id, CashTransactionModel.id.in_(ids[offset:offset + 400]))))
        old_members = {}
        for member in members.values():
            old_members.setdefault(member.entry_id, set()).add(member.transaction_id)
        # 身份锚所在的拆分结果先保留旧对象；展示代表不能抢占身份。
        anchors = {entry.identity_transaction_id for entry in entries.values()}
        projections = sorted(projections, key=lambda p: (not anchors.intersection(p.member_ids), p.projection_id))
        used, result = set(), {}
        for projection in projections:
            group = {int(member.id) for member, _ in projection.members}
            root = int(projection.primary_record.id)
            old_ids = {members[item].entry_id for item in group if item in members}
            eligible = [entries[entry_id] for entry_id in old_ids if entry_id not in used
                and (entries[entry_id].identity_transaction_id in group
                    or entries[entry_id].identity_transaction_id not in identifiers)]
            economic_members = set(projection.economic_root_ids or (root,))
            eligible.sort(key=lambda entry: (entry.identity_transaction_id not in economic_members, entry.id))
            candidate = eligible[0] if eligible else None
            values = public_values(sources[root])
            human = {}
            for entry_id in old_ids:
                old = entries[entry_id]
                split = bool(old_members[entry_id] - group)
                if split and old.split_inherit is False:
                    for member_id in group & old_members[entry_id]:
                        member = members[member_id]
                        for field in member.standalone_manual_fields:
                            # 拆分的展示基准负责公共值，成员恢复值始终单独保留。
                            if member_id == root:
                                human[field] = deepcopy(member.standalone_values[field])
                    continue
                for field in old.manual_fields:
                    value = deepcopy(getattr(old, field))
                    if field in human and human[field] != value:
                        raise CashRecordPolicyError('common_field_conflict', (field,))
                    human[field] = value
            if candidate is None:
                anchor = self._new_identity_anchor(projection)
                candidate = CashEntryModel(id=str(uuid4()), workspace_id=self.workspace_id, root_transaction_id=root, identity_transaction_id=anchor,
                    revision=1, manual_fields=[])
                self.session.add(candidate)
            else:
                if candidate.root_transaction_id != root or old_members.get(candidate.id) != group:
                    candidate.revision += 1
                if candidate.root_transaction_id != root:
                    candidate.root_transaction_id = root
                if candidate.identity_transaction_id not in group:
                    candidate.identity_transaction_id = self._new_identity_anchor(projection)
            for member_id in group:
                source = sources[member_id]
                member = members.get(member_id)
                if member is None:
                    member = CashEntryMemberModel(workspace_id=self.workspace_id, transaction_id=member_id,
                        entry_id=candidate.id, standalone_values=public_values(source),
                        standalone_manual_fields=sorted(set(source.manual_overrides or {}) & COMMON_CASH_FIELDS))
                    members[member_id] = member
                    self.session.add(member)
                    if member_id == root:
                        human.update({field: member.standalone_values[field] for field in member.standalone_manual_fields
                            if field not in human})
                else:
                    standalone = deepcopy(member.standalone_values)
                    for field in COMMON_CASH_FIELDS - set(member.standalone_manual_fields):
                        standalone[field] = deepcopy(getattr(source, field))
                    if member.standalone_values != standalone:
                        member.standalone_values = standalone
                    if member.entry_id != candidate.id:
                        member.entry_id = candidate.id
                if old_ids and any(entries[item].split_inherit is True and old_members[item] - group for item in old_ids):
                    member.standalone_values = {**member.standalone_values, **deepcopy(human)}
                    member.standalone_manual_fields = sorted(set(member.standalone_manual_fields) | human.keys())
            values.update(human)
            if any(getattr(candidate, field) != value for field, value in values.items()) and candidate.id in entries:
                candidate.revision += 1
            for field, value in values.items():
                if getattr(candidate, field) != value:
                    setattr(candidate, field, deepcopy(value))
            if candidate.manual_fields != sorted(human):
                candidate.manual_fields = sorted(human)
            used.add(candidate.id)
            result[projection.projection_id] = candidate
        # 两阶段：先落对象，再调整成员，最后删除无当前成员的对象。
        pending = self.session.new
        fresh = [entry for entry in result.values() if entry in pending]
        if fresh:
            self.session.flush(fresh)
        self.session.flush()
        for entry in entries.values():
            if entry.split_inherit is not None:
                entry.split_inherit = None
        self.session.flush()
        obsolete = set(entries) - used
        if obsolete:
            self.session.execute(delete(CashEntryModel).where(CashEntryModel.workspace_id == self.workspace_id,
                CashEntryModel.id.in_(obsolete), ~select(CashEntryMemberModel.transaction_id).where(
                    CashEntryMemberModel.workspace_id == self.workspace_id, CashEntryMemberModel.entry_id == CashEntryModel.id).exists()))
        return result

    @staticmethod
    def _new_identity_anchor(projection):
        from ft.domain.cash_projection import CashProjectionError, source_identity_key
        facts = tuple(fact for fact, _ in projection.members)
        if len(facts) == 1:
            return facts[0].id
        keys = {fact.id: source_identity_key(fact) for fact in facts}
        if len(set(keys.values())) != len(keys):
            raise CashProjectionError('projection.source_identity_duplicate')
        return min(keys, key=keys.__getitem__)

    def choose_common(self, identifiers, values):
        """关系保存点内写入明确公共决定，保留成员独立恢复值。"""
        from ft.adapters.relational.models import CashCategoryModel
        from ft.adapters.relational.projections import RelationalCashProjectionRepository
        from ft.domain.cash_projection import build_cash_projections
        values = {field: _normalize(field, value) for field, value in values.items() if field in COMMON_CASH_FIELDS}
        if values.get('category_id') is not None and self.session.scalar(select(CashCategoryModel.id).where(
            CashCategoryModel.workspace_id == self.workspace_id, CashCategoryModel.id == values['category_id'])) is None:
            raise CashRecordPolicyError('category_unavailable', ('category_id',))
        if not values:
            return
        for identifier in {int(item) for item in identifiers}:
            entry = self.current(identifier)
            if entry is None:
                source = self.session.get(CashTransactionModel, identifier)
                if source is None or source.workspace_id != self.workspace_id:
                    raise CashRecordPolicyError('entry_changed', ())
                fact = RelationalCashProjectionRepository(self.session, self.workspace_id)._fact_from_model(source)
                self.reconcile(build_cash_projections((fact,), ()).projections)
                entry = self.current(identifier)
            for field, value in values.items():
                setattr(entry, field, deepcopy(value))
            entry.manual_fields = sorted(set(entry.manual_fields) | values.keys())
            entry.revision += 1
        self.session.flush()

    def prepare_split(self, identifiers, inherit):
        _, entries = self.for_transactions(identifiers)
        for entry in entries.values():
            if entry.manual_fields and type(inherit) is not bool:
                raise CashRecordPolicyError('inheritance_required', ())
            entry.split_inherit = inherit if type(inherit) is bool else False
        self.session.flush()

    def remove_members(self, identifiers):
        members, entries = self.for_transactions(identifiers)
        for member in members.values():
            self.session.delete(member)
        for entry in entries.values():
            entry.revision += 1
        self.session.flush()
        if entries:
            self.session.execute(delete(CashEntryModel).where(CashEntryModel.workspace_id == self.workspace_id,
                CashEntryModel.id.in_(entries), ~select(CashEntryMemberModel.transaction_id).where(
                    CashEntryMemberModel.workspace_id == self.workspace_id, CashEntryMemberModel.entry_id == CashEntryModel.id).exists()))
