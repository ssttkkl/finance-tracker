"""人工选择导入流水；复用共同硬约束及完整图校验。"""
from copy import deepcopy
from dataclasses import asdict
from datetime import datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

from sqlalchemy import and_, func, or_, select

from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.models import CashInvestmentFundingRelationModel, CashTransactionComponentModel, CashTransactionModel
from ft.application.cash_import_business_plan import _virtual
from ft.application.cash_import_operations import _wire
from ft.application.cash_record_operations import CashRecordMaintenanceError, _identifier
from ft.domain.cash_record_policy import CashRecordPolicyError, COMMON_CASH_FIELDS, _normalize
from ft.domain.cash_relation_graph import CashRelationGraphEdge
from ft.domain.cash_relation_policy import CashRelationError, RelationEndpoint, RelationRoleEvidence, infer_relation_roles, TRANSFER_IN_TYPES, TRANSFER_OUT_TYPES
from ft.domain.cash_source_matching import _digest
from ft.domain.decimal import exact_decimal


def association_targets(service, transaction_id, *, kind=None, query="", account_id=None, amount=None,
        date_from=None, date_to=None, timezone_name="UTC", cursor=None, limit=20):
    identifier = _identifier(transaction_id)
    try:
        if type(limit) is not int or not 1 <= limit <= 100 or not isinstance(query, str) or len(query) > 256:
            raise ValueError
        if kind not in {None, "payment_mirror", "refund_offset", "transfer_pair"}:
            raise ValueError
        after = _identifier(cursor) if cursor is not None else 0
        account = _identifier(account_id) if account_id is not None else None
        value = exact_decimal(amount).copy_abs() if amount is not None else None
        zone = ZoneInfo(timezone_name)
        start = datetime.combine(datetime.fromisoformat(date_from).date(), time.min, zone).astimezone(timezone.utc) if date_from else None
        end = datetime.combine(datetime.fromisoformat(date_to).date() + timedelta(days=1), time.min, zone).astimezone(timezone.utc) if date_to else None
        if start and end and start >= end:
            raise ValueError
    except (ValueError, TypeError, KeyError):
        raise CashRecordMaintenanceError("record_input_invalid") from None
    with service._uow as uow:
        session = uow._state().session
        service._authorize(session, write=False)
        source = service._record(session, identifier)
        if source.record_origin not in {"import", "legacy"} or source.state != "posted":
            raise CashRecordPolicyError("relation_endpoint_incompatible", ())
        excluded = CurrentCashRelationRepository(session, service._workspace_id).confirmed_closure({identifier})
        model = CashTransactionModel
        statement = select(model).where(model.workspace_id == service._workspace_id,
            model.deleted_at.is_(None), model.state == "posted", model.record_origin.in_(("import", "legacy")),
            model.id > after, model.id.not_in(excluded)).order_by(model.id)
        if kind is not None:
            statement = statement.where(_role_target_condition(session, source, model, kind))
        if query.strip():
            statement = statement.where(or_(*(column.contains(query.strip(), autoescape=True)
                for column in (model.counterparty, model.note, model.record_id))))
        if account is not None:
            statement = statement.where(select(CashTransactionComponentModel.id).where(
                CashTransactionComponentModel.workspace_id == service._workspace_id,
                CashTransactionComponentModel.cash_transaction_id == model.id,
                CashTransactionComponentModel.account_id == account).exists())
        if value is not None:
            from ft.adapters.relational.cash_association_search import magnitude_matches
            statement = statement.where(magnitude_matches(session, model.amount, value))
        if start:
            statement = statement.where(model.occurred_at >= start)
        if end:
            statement = statement.where(model.occurred_at < end)
        selected = []
        scan_after = after
        batch_size = max(32, limit + 1)
        while len(selected) <= limit:
            rows = list(session.scalars(statement.where(model.id > scan_after).limit(batch_size)))
            if not rows:
                break
            parts = {}
            ids = [identifier, *(row.id for row in rows)]
            for part in session.scalars(select(CashTransactionComponentModel).where(
                CashTransactionComponentModel.workspace_id == service._workspace_id,
                CashTransactionComponentModel.cash_transaction_id.in_(ids)
            ).order_by(CashTransactionComponentModel.ordinal)):
                parts.setdefault(part.cash_transaction_id, []).append(part)
            funded_parents = set(session.scalars(select(CashTransactionComponentModel.cash_transaction_id).join(
                CashInvestmentFundingRelationModel,
                and_(CashInvestmentFundingRelationModel.workspace_id == service._workspace_id,
                    CashInvestmentFundingRelationModel.cash_transaction_component_id == CashTransactionComponentModel.id,
                    CashInvestmentFundingRelationModel.status == 'accepted',
                    CashInvestmentFundingRelationModel.active_slot == 'active')).where(
                CashTransactionComponentModel.workspace_id == service._workspace_id,
                CashTransactionComponentModel.cash_transaction_id.in_(ids))))
            for row in rows:
                choices = _component_choices(source, row, parts, kind, funded_parents)
                if choices['component_choices']:
                    selected.append((row, choices))
                    if len(selected) > limit:
                        break
            scan_after = rows[-1].id
            if len(rows) < batch_size:
                break
        items = [{"transaction_id": str(row.id), "row_revision": row.row_revision,
            "record_origin": row.record_origin, "state": row.state, "confirmation_status": "confirmed" if row.state == "posted" else "pending",
            "entry": None, "fields": _wire(service._fields(session, row)), "editable_fields": [],
            "issues": [], "relations": [], "projection_ref": None,
            **choices} for row, choices in selected[:limit]]
        return {"kind": kind, "items": items, "next_cursor": str(selected[limit - 1][0].id) if len(selected) > limit else None}


