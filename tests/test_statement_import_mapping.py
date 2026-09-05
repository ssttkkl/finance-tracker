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


def test_statement_parser_keeps_relation_metadata_out_of_source_snapshot(tmp_path, monkeypatch):
    from ft import convert
    from ft.adapters.statement_import import StatementParser
    from ft.domain.imports import StatementImportCommand

    source_row = {
        "date": "2023-10-09 13:51:23",
        "amount": "-9.90",
        "payment_method": "零钱",
        "counterparty": "瑞幸咖啡",
        "category": "expense",
        "record_type": "refund",
        "_source_payload": {
            "交易时间": "2023-10-09 13:51:23",
            "交易对方": "瑞幸咖啡",
            "金额": "-9.90",
        },
        "offset_group": "refund_000001",
        "offset_role": "expense",
        "offset_strength": "strong",
        "offset_rule_hint": "import.wechat.full_status_pay.v1",
    }

    monkeypatch.setattr(
        convert,
        "_prepare_convert_rows",
        lambda *_args: ([dict(source_row)], "wechat", []),
    )
    monkeypatch.setattr(
        convert,
        "_build_output_row",
        lambda row, **_kwargs: {
            "occurred_at": row["date"],
            "amount": row["amount"],
            "record_type": row["record_type"],
            "account_name": "",
        },
    )

    source = tmp_path / "unused.xlsx"
    source.write_bytes(b"fixture")
    rows = StatementParser().parse_source_rows(
        StatementImportCommand(str(source), source="wechat", currency="CNY")
    )

    assert rows[0]["relation_metadata"] == {
        "offset_group": "refund_000001",
        "offset_role": "expense",
        "offset_strength": "strong",
        "offset_rule_hint": "import.wechat.full_status_pay.v1",
    }
    assert rows[0]["source_payload"] == source_row["_source_payload"]
    assert "offset_role" not in rows[0]["source_payload"]


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
        StatementImportCommand(source_path=str(source))
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
        StatementImportCommand(source_path=str(source))
    )
    second = service.import_statement(
        StatementImportCommand(source_path=str(source))
    )
    assert first.count == 1
    assert second.count == 0
    assert second.details["duplicate"] is True
    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 1


def test_unmatched_payment_method_rolls_back(tmp_path, mapping_path):
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
            ["2026-01-01 10:00:00", "消费", "店A", "咖啡", "支出", "12.00", "未知支付方式XYZ", "交易成功", "OID1"],
        ],
    )
    service = StatementImportService(
        unit_of_work(sessions, "workspace-a"), StatementParser()
    )
    with pytest.raises(ValueError, match="mapping|未匹配|payment_method"):
        service.import_statement(
            StatementImportCommand(source_path=str(source))
        )
    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 0
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 0


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
            "occurred_at": "2026-01-01 10:00:00",
            "amount": -10,
            "currency": "CNY",
            "counterparty": "Shop",
            "note": "x",
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
            "occurred_at": "2026-01-01 11:00:00",
            "amount": -20,
            "currency": "CNY",
            "counterparty": "Shop",
            "note": "y",
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


def test_icbc_source_identity_routes_via_mapping_without_channel_fallback():
    from ft.convert import _build_output_row

    rules = [{
        "source": "icbc_credit_622599000000001200",
        "match": "*",
        "account": "工行信用卡",
        "currency": "CNY",
    }]
    row = _build_output_row(
        {
            "occurred_at": "2026-01-01 10:00:00",
            "amount": -10,
            "currency": "CNY",
            "counterparty": "Shop",
            "note": "x",
            "category": "expense",
            "_source_account_identifier": "622599000000001200",
            "payment_method": "快捷支付",
        },
        bill_type="icbc_credit",
        rules=rules,
        default_action="error",
    )

    assert row["account_name"] == "工行信用卡"


def test_icbc_direct_mapping_fails_closed_without_source_identity():
    from ft.convert import _build_output_row

    with pytest.raises(ValueError, match="来源账户身份"):
        _build_output_row(
            {
                "occurred_at": "2026-01-01 10:00:00",
                "amount": -10,
                "currency": "CNY",
                "counterparty": "Shop",
                "note": "x",
                "category": "expense",
                "payment_method": "快捷支付",
            },
            bill_type="icbc_debit",
            rules=[{
                "source": "icbc_debit",
                "match": "*",
                "account": "工行借记卡",
                "currency": "CNY",
            }],
            default_action="error",
        )


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
        StatementImportCommand(source_path=str(source))
    )
    convert_dist = Counter(row["account_name"] for row in convert_rows)

    service = StatementImportService(
        unit_of_work(sessions, "workspace-a"), StatementParser()
    )
    service.import_statement(
        StatementImportCommand(source_path=str(source))
    )
    with sessions() as session:
        facts = list(session.scalars(select(CashTransactionModel)))
        accounts = {
            account.id: account.name
            for account in session.scalars(select(AccountModel))
        }
        import_dist = Counter(accounts[fact.account_id] for fact in facts)
    assert convert_dist == import_dist


def test_single_account_accepts_cny_and_jpy_rows_without_currency_match(tmp_path):
    from ft.adapters.relational.models import CashTransactionModel
    from ft.application.statement_import import StatementImportService
    from ft.domain.imports import StatementImportCommand
    from test_postgres_adapter import _database
    from test_postgres_statement_import import FakeStatementParser, _cash_row

    sessions, unit_of_work = _database()
    with unit_of_work(sessions, "workspace-a") as uow:
        uow.accounts.add_raw({"name": "工行", "type": "cash"})
        uow.commit()

    rows = [
        _cash_row(account_name="工行", currency="CNY", amount="-12.00", record_id="cny-1"),
        _cash_row(account_name="工行", currency="JPY", amount="-500", record_id="jpy-1"),
    ]
    service = StatementImportService(
        unit_of_work(sessions, "workspace-a"), FakeStatementParser(rows)
    )
    source = tmp_path / "mixed.csv"
    source.write_bytes(b"mixed statement")
    result = service.import_statement(
        StatementImportCommand(source_path=str(source))
    )
    assert result.ok is True
    assert result.count == 2
    with sessions() as session:
        facts = list(session.scalars(select(CashTransactionModel)))
        assert sorted(fact.currency for fact in facts) == ["CNY", "JPY"]
        assert len({fact.account_id for fact in facts}) == 1
    with unit_of_work(sessions, "workspace-a") as uow:
        snap = uow.snapshot.load()
        assert snap["accounts"]["cash"]["工行"]["CNY"] == "-12.00"
        assert snap["accounts"]["cash"]["工行"]["JPY"] == "-500"
        uow.commit()


def test_import_missing_account_name_rolls_back(tmp_path):
    from ft.adapters.relational.models import CashTransactionModel
    from ft.application.statement_import import StatementImportService
    from ft.domain.imports import StatementImportCommand
    from test_postgres_adapter import _database
    from test_postgres_statement_import import FakeStatementParser, _cash_row

    sessions, unit_of_work = _database()
    with unit_of_work(sessions, "workspace-a") as uow:
        uow.accounts.add_raw({"name": "工行", "type": "cash"})
        uow.commit()

    row = _cash_row(account_name="不存在", currency="CNY", amount="-1", record_id="missing")
    service = StatementImportService(
        unit_of_work(sessions, "workspace-a"), FakeStatementParser([row])
    )
    source = tmp_path / "missing.csv"
    source.write_bytes(b"missing")
    with pytest.raises(ValueError, match="找不到账户"):
        service.import_statement(
            StatementImportCommand(source_path=str(source))
        )
    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 0
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 0
