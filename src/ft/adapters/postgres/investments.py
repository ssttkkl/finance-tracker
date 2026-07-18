"""PostgreSQL investment command adapter."""
from __future__ import annotations

from ft.domain.application import OperationResult
from ft.domain.investment_projection import apply_investment_command


class PostgresInvestmentCommandRepository:
    def __init__(self, unit_of_work):
        self._uow = unit_of_work

    def execute(self, command) -> OperationResult:
        with self._uow as uow:
            account = uow.accounts.find(command.account)
            if account is None:
                uow.rollback()
                return OperationResult(ok=False, message=f"account not found: {command.account}")
            if account.type not in {"security", "crypto"}:
                uow.rollback()
                return OperationResult(ok=False, message="investment command requires an investment account")
            snapshot = uow.snapshot.load(lock=True)
            row = apply_investment_command(
                snapshot, command, account_type=account.type, default_currency=account.currency
            )
            uow.investments.add(account.type, row)
            uow.snapshot.save(snapshot)
            uow.commit()
        return OperationResult(ok=True, message=command.action, details={"row": row})
