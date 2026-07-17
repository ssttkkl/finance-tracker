"""Local connector, credential, and investment-event adapters."""
import csv
from pathlib import Path

import yaml

from ft.adapters.local_investment import LocalInvestmentCommandRepository
from ft.adapters.local_legacy import local_ledger_globals
from ft.domain.sync import external_event_id, row_identity
from ft.schema import CSV_FIELDS


class LocalSecretStore:
    def __init__(self, ledger_root):
        self._path = Path(ledger_root) / "credentials.yaml"

    def get_secret(self, provider, account=None):
        if not self._path.exists():
            if provider == "polymarket":
                raise ValueError(
                    "必须指定 wallet 或 proxy_wallet，或在 credentials.yaml 的 polymarket 段配置"
                )
            raise ValueError(f"未找到凭证文件 {self._path}")
        data = yaml.safe_load(self._path.read_text(encoding="utf-8")) or {}
        section = data.get(provider)
        if not isinstance(section, dict) or not section:
            raise ValueError(f"凭证文件 {self._path} 缺少 '{provider}' 段")
        if provider == "polymarket":
            if not section.get("wallet") and not section.get("proxy_wallet"):
                raise ValueError("Polymarket 凭证需要 wallet 或 proxy_wallet")
        else:
            for field in ("api_key", "api_secret"):
                if not section.get(field):
                    raise ValueError(f"凭证 '{provider}' 缺少必填字段 '{field}'")
        return dict(section)


class CcxtConnector:
    def __init__(self, ledger_root, provider):
        self._root = Path(ledger_root)
        self._provider = provider

    def fetch(self, command, *, credentials, mapping):
        from ft import exchange_sync

        with local_ledger_globals(self._root):
            client = exchange_sync.build_client(self._provider, credentials)
            since_ms = exchange_sync._since_to_ms(command.since)
            trades = exchange_sync.fetch_trades(
                client, since=since_ms, symbols=command.symbols or None
            )
            rows = []
            for trade in trades:
                rows.extend(exchange_sync.trade_to_rows(
                    trade, command.account, self._provider
                ))
            if self._provider == "kraken":
                for entry in exchange_sync.fetch_ledger(client, since=since_ms):
                    rows.extend(exchange_sync.ledger_to_rows(
                        entry, command.account, self._provider
                    ))
        return sorted(rows, key=lambda row: row.get("date", ""))


class PolymarketConnector:
    def __init__(self, ledger_root):
        self._root = Path(ledger_root)

    def _proxy_wallet(self, command, credentials):
        from ft.polymarket_sync import resolve_proxy_wallet

        proxy_wallet = command.proxy_wallet or credentials.get("proxy_wallet")
        wallet = command.wallet or credentials.get("wallet")
        if proxy_wallet:
            return proxy_wallet.lower()
        if not wallet:
            raise ValueError("必须指定 wallet 或 proxy_wallet")
        return resolve_proxy_wallet(wallet)

    def fetch(self, command, *, credentials, mapping):
        from ft.polymarket_sync import activities_to_stock_rows, fetch_activity

        proxy_wallet = self._proxy_wallet(command, credentials)
        activities = fetch_activity(
            proxy_wallet, limit=command.limit, max_pages=command.max_pages
        )
        return activities_to_stock_rows(activities, account_name=command.account)

    def enrich(self, command, new_rows, *, credentials, mapping):
        from ft.polymarket_sync import (
            _project_pm_positions,
            _settlement_rows_for_open_positions,
        )

        with local_ledger_globals(self._root):
            projected = _project_pm_positions(command.account, new_rows)
            return _settlement_rows_for_open_positions(
                account_name=command.account,
                positions=projected,
                settled_tokens=set(),
            )


class LocalConnectorRegistry:
    def __init__(self, ledger_root):
        root = Path(ledger_root)
        self._connectors = {
            provider: CcxtConnector(root, provider)
            for provider in ("kraken", "okx", "binance", "coinbase", "bybit")
        }
        self._connectors["polymarket"] = PolymarketConnector(root)

    def get_connector(self, provider):
        try:
            return self._connectors[provider]
        except KeyError as exc:
            raise ValueError(f"未知 connector: {provider}") from exc


class LocalInvestmentEventRepository:
    def __init__(self, ledger_root):
        self._root = Path(ledger_root)
        self._commands = LocalInvestmentCommandRepository(self._root)

    def validate_destination(self, provider, account):
        path = self._root / "accounts.yaml"
        rows = (yaml.safe_load(path.read_text(encoding="utf-8")) or {}).get("accounts", []) if path.exists() else []
        matches = [row for row in rows if row.get("name") == account]
        if not matches:
            raise ValueError(f"未知账户 '{account}'")
        allowed = {"security"} if provider == "polymarket" else {"crypto"}
        if matches[0].get("type") not in allowed:
            raise ValueError(f"账户 '{account}' 类型不适用于 {provider} 同步")

    def existing_identities(self, provider, account):
        external_ids = set()
        exact_rows = set()
        directory = self._root / "records" / "security"
        if not directory.exists():
            return external_ids, exact_rows
        for path in sorted(directory.glob("*.csv")):
            with path.open(encoding="utf-8") as handle:
                reader = csv.DictReader(handle)
                if list(reader.fieldnames or ()) != list(CSV_FIELDS):
                    raise ValueError(f"invalid security CSV header: {path}")
                for row in reader:
                    if row.get("account_name") != account:
                        continue
                    event_id = external_event_id(row)
                    if event_id:
                        external_ids.add(event_id)
                    exact_rows.add(row_identity(row))
        return external_ids, exact_rows

    def append_events(self, rows):
        return self._commands.append_investments(rows)
