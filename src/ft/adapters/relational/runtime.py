"""Composition root and fail-closed validation for the selected relational runtime."""
from datetime import date, datetime, timedelta, timezone
import json
from decimal import Decimal

from sqlalchemy import inspect, select, text
from sqlalchemy.exc import SQLAlchemyError

from ft.adapters.fx_rates import FxRateProvider
from ft.adapters.market_data import CompositeQuoteProvider, MarketDataProvider
from ft.application.accounts import AccountService
from ft.application.cashflow import CashflowService, TransferService
from ft.application.cash_investment_funding_relations import CashInvestmentFundingRelationService
from ft.application.investment import InvestmentService, PortfolioQueryService
from ft.application.queries import FinanceQueryService
from ft.application.statement_import import StatementImportService
from ft.application.relations import RelationService
from ft.application.valuation import ValuationService
from ft.application.wealth import WealthChangeService
from ft.domain.wealth import ComponentKind, CoverageDisposition
from ft.runtime import ServiceBundle

from .queries import (
    RelationalAccountQueryRepository,
    RelationalPortfolioRepository,
    RelationalSnapshotQueryRepository,
    RelationalTransactionQueryRepository,
)
from .uow import RelationalUnitOfWork, create_session_factory
from .dialect import RelationalEngineError, connection_summary, create_relational_engine
from .models import WorkspaceModel
from .wealth_facts import RelationalWealthFactRepository
from .wealth_read_model import RelationalWealthReadModel
from .investments import RelationalInvestmentCommandRepository
from ft.adapters.statement_import import StatementParser


def _utc_today():
    return datetime.now(timezone.utc).date()


SCHEMA_REVISION = "20260813_29"
REQUIRED_TABLES = {
    "workspaces", "accounts", "cash_transactions", "investment_events",
    "ledger_snapshots",
    "transaction_relations", "account_aliases",
    "valuation_observations", "account_lifecycle_events", "wealth_source_manifests",
    "wealth_source_manifest_items", "wealth_generations", "wealth_generation_days",
    "wealth_daily_results", "wealth_active_manifests", "wealth_components",
    "wealth_evidence_manifests", "wealth_evidence_items", "wealth_evidence_manifest_items",
    "wealth_coverage_dispositions",
    "sync_cursors",
    "cash_projection_states", "cash_projection_datasets", "cash_projections",
    "cash_projection_members", "cash_projection_relations",
    "cash_investment_funding_relations",
    "users", "workspace_memberships", "user_sessions", "workspace_invitations",
    "cash_categories", "cash_category_states",
}


class StorageError(RuntimeError):
    def __init__(self, code: str, database_url: str | None = None):
        self.code = code
        try:
            summary = connection_summary(database_url) if database_url else "database"
        except Exception:
            summary = "database"
        labels = {
            "storage.config": "storage configuration is invalid",
            "storage.connect": "unable to connect to selected storage",
            "storage.schema": "database schema is not initialized or current",
            "storage.workspace": "workspace does not exist",
            "storage.readonly": "selected storage is read-only",
            "storage.busy": "selected storage is busy; retry after other writes complete",
        }
        super().__init__(f"{code}: {labels.get(code, 'storage operation failed')} ({summary})")


def storage_error(exc: BaseException, database_url: str) -> StorageError:
    """Map dialect-native failures without exposing driver text to callers."""
    code = getattr(getattr(exc, "orig", exc), "sqlite_errorcode", None)
    if code in {5, 6} or "locked" in str(exc).lower() or "busy" in str(exc).lower():
        return StorageError("storage.busy", database_url)
    if code == 8 or "readonly" in str(exc).lower() or "read-only" in str(exc).lower():
        return StorageError("storage.readonly", database_url)
    return StorageError("storage.connect", database_url)


def validate_runtime(engine, workspace_id: str, database_url: str) -> None:
    try:
        tables = set(inspect(engine).get_table_names())
        missing = sorted(REQUIRED_TABLES - tables)
        if missing or "alembic_version" not in tables:
            raise StorageError("storage.schema", database_url)
        with engine.connect() as connection:
            revision = connection.scalar(text("SELECT version_num FROM alembic_version"))
            workspace = connection.scalar(select(WorkspaceModel.id).where(
                WorkspaceModel.id == workspace_id
            ))
        if revision != SCHEMA_REVISION:
            raise StorageError("storage.schema", database_url)
        if workspace is None:
            raise StorageError("storage.workspace", database_url)
    except StorageError:
        raise
    except SQLAlchemyError as exc:
        raise storage_error(exc, database_url) from exc


