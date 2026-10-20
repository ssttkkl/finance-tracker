"""当前命令的永久收据和分页结果；调用方持有工作区锁及外层事务。"""
from __future__ import annotations

from copy import deepcopy
from datetime import datetime
import json
import re

from sqlalchemy import select

from .models import CashMutationReceiptModel, CashMutationResultModel


class CashMutationConflict(ValueError):
    def __init__(self, code, receipt_id=None):
        self.code, self.receipt_id = code, receipt_id
        super().__init__(code)


def _text(value, maximum, *, code="mutation_reference_invalid", nullable=False):
    if value is None and nullable:
        return None
    if type(value) is int and value > 0:
        value = str(value)
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_:.~-]{1," + str(maximum) + "}", value):
        raise ValueError(code)
    return value


def _digest(value, *, nullable=False):
    if value is None and nullable:
        return None
    if not isinstance(value, str) or not re.fullmatch("[0-9a-f]{64}", value):
        raise ValueError("mutation_digest_invalid")
    return value


def _account_result(value):
    from ft.domain.accounts import ACCOUNT_TYPES
    expected = {"account_id", "name", "type", "active", "currencies", "account_revision", "deleted"}
    if (not isinstance(value, dict) or set(value) != expected
        or not isinstance(value["name"], str) or not 1 <= len(value["name"]) <= 255
        or not isinstance(value["type"], str) or value["type"] not in ACCOUNT_TYPES
        or type(value["active"]) is not bool or type(value["deleted"]) is not bool
        or type(value["account_revision"]) is not int or not 1 <= value["account_revision"] < 2 ** 63
        or not isinstance(value["currencies"], list) or len(value["currencies"]) > 100
        or any(not isinstance(currency, str) or not re.fullmatch(r"[A-Z]{3}", currency) for currency in value["currencies"])
        or len(set(value["currencies"])) != len(value["currencies"])):
        raise ValueError("mutation_summary_invalid")
    return dict(value, account_id=_text(value["account_id"], 128, code="mutation_summary_invalid"))


def _summary(value):
    counters = {"needs_attention_count", "pending_item_count", "projection_version", "account_revision"}
    references = {"retry_context_id", "account_id", "retained_transaction_id"}
    reference_lists = {"affected_transaction_ids"}
    allowed = counters | references | reference_lists | {"status", "summary", "committed", "retry_context_expires_at", "projection_refs", "account"}
    if not isinstance(value, dict) or set(value) - allowed:
        raise ValueError("mutation_summary_invalid")
    value = deepcopy(value)
    for key, item in value.items():
        if key in counters:
            if type(item) is not int or item < 0:
                raise ValueError("mutation_summary_invalid")
        elif key in references:
            value[key] = _text(item, 128, code="mutation_summary_invalid", nullable=True)
        elif key in reference_lists:
            if not isinstance(item, list):
                raise ValueError("mutation_summary_invalid")
            value[key] = [_text(reference, 128, code="mutation_summary_invalid") for reference in item]
        elif key == "projection_refs":
            if not isinstance(item, list) or any(not isinstance(reference, dict) or set(reference) != {"transaction_id", "projection_id"} for reference in item):
                raise ValueError("mutation_summary_invalid")
            value[key] = [{field: _text(reference[field], 128, code="mutation_summary_invalid") for field in ("transaction_id", "projection_id")} for reference in item]
        elif key == "account":
            value[key] = _account_result(item)
        elif key == "status":
            if not isinstance(item, str) or item not in {"completed", "partial", "no_changes", "failed"}:
                raise ValueError("mutation_summary_invalid")
        elif key == "committed":
            if item is not True:
                raise ValueError("mutation_summary_invalid")
        elif key == "summary":
            if not isinstance(item, dict) or any(not isinstance(name, str) or not re.fullmatch("[a-z][a-z0-9_]{0,63}", name) or type(count) is not int or count < 0 for name, count in item.items()):
                raise ValueError("mutation_summary_invalid")
        elif key == "retry_context_expires_at" and item is not None:
            try:
                if not isinstance(item, str) or datetime.fromisoformat(item.replace("Z", "+00:00")).tzinfo is None:
                    raise ValueError
            except ValueError:
                raise ValueError("mutation_summary_invalid") from None
    try:
        if "account" in value and (value.get("account_id") != value["account"]["account_id"] or value.get("account_revision") != value["account"]["account_revision"]):
            raise ValueError
        if len(json.dumps(value, allow_nan=False).encode()) > 1024 * 1024:
            raise ValueError
    except (TypeError, ValueError, RecursionError):
        raise ValueError("mutation_summary_invalid") from None
    return deepcopy(value)


_RESULT_FIELDS = {"result_kind", "item_ref", "outcome", "code", "operation_ref", "transaction_id", "relation_id", "account_id"}


def _results(values):
    if not isinstance(values, list):
        raise ValueError("mutation_results_invalid")
    result, seen = [], set()
    for value in values:
        if not isinstance(value, dict) or set(value) - _RESULT_FIELDS or not {"result_kind", "item_ref", "outcome"} <= value.keys():
            raise ValueError("mutation_result_invalid")
        item = dict(value)
        if not isinstance(item["result_kind"], str) or item["result_kind"] not in {"row", "relation", "file", "account"}:
            raise ValueError("mutation_result_invalid")
        item["item_ref"] = _text(item["item_ref"], 255, code="mutation_result_invalid")
        for key, maximum in (("outcome", 32), ("code", 64)):
            entry = item.get(key)
            if entry is None and key == "code":
                continue
            if not isinstance(entry, str) or not re.fullmatch(r"[a-z][a-z0-9_]{0," + str(maximum - 1) + "}", entry):
                raise ValueError("mutation_result_invalid")
        for key in ("transaction_id", "relation_id", "account_id", "operation_ref"):
            item[key] = _text(item.get(key), 128, code="mutation_result_invalid", nullable=True)
        identity = item["result_kind"], item["item_ref"]
        if identity in seen:
            raise ValueError("mutation_result_invalid")
        seen.add(identity)
        result.append(item)
    return result


