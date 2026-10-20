"""独立生效的账户命令；导入退出补偿也使用同一 CAS 与幂等边界。"""
from __future__ import annotations

from decimal import Decimal, InvalidOperation
from hashlib import sha256
import json
import re

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from ft.adapters.relational.cash_mutations import CashMutationRepository
from ft.adapters.relational.models import AccountModel, WorkspaceMembershipModel
from ft.application.access import PermissionDenied
from ft.domain.accounts import ACCOUNT_TYPES


class AccountCommandError(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def _account_id(value):
    if type(value) is int and value > 0:
        value = str(value)
    if not isinstance(value, str) or not re.fullmatch(r"[1-9][0-9]{0,18}", value) or int(value) >= 2 ** 63:
        raise AccountCommandError("account_payload_invalid")
    return int(value)


def _expected(value):
    if type(value) is not int or not 1 <= value < 2 ** 63 - 1:
        raise AccountCommandError("account_payload_invalid")
    return value


def _fields(payload, *, create=False):
    allowed = {"name", "type", "currencies"} if create else {"name", "active", "currencies"}
    if not isinstance(payload, dict) or set(payload) - allowed or create and not {"name", "type"} <= payload.keys():
        raise AccountCommandError("account_payload_invalid")
    normalized = dict(payload)
    if "name" in normalized:
        name = normalized["name"]
        if not isinstance(name, str) or not 1 <= len(name.strip()) <= 255:
            raise AccountCommandError("account_payload_invalid")
        try:
            name.encode("utf-8")
        except UnicodeError:
            raise AccountCommandError("account_payload_invalid") from None
        normalized["name"] = name.strip()
    if create and (not isinstance(normalized["type"], str) or normalized["type"] not in ACCOUNT_TYPES):
        raise AccountCommandError("account_payload_invalid")
    if "active" in normalized and type(normalized["active"]) is not bool:
        raise AccountCommandError("account_payload_invalid")
    if create:
        normalized.setdefault("currencies", [])
    if "currencies" in normalized:
        currencies = normalized["currencies"]
        if (not isinstance(currencies, list) or len(currencies) > 100
            or any(not isinstance(currency, str) or not re.fullmatch(r"[A-Za-z]{3}", currency.strip()) for currency in currencies)):
            raise AccountCommandError("account_payload_invalid")
        normalized["currencies"] = sorted({currency.strip().upper() for currency in currencies})
    return normalized


class AccountCommandService:
    def __init__(self, uow, *, user_id):
        self._uow, self._workspace_id, self._user_id = uow, uow.workspace_id, user_id

    def _authorize(self, session, *, write):
        query = select(WorkspaceMembershipModel).where(WorkspaceMembershipModel.workspace_id == self._workspace_id,
            WorkspaceMembershipModel.user_id == self._user_id)
        if write and session.bind.dialect.name == "postgresql":
            query = query.with_for_update()
        member = session.scalar(query)
        if member is None or write and member.role not in {"admin", "editor"}:
            raise PermissionDenied("workspace_forbidden")
        return member.role in {"admin", "editor"}

    def _account(self, session, account_id, *, lock=True):
        query = select(AccountModel).where(AccountModel.workspace_id == self._workspace_id, AccountModel.id == account_id)
        if lock and session.bind.dialect.name == "postgresql":
            query = query.with_for_update()
        row = session.scalar(query)
        if row is None:
            raise AccountCommandError("account_not_found")
        return row

    @staticmethod
    def _view(row, *, deleted=False):
        return {"account_id": str(row.id), "name": row.name, "type": row.type, "active": row.active,
            "currencies": list(row.currencies or ()), "account_revision": row.revision, "deleted": deleted}

    @staticmethod
    def _has_references(session, row):
        # 包含 CASCADE 配置引用；补偿不能顺带删掉其他流程建立的映射或别名。
        for table in AccountModel.metadata.tables.values():
            for constraint in table.foreign_key_constraints:
                if not all(element.column.table is AccountModel.__table__ for element in constraint.elements):
                    continue
                conditions = [element.parent == getattr(row, element.column.name) for element in constraint.elements]
                if session.execute(select(1).select_from(table).where(*conditions).limit(1)).first():
                    return True
        return False

    @staticmethod
    def _has_balance(pockets):
        if not isinstance(pockets, dict):
            return True
        try:
            return any(not isinstance(value, (str, int, Decimal)) or not Decimal(str(value)).is_finite() or Decimal(str(value)) != 0 for value in pockets.values())
        except (InvalidOperation, ValueError):
            return True

    @staticmethod
    def _snapshot_bucket(snapshot, row):
        bucket_type = "security" if row.type in {"security", "crypto"} else row.type
        accounts = snapshot.get("accounts", {})
        return accounts.get(bucket_type, {}) if isinstance(accounts, dict) else accounts

    @classmethod
    def _snapshot_in_use(cls, bucket, row):
        if not isinstance(bucket, dict):
            return True
        return row.name in bucket and cls._has_balance(bucket[row.name])

    def get(self, account_id):
        account_id = _account_id(account_id)
        with self._uow as uow:
            session = uow._state().session
            writable = self._authorize(session, write=False)
            row = self._account(session, account_id, lock=False)
            result = self._view(row)
            can_delete = writable and not row.active and not self._has_references(session, row)
            if can_delete:
                bucket = self._snapshot_bucket(uow.snapshot.load(), row)
                can_delete = not self._snapshot_in_use(bucket, row)
            result["capabilities"] = {"can_update": writable, "can_delete": can_delete}
            return result

    def create(self, payload, *, idempotency_key):
        return self._execute("account_create", _fields(payload, create=True), idempotency_key=idempotency_key)

    def update(self, account_id, patch, *, expected_revision, idempotency_key):
        return self._execute("account_update", _fields(patch), account_id=_account_id(account_id),
            expected_revision=_expected(expected_revision), idempotency_key=idempotency_key)

    def delete(self, account_id, *, expected_revision, idempotency_key):
        return self._execute("account_delete", {}, account_id=_account_id(account_id),
            expected_revision=_expected(expected_revision), idempotency_key=idempotency_key)

    def _execute(self, operation, payload, *, idempotency_key, account_id=None, expected_revision=None):
        digest = sha256(json.dumps({"operation": operation, "account_id": account_id, "expected_revision": expected_revision, "payload": payload},
            sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()
        try:
            with self._uow as uow:
                session = uow._state().session
                self._authorize(session, write=True)
                receipts = CashMutationRepository(session, self._workspace_id)
                replay = receipts.replay(self._user_id, idempotency_key, request_digest=digest, operation_kind=operation)
                if replay is not None:
                    return replay
                row = self._account(session, account_id) if account_id is not None else None
                if row is not None and row.revision != expected_revision:
                    raise AccountCommandError("account_changed")
                if "name" in payload:
                    query = select(AccountModel.id).where(AccountModel.workspace_id == self._workspace_id, AccountModel.name == payload["name"])
                    if row is not None:
                        query = query.where(AccountModel.id != row.id)
                    if session.scalar(query) is not None:
                        raise AccountCommandError("account_name_unavailable")
                if operation == "account_create":
                    row = AccountModel(workspace_id=self._workspace_id, **payload)
                    session.add(row); session.flush()
                elif operation == "account_update":
                    for field, value in payload.items():
                        setattr(row, field, value)
                    row.revision += 1
                    session.flush()
                snapshot = uow.snapshot.load(lock=True)
                bucket = self._snapshot_bucket(snapshot, row)
                if operation == "account_delete":
                    if row.active:
                        raise AccountCommandError("account_active")
                    if self._has_references(session, row) or self._snapshot_in_use(bucket, row):
                        raise AccountCommandError("account_in_use")
                    result_account = self._view(row, deleted=True)
                    result_account["account_revision"] += 1
                    if isinstance(bucket, dict):
                        bucket.pop(row.name, None)
                    session.delete(row); session.flush()
                else:
                    if row.type in {"cash", "loan", "lend"}:
                        bucket = snapshot.setdefault("accounts", {}).setdefault(row.type, {})
                        pockets = bucket.setdefault(row.name, {})
                        if not isinstance(pockets, dict):
                            raise AccountCommandError("account_in_use")
                        for currency in payload.get("currencies", ()):
                            pockets.setdefault(currency, "0")
                    result_account = self._view(row)
                uow.snapshot.save(snapshot)
                receipt = receipts.create(user_id=self._user_id, idempotency_key=idempotency_key, request_digest=digest, operation_kind=operation,
                    result_summary={"status": "completed", "account_id": result_account["account_id"], "account_revision": result_account["account_revision"], "account": result_account},
                    results=[{"result_kind": "account", "item_ref": result_account["account_id"], "account_id": result_account["account_id"], "outcome": operation.removeprefix("account_") + "d"}])
                uow.commit()
                return receipt
        except IntegrityError:
            raise AccountCommandError("account_in_use" if operation == "account_delete" else "account_name_unavailable") from None
