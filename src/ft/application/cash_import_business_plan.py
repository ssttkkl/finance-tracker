"""实际账单操作、当前关系和预计流水列表的只读业务构建器。"""
from __future__ import annotations

from collections import Counter, defaultdict
from copy import deepcopy
from dataclasses import asdict, dataclass, replace
from datetime import datetime, timedelta, timezone
from decimal import Decimal, localcontext
from itertools import islice, product

from sqlalchemy import and_, exists, func, or_, select

from ft.adapters.cash_statement_evidence import extract_source_explanation
from ft.adapters.cash_statement_effects import cash_source_note
from ft.adapters.relational.models import (AccountAliasModel, AccountModel, CashSourceBindingModel,
    CashTransactionComponentModel, CashTransactionModel)
from ft.application.cash_import_issues import cash_import_issue
from ft.application.cash_import_operations import CashImportOperationPlanner, _batches, _wire
from ft.application.cash_import_plans import CashImportPlanError
from ft.application.cash_relation_context import CashRelationGraphService
from ft.application.relations import plan_relation_proposals
from ft.domain.cash_projection import CashProjectionBuild, CashProjectionFact, build_cash_projections
from ft.domain.cash_record_policy import CASH_FINANCIAL_FIELDS, MUTABLE_CASH_FIELDS, _normalize
from ft.domain.cash_relation_graph import CashRelationGraphEdge, build_cash_relation_graph
from ft.domain.cash_relation_policy import (CashRelationError, RelationEndpoint, RelationLink, RelationRoleEvidence,
    cash_funding_role_compatible, discover_relation_roles, relation_commit_groups, validate_relation_roles)
from ft.domain.cash_source_matching import _digest, provider_identity_key
from ft.domain.cash_source_scope import WECHAT_DEFAULT_SCOPE
from ft.domain.platform_refund import wechat_is_refund_origin_expense
from ft.domain.relations import FactView, RelationProposal


def _virtual(kind, *values):
    return 2 ** 64 + int(_digest("cash-import-virtual-v1", kind, values)[:32], 16)


def _edge_key(edge):
    pair = (edge.primary_component_id, edge.secondary_component_id)
    if edge.kind == "payment_mirror" and all(pair):
        pair = tuple(sorted(pair))
    return edge.kind, edge.subtype, pair if all(pair) else (edge.primary_transaction_id, edge.primary_component_id, None)


def _link(edge, group=None):
    return RelationLink(str(edge.id), edge.primary_transaction_id, edge.secondary_transaction_id,
        edge.state, edge.active, group)


def _pending(code, *, relation=False):
    return cash_import_issue(code, blocking_scope="relation_group" if relation else "row",
        after_import_action="open_home_item")


@dataclass
class _Snapshot:
    context: object
    values: dict
    facts: dict
    components: dict
    edges: dict
    funding: dict
    parent_ops: dict
    removed_relations: set
    removed_funding: set

    def __post_init__(self):
        self.incident = defaultdict(set)
        self.funding_by_parent = defaultdict(set)
        self.register_edges(tuple(self.edges.values()))
        for identifier, row in self.funding.items():
            part = self.components.get(str(row[1]))
            if part:
                self.funding_by_parent[part.transaction_id].add(identifier)

    def register_edges(self, edges):
        for edge in edges:
            previous = self.edges.get(edge.id)
            if previous:
                self.incident[previous.primary_transaction_id].discard(edge.id)
                if previous.secondary_transaction_id:
                    self.incident[previous.secondary_transaction_id].discard(edge.id)
            self.edges[edge.id] = edge
            self.incident[edge.primary_transaction_id].add(edge.id)
            if edge.secondary_transaction_id:
                self.incident[edge.secondary_transaction_id].add(edge.id)

    def restore_edges(self, before):
        for identifier, previous in before.items():
            current = self.edges.pop(identifier, None)
            if current:
                self.incident[current.primary_transaction_id].discard(identifier)
                if current.secondary_transaction_id:
                    self.incident[current.secondary_transaction_id].discard(identifier)
            if previous:
                self.register_edges((previous,))

    def build(self, parent_ids=None, *, additions=()):
        parents = self.facts.keys() if parent_ids is None else set(parent_ids)
        edges = tuple(self.edges.values()) if parent_ids is None else [self.edges[identifier]
            for identifier in {identifier for parent in parents for identifier in self.incident[parent]} if identifier in self.edges]
        if additions:
            edges = tuple({edge.id: edge for edge in (*edges, *additions)}.values())
        pending = {identifier for edge in edges if edge.active and edge.state == "draft"
            for identifier in (edge.primary_transaction_id, edge.secondary_transaction_id) if identifier is not None}
        funding_rows = [self.funding[identifier] for parent in parents for identifier in self.funding_by_parent[parent] if identifier in self.funding]
        funding_by_parent = {self.components[str(row[1])].transaction_id: row[0] for row in funding_rows if str(row[1]) in self.components}
        formal = [replace(fact, needs_attention=fact.needs_attention or str(fact.id) in pending,
            funding_relation_id=funding_by_parent.get(str(fact.id)))
            for identifier in parents if (fact := self.facts.get(identifier)) is not None and fact.state == "posted"]
        components = [self.components[part["component_id"]] for parent in parents for part in self.values[parent]["components"]
            if part["component_id"] in self.components]
        component_ids = {part.component_id for part in components}
        complete = build_cash_relation_graph(formal, components, edges,
            funding_component_ids={str(value[1]) for value in funding_rows if str(value[1]) in component_ids})
        drafts = build_cash_projections([fact for identifier in parents if (fact := self.facts.get(identifier)) is not None and fact.state == "draft"], ())
        return CashProjectionBuild((*complete.projections, *drafts.projections))


