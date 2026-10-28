"""当前现金关系及规范候选的工作区仓库；最终图由应用服务联合校验。"""
from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timezone
import json

from sqlalchemy import and_, bindparam, case, delete, literal, select, union, union_all, update

from ft.domain.cash_relation_policy import CashRelationError, RelationEndpoint, validate_relation_roles
from ft.domain.decimal import exact_decimal
from .models import CashRelationCandidateModel, CashTransactionComponentModel, CashTransactionModel, TransactionRelationModel


_FIELDS = frozenset({"kind", "subtype", "state", "active", "primary_transaction_id", "secondary_transaction_id",
    "primary_component_id", "secondary_component_id", "anchor_transaction_id", "anchor_component_id",
    "primary_role", "secondary_role", "applied_amount", "decision_group_id", "decision_source", "evidence",
    "rule_id", "created_by", "decided_by", "decided_at", "decision_reason"})
_IDS = frozenset({"primary_transaction_id", "secondary_transaction_id", "primary_component_id",
    "secondary_component_id", "anchor_transaction_id", "anchor_component_id"})
_GRAPH_FIELDS = ("id", "kind", "subtype", "primary_transaction_id", "secondary_transaction_id",
    "primary_component_id", "secondary_component_id", "primary_role", "secondary_role", "applied_amount",
    "state", "active", "decision_group_id", "row_revision")


def _confirmed_closure_query():
    workspace = bindparam("closure_workspace")
    reachable = select(CashTransactionModel.id.label("node")).where(
        CashTransactionModel.workspace_id == workspace,
        CashTransactionModel.id.in_(bindparam("closure_seeds", expanding=True))).cte("current_relation_closure", recursive=True)
    # 两个有索引的方向分别连接，保留 SQLite 的局部查询计划。
    directions = select(literal(0).label("direction")).union_all(select(literal(1))).cte("relation_directions")
    primary = TransactionRelationModel.__table__.alias("from_primary")
    secondary = TransactionRelationModel.__table__.alias("from_secondary")
    traversal = reachable.join(directions, literal(True)).outerjoin(primary, and_(
        directions.c.direction == 0, primary.c.workspace_id == workspace,
        primary.c.primary_transaction_id == reachable.c.node, primary.c.active.is_(True), primary.c.state == "confirmed",
    )).outerjoin(secondary, and_(
        directions.c.direction == 1, secondary.c.workspace_id == workspace,
        secondary.c.secondary_transaction_id == reachable.c.node, secondary.c.active.is_(True), secondary.c.state == "confirmed",
    ))
    neighbour = case((directions.c.direction == 0, primary.c.secondary_transaction_id), else_=secondary.c.primary_transaction_id)
    reachable = reachable.union(select(neighbour).select_from(traversal).where(neighbour.is_not(None)))
    return select(reachable.c.node)


# 只复用无业务值的表达式；工作区和种子每次绑定，结果始终读取当前事务。
_CONFIRMED_CLOSURE_QUERY = _confirmed_closure_query()


def _graph_rows_query():
    workspace, ids = bindparam("graph_workspace"), bindparam("graph_parent_ids", expanding=True)
    branches = [select(TransactionRelationModel.id).where(TransactionRelationModel.workspace_id == workspace,
        TransactionRelationModel.active.is_(True), endpoint.in_(ids)) for endpoint in (
            TransactionRelationModel.primary_transaction_id, TransactionRelationModel.secondary_transaction_id,
            TransactionRelationModel.anchor_transaction_id)]
    return select(*(getattr(TransactionRelationModel, key) for key in _GRAPH_FIELDS)).where(
        TransactionRelationModel.workspace_id == workspace, TransactionRelationModel.id.in_(union(*branches)))


_GRAPH_ROWS_QUERY = _graph_rows_query()


