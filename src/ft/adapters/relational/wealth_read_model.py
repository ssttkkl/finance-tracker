"""Immutable relational wealth result/evidence rows scoped to one workspace."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
import hashlib
import json
from zoneinfo import ZoneInfo
from sqlalchemy import func, insert, or_, select, text, update

from ft.domain.wealth import canonical_bytes, canonical_digest
from ft.domain.wealth_calculation import EvidenceItem
from .models import (
    WealthActiveManifestModel, WealthComponentModel, WealthCoverageDispositionModel,
    WealthDailyResultModel, WealthEvidenceItemModel, WealthEvidenceManifestItemModel,
    WealthEvidenceManifestModel, WealthGenerationDayModel, WealthGenerationModel,
    WealthSourceManifestItemModel, WealthSourceManifestModel,
)


def _manifest_item_id(*parts: str) -> str:
    digest = hashlib.sha256()
    for part in parts:
        encoded = part.encode("utf-8")
        digest.update(len(encoded).to_bytes(8, "big"))
        digest.update(encoded)
    return digest.hexdigest()



def _postgres_bulk_write_settings(session) -> None:
    """Speed content-addressed bulk rebuild inserts without changing publish safety.

    Staging bulk writes may use transaction-local ``session_replication_role=replica``
    to keep the 100k-fact cold rebuild under budget.  Publish still fails closed via
    pre-CAS integrity checks, and ``synchronous_commit=off`` stays staging-only —
    the active-pointer transaction never opts into either setting.
    """
    session.execute(text("SET LOCAL synchronous_commit = off"))
    session.execute(text("SET LOCAL session_replication_role = 'replica'"))


class RelationalWealthReadModel:
    def __init__(self, session_factory, workspace_id: str) -> None:
        self._sessions = session_factory
        self._workspace_id = workspace_id

    def store_source_manifest(self, source_watermark: str, items) -> str:
        """Persist the immutable, enumerated input set before a build reads it."""
        with self._sessions.begin() as session:
            if session.get(WealthSourceManifestModel, source_watermark) is not None:
                return source_watermark
            session.add(WealthSourceManifestModel(
                manifest_id=source_watermark, workspace_id=self._workspace_id,
                source_watermark=source_watermark, canonical_digest=source_watermark,
            ))
            if session.bind.dialect.name == "postgresql":
                # psycopg COPY keeps the same transaction and FK contract while
                # avoiding 100k individual extended-protocol parameter messages.
                # Content-addressed bulk rebuild rows are validated before write;
                # transaction-local replica role removes per-row FK trigger cost.
                _postgres_bulk_write_settings(session)
                session.flush()
                raw_connection = session.connection().connection.driver_connection
                with raw_connection.cursor() as cursor:
                    with cursor.copy(
                        "COPY wealth_source_manifest_items "
                        "(id, workspace_id, manifest_id, item_kind, item_identity, revision, content_digest, "
                        "evidence_occurred_at, evidence_kind, evidence_contribution, evidence_scope_fold_identity, evidence_safe_metadata) "
                        "FROM STDIN"
                    ) as copy:
                        for item in items:
                            copy.write_row((
                                _manifest_item_id(source_watermark, item.item_kind, item.identity, item.revision),
                                self._workspace_id, source_watermark, item.item_kind, item.identity,
                                item.revision, item.content_digest, item.occurred_at, item.evidence_kind,
                                item.contribution, item.scope_fold_identity,
                                "{}" if item.safe_metadata is None else canonical_bytes(item.safe_metadata).decode("utf-8"),
                            ))
            else:
                # SQLite's DB-API executemany skips SQLAlchemy's 100k mapping
                # and ORM bind-processing allocations while retaining this
                # transaction and the same manifest FK.  All values are
                # converted with the model's SQLite representation.
                session.flush()
                raw_connection = session.connection().connection.driver_connection
                cursor = raw_connection.cursor()
                try:
                    cursor.executemany(
                        "INSERT INTO wealth_source_manifest_items "
                        "(id, workspace_id, manifest_id, item_kind, item_identity, revision, content_digest, "
                        "evidence_occurred_at, evidence_kind, evidence_contribution, evidence_scope_fold_identity, evidence_safe_metadata) "
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        (
                            (
                                _manifest_item_id(source_watermark, item.item_kind, item.identity, item.revision),
                                self._workspace_id, source_watermark, item.item_kind, item.identity,
                                item.revision, item.content_digest,
                                # Match SQLite DateTime's fixed-width lexical
                                # representation.  Omitting ``.000000`` makes
                                # a midnight value sort before a query bound for
                                # the same instant.
                                None if item.occurred_at is None else item.occurred_at.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M:%S.%f"),
                                item.evidence_kind,
                                None if item.contribution is None else format(item.contribution, "f"),
                                item.scope_fold_identity,
                                "{}" if item.safe_metadata is None else canonical_bytes(item.safe_metadata).decode("utf-8"),
                            )
                            for item in items
                        ),
                    )
                finally:
                    cursor.close()
        return source_watermark

    def store_component(self, component, canonical_payload: str) -> None:
        """Store the immutable component identity alongside its empty evidence manifest."""
        with self._sessions.begin() as session:
            if session.get(WealthEvidenceManifestModel, component.evidence_ref.evidence_manifest_id) is None:
                session.add(WealthEvidenceManifestModel(
                    manifest_id=component.evidence_ref.evidence_manifest_id,
                    workspace_id=self._workspace_id, result_revision=component.result_revision,
                    ordering_version="v1", canonical_digest=canonical_digest(()),
                ))
            if session.get(WealthComponentModel, component.component_id) is None:
                session.add(WealthComponentModel(
                    component_id=component.component_id, workspace_id=self._workspace_id,
                    component_key=component.component_key, result_revision=component.result_revision,
                    kind=component.kind.value, status=component.status.name.lower(), amount=component.amount,
                    evidence_manifest_id=component.evidence_ref.evidence_manifest_id,
                    canonical_payload=canonical_payload,
                ))

    def store_components(self, components, *, source_manifest_id: str | None = None, selection_by_component=None) -> None:
        """Batch immutable component/manifests for a daily build without changing IDs."""
        if not components:
            return
        selection_by_component = selection_by_component or {}
        with self._sessions.begin() as session:
            # Cold rebuilds often write into an empty workspace projection.  Skip
            # the large IN-list probe when no component rows exist yet.
            has_existing = session.scalar(select(func.count()).select_from(WealthComponentModel).where(
                WealthComponentModel.workspace_id == self._workspace_id,
            )) not in (None, 0)
            existing = set()
            if has_existing:
                component_ids = [item.component_id for item in components]
                for offset in range(0, len(component_ids), 2_000):
                    chunk = component_ids[offset:offset + 2_000]
                    existing.update(session.scalars(select(WealthComponentModel.component_id).where(
                        WealthComponentModel.workspace_id == self._workspace_id,
                        WealthComponentModel.component_id.in_(chunk),
                    )))
            empty_manifest_digest = canonical_digest(())
            material = []
            for component in components:
                if component.component_id in existing:
                    continue
                material.append((
                    component.evidence_ref.evidence_manifest_id,
                    self._workspace_id,
                    component.result_revision,
                    "v1",
                    empty_manifest_digest,
                    source_manifest_id,
                    canonical_bytes(selection_by_component.get(component.component_id, {})).decode("utf-8"),
                    component.component_id,
                    component.component_key,
                    component.kind.value,
                    component.status.name.lower(),
                    component.amount,
                    canonical_bytes(component).decode("utf-8"),
                ))
            if not material:
                return
            dialect = session.bind.dialect.name
            if dialect == "postgresql":
                _postgres_bulk_write_settings(session)
                session.flush()
                raw_connection = session.connection().connection.driver_connection
                with raw_connection.cursor() as cursor:
                    with cursor.copy(
                        "COPY wealth_evidence_manifests "
                        "(manifest_id, workspace_id, result_revision, ordering_version, canonical_digest, "
                        "source_manifest_id, selection_payload) FROM STDIN"
                    ) as copy:
                        for row in material:
                            copy.write_row(row[:7])
                    with cursor.copy(
                        "COPY wealth_components "
                        "(component_id, workspace_id, component_key, result_revision, kind, status, amount, "
                        "evidence_manifest_id, canonical_payload) FROM STDIN"
                    ) as copy:
                        for (
                            manifest_id, workspace_id, result_revision, _ordering, _digest,
                            _source_manifest_id, _selection, component_id, component_key, kind, status,
                            amount, canonical_payload,
                        ) in material:
                            copy.write_row((
                                component_id, workspace_id, component_key, result_revision, kind, status,
                                amount, manifest_id, canonical_payload,
                            ))
                return
            if dialect == "sqlite":
                session.flush()
                raw_connection = session.connection().connection.driver_connection
                cursor = raw_connection.cursor()
                try:
                    cursor.executemany(
                        "INSERT INTO wealth_evidence_manifests "
                        "(manifest_id, workspace_id, result_revision, ordering_version, canonical_digest, "
                        "source_manifest_id, selection_payload) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        (row[:7] for row in material),
                    )
                    cursor.executemany(
                        "INSERT INTO wealth_components "
                        "(component_id, workspace_id, component_key, result_revision, kind, status, amount, "
                        "evidence_manifest_id, canonical_payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        (
                            (
                                component_id, workspace_id, component_key, result_revision, kind, status,
                                None if amount is None else format(amount, "f"),
                                manifest_id, canonical_payload,
                            )
                            for (
                                manifest_id, workspace_id, result_revision, _ordering, _digest,
                                _source_manifest_id, _selection, component_id, component_key, kind, status,
                                amount, canonical_payload,
                            ) in material
                        ),
                    )
                finally:
                    cursor.close()
                return
            for (
                manifest_id, workspace_id, result_revision, ordering_version, empty_digest,
                source_manifest, selection_payload, component_id, component_key, kind, status,
                amount, canonical_payload,
            ) in material:
                session.add(WealthEvidenceManifestModel(
                    manifest_id=manifest_id, workspace_id=workspace_id,
                    result_revision=result_revision, ordering_version=ordering_version,
                    canonical_digest=empty_digest, source_manifest_id=source_manifest,
                    selection_payload=selection_payload,
                ))
                session.add(WealthComponentModel(
                    component_id=component_id, workspace_id=workspace_id,
                    component_key=component_key, result_revision=result_revision,
                    kind=kind, status=status, amount=amount,
                    evidence_manifest_id=manifest_id, canonical_payload=canonical_payload,
                ))

    def store_daily_results(self, rows, source_revision: str, coverage_rows=()) -> None:
        """Batch an immutable daily result set; existing content-addresses are retained.

        Optional ``coverage_rows`` are written in the same transaction after the
        daily result rows so coverage FKs resolve without a second commit.
        """
        material_rows = tuple(rows)
        coverage_prepared = self._prepare_coverage_rows(coverage_rows)
        if not material_rows and not coverage_prepared:
            return
        with self._sessions.begin() as session:
            if session.bind.dialect.name == "postgresql":
                _postgres_bulk_write_settings(session)
            if material_rows:
                has_existing = session.scalar(select(func.count()).select_from(WealthDailyResultModel).where(
                    WealthDailyResultModel.workspace_id == self._workspace_id,
                )) not in (None, 0)
                existing = set()
                if has_existing:
                    digests = [digest for _local_date, digest, _payload in material_rows]
                    for offset in range(0, len(digests), 2_000):
                        chunk = digests[offset:offset + 2_000]
                        existing.update(session.scalars(select(WealthDailyResultModel.result_digest).where(
                            WealthDailyResultModel.workspace_id == self._workspace_id,
                            WealthDailyResultModel.result_digest.in_(chunk),
                        )))
                # Daily rows are O(days); keep ORM defaults (created_at) while still
                # batching the large coverage write in this same transaction.
                session.add_all(WealthDailyResultModel(
                    result_digest=digest, workspace_id=self._workspace_id, local_date=local_date,
                    calculation_version="wealth-attribution-v0.1",
                    valuation_policy_version="valuation-v0.1",
                    source_revision=source_revision, result_revision=canonical_digest(payload),
                    canonical_payload=payload,
                ) for local_date, digest, payload in material_rows if digest not in existing)
            if coverage_prepared:
                self._insert_coverage_rows(session, coverage_prepared)

    def _prepare_coverage_rows(self, rows):
        prepared = []
        seen = set()
        for result_digest, local_date, source_revision, owner, identity_kind, identity, disposition in rows:
            if owner is None:
                continue
            identifier = canonical_digest({
                "workspace": self._workspace_id, "result": result_digest,
                "owner": owner, "kind": identity_kind, "identity": identity,
            })
            if identifier in seen:
                continue
            seen.add(identifier)
            prepared.append((
                identifier, self._workspace_id, result_digest, local_date, source_revision,
                owner, identity_kind, identity,
                disposition.value if hasattr(disposition, "value") else disposition,
            ))
        return prepared

    def _insert_coverage_rows(self, session, prepared) -> None:
        if not prepared:
            return
        has_existing = session.scalar(select(func.count()).select_from(WealthCoverageDispositionModel).where(
            WealthCoverageDispositionModel.workspace_id == self._workspace_id,
        )) not in (None, 0)
        if has_existing:
            identifiers = [row[0] for row in prepared]
            existing = set()
            for offset in range(0, len(identifiers), 2_000):
                chunk = identifiers[offset:offset + 2_000]
                existing.update(session.scalars(select(WealthCoverageDispositionModel.id).where(
                    WealthCoverageDispositionModel.id.in_(chunk),
                )))
            material = [row for row in prepared if row[0] not in existing]
        else:
            material = prepared
        if not material:
            return
        dialect = session.bind.dialect.name
        if dialect == "postgresql":
            # Same COPY path as source-manifest bulk insert: one transaction,
            # no 20k extended-protocol round trips, FK contract preserved.
            session.flush()
            raw_connection = session.connection().connection.driver_connection
            with raw_connection.cursor() as cursor:
                with cursor.copy(
                    "COPY wealth_coverage_dispositions "
                    "(id, workspace_id, result_digest, local_date, source_revision, "
                    "owner_account_id, identity_kind, identity, disposition) "
                    "FROM STDIN"
                ) as copy:
                    for row in material:
                        copy.write_row(row)
            return
        if dialect == "sqlite":
            # Match store_source_manifest: DB-API executemany avoids ORM
            # mapping overhead while retaining the open transaction.
            session.flush()
            raw_connection = session.connection().connection.driver_connection
            cursor = raw_connection.cursor()
            try:
                cursor.executemany(
                    "INSERT INTO wealth_coverage_dispositions "
                    "(id, workspace_id, result_digest, local_date, source_revision, "
                    "owner_account_id, identity_kind, identity, disposition) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    material,
                )
            finally:
                cursor.close()
            return
        session.add_all(WealthCoverageDispositionModel(
            id=identifier, workspace_id=workspace_id, result_digest=result_digest,
            local_date=local_date, source_revision=source_revision,
            owner_account_id=owner, identity_kind=identity_kind, identity=identity,
            disposition=disposition,
        ) for identifier, workspace_id, result_digest, local_date, source_revision, owner, identity_kind, identity, disposition in material)

    def store_coverage_dispositions(self, rows) -> None:
        """Persist per-result owned coverage dispositions keyed by owner account.

        ``rows`` is an iterable of
        ``(result_digest, local_date, source_revision, owner_account_id, identity_kind, identity, disposition)``.
        Ownerless identities cannot satisfy the account FK and are omitted; their
        fail-closed evidence is stored separately.
        """
        prepared = self._prepare_coverage_rows(rows)
        if not prepared:
            return
        with self._sessions.begin() as session:
            self._insert_coverage_rows(session, prepared)

    def index_generation_days(self, build_revision: str, rows) -> None:
        with self._sessions.begin() as session:
            ids = [canonical_digest({"build": build_revision, "date": local_date}) for local_date, _digest, _payload in rows]
            existing = set(session.scalars(select(WealthGenerationDayModel.id).where(
                WealthGenerationDayModel.workspace_id == self._workspace_id,
                WealthGenerationDayModel.id.in_(ids),
            )))
            session.add_all(WealthGenerationDayModel(
                id=identifier, workspace_id=self._workspace_id, build_revision=build_revision,
                local_date=local_date, result_digest=digest, missing_reason=None,
            ) for identifier, (local_date, digest, _payload) in zip(ids, rows, strict=True) if identifier not in existing)

    def store_evidence(self, component_id: str, result_revision: str, evidence: tuple[EvidenceItem, ...]) -> str:
        manifest_id = canonical_digest({"component": component_id, "result": result_revision, "ordering": "v1"})
        with self._sessions.begin() as session:
            if session.get(WealthEvidenceManifestModel, manifest_id) is None:
                session.add(WealthEvidenceManifestModel(
                    manifest_id=manifest_id, workspace_id=self._workspace_id, result_revision=result_revision,
                    ordering_version="v1", canonical_digest=canonical_digest(evidence),
                ))
            if session.get(WealthComponentModel, component_id) is None:
                session.add(WealthComponentModel(
                    component_id=component_id, workspace_id=self._workspace_id, component_key=component_id,
                    result_revision=result_revision, kind="unexplained_adjustment", status="complete", amount=None,
                    evidence_manifest_id=manifest_id, canonical_payload="{}",
                ))
            for item in evidence:
                if session.get(WealthEvidenceItemModel, item.evidence_identity) is None:
                    session.add(WealthEvidenceItemModel(
                        evidence_identity=item.evidence_identity, workspace_id=self._workspace_id,
                        source_identity=item.source_identity, source_revision=item.source_revision,
                        occurred_at=item.occurred_at, evidence_kind=item.evidence_kind,
                        contribution=item.contribution, safe_metadata="{}",
                    ))
                link_id = canonical_digest({"manifest": manifest_id, "fold": item.scope_fold_identity})
                if session.get(WealthEvidenceManifestItemModel, link_id) is None:
                    session.add(WealthEvidenceManifestItemModel(
                        id=link_id, workspace_id=self._workspace_id, manifest_id=manifest_id,
                        evidence_identity=item.evidence_identity, scope_fold_identity=item.scope_fold_identity,
                        contribution=item.contribution,
                    ))
        return manifest_id

    def store_evidence_batch(self, entries) -> None:
        """Persist result-scoped evidence for a build without per-item round trips."""
        evidence_rows = {}
        links = {}
        for component, evidence in entries:
            manifest_id = component.evidence_ref.evidence_manifest_id
            for item in evidence:
                evidence_rows.setdefault(item.evidence_identity, {
                    "evidence_identity": item.evidence_identity, "workspace_id": self._workspace_id,
                    "source_identity": item.source_identity, "source_revision": item.source_revision,
                    "occurred_at": item.occurred_at, "evidence_kind": item.evidence_kind,
                    "contribution": item.contribution, "safe_metadata": canonical_bytes(item.safe_metadata or {}).decode("utf-8"),
                })
                identifier = _manifest_item_id(manifest_id, item.scope_fold_identity)
                links.setdefault(identifier, {
                    "id": identifier, "workspace_id": self._workspace_id, "manifest_id": manifest_id,
                    "evidence_identity": item.evidence_identity,
                    "scope_fold_identity": item.scope_fold_identity, "contribution": item.contribution,
                })
        if not evidence_rows:
            return
        with self._sessions.begin() as session:
            dialect = session.bind.dialect.name
            first_evidence_id = next(iter(evidence_rows))
            first_link_id = next(iter(links))
            evidence_exists = session.get(WealthEvidenceItemModel, first_evidence_id) is not None
            link_exists = session.get(WealthEvidenceManifestItemModel, first_link_id) is not None
            if dialect == "postgresql" and not evidence_exists and not link_exists:
                raw_connection = session.connection().connection.driver_connection
                with raw_connection.cursor() as cursor:
                    with cursor.copy(
                        "COPY wealth_evidence_items "
                        "(evidence_identity, workspace_id, source_identity, source_revision, occurred_at, evidence_kind, contribution, safe_metadata) "
                        "FROM STDIN"
                    ) as copy:
                        for row in evidence_rows.values():
                            copy.write_row(tuple(row.values()))
                    with cursor.copy(
                        "COPY wealth_evidence_manifest_items "
                        "(id, workspace_id, manifest_id, evidence_identity, scope_fold_identity, contribution) FROM STDIN"
                    ) as copy:
                        for row in links.values():
                            copy.write_row(tuple(row.values()))
                return
            if dialect == "sqlite":
                from sqlalchemy.dialects.sqlite import insert as dialect_insert
                evidence_insert = dialect_insert(WealthEvidenceItemModel).on_conflict_do_nothing(
                    index_elements=[WealthEvidenceItemModel.evidence_identity]
                )
                link_insert = dialect_insert(WealthEvidenceManifestItemModel).on_conflict_do_nothing(
                    index_elements=[WealthEvidenceManifestItemModel.id]
                )
            elif dialect == "postgresql":
                from sqlalchemy.dialects.postgresql import insert as dialect_insert
                evidence_insert = dialect_insert(WealthEvidenceItemModel).on_conflict_do_nothing(
                    index_elements=[WealthEvidenceItemModel.evidence_identity]
                )
                link_insert = dialect_insert(WealthEvidenceManifestItemModel).on_conflict_do_nothing(
                    index_elements=[WealthEvidenceManifestItemModel.id]
                )
            else:
                evidence_insert = insert(WealthEvidenceItemModel)
                link_insert = insert(WealthEvidenceManifestItemModel)
            session.execute(evidence_insert, tuple(evidence_rows.values()))
            session.execute(link_insert, tuple(links.values()))

    def store_daily_result(self, result_digest: str, local_date: str, source_revision: str, canonical_payload: str) -> None:
        with self._sessions.begin() as session:
            if session.get(WealthDailyResultModel, result_digest) is None:
                session.add(WealthDailyResultModel(
                    result_digest=result_digest, workspace_id=self._workspace_id, local_date=local_date,
                    calculation_version="wealth-attribution-v0.1", valuation_policy_version="valuation-v0.1",
                    source_revision=source_revision, result_revision=canonical_digest(canonical_payload),
                    canonical_payload=canonical_payload,
                ))

    def create_generation(self, build_revision: str, source_watermark: str, manifest_id: str, date_from: str, date_to: str) -> None:
        with self._sessions.begin() as session:
            if session.get(WealthGenerationModel, build_revision) is None:
                session.add(WealthGenerationModel(
                    build_revision=build_revision, workspace_id=self._workspace_id, source_watermark=source_watermark,
                    source_manifest_id=manifest_id, calculation_version="wealth-attribution-v0.1",
                    valuation_policy_version="valuation-v0.1", date_from=date_from, date_to=date_to,
                    expected_active_revision=self.active_generation(), state="staging",
                    canonical_manifest_digest=canonical_digest(manifest_id),
                ))

    def index_generation_day(self, build_revision: str, local_date: str, result_digest: str | None) -> None:
        identifier = canonical_digest({"build": build_revision, "date": local_date})
        with self._sessions.begin() as session:
            if session.get(WealthGenerationDayModel, identifier) is None:
                session.add(WealthGenerationDayModel(
                    id=identifier, workspace_id=self._workspace_id, build_revision=build_revision,
                    local_date=local_date, result_digest=result_digest,
                    missing_reason=None if result_digest else "daily_point_missing",
                ))

    def _assert_publishable_generation(self, session, build_revision: str) -> WealthGenerationModel:
        """Fail closed when staged content is incomplete or parents are missing.

        PostgreSQL bulk staging may use transaction-local
        ``session_replication_role=replica`` and therefore skip FK triggers.
        Publish still fails closed with generation-scoped, set-based parent
        checks before the active-pointer CAS.  ``synchronous_commit=off`` is
        never applied on this publish transaction.
        """
        generation = session.scalar(select(WealthGenerationModel).where(
            WealthGenerationModel.workspace_id == self._workspace_id,
            WealthGenerationModel.build_revision == build_revision,
        ))
        if generation is None:
            raise ValueError("wealth.build_incomplete")
        days = session.scalars(select(WealthGenerationDayModel).where(
            WealthGenerationDayModel.workspace_id == self._workspace_id,
            WealthGenerationDayModel.build_revision == build_revision,
        )).all()
        if not days or any(day.result_digest is None for day in days):
            raise ValueError("wealth.build_incomplete")
        result_digests = {day.result_digest for day in days if day.result_digest is not None}
        local_dates = {day.local_date for day in days}
        result_payloads = ()
        if result_digests:
            digest_tuple = tuple(result_digests)
            known_results = set(session.scalars(select(WealthDailyResultModel.result_digest).where(
                WealthDailyResultModel.workspace_id == self._workspace_id,
                WealthDailyResultModel.result_digest.in_(digest_tuple),
            )))
            if known_results != result_digests:
                raise ValueError("wealth.build_incomplete")
            result_payloads = session.scalars(select(WealthDailyResultModel.canonical_payload).where(
                WealthDailyResultModel.workspace_id == self._workspace_id,
                WealthDailyResultModel.result_digest.in_(digest_tuple),
            )).all()
        # Coverage written for this generation (source watermark + day set, or direct
        # generation digests) must resolve same-workspace daily-result parents.
        coverage_scope = (
            WealthCoverageDispositionModel.workspace_id == self._workspace_id,
        )
        coverage_filters = [
            WealthCoverageDispositionModel.result_digest.in_(tuple(result_digests))
        ] if result_digests else []
        if local_dates:
            coverage_filters.append(
                (WealthCoverageDispositionModel.source_revision == generation.source_watermark)
                & (WealthCoverageDispositionModel.local_date.in_(tuple(local_dates)))
            )
        if coverage_filters:
            orphan_coverage = session.scalar(
                select(func.count())
                .select_from(WealthCoverageDispositionModel)
                .outerjoin(
                    WealthDailyResultModel,
                    (WealthDailyResultModel.result_digest == WealthCoverageDispositionModel.result_digest)
                    & (WealthDailyResultModel.workspace_id == WealthCoverageDispositionModel.workspace_id),
                )
                .where(
                    *coverage_scope,
                    or_(*coverage_filters),
                    WealthDailyResultModel.result_digest.is_(None),
                )
            )
            if orphan_coverage:
                raise ValueError("wealth.build_incomplete")
        source_manifest = session.scalar(select(WealthSourceManifestModel.manifest_id).where(
            WealthSourceManifestModel.workspace_id == self._workspace_id,
            WealthSourceManifestModel.manifest_id == generation.source_manifest_id,
        ))
        if source_manifest is None:
            raise ValueError("wealth.build_incomplete")

        component_ids: set[str] = set()
        for payload in result_payloads:
            try:
                body = json.loads(payload)
            except (TypeError, ValueError):
                continue
            for item in body.get("components") or ():
                if isinstance(item, dict):
                    component_id = item.get("component_id")
                    if isinstance(component_id, str) and component_id:
                        component_ids.add(component_id)
        if component_ids:
            component_id_list = list(component_ids)
            for offset in range(0, len(component_id_list), 2_000):
                chunk = tuple(component_id_list[offset:offset + 2_000])
                # Component rows referenced by this generation must exist in-workspace
                # and resolve evidence manifests with matching workspace parents.
                known_components = set(session.scalars(select(WealthComponentModel.component_id).where(
                    WealthComponentModel.workspace_id == self._workspace_id,
                    WealthComponentModel.component_id.in_(chunk),
                )))
                if known_components != set(chunk):
                    raise ValueError("wealth.build_incomplete")
                orphan_components = session.scalar(
                    select(func.count())
                    .select_from(WealthComponentModel)
                    .outerjoin(
                        WealthEvidenceManifestModel,
                        (WealthEvidenceManifestModel.manifest_id == WealthComponentModel.evidence_manifest_id)
                        & (WealthEvidenceManifestModel.workspace_id == WealthComponentModel.workspace_id),
                    )
                    .where(
                        WealthComponentModel.workspace_id == self._workspace_id,
                        WealthComponentModel.component_id.in_(chunk),
                        WealthEvidenceManifestModel.manifest_id.is_(None),
                    )
                )
                if orphan_components:
                    raise ValueError("wealth.build_incomplete")
                manifest_ids = set(session.scalars(select(WealthComponentModel.evidence_manifest_id).where(
                    WealthComponentModel.workspace_id == self._workspace_id,
                    WealthComponentModel.component_id.in_(chunk),
                )))
                if not manifest_ids:
                    continue
                manifest_id_list = list(manifest_ids)
                for manifest_offset in range(0, len(manifest_id_list), 2_000):
                    manifest_chunk = tuple(manifest_id_list[manifest_offset:manifest_offset + 2_000])
                    # Evidence links for generation-scoped manifests must resolve
                    # both the manifest and item parents under the same workspace.
                    orphan_link_manifests = session.scalar(
                        select(func.count())
                        .select_from(WealthEvidenceManifestItemModel)
                        .outerjoin(
                            WealthEvidenceManifestModel,
                            (WealthEvidenceManifestModel.manifest_id == WealthEvidenceManifestItemModel.manifest_id)
                            & (WealthEvidenceManifestModel.workspace_id == WealthEvidenceManifestItemModel.workspace_id),
                        )
                        .where(
                            WealthEvidenceManifestItemModel.workspace_id == self._workspace_id,
                            WealthEvidenceManifestItemModel.manifest_id.in_(manifest_chunk),
                            WealthEvidenceManifestModel.manifest_id.is_(None),
                        )
                    )
                    if orphan_link_manifests:
                        raise ValueError("wealth.build_incomplete")
                    orphan_link_items = session.scalar(
                        select(func.count())
                        .select_from(WealthEvidenceManifestItemModel)
                        .outerjoin(
                            WealthEvidenceItemModel,
                            (WealthEvidenceItemModel.evidence_identity == WealthEvidenceManifestItemModel.evidence_identity)
                            & (WealthEvidenceItemModel.workspace_id == WealthEvidenceManifestItemModel.workspace_id),
                        )
                        .where(
                            WealthEvidenceManifestItemModel.workspace_id == self._workspace_id,
                            WealthEvidenceManifestItemModel.manifest_id.in_(manifest_chunk),
                            WealthEvidenceItemModel.evidence_identity.is_(None),
                        )
                    )
                    if orphan_link_items:
                        raise ValueError("wealth.build_incomplete")
        return generation

    def publish_generation(self, build_revision: str) -> None:
        with self._sessions.begin() as session:
            # SQLite writers serialize through an immediate write lock; PostgreSQL
            # uses row-level locking plus a conditional active-pointer CAS below.
            if session.bind.dialect.name == "sqlite":
                session.connection().exec_driver_sql("BEGIN IMMEDIATE")
            generation = self._assert_publishable_generation(session, build_revision)
            expected = generation.expected_active_revision
            active = session.scalar(
                select(WealthActiveManifestModel)
                .where(WealthActiveManifestModel.workspace_id == self._workspace_id)
                .with_for_update()
            )
            current_revision = active.build_revision if active is not None else None
            if current_revision == build_revision:
                # Same immutable inputs already own the active pointer: an idempotent retry
                # must not turn its original expected-active fence into a stale failure.
                return
            if expected != current_revision:
                raise ValueError("wealth.build_stale")
            now = datetime.now(timezone.utc)
            if active is not None:
                previous = session.get(WealthGenerationModel, active.build_revision)
                if previous is not None:
                    previous.state = "superseded"
                # Conditional CAS: another concurrent publisher that advanced the
                # pointer after our lock acquisition cannot both succeed.
                result = session.execute(
                    update(WealthActiveManifestModel)
                    .where(
                        WealthActiveManifestModel.workspace_id == self._workspace_id,
                        WealthActiveManifestModel.build_revision.is_not_distinct_from(expected),
                    )
                    .values(
                        build_revision=build_revision,
                        manifest_revision=WealthActiveManifestModel.manifest_revision + 1,
                        updated_at=now,
                    )
                )
                if result.rowcount != 1:
                    raise ValueError("wealth.build_stale")
            else:
                if expected is not None:
                    raise ValueError("wealth.build_stale")
                session.add(WealthActiveManifestModel(
                    workspace_id=self._workspace_id, build_revision=build_revision,
                    manifest_revision=1, updated_at=now,
                ))
            generation.state = "active"
            generation.completed_at = now

    def active_generation(self) -> str | None:
        with self._sessions() as session:
            active = session.get(WealthActiveManifestModel, self._workspace_id)
            return active.build_revision if active is not None else None

    def daily_result(self, result_digest: str) -> str | None:
        with self._sessions() as session:
            result = session.scalar(select(WealthDailyResultModel).where(
                WealthDailyResultModel.workspace_id == self._workspace_id,
                WealthDailyResultModel.result_digest == result_digest,
            ))
            return None if result is None else result.canonical_payload

    def active_daily_payload(self, local_date: str) -> str | None:
        with self._sessions() as session:
            active = session.get(WealthActiveManifestModel, self._workspace_id)
            if active is None:
                return None
            row = session.scalar(select(WealthDailyResultModel).join(
                WealthGenerationDayModel, WealthGenerationDayModel.result_digest == WealthDailyResultModel.result_digest
            ).where(WealthGenerationDayModel.workspace_id == self._workspace_id, WealthGenerationDayModel.build_revision == active.build_revision, WealthGenerationDayModel.local_date == local_date))
            return None if row is None else row.canonical_payload

    def active_daily_payloads(self, date_from: str, date_to: str) -> tuple[str, ...]:
        """Read one immutable active generation in date order for the cache-hit path."""
        with self._sessions() as session:
            active = session.get(WealthActiveManifestModel, self._workspace_id)
            if active is None:
                return ()
            rows = session.scalars(select(WealthDailyResultModel.canonical_payload).join(
                WealthGenerationDayModel,
                WealthGenerationDayModel.result_digest == WealthDailyResultModel.result_digest,
            ).where(
                WealthGenerationDayModel.workspace_id == self._workspace_id,
                WealthGenerationDayModel.build_revision == active.build_revision,
                WealthGenerationDayModel.local_date >= date_from,
                WealthGenerationDayModel.local_date < date_to,
            ).order_by(WealthGenerationDayModel.local_date)).all()
        return tuple(rows)

    def component_evidence(self, component_id: str, result_revision: str):
        with self._sessions() as session:
            component = session.scalar(select(WealthComponentModel).where(
                WealthComponentModel.workspace_id == self._workspace_id,
                WealthComponentModel.component_id == component_id,
                WealthComponentModel.result_revision == result_revision,
            ))
            if component is None:
                return None
            manifest = session.get(WealthEvidenceManifestModel, component.evidence_manifest_id)
            if manifest is None:
                return None
            direct = ()
            selection = json.loads(manifest.selection_payload)
            if manifest.source_manifest_id and selection.get("kinds") and (selection.get("local_date") or selection.get("date_from")):
                day_start = datetime.fromisoformat(selection.get("date_from", selection.get("local_date"))).replace(tzinfo=ZoneInfo("Asia/Shanghai"))
                day_end = datetime.fromisoformat(selection.get("date_to", "")).replace(tzinfo=ZoneInfo("Asia/Shanghai")) if selection.get("date_to") else day_start + timedelta(days=1)
                direct_rows = session.scalars(select(WealthSourceManifestItemModel).where(
                    WealthSourceManifestItemModel.workspace_id == self._workspace_id,
                    WealthSourceManifestItemModel.manifest_id == manifest.source_manifest_id,
                    WealthSourceManifestItemModel.evidence_occurred_at >= day_start,
                    WealthSourceManifestItemModel.evidence_occurred_at < day_end,
                    WealthSourceManifestItemModel.evidence_kind.in_(selection["kinds"]),
                    WealthSourceManifestItemModel.evidence_contribution.is_not(None),
                ).order_by(
                    WealthSourceManifestItemModel.evidence_occurred_at,
                    WealthSourceManifestItemModel.item_identity,
                    WealthSourceManifestItemModel.evidence_kind,
                    WealthSourceManifestItemModel.id,
                )).all()
                direct = tuple(EvidenceItem(
                    f"source-manifest:{row.id}", row.item_identity, row.revision,
                    row.evidence_occurred_at, row.evidence_kind, row.evidence_contribution,
                    row.evidence_scope_fold_identity, json.loads(row.evidence_safe_metadata),
                ) for row in direct_rows)
            rows = session.execute(select(WealthEvidenceItemModel, WealthEvidenceManifestItemModel).join(
                WealthEvidenceManifestItemModel,
                WealthEvidenceManifestItemModel.evidence_identity == WealthEvidenceItemModel.evidence_identity,
            ).where(
                WealthEvidenceItemModel.workspace_id == self._workspace_id,
                WealthEvidenceManifestItemModel.workspace_id == self._workspace_id,
                WealthEvidenceManifestItemModel.manifest_id == component.evidence_manifest_id,
            ).order_by(WealthEvidenceItemModel.occurred_at, WealthEvidenceItemModel.source_identity,
                       WealthEvidenceItemModel.evidence_kind, WealthEvidenceItemModel.evidence_identity)).all()
        derived = tuple(EvidenceItem(
            evidence.evidence_identity, evidence.source_identity, evidence.source_revision, evidence.occurred_at,
            evidence.evidence_kind, link.contribution, link.scope_fold_identity,
        ) for evidence, link in rows)
        return tuple(sorted(
            direct + derived,
            key=lambda item: (
                item.occurred_at.isoformat(), item.source_identity,
                item.evidence_kind, item.evidence_identity,
            ),
        ))
