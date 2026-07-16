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
