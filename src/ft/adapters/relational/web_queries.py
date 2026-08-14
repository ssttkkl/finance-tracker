"""收支投影的关系型只读查询。"""
from __future__ import annotations
from contextlib import contextmanager
from datetime import timezone
from decimal import Decimal
from sqlalchemy import and_, func, or_, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import aliased
from ft.adapters.relational.dialect import RelationalEngineError
from ft.adapters.relational.models import AccountModel, CashCategoryModel, CashInvestmentFundingRelationModel, CashProjectionMemberModel, CashProjectionModel, CashProjectionRelationModel, CashProjectionStateModel, CashTransactionModel, InvestmentEventModel, TransactionRelationModel
from ft.adapters.relational.runtime import StorageError, storage_error
from ft.application.web_queries import CashAccountDTO, CashAccountSummaryDTO, CashCategoryDTO, CashCategoryPathItemDTO, CashEconomicTypeFilterOptionDTO, CashFilterOptionsDTO, CashMonthlyCurrencySummaryDTO, CashMonthlySummaryDTO, CashTransferDTO, ProjectionDTO, ProjectionUnavailableError, ProjectionUpdatedError, local_bounds

def _amount(value):
    amount = Decimal(value).normalize()
    return "0" if amount.is_zero() else format(amount, "f")