def _postgres_closure_query():
    workspace = bindparam("closure_workspace")
    reachable = select(CashTransactionModel.id.label("node")).where(
        CashTransactionModel.workspace_id == workspace,
        CashTransactionModel.id.in_(bindparam("closure_seeds", expanding=True))).cte("current_relation_closure", recursive=True)
    relations = TransactionRelationModel.__table__
    directions = [select(neighbour.label("node")).where(
        relations.c.workspace_id == workspace, relations.c.active.is_(True),
        relations.c.state == "confirmed", endpoint == reachable.c.node,
        neighbour.is_not(None)).correlate(reachable) for endpoint, neighbour in (
            (relations.c.primary_transaction_id, relations.c.secondary_transaction_id),
            (relations.c.secondary_transaction_id, relations.c.primary_transaction_id))]
    neighbours = union_all(*directions).offset(0).lateral("neighbours")
    reachable = reachable.union(select(neighbours.c.node).select_from(reachable.join(neighbours, literal(True))))
    return select(reachable.c.node)


def _direct_graph_rows_query():
    workspace, ids = bindparam("graph_workspace"), bindparam("graph_parent_ids", expanding=True)
    return union_all(*(select(*(getattr(TransactionRelationModel, key) for key in _GRAPH_FIELDS)).where(
        TransactionRelationModel.workspace_id == workspace, TransactionRelationModel.active.is_(True),
        endpoint.in_(ids)) for endpoint in (TransactionRelationModel.primary_transaction_id,
            TransactionRelationModel.secondary_transaction_id, TransactionRelationModel.anchor_transaction_id)))


_POSTGRES_CLOSURE_QUERY = _postgres_closure_query()
_POSTGRES_GRAPH_ROWS_QUERY = _direct_graph_rows_query()


def _batches(values):
    values = tuple(values)
    for start in range(0, len(values), 500):
        yield values[start:start + 500]


def _id(value):
    if isinstance(value, str) and 1 <= len(value) <= 19 and value.isascii() and value.isdigit():
        value = int(value)
    if type(value) is int and 0 < value < 2 ** 63:
        return value
    raise CashRelationError("relation_data_invalid")


def legacy_relation_roles(kind, subtype, amount):
    """只迁接旧服务已经形成的决定；新建议必须使用来源证据发现角色。"""
    if kind == "payment_mirror":
        role = "refund" if amount > 0 else "expense"
        return role, role
    if kind == "refund_offset":
        return ("p2p_out", "p2p_in") if subtype == "p2p_return" else ("expense", "refund")
    return "outgoing", "incoming"


def _evidence(value):
    try:
        if not isinstance(value, dict) or len(json.dumps(value, allow_nan=False).encode()) > 64 * 1024:
            raise ValueError
        return deepcopy(value)
    except (ValueError, TypeError, OverflowError, RecursionError):
        raise CashRelationError("relation_data_invalid") from None


