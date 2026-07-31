"""收支投影的关系型只读查询。"""
from __future__ import annotations
from contextlib import contextmanager
from decimal import Decimal
from sqlalchemy import and_, func, or_, select
from sqlalchemy.exc import SQLAlchemyError
from ft.adapters.relational.dialect import RelationalEngineError
from ft.adapters.relational.models import AccountModel, CashProjectionMemberModel, CashProjectionModel, CashProjectionRelationModel, CashProjectionStateModel, CashTransactionModel, TransactionRelationModel
from ft.adapters.relational.runtime import StorageError, storage_error
from ft.application.web_queries import CashAccountDTO, ProjectionDTO, ProjectionUnavailableError, ProjectionUpdatedError, shanghai_bounds

def _amount(value):
    amount = Decimal(value).normalize()
    return "0" if amount.is_zero() else format(amount, "f")


_SOURCE_SNAPSHOT_KEYS = frozenset({
    "merchant", "store", "channel", "transaction_type", "amount", "currency", "occurred_at",
})
_RELATION_EVIDENCE_KEYS = frozenset({
    "rule_id", "amount_match", "time_distance_minutes", "anchor_role",
})


def _safe_snapshot(payload):
    if not isinstance(payload, dict):
        return None
    return {
        key: value for key, value in payload.items()
        if key in _SOURCE_SNAPSHOT_KEYS
        and isinstance(value, (str, int, float, bool))
        and len(str(value)) <= 160
    }


def _safe_relation_evidence(payload):
    if not isinstance(payload, dict):
        return {}
    return {
        key: value for key, value in payload.items()
        if key in _RELATION_EVIDENCE_KEYS
        and isinstance(value, (str, int, float, bool))
        and len(str(value)) <= 160
    }


def _record_summary(row, account):
    if row is None or account is None:
        return None
    return {
        "id": str(row.id), "occurred_at": row.occurred_at.isoformat(),
        "account": {"id": account.id, "name": account.name, "type": account.type, "active": account.active},
        "counterparty": row.counterparty, "category": row.category, "note": row.note,
        "amount": _amount(row.amount), "currency": row.currency,
        "source_type": row.source_type, "record_id": row.record_id,
    }