def build_relational_services(settings) -> ServiceBundle:
    try:
        engine = create_relational_engine(settings.database_url)
    except RelationalEngineError as exc:
        raise StorageError(exc.code, settings.database_url) from exc
    validate_runtime(engine, settings.workspace_id, settings.database_url)
    sessions = create_session_factory(engine)
    uow = RelationalUnitOfWork(sessions, settings.workspace_id)
    quote_provider = CompositeQuoteProvider()
    market_data = MarketDataProvider(quote_provider)
    valuation = ValuationService(quote_provider)
    fx_rates = FxRateProvider()
    relations_preview = RelationService(uow)
    queries = FinanceQueryService(
        accounts=RelationalAccountQueryRepository(sessions, settings.workspace_id),
        transactions=RelationalTransactionQueryRepository(sessions, settings.workspace_id),
        snapshots=RelationalSnapshotQueryRepository(sessions, settings.workspace_id),
        market_data=market_data,
        valuation=valuation,
        relation_projector=relations_preview.project,
    )
    wealth_facts = RelationalWealthFactRepository(sessions, settings.workspace_id)
    wealth_read_model = RelationalWealthReadModel(sessions, settings.workspace_id)

    class WealthRuntime:
        def __getattr__(self, name):
            for target in (wealth_facts, wealth_read_model):
                if hasattr(target, name):
                    return getattr(target, name)
            raise AttributeError(name)

        def build_daily_results(self, source_watermark, affected_from):
            """Map only frozen, formal facts into canonical daily read-model payloads."""
            from ft.domain.wealth import WealthStatus, canonical_bytes, canonical_digest, decimal_value
            from ft.domain.wealth_calculation import DailyPoint, EvidenceItem, LifecycleEvent, PortfolioBucket, WealthEvent, account_applicable, aggregate_daily_points, attribute_complete_day, build_component, dietz_time_weight, project_daily_point, valuation_freshness, weighted_modified_dietz

            accounts, valuations, cashflows, investments, lifecycle = wealth_facts.captured_build_inputs(source_watermark)
            start = date.fromisoformat(affected_from)
            end = _utc_today()
            rows = []
            components_to_store = []
            evidence_to_store = []
            coverage_to_store = []
            selection_by_component = {}
            daily_points = []
            fx_by_day = {
                (value.as_of.astimezone(timezone.utc).date(), value.identity): value.value
                for value in valuations if value.identity_kind == "fx"
            }

            def local_day(value):
                return value.astimezone(timezone.utc).date()

            # Coverage is keyed by its formal owner, never a ticker/name prefix.
            # Shared FX is support input, not a coverage identity.
            identity_started_at = {}
            ownership_missing_identities: set[tuple[str | None, str, str]] = set()
            ownership_conflict_identities: set[tuple[str | None, str, str]] = set()
            formal_position_owners: dict[str, set[str]] = {}
            def _investment_position(value):
                payload = value.payload if isinstance(value.payload, dict) else {}
                position = (
                    payload.get("position")
                    or getattr(value, "from_ticker", None)
                    or getattr(value, "to_ticker", None)
                    or payload.get("from_ticker")
                    or payload.get("to_ticker")
                )
                return position if isinstance(position, str) and position else None

            def _investment_amount(value, kind: str):
                if kind == "trade":
                    raw = value.commission
                else:
                    raw = value.to_amount if decimal_value(value.to_amount or 0) > 0 else value.from_amount
                if raw is None and isinstance(value.payload, dict):
                    raw = value.payload.get("commission") if kind == "trade" else value.payload.get("amount")
                return raw

            for value in investments:
                kind = value.record_type.lower()
                if kind != "trade":
                    continue
                position = _investment_position(value)
                if not position:
                    continue
                formal_position_owners.setdefault(position, set()).add(value.account_id)
            for value in valuations:
                if value.identity_kind in {"fx", "instrument_quote", "currency_pair"}:
                    continue
                owned_identity = (value.owner_account_id, value.identity_kind, value.identity)
                observed_on = local_day(value.as_of)
                if value.identity_kind in {"cash_account", "position"} and value.owner_account_id is None:
                    ownership_missing_identities.add(owned_identity)
                    continue
                if (
                    value.identity_kind == "position"
                    and value.identity in formal_position_owners
                    and value.owner_account_id not in formal_position_owners[value.identity]
                ):
                    ownership_conflict_identities.add(owned_identity)
                    # Still track start so the unsupported identity remains in the universe.
                identity_started_at[owned_identity] = min(
                    identity_started_at.get(owned_identity, observed_on), observed_on,
                )

            def cny_value(value, boundary):
                if value is None:
                    return None
                if valuation_status(value, boundary) is WealthStatus.PARTIAL:
                    return None
                if value.currency == "CNY":
                    return decimal_value(value.value.normalize())
                rate = fx_by_day.get((boundary, f"{value.currency}/CNY"))
                # PostgreSQL NUMERIC multiplication preserves storage scale while
                # SQLite returns the minimal Decimal.  Remove only trailing zero
                # scale before the shared exact-domain boundary validates it.
                return None if rate is None else decimal_value((value.value * rate).normalize())

            account_type_by_id = {account.account_id: account.account_type for account in accounts}
            account_metadata_by_id = {
                account.account_id: dict(account.metadata or {}) for account in accounts
            }

            def valuation_status(value, boundary):
                owner_id = value.owner_account_id or ""
                owner_type = account_type_by_id.get(owner_id)
                metadata = account_metadata_by_id.get(owner_id, {})
                metadata_kind = metadata.get("asset_kind") or metadata.get("asset_type")
                # Prefer the formal owner account type; fall back to account metadata,
                # then identity-kind defaults. Crypto must use 24h/7d age bands.
                if owner_type == "crypto" or metadata_kind == "crypto":
                    asset_kind = "crypto"
                elif value.identity_kind in {"position", "instrument_quote"}:
                    asset_kind = "security"
                else:
                    asset_kind = "cash"
                boundary_at = datetime.combine(boundary, datetime.min.time(), tzinfo=timezone.utc)
                return valuation_freshness(value.observed_at, boundary_at, asset_kind=asset_kind)

            by_identity_day = {
                (value.owner_account_id, value.identity_kind, value.identity, local_day(value.as_of)): value
                for value in valuations if value.identity_kind != "fx"
            }
            lifecycle_by_account: dict[str, tuple[LifecycleEvent, ...]] = {}
            for account_id in {account.account_id for account in accounts}:
                lifecycle_by_account[account_id] = tuple(
                    LifecycleEvent(item.event_kind, item.effective_at)
                    for item in lifecycle if item.account_id == account_id
                )
            flows_by_day: dict[date, list[Decimal]] = {}
            cash_events_by_day: dict[date, list[WealthEvent]] = {}
            # Foreign-cash FX uses local-currency flow amounts and the FX rate at
            # flow day (day-start FX series), not opening-balance-only impact.
            cash_flows_by_day_currency: dict[tuple[date, str], list[tuple[Decimal, Decimal]]] = {}
            unsupported_by_day: set[date] = set()
            for value in cashflows:
                occurred = local_day(value.occurred_at)
                local_amount = decimal_value(value.amount.normalize())
                if value.currency == "CNY":
                    amount = local_amount
                    flow_rate = Decimal("1")
                else:
                    rate = fx_by_day.get((occurred, f"{value.currency}/CNY"))
                    if rate is None:
                        unsupported_by_day.add(occurred)
                        continue
                    amount = decimal_value((local_amount * rate).normalize())
                    flow_rate = decimal_value(rate)
                flows_by_day.setdefault(occurred, []).append(amount)
                cat = (value.record_type or "").lower()
                if cat in {"transfer", "transfer_in", "transfer_out"}:
                    event_kind = "transfer"
                elif cat in {"salary", "expense", "refund", "interest", "liability_interest"}:
                    event_kind = cat
                else:
                    event_kind = "external_cashflow"
                cash_events_by_day.setdefault(occurred, []).append(WealthEvent(event_kind, amount))
                if event_kind != "transfer" and value.currency != "CNY":
                    cash_flows_by_day_currency.setdefault(
                        (occurred, value.currency), []
                    ).append((local_amount, flow_rate))
            investment_events_by_day: dict[date, list[WealthEvent]] = {}
            # FX attribution uses (local_amount, flow_fx). Dietz capital uses
            # (local_amount, remaining-day time weight) and never reuses FX rates.
            funding_by_day_currency: dict[tuple[date, str], list[tuple[Decimal, Decimal]]] = {}
            dietz_funding_by_day_currency: dict[tuple[date, str], list[tuple[Decimal, Decimal]]] = {}
            for value in investments:
                occurred = local_day(value.occurred_at)
                kind = value.record_type.lower()
                if kind not in {"funding", "trade", "income", "expense", "reversal", "subscription", "adjustment", "snapshot"}:
                    unsupported_by_day.add(occurred)
                    continue
                if kind == "adjustment" and value.record_subtype == "unclassified":
                    # 无法解释的历史折叠记录不能静默进入完整财富归因。
                    unsupported_by_day.add(occurred)
                    continue
                if kind == "trade":
                    position = _investment_position(value)
                    if not position:
                        unsupported_by_day.add(occurred)
                        continue
                    owned_identity = (value.account_id, "position", position)
                    identity_started_at[owned_identity] = min(
                        identity_started_at.get(owned_identity, occurred), occurred,
                    )
                raw_amount = _investment_amount(value, kind)
                if raw_amount is None:
                    # 没有手续费的成交仍然建立正式持仓归属。
                    # ownership, but has no standalone wealth contribution.
                    if kind == "trade":
                        continue
                    unsupported_by_day.add(occurred)
                    continue
                local_amount = decimal_value(raw_amount)
                if value.currency != "CNY":
                    rate = fx_by_day.get((occurred, f"{value.currency}/CNY"))
                    if rate is None:
                        unsupported_by_day.add(occurred)
                        continue
                    amount = decimal_value((local_amount * rate).normalize())
                    flow_rate = decimal_value(rate)
                else:
                    amount = local_amount
                    flow_rate = Decimal("1")
                if kind == "income":
                    # 投资收入已由边界价值吸收，只保留来源证据。
                    continue
                if kind == "funding" and value.record_subtype == "external":
                    incoming = decimal_value(value.to_amount or 0) > 0
                    signed_local = local_amount if incoming else -local_amount
                    signed_cny = amount if incoming else -amount
                    # Direct external funding is workspace external cashflow and
                    # one portfolio Fi for the funding currency universe.
                    investment_events_by_day.setdefault(occurred, []).append(
                        WealthEvent("external_cashflow", signed_cny)
                    )
                    funding_by_day_currency.setdefault(
                        (occurred, value.currency), []
                    ).append((signed_local, flow_rate))
                    day_start_at = datetime.combine(occurred, datetime.min.time(), tzinfo=timezone.utc)
                    day_end_at = day_start_at + timedelta(days=1)
                    dietz_funding_by_day_currency.setdefault(
                        (occurred, value.currency), []
                    ).append((signed_local, dietz_time_weight(value.occurred_at, day_start_at, day_end_at)))
                    continue
                if kind == "trade" and value.payload.get("commission") is not None:
                    # Fees reduce local market return through the boundary values.
                    continue
            current = start
            while current <= end:
                fx_evidence = []
                ownership_evidence = []
                day_boundary = datetime.combine(current, datetime.min.time(), tzinfo=timezone.utc)
                day_missing = {
                    identity for identity in ownership_missing_identities
                }
                day_conflict = {
                    identity for identity in ownership_conflict_identities
                    if identity[0] is None or (
                        identity[0] in lifecycle_by_account
                        and account_applicable(lifecycle_by_account[identity[0]], day_boundary)
                        and current >= identity_started_at.get(identity, current)
                    )
                }
                applicable = {
                    (owner, identity_kind, identity): started_at
                    for (owner, identity_kind, identity), started_at in identity_started_at.items()
                    if owner is not None
                    and owner in lifecycle_by_account
                    and account_applicable(lifecycle_by_account[owner], day_boundary)
                    and current >= started_at
                }
                dispositions: dict[tuple[str | None, str, str], str] = {}
                for owned_identity in day_missing:
                    dispositions[owned_identity] = CoverageDisposition.UNSUPPORTED.value
                for owned_identity in day_conflict:
                    dispositions[owned_identity] = CoverageDisposition.UNSUPPORTED.value
                for owned_identity, _started in applicable.items():
                    if owned_identity in dispositions:
                        continue
                    owner, identity_kind, identity = owned_identity
                    opening_value = cny_value(by_identity_day.get((owner, identity_kind, identity, current)), current)
                    closing_value = cny_value(
                        by_identity_day.get((owner, identity_kind, identity, current + timedelta(days=1))),
                        current + timedelta(days=1),
                    )
                    if opening_value is None or closing_value is None:
                        dispositions[owned_identity] = CoverageDisposition.MISSING.value
                    else:
                        dispositions[owned_identity] = CoverageDisposition.SUPPORTED.value
                boundaries = {
                    f"{owner}:{identity_kind}:{identity}": (
                        cny_value(by_identity_day.get((owner, identity_kind, identity, current)), current),
                        cny_value(by_identity_day.get((owner, identity_kind, identity, current + timedelta(days=1))), current + timedelta(days=1)),
                    ) for (owner, identity_kind, identity) in applicable
                    if (owner, identity_kind, identity) not in day_conflict
                }
                flows = tuple(flows_by_day.get(current, ()))
                fingerprint = canonical_digest(tuple(sorted(
                    (f"{owner}:{identity_kind}:{identity}", disposition)
                    for (owner, identity_kind, identity), disposition in dispositions.items()
                )))
                stale = any(
                    value is not None and valuation_status(value, boundary) is WealthStatus.STALE
                    for owner, identity_kind, identity in applicable
                    for boundary, value in (
                        (current, by_identity_day.get((owner, identity_kind, identity, current))),
                        (current + timedelta(days=1), by_identity_day.get((owner, identity_kind, identity, current + timedelta(days=1)))),
                    )
                )
                ownership_warnings = []
                if day_missing:
                    ownership_warnings.append("OWNERSHIP_MISSING")
                    for owner, identity_kind, identity in sorted(day_missing, key=lambda item: (str(item[0]), item[1], item[2])):
                        ownership_evidence.append(EvidenceItem(
                            evidence_identity=canonical_digest({
                                "kind": "OWNERSHIP_MISSING", "date": current.isoformat(),
                                "owner": owner, "identity_kind": identity_kind, "identity": identity,
                            }),
                            source_identity=f"ownership-missing:{identity_kind}:{identity}",
                            source_revision=source_watermark,
                            occurred_at=day_boundary,
                            evidence_kind="OWNERSHIP_MISSING",
                            contribution=None,
                            scope_fold_identity=f"{current.isoformat()}:ownership_missing:{identity_kind}:{identity}",
                            safe_metadata={
                                "identity_kind": identity_kind,
                                "identity": identity,
                                **({} if owner is None else {"owner_account_id": owner}),
                            },
                        ))
                if day_conflict:
                    ownership_warnings.append("OWNERSHIP_CONFLICT")
                    for owner, identity_kind, identity in sorted(day_conflict, key=lambda item: (str(item[0]), item[1], item[2])):
                        ownership_evidence.append(EvidenceItem(
                            evidence_identity=canonical_digest({
                                "kind": "OWNERSHIP_CONFLICT", "date": current.isoformat(),
                                "owner": owner, "identity_kind": identity_kind, "identity": identity,
                            }),
                            source_identity=f"ownership-conflict:{owner}:{identity_kind}:{identity}",
                            source_revision=source_watermark,
                            occurred_at=day_boundary,
                            evidence_kind="OWNERSHIP_CONFLICT",
                            contribution=None,
                            scope_fold_identity=f"{current.isoformat()}:ownership_conflict:{owner}:{identity_kind}:{identity}",
                            safe_metadata={
                                "owner_account_id": owner or "",
                                "identity_kind": identity_kind,
                                "identity": identity,
                            },
                        ))
                if day_missing or day_conflict or current in unsupported_by_day:
                    warnings = tuple(sorted(set(ownership_warnings) | (
                        {"UNSUPPORTED_INPUT"} if current in unsupported_by_day else set()
                    )))
                    if not warnings:
                        warnings = ("UNSUPPORTED_INPUT",)
                    point = DailyPoint(
                        current, None, None, (None,) * 6, WealthStatus.UNSUPPORTED, fingerprint,
                        source_revision=source_watermark, warnings=warnings,
                    )
                elif any(opening is None or closing is None for opening, closing in boundaries.values()):
                    point = project_daily_point(
                        local_date=current.isoformat(), source_revision=source_watermark,
                        boundaries=boundaries, cashflows=flows, valuations=(), lifecycle=(),
                    )
                    # Prefer the disposition-aware fingerprint over the boundary-only one.
                    point = DailyPoint(
                        point.local_date, point.opening, point.closing, point.components,
                        point.status, fingerprint, source_revision=point.source_revision,
                        warnings=point.warnings,
                    )
                    if stale and point.status is WealthStatus.PARTIAL:
                        point = DailyPoint(
                            point.local_date, point.opening, point.closing, point.components,
                            WealthStatus.PARTIAL, point.coverage_fingerprint,
                            source_revision=point.source_revision,
                            warnings=tuple(sorted(set(point.warnings) | {"STALE_VALUATION"})),
                        )
                else:
                    # Investment attribution is currency-bucketed across the
                    # workspace investment universe; cash identities stay separate.
                    position_by_currency: dict[str, dict[str, object]] = {}
                    cash_buckets = []
                    for owner, identity_kind, identity in applicable:
                        if (owner, identity_kind, identity) in day_conflict:
                            continue
                        opening_valuation = by_identity_day.get((owner, identity_kind, identity, current))
                        closing_valuation = by_identity_day.get((owner, identity_kind, identity, current + timedelta(days=1)))
                        if opening_valuation is None or closing_valuation is None:
                            continue
                        if identity_kind == "cash_account":
                            if opening_valuation.currency == "CNY":
                                continue
                            opening_rate = fx_by_day.get((current, f"{opening_valuation.currency}/CNY"))
                            closing_rate = fx_by_day.get((current + timedelta(days=1), f"{opening_valuation.currency}/CNY"))
                            if opening_rate is None or closing_rate is None:
                                continue
                            cash_flows = tuple(cash_flows_by_day_currency.get(
                                (current, opening_valuation.currency), (),
                            ))
                            cash_buckets.append(PortfolioBucket(
                                opening_local=decimal_value(opening_valuation.value),
                                closing_local=decimal_value(closing_valuation.value),
                                opening_fx=decimal_value(opening_rate),
                                closing_fx=decimal_value(closing_rate),
                                flows=cash_flows,
                                is_cash_or_liability=True,
                            ))
                            contribution = decimal_value((
                                decimal_value(opening_valuation.value) * (closing_rate - opening_rate)
                                + sum((amount * (closing_rate - rate) for amount, rate in cash_flows), Decimal("0"))
                            ).normalize())
                            if contribution:
                                fx_evidence.append(EvidenceItem(
                                    evidence_identity=canonical_digest({
                                        "identity": identity, "date": current.isoformat(), "kind": "fx",
                                    }),
                                    source_identity=opening_valuation.source_identity,
                                    source_revision=opening_valuation.source_revision,
                                    occurred_at=opening_valuation.as_of,
                                    evidence_kind="fx", contribution=contribution,
                                    scope_fold_identity=f"{current.isoformat()}:fx:{opening_valuation.currency}:cash",
                                    safe_metadata={"currency": opening_valuation.currency, "bucket": "cash"},
                                ))
                            continue
                        if identity_kind != "position":
                            continue
                        currency = opening_valuation.currency
                        bucket = position_by_currency.setdefault(currency, {
                            "opening": Decimal("0"), "closing": Decimal("0"),
                            "source_identity": opening_valuation.source_identity,
                            "source_revision": opening_valuation.source_revision,
                            "as_of": opening_valuation.as_of,
                            "identity": identity,
                        })
                        bucket["opening"] = decimal_value(bucket["opening"]) + decimal_value(opening_valuation.value)
                        bucket["closing"] = decimal_value(bucket["closing"]) + decimal_value(closing_valuation.value)
                    portfolio_buckets = list(cash_buckets)
                    for currency, values in sorted(position_by_currency.items()):
                        if currency == "CNY":
                            portfolio_buckets.append(PortfolioBucket(
                                opening_local=decimal_value(values["opening"]),
                                closing_local=decimal_value(values["closing"]),
                                opening_fx=Decimal("1"), closing_fx=Decimal("1"),
                                flows=tuple(funding_by_day_currency.get((current, "CNY"), ())),
                            ))
                            continue
                        opening_rate = fx_by_day.get((current, f"{currency}/CNY"))
                        closing_rate = fx_by_day.get((current + timedelta(days=1), f"{currency}/CNY"))
                        if opening_rate is None or closing_rate is None:
                            continue
                        flows = tuple(funding_by_day_currency.get((current, currency), ()))
                        portfolio_buckets.append(PortfolioBucket(
                            opening_local=decimal_value(values["opening"]),
                            closing_local=decimal_value(values["closing"]),
                            opening_fx=decimal_value(opening_rate), closing_fx=decimal_value(closing_rate),
                            flows=flows,
                        ))
                        contribution = decimal_value((
                            decimal_value(values["opening"]) * (closing_rate - opening_rate)
                            + sum((amount * (closing_rate - rate) for amount, rate in flows), Decimal("0"))
                        ).normalize())
                        if contribution:
                            fx_evidence.append(EvidenceItem(
                                evidence_identity=canonical_digest({"identity": values["identity"], "date": current.isoformat(), "kind": "fx"}),
                                source_identity=values["source_identity"],
                                source_revision=values["source_revision"],
                                occurred_at=values["as_of"],
                                evidence_kind="fx", contribution=contribution,
                                scope_fold_identity=f"{current.isoformat()}:fx:{currency}",
                                safe_metadata={"currency": currency},
                            ))
                    identity = attribute_complete_day(
                        opening=sum((opening for opening, _closing in boundaries.values()), Decimal("0")),
                        closing=sum((closing for _opening, closing in boundaries.values()), Decimal("0")),
                        external_events=tuple(cash_events_by_day.get(current, ())) + tuple(investment_events_by_day.get(current, ())),
                        portfolio_buckets=tuple(portfolio_buckets),
                    )
                    dietz_buckets = []
                    dietz_complete = True
                    for currency, values in sorted(position_by_currency.items()):
                        opening_rate = (
                            Decimal("1") if currency == "CNY"
                            else fx_by_day.get((current, f"{currency}/CNY"))
                        )
                        if opening_rate is None:
                            dietz_complete = False
                            break
                        dietz_buckets.append((
                            decimal_value(values["opening"]),
                            decimal_value(values["closing"]),
                            decimal_value(opening_rate),
                            tuple(dietz_funding_by_day_currency.get((current, currency), ())),
                        ))
                    investment_return_rate = (
                        weighted_modified_dietz(tuple(dietz_buckets)) if dietz_complete else None
                    )
                    point = DailyPoint(
                        current, identity.opening, identity.closing, tuple(identity.components.values()),
                        WealthStatus.STALE if stale else WealthStatus.COMPLETE,
                        fingerprint,
                        investment_return_rate=investment_return_rate,
                        source_revision=source_watermark,
                        warnings=("STALE_VALUATION",) if stale else (),
                    )
                components = tuple(
                    build_component(
                        workspace_id=settings.workspace_id, period_start=current.isoformat(),
                        period_end=(current + timedelta(days=1)).isoformat(), granularity="day",
                        kind=kind, grouping_key="workspace", amount=amount, status=point.status,
                        source_revision=source_watermark,
                    ) for kind, amount in zip(ComponentKind, point.components, strict=True)
                )
                # A component's formal direct rows are read from the frozen
                # source manifest rather than copied to evidence tables.  Keep
                # the reconciliation contract explicit at the build boundary:
                # external cash, direct external investment funding, and the
                # separately materialized FX evidence must account for their
                # corresponding result components before publication.
                if point.status is WealthStatus.COMPLETE:
                    expected_evidence = {
                        ComponentKind.EXTERNAL_CASHFLOW: sum(
                            (event.amount for event in cash_events_by_day.get(current, ())
                            if event.kind in {"salary", "expense", "refund", "interest", "liability_interest", "external_cashflow"}),
                            Decimal("0"),
                        ) + sum(
                            (event.amount for event in investment_events_by_day.get(current, ())
                            if event.kind == "external_cashflow"),
                            Decimal("0"),
                        ),
                        ComponentKind.FX_IMPACT: sum((item.contribution or Decimal("0") for item in fx_evidence), Decimal("0")),
                    }
                    for component in components:
                        expected = expected_evidence.get(component.kind)
                        if expected is not None and component.amount != expected:
                            raise ValueError("wealth.evidence_reconciliation_failed")
                payload = canonical_bytes({
                    "local_date": current.isoformat(), "source_revision": source_watermark,
                    "opening": point.opening, "closing": point.closing, "status": point.status,
                    "coverage_fingerprint": point.coverage_fingerprint, "components": components,
                    # Rates keep exact division scale; money fields remain on decimal_value limits.
                    "investment_return_rate": (
                        None if point.investment_return_rate is None
                        else format(point.investment_return_rate, "f")
                    ),
                    "warnings": point.warnings,
                }).decode("utf-8")
                components_to_store.extend(components)
                for component in components:
                    if component.kind.value == "external_cashflow":
                        selection_by_component[component.component_id] = {
                            "local_date": current.isoformat(),
                            "kinds": [
                                "salary", "expense", "refund", "interest", "liability_interest",
                                "external_cashflow", "investment_funding",
                            ],
                        }
                    elif component.kind.value == "investment_return":
                        # 边界公式决定金额；投资收入与费用行只保留来源证据。
                        # as explanatory source-manifest evidence only.
                        selection_by_component[component.component_id] = {
                            "local_date": current.isoformat(), "kinds": ["dividend", "fee"],
                        }
                    else:
                        selection_by_component[component.component_id] = {}
                    if component.kind.value == "fx_impact" and fx_evidence:
                        evidence_to_store.append((component, tuple(fx_evidence)))
                    if component.kind.value == "unexplained_adjustment" and ownership_evidence:
                        evidence_to_store.append((component, tuple(ownership_evidence)))
                result_digest = canonical_digest(payload)
                for (owner, identity_kind, identity), disposition in sorted(
                    dispositions.items(), key=lambda item: (str(item[0][0]), item[0][1], item[0][2])
                ):
                    coverage_to_store.append((
                        result_digest, current.isoformat(), source_watermark,
                        owner, identity_kind, identity, disposition,
                    ))
                rows.append((current.isoformat(), result_digest, payload))
                daily_points.append(point)
                current += timedelta(days=1)
            # Store weekly/monthly component identities at the same time as the
            # canonical daily projection.  Read paths only deserialize them;
            # they never rerun a competing attribution formula.
            for granularity in ("week", "month"):
                buckets = {}
                for point in daily_points:
                    key = point.local_date.isocalendar()[:2] if granularity == "week" else (point.local_date.year, point.local_date.month)
                    buckets.setdefault(key, []).append(point)
                for bucket in buckets.values():
                    aggregate = aggregate_daily_points(tuple(bucket))
                    period_start = bucket[0].local_date
                    period_end = bucket[-1].local_date + timedelta(days=1)
                    aggregate_components = self.period_components(
                        period_start=period_start, period_end=period_end, granularity=granularity,
                        amounts=aggregate.components, status=aggregate.status,
                        source_revision=aggregate.source_revision,
                    )
                    components_to_store.extend(aggregate_components)
                    for component in aggregate_components:
                        if component.kind.value == "external_cashflow":
                            selection_by_component[component.component_id] = {
                                "date_from": period_start.isoformat(), "date_to": period_end.isoformat(),
                                "kinds": [
                                    "salary", "expense", "refund", "interest", "liability_interest",
                                    "external_cashflow", "investment_funding",
                                ],
                            }
                        elif component.kind.value == "investment_return":
                            selection_by_component[component.component_id] = {
                                "date_from": period_start.isoformat(), "date_to": period_end.isoformat(),
                                "kinds": ["dividend", "fee"],
                            }
                        else:
                            selection_by_component[component.component_id] = {}
            wealth_read_model.store_components(
                tuple(components_to_store), source_manifest_id=source_watermark,
                selection_by_component=selection_by_component,
            )
            wealth_read_model.store_evidence_batch(tuple(evidence_to_store))
            # Coverage dispositions are content-addressed by result digest; they
            # are written after components/evidence and before the application
            # service stores the daily result rows themselves.
            self._pending_coverage_dispositions = tuple(coverage_to_store)
            return tuple(rows)

        def store_daily_results(self, rows, source_revision):
            coverage = getattr(self, "_pending_coverage_dispositions", ())
            # One transaction for daily results + owned coverage keeps the cold
            # rebuild within budget and preserves the result FK order.
            wealth_read_model.store_daily_results(rows, source_revision, coverage_rows=coverage)
            self._pending_coverage_dispositions = ()

        def daily_points(self, *, date_from, date_to):
            """Deserialize immutable active payloads; calculations never re-run on a hit."""
            from ft.domain.wealth import WealthStatus
            from ft.domain.wealth_calculation import DailyPoint
            payloads = wealth_read_model.active_daily_payloads(date_from.isoformat(), date_to.isoformat())
            return tuple(DailyPoint(
                local_date=date.fromisoformat(payload["local_date"]),
                opening=None if payload["opening"] is None else Decimal(payload["opening"]),
                closing=None if payload["closing"] is None else Decimal(payload["closing"]),
                components=tuple(None if item["amount"] is None else Decimal(item["amount"]) for item in payload["components"]),
                status=WealthStatus(payload["status"]), coverage_fingerprint=payload["coverage_fingerprint"],
                investment_return_rate=(
                    None if payload.get("investment_return_rate") is None
                    else Decimal(payload["investment_return_rate"])
                ),
                source_revision=payload["source_revision"],
                warnings=tuple(payload.get("warnings") or ()),
            ) for payload in map(json.loads, payloads))

        def period_components(self, *, period_start, period_end, granularity, amounts, status, source_revision):
            from ft.domain.wealth_calculation import build_component
            return tuple(build_component(
                workspace_id=settings.workspace_id, period_start=period_start.isoformat(),
                period_end=period_end.isoformat(), granularity=granularity, kind=kind,
                grouping_key="workspace", amount=amount, status=status,
                source_revision=source_revision,
            ) for kind, amount in zip(ComponentKind, amounts, strict=True))

    relations = relations_preview
    funding_relations = CashInvestmentFundingRelationService(sessions, settings.workspace_id)
    return ServiceBundle(
        queries=queries,
        portfolio=PortfolioQueryService(
            RelationalPortfolioRepository(sessions, settings.workspace_id),
            valuation,
            fx_rates=fx_rates,
        ),
        investments=InvestmentService(
            repository=RelationalInvestmentCommandRepository(uow)
        ),
        statement_import=StatementImportService(
            uow, StatementParser(), relation_service=relations,
            enforce_account_currencies=True,
        ),
        relations=relations,
        funding_relations=funding_relations,
        wealth=WealthChangeService(WealthRuntime()),
        accounts=AccountService(uow),
        cashflow=CashflowService(uow),
        transfers=TransferService(uow),
        uow=uow,
        notices=tuple(engine.info["runtime_notices"]),
    )