def _role_target_condition(session, source, model, kind):
    positive = func.decimal_compare(model.amount, '0') > 0 if session.bind.dialect.name == 'sqlite' else model.amount > 0
    negative = func.decimal_compare(model.amount, '0') < 0 if session.bind.dialect.name == 'sqlite' else model.amount < 0
    is_out = source.amount is not None and source.amount < 0
    if source.amount is None:
        return False
    if source.amount == 0:
        zero = func.decimal_compare(model.amount, '0') == 0 if session.bind.dialect.name == 'sqlite' else model.amount == 0
        if kind != 'refund_offset' or source.record_type not in {'consumption', 'refund', 'investment_out'}:
            return False
        return and_(zero, model.record_type.in_(('consumption', 'refund', 'investment_out') if source.record_type == 'refund' else ('refund',)))
    if kind == 'transfer_pair':
        valid = source.record_type in (TRANSFER_OUT_TYPES if is_out else TRANSFER_IN_TYPES)
        return and_(valid, model.record_type.in_(TRANSFER_IN_TYPES if is_out else TRANSFER_OUT_TYPES), positive if is_out else negative)
    if kind == 'refund_offset':
        if source.record_type == 'transfer_reversal':
            return and_(model.record_type == 'transfer_reversal', positive if is_out else negative)
        if is_out and source.record_type in {'consumption', 'refund', 'investment_out'}:
            return and_(model.record_type == 'refund', positive)
        return and_(source.record_type == 'refund', model.record_type.in_(('consumption', 'refund', 'investment_out')), negative)
    valid = source.record_type in ({'consumption', 'refund'} if is_out else {'refund'})
    return and_(valid, model.record_type.in_(('consumption', 'refund') if is_out else ('refund',)), negative if is_out else positive)


def _component_choices(source, target, parts, kind, funded_parents):
    if source.id in funded_parents or target.id in funded_parents:
        return {'component_choices': [], 'unavailable_reason': 'relation_endpoint_occupied'}
    kinds = (kind,) if kind is not None else ('payment_mirror', 'refund_offset', 'transfer_pair')
    choices = []
    for first in parts.get(source.id, ()):
        for second in parts.get(target.id, ()):
            endpoints = tuple(RelationEndpoint(str(part.id), str(parent.id), part.amount, part.currency,
                str(part.account_id) if part.account_id is not None else None, parent.record_type, parent.record_subtype)
                for parent, part in ((source, first), (target, second)))
            for relation_kind in kinds:
                if relation_kind == 'transfer_pair' and any(parent.cash_granularity != 'atomic' for parent in (source, target)):
                    continue
                primary, secondary = _ordered_endpoints(relation_kind, *endpoints)
                if primary.amount == secondary.amount == 0 and (source.amount != 0 or target.amount != 0):
                    continue
                try:
                    infer_relation_roles(relation_kind, primary, secondary)
                except CashRelationError:
                    continue
                choices.append({'anchor_component_id': str(first.id), 'target_component_id': str(second.id)})
                break
    return {'component_choices': choices, 'unavailable_reason': None if choices else 'relation_endpoint_incompatible'}


