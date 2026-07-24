"""Workspace-bound typed formal facts for the wealth application service."""
from __future__ import annotations

from datetime import datetime
import hashlib
from decimal import Decimal
from zoneinfo import ZoneInfo

from sqlalchemy import func, select, text

from ft.domain.wealth import canonical_digest
from ft.repositories.wealth import AccountFact, CashflowFact, InvestmentFact, LifecycleFact, ValuationFact, WealthSourceItem
from .models import AccountLifecycleEventModel, AccountModel, CashTransactionModel, InvestmentEventModel, ValuationObservationModel


def _digest_parts(*parts: object) -> str:
    """Hash a typed-source identity without constructing transient JSON graphs."""
    digest = hashlib.sha256()
    for part in parts:
        encoded = str(part).encode("utf-8")
        digest.update(len(encoded).to_bytes(8, "big"))
        digest.update(encoded)
    return digest.hexdigest()


def _manifest_digest(items: tuple[WealthSourceItem, ...]) -> str:
    digest = hashlib.sha256()
    for item in items:
        # The canonical source set includes its direct-evidence projection.  A
        # changed period, kind, fold identity, or contribution must therefore
        # yield a new immutable source-manifest identity even if the formal
        # fact's primary identity is unchanged.
        digest.update(bytes.fromhex(_digest_parts(
            item.item_kind, item.identity, item.revision, item.content_digest,
            item.occurred_at.isoformat() if item.occurred_at else None,
            item.evidence_kind, item.contribution, item.scope_fold_identity,
        )))
    return digest.hexdigest()


