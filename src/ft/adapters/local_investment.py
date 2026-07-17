"""Focused local compatibility adapters for investment use cases."""
from contextlib import redirect_stdout
import csv
from io import StringIO
from pathlib import Path
import tempfile

import yaml

from ft.adapters.local_legacy import local_ledger_globals
from ft.domain.application import OperationResult
from ft.schema import CSV_FIELDS, DEFAULT_SNAPSHOT


def _number(value):
    return float(value) if value is not None else None


class LocalInvestmentCommandRepository:
    def __init__(self, ledger_root):
        self._root = Path(ledger_root)

    def execute(self, command):
        from ft import stock

        output = StringIO()
        with local_ledger_globals(self._root), redirect_stdout(output):
            if command.action == "buy":
                stock.do_buy(command.ticker, _number(command.quantity), _number(command.price),
                             _number(command.commission), command.currency, command.account,
                             command.note, command.date)
            elif command.action == "sell":
                stock.do_sell(command.ticker, _number(command.quantity), _number(command.price),
                              _number(command.commission), command.currency, command.account,
                              command.note, command.date)
            elif command.action == "swap":
                stock.do_swap(command.account, command.from_ticker, _number(command.quantity),
                              command.to_ticker, _number(command.to_quantity), command.currency,
                              command.note, date=command.date)
            elif command.action == "deposit":
                stock.do_deposit(_number(command.amount), command.currency, command.account,
                                 command.note, command.date)
            elif command.action == "withdraw":
                stock.do_withdraw(_number(command.amount), command.currency, command.account,
                                  command.note, command.date)
            elif command.action == "dividend":
                stock.do_dividend(command.ticker, _number(command.amount), command.currency,
                                  command.account, command.note, command.date)
            elif command.action == "checkin_ticker":
                stock.do_checkin_ticker(command.ticker, _number(command.quantity),
                                        _number(command.price), command.currency,
                                        command.account, command.note, command.date)
            elif command.action == "checkin_cash":
                stock.do_checkin_cash(_number(command.amount), command.account,
                                      command.currency, command.note, command.date)
            else:
                raise ValueError(f"unsupported investment action: {command.action}")
        return OperationResult(ok=True, message=output.getvalue().strip())

    def append_investments(self, rows):
        from ft.stock import do_append

        with tempfile.NamedTemporaryFile("w", suffix=".csv", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=CSV_FIELDS, extrasaction="ignore")
            writer.writeheader()
            writer.writerows(rows)
            handle.flush()
            output = StringIO()
            with local_ledger_globals(self._root), redirect_stdout(output):
                ok = do_append(handle.name)
        if not ok:
            raise ValueError(output.getvalue().strip() or "investment import failed")
        return len(rows)


class LocalInvestmentImporter:
    def __init__(self, ledger_root):
        self._root = Path(ledger_root)

    def convert(self, command):
        from ft.stock import do_convert

        with tempfile.NamedTemporaryFile(suffix=".csv") as output_file:
            with local_ledger_globals(self._root), redirect_stdout(StringIO()):
                do_convert(
                    command.source_path, command.source, output_file.name,
                    password=command.password, account=command.account,
                    currency=command.currency,
                )
            output_file.seek(0)
            text = output_file.read().decode("utf-8")
        if not text.strip():
            return []
        return list(csv.DictReader(text.splitlines()))

    def read_converted(self, source):
        path = Path(source)
        if not path.exists():
            raise FileNotFoundError(f"❌ 文件不存在: {source}")
        with path.open(encoding="utf-8") as handle:
            return list(csv.DictReader(handle))


class LocalPortfolioRepository:
    def __init__(self, ledger_root):
        self._root = Path(ledger_root)

    def load_portfolio(self):
        accounts_path = self._root / "accounts.yaml"
        account_rows = []
        if accounts_path.exists():
            account_rows = (yaml.safe_load(accounts_path.read_text(encoding="utf-8")) or {}).get("accounts", [])
        snapshot_path = self._root / "snapshot.yaml"
        snapshot = DEFAULT_SNAPSHOT
        if snapshot_path.exists():
            snapshot = yaml.safe_load(snapshot_path.read_text(encoding="utf-8")) or DEFAULT_SNAPSHOT
        base_currencies = {
            row.get("name", ""): tuple(str(item).upper() for item in row.get("base_currencies", ()))
            for row in account_rows if row.get("type") in {"security", "crypto"}
        }
        configured = sorted({currency for values in base_currencies.values() for currency in values})
        return {
            "accounts": snapshot.get("accounts", {}).get("security", {}),
            "base_currencies": base_currencies,
            "configured_currencies": tuple(configured),
        }
