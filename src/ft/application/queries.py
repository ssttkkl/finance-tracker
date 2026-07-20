"""Storage-independent finance query application service."""
from __future__ import annotations

from collections import defaultdict
from decimal import Decimal, InvalidOperation

from ft.domain.queries import (
    AccountBalanceDTO,
    AccountListDTO,
    FinanceReportDTO,
    FlowDTO,
    TransactionDTO,
    TransactionPageDTO,
)


def _decimal(value) -> Decimal:
    try:
        return Decimal(str(value or 0))
    except (InvalidOperation, ValueError, TypeError):
        return Decimal("0")


class FinanceQueryService:
    def __init__(self, *, accounts, transactions, snapshots, market_data):
        self._accounts = accounts
        self._transactions = transactions
        self._snapshots = snapshots
        self._market_data = market_data

    def list_accounts(self) -> AccountListDTO:
        snapshot = self._snapshots.load_snapshot()
        items = tuple(
            AccountBalanceDTO(account.name, account.type, currency, account.active, balance)
            for account in self._accounts.list_accounts()
            for currency, balance in self._account_balances(account, snapshot)
        )
        return AccountListDTO(items)

    def report(self, *, month: str | None = None) -> FinanceReportDTO:
        month_rows = self._transactions.list_transactions(month=month)
        all_rows = self._transactions.list_transactions()
        active_accounts = {
            account.name
            for account in self._accounts.list_accounts()
            if account.active
        }

        by_account: dict[tuple[str, str], list[dict]] = defaultdict(list)
        for row in month_rows:
            key = (
                row.get("account_name", "").strip(),
                row.get("currency", "").strip() or "CNY",
            )
            if key[0]:
                by_account[key].append(row)

        expenses: dict[str, Decimal] = defaultdict(lambda: Decimal("0"))
        for key, rows in by_account.items():
            if key[0] not in active_accounts:
                continue
            rows.sort(key=lambda row: row.get("date", ""))
            last_checkin = max(
                (index for index, row in enumerate(rows) if row.get("category") == "checkin"),
                default=-1,
            )
            for row in rows[last_checkin + 1:]:
                if row.get("category") == "expense":
                    expenses[key[1]] += abs(_decimal(row.get("amount")))

        income: dict[str, Decimal] = defaultdict(lambda: Decimal("0"))
        for row in month_rows:
            if row.get("category") == "income":
                income[row.get("currency", "CNY") or "CNY"] += _decimal(row.get("amount"))

        grouped_flows: dict[tuple[str, str], Decimal] = defaultdict(lambda: Decimal("0"))
        for row in all_rows:
            if row.get("category") not in {"transfer", "transfer_in", "transfer_out"}:
                continue
            if row.get("category") == "transfer_in":
                continue
            description = row.get("transfer_account") or row.get("description", "")
            currency = row.get("currency", "CNY") or "CNY"
            grouped_flows[(description, currency)] += abs(_decimal(row.get("amount")))
        flows = tuple(
            FlowDTO(description, currency, amount)
            for (description, currency), amount in sorted(
                grouped_flows.items(), key=lambda item: item[1], reverse=True
            )[:10]
        )
        return FinanceReportDTO(
            accounts=self.list_accounts(),
            expenses=dict(expenses),
            income=dict(income),
            flows=flows,
        )

    def list_transactions(
        self,
        *,
        month: str | None = None,
        account: str | None = None,
        category: str | None = None,
        limit: int = 30,
    ) -> TransactionPageDTO:
        rows = self._transactions.list_transactions(
            month=month, account=account, category=category, limit=limit
        )
        items = tuple(self._transaction_dto(row) for row in rows)
        return TransactionPageDTO(items)

    def _account_balances(self, account, snapshot: dict) -> tuple[tuple[str, Decimal], ...]:
        accounts = snapshot.get("accounts", {})
        if account.type in {"cash", "loan", "lend"}:
            bucket = accounts.get(account.type, {}).get(account.name, {})
            if isinstance(bucket, dict):
                return tuple((currency, _decimal(balance)) for currency, balance in sorted(bucket.items()))
            return (("CNY", _decimal(bucket)),)

        security = accounts.get("security", {}).get(account.name, {})
        positions = security.get("positions", {}) if isinstance(security, dict) else {}
        quote_currency = str(
            security.get("currency") or next(iter(getattr(account, "metadata", {}).get("base_currencies", ())), "CNY")
        ).upper()
        currency_ticker = quote_currency.lower()
        total = Decimal("0")
        market_tickers = []
        for ticker, position in positions.items():
            if ticker.lower() == currency_ticker:
                total += _decimal(position.get("shares"))
            elif _decimal(position.get("shares")) != 0:
                market_tickers.append(ticker)
        prices = self._market_data.get_prices(
            market_tickers, quote_currency=quote_currency
        ) if market_tickers else {}
        for ticker in market_tickers:
            position = positions[ticker]
            shares = _decimal(position.get("shares"))
            if ticker in prices:
                total += shares * _decimal(prices[ticker])
            else:
                total += _decimal(position.get("total_cost"))
        return ((quote_currency, total),)

    @staticmethod
    def _transaction_dto(row: dict) -> TransactionDTO:
        return TransactionDTO(
            date=row.get("date", ""),
            account_name=row.get("account_name", ""),
            currency=row.get("currency", "CNY") or "CNY",
            category=row.get("category", ""),
            amount=_decimal(row.get("amount")),
            description=row.get("description", ""),
            counterparty=row.get("counterparty", ""),
            transfer_account=row.get("transfer_account", ""),
        )