class CashImportBusinessPlanBuilder:
    def __init__(self, workspace_id, *, clock=None):
        self.workspace_id = workspace_id
        self._clock = clock or (lambda: datetime.now(timezone.utc))

    def __call__(self, *, session, files, preview_input, previous_plan, plan_id, source_plan=None):
        record_ids = {op["occurrence_key"]: op["values"]["record_id"] for op in (previous_plan or {}).get("operations", [])
            if op.get("occurrence_key") and not op.get("transaction_id")}
        prepared = CashImportOperationPlanner(session, self.workspace_id).prepare(files,
            account_selections=preview_input["account_selections"], row_choices=preview_input["row_choices"], record_ids=record_ids,
            source_plan=source_plan)
        operations = deepcopy(list(prepared.operations))
        for operation in operations:
            source = prepared.source_plan.rows[operation["row_refs"][0]]
            operation["source_scope_key"] = source.account_scope_key or "file:" + source.file_scope_key
            operation["matching_record_id"] = operation["values"]["record_id"] if source.provider_id else "occurrence:" + source.occurrence_key
        rows = deepcopy(list(prepared.rows))
        return self.build_operations(session=session, files=files, operations=operations, rows=rows,
            source_rows=prepared.source_plan.rows, relation_choices=preview_input["relation_choices"], previous_plan=previous_plan,
            source_candidate_sets=prepared.candidate_sets)

    def build_operations(self, *, session, operations, rows, relation_choices, previous_plan,
        files=(), source_rows=None, source_candidate_sets=None, include_unchanged=False,
        current_records=None, source_scope_snapshot=None):
        """共享已准备操作之后的只读关系、最终图与预计列表计算。"""
        accounts = self._accounts(session)
        new = [op for op in operations if op["planned_action"] in {"create_posted", "finalize_draft"}]
        candidates = self._candidate_ids(session, new, source_rows or {})
        existing = {int(op["transaction_id"]) for op in operations if op.get("transaction_id")}
        context = CashRelationGraphService(session, self.workspace_id).load(existing | candidates)
        snapshot = self._snapshot(session, context, operations, accounts, rows,
            current_records=current_records, source_scope_snapshot=source_scope_snapshot)
        seeds = {part.component_id for part in snapshot.components.values()
            if snapshot.parent_ops.get(part.transaction_id, {}).get("planned_action") in {"create_posted", "finalize_draft"}}
        proposals, failures = self._discover(snapshot, seeds, accounts, session) if seeds else ([], [])
        groups, conversion_failures = self._groups(snapshot, proposals)
        failures.extend(conversion_failures)
        invalidated = self._apply_choices(groups, relation_choices, previous_plan)
        relation_failures = self._apply_groups(snapshot, groups)
        failures.extend(relation_failures)
        unique_failures = {}
        for failure in failures:
            failure = dict(failure, parent_ids=sorted(set(failure["parent_ids"])))
            key = _digest(failure["code"], failure["parent_ids"], failure.get("suggestion_id"))
            unique_failures.setdefault(key, failure)
        failures = list(unique_failures.values())
        for failure in failures:
            for parent in failure["parent_ids"]:
                if parent in snapshot.facts:
                    snapshot.facts[parent] = replace(snapshot.facts[parent], needs_attention=True)
        build = snapshot.build()
        result = self._present(files, operations, rows, snapshot, build, groups, failures, accounts,
            include_unchanged=include_unchanged)
        result.update(invalidated_choices=invalidated, operations=operations, row_decisions=rows,
            source_candidate_sets=source_candidate_sets or {},
            relation_groups=_wire(groups), relation_failures=failures, dependencies={"seed_ids": sorted(context.seed_ids), "token": context.token,
                "candidate_ids": sorted(candidates), "discovery_seeds": sorted(seeds)},
            type_effects={"removed_relation_ids": sorted(snapshot.removed_relations),
                "removed_funding_ids": sorted(snapshot.removed_funding)})
        return _wire(result)

    def _accounts(self, session):
        return {str(account.id): {"name": account.name, "type": account.type, "revision": account.revision}
            for account in session.scalars(select(AccountModel).where(AccountModel.workspace_id == self.workspace_id))}

    def _candidate_ids(self, session, operations, source_rows):
        if not operations:
            return set()
        intervals = sorted((_normalize("occurred_at", op["values"]["occurred_at"]) - timedelta(days=61),
            _normalize("occurred_at", op["values"]["occurred_at"]) + timedelta(days=61)) for op in operations)
        merged = []
        for start, end in intervals:
            if merged and start <= merged[-1][1]:
                merged[-1] = (merged[-1][0], max(end, merged[-1][1]))
            else:
                merged.append((start, end))
        result = set()
        for batch in _batches(merged, 100):
            result.update(session.scalars(select(CashTransactionModel.id).where(
                CashTransactionModel.workspace_id == self.workspace_id, CashTransactionModel.state == "posted",
                CashTransactionModel.deleted_at.is_(None), or_(*(and_(CashTransactionModel.occurred_at >= start,
                    CashTransactionModel.occurred_at <= end) for start, end in batch)))))
        result.update(self._hard_key_ids(session, operations, source_rows))
        return result

    def _hard_key_ids(self, session, operations, source_rows):
        requests = defaultdict(lambda: {"txn": set(), "merchant": set()})
        for operation in operations:
            source = operation["values"]["source_type"]
            if source not in {"alipay", "wechat"}:
                continue
            references = operation.get("row_refs", [])
            occurrence = source_rows.get(references[0]) if references else None
            scope = occurrence.account_scope_key if occurrence is not None else operation.get("source_scope_key")
            accounts = tuple(sorted({int(part["account_id"]) for part in operation["values"]["components"]}))
            metadata = operation.get("source_explanation", operation.get("matching_source_explanation", {}))
            request = requests[(source, scope, accounts)]
            for key, field in (("txn", "txn_id"), ("merchant", "merchant_order_id")):
                value = metadata.get(field, occurrence.provider_id if occurrence is not None and key == "txn" else None)
                if isinstance(value, str) and value.strip():
                    request[key].add(value.strip())
        result = set()
        for (source, scope, accounts), request in requests.items():
            txn_keys = set(request["txn"])
            if source == "alipay":
                txn_keys.update(value[:index] for value in request["txn"] for index, character in enumerate(value)
                    if index and character in "_*")
            # 已保存的可靠本方编号直接使用来源唯一索引，不依赖可变文本。
            if scope:
                for batch in _batches(provider_identity_key(value) for value in txn_keys):
                    result.update(session.scalars(select(CashSourceBindingModel.transaction_id).join(CashTransactionModel,
                        and_(CashTransactionModel.workspace_id == CashSourceBindingModel.workspace_id,
                            CashTransactionModel.id == CashSourceBindingModel.transaction_id)).where(
                        CashSourceBindingModel.workspace_id == self.workspace_id, CashSourceBindingModel.source_type == source,
                        CashSourceBindingModel.source_scope_kind == "account", CashSourceBindingModel.source_scope_key == scope,
                        CashSourceBindingModel.identity_kind == "provider_id", CashSourceBindingModel.identity_key.in_(batch),
                        CashTransactionModel.state == "posted", CashTransactionModel.deleted_at.is_(None))))
            # 商家订单／旧来源行按明确账户及来源限域，只返回键匹配的当前记录。
            account_parents = select(CashTransactionComponentModel.cash_transaction_id).where(
                CashTransactionComponentModel.workspace_id == self.workspace_id,
                CashTransactionComponentModel.account_id.in_(accounts))
            scope_terms = []
            if scope:
                scoped = select(CashSourceBindingModel.id).where(CashSourceBindingModel.workspace_id == self.workspace_id,
                    CashSourceBindingModel.transaction_id == CashTransactionModel.id,
                    CashSourceBindingModel.source_scope_kind == "account")
                scope_terms.append(~exists(scoped.where(CashSourceBindingModel.source_scope_key != scope)))
                if source == "wechat" and scope != WECHAT_DEFAULT_SCOPE:
                    scope_terms.append(exists(scoped.where(CashSourceBindingModel.source_scope_key == scope)))
            txn = _source_key_expression("txn_id", "交易号" if source == "alipay" else "交易单号")
            merchant = _source_key_expression("merchant_order_id", "商家订单号", "商户单号")
            keys = txn_keys | request["merchant"]
            for batch in _batches(keys, 100):
                terms = [txn.in_(batch), merchant.in_(batch)]
                if source == "alipay":
                    terms.extend(txn.startswith(value + suffix, autoescape=True)
                        for value in batch if value in request["txn"] for suffix in ("_", "*"))
                result.update(session.scalars(select(CashTransactionModel.id).where(
                    CashTransactionModel.workspace_id == self.workspace_id, CashTransactionModel.source_type == source,
                    CashTransactionModel.id.in_(account_parents), CashTransactionModel.state == "posted",
                    CashTransactionModel.deleted_at.is_(None), *scope_terms, or_(*terms))))
        return result

    @staticmethod
    def _fallback_scope(source, identifier):
        return WECHAT_DEFAULT_SCOPE if source == "wechat" else "unbound:" + str(identifier)

    def _source_scopes(self, session, identifiers):
        scopes = defaultdict(lambda: defaultdict(set))
        for batch in _batches(identifiers):
            for parent, source, kind, scope in session.execute(select(CashSourceBindingModel.transaction_id,
                CashSourceBindingModel.source_type, CashSourceBindingModel.source_scope_kind, CashSourceBindingModel.source_scope_key).where(
                    CashSourceBindingModel.workspace_id == self.workspace_id, CashSourceBindingModel.transaction_id.in_(batch))):
                scopes[(str(parent), source)][kind].add(scope)
        result = {}
        for key, kinds in scopes.items():
            known = kinds["account"]
            local = kinds["file_local"]
            result[key] = (next(iter(known)) if len(known) == 1 else "ambiguous:" + key[0] if known else
                WECHAT_DEFAULT_SCOPE if key[1] == "wechat" else "file:" + next(iter(local)) if len(local) == 1 else "ambiguous:" + key[0])
        return result

    def _snapshot(self, session, context, operations, accounts, rows, *, current_records=None, source_scope_snapshot=None):
        # 已查询 ID 单独保存，空结果也属于本次已读事实；其余闭包成员仍实际查询。
        covered, known_scopes = source_scope_snapshot if source_scope_snapshot is not None else (frozenset(), {})
        scopes = {**known_scopes, **self._source_scopes(session, context.transaction_ids - covered)}
        current = {int(identifier): parent for identifier, parent in (current_records or {}).items()
            if int(identifier) in context.transaction_ids}
        for batch in _batches(context.transaction_ids - current.keys()):
            current.update((parent.id, parent) for parent in session.scalars(select(CashTransactionModel).where(
                CashTransactionModel.workspace_id == self.workspace_id, CashTransactionModel.id.in_(batch))))
        values = {}
        for parent in current.values():
            values[str(parent.id)] = {field: _normalize(field, getattr(parent, field)) for field in MUTABLE_CASH_FIELDS | CASH_FINANCIAL_FIELDS
                if hasattr(parent, field)} | {"source_type": parent.source_type, "record_id": parent.record_id,
                "state": parent.state, "created_at": parent.created_at,
                "source_explanation": extract_source_explanation(parent.relation_metadata)}
            values[str(parent.id)]["source_scope_key"] = scopes.get((str(parent.id), parent.source_type),
                self._fallback_scope(parent.source_type, parent.id))
            values[str(parent.id)]["matching_record_id"] = "occurrence:" + str(parent.id)
        by_parent = defaultdict(list)
        for identifier, parent, account, amount, currency, ordinal in context.component_versions:
            by_parent[str(parent)].append({"component_id": str(identifier), "account_id": str(account) if account is not None else None,
                "amount": Decimal(amount) if amount is not None else None, "currency": currency, "ordinal": ordinal})
        for identifier, value in values.items():
            value["components"] = by_parent[identifier]
        facts = {str(fact.id): fact for fact in context.facts}
        parts = {part.component_id: part for part in context.components}
        snapshot = _Snapshot(context, values, facts, parts, {edge.id: edge for edge in context.relations},
            {row[0]: row for row in context.funding_versions}, {}, set(), set())
        old_closures = _closure_index(context.relations)
        for identifier, value in values.items():
            if value["state"] == "draft":
                snapshot.facts[identifier] = self._fact(identifier, value, needs_attention=True)
        for op in tuple(operations):
            identifier = op["transaction_id"] or str(_virtual("transaction", op["occurrence_key"]))
            op["preview_transaction_id"] = identifier
            value = {field: _normalize(field, item) for field, item in op["values"].items()}
            value["created_at"] = values.get(identifier, {}).get("created_at", self._clock())
            value["source_explanation"] = op.get("source_explanation", values.get(identifier, {}).get("source_explanation", {}))
            value["source_scope_key"] = op["source_scope_key"]
            value["matching_record_id"] = op["matching_record_id"]
            for part in value["components"]:
                part.setdefault("component_id", str(_virtual("component", identifier, part["ordinal"])))
                if part["component_id"] is None:
                    part["component_id"] = str(_virtual("component", identifier, part["ordinal"]))
            type_update = op.get("type_changed") and op.get("transaction_id") and value["state"] == "posted"
            previous = self._capture(snapshot, identifier) if type_update else None
            for part in snapshot.values.get(identifier, {}).get("components", []):
                snapshot.components.pop(part["component_id"], None)
            snapshot.values[identifier] = value
            snapshot.facts[identifier] = self._fact(identifier, value, needs_attention=bool(op["issues"] or op.get("retained_issue_ids")))
            if value["state"] == "posted":
                for part in value["components"]:
                    snapshot.components[part["component_id"]] = self._endpoint(identifier, value, part)
            if type_update:
                try:
                    self._type_effects(snapshot, identifier)
                    snapshot.build(old_closures.get(identifier, (identifier,)))
                except CashRelationError:
                    self._restore(snapshot, identifier, previous)
                    operations.remove(op)
                    for row in rows:
                        if row.get("operation_ref") == op["operation_ref"]:
                            row.update(planned_action="skip", code="relation_endpoint_incompatible", operation_ref=None,
                                issues=[cash_import_issue("relation_endpoint_incompatible")])
                    continue
            snapshot.parent_ops[identifier] = op
            op["preview_components"] = [{"ordinal": part["ordinal"], "component_id": part["component_id"]}
                for part in value["components"]]
        return snapshot

    @staticmethod
    def _capture(snapshot, identifier):
        return (snapshot.values.get(identifier), snapshot.facts.get(identifier),
            {key: part for key, part in snapshot.components.items() if part.transaction_id == identifier},
            dict(snapshot.edges), dict(snapshot.funding), set(snapshot.removed_relations), set(snapshot.removed_funding))

    @staticmethod
    def _restore(snapshot, identifier, previous):
        value, fact, parts, snapshot.edges, snapshot.funding, snapshot.removed_relations, snapshot.removed_funding = previous
        snapshot.values[identifier], snapshot.facts[identifier] = value, fact
        snapshot.components = {key: part for key, part in snapshot.components.items() if part.transaction_id != identifier} | parts

    @staticmethod
    def _fact(identifier, value, *, needs_attention=False):
        return CashProjectionFact(int(identifier), int(value["account_id"]) if value.get("account_id") else None,
            value.get("occurred_at"), value.get("amount"), value.get("currency"), value.get("counterparty", ""),
            value.get("category_id"), value.get("note", ""), value.get("source_type"), value.get("record_id", ""),
            cash_granularity=value["cash_granularity"], state=value["state"], created_at=value["created_at"], needs_attention=needs_attention)

    @staticmethod
    def _endpoint(identifier, value, part):
        return RelationEndpoint(part["component_id"], identifier, part["amount"], part["currency"], part["account_id"],
            value["record_type"], value["record_subtype"])

    @staticmethod
    def _type_effects(snapshot, identifier):
        for key in tuple(snapshot.incident[identifier]):
            edge = snapshot.edges[key]
            if not edge.active or edge.state != "confirmed" or identifier not in (edge.primary_transaction_id, edge.secondary_transaction_id):
                continue
            try:
                validate_relation_roles(edge.kind, edge.subtype, snapshot.components[edge.primary_component_id],
                    snapshot.components[edge.secondary_component_id], edge.primary_role, edge.secondary_role)
            except CashRelationError as error:
                if error.code != "relation_endpoint_incompatible":
                    raise
                snapshot.edges[key] = replace(edge, active=False)
                snapshot.removed_relations.add(key)
        for key in tuple(snapshot.funding_by_parent[identifier]):
            if key not in snapshot.funding:
                continue
            _key, component, _event, direction = snapshot.funding[key]
            part = snapshot.components.get(str(component))
            if part and part.transaction_id == identifier and not cash_funding_role_compatible(direction, part.amount, part.record_type):
                snapshot.funding.pop(key)
                snapshot.removed_funding.add(key)

    def _matching_facts(self, snapshot, accounts):
        result = []
        for part in snapshot.components.values():
            value, account = snapshot.values[part.transaction_id], accounts[part.account_id]
            metadata = deepcopy(value.get("source_explanation", {}))
            if self._original_expense(value):
                metadata["offset_role"] = "expense"
            result.append(FactView(part.component_id, part.amount, part.currency, part.account_id,
                account_name=account["name"], account_type=account["type"], occurred_at=value["occurred_at"],
                counterparty=value["counterparty"], counterparty_account=value["counterparty_account"],
                counterparty_account_attrs=value["counterparty_account_attrs"], payment_method=metadata.get("payment_method", ""),
                note=cash_source_note(value["source_type"], value.get("source_payload")), record_type=part.record_type, record_subtype=part.record_subtype,
                bill_source=value["source_type"], source=value["source_type"], record_id=value.get("matching_record_id", value["record_id"]),
                source_identity=value.get("source_scope_key", ""),
                raw_payload=metadata, relation_metadata=metadata, parent_id=part.transaction_id,
                component_ordinal=next(item["ordinal"] for item in value["components"] if item["component_id"] == part.component_id)
                    if value["cash_granularity"] == "aggregate" else None, cash_granularity=value["cash_granularity"]))
        return result

    @staticmethod
    def _original_expense(value):
        metadata = value.get("source_explanation", {})
        return value.get("source_type") == "wechat" and value.get("record_type") == "refund" and value.get("amount", 0) < 0 and wechat_is_refund_origin_expense(
            metadata.get("_wechat_direction", metadata.get("direction", "")), metadata.get("platform_status", metadata.get("status", "")))

    def _discover(self, snapshot, seeds, accounts, session):
        facts = self._matching_facts(snapshot, accounts)
        accepted = [{"id": str(edge.id), "kind": edge.kind, "subtype": edge.subtype,
            "primary_fact_id": edge.primary_component_id, "secondary_fact_id": edge.secondary_component_id,
            "status": "accepted", "applied_amount": edge.applied_amount} for edge in snapshot.edges.values()
            if edge.active and edge.state == "confirmed"]
        tails, identifiers = defaultdict(list), defaultdict(list)
        for alias in session.scalars(select(AccountAliasModel).where(AccountAliasModel.workspace_id == self.workspace_id)):
            if alias.alias_type == "card_tail":
                tails[alias.alias_value].append(str(alias.account_id))
            elif alias.alias_type == "account_identifier":
                identifiers[alias.alias_value].append(str(alias.account_id))
        failures = []
        def run(selected):
            try:
                with localcontext() as arithmetic:
                    arithmetic.prec = 80
                    return list(plan_relation_proposals(facts, seed_ids=selected, accepted_relations=accepted,
                        aliases_by_tail=dict(tails), account_identifiers_by_value=dict(identifiers),
                        workspace_id=self.workspace_id, require_unique_mirrors=True).proposals)
            except Exception:
                if len(selected) > 1:
                    middle = len(selected) // 2
                    return [*run(selected[:middle]), *run(selected[middle:])]
                failures.append({"code": "relation_discovery_failed", "parent_ids": [snapshot.components[selected[0]].transaction_id]})
                return []
        proposals = run(sorted(seeds))
        by_key = {}
        for proposal in proposals:
            try:
                if not isinstance(proposal, RelationProposal):
                    raise ValueError
                key = (proposal.kind, proposal.subtype, proposal.primary_fact_id, proposal.secondary_fact_id)
                by_key.setdefault(key, proposal)
            except Exception:
                failures.append({"code": "relation_discovery_failed", "parent_ids": sorted({
                    snapshot.components[seed].transaction_id for seed in seeds})})
        return list(by_key.values()), failures

    def _groups(self, snapshot, proposals):
        old_keys = {_edge_key(edge): edge for edge in snapshot.edges.values() if edge.active}
        converted, failures = {}, []
        new_parents = {parent for parent, operation in snapshot.parent_ops.items()
            if operation["planned_action"] in {"create_posted", "finalize_draft"}}
        for proposal in proposals:
            parents = {part.transaction_id for identifier in (proposal.primary_fact_id, proposal.secondary_fact_id)
                if isinstance(identifier, str) and (part := snapshot.components.get(identifier)) is not None}
            try:
                member = self._proposal_member(snapshot, proposal, old_keys)
                if member is not None:
                    converted[str(member["edge"].id)] = member
            except Exception as error:
                if parents.intersection(new_parents):
                    failures.append({"code": error.code if isinstance(error, CashRelationError) else "relation_discovery_failed",
                        "parent_ids": sorted(parents)})
        links = [_link(item["edge"]) for item in converted.values()]
        groups = []
        closures = _closure_index(snapshot.context.relations)
        for identifiers in relation_commit_groups([_link(edge) for edge in snapshot.edges.values()], links):
            members = [converted[identifier] for identifier in identifiers]
            parents = {value for item in members for value in (item["edge"].primary_transaction_id, item["edge"].secondary_transaction_id) if value}
            if not any(snapshot.parent_ops.get(parent, {}).get("planned_action") in {"create_posted", "finalize_draft"} for parent in parents):
                continue
            dependency_parents = set(parents)
            dependency_parents.update(snapshot.components[identifier].transaction_id for item in members
                for identifier in item["candidates"] if identifier in snapshot.components)
            versions = dict(snapshot.context.transaction_revisions)
            fingerprint = [(parent, versions.get(int(parent)), snapshot.parent_ops.get(parent, {}).get("values"),
                snapshot.values[parent].get("source_explanation")) for parent in sorted(dependency_parents)]
            existing = {parent for parent in dependency_parents if int(parent) < 2 ** 63}
            complete = {parent for identifier in existing for parent in closures.get(identifier, (identifier,))}
            context = snapshot.context
            relation_ids = {edge.id for edge in context.relations if
                {edge.primary_transaction_id, edge.secondary_transaction_id}.intersection(complete)}
            dependency = (tuple(row for row in context.transaction_revisions if str(row[0]) in complete),
                tuple(row for row in context.component_versions if str(row[1]) in complete),
                tuple(row for row in context.relation_revisions if row[0] in relation_ids),
                tuple(row for row in context.funding_versions if str(row[1]) in snapshot.components
                    and snapshot.components[str(row[1])].transaction_id in complete),
                tuple(row for row in context.issue_versions if str(row[1]) in complete))
            proof = [(item["rule_id"], item["anchor_component_id"], item["candidate_count"], item["candidates"]) for item in members]
            suggestion = "sg_" + _digest("cash-import-suggestion-v1", sorted(identifiers), fingerprint, dependency, proof)[:40]
            group = {"suggestion_id": suggestion, "action": "accept" if all(item["strong"] for item in members) else "defer",
                "edges": [asdict(item["edge"]) for item in members], "evidence": {str(item["edge"].id): item["evidence"] for item in members},
                "anchors": {str(item["edge"].id): item["anchor_component_id"] or item["edge"].primary_component_id for item in members},
                "parent_ids": sorted(parents), "member_refs": [self._ref(snapshot, parent) for parent in sorted(parents)],
                "allowed_actions": ["accept", "defer", "ignore"] if all(item["edge"].secondary_transaction_id for item in members) else ["defer", "ignore"],
                "candidates": [], "candidate_id": None, "dependency_parent_ids": sorted(dependency_parents)}
            if group["action"] == "defer":
                group["candidates"] = self._candidate_choices(snapshot, group, members)
                if group["candidates"]:
                    group["allowed_actions"] = ["accept", "defer", "ignore"]
            groups.append(group)
        return groups, failures

    def _proposal_member(self, snapshot, proposal, old_keys):
        primary = snapshot.components.get(proposal.primary_fact_id)
        secondary = snapshot.components.get(proposal.secondary_fact_id)
        if primary is None or proposal.secondary_fact_id is not None and secondary is None:
            raise CashRelationError("relation_endpoint_incomplete")
        if secondary and primary.transaction_id == secondary.transaction_id:
            raise CashRelationError("relation_endpoint_incompatible")
        evidence = RelationRoleEvidence(self._original_expense(snapshot.values[primary.transaction_id]),
            self._original_expense(snapshot.values[secondary.transaction_id]) if secondary else False,
            proposal.kind == "refund_offset" and "platform_hard_key" in proposal.evidence.signals,
            proposal.subtype == "p2p_return" and "platform_hard_key" in proposal.evidence.signals)
        roles = discover_relation_roles(proposal.kind, proposal.subtype, primary, secondary, evidence=evidence) if secondary else (None, None)
        edge = CashRelationGraphEdge(_virtual("relation", proposal.kind, proposal.subtype, primary.component_id,
            secondary.component_id if secondary else None), proposal.kind, primary.transaction_id,
            secondary.transaction_id if secondary else None, primary.component_id,
            secondary.component_id if secondary else None, *roles, subtype=proposal.subtype,
            applied_amount=proposal.refund_amount if proposal.kind == "refund_offset" else None, state="draft")
        if _edge_key(edge) in old_keys:
            return None
        anchor = proposal.anchor_fact_id or None
        if anchor is not None and anchor not in {edge.primary_component_id, edge.secondary_component_id}:
            raise CashRelationError("relation_endpoint_incompatible")
        return {"edge": edge, "evidence": asdict(evidence), "anchor_component_id": anchor,
            "strong": proposal.status == "accepted" and proposal.confidence == "strong",
            "rule_id": proposal.rule_id, "candidates": list(proposal.evidence.candidate_fact_ids),
            "candidate_count": proposal.evidence.candidate_count}

    def _candidate_choices(self, snapshot, group, members):
        options = [self._edge_variants(snapshot, item) for item in members]
        if any(not variants for variants in options):
            return []
        closures = _closure_index(snapshot.edges.values())
        choices, seen = [], set()
        for combination in islice(product(*options), 2000):
            edges = tuple(replace(item[0], state="confirmed") for item in combination)
            keys = tuple(_edge_key(edge) for edge in edges)
            if keys in seen:
                continue
            seen.add(keys)
            parents = {parent for edge in edges for parent in (edge.primary_transaction_id, edge.secondary_transaction_id) if parent}
            complete = {parent for identifier in parents for parent in closures.get(identifier, (identifier,))}
            try:
                build = snapshot.build(complete, additions=edges)
            except CashRelationError:
                continue
            projection = next((item for item in build.projections if item.primary_record.id in {int(value) for value in parents}), None)
            if projection is None:
                continue
            choices.append({"candidate_id": "candidate_" + _digest(group["suggestion_id"], keys)[:40],
                "member_refs": [self._ref(snapshot, parent) for parent in sorted(parents)],
                "parent_ids": sorted(parents), "edges": [asdict(edge) for edge in edges],
                "evidence": {str(item[0].id): item[1] for item in combination},
                "display": {"occurred_at": projection.occurred_at, "amount": projection.net_amount,
                    "currency": projection.primary_record.currency, "counterparty": projection.primary_record.counterparty,
                    "note": projection.primary_record.note, "accounts": []}})
            if len(choices) == 20:
                break
        return choices

    def _edge_variants(self, snapshot, item):
        edge = item["edge"]
        candidate_ids = item["candidates"]
        anchor = item.get("anchor_component_id")
        if anchor is None:
            if edge.secondary_component_id in candidate_ids or edge.secondary_component_id is None:
                anchor = edge.primary_component_id
            elif edge.primary_component_id in candidate_ids:
                anchor = edge.secondary_component_id
            else:
                return [(edge, item["evidence"])] if edge.secondary_transaction_id else []
        variants = [(edge, item["evidence"])] if edge.secondary_transaction_id else []
        for identifier in candidate_ids:
            candidate = snapshot.components.get(identifier)
            fixed = snapshot.components[anchor]
            if candidate is None or candidate.transaction_id == fixed.transaction_id:
                continue
            if edge.kind in {"refund_offset", "transfer_pair"}:
                primary, secondary = (fixed, candidate) if fixed.amount < 0 else (candidate, fixed)
            else:
                primary, secondary = (fixed, candidate) if anchor == edge.primary_component_id else (candidate, fixed)
            evidence = RelationRoleEvidence(self._original_expense(snapshot.values[primary.transaction_id]),
                self._original_expense(snapshot.values[secondary.transaction_id]))
            try:
                roles = discover_relation_roles(edge.kind, edge.subtype, primary, secondary, evidence=evidence)
            except CashRelationError:
                continue
            variants.append((replace(edge, primary_transaction_id=primary.transaction_id, secondary_transaction_id=secondary.transaction_id,
                primary_component_id=primary.component_id, secondary_component_id=secondary.component_id,
                primary_role=roles[0], secondary_role=roles[1]), asdict(evidence)))
        return variants

    @staticmethod
    def _apply_choices(groups, choices, previous):
        old = {group["suggestion_id"]: group for group in (previous or {}).get("relation_groups", [])}
        if any(choice["suggestion_id"] not in old or choice.get("candidate_id") is not None
            and choice["candidate_id"] not in {item["candidate_id"] for item in old[choice["suggestion_id"]]["candidates"]} for choice in choices):
            raise CashImportPlanError("plan_input_invalid")
        current = {group["suggestion_id"]: group for group in groups}
        invalidated, touched = [], set()
        for choice in choices:
            identifier = choice["suggestion_id"]
            if identifier in current:
                action = choice["action"]
                if action not in current[identifier]["allowed_actions"]:
                    raise CashImportPlanError("plan_input_invalid")
                candidate_id = choice.get("candidate_id")
                if candidate_id:
                    candidate = next((item for item in current[identifier]["candidates"] if item["candidate_id"] == candidate_id), None)
                    if candidate is None:
                        invalidated.append({"kind": "relation", "reference": identifier, "code": "relation_context_changed"})
                        current[identifier]["action"] = "defer"
                        continue
                    for key in ("edges", "evidence", "parent_ids", "member_refs"):
                        current[identifier][key] = deepcopy(candidate[key])
                    current[identifier]["candidate_id"] = candidate_id
                elif action == "accept" and any(edge["secondary_transaction_id"] is None for edge in current[identifier]["edges"]):
                    raise CashImportPlanError("plan_input_invalid")
                current[identifier]["action"] = action
            else:
                invalidated.append({"kind": "relation", "reference": identifier, "code": "relation_context_changed"})
                touched.update(tuple(sorted(ref.items())) for ref in old[identifier]["member_refs"])
        for group in groups:
            if any(tuple(sorted(ref.items())) in touched for ref in group["member_refs"]):
                group["action"] = "defer"
        return invalidated

    @staticmethod
    def _apply_groups(snapshot, groups):
        failures = []
        old_closures = _closure_index(snapshot.context.relations)
        edges_by_group, group_by_edge = {}, {}
        for group in groups:
            state = {"accept": "confirmed", "defer": "draft", "ignore": "ignored"}[group["action"]]
            edges = [CashRelationGraphEdge(**dict(edge, state=state)) for edge in group["edges"]]
            edges_by_group[group["suggestion_id"]] = edges
            group_by_edge.update((str(edge.id), group) for edge in edges)
        changes = [_link(edge, group["suggestion_id"]) for group in groups for edge in edges_by_group[group["suggestion_id"]]]
        for identifiers in relation_commit_groups([_link(edge) for edge in snapshot.edges.values()], changes):
            related = {group_by_edge[identifier]["suggestion_id"]: group_by_edge[identifier] for identifier in identifiers}
            edges = [edge for suggestion in related for edge in edges_by_group[suggestion]]
            before = {edge.id: snapshot.edges.get(edge.id) for edge in edges}
            commit_id = "cg_" + _digest("cash-import-commit-group-v1", sorted(related))[:40]
            for group in related.values():
                group["commit_group_id"] = commit_id
            snapshot.register_edges(edges)
            try:
                parents = {parent for group in related.values() for identifier in group["parent_ids"]
                    for parent in old_closures.get(identifier, (identifier,))}
                build = snapshot.build(parents)
                applied = {edge.id: edge.applied_amount for projection in build.projections for edge in projection.relations}
                for edge in edges:
                    if edge.id in applied:
                        snapshot.edges[edge.id] = replace(edge, applied_amount=applied[edge.id])
                for group in related.values():
                    group["edges"] = [asdict(snapshot.edges[edge.id]) for edge in edges_by_group[group["suggestion_id"]]]
            except CashRelationError as error:
                snapshot.restore_edges(before)
                for group in related.values():
                    group.update(action="skip", code=error.code)
                    failures.append({"code": error.code, "parent_ids": group["parent_ids"],
                        "suggestion_id": group["suggestion_id"]})
        return failures

    @staticmethod
    def _ref(snapshot, identifier):
        operation = snapshot.parent_ops.get(identifier)
        return {"row_ref": operation["row_refs"][0]} if operation else {"transaction_id": identifier}

    def _present(self, files, operations, rows, snapshot, build, groups, failures, accounts, *, include_unchanged=False):
        changed = {parent for parent, operation in snapshot.parent_ops.items() if operation["planned_action"] != "duplicate"}
        touched = set(changed)
        if include_unchanged:
            touched.update(snapshot.parent_ops)
        touched.update(parent for group in groups for parent in group["parent_ids"])
        # 类型解除后同一旧组的其余成员也属于此次预计结果。
        old_links = [_link(edge) for edge in snapshot.context.relations]
        from ft.domain.cash_relation_policy import affected_relation_closure
        touched.update(affected_relation_closure(changed, old_links, [_link(edge) for edge in snapshot.edges.values()]))
        projections = [item for item in build.projections if any(str(identifier) in touched for identifier in item.member_ids)
            and (include_unchanged or item.visible or item.confirmation_status == "pending")]
        projections.sort(key=lambda item: (item.sort_at, item.primary_record.id), reverse=True)
        items, details, detail_values = [], {}, {}
        for index, projection in enumerate(projections):
            identifier = f"pi_{index + 1}"
            parents = {str(parent) for parent in projection.member_ids}
            relevant = [group for group in groups if parents.intersection(group["parent_ids"])]
            issues = [issue for parent in parents for key in ("issues", "display_retained_issues")
                for issue in snapshot.parent_ops.get(parent, {}).get(key, [])]
            if any(group["action"] == "defer" for group in relevant):
                issues.append(_pending("relation_pending", relation=True))
            issues.extend(_pending(failure["code"], relation=True) for failure in failures if parents.intersection(failure["parent_ids"]))
            item = {"preview_item_id": identifier, "confirmation_status": "pending" if issues or projection.confirmation_status == "pending" else "confirmed",
                "anticipated_state": projection.state, "statistics_eligible": projection.statistics_eligible,
                "display": {"occurred_at": projection.occurred_at, "amount": projection.net_amount, "currency": projection.primary_record.currency,
                    "counterparty": projection.primary_record.counterparty, "note": projection.primary_record.note,
                    "accounts": list(dict.fromkeys(accounts[part["account_id"]]["name"] for parent in sorted(parents)
                        for part in snapshot.values[parent]["components"] if part.get("account_id") in accounts))},
                "member_refs": [self._ref(snapshot, parent) for parent in sorted(parents)],
                "attention_reasons": issues, "detail_available": True}
            items.append(item)
            members, diffs = [], []
            for parent in sorted(parents):
                operation = snapshot.parent_ops.get(parent)
                value = snapshot.values[parent]
                members.append({**self._ref(snapshot, parent), "planned_action": operation["planned_action"] if operation else "duplicate",
                    "fields_ref": {"operation_ref": operation["operation_ref"]} if operation else {"transaction_id": parent},
                    "editable_fields": sorted(MUTABLE_CASH_FIELDS | (CASH_FINANCIAL_FIELDS if operation and (
                        operation["transaction_id"] is None or operation.get("current", {}).get("state") == "draft") else set())),
                    "allowed_actions": ["apply", "keep_existing", "defer"] if operation else [],
                    "row_revision": operation.get("expected_revision") if operation else dict(snapshot.context.transaction_revisions).get(int(parent))})
                if not operation:
                    detail_values[parent] = self._member_fields(value)
                if operation:
                    diffs.extend({**diff, **self._ref(snapshot, parent)} for diff in operation.get("field_diffs", []))
            effects = [{"relation_id": str(edge.id), "kind": edge.kind, "subtype": edge.subtype,
                "effect": "remove" if edge.id in snapshot.removed_relations else "keep",
                "member_refs": [self._ref(snapshot, parent) for parent in (edge.primary_transaction_id, edge.secondary_transaction_id) if parent in snapshot.values]}
                for edge in snapshot.context.relations if edge.active and edge.state == "confirmed"
                and parents.intersection((edge.primary_transaction_id, edge.secondary_transaction_id))]
            choices = [{key: group[key] for key in ("suggestion_id", "action", "candidate_id", "candidates", "member_refs", "allowed_actions")}
                | {"kind": group["edges"][0]["kind"], "subtype": group["edges"][0]["subtype"]} for group in relevant]
            details[identifier] = {"members": members, "field_diffs": diffs, "issues": issues, "relation_choices": choices, "relation_effects": effects}
        counts = dict(Counter(op["planned_action"] for op in operations))
        counts.update({"duplicate": sum(row["planned_action"] in {"duplicate", "duplicate_in_batch"} for row in rows),
            "skip": sum(row["planned_action"] == "skip" for row in rows),
            "relations_accepted": sum(group["action"] == "accept" for group in groups),
            "relations_deferred": sum(group["action"] == "defer" for group in groups),
            "relations_ignored": sum(group["action"] == "ignore" for group in groups), "relations_failed": len(failures)})
        processing = []
        for row in rows:
            if row["planned_action"] == "skip" or row["planned_action"] in {"duplicate", "duplicate_in_batch"}:
                processing.append({**row, **(row.get("issues") or [{}])[0]})
        processing.extend({"planned_action": "skip", "member_refs": [self._ref(snapshot, parent)
            for parent in failure["parent_ids"] if parent in snapshot.values], **_pending(failure["code"], relation=True)}
            for failure in failures)
        file_results = [{"file_ref": file.file_ref, "display_name": file.filename, "status": file.status,
            "recognized_row_count": len(file.document.rows) if file.document else 0,
            "issues": [cash_import_issue(file.error_code, blocking_scope="file")] if file.error_code else [],
            "can_retry": file.status != "parsed"} for file in files]
        return {"items": items, "item_details": details, "detail_values": detail_values, "processing_details": processing,
            "file_results": file_results, "summary": counts, "can_commit": any(file.status == "parsed" for file in files)}

    @staticmethod
    def _member_fields(value):
        result = {field: deepcopy(item) for field, item in value.items()
            if field in MUTABLE_CASH_FIELDS | CASH_FINANCIAL_FIELDS | {"state", "record_id", "source_type"}}
        for part in result["components"]:
            if part.get("component_id") and int(part["component_id"]) >= 2 ** 63:
                part.pop("component_id")
        return result


def _closure_index(edges):
    neighbors = defaultdict(set)
    for edge in edges:
        if edge.active and edge.state == "confirmed" and edge.secondary_transaction_id is not None:
            neighbors[edge.primary_transaction_id].add(edge.secondary_transaction_id)
            neighbors[edge.secondary_transaction_id].add(edge.primary_transaction_id)
    result = {}
    for identifier in neighbors:
        if identifier in result:
            continue
        members, frontier = set(), [identifier]
        while frontier:
            item = frontier.pop()
            if item in members:
                continue
            members.add(item)
            frontier.extend(neighbors[item] - members)
        members = frozenset(members)
        result.update((item, members) for item in members)
    return result


def _source_key_expression(native_key, *source_keys):
    return func.coalesce(CashTransactionModel.relation_metadata[native_key].as_string(),
        CashTransactionModel.source_payload[native_key].as_string(),
        *(CashTransactionModel.source_payload[key].as_string() for key in source_keys))