_SOURCE_SNAPSHOT_KEYS = frozenset({
    "merchant", "store", "channel", "transaction_type", "amount", "currency", "occurred_at",
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


_FUNDING_MATCH_KEYS = ["amount", "currency", "direction", "business_day"]
_FUNDING_INSTITUTION_MATCH_KEYS = ["institution_name", "direction", "business_day"]
_FUNDING_CASH_RECORD_TYPES = frozenset({
    "investment_in", "investment_out", "transfer_in", "transfer_out",
})


def _safe_funding_evidence(payload):
    if not isinstance(payload, dict):
        return {}
    result = {}
    window = payload.get("business_day_window")
    if type(window) is int and 0 <= window <= 7:
        result["business_day_window"] = window
    candidate_count = payload.get("candidate_count")
    if type(candidate_count) is int and 1 <= candidate_count <= 100_000:
        result["candidate_count"] = candidate_count
    record_type = payload.get("cash_record_type")
    if record_type in _FUNDING_CASH_RECORD_TYPES:
        result["cash_record_type"] = record_type
    match_keys = payload.get("match_keys")
    if match_keys in (_FUNDING_MATCH_KEYS, _FUNDING_INSTITUTION_MATCH_KEYS):
        result["match_keys"] = list(match_keys)
    return result


def _category_dto(categories: dict[str, CashCategoryModel], category_id: str | None):
    if not category_id or category_id not in categories:
        return None
    current = categories[category_id]
    path = []
    while current is not None:
        path.append(CashCategoryPathItemDTO(current.id, current.name))
        current = categories.get(current.parent_id) if current.parent_id else None
    path.reverse()
    return CashCategoryDTO(categories[category_id].id, categories[category_id].name, tuple(path))


def _category_rows(session, workspace_id: str) -> dict[str, CashCategoryModel]:
    return {
        row.id: row for row in session.scalars(select(CashCategoryModel).where(
            CashCategoryModel.workspace_id == workspace_id,
        )).all()
    }


def _record_summary(row, account, categories=None):
    if row is None or account is None:
        return None
    return {
        "id": str(row.id), "occurred_at": row.occurred_at.isoformat(),
        "account": {"id": account.id, "name": account.name, "type": account.type, "active": account.active},
        "account_name": account.name, "account_id": account.id, "account_type": account.type,
        "counterparty": row.counterparty, "category": _category_dto(categories or {}, row.category_id), "note": row.note,
        "amount": _amount(row.amount), "currency": row.currency,
        "source_type": row.source_type,
        "counterparty_account": row.counterparty_account or "",
        "record_type": row.record_type, "record_subtype": row.record_subtype or "not_applicable",
    }


class RelationalCashLedgerQueryRepository:
    def __init__(self, sessions, workspace_id):
        self._sessions, self._workspace_id = sessions, workspace_id
        self._filter_options_cache = {}
        self._monthly_summary_cache = {}

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
        return tuple(
            CashAccountDTO(x.id, x.name, x.type, x.active, tuple(x.currencies or ()))
            for x in rows
        )
    def _active(self, s):
        state=s.scalar(select(CashProjectionStateModel).where(CashProjectionStateModel.workspace_id==self._workspace_id))
        if state is None or state.availability != "ready" or not state.active_dataset_id: raise ProjectionUnavailableError()
        return state
    def active_version(self):
        with self._session() as s: return self._active(s).projection_version
    def _dto(self, row, account, relations, source_types=(), transfer=None, categories=None):
        kinds=tuple(sorted({r.kind for r in relations})); summary=tuple({"kind":kind,"subtype":subtype,"count":sum(r.kind==kind and r.subtype==subtype for r in relations)} for kind,subtype in sorted({(r.kind,r.subtype) for r in relations}))
        return ProjectionDTO(row.projection_id,row.occurred_at.isoformat(),CashAccountSummaryDTO(account.id,account.name,account.type,account.active),row.counterparty,_category_dto(categories or {}, row.category_id),row.note,_amount(row.net_amount),row.currency,row.economic_type,row.transfer_subtype,kinds,row.member_count,summary,row.source_type,tuple(source_types),row.record_id,row.visible,row.hidden_reason,transfer)
    def _member_source_types(self, session, dataset_id, projection_row_ids):
        projection_row_ids = tuple(projection_row_ids)
        source_types = {projection_row_id: [] for projection_row_id in projection_row_ids}
        if not source_types:
            return source_types
        rows = session.execute(
            select(CashProjectionMemberModel.projection_row_id, CashTransactionModel.source_type)
            .join(
                CashTransactionModel,
                and_(
                    CashTransactionModel.workspace_id == CashProjectionMemberModel.workspace_id,
                    CashTransactionModel.id == CashProjectionMemberModel.cash_transaction_id,
                ),
            ).where(
                CashProjectionMemberModel.workspace_id == self._workspace_id,
                CashProjectionMemberModel.dataset_id == dataset_id,
                CashProjectionMemberModel.projection_row_id.in_(source_types),
            ).order_by(CashProjectionMemberModel.projection_row_id, CashProjectionMemberModel.ordinal)
        )
        for projection_row_id, source_type in rows:
            if source_type and source_type not in source_types[projection_row_id]:
                source_types[projection_row_id].append(source_type)
        return source_types
    def _transfer_details(self, session, dataset_id, projection_row_ids):
        projection_row_ids = tuple(projection_row_ids)
        if not projection_row_ids:
            return {}
        relation_rows = session.execute(
            select(
                CashProjectionRelationModel.projection_row_id,
                TransactionRelationModel.primary_fact_id,
                TransactionRelationModel.secondary_fact_id,
            ).join(
                TransactionRelationModel,
                and_(
                    TransactionRelationModel.workspace_id == CashProjectionRelationModel.workspace_id,
                    TransactionRelationModel.id == CashProjectionRelationModel.transaction_relation_id,
                ),
            ).where(
                CashProjectionRelationModel.workspace_id == self._workspace_id,
                CashProjectionRelationModel.dataset_id == dataset_id,
                CashProjectionRelationModel.projection_row_id.in_(projection_row_ids),
                CashProjectionRelationModel.kind == "transfer_pair",
            ).order_by(CashProjectionRelationModel.projection_row_id, CashProjectionRelationModel.ordinal)
        ).all()
        endpoint_ids = sorted({endpoint for _row_id, primary_id, secondary_id in relation_rows for endpoint in (primary_id, secondary_id)})
        endpoint_rows = session.execute(
            select(CashTransactionModel, AccountModel).join(
                AccountModel,
                and_(AccountModel.workspace_id == CashTransactionModel.workspace_id, AccountModel.id == CashTransactionModel.account_id),
            ).where(
                CashTransactionModel.workspace_id == self._workspace_id,
                CashTransactionModel.id.in_(endpoint_ids),
            )
        ).all() if endpoint_ids else []
        endpoints = {cash.id: (cash, account) for cash, account in endpoint_rows}
        transfers = {}
        for projection_row_id, primary_id, secondary_id in relation_rows:
            if projection_row_id in transfers or primary_id not in endpoints or secondary_id not in endpoints:
                continue
            primary, primary_account = endpoints[primary_id]
            secondary, secondary_account = endpoints[secondary_id]
            transfers[projection_row_id] = CashTransferDTO(
                CashAccountSummaryDTO(primary_account.id, primary_account.name, primary_account.type, primary_account.active),
                _amount(primary.amount), primary.currency,
                CashAccountSummaryDTO(secondary_account.id, secondary_account.name, secondary_account.type, secondary_account.active),
                _amount(secondary.amount), secondary.currency,
            )
        investment_account = aliased(AccountModel)
        funding_rows = session.execute(
            select(
                CashProjectionModel.id,
                CashInvestmentFundingRelationModel.direction,
                CashTransactionModel,
                AccountModel,
                InvestmentEventModel,
                investment_account,
            ).join(
                CashInvestmentFundingRelationModel,
                and_(
                    CashInvestmentFundingRelationModel.workspace_id == CashProjectionModel.workspace_id,
                    CashInvestmentFundingRelationModel.id == CashProjectionModel.funding_relation_id,
                    CashInvestmentFundingRelationModel.status == "accepted",
                    CashInvestmentFundingRelationModel.active_slot == "active",
                ),
            ).join(
                CashTransactionModel,
                and_(
                    CashTransactionModel.workspace_id == CashInvestmentFundingRelationModel.workspace_id,
                    CashTransactionModel.id == CashInvestmentFundingRelationModel.cash_transaction_id,
                ),
            ).join(
                AccountModel,
                and_(
                    AccountModel.workspace_id == CashTransactionModel.workspace_id,
                    AccountModel.id == CashTransactionModel.account_id,
                ),
            ).join(
                InvestmentEventModel,
                and_(
                    InvestmentEventModel.workspace_id == CashInvestmentFundingRelationModel.workspace_id,
                    InvestmentEventModel.id == CashInvestmentFundingRelationModel.investment_event_id,
                ),
            ).join(
                investment_account,
                and_(
                    investment_account.workspace_id == InvestmentEventModel.workspace_id,
                    investment_account.id == InvestmentEventModel.account_id,
                ),
            ).where(
                CashProjectionModel.workspace_id == self._workspace_id,
                CashProjectionModel.dataset_id == dataset_id,
                CashProjectionModel.id.in_(projection_row_ids),
                CashProjectionModel.economic_type == "internal_transfer",
                CashProjectionModel.transfer_subtype == "bank_security_transfer",
            )
        ).all()
        for projection_row_id, direction, cash, cash_account, investment, investment_account_row in funding_rows:
            if projection_row_id in transfers:
                continue
            investment_amount = investment.to_amount if direction == "cash_to_investment" else investment.from_amount
            if investment_amount is None:
                continue
            cash_dto = CashAccountSummaryDTO(
                cash_account.id, cash_account.name, cash_account.type, cash_account.active,
            )
            investment_dto = CashAccountSummaryDTO(
                investment_account_row.id, investment_account_row.name,
                investment_account_row.type, investment_account_row.active,
            )
            if direction == "cash_to_investment":
                transfers[projection_row_id] = CashTransferDTO(
                    cash_dto, _amount(cash.amount), cash.currency,
                    investment_dto, _amount(investment_amount), investment.currency,
                )
            elif direction == "investment_to_cash":
                transfers[projection_row_id] = CashTransferDTO(
                    investment_dto, _amount(investment_amount), investment.currency,
                    cash_dto, _amount(cash.amount), cash.currency,
                )
        return transfers
    def _filter_options(self, session, dataset_id, *, version=None, categories=None):
        cache_key = (dataset_id, version)
        cached = self._filter_options_cache.get(cache_key)
        if cached is not None:
            return cached
        values = session.execute(
            select(CashProjectionModel.category_id, CashProjectionModel.currency, CashProjectionModel.economic_type, CashProjectionModel.transfer_subtype).where(
                CashProjectionModel.workspace_id == self._workspace_id,
                CashProjectionModel.dataset_id == dataset_id,
                CashProjectionModel.visible.is_(True),
            ).distinct()
        ).all()
        category_rows = categories or _category_rows(session, self._workspace_id)
        category_options = tuple(
            _category_dto(category_rows, row.id)
            for row in sorted(category_rows.values(), key=lambda item: (item.category_path, item.sort_order, item.id))
        )
        category_options = tuple(item for item in category_options if item is not None)
        currencies = tuple(sorted({str(currency).strip().upper() for _category, currency, _economic_type, _subtype in values if currency and str(currency).strip()}))
        economic_types = {}
        for _category, _currency, economic_type, subtype in values:
            economic = str(economic_type).strip() if economic_type else ""
            if not economic:
                continue
            subtypes = economic_types.setdefault(economic, set())
            if subtype and str(subtype).strip():
                subtypes.add(str(subtype).strip())
        result = CashFilterOptionsDTO(
            category_options,
            currencies,
            tuple(
                CashEconomicTypeFilterOptionDTO(economic_type, tuple(sorted(subtypes)))
                for economic_type, subtypes in sorted(economic_types.items())
            ),
        )
        self._filter_options_cache[cache_key] = result
        if len(self._filter_options_cache) > 16:
            self._filter_options_cache.pop(next(iter(self._filter_options_cache)))
        return result
    @staticmethod
    def _monthly_summaries(rows, timezone_name):
        from zoneinfo import ZoneInfo

        zone = ZoneInfo(timezone_name)
        months = set()
        totals = {}
        for occurred_at, economic_type, amount, currency in rows:
            if occurred_at is None:
                continue
            local_time = occurred_at.astimezone(zone) if occurred_at.tzinfo else occurred_at.replace(tzinfo=timezone.utc).astimezone(zone)
            month = local_time.strftime("%Y-%m")
            months.add(month)
            if economic_type not in ("expense", "income") or not currency:
                continue
            key = (month, str(currency).strip().upper())
            income, expense = totals.get(key, (Decimal("0"), Decimal("0")))
            if economic_type == "income":
                income += Decimal(amount)
            else:
                expense += Decimal(amount)
            totals[key] = (income, expense)
        return tuple(
            CashMonthlySummaryDTO(
                month,
                tuple(
                    CashMonthlyCurrencySummaryDTO(currency, _amount(totals[(month, currency)][0]), _amount(totals[(month, currency)][1]))
                    for _month, currency in sorted(totals) if _month == month
                ),
            )
            for month in sorted(months, reverse=True)
        )
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
            filter_conditions=[
                CashProjectionModel.workspace_id==self._workspace_id,
                CashProjectionModel.visible.is_(True),
            ]
            start,end=local_bounds(filters)
            if start:filter_conditions.append(CashProjectionModel.occurred_at>=start)
            if end:filter_conditions.append(CashProjectionModel.occurred_at<end)
            for field in ("account_id","currency"):
                value=getattr(filters,field)
                if value is not None:filter_conditions.append(getattr(CashProjectionModel,field)==value)
            categories = _category_rows(s, self._workspace_id) if filters.category_id is not None else None
            if filters.category_id is not None:
                selected = categories.get(filters.category_id)
                if selected is None:
                    raise ValueError("invalid_filter")
                filter_conditions.append(CashProjectionModel.category_path.like(f"{selected.category_path}%"))
            elif filters.uncategorized:
                filter_conditions.append(CashProjectionModel.category_id.is_(None))
            if filters.economic_type is not None:
                filter_conditions.append(CashProjectionModel.economic_type == filters.economic_type)
            if filters.transfer_subtype is not None:
                filter_conditions.append(CashProjectionModel.transfer_subtype == filters.transfer_subtype)
            if filters.counterparty:
                filter_conditions.append(or_(
                    CashProjectionModel.counterparty.contains(filters.counterparty),
                    CashProjectionModel.note.contains(filters.counterparty),
                ))
            if filters.amount_min:
                filter_conditions.append(
                    func.decimal_compare(CashProjectionModel.net_amount, filters.amount_min) >= 0
                    if s.bind.dialect.name == "sqlite"
                    else CashProjectionModel.net_amount >= Decimal(filters.amount_min)
                )
            if filters.amount_max:
                filter_conditions.append(
                    func.decimal_compare(CashProjectionModel.net_amount, filters.amount_max) <= 0
                    if s.bind.dialect.name == "sqlite"
                    else CashProjectionModel.net_amount <= Decimal(filters.amount_max)
                )
            if filters.composition=="single":filter_conditions.extend((~CashProjectionModel.has_payment_mirror,~CashProjectionModel.has_refund_offset,~CashProjectionModel.has_transfer_pair))
            elif filters.composition=="payment_mirror":filter_conditions.append(CashProjectionModel.has_payment_mirror)
            elif filters.composition=="refund_offset":filter_conditions.append(CashProjectionModel.has_refund_offset)
            elif filters.composition=="combined":filter_conditions.append(or_(and_(CashProjectionModel.has_payment_mirror,CashProjectionModel.has_refund_offset),and_(CashProjectionModel.has_payment_mirror,CashProjectionModel.has_transfer_pair),and_(CashProjectionModel.has_refund_offset,CashProjectionModel.has_transfer_pair)))
            conditions = [CashProjectionModel.dataset_id==state.c.active_dataset_id, *filter_conditions]
            if cursor_version is not None: conditions.append(state.c.projection_version==cursor_version)
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
            if not result:
                # A ready dataset may legitimately contain no visible rows.
                # The outer join has no row to carry the state in that case, so
                # read the state explicitly instead of treating an empty
                # workspace as an unavailable projection.
                empty_state = s.scalar(
                    select(CashProjectionStateModel).where(
                        CashProjectionStateModel.workspace_id == self._workspace_id,
                    )
                )
                if empty_state is None or empty_state.availability != "ready" or not empty_state.active_dataset_id:
                    raise ProjectionUnavailableError()
                if cursor_version is not None and cursor_version != empty_state.projection_version:
                    raise ProjectionUpdatedError()
                return (
                    empty_state.projection_version,
                    [],
                    self._filter_options(
                        s, empty_state.active_dataset_id,
                        version=empty_state.projection_version, categories={},
                    ),
                    (),
                )
            version,dataset_id,availability,_,_,_=result[0]
            if availability != "ready" or not dataset_id: raise ProjectionUnavailableError()
            if cursor_version is not None and cursor_version != version: raise ProjectionUpdatedError()
            summary_key = (
                dataset_id,
                version,
                tuple(sorted(filters.as_cursor_data().items())),
            )
            monthly_summaries = self._monthly_summary_cache.get(summary_key)
            if monthly_summaries is None:
                summary_rows = s.execute(
                    select(CashProjectionModel.occurred_at, CashProjectionModel.economic_type, CashProjectionModel.net_amount, CashProjectionModel.currency).where(
                        CashProjectionModel.dataset_id == dataset_id,
                        *filter_conditions,
                    )
                ).all()
                monthly_summaries = self._monthly_summaries(summary_rows, filters.timezone)
                self._monthly_summary_cache[summary_key] = monthly_summaries
                if len(self._monthly_summary_cache) > 16:
                    self._monthly_summary_cache.pop(next(iter(self._monthly_summary_cache)))
            rows=[]; by={}
            for _,_,_,row,account,relation in result:
                if row is None: continue
                if row.id not in by:
                    rows.append((row,account))
                    by[row.id]=[]
                if relation is not None: by[row.id].append(relation)
            transfer_row_ids = [
                row.id for row, _account in rows
                if row.has_transfer_pair or row.funding_relation_id is not None
            ]
            transfer_details = self._transfer_details(s, dataset_id, transfer_row_ids) if transfer_row_ids else {}
            source_types = {
                row.id: ([row.source_type] if row.member_count == 1 and row.source_type else [])
                for row, _account in rows
            }
            member_row_ids = [row.id for row, _account in rows if row.member_count != 1]
            if member_row_ids:
                source_types.update(self._member_source_types(s, dataset_id, member_row_ids))
            if categories is None:
                category_ids = {row.category_id for row, _account in rows if row.category_id}
                categories = _category_rows(s, self._workspace_id) if category_ids else {}
            return version, [self._dto(row,account,by[row.id],source_types[row.id],transfer_details.get(row.id),categories) for row,account in rows], self._filter_options(s, dataset_id, version=version, categories=categories), monthly_summaries
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
            category_ids = {
                cash.category_id
                for cash, _account in (*member_rows.values(), *endpoint_rows.values())
                if cash.category_id
            }
            categories = _category_rows(s, self._workspace_id) if category_ids else {}
            root_record = _record_summary(root, root_account, categories)
            assert root_record is not None
            source_types = tuple(dict.fromkeys(
                cash.source_type for _member, cash, _member_account in members if cash.source_type
            ))
            transfer = (
                self._transfer_details(s, state.active_dataset_id, [row.id]).get(row.id)
                if any(relation.kind == "transfer_pair" for relation in rels)
                or row.transfer_subtype == "bank_security_transfer"
                else None
            )
            funding_relation = None
            if row.funding_relation_id is not None:
                relation = s.scalar(select(CashInvestmentFundingRelationModel).where(
                    CashInvestmentFundingRelationModel.workspace_id == self._workspace_id,
                    CashInvestmentFundingRelationModel.id == row.funding_relation_id,
                    CashInvestmentFundingRelationModel.status == "accepted",
                    CashInvestmentFundingRelationModel.active_slot == "active",
                ))
                if relation is not None:
                    funding_relation = {
                        "id": str(relation.id),
                        "investment_event_id": str(relation.investment_event_id),
                        "direction": relation.direction,
                        "status": relation.status,
                        "rule_id": relation.rule_id,
                        "evidence": _safe_funding_evidence(relation.evidence),
                    }
            return {
                "projection_version": state.projection_version,
                "projection": self._dto(row, account, rels, source_types, transfer, categories),
                "root_record": root_record,
                "members": [
                    {**_record_summary(cash, member_account, categories), "roles": list(member.roles_json)}
                    for member, cash, member_account in members
                ],
                "accepted_relations": [
                    {
                        "id": str(relation.transaction_relation_id), "kind": relation.kind, "subtype": relation.subtype,
                        "rule_id": accepted_by_id[relation.transaction_relation_id].rule_id if relation.transaction_relation_id in accepted_by_id else "",
                        "primary_record": _record_summary(*endpoint_rows[accepted_by_id[relation.transaction_relation_id].primary_fact_id], categories) if relation.transaction_relation_id in accepted_by_id and accepted_by_id[relation.transaction_relation_id].primary_fact_id in endpoint_rows else None,
                        "secondary_record": _record_summary(*endpoint_rows[accepted_by_id[relation.transaction_relation_id].secondary_fact_id], categories) if relation.transaction_relation_id in accepted_by_id and accepted_by_id[relation.transaction_relation_id].secondary_fact_id in endpoint_rows else None,
                    }
                    for relation in rels
                ],
                "inactive_relation_hints": [
                    {
                        "id": str(relation.id), "kind": relation.kind, "subtype": relation.subtype, "status": relation.status,
                        "primary_record": _record_summary(*endpoint_rows[relation.primary_fact_id], categories) if relation.primary_fact_id in endpoint_rows else None,
                        "secondary_record": _record_summary(*endpoint_rows[relation.secondary_fact_id], categories) if relation.secondary_fact_id in endpoint_rows else None,
                    }
                    for relation in inactive
                ],
                "refund_timeline": [
                    {"record_id": cash.record_id, "occurred_at": cash.occurred_at.isoformat(), "amount": _amount(cash.amount), "currency": cash.currency, "source_type": cash.source_type}
                    for member, cash, _ in members if "refund" in member.roles_json
                ],
                "funding_relation": funding_relation,
            }
