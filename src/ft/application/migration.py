"""Storage-independent local-to-database migration orchestration."""
from __future__ import annotations

from decimal import Decimal, InvalidOperation

from ft.domain.migration import (
    MigrationFinding,
    MigrationInspection,
    MigrationVerificationReport,
)


def _inspection(data) -> MigrationInspection:
    return MigrationInspection(
        source_digest=data.source_digest,
        account_count=len(data.accounts),
        cash_transaction_count=len(data.cashflows),
        investment_event_count=len(data.investments),
        raw_file_count=len(data.raw_files),
    )


def _decimal_text(value) -> str:
    try:
        decimal = Decimal(str(value))
    except (InvalidOperation, ValueError, TypeError):
        return str(value)
    if not decimal.is_finite():
        return str(value)
    if decimal == 0:
        return "0"
    return format(decimal.normalize(), "f")


def _rows(rows, *, amount_field=None):
    normalized = []
    for source in rows:
        row = {key: value for key, value in source.items() if not key.startswith("_")}
        if amount_field and amount_field in row:
            row[amount_field] = _decimal_text(row[amount_field])
        normalized.append(tuple(sorted(row.items())))
    return tuple(sorted(normalized))


def _accounts(data):
    return tuple(sorted(
        (item.get("name", ""), item.get("type", ""), item.get("currency", ""), item.get("active", True))
        for item in data.accounts
    ))


def _balances(data):
    result = []
    accounts = data.snapshot.get("accounts", {})
    for account_type in ("cash", "loan", "lend"):
        for name, currencies in accounts.get(account_type, {}).items():
            if isinstance(currencies, dict):
                for currency, amount in currencies.items():
                    result.append((account_type, name, currency, _decimal_text(amount)))
    return tuple(sorted(result))


def _cashflow_summary(data):
    totals = {}
    for row in data.cashflows:
        category = row.get("category", "")
        if category not in {"income", "expense"}:
            continue
        key = (category, row.get("currency", "CNY") or "CNY")
        totals[key] = totals.get(key, Decimal("0")) + Decimal(str(row.get("amount", 0)))
    return tuple(sorted((kind, currency, _decimal_text(amount)) for (kind, currency), amount in totals.items()))


def _portfolio(data):
    security = data.snapshot.get("accounts", {}).get("security", {})
    result = []
    for account, payload in security.items():
        for ticker, position in payload.get("positions", {}).items():
            result.append((
                account, ticker,
                _decimal_text(position.get("shares", 0)),
                _decimal_text(position.get("total_cost", 0)),
                position.get("cost_currency", payload.get("currency", "")),
            ))
    return tuple(sorted(result))


def _net_worth_projection(data):
    totals = {}
    for _, _, currency, amount in _balances(data):
        totals[currency] = totals.get(currency, Decimal("0")) + Decimal(amount)
    for _, _, _, total_cost, currency in _portfolio(data):
        totals[currency] = totals.get(currency, Decimal("0")) + Decimal(total_cost)
    return tuple(sorted((currency, _decimal_text(amount)) for currency, amount in totals.items()))


def _snapshot(data):
    def normalize(value):
        if isinstance(value, dict):
            return tuple(sorted((key, normalize(item)) for key, item in value.items()))
        if isinstance(value, (list, tuple)):
            return tuple(normalize(item) for item in value)
        if isinstance(value, (int, float, Decimal)):
            return _decimal_text(value)
        return value
    return normalize(data.snapshot)


class MigrationService:
    def __init__(self, source, target):
        self._source = source
        self._target = target

    def inspect(self):
        return _inspection(self._source.load())

    def import_ledger(self):
        return self._target.import_ledger(self._source.load())

    def verify(self) -> MigrationVerificationReport:
        expected = self._source.load()
        actual = self._target.load()
        comparators = {
            "accounts": _accounts,
            "cash_transactions": lambda data: _rows(data.cashflows, amount_field="amount"),
            "investment_events": lambda data: _rows(data.investments),
            "snapshot": _snapshot,
            "account_balances": _balances,
            "cashflow_summary": _cashflow_summary,
            "portfolio": _portfolio,
            "net_worth_projection": _net_worth_projection,
        }
        checks = {}
        findings = []
        for component, projector in comparators.items():
            expected_value = projector(expected)
            actual_value = projector(actual)
            checks[component] = expected_value == actual_value
            if expected_value != actual_value:
                findings.append(MigrationFinding(component, expected_value, actual_value))
        return MigrationVerificationReport(all(checks.values()), checks, tuple(findings))

    def export(self, destination):
        data = self._target.load()
        self._target.export(data, destination)
        from ft.adapters.local_migration import LocalMigrationSource
        return _inspection(LocalMigrationSource(destination).load())
