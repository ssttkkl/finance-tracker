"""工作区收支分类目录的应用服务。"""
from __future__ import annotations

from datetime import datetime, timezone
from uuid import uuid4

from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError

from ft.adapters.relational.models import CashCategoryModel, CashCategoryStateModel, CashTransactionModel, WorkspaceModel


ROOT_SCOPE = "__root__"
MAX_DEPTH = 5
_UNSET = object()


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _normalize_name(value: str) -> tuple[str, str]:
    name = " ".join(str(value or "").split())
    if not name:
        raise ValueError("category.invalid_name")
    if len(name) > 40:
        raise ValueError("category.invalid_name")
    return name, name.casefold()


class CashCategoryService:
    """在单一工作区内维护最多五层的可分配分类树。"""

    def __init__(self, sessions, workspace_id: str):
        self._sessions = sessions
        self._workspace_id = workspace_id

    def _state(self, session, *, lock: bool = False) -> CashCategoryStateModel:
        query = select(CashCategoryStateModel).where(
            CashCategoryStateModel.workspace_id == self._workspace_id,
        )
        if lock and session.bind.dialect.name == "postgresql":
            query = query.with_for_update()
        state = session.scalar(query)
        if state is None:
            workspace = session.scalar(select(WorkspaceModel.id).where(WorkspaceModel.id == self._workspace_id))
            if workspace is None:
                raise ValueError("category.workspace_not_found")
            state = CashCategoryStateModel(workspace_id=self._workspace_id, revision=0, updated_at=_now())
            session.add(state)
            session.flush()
        return state

    def _check_revision(self, state: CashCategoryStateModel, expected_revision: int | None) -> None:
        if expected_revision is not None and int(expected_revision) != int(state.revision):
            raise ValueError("category.revision_conflict")

    def _category(self, session, category_id: str, *, lock: bool = False) -> CashCategoryModel:
        query = select(CashCategoryModel).where(
            CashCategoryModel.workspace_id == self._workspace_id,
            CashCategoryModel.id == str(category_id),
        )
        if lock and session.bind.dialect.name == "postgresql":
            query = query.with_for_update()
        category = session.scalar(query)
        if category is None:
            raise ValueError("category.not_found")
        return category

    @staticmethod
    def _path_names(categories: dict[str, CashCategoryModel], category: CashCategoryModel) -> list[str]:
        result = []
        current = category
        while current is not None:
            result.append(current.name)
            current = categories.get(current.parent_id) if current.parent_id else None
        return list(reversed(result))

    def _item(self, session, category: CashCategoryModel, *, revision: int | None = None) -> dict:
        all_categories = {
            row.id: row
            for row in session.scalars(select(CashCategoryModel).where(CashCategoryModel.workspace_id == self._workspace_id)).all()
        }
        return {
            "id": category.id,
            "parent_id": category.parent_id,
            "name": category.name,
            "description": category.description,
            "path": self._path_names(all_categories, category),
            "depth": category.depth,
            "sort_order": category.sort_order,
            "revision": int(revision if revision is not None else self._state(session).revision),
        }

    def list(self) -> dict:
        with self._sessions() as session:
            state = self._state(session)
            rows = session.scalars(select(CashCategoryModel).where(
                CashCategoryModel.workspace_id == self._workspace_id,
            )).all()
            by_parent: dict[str | None, list[CashCategoryModel]] = {}
            for row in rows:
                by_parent.setdefault(row.parent_id, []).append(row)
            ordered: list[CashCategoryModel] = []
            def visit(parent_id: str | None) -> None:
                siblings = sorted(by_parent.get(parent_id, []), key=lambda row: (row.sort_order, row.id))
                for row in siblings:
                    ordered.append(row)
                    visit(row.id)
            visit(None)
            return {"revision": int(state.revision), "items": [self._item(session, row, revision=state.revision) for row in ordered]}

    def create(self, *, name: str, parent_id: str | None = None, description: str | None = None, expected_revision: int | None = None) -> dict:
        normalized_name, normalized = _normalize_name(name)
        description = str(description or "").strip() or None
        if description is not None and len(description) > 500:
            raise ValueError("category.invalid_description")
        with self._sessions.begin() as session:
            state = self._state(session, lock=True)
            self._check_revision(state, expected_revision)
            parent = self._category(session, parent_id) if parent_id else None
            depth = (parent.depth + 1) if parent else 1
            if depth > MAX_DEPTH:
                raise ValueError("category.depth_limit")
            parent_scope_key = parent.id if parent else ROOT_SCOPE
            duplicate = session.scalar(select(CashCategoryModel.id).where(
                CashCategoryModel.workspace_id == self._workspace_id,
                CashCategoryModel.parent_scope_key == parent_scope_key,
                CashCategoryModel.normalized_name == normalized,
            ))
            if duplicate is not None:
                raise ValueError("category.duplicate_name")
            category_id = uuid4().hex
            category = CashCategoryModel(
                id=category_id,
                workspace_id=self._workspace_id,
                parent_id=parent.id if parent else None,
                parent_scope_key=parent_scope_key,
                name=normalized_name,
                normalized_name=normalized,
                description=description,
                category_path=f"{parent.category_path if parent else ''}/{category_id}/",
                depth=depth,
                sort_order=self._next_sort_order(session, parent_scope_key),
                revision=1,
                created_at=_now(),
                updated_at=_now(),
            )
            session.add(category)
            state.revision += 1
            state.updated_at = _now()
            session.flush()
            return self._item(session, category, revision=state.revision)

    def update(self, category_id: str, *, name: str | None = None, description: str | None = None,
               parent_id: str | None | object = _UNSET, expected_revision: int) -> dict:
        with self._sessions.begin() as session:
            state = self._state(session, lock=True)
            self._check_revision(state, expected_revision)
            category = self._category(session, category_id, lock=True)
            moved = parent_id is not _UNSET and (str(parent_id) if parent_id is not None else None) != category.parent_id
            if moved:
                self._move_in_session(session, state, category, parent_id=parent_id)
            if name is not None:
                normalized_name, normalized = _normalize_name(name)
                duplicate = session.scalar(select(CashCategoryModel.id).where(
                    CashCategoryModel.workspace_id == self._workspace_id,
                    CashCategoryModel.parent_scope_key == category.parent_scope_key,
                    CashCategoryModel.normalized_name == normalized,
                    CashCategoryModel.id != category.id,
                ))
                if duplicate is not None:
                    raise ValueError("category.duplicate_name")
                category.name = normalized_name
                category.normalized_name = normalized
            if description is not None:
                value = str(description).strip()
                if len(value) > 500:
                    raise ValueError("category.invalid_description")
                category.description = value or None
            category.revision += 1
            category.updated_at = _now()
            if not moved:
                state.revision += 1
                state.updated_at = _now()
            session.flush()
            return self._item(session, category, revision=state.revision)

    def deletion_impact(self, category_id: str) -> dict:
        from sqlalchemy import func
        with self._sessions() as session:
            category = self._category(session, category_id)
            child_count = session.scalar(select(func.count()).select_from(CashCategoryModel).where(
                CashCategoryModel.workspace_id == self._workspace_id,
                CashCategoryModel.parent_id == category.id,
            ))
            usage_count = session.scalar(select(func.count()).select_from(CashTransactionModel).where(
                CashTransactionModel.workspace_id == self._workspace_id,
                CashTransactionModel.category_id == category.id,
                CashTransactionModel.deleted_at.is_(None),
            ))
            state = self._state(session)
            return {
                "category_id": category.id,
                "revision": int(state.revision),
                "category_revision": int(category.revision),
                "child_count": int(child_count or 0),
                "direct_usage_count": int(usage_count or 0),
            }

    def delete(
        self,
        category_id: str,
        *,
        expected_revision: int,
        expected_category_revision: int,
        expected_usage_count: int,
        confirmed: bool,
    ) -> dict:
        from sqlalchemy import func
        with self._sessions.begin() as session:
            state = self._state(session, lock=True)
            self._check_revision(state, expected_revision)
            category = self._category(session, category_id, lock=True)
            child_count = session.scalar(select(func.count()).select_from(CashCategoryModel).where(
                CashCategoryModel.workspace_id == self._workspace_id,
                CashCategoryModel.parent_id == category.id,
            ))
            if child_count:
                raise ValueError("category.has_children")
            if int(category.revision) != int(expected_category_revision):
                raise ValueError("category.impact_changed")
            usage_count = session.scalar(select(func.count()).select_from(CashTransactionModel).where(
                CashTransactionModel.workspace_id == self._workspace_id,
                CashTransactionModel.category_id == category.id,
                CashTransactionModel.deleted_at.is_(None),
            )) or 0
            if int(usage_count) != int(expected_usage_count):
                raise ValueError("category.impact_changed")
            if usage_count and not confirmed:
                raise ValueError("category.delete_confirmation_required")
            affected_ids = list(session.scalars(select(CashTransactionModel.id).where(
                CashTransactionModel.workspace_id == self._workspace_id,
                CashTransactionModel.category_id == category.id,
                CashTransactionModel.deleted_at.is_(None),
            )))
            session.execute(update(CashTransactionModel).where(
                CashTransactionModel.workspace_id == self._workspace_id,
                CashTransactionModel.category_id == category.id,
            ).values(category_id=None))
            if affected_ids:
                from ft.application.cash_projections import CashProjectionService
                CashProjectionService.maintain_if_ready_in_session(
                    session, self._workspace_id, set(affected_ids),
                )
            session.delete(category)
            state.revision += 1
            state.updated_at = _now()
            return {
                "category_id": category_id,
                "cleared_transaction_count": int(usage_count),
                "revision": int(state.revision),
            }

    def _next_sort_order(self, session, parent_scope_key: str) -> int:
        from sqlalchemy import func
        value = session.scalar(select(func.max(CashCategoryModel.sort_order)).where(
            CashCategoryModel.workspace_id == self._workspace_id,
            CashCategoryModel.parent_scope_key == parent_scope_key,
        ))
        return int(value or 0) + 1

    def move(self, category_id: str, *, parent_id: str | None, expected_revision: int) -> dict:
        with self._sessions.begin() as session:
            session.info["cash_category_workspace_id"] = self._workspace_id
            state = self._state(session, lock=True)
            self._check_revision(state, expected_revision)
            category = self._category(session, category_id, lock=True)
            affected_ids = self._move_in_session(session, state, category, parent_id=parent_id)
            if affected_ids:
                from ft.application.cash_projections import CashProjectionService
                CashProjectionService.maintain_if_ready_in_session(
                    session, self._workspace_id, affected_ids,
                )
            return self._item(session, category, revision=state.revision)

    def _move_in_session(self, session, state, category: CashCategoryModel, *, parent_id: str | None) -> set[int]:
        parent = self._category(session, parent_id, lock=True) if parent_id else None
        if parent and (parent.id == category.id or parent.category_path.startswith(category.category_path)):
            raise ValueError("category.cycle")
        new_depth = (parent.depth + 1) if parent else 1
        new_parent_scope = parent.id if parent else ROOT_SCOPE
        duplicate = session.scalar(select(CashCategoryModel.id).where(
            CashCategoryModel.workspace_id == self._workspace_id,
            CashCategoryModel.parent_scope_key == new_parent_scope,
            CashCategoryModel.normalized_name == category.normalized_name,
            CashCategoryModel.id != category.id,
        ))
        if duplicate is not None:
            raise ValueError("category.duplicate_name")
        subtree = session.scalars(select(CashCategoryModel).where(
            CashCategoryModel.workspace_id == self._workspace_id,
            CashCategoryModel.category_path.like(f"{category.category_path}%"),
        )).all()
        subtree_ids = {row.id for row in subtree}
        affected_ids = set(session.scalars(select(CashTransactionModel.id).where(
            CashTransactionModel.workspace_id == self._workspace_id,
            CashTransactionModel.category_id.in_(subtree_ids),
            CashTransactionModel.deleted_at.is_(None),
        )).all()) if subtree_ids else set()
        if any(new_depth + (row.depth - category.depth) > MAX_DEPTH for row in subtree):
            raise ValueError("category.depth_limit")
        new_parent_path = parent.category_path if parent else ""
        old_path = category.category_path
        new_root_path = f"{new_parent_path}/{category.id}/"
        category.parent_id = parent.id if parent else None
        category.parent_scope_key = new_parent_scope
        category.sort_order = self._next_sort_order(session, new_parent_scope)
        for row in subtree:
            suffix = row.category_path[len(old_path):]
            row.category_path = new_root_path + suffix
            row.depth = new_depth + (row.depth - category.depth)
            row.updated_at = _now()
        state.revision += 1
        state.updated_at = _now()
        session.flush()
        return affected_ids

    def reorder(self, category_id: str, *, direction: str, expected_revision: int) -> dict:
        if direction not in {"before", "after"}:
            raise ValueError("category.invalid_order")
        with self._sessions.begin() as session:
            state = self._state(session, lock=True)
            self._check_revision(state, expected_revision)
            category = self._category(session, category_id, lock=True)
            siblings = session.scalars(select(CashCategoryModel).where(
                CashCategoryModel.workspace_id == self._workspace_id,
                CashCategoryModel.parent_scope_key == category.parent_scope_key,
            ).order_by(CashCategoryModel.sort_order, CashCategoryModel.id).with_for_update()).all()
            index = next(index for index, item in enumerate(siblings) if item.id == category.id)
            other_index = index - 1 if direction == "before" else index + 1
            if not 0 <= other_index < len(siblings):
                return self._item(session, category, revision=state.revision)
            siblings[index].sort_order, siblings[other_index].sort_order = (
                siblings[other_index].sort_order, siblings[index].sort_order,
            )
            state.revision += 1
            state.updated_at = _now()
            category.revision += 1
            category.updated_at = _now()
            session.flush()
            return self._item(session, category, revision=state.revision)
