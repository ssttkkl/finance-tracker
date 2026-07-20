"""Multi-account import via mapping; no CLI --account."""
from decimal import Decimal
from pathlib import Path

import pytest
import yaml
from sqlalchemy import func, select


def _write_mapping(path: Path, rules: list[dict], default: str = "error") -> Path:
    path.write_text(
        yaml.safe_dump({"rules": rules, "default": default}, allow_unicode=True),
        encoding="utf-8",
    )
    return path


def _alipay_csv(path: Path, rows: list[list[str]]) -> Path:
    import csv

    header = ["交易时间", "交易分类", "交易对方", "商品说明", "收/支", "金额", "收/付款方式", "交易状态", "交易订单号"]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(header)
        for row in rows:
            writer.writerow(row)
    return path


@pytest.fixture
def mapping_path(tmp_path, monkeypatch):
    from ft import mapping as mapping_mod

    path = tmp_path / "mapping.yaml"
    _write_mapping(
        path,
        [
            {
                "source": "alipay",
                "match": "账户余额",
                "account": "支付宝余额",
                "currency": "CNY",
            },
            {
                "source": "alipay",
                "match": "工商银行信用卡(1200)*",
                "account": "工行信用卡(1200)",
                "currency": "CNY",
            },
            {
                "source": "alipay",
                "match": "花呗*",
                "account": "花呗",
                "currency": "CNY",
            },
        ],
        default="error",
    )
    monkeypatch.setattr(mapping_mod, "MAPPING_PATH", path)
    return path


def _seed_accounts(unit_of_work, sessions):
    with unit_of_work(sessions, "workspace-a") as uow:
        for name, type_ in (
            ("支付宝余额", "cash"),
            ("工行信用卡(1200)", "loan"),
            ("花呗", "loan"),
        ):
            uow.accounts.add_raw({"name": name, "type": type_, "currency": "CNY"})
        uow.commit()


def test_multi_pay_alipay_import_routes_per_row(tmp_path, mapping_path):
    from ft.adapters.relational.models import CashTransactionModel
    from ft.adapters.statement_import import StatementParser
    from ft.application.statement_import import StatementImportService
    from ft.domain.imports import StatementImportCommand
    from test_postgres_adapter import _database

    sessions, unit_of_work = _database()
    _seed_accounts(unit_of_work, sessions)
    source = _alipay_csv(
        tmp_path / "alipay.csv",
        [
            ["2026-01-01 10:00:00", "消费", "店A", "咖啡", "支出", "12.00", "账户余额", "交易成功", "OID1"],
            ["2026-01-01 11:00:00", "消费", "店B", "午餐", "支出", "30.00", "工商银行信用卡(1200)", "交易成功", "OID2"],
            ["2026-01-01 12:00:00", "消费", "店C", "零食", "支出", "8.50", "花呗", "交易成功", "OID3"],
        ],
    )
    service = StatementImportService(
        unit_of_work(sessions, "workspace-a"), StatementParser()
    )
    result = service.import_statement(
        StatementImportCommand(source_path=str(source), source="alipay")
    )
    assert result.ok is True
    assert result.count == 3
    with sessions() as session:
        facts = list(session.scalars(select(CashTransactionModel)))
        assert len(facts) == 3
    with unit_of_work(sessions, "workspace-a") as uow:
        snap = uow.snapshot.load()
        assert snap["accounts"]["cash"]["支付宝余额"]["CNY"] == "-12.00"
        assert snap["accounts"]["loan"]["工行信用卡(1200)"]["CNY"] == "-30.00"
        assert snap["accounts"]["loan"]["花呗"]["CNY"] == "-8.50"
        uow.commit()


def test_import_cli_rejects_account_flag(tmp_path, mapping_path, monkeypatch):
    from ft import cli
    from ft.domain.application import OperationResult

    calls = []

    class Importer:
        def import_statement(self, command):
            calls.append(command)
            return OperationResult(ok=True, count=0, message="imported", details={})

    monkeypatch.setattr(
        cli,
        "_runtime_services",
        lambda: type("Bundle", (), {"statement_import": Importer()})(),
    )
    source = tmp_path / "x.csv"
    source.write_text("x", encoding="utf-8")
    with pytest.raises(SystemExit):
        cli.main(
            [
                "import",
                str(source),
                "--source",
                "alipay",
                "--account",
                "支付宝余额",
            ]
        )
    assert calls == []