class RelationalWealthFactRepository:
    def __init__(self, session_factory, workspace_id: str) -> None:
        self._sessions = session_factory
        self._workspace_id = workspace_id

    def accounts(self) -> tuple[AccountFact, ...]:
        with self._sessions() as session:
            rows = session.scalars(select(AccountModel).where(
                AccountModel.workspace_id == self._workspace_id
            ).order_by(AccountModel.id)).all()
        return tuple(AccountFact(row.workspace_id, row.id, row.type, row.metadata_json) for row in rows)

    def valuations(self, *, starts_at: datetime, ends_at: datetime) -> tuple[ValuationFact, ...]:
        with self._sessions() as session:
            rows = session.execute(select(
                ValuationObservationModel.workspace_id, ValuationObservationModel.observation_id,
                ValuationObservationModel.identity_kind, ValuationObservationModel.identity,
                ValuationObservationModel.owner_account_id,
                ValuationObservationModel.observation_kind, ValuationObservationModel.value,
                ValuationObservationModel.currency, ValuationObservationModel.unit,
                ValuationObservationModel.as_of, ValuationObservationModel.observed_at,
                ValuationObservationModel.source_identity, ValuationObservationModel.source_revision,
                ValuationObservationModel.trust, ValuationObservationModel.raw_record_id,
            ).where(
                ValuationObservationModel.workspace_id == self._workspace_id,
                ValuationObservationModel.as_of >= starts_at,
                # The wealth query range is [start, end) for daily buckets, but
                # its final bucket needs the valuation at the exclusive end as
                # its closing boundary.
                ValuationObservationModel.as_of <= ends_at,
            ).order_by(ValuationObservationModel.as_of, ValuationObservationModel.observation_id)).all()
        return tuple(ValuationFact(
            row[0], row[1], row[2], row[3], row[5], row[6], row[7], row[8], row[9], row[10],
            row[11], row[12], row[13], row[14], row[4],
        ) for row in rows)

    def lifecycle_events(self) -> tuple[LifecycleFact, ...]:
        with self._sessions() as session:
            rows = session.scalars(select(AccountLifecycleEventModel).where(
                AccountLifecycleEventModel.workspace_id == self._workspace_id
            ).order_by(AccountLifecycleEventModel.effective_at, AccountLifecycleEventModel.event_id)).all()
        return tuple(LifecycleFact(
            row.workspace_id, row.event_id, row.account_id, row.event_kind, row.effective_at,
            row.source_identity, row.source_revision, row.reason,
        ) for row in rows)

    def cashflows(self) -> tuple[CashflowFact, ...]:
        with self._sessions() as session:
            rows = session.execute(select(
                CashTransactionModel.workspace_id, CashTransactionModel.id,
                CashTransactionModel.account_id, CashTransactionModel.occurred_at,
                CashTransactionModel.amount, CashTransactionModel.currency,
                CashTransactionModel.revision, CashTransactionModel.raw_record_id,
                CashTransactionModel.category, CashTransactionModel.transfer_account,
                CashTransactionModel.offset_group, CashTransactionModel.offset_role,
            ).where(
                CashTransactionModel.workspace_id == self._workspace_id
            ).order_by(CashTransactionModel.occurred_at, CashTransactionModel.id)).all()
        return tuple(CashflowFact(*row) for row in rows)

    def investments(self) -> tuple[InvestmentFact, ...]:
        with self._sessions() as session:
            rows = session.execute(select(
                InvestmentEventModel.workspace_id, InvestmentEventModel.id,
                InvestmentEventModel.account_id, InvestmentEventModel.occurred_at,
                InvestmentEventModel.action, InvestmentEventModel.currency,
                InvestmentEventModel.payload, InvestmentEventModel.revision,
                InvestmentEventModel.raw_record_id,
                InvestmentEventModel.commission, InvestmentEventModel.from_amount,
                InvestmentEventModel.to_amount,
                InvestmentEventModel.from_ticker, InvestmentEventModel.to_ticker,
            ).where(
                InvestmentEventModel.workspace_id == self._workspace_id
            ).order_by(InvestmentEventModel.occurred_at, InvestmentEventModel.id)).all()
        return tuple(InvestmentFact(*row) for row in rows)

    def capture_source_manifest(self) -> tuple[str, tuple[WealthSourceItem, ...]]:
        """Return a deterministic enumeration; callers persist it before calculating."""
        # All source categories are captured in one explicit database snapshot.
        # SQLite reserves its short read/write window; PostgreSQL uses a repeatable
        # read transaction.  The calculator subsequently receives only these rows.
        with self._sessions.begin() as session:
            if session.bind.dialect.name == "sqlite":
                session.connection().exec_driver_sql("BEGIN IMMEDIATE")
            elif session.bind.dialect.name == "postgresql":
                session.execute(text("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ"))
            account_rows = session.execute(select(
                AccountModel.workspace_id, AccountModel.id, AccountModel.type,
                AccountModel.metadata_json,
            ).where(AccountModel.workspace_id == self._workspace_id).order_by(AccountModel.id)).all()
            valuation_rows = session.execute(select(
                ValuationObservationModel.workspace_id, ValuationObservationModel.observation_id,
                ValuationObservationModel.identity_kind, ValuationObservationModel.identity,
                ValuationObservationModel.owner_account_id,
                ValuationObservationModel.observation_kind, ValuationObservationModel.value,
                ValuationObservationModel.currency, ValuationObservationModel.unit,
                ValuationObservationModel.as_of, ValuationObservationModel.observed_at,
                ValuationObservationModel.source_identity, ValuationObservationModel.source_revision,
                ValuationObservationModel.trust, ValuationObservationModel.raw_record_id,
            ).where(
                ValuationObservationModel.workspace_id == self._workspace_id
            ).order_by(ValuationObservationModel.observation_id, ValuationObservationModel.source_revision)).all()
            lifecycle_rows = session.execute(select(
                AccountLifecycleEventModel.workspace_id, AccountLifecycleEventModel.event_id,
                AccountLifecycleEventModel.account_id, AccountLifecycleEventModel.event_kind,
                AccountLifecycleEventModel.effective_at, AccountLifecycleEventModel.source_identity,
                AccountLifecycleEventModel.source_revision, AccountLifecycleEventModel.reason,
            ).where(
                AccountLifecycleEventModel.workspace_id == self._workspace_id
            ).order_by(AccountLifecycleEventModel.event_id, AccountLifecycleEventModel.source_revision)).all()
            cash_rows = session.execute(select(
                CashTransactionModel.workspace_id, CashTransactionModel.id,
                CashTransactionModel.account_id, CashTransactionModel.occurred_at,
                CashTransactionModel.amount, CashTransactionModel.currency,
                CashTransactionModel.revision, CashTransactionModel.raw_record_id,
                CashTransactionModel.category, CashTransactionModel.transfer_account,
                CashTransactionModel.offset_group, CashTransactionModel.offset_role,
            ).where(CashTransactionModel.workspace_id == self._workspace_id).order_by(
                CashTransactionModel.occurred_at, CashTransactionModel.id,
            )).all()
            investment_rows = session.execute(select(
                InvestmentEventModel.workspace_id, InvestmentEventModel.id,
                InvestmentEventModel.account_id, InvestmentEventModel.occurred_at,
                InvestmentEventModel.action, InvestmentEventModel.currency,
                InvestmentEventModel.payload, InvestmentEventModel.revision,
                InvestmentEventModel.raw_record_id,
                InvestmentEventModel.commission, InvestmentEventModel.from_amount,
                InvestmentEventModel.to_amount,
                InvestmentEventModel.from_ticker, InvestmentEventModel.to_ticker,
            ).where(InvestmentEventModel.workspace_id == self._workspace_id).order_by(
                InvestmentEventModel.occurred_at, InvestmentEventModel.id,
            )).all()
            # Fence digest is derived from the same snapshot rows just read so a
            # cold rebuild never pays a second full formal-source table scan at
            # capture time.  publish-time source_is_current still re-reads.
            captured_state = self._source_state_from_capture_rows(
                account_rows, valuation_rows, lifecycle_rows, cash_rows, investment_rows,
            )
        accounts = tuple(AccountFact(*row) for row in account_rows)
        valuations = tuple(ValuationFact(
            row[0], row[1], row[2], row[3], row[5], row[6], row[7], row[8], row[9], row[10],
            row[11], row[12], row[13], row[14], row[4],
        ) for row in valuation_rows)
        lifecycle = tuple(LifecycleFact(*row) for row in lifecycle_rows)
        cashflows = tuple(CashflowFact(*row) for row in cash_rows)
        investments = tuple(InvestmentFact(*row) for row in investment_rows)
        shanghai = ZoneInfo("Asia/Shanghai")
        fx_by_day = {
            (value.as_of.astimezone(shanghai).date(), value.identity): value.value
            for value in valuations if value.identity_kind == "fx"
        }

        def projected_amount(amount, currency: str, occurred_at: datetime) -> Decimal | None:
            if currency == "CNY":
                return amount.normalize()
            return (amount * fx_by_day[(occurred_at.astimezone(shanghai).date(), f"{currency}/CNY")]).normalize() if (
                occurred_at.astimezone(shanghai).date(), f"{currency}/CNY"
            ) in fx_by_day else None

        def cash_kind(row: CashflowFact) -> str:
            if row.transfer_account or row.offset_group or row.offset_role:
                return "transfer"
            return row.category.lower() if row.category.lower() in {
                "salary", "expense", "refund", "interest", "liability_interest",
            } else "external_cashflow"

        def investment_projection(row: InvestmentFact):
            raw = row.commission
            if raw is None:
                raw = row.to_amount if row.action.lower() in {"dividend", "deposit"} else row.from_amount
            if raw is None and isinstance(row.payload, dict):
                raw = row.payload.get("amount", row.payload.get("commission"))
            if raw is None:
                return None, None
            amount = projected_amount(Decimal(str(raw)), row.currency, row.occurred_at)
            if amount is None:
                return None, None
            kind = row.action.lower()
            if kind == "dividend":
                return "dividend", amount
            if kind in {"buy", "sell", "swap"} and row.commission is not None:
                return "fee", -abs(amount)
            if kind == "withdraw":
                return "investment_funding", -amount
            if kind == "deposit":
                return "investment_funding", amount
            if kind == "fee":
                return "fee", -abs(amount) if (row.from_amount or 0) else abs(amount)
            return None, None
        items = [
            WealthSourceItem("account", row.account_id, "account", canonical_digest({"type": row.account_type, "metadata": dict(row.metadata)}))
            for row in accounts
        ]
        items.extend(WealthSourceItem("valuation", row.observation_id, row.source_revision, _digest_parts(
            row.identity, row.value, row.as_of.isoformat(), row.source_revision,
        )) for row in valuations)
        items.extend(WealthSourceItem("lifecycle", row.event_id, row.source_revision, _digest_parts(
            row.account_id, row.event_kind, row.effective_at.isoformat(), row.source_revision,
        )) for row in lifecycle)
        items.extend(WealthSourceItem(
            "cashflow", row.fact_id, str(row.revision), _digest_parts(
                row.account_id, row.occurred_at.isoformat(), row.amount, row.category,
                row.transfer_account, row.offset_group, row.offset_role, row.revision,
            ), row.occurred_at, cash_kind(row), projected_amount(row.amount, row.currency, row.occurred_at),
            f"{row.occurred_at.astimezone(shanghai).date().isoformat()}:{cash_kind(row)}:{row.fact_id}",
            None,
        ) for row in cashflows)
        for row in investments:
            evidence_kind, contribution = investment_projection(row)
            items.append(WealthSourceItem(
                "investment", row.fact_id, str(row.revision), _digest_parts(
                    row.account_id, row.occurred_at.isoformat(), row.action, canonical_digest(dict(row.payload)), row.revision,
                ), row.occurred_at, evidence_kind, contribution,
                f"{row.occurred_at.astimezone(shanghai).date().isoformat()}:{evidence_kind}:{row.fact_id}",
                None,
            ))
        ordered = tuple(sorted(items, key=lambda item: (item.item_kind, item.identity, item.revision)))
        watermark = _manifest_digest(ordered)
        self._captured_build_inputs = (watermark, accounts, valuations, cashflows, investments, lifecycle)
        self._captured_source_state = captured_state
        return watermark, ordered

    def captured_build_inputs(self, watermark: str):
        """Use exactly the formal rows enumerated at build capture, never a reread."""
        captured = getattr(self, "_captured_build_inputs", None)
        if captured is None or captured[0] != watermark:
            raise ValueError("wealth.source_changed")
        return captured[1:]

    def source_is_current(self, watermark: str) -> bool:
        # Formal wealth inputs are append-only revisions.  A compact relational
        # state vector detects any new fact/revision without rematerializing and
        # re-canonicalizing a 100k-item manifest at publish time.
        return (
            getattr(self, "_captured_build_inputs", (None,))[0] == watermark
            and getattr(self, "_captured_source_state", None) == self._source_state()
        )

    def _absorb_source_state_rows(self, digest, rows) -> None:
        for row in rows:
            for part in row:
                encoded = str(part).encode("utf-8")
                digest.update(len(encoded).to_bytes(8, "big"))
                digest.update(encoded)
            digest.update(b"\n")
        digest.update(b"|")

    def _source_state_from_capture_rows(
        self, account_rows, valuation_rows, lifecycle_rows, cash_rows, investment_rows,
    ) -> tuple[object, ...]:
        """Same fence digest as ``_source_state``, built from capture snapshot rows.

        Capture already materializes the full formal projection under the snapshot
        isolation window.  Re-deriving the fence from those rows preserves the
        correction-sensitive digest without a second table scan at capture time.
        """
        digest = hashlib.sha256()
        # Match the column projection and ordering used by ``_source_state``.
        self._absorb_source_state_rows(digest, (
            (row[1], row[2], row[3]) for row in account_rows
        ))
        self._absorb_source_state_rows(digest, (
            (row[1], row[12], row[6], row[4], row[9], row[10]) for row in valuation_rows
        ))
        self._absorb_source_state_rows(digest, (
            (row[1], row[6], row[3], row[4]) for row in lifecycle_rows
        ))
        # Capture orders cash/investments by occurred_at for manifest stability;
        # the fence orders by primary identity/revision so in-place corrections
        # that preserve maxima still invalidate.  Sort the already-fetched rows.
        self._absorb_source_state_rows(digest, (
            (row[1], row[6], row[2], row[3], row[4], row[8], row[9], row[10], row[11])
            for row in sorted(cash_rows, key=lambda item: (item[1], item[6]))
        ))
        self._absorb_source_state_rows(digest, (
            (row[1], row[7], row[2], row[3], row[4], row[6])
            for row in sorted(investment_rows, key=lambda item: (item[1], item[7]))
        ))
        return (digest.hexdigest(),)

    def _source_state(self, session=None) -> tuple[object, ...]:
        def read(active_session):
            # A correction can preserve every count and maximum.  Fence against
            # the complete workspace-qualified immutable input projection, but
            # stream the digest so publish-time fencing stays within the cold
            # rebuild budget even at 100k formal facts.
            digest = hashlib.sha256()
            self._absorb_source_state_rows(digest, active_session.execute(select(
            AccountModel.id, AccountModel.type, AccountModel.metadata_json,
            ).where(AccountModel.workspace_id == self._workspace_id).order_by(AccountModel.id)).yield_per(2_000))
            self._absorb_source_state_rows(digest, active_session.execute(select(
                ValuationObservationModel.observation_id, ValuationObservationModel.source_revision,
                ValuationObservationModel.value, ValuationObservationModel.owner_account_id,
                ValuationObservationModel.as_of, ValuationObservationModel.observed_at,
            ).where(ValuationObservationModel.workspace_id == self._workspace_id).order_by(
                ValuationObservationModel.observation_id, ValuationObservationModel.source_revision,
            )).yield_per(2_000))
            self._absorb_source_state_rows(digest, active_session.execute(select(
                AccountLifecycleEventModel.event_id, AccountLifecycleEventModel.source_revision,
                AccountLifecycleEventModel.event_kind, AccountLifecycleEventModel.effective_at,
            ).where(AccountLifecycleEventModel.workspace_id == self._workspace_id).order_by(
                AccountLifecycleEventModel.event_id, AccountLifecycleEventModel.source_revision,
            )).yield_per(2_000))
            self._absorb_source_state_rows(digest, active_session.execute(select(
                CashTransactionModel.id, CashTransactionModel.revision, CashTransactionModel.account_id,
                CashTransactionModel.occurred_at, CashTransactionModel.amount, CashTransactionModel.category,
                CashTransactionModel.transfer_account, CashTransactionModel.offset_group, CashTransactionModel.offset_role,
            ).where(CashTransactionModel.workspace_id == self._workspace_id).order_by(
                CashTransactionModel.id, CashTransactionModel.revision,
            )).yield_per(2_000))
            self._absorb_source_state_rows(digest, active_session.execute(select(
                InvestmentEventModel.id, InvestmentEventModel.revision, InvestmentEventModel.account_id,
                InvestmentEventModel.occurred_at, InvestmentEventModel.action, InvestmentEventModel.payload,
            ).where(InvestmentEventModel.workspace_id == self._workspace_id).order_by(
                InvestmentEventModel.id, InvestmentEventModel.revision,
            )).yield_per(2_000))
            return (digest.hexdigest(),)
        if session is not None:
            return read(session)
        with self._sessions() as fresh_session:
            return read(fresh_session)


