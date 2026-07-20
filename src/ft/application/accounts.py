"""Account application service."""
from datetime import datetime, timezone

from ft.domain.accounts import ACCOUNT_TYPES, AccountDTO, AccountResult, normalize_currency
from ft.repositories import UnitOfWork


class AccountService:
    def __init__(self, uow: UnitOfWork):
        self._uow = uow

    def create_account(
        self, name: str, type_: str, currency: str | None = None,
    ) -> AccountResult:
        normalized_name = name.strip()
        if not normalized_name:
            return AccountResult.fail("account.invalid_name", "账户名不能为空")
        if type_ not in ACCOUNT_TYPES:
            return AccountResult.fail(
                "account.invalid_type",
                f"无效账户类型 '{type_}'，可用类型: {ACCOUNT_TYPES}",
                type=type_,
                allowed=ACCOUNT_TYPES,
            )
        seed_currency: str | None = None
        if currency is not None and str(currency).strip():
            try:
                seed_currency = normalize_currency(currency)
            except ValueError:
                return AccountResult.fail(
                    "account.invalid_currency",
                    f"无效币种 '{currency}'，须为 3 位字母币种码（如 CNY/USD/JPY）",
                    currency=currency,
                )

        account = AccountDTO(
            name=normalized_name,
            type=type_,
            active=True,
        )
        with self._uow as uow:
            accounts = uow.accounts.list()
            if type_ in {"security", "crypto"} and any(
                candidate.name == normalized_name
                and candidate.type in {"security", "crypto"}
                for candidate in accounts
            ):
                uow.rollback()
                return AccountResult.fail(
                    "account.duplicate_investment_name",
                    f"投资账户展示名必须唯一: {normalized_name}",
                    name=normalized_name,
                )
            existing = uow.accounts.find(normalized_name)
            if existing is not None:
                uow.rollback()
                return AccountResult.fail(
                    "account.duplicate",
                    f"账户已存在: {normalized_name}",
                    name=normalized_name,
                )
            uow.accounts.add(account)
            if seed_currency is not None and type_ in {"cash", "loan", "lend"}:
                snap = uow.snapshot.load(lock=True)
                bucket = snap.setdefault("accounts", {}).setdefault(type_, {})
                pockets = bucket.setdefault(normalized_name, {})
                if isinstance(pockets, dict):
                    pockets.setdefault(seed_currency, "0")
                uow.snapshot.save(snap)
            if hasattr(uow, "wealth_facts"):
                uow.wealth_facts.record_lifecycle(
                    account_name=account.name, event_kind="opened",
                    effective_at=datetime.now(timezone.utc),
                )
            uow.commit()
        return AccountResult.success(account)

    def list_accounts(self) -> list[AccountDTO]:
        with self._uow as uow:
            accounts = uow.accounts.list()
            uow.commit()
            return accounts

    def rename_account(self, old_name: str, new_name: str) -> AccountResult:
        normalized_new = new_name.strip()
        if not normalized_new:
            return AccountResult.fail("account.invalid_name", "账户名不能为空")
        with self._uow as uow:
            accounts = uow.accounts.list()
            target = next((account for account in accounts if account.name == old_name), None)
            if target is None:
                uow.rollback()
                return AccountResult.fail("account.not_found", f"未找到账户: {old_name}")
            if target.type in {"security", "crypto"} and any(
                account is not target
                and account.name == normalized_new
                and account.type in {"security", "crypto"}
                for account in accounts
            ):
                uow.rollback()
                return AccountResult.fail(
                    "account.duplicate_investment_name",
                    f"投资账户展示名必须唯一: {normalized_new}",
                    name=normalized_new,
                )
            if any(account.name == normalized_new for account in accounts):
                uow.rollback()
                return AccountResult.fail("account.duplicate", f"账户已存在: {normalized_new}")
            updated = uow.accounts.rename(old_name, normalized_new)
            uow.commit()
            return AccountResult.success(updated)

    def delete_account(self, name: str) -> AccountResult:
        with self._uow as uow:
            target = uow.accounts.find(name)
            if target is None:
                uow.rollback()
                return AccountResult.fail("account.not_found", f"未找到账户: {name}")
            if uow.accounts.has_facts(name):
                uow.rollback()
                return AccountResult.fail(
                    "account.in_use",
                    "账户已有正式事实，不能删除；请停用账户",
                    name=name,
                )
            if target.active:
                uow.rollback()
                return AccountResult.fail(
                    "account.active",
                    "账户仍处于启用状态，请先停用账户",
                    name=name,
                )
            removed = uow.accounts.delete(name)
            uow.commit()
            return AccountResult.success(removed)

    def set_active(self, name: str, active: bool) -> AccountResult:
        with self._uow as uow:
            target = uow.accounts.find(name)
            if target is None:
                uow.rollback()
                return AccountResult.fail("account.not_found", f"未找到账户: {name}")
            target = uow.accounts.set_active(name, active)
            if hasattr(uow, "wealth_facts"):
                uow.wealth_facts.record_lifecycle(
                    account_name=target.name,
                    event_kind="reactivated" if active else "closed",
                    effective_at=datetime.now(timezone.utc),
                )
            uow.commit()
            return AccountResult.success(target)