def test_import_idempotent_by_digest(tmp_path, mapping_path):
    from ft.adapters.relational.models import CashTransactionModel
    from ft.adapters.statement_import import StatementParser
    from ft.application.statement_import import StatementImportService
    from ft.domain.imports import StatementImportCommand
    from test_postgres_adapter import _database

    sessions, unit_of_work = _database()
    _seed_accounts(unit_of_work, sessions)
    source = _alipay_csv(
        tmp_path / "alipay.csv",
        [
            ["2026-01-01 10:00:00", "消费", "店A", "咖啡", "支出", "12.00", "账户余额", "交易成功", "OID1"],
        ],
    )
    service = StatementImportService(
        unit_of_work(sessions, "workspace-a"), StatementParser()
    )
    first = service.import_statement(
        StatementImportCommand(source_path=str(source), source="alipay")
    )
    second = service.import_statement(
        StatementImportCommand(source_path=str(source), source="alipay")
    )
    assert first.count == 1
    assert second.count == 0
    assert second.details["duplicate"] is True
    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 1


def test_unmatched_payment_method_rolls_back(tmp_path, mapping_path):
    from ft.adapters.relational.models import CashTransactionModel, ImportBatchModel
    from ft.adapters.statement_import import StatementParser
    from ft.application.statement_import import StatementImportService
    from ft.domain.imports import StatementImportCommand
    from test_postgres_adapter import _database

    sessions, unit_of_work = _database()
    _seed_accounts(unit_of_work, sessions)
    source = _alipay_csv(
        tmp_path / "alipay.csv",
        [
            ["2026-01-01 10:00:00", "消费", "店A", "咖啡", "支出", "12.00", "未知支付方式XYZ", "交易成功", "OID1"],
        ],
    )
    service = StatementImportService(
        unit_of_work(sessions, "workspace-a"), StatementParser()
    )
    with pytest.raises(ValueError, match="mapping|未匹配|payment_method"):
        service.import_statement(
            StatementImportCommand(source_path=str(source), source="alipay")
        )
    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 0
        assert session.scalar(select(func.count()).select_from(ImportBatchModel)) == 0


def test_ccb_card_number_routes_via_mapping(tmp_path, monkeypatch):
    from ft import mapping as mapping_mod
    from ft.convert import _build_output_row

    path = tmp_path / "mapping.yaml"
    _write_mapping(
        path,
        [
            {
                "source": "ccb_debit_2820",
                "match": "*",
                "account": "建行储蓄卡(2820)",
                "currency": "CNY",
            },
            {
                "source": "ccb_debit_0523",
                "match": "*",
                "account": "建行储蓄卡(0523)",
                "currency": "CNY",
            },
        ],
    )
    monkeypatch.setattr(mapping_mod, "MAPPING_PATH", path)
    rules, default = mapping_mod.load_rules(path)
    row_2820 = _build_output_row(
        {
            "date": "2026-01-01 10:00:00",
            "amount": -10,
            "currency": "CNY",
            "counterparty": "Shop",
            "description": "x",
            "category": "expense",
            "card_number": "2820",
            "payment_method": "建行储蓄卡(2820)",
        },
        bill_type="ccb_debit",
        rules=rules,
        default_action=default,
    )
    row_0523 = _build_output_row(
        {
            "date": "2026-01-01 11:00:00",
            "amount": -20,
            "currency": "CNY",
            "counterparty": "Shop",
            "description": "y",
            "category": "expense",
            "card_number": "0523",
            "payment_method": "建行储蓄卡(0523)",
        },
        bill_type="ccb_debit",
        rules=rules,
        default_action=default,
    )
    assert row_2820["account_name"] == "建行储蓄卡(2820)"
    assert row_0523["account_name"] == "建行储蓄卡(0523)"


def test_convert_and_import_account_distribution_match(tmp_path, mapping_path):
    from collections import Counter

    from ft.adapters.relational.models import AccountModel, CashTransactionModel
    from ft.adapters.statement_import import StatementParser
    from ft.application.statement_import import StatementImportService
    from ft.domain.imports import StatementImportCommand
    from test_postgres_adapter import _database

    sessions, unit_of_work = _database()
    _seed_accounts(unit_of_work, sessions)
    source = _alipay_csv(
        tmp_path / "alipay.csv",
        [
            ["2026-01-01 10:00:00", "消费", "店A", "咖啡", "支出", "12.00", "账户余额", "交易成功", "OID1"],
            ["2026-01-01 11:00:00", "消费", "店B", "午餐", "支出", "30.00", "工商银行信用卡(1200)", "交易成功", "OID2"],
        ],
    )
    convert_rows = StatementParser().parse(
        StatementImportCommand(source_path=str(source), source="alipay")
    )
    convert_dist = Counter(row["account_name"] for row in convert_rows)

    service = StatementImportService(
        unit_of_work(sessions, "workspace-a"), StatementParser()
    )
    service.import_statement(
        StatementImportCommand(source_path=str(source), source="alipay")
    )
    with sessions() as session:
        facts = list(session.scalars(select(CashTransactionModel)))
        accounts = {
            account.id: account.name
            for account in session.scalars(select(AccountModel))
        }
        import_dist = Counter(accounts[fact.account_id] for fact in facts)
    assert convert_dist == import_dist