class RelationalWealthFactWriter:
    """Command-side formal observations, bound to the caller's UoW transaction."""
    def __init__(self, session, workspace_id: str) -> None:
        self._session = session
        self._workspace_id = workspace_id

    def record_cash_checkin(self, *, account_name: str, currency: str, balance, occurred_at: datetime) -> None:
        account = self._session.scalar(select(AccountModel).where(
            AccountModel.workspace_id == self._workspace_id, AccountModel.name == account_name,
        ))
        if account is None:
            raise ValueError("account not found")
        seed = {"workspace": self._workspace_id, "account": account.id, "currency": currency, "at": occurred_at, "balance": balance}
        observation_id = canonical_digest(seed)
        if self._session.get(ValuationObservationModel, observation_id) is None:
            self._session.add(ValuationObservationModel(
                observation_id=observation_id, workspace_id=self._workspace_id,
                identity_kind="cash_account", identity=f"{account.id}:{currency}", observation_kind="boundary_checkin",
                owner_account_id=account.id,
                value=balance, currency=currency, unit="currency", as_of=occurred_at, observed_at=occurred_at,
                source_identity=f"manual-checkin:{account.id}:{occurred_at.isoformat()}",
                source_revision=observation_id, trust="trusted_checkin",
            ))

    def record_lifecycle(self, *, account_name: str, event_kind: str, effective_at: datetime) -> None:
        account = self._session.scalar(select(AccountModel).where(
            AccountModel.workspace_id == self._workspace_id, AccountModel.name == account_name,
        ))
        if account is None:
            raise ValueError("account not found")
        event_id = canonical_digest({"workspace": self._workspace_id, "account": account.id, "kind": event_kind, "at": effective_at})
        if self._session.get(AccountLifecycleEventModel, event_id) is None:
            self._session.add(AccountLifecycleEventModel(
                event_id=event_id, workspace_id=self._workspace_id, account_id=account.id, event_kind=event_kind,
                effective_at=effective_at, source_identity=f"command:{account.id}:{event_kind}",
                source_revision=event_id, reason="account command",
            ))
