"""收支投影的全量构建编排。"""
from __future__ import annotations

import logging

from ft.domain.cash_projection import CashProjectionError, RULES_VERSION, build_cash_projections


logger = logging.getLogger(__name__)


class ProjectionUnavailableError(RuntimeError):
    code = "projection.unavailable"


class CashProjectionService:
    """在一个数据库事务中全量或增量构建并原子替换收支投影。"""

    def __init__(self, session_factory, workspace_id: str):
        self._session_factory = session_factory
        self._workspace_id = workspace_id

    def status(self) -> dict:
        from ft.adapters.relational.projections import RelationalCashProjectionRepository

        with self._session_factory() as session:
            return RelationalCashProjectionRepository(session, self._workspace_id).status()

    def rebuild(self) -> dict:
        from ft.adapters.relational.projections import RelationalCashProjectionRepository

        failure_context = {"active_dataset_id": None, "source_revision": 0}
        try:
            with self._session_factory.begin() as session:
                if (
                    session.bind.dialect.name == "sqlite"
                    and session.bind.url.database != ":memory:"
                ):
                    session.connection().exec_driver_sql("BEGIN IMMEDIATE")
                repository = RelationalCashProjectionRepository(session, self._workspace_id)
                failure_context = repository.rebuild_failure_context()
                facts, relations = repository.read_sources()
                build = build_cash_projections(facts, relations)
                self._synchronize_component_categories(session, self._workspace_id, build)
                digest = repository.source_digest()
                dataset_id = repository.create_staging_dataset(source_digest=digest, rules_version=RULES_VERSION)
                next_version = repository.status()["projection_version"] + 1
                repository.replace_dataset(dataset_id, build, projection_version=next_version)
                return repository.publish_dataset(dataset_id, source_digest=digest, rules_version=RULES_VERSION)
        except Exception as exc:
            from sqlalchemy.exc import DBAPIError
            if isinstance(exc, DBAPIError):
                from ft.adapters.relational.runtime import storage_error

                raise storage_error(exc, str(self._session_factory.kw["bind"].url)) from exc
            self._record_failed_rebuild(
                self._session_factory,
                self._workspace_id,
                active_dataset_id=failure_context["active_dataset_id"],
                source_revision=failure_context["source_revision"],
                error_code=self._failure_code(exc),
            )
            if isinstance(exc, CashProjectionError):
                raise
            if isinstance(exc, RuntimeError) and str(exc).startswith("projection."):
                raise
            raise RuntimeError("projection.failed") from exc

    @staticmethod
    def _failure_code(exc: Exception) -> str:
        if isinstance(exc, CashProjectionError):
            return str(exc)
        if isinstance(exc, RuntimeError) and str(exc).startswith("projection."):
            return str(exc)
        return "projection.failed"

    @staticmethod
    def _record_failed_rebuild(
        session_factory,
        workspace_id: str,
        *,
        active_dataset_id: str | None,
        source_revision: int,
        error_code: str,
    ) -> None:
        from ft.adapters.relational.projections import RelationalCashProjectionRepository

        try:
            with session_factory.begin() as session:
                RelationalCashProjectionRepository(session, workspace_id).record_failed_build(
                    active_dataset_id=active_dataset_id,
                    source_revision=source_revision,
                    error_code=error_code,
                )
        except Exception:
            logger.warning("无法写入收支投影失败诊断", exc_info=True)

    @staticmethod
    def maintain_in_session(session, workspace_id: str, affected_fact_ids: set[int]) -> dict:
        """在源写入所属事务内替换受影响投影连通组。"""
        from ft.adapters.relational.projections import RelationalCashProjectionRepository

        repository = RelationalCashProjectionRepository(session, workspace_id)
        state = repository.require_ready_state_lock()
        component_ids = repository.accepted_relation_component_ids(affected_fact_ids)
        facts, relations = repository.read_sources_for_facts(component_ids)
        build = build_cash_projections(facts, relations)
        CashProjectionService._synchronize_component_categories(session, workspace_id, build)
        return repository.replace_active_components(build, component_ids, state=state)

    @staticmethod
    def _synchronize_component_categories(session, workspace_id: str, build) -> None:
        """已确认关系使用展示基准流水分类，并同步整个现金部分。"""
        from sqlalchemy import update
        from ft.adapters.relational.models import CashTransactionModel

        for projection in build.projections:
            category_id = projection.primary_record.category_id
            member_ids = [member.id for member, _roles in projection.members]
            if not member_ids:
                continue
            session.execute(update(CashTransactionModel).where(
                CashTransactionModel.workspace_id == workspace_id,
                CashTransactionModel.id.in_(member_ids),
                CashTransactionModel.deleted_at.is_(None),
            ).values(category_id=category_id))

    @staticmethod
    def maintain_if_ready_in_session(
        session,
        workspace_id: str,
        affected_fact_ids: set[int],
        *,
        new_fact_ids: set[int] | None = None,
        known_component_ids: set[int] | None = None,
    ) -> dict | None:
        from ft.adapters.relational.projections import RelationalCashProjectionRepository

        repository = RelationalCashProjectionRepository(session, workspace_id)
        state = repository.ready_state_lock_or_none()
        if state is None:
            return None
        component_ids = (
            {int(item) for item in known_component_ids}
            if known_component_ids is not None
            else repository.accepted_relation_component_ids(affected_fact_ids)
        )
        facts, relations = repository.read_sources_for_facts(component_ids)
        build = build_cash_projections(facts, relations)
        CashProjectionService._synchronize_component_categories(session, workspace_id, build)
        return repository.replace_active_components(
            build,
            component_ids,
            state=state,
            known_new_fact_ids=new_fact_ids,
        )

    @staticmethod
    def maintain_standalone_fact_if_ready_in_session(
        session, workspace_id: str, row,
    ) -> dict | None:
        """Maintain a newly created standalone cash fact with no source rereads."""
        from ft.adapters.relational.projections import RelationalCashProjectionRepository

        repository = RelationalCashProjectionRepository(session, workspace_id)
        state = repository.ready_state_lock_or_none()
        if state is None:
            return None
        build = build_cash_projections((repository._fact_from_model(row),), ())
        return repository.replace_active_components(
            build,
            {int(row.id)},
            state=state,
            known_new_fact_ids={int(row.id)},
        )

    @staticmethod
    def replace_standalone_fact_if_ready_in_session(
        session, workspace_id: str, row,
    ) -> dict | None:
        """Refresh one standalone projection from the already-loaded source row."""
        from ft.adapters.relational.projections import RelationalCashProjectionRepository

        repository = RelationalCashProjectionRepository(session, workspace_id)
        state = repository.ready_state_lock_or_none()
        if state is None:
            return None
        return repository.replace_standalone_fact(row, state=state)

    @staticmethod
    def refresh_display_fields_if_ready_in_session(
        session, workspace_id: str, fact_id: int, *, counterparty: str, note: str,
    ) -> dict | None:
        from ft.adapters.relational.projections import RelationalCashProjectionRepository

        repository = RelationalCashProjectionRepository(session, workspace_id)
        state = repository.ready_state_lock_or_none()
        if state is None:
            return None
        return repository.refresh_display_fields(
            fact_id,
            counterparty=counterparty,
            note=note,
            state=state,
        )

    @staticmethod
    def remove_if_ready_in_session(session, workspace_id: str, affected_fact_ids: set[int]) -> dict | None:
        """Remove standalone derived rows before deleting their source fact."""
        from ft.adapters.relational.projections import RelationalCashProjectionRepository

        repository = RelationalCashProjectionRepository(session, workspace_id)
        state = repository.ready_state_lock_or_none()
        if state is None:
            return None
        return repository.remove_active_components(affected_fact_ids, state=state)
