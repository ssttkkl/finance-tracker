"""PostgreSQL investment command adapter."""
from __future__ import annotations

from ft.domain.application import OperationResult
from ft.domain.investment_projection import apply_investment_command, normalize_base_tickers
from ft.domain.investment_validation import validate_investment_snapshot


def _base_tickers_for_account(uow, account_name: str):
    """Load account metadata.base_currencies for projection."""
    for row in uow.accounts.list_raw():
        if row.get("name") == account_name:
            return normalize_base_tickers(row.get("base_currencies"))
    return normalize_base_tickers(None)


class RelationalInvestmentCommandRepository:
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
            try:
                snapshot = uow.snapshot.load(lock=True)
                bases = _base_tickers_for_account(uow, command.account)
                row = apply_investment_command(
                    snapshot, command, account_type=account.type,
                    default_currency=command.currency, base_tickers=bases,
                )
                validate_investment_snapshot(snapshot)
                uow.investments.add(account.type, row)
                uow.snapshot.save(snapshot)
                uow.commit()
            except ValueError as exc:
                uow.rollback()
                return OperationResult(ok=False, message=str(exc))
        return OperationResult(ok=True, message=command.action, details={"row": row})
