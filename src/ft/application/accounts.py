"""Account application service."""
from datetime import datetime, timezone

from ft.domain.accounts import ACCOUNT_TYPES, CURRENCIES, AccountDTO, AccountResult
from ft.repositories import UnitOfWork


class AccountService:
    def __init__(self, uow: UnitOfWork):
        self._uow = uow

    def create_account(self, name: str, type_: str, currency: str) -> AccountResult:
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
        if currency not in CURRENCIES:
            return AccountResult.fail(
                "account.invalid_currency",
                f"无效币种 '{currency}'，可用币种: {CURRENCIES}",
                currency=currency,
                allowed=CURRENCIES,
            )

        account = AccountDTO(
            name=normalized_name,
            type=type_,
            currency=currency,
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
            existing = uow.accounts.find(normalized_name, currency)
            if existing is not None:
                uow.rollback()
                return AccountResult.fail(
                    "account.duplicate",
                    f"账户已存在: {normalized_name} ({currency})",
                    name=normalized_name,
                    currency=currency,
                )
            uow.accounts.add(account)
            if hasattr(uow, "wealth_facts"):
                uow.wealth_facts.record_lifecycle(
                    account_name=account.name, currency=account.currency, event_kind="opened",
                    effective_at=datetime.now(timezone.utc),
                )
            uow.commit()
        return AccountResult.success(account)

    def list_accounts(self) -> list[AccountDTO]:
        with self._uow as uow:
            accounts = uow.accounts.list()
            uow.commit()
            return accounts

    def rename_account(self, old_name: str, new_name: str, currency: str) -> AccountResult:
        normalized_new = new_name.strip()
        if not normalized_new:
            return AccountResult.fail("account.invalid_name", "账户名不能为空")
        with self._uow as uow:
            accounts = uow.accounts.list()
            target = None
            for account in accounts:
                if account.name == old_name and account.currency == currency:
                    target = account
                    break
            if target is None:
                uow.rollback()
                return AccountResult.fail("account.not_found", f"未找到账户: {old_name} ({currency})")
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
            if any(a.name == normalized_new and a.currency == currency for a in accounts):
                uow.rollback()
                return AccountResult.fail("account.duplicate", f"账户已存在: {normalized_new} ({currency})")
            updated = uow.accounts.rename(old_name, currency, normalized_new)
            uow.commit()
            return AccountResult.success(updated)

    def delete_account(self, name: str, currency: str) -> AccountResult:
        with self._uow as uow:
            target = uow.accounts.find(name, currency)
            if target is None:
                uow.rollback()
                return AccountResult.fail("account.not_found", f"未找到账户: {name} ({currency})")
            if uow.accounts.has_facts(name, currency):
                uow.rollback()
                return AccountResult.fail(
                    "account.in_use",
                    "账户已有正式事实，不能删除；请停用账户",
                    name=name,
                    currency=currency,
                )
            if target.active:
                uow.rollback()
                return AccountResult.fail(
                    "account.active",
                    "账户仍处于启用状态，请先停用账户",
                    name=name,
                    currency=currency,
                )
            removed = uow.accounts.delete(name, currency)
            uow.commit()
            return AccountResult.success(removed)

    def set_active(self, name: str, currency: str, active: bool) -> AccountResult:
        with self._uow as uow:
            target = uow.accounts.find(name, currency)
            if target is None:
                uow.rollback()
                return AccountResult.fail("account.not_found", f"未找到账户: {name} ({currency})")
            target = uow.accounts.set_active(name, currency, active)
            if hasattr(uow, "wealth_facts"):
                uow.wealth_facts.record_lifecycle(
                    account_name=target.name, currency=target.currency,
                    event_kind="reactivated" if active else "closed", effective_at=datetime.now(timezone.utc),
                )
            uow.commit()
            return AccountResult.success(target)