class CashMutationRepository:
    def __init__(self, session, workspace_id):
        self._session, self._workspace_id = session, workspace_id

    @staticmethod
    def _view(row):
        if row is None:
            return None
        return {"receipt_id": row.id, "plan_id": row.plan_id, "operation_kind": row.operation_kind, "committed": True,
            "request_digest": row.request_digest, "session_digest": row.session_digest,
            "edit_context_digest": row.edit_context_digest, "created_at": row.created_at.isoformat(),
            **deepcopy(row.result_summary)}

    def _query(self, user_id):
        return select(CashMutationReceiptModel).where(CashMutationReceiptModel.workspace_id == self._workspace_id,
            CashMutationReceiptModel.user_id == _text(user_id, 128))

    def by_key(self, user_id, idempotency_key):
        row = self._session.scalar(self._query(user_id).where(CashMutationReceiptModel.idempotency_key == _text(idempotency_key, 255)))
        return self._view(row)

    def get(self, user_id, receipt_id):
        return self._view(self._session.scalar(self._query(user_id).where(CashMutationReceiptModel.id == _text(receipt_id, 36))))

    def replay(self, user_id, idempotency_key, *, request_digest, operation_kind, plan_id=None):
        row = self.by_key(user_id, idempotency_key)
        if row is not None and (row["request_digest"] != _digest(request_digest) or row["operation_kind"] != operation_kind or row["plan_id"] != plan_id):
            raise CashMutationConflict("idempotency_conflict", row["receipt_id"])
        return row

    def for_context(self, user_id, *, session_digest=None, edit_context_digest=None):
        if (session_digest is None) == (edit_context_digest is None):
            raise ValueError("mutation_context_invalid")
        column = CashMutationReceiptModel.session_digest if session_digest is not None else CashMutationReceiptModel.edit_context_digest
        digest = _digest(session_digest if session_digest is not None else edit_context_digest)
        return self._view(self._session.scalar(self._query(user_id).where(column == digest)))

    def create(self, *, user_id, idempotency_key, request_digest, operation_kind, result_summary, results,
               plan_id=None, session_digest=None, edit_context_digest=None):
        user_id, idempotency_key = _text(user_id, 128), _text(idempotency_key, 255)
        request_digest = _digest(request_digest)
        plan_id = _text(plan_id, 128, nullable=True)
        if not isinstance(operation_kind, str) or not re.fullmatch("[a-z][a-z0-9_]{0,31}", operation_kind):
            raise ValueError("mutation_operation_invalid")
        session_digest, edit_context_digest = _digest(session_digest, nullable=True), _digest(edit_context_digest, nullable=True)
        if session_digest is not None and edit_context_digest is not None:
            raise ValueError("mutation_context_invalid")
        summary, items = _summary(result_summary), _results(results)
        existing = self.by_key(user_id, idempotency_key)
        if existing:
            # 创建必须在执行前先 replay；不能靠写后重放掩盖重复业务写入。
            raise CashMutationConflict("idempotency_conflict", existing["receipt_id"])
        if session_digest is not None or edit_context_digest is not None:
            consumed = self.for_context(user_id, session_digest=session_digest, edit_context_digest=edit_context_digest)
            if consumed:
                raise CashMutationConflict("session_committed" if session_digest is not None else "edit_context_committed", consumed["receipt_id"])
        receipt = CashMutationReceiptModel(workspace_id=self._workspace_id, user_id=user_id, idempotency_key=idempotency_key,
            request_digest=request_digest, operation_kind=operation_kind, plan_id=plan_id, session_digest=session_digest,
            edit_context_digest=edit_context_digest, result_summary=summary)
        self._session.add(receipt); self._session.flush()
        self._session.add_all(CashMutationResultModel(workspace_id=self._workspace_id, receipt_id=receipt.id, **item) for item in items)
        self._session.flush()
        return self._view(receipt)

    def results(self, user_id, receipt_id, *, result_kind=None, after=0, limit=50):
        if type(limit) is not int or not 1 <= limit <= 200 or type(after) is not int or not 0 <= after <= 2**63 - 1 or result_kind is not None and (not isinstance(result_kind, str) or result_kind not in {"row", "relation", "file", "account"}):
            raise ValueError("mutation_page_invalid")
        if self.get(user_id, receipt_id) is None:
            return None
        statement = select(CashMutationResultModel).where(CashMutationResultModel.workspace_id == self._workspace_id,
            CashMutationResultModel.receipt_id == receipt_id, CashMutationResultModel.id > after)
        if result_kind is not None:
            statement = statement.where(CashMutationResultModel.result_kind == result_kind)
        rows = self._session.scalars(statement.order_by(CashMutationResultModel.id).limit(limit + 1)).all()
        return {"items": [{"result_id": str(row.id), **{field: getattr(row, field) for field in _RESULT_FIELDS}} for row in rows[:limit]],
            "next_after": rows[limit - 1].id if len(rows) > limit else None}
