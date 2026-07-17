"""Account application service."""
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
            updated = [
                AccountDTO(normalized_new, a.type, a.currency, a.active)
                if a.name == old_name and a.currency == currency else a
                for a in accounts
            ]
            uow.accounts.replace_all(updated)
            uow.commit()
            return AccountResult.success(AccountDTO(normalized_new, target.type, target.currency, target.active))

    def delete_account(self, name: str, currency: str) -> AccountResult:
        with self._uow as uow:
            accounts = uow.accounts.list()
            remaining = [a for a in accounts if not (a.name == name and a.currency == currency)]
            if len(remaining) == len(accounts):
                uow.rollback()
                return AccountResult.fail("account.not_found", f"未找到账户: {name} ({currency})")
            removed = next(a for a in accounts if a.name == name and a.currency == currency)
            uow.accounts.replace_all(remaining)
            uow.commit()
            return AccountResult.success(removed)

    def set_active(self, name: str, currency: str, active: bool) -> AccountResult:
        with self._uow as uow:
            accounts = uow.accounts.list()
            target = None
            updated = []
            for account in accounts:
                if account.name == name and account.currency == currency:
                    target = AccountDTO(account.name, account.type, account.currency, active)
                    updated.append(target)
                else:
                    updated.append(account)
            if target is None:
                uow.rollback()
                return AccountResult.fail("account.not_found", f"未找到账户: {name} ({currency})")
            uow.accounts.replace_all(updated)
            uow.commit()
            return AccountResult.success(target)