class CurrentCashRelationRepository:
    def __init__(self, session, workspace_id):
        self._session, self._workspace_id = session, workspace_id

    def _model(self, relation_id):
        row = self._session.scalar(select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id, TransactionRelationModel.id == _id(relation_id)
        ).execution_options(populate_existing=True))
        if row is None:
            raise CashRelationError("relation_not_found")
        return row

    def _dicts(self, rows, *, include_candidate_ids=False):
        rows = tuple(rows)
        grouped = {}
        for batch in _batches(rows):
            for candidate in self._session.scalars(select(CashRelationCandidateModel).where(
                CashRelationCandidateModel.workspace_id == self._workspace_id,
                CashRelationCandidateModel.relation_id.in_([row.id for row in batch]),
            ).order_by(CashRelationCandidateModel.candidate_transaction_id, CashRelationCandidateModel.candidate_component_id)):
                grouped.setdefault(candidate.relation_id, []).append({**({"id": candidate.id} if include_candidate_ids else {}),
                    "candidate_transaction_id": candidate.candidate_transaction_id,
                    "candidate_component_id": candidate.candidate_component_id,
                    "expected_row_revision": candidate.expected_row_revision,
                    "evidence": deepcopy(candidate.evidence), "validity": candidate.validity,
                })
        return [{"id": row.id, "workspace_id": row.workspace_id, "row_revision": row.row_revision,
            "created_at": row.created_at, **{key: deepcopy(getattr(row, key)) for key in _FIELDS},
            "candidates": grouped.get(row.id, [])} for row in rows]

    def get(self, relation_id):
        return self._dicts((self._model(relation_id),))[0]

    def list_for_transactions(self, transaction_ids, *, active_only=True, include_candidates=False, include_candidate_ids=False):
        ids = {_id(value) for value in transaction_ids}
        if not ids:
            return []
        rows = {}
        for batch in _batches(sorted(ids)):
            # Keep the outer lookup on (workspace, id). An outer active filter
            # makes SQLite prefer its workspace-state index over the ID set.
            query = select(TransactionRelationModel).where(TransactionRelationModel.workspace_id == self._workspace_id,
                TransactionRelationModel.id.in_(self._transaction_ids_query(batch, active_only, include_candidates))).execution_options(populate_existing=True)
            rows.update((row.id, row) for row in self._session.scalars(query) if not active_only or row.active)
        return self._dicts((rows[key] for key in sorted(rows)), include_candidate_ids=include_candidate_ids)

    def _transaction_ids_query(self, batch, active_only, include_candidates):
        filters = [TransactionRelationModel.workspace_id == self._workspace_id]
        if active_only:
            filters.append(TransactionRelationModel.active.is_(True))
        branches = [select(TransactionRelationModel.id).where(*filters, endpoint.in_(batch)) for endpoint in (
            TransactionRelationModel.primary_transaction_id, TransactionRelationModel.secondary_transaction_id,
            TransactionRelationModel.anchor_transaction_id)]
        if include_candidates:
            branches.append(select(CashRelationCandidateModel.relation_id).where(
                CashRelationCandidateModel.workspace_id == self._workspace_id,
                CashRelationCandidateModel.candidate_transaction_id.in_(batch)))
        return union(*branches)

    def graph_rows_for_transactions(self, transaction_ids):
        rows = {}
        for batch in _batches(sorted({_id(value) for value in transaction_ids})):
            query = _POSTGRES_GRAPH_ROWS_QUERY if self._session.bind.dialect.name == "postgresql" else _GRAPH_ROWS_QUERY
            current = self._session.execute(query,
                {"graph_workspace": self._workspace_id, "graph_parent_ids": batch}).mappings()
            rows.update((row["id"], dict(row)) for row in current if row["active"])
        return tuple(rows[key] for key in sorted(rows))

    def graph_rows_for_groups(self, group_ids):
        rows = {}
        for batch in _batches(sorted(set(group_ids))):
            query = select(*(getattr(TransactionRelationModel, key) for key in _GRAPH_FIELDS)).where(
                TransactionRelationModel.workspace_id == self._workspace_id, TransactionRelationModel.decision_group_id.in_(batch))
            rows.update((row["id"], dict(row)) for row in self._session.execute(query).mappings())
        return tuple(rows[key] for key in sorted(rows))

    def has_live_candidates(self):
        return self._session.scalar(select(CashRelationCandidateModel.id).where(
            CashRelationCandidateModel.workspace_id == self._workspace_id,
            CashRelationCandidateModel.validity != "stale").limit(1)) is not None

    def mark_candidates_stale(self, transaction_ids):
        """调用方传入实际更新的流水；使其端点及对侧候选依赖失效。"""
        transaction_ids = tuple(sorted({_id(value) for value in transaction_ids}))
        if len(transaction_ids) > 500 and not self.has_live_candidates():
            return frozenset()
        changed = set()
        for batch in _batches(transaction_ids):
            candidate_dependencies = select(CashRelationCandidateModel.id, CashRelationCandidateModel.relation_id).where(
                CashRelationCandidateModel.workspace_id == self._workspace_id,
                CashRelationCandidateModel.candidate_transaction_id.in_(batch), CashRelationCandidateModel.validity != "stale",
                CashRelationCandidateModel.expected_row_revision != select(CashTransactionModel.row_revision).where(
                    CashTransactionModel.workspace_id == self._workspace_id,
                    CashTransactionModel.id == CashRelationCandidateModel.candidate_transaction_id).scalar_subquery())
            endpoint_dependencies = select(CashRelationCandidateModel.id, CashRelationCandidateModel.relation_id).where(
                CashRelationCandidateModel.workspace_id == self._workspace_id, CashRelationCandidateModel.validity != "stale",
                CashRelationCandidateModel.relation_id.in_(self._transaction_ids_query(batch, True, False)))
            rows = self._session.execute(union(candidate_dependencies, endpoint_dependencies)).all()
            for candidate_batch in _batches([row.id for row in rows]):
                self._session.execute(update(CashRelationCandidateModel).where(
                    CashRelationCandidateModel.workspace_id == self._workspace_id, CashRelationCandidateModel.id.in_(candidate_batch)
                ).values(validity="stale"))
            changed.update(row.relation_id for row in rows)
        for batch in _batches(sorted(changed)):
            self._session.execute(update(TransactionRelationModel).where(
                TransactionRelationModel.workspace_id == self._workspace_id, TransactionRelationModel.id.in_(batch)
            ).values(row_revision=TransactionRelationModel.row_revision + 1))
        return frozenset(changed)

    def confirmed_closure(self, transaction_ids):
        ids = {_id(value) for value in transaction_ids}
        if not ids:
            return set()
        result = set()
        for batch in _batches(sorted(ids)):
            seeds = set(batch) - result
            if not seeds:
                continue
            query = _POSTGRES_CLOSURE_QUERY if self._session.bind.dialect.name == "postgresql" else _CONFIRMED_CLOSURE_QUERY
            result.update(self._session.scalars(query,
                {"closure_workspace": self._workspace_id, "closure_seeds": sorted(seeds)}))
        return result

    def _values(self, values):
        if not isinstance(values, dict) or values.keys() - _FIELDS:
            raise CashRelationError("relation_data_invalid")
        result = {"subtype": "", "state": "draft", "active": True, "secondary_transaction_id": None,
            "primary_component_id": None, "secondary_component_id": None, "anchor_component_id": None,
            "primary_role": None, "secondary_role": None, "decision_group_id": None,
            "decision_source": "rule", "evidence": {}, "applied_amount": "0", "rule_id": "",
            "created_by": "system", "decided_by": "", "decided_at": None, "decision_reason": "", **values}
        if (not isinstance(result.get("kind"), str) or result["kind"] not in {"payment_mirror", "refund_offset", "transfer_pair"}
            or not isinstance(result["state"], str) or result["state"] not in {"draft", "confirmed", "ignored"} or type(result["active"]) is not bool):
            raise CashRelationError("relation_data_invalid")
        for key in _IDS:
            result[key] = _id(result[key]) if result.get(key) is not None else None
        if result["primary_transaction_id"] is None or result["anchor_transaction_id"] is None:
            raise CashRelationError("relation_endpoint_incomplete")
        for key, maximum in (("subtype", 64), ("decision_source", 32), ("rule_id", 128), ("created_by", 128), ("decided_by", 128), ("decision_reason", 4096)):
            if not isinstance(result[key], str) or len(result[key]) > maximum:
                raise CashRelationError("relation_data_invalid")
        for key, allowed in (("primary_role", {"expense", "refund", "p2p_out", "p2p_in", "outgoing", "incoming"}),
                            ("secondary_role", {"expense", "refund", "p2p_out", "p2p_in", "outgoing", "incoming"})):
            if result[key] is not None and (not isinstance(result[key], str) or result[key] not in allowed):
                raise CashRelationError("relation_data_invalid")
        group = result["decision_group_id"]
        if group is not None and (not isinstance(group, str) or not 1 <= len(group) <= 128):
            raise CashRelationError("relation_data_invalid")
        if result["decided_at"] is not None and (not isinstance(result["decided_at"], datetime) or result["decided_at"].tzinfo is None):
            raise CashRelationError("relation_data_invalid")
        result["evidence"] = _evidence(result["evidence"])
        try:
            result["applied_amount"] = exact_decimal(result["applied_amount"])
            if result["applied_amount"] < 0:
                raise ValueError
        except (TypeError, ValueError, ArithmeticError):
            raise CashRelationError("relation_data_invalid") from None
        parents = {row.id: row for row in self._session.scalars(select(CashTransactionModel).where(
            CashTransactionModel.workspace_id == self._workspace_id,
            CashTransactionModel.id.in_({result[f"{role}_transaction_id"] for role in ("primary", "secondary", "anchor")} - {None})))}
        component_ids = {result[f"{role}_component_id"] for role in ("primary", "secondary", "anchor")} - {None}
        components = {row.id: row for row in self._session.scalars(select(CashTransactionComponentModel).where(
            CashTransactionComponentModel.workspace_id == self._workspace_id, CashTransactionComponentModel.id.in_(component_ids)))} if component_ids else {}
        for role in ("primary", "secondary", "anchor"):
            parent_id, component_id = result[f"{role}_transaction_id"], result[f"{role}_component_id"]
            if parent_id is not None and parent_id not in parents or component_id is not None and (component_id not in components or components[component_id].cash_transaction_id != parent_id):
                raise CashRelationError("relation_endpoint_incomplete")
        if (result["secondary_transaction_id"] == result["primary_transaction_id"]
            or result["secondary_transaction_id"] is None and result["secondary_role"] is not None
            or result["anchor_transaction_id"] not in {result["primary_transaction_id"], result["secondary_transaction_id"]}
            or result["anchor_component_id"] is not None and result["anchor_component_id"] not in {result["primary_component_id"], result["secondary_component_id"]}):
            raise CashRelationError("relation_endpoint_incompatible")
        if result["active"] and result["state"] != "ignored":
            from ft.adapters.relational.cash_relation_origin import _assert_loaded_relation_origin
            _assert_loaded_relation_origin(parents.values(), kind=result["kind"])
        if result["state"] == "confirmed" and result["active"]:
            endpoints = []
            for role in ("primary", "secondary"):
                component = components.get(result[f"{role}_component_id"])
                parent = parents.get(result[f"{role}_transaction_id"])
                if component is None or parent is None or result["anchor_component_id"] is None:
                    raise CashRelationError("relation_endpoint_incomplete")
                endpoints.append(RelationEndpoint(str(component.id), str(parent.id), component.amount, component.currency,
                    str(component.account_id) if component.account_id is not None else None,
                    parent.record_type, parent.record_subtype, parent.state, parent.deleted_at is None))
            validate_relation_roles(result["kind"], result["subtype"], *endpoints, result["primary_role"], result["secondary_role"])
        return result

    def add(self, values):
        row = TransactionRelationModel(workspace_id=self._workspace_id, **self._values(values))
        self._session.add(row)
        self._session.flush()
        return self.get(row.id)

    def replace(self, relation_id, changes, *, expected_revision, _return_current=True):
        row = self._model(relation_id)
        if type(expected_revision) is not int or row.row_revision != expected_revision:
            raise CashRelationError("relation_revision_changed")
        if not isinstance(changes, dict) or changes.keys() - _FIELDS:
            raise CashRelationError("relation_data_invalid")
        values = self._values({**{key: getattr(row, key) for key in _FIELDS}, **changes})
        result = self._session.execute(update(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id, TransactionRelationModel.id == row.id,
            TransactionRelationModel.row_revision == expected_revision,
        ).values(**values, row_revision=expected_revision + 1).execution_options(synchronize_session=False))
        if result.rowcount != 1:
            raise CashRelationError("relation_revision_changed")
        if values["state"] != "draft" or not values["active"]:
            self._session.execute(delete(CashRelationCandidateModel).where(
                CashRelationCandidateModel.workspace_id == self._workspace_id, CashRelationCandidateModel.relation_id == row.id))
        identifier = row.id
        self._session.expire(row)
        return self.get(identifier) if _return_current else None

    def set_candidates(self, relation_id, candidates, *, expected_revision):
        row = self._model(relation_id)
        if type(expected_revision) is not int or row.row_revision != expected_revision:
            raise CashRelationError("relation_revision_changed")
        if row.state != "draft" or not row.active or not isinstance(candidates, (tuple, list)) or len(candidates) > 100:
            raise CashRelationError("relation_data_invalid")
        entries, keys = [], set()
        for value in candidates:
            if not isinstance(value, dict) or value.keys() - {"candidate_transaction_id", "candidate_component_id", "expected_row_revision", "evidence", "validity"}:
                raise CashRelationError("relation_data_invalid")
            parent_id = _id(value.get("candidate_transaction_id"))
            component_id = _id(value["candidate_component_id"]) if value.get("candidate_component_id") is not None else None
            revision = value.get("expected_row_revision")
            if type(revision) is not int or revision < 1 or (parent_id, component_id) in keys:
                raise CashRelationError("relation_data_invalid")
            keys.add((parent_id, component_id))
            if parent_id == row.anchor_transaction_id:
                raise CashRelationError("relation_endpoint_incompatible")
            entries.append((parent_id, component_id, revision, value))
        parents = {parent.id: parent for parent in self._session.scalars(select(CashTransactionModel).where(
            CashTransactionModel.workspace_id == self._workspace_id, CashTransactionModel.id.in_({entry[0] for entry in entries})
        ).execution_options(populate_existing=True))} if entries else {}
        component_ids = {entry[1] for entry in entries} - {None}
        components = {component.id: component for component in self._session.scalars(select(CashTransactionComponentModel).where(
            CashTransactionComponentModel.workspace_id == self._workspace_id, CashTransactionComponentModel.id.in_(component_ids)))} if component_ids else {}
        from ft.adapters.relational.cash_relation_origin import assert_relation_origin
        if entries:
            assert_relation_origin(self._session, self._workspace_id, set(parents) | {row.anchor_transaction_id}, kind=row.kind)
        prepared = []
        for parent_id, component_id, revision, value in entries:
            parent, component = parents.get(parent_id), components.get(component_id)
            if parent is None or component_id is not None and (component is None or component.cash_transaction_id != parent_id):
                raise CashRelationError("relation_endpoint_incomplete")
            validity = value.get("validity", "valid")
            if not isinstance(validity, str) or validity not in {"valid", "incomplete", "stale"}:
                raise CashRelationError("relation_data_invalid")
            if parent.row_revision != revision or parent.deleted_at is not None:
                validity = "stale"
            elif component_id is None or parent.state != "posted":
                validity = "incomplete"
            prepared.append(CashRelationCandidateModel(workspace_id=self._workspace_id, relation_id=row.id,
                candidate_transaction_id=parent_id, candidate_component_id=component_id,
                expected_row_revision=revision, evidence=_evidence(value.get("evidence", {})), validity=validity))
        result = self._session.execute(update(TransactionRelationModel).where(TransactionRelationModel.workspace_id == self._workspace_id,
            TransactionRelationModel.id == row.id, TransactionRelationModel.row_revision == expected_revision,
        ).values(row_revision=expected_revision + 1).execution_options(synchronize_session=False))
        if result.rowcount != 1:
            raise CashRelationError("relation_revision_changed")
        self._session.execute(delete(CashRelationCandidateModel).where(CashRelationCandidateModel.workspace_id == self._workspace_id,
            CashRelationCandidateModel.relation_id == row.id))
        self._session.add_all(prepared)
        self._session.flush()
        identifier = row.id
        self._session.expire(row)
        return self.get(identifier)