class RelationalCashLedgerQueryRepository:
    def __init__(self, sessions, workspace_id): self._sessions, self._workspace_id = sessions, workspace_id

    def _storage_error(self, exc):
        if isinstance(exc, RelationalEngineError):
            return StorageError(exc.code)
        return storage_error(exc, str(self._sessions.kw["bind"].url))

    @contextmanager
    def _session(self):
        try:
            with self._sessions() as session: yield session
        except (SQLAlchemyError, RelationalEngineError) as exc:
            raise self._storage_error(exc) from exc

    @contextmanager
    def _evidence_snapshot(self):
        try:
            with self._sessions() as session:
                with session.begin():
                    if session.bind.dialect.name == "postgresql":
                        session.connection().exec_driver_sql("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
                    elif session.bind.dialect.name == "sqlite":
                        session.connection().exec_driver_sql("BEGIN")
                    yield session
        except (SQLAlchemyError, RelationalEngineError) as exc:
            raise self._storage_error(exc) from exc
    def list_accounts(self):
        with self._session() as s: rows=s.scalars(select(AccountModel).where(AccountModel.workspace_id==self._workspace_id, AccountModel.type.in_(("cash","loan","lend"))).order_by(AccountModel.id)).all()
        return tuple(CashAccountDTO(x.id,x.name,x.type,x.active) for x in rows)
    def _active(self, s):
        state=s.scalar(select(CashProjectionStateModel).where(CashProjectionStateModel.workspace_id==self._workspace_id))
        if state is None or state.availability != "ready" or not state.active_dataset_id: raise ProjectionUnavailableError()
        return state
    def active_version(self):
        with self._session() as s: return self._active(s).projection_version
    def _dto(self, row, account, relations):
        kinds=tuple(sorted({r.kind for r in relations})); summary=tuple({"kind":kind,"subtype":subtype,"count":sum(r.kind==kind and r.subtype==subtype for r in relations)} for kind,subtype in sorted({(r.kind,r.subtype) for r in relations}))
        return ProjectionDTO(row.projection_id,row.occurred_at.isoformat(),CashAccountDTO(account.id,account.name,account.type,account.active),row.counterparty,row.category,row.note,_amount(row.net_amount),row.currency,row.economic_type,row.transfer_subtype,kinds,row.member_count,summary,row.source_type,row.record_id,row.visible,row.hidden_reason)
    def list_projection_page(self, filters, cursor, limit):
        from ft.application.web_queries import _decode
        with self._session() as s:
            cursor_version, position = (None, None)
            if cursor:
                cursor_version, *position = _decode(cursor, self._workspace_id, filters)
                position = tuple(position)
            state=select(
                CashProjectionStateModel.projection_version.label("projection_version"),
                CashProjectionStateModel.active_dataset_id.label("active_dataset_id"),
                CashProjectionStateModel.availability.label("availability"),
            ).where(CashProjectionStateModel.workspace_id==self._workspace_id).cte("active_state")
            conditions=[
                CashProjectionModel.workspace_id==self._workspace_id,
                CashProjectionModel.dataset_id==state.c.active_dataset_id,
                CashProjectionModel.visible.is_(True),
                CashProjectionModel.economic_type.in_(("expense","income")),
            ]
            if cursor_version is not None: conditions.append(state.c.projection_version==cursor_version)
            start,end=shanghai_bounds(filters)
            if start:conditions.append(CashProjectionModel.occurred_at>=start)
            if end:conditions.append(CashProjectionModel.occurred_at<end)
            for field in ("account_id","category","currency","economic_type"):
                value=getattr(filters,field)
                if value is not None:conditions.append(getattr(CashProjectionModel,field)==value)
            if filters.counterparty:conditions.append(CashProjectionModel.counterparty.contains(filters.counterparty))
            if filters.amount_min:
                conditions.append(
                    func.decimal_compare(CashProjectionModel.net_amount, filters.amount_min) >= 0
                    if s.bind.dialect.name == "sqlite"
                    else CashProjectionModel.net_amount >= Decimal(filters.amount_min)
                )
            if filters.amount_max:
                conditions.append(
                    func.decimal_compare(CashProjectionModel.net_amount, filters.amount_max) <= 0
                    if s.bind.dialect.name == "sqlite"
                    else CashProjectionModel.net_amount <= Decimal(filters.amount_max)
                )
            if filters.composition=="single":conditions.extend((~CashProjectionModel.has_payment_mirror,~CashProjectionModel.has_refund_offset,~CashProjectionModel.has_transfer_pair))
            elif filters.composition=="payment_mirror":conditions.append(CashProjectionModel.has_payment_mirror)
            elif filters.composition=="refund_offset":conditions.append(CashProjectionModel.has_refund_offset)
            elif filters.composition=="combined":conditions.append(or_(and_(CashProjectionModel.has_payment_mirror,CashProjectionModel.has_refund_offset),and_(CashProjectionModel.has_payment_mirror,CashProjectionModel.has_transfer_pair),and_(CashProjectionModel.has_refund_offset,CashProjectionModel.has_transfer_pair)))
            if position:conditions.append(or_(CashProjectionModel.occurred_at<position[0],and_(CashProjectionModel.occurred_at==position[0],CashProjectionModel.projection_id<position[1])))
            page=select(
                state.c.projection_version,
                state.c.active_dataset_id,
                state.c.availability,
                CashProjectionModel.id.label("projection_row_id"),
            ).select_from(
                state.outerjoin(CashProjectionModel,and_(*conditions))
            ).order_by(CashProjectionModel.occurred_at.desc(),CashProjectionModel.projection_id.desc()).limit(limit).cte("projection_page")
            q=select(
                page.c.projection_version,
                page.c.active_dataset_id,
                page.c.availability,
                CashProjectionModel,
                AccountModel,
                CashProjectionRelationModel,
            ).select_from(
                page.outerjoin(CashProjectionModel,CashProjectionModel.id==page.c.projection_row_id).outerjoin(
                    AccountModel,
                    and_(AccountModel.workspace_id==CashProjectionModel.workspace_id,AccountModel.id==CashProjectionModel.account_id),
                ).outerjoin(
                    CashProjectionRelationModel,
                    and_(
                        CashProjectionRelationModel.dataset_id==page.c.active_dataset_id,
                        CashProjectionRelationModel.projection_row_id==CashProjectionModel.id,
                    ),
                )
            ).order_by(CashProjectionModel.occurred_at.desc(),CashProjectionModel.projection_id.desc(),CashProjectionRelationModel.ordinal)
            result=s.execute(q).all()
            if not result: raise ProjectionUnavailableError()
            version,dataset_id,availability,_,_,_=result[0]
            if availability != "ready" or not dataset_id: raise ProjectionUnavailableError()
            if cursor_version is not None and cursor_version != version: raise ProjectionUpdatedError()
            rows=[]; by={}
            for _,_,_,row,account,relation in result:
                if row is None: continue
                if row.id not in by:
                    rows.append((row,account))
                    by[row.id]=[]
                if relation is not None: by[row.id].append(relation)
            return version, [self._dto(row,account,by[row.id]) for row,account in rows]
    def get_evidence(self, projection_id):
        with self._evidence_snapshot() as s:
            state=self._active(s); row=s.scalar(select(CashProjectionModel).where(CashProjectionModel.workspace_id==self._workspace_id,CashProjectionModel.dataset_id==state.active_dataset_id,CashProjectionModel.projection_id==projection_id))
            if row is None: raise LookupError(projection_id)
            account=s.scalar(select(AccountModel).where(AccountModel.workspace_id==self._workspace_id,AccountModel.id==row.account_id))
            rels=s.scalars(select(CashProjectionRelationModel).where(CashProjectionRelationModel.projection_row_id==row.id).order_by(CashProjectionRelationModel.ordinal)).all()
            members=s.execute(
                select(CashProjectionMemberModel, CashTransactionModel, AccountModel)
                .join(CashTransactionModel, and_(CashTransactionModel.workspace_id==CashProjectionMemberModel.workspace_id, CashTransactionModel.id==CashProjectionMemberModel.cash_transaction_id))
                .join(AccountModel, and_(AccountModel.workspace_id==CashTransactionModel.workspace_id, AccountModel.id==CashTransactionModel.account_id))
                .where(CashProjectionMemberModel.projection_row_id==row.id)
                .order_by(CashProjectionMemberModel.ordinal)
            ).all()
            member_ids = [cash.id for _, cash, _ in members]
            member_rows = {cash.id: (cash, member_account) for _, cash, member_account in members}
            root, root_account = member_rows[row.root_cash_transaction_id]
            accepted_by_id = {
                relation.id: relation for relation in s.scalars(
                    select(TransactionRelationModel).where(
                        TransactionRelationModel.workspace_id == self._workspace_id,
                        TransactionRelationModel.id.in_([relation.transaction_relation_id for relation in rels]),
                    )
                ).all()
            }
            inactive = s.scalars(
                select(TransactionRelationModel).where(
                    TransactionRelationModel.workspace_id == self._workspace_id,
                    TransactionRelationModel.status.in_(("pending_review", "rejected", "superseded")),
                    TransactionRelationModel.primary_fact_type == "cash",
                    or_(TransactionRelationModel.secondary_fact_id.is_(None), TransactionRelationModel.secondary_fact_type == "cash"),
                    or_(TransactionRelationModel.primary_fact_id.in_(member_ids), TransactionRelationModel.secondary_fact_id.in_(member_ids)),
                ).order_by(TransactionRelationModel.status, TransactionRelationModel.id)
            ).all()
            endpoint_ids = sorted({
                endpoint for relation in (*inactive, *accepted_by_id.values()) for endpoint in (relation.primary_fact_id, relation.secondary_fact_id)
                if endpoint is not None
            })
            endpoints = s.execute(
                select(CashTransactionModel, AccountModel)
                .join(AccountModel, and_(AccountModel.workspace_id == CashTransactionModel.workspace_id, AccountModel.id == CashTransactionModel.account_id))
                .where(CashTransactionModel.workspace_id == self._workspace_id, CashTransactionModel.id.in_(endpoint_ids))
            ).all() if endpoint_ids else []
            endpoint_rows = {cash.id: (cash, endpoint_account) for cash, endpoint_account in endpoints}
            root_record = _record_summary(root, root_account)
            assert root_record is not None
            root_record["source_snapshot"] = _safe_snapshot(root.source_payload)
            return {
                "projection_version": state.projection_version,
                "projection": self._dto(row, account, rels),
                "root_record": root_record,
                "members": [
                    {**_record_summary(cash, member_account), "roles": list(member.roles_json)}
                    for member, cash, member_account in members
                ],
                "accepted_relations": [
                    {
                        "id": str(relation.transaction_relation_id), "kind": relation.kind, "subtype": relation.subtype,
                        "rule_id": accepted_by_id[relation.transaction_relation_id].rule_id if relation.transaction_relation_id in accepted_by_id else "",
                        "confidence": accepted_by_id[relation.transaction_relation_id].confidence if relation.transaction_relation_id in accepted_by_id else "",
                        "evidence": _safe_relation_evidence(accepted_by_id[relation.transaction_relation_id].evidence_json) if relation.transaction_relation_id in accepted_by_id else {},
                        "primary_record": _record_summary(*endpoint_rows[accepted_by_id[relation.transaction_relation_id].primary_fact_id]) if relation.transaction_relation_id in accepted_by_id and accepted_by_id[relation.transaction_relation_id].primary_fact_id in endpoint_rows else None,
                        "secondary_record": _record_summary(*endpoint_rows[accepted_by_id[relation.transaction_relation_id].secondary_fact_id]) if relation.transaction_relation_id in accepted_by_id and accepted_by_id[relation.transaction_relation_id].secondary_fact_id in endpoint_rows else None,
                    }
                    for relation in rels
                ],
                "inactive_relation_hints": [
                    {
                        "id": str(relation.id), "kind": relation.kind, "subtype": relation.subtype, "status": relation.status,
                        "primary_record": _record_summary(*endpoint_rows[relation.primary_fact_id]) if relation.primary_fact_id in endpoint_rows else None,
                        "secondary_record": _record_summary(*endpoint_rows[relation.secondary_fact_id]) if relation.secondary_fact_id in endpoint_rows else None,
                    }
                    for relation in inactive
                ],
                "refund_timeline": [
                    {"record_id": cash.record_id, "occurred_at": cash.occurred_at.isoformat(), "amount": _amount(cash.amount), "currency": cash.currency, "source_type": cash.source_type}
                    for member, cash, _ in members if "refund" in member.roles_json
                ],
            }