def _ordered_endpoints(kind, primary, secondary):
    if kind in {'refund_offset', 'transfer_pair'} and (
        primary.amount is not None and primary.amount > 0
        or kind == 'refund_offset' and primary.amount == secondary.amount == 0
        and primary.record_type == 'refund' and secondary.record_type != 'refund'
    ):
        return secondary, primary
    return primary, secondary


def validate_additions(value):
    if not isinstance(value, list) or len(value) > 1:
        raise CashRecordMaintenanceError("plan_input_invalid")
    fields = {"target_transaction_id", "expected_target_revision", "anchor_component_id", "target_component_id", "kind", "common_fields"}
    result = []
    for item in value:
        if not isinstance(item, dict) or item.keys() - fields or fields - {"common_fields"} - item.keys():
            raise CashRecordMaintenanceError("plan_input_invalid")
        normalized = deepcopy(item)
        for field in ("target_transaction_id", "anchor_component_id", "target_component_id"):
            normalized[field] = str(_identifier(item[field], code="plan_input_invalid"))
        if (type(item["expected_target_revision"]) is not int or not 1 <= item["expected_target_revision"] < 2 ** 63
            or item["kind"] not in {"payment_mirror", "refund_offset", "transfer_pair"}
            or not isinstance(item.get("common_fields", {}), dict)):
            raise CashRecordMaintenanceError("plan_input_invalid")
        result.append(normalized)
    return result


def manual_groups(snapshot, planner, source_id, additions):
    groups = []
    for item in additions:
        target_id = item["target_transaction_id"]
        first, second = snapshot.values.get(str(source_id)), snapshot.values.get(target_id)
        versions = dict(snapshot.context.transaction_revisions)
        if str(source_id) == target_id or first is None or second is None:
            raise CashRecordPolicyError("relation_endpoint_incompatible", ())
        if versions.get(int(target_id)) != item["expected_target_revision"]:
            raise CashRecordMaintenanceError("record_changed")
        for parent in (str(source_id), target_id):
            fact = snapshot.facts[parent]
            if fact.state != "posted":
                raise CashRecordPolicyError("relation_endpoint_incompatible", ())
        primary, secondary = (snapshot.components.get(item[key]) for key in ("anchor_component_id", "target_component_id"))
        if primary is None or secondary is None or primary.transaction_id != str(source_id) or secondary.transaction_id != target_id:
            raise CashRecordPolicyError("relation_endpoint_incompatible", ())
        primary, secondary = _ordered_endpoints(item['kind'], primary, secondary)
        edge = CashRelationGraphEdge(_virtual("manual-relation", item["kind"], primary.component_id, secondary.component_id),
            item["kind"], primary.transaction_id, secondary.transaction_id, primary.component_id, secondary.component_id,
            None, None, state="confirmed")
        try:
            proof = RelationRoleEvidence()
            roles = infer_relation_roles(edge.kind, primary, secondary)
        except CashRelationError as error:
            raise CashRecordPolicyError(error.code, ()) from None
        from dataclasses import replace
        edge = replace(edge, primary_role=roles[0], secondary_role=roles[1])
        parents = sorted({primary.transaction_id, secondary.transaction_id})
        if item.get("common_fields", {}).keys() - COMMON_CASH_FIELDS:
            raise CashRecordPolicyError("field_not_editable", ())
        for field, value in item.get("common_fields", {}).items():
            normalized = _normalize(field, value)
            allowed = [snapshot.entry_public.get(parent, {}).get("values", snapshot.values[parent]).get(field) for parent in parents]
            clear = [] if field == "counterparty_account_attrs" else None if field == "category_id" else ""
            if normalized != clear and normalized not in allowed:
                raise CashRecordPolicyError("common_field_conflict", (field,))
        suggestion = "sg_" + _digest("manual-association-v1", item, snapshot.context.token)[:40]
        groups.append({"suggestion_id": suggestion, "origin": "manual", "action": "accept",
            "edges": [asdict(edge)], "evidence": {str(edge.id): asdict(proof)}, "anchors": {str(edge.id): primary.component_id},
            "parent_ids": parents, "member_refs": [{"transaction_id": parent} for parent in parents],
            "allowed_actions": ["accept"], "candidates": [], "candidate_id": None,
            **({"common_fields": deepcopy(item["common_fields"])} if "common_fields" in item else {})})
    return groups
