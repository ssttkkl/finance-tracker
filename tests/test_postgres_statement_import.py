from decimal import Decimal
from pathlib import Path

import pytest
from sqlalchemy import func, select

from test_postgres_adapter import _database


class FakeStatementParser:
    def __init__(self, rows):
        self.rows = [dict(row) for row in rows]

    def parse(self, command):
        return [dict(row) for row in self.rows]


def _cash_row(**overrides):
    row = {
        "record_id": "alipay-1",
        "occurred_at": "2026-07-17 09:00:00",
        "amount": "-12.34",
        "currency": "CNY",
        "counterparty": "Coffee",
        "counterparty_account": "",
        "note": "Coffee",
        "category": "expense",
        "account_name": "Cash",
        "source": "Alipay",
        "bill_source": "alipay",
        "source_payload": {"交易时间": "2026-07-17 09:00:00", "交易对方": "Coffee"},
    }
    row.update(overrides)
    return row


def _command(path, **overrides):
    from ft.domain.imports import StatementImportCommand

    values = {
        "source_path": str(path), "source": "alipay", "currency": "CNY",
    }
    values.update(overrides)
    # Legacy tests used account=; map to pre-routed rows via FakeStatementParser.
    values.pop("account", None)
    return StatementImportCommand(**values)


def _service(rows):
    from ft.application.statement_import import StatementImportService

    sessions, unit_of_work = _database()
    with unit_of_work(sessions, "workspace-a") as uow:
        uow.accounts.add_raw({"name": "Cash", "type": "cash", "currency": "CNY"})
        uow.commit()
    return sessions, unit_of_work, StatementImportService(
        unit_of_work(sessions, "workspace-a"), FakeStatementParser(rows)
    )


def test_cash_statement_import_persists_provenance_and_projection(tmp_path):
    from ft.adapters.relational.models import CashTransactionModel

    source = tmp_path / "alipay.csv"
    source.write_bytes(b"statement bytes")
    sessions, unit_of_work, service = _service([_cash_row()])

    result = service.import_statement(_command(source))

    assert result.ok is True
    assert result.count == 1
    with sessions() as session:
        fact = session.scalar(select(CashTransactionModel))
        assert fact.source_type == "alipay"
        assert fact.record_id == "alipay-1"
        assert fact.source_payload is not None
        assert fact.amount == Decimal("-12.34")
    with unit_of_work(sessions, "workspace-a") as uow:
        assert uow.snapshot.load()["accounts"]["cash"]["Cash"]["CNY"] == "-12.34"
        uow.commit()


def test_statement_import_persists_record_type(tmp_path):
    from ft.adapters.relational.models import CashTransactionModel

    source = tmp_path / "alipay.csv"
    source.write_bytes(b"statement bytes")
    sessions, _unit_of_work, service = _service([_cash_row(
        record_id="repay-1",
        record_type="repayment",
        txn_type="信用借还",
    )])

    result = service.import_statement(_command(source))

    assert result.count == 1
    with sessions() as session:
        fact = session.scalar(select(CashTransactionModel))
        assert fact.record_type == "repayment"


def test_icbc_import_uses_parsed_bill_source_and_refund_fields(tmp_path):
    from ft.adapters.relational.models import CashTransactionModel
    from ft.application.statement_import import StatementImportService

    source = tmp_path / "icbc.pdf"
    source.write_bytes(b"icbc statement")
    rows = [_cash_row(
        record_id="icbc-credit-refund-1",
        amount="272.00",
        account_name="Cash",
        source="美团支付",
        bill_source="icbc_credit",
        source_payload={"原始文本单元": ["退货", "美团支付-美团App山葵村烤肉"]},
        summary="退货",
        refund_signal="icbc_credit_return",
        _raw_cp="美团支付-美团App山葵村烤肉",
    )]
    sessions, unit_of_work, service = _service(rows)

    result = service.import_statement(_command(source, source="icbc"))

    assert result.ok is True
    with sessions() as session:
        fact = session.scalar(select(CashTransactionModel))
        assert fact.source_type == "icbc_credit"
        assert fact.source_payload == {
            "原始文本单元": ["退货", "美团支付-美团App山葵村烤肉"],
        }


def test_statement_import_is_idempotent_by_source_digest(tmp_path):
    from ft.adapters.relational.models import CashTransactionModel

    source = tmp_path / "alipay.csv"
    source.write_bytes(b"same bytes")
    sessions, _unit_of_work, service = _service([_cash_row()])

    first = service.import_statement(_command(source))
    second = service.import_statement(_command(source))

    assert first.count == 1
    assert second.count == 0
    assert second.details["duplicate"] is True
    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 1


def test_duplicate_digest_is_idempotent_without_account_override(tmp_path):
    source = tmp_path / "alipay.csv"
    source.write_bytes(b"same bytes")
    sessions, unit_of_work, service = _service([_cash_row()])
    with unit_of_work(sessions, "workspace-a") as uow:
        uow.accounts.add_raw({"name": "Other", "type": "cash", "currency": "CNY"})
        uow.commit()

    first = service.import_statement(_command(source))
    second = service.import_statement(_command(source))
    assert first.count == 1
    assert second.count == 0
    assert second.details["duplicate"] is True


def test_overlap_only_batch_preserves_existing_fact_account(tmp_path):
    from ft.application.statement_import import StatementImportService

    first_source = tmp_path / "first.csv"
    overlap_source = tmp_path / "overlap.csv"
    first_source.write_bytes(b"first")
    overlap_source.write_bytes(b"overlap")
    sessions, unit_of_work, first_service = _service([_cash_row()])
    with unit_of_work(sessions, "workspace-a") as uow:
        uow.accounts.add_raw({"name": "Other", "type": "cash", "currency": "CNY"})
        uow.commit()
    # 同一数据源记录 ID 不能在后续导入中改归其他账户。
    overlap_service = StatementImportService(
        unit_of_work(sessions, "workspace-a"),
        FakeStatementParser([_cash_row(account_name="Other")]),
    )

    assert first_service.import_statement(_command(first_source)).count == 1
    with pytest.raises(ValueError, match="已导入其他账户"):
        overlap_service.import_statement(_command(overlap_source))


def test_same_statement_duplicate_provider_id_projects_once(tmp_path):
    from ft.adapters.relational.models import CashTransactionModel

    source = tmp_path / "duplicate-provider-id.csv"
    source.write_bytes(b"duplicate provider id")
    first = _cash_row(amount="-12.34")
    duplicate = _cash_row(amount="-99.00")
    sessions, unit_of_work, service = _service([first, duplicate])

    result = service.import_statement(_command(source))

    assert result.count == 1
    with sessions() as session:
        facts = list(session.scalars(select(CashTransactionModel)))
        assert len(facts) == 1
        assert facts[0].amount == Decimal("-12.34")
    with unit_of_work(sessions, "workspace-a") as uow:
        assert uow.snapshot.load()["accounts"]["cash"]["Cash"]["CNY"] == "-12.34"
        uow.commit()


def test_overlapping_digest_rejects_existing_record_from_a_different_account(tmp_path):
    first_source = tmp_path / "first.csv"
    second_source = tmp_path / "second.csv"
    first_source.write_bytes(b"first")
    second_source.write_bytes(b"different")
    sessions, unit_of_work, service = _service([_cash_row()])
    with unit_of_work(sessions, "workspace-a") as uow:
        uow.accounts.add_raw({"name": "Other", "type": "cash", "currency": "CNY"})
        uow.commit()

    service.import_statement(_command(first_source))

    other_service = __import__(
        "ft.application.statement_import", fromlist=["StatementImportService"]
    ).StatementImportService(
        unit_of_work(sessions, "workspace-a"),
        FakeStatementParser([_cash_row(account_name="Other")]),
    )
    with pytest.raises(ValueError, match="已导入其他账户"):
        other_service.import_statement(_command(second_source))


def test_fallback_identity_is_stable_when_preceding_rows_change(tmp_path):
    from ft.adapters.relational.models import CashTransactionModel
    from ft.application.statement_import import StatementImportService

    first_source = tmp_path / "first.csv"
    second_source = tmp_path / "second.csv"
    first_source.write_bytes(b"first")
    second_source.write_bytes(b"broader")
    existing = _cash_row(record_id="", note="existing")
    preceding = _cash_row(record_id="", note="preceding", amount="-1")
    sessions, unit_of_work, first_service = _service([existing])
    second_service = StatementImportService(
        unit_of_work(sessions, "workspace-a"), FakeStatementParser([preceding, existing])
    )

    assert first_service.import_statement(_command(first_source)).count == 1
    assert second_service.import_statement(_command(second_source)).count == 1

    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 2


def test_cash_statement_currency_creates_another_pocket_on_selected_account(tmp_path):
    from ft.adapters.relational.models import CashTransactionModel

    source = tmp_path / "statement.csv"
    source.write_bytes(b"currency mismatch")
    # One account may hold both CNY and USD; the statement currency selects the balance.
    sessions, _unit_of_work, service = _service([_cash_row(currency="USD")])

    result = service.import_statement(_command(source))
    assert result.ok is True
    assert result.count == 1

    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 1


def test_overlapping_statement_reuses_provider_record_without_duplicate_fact(tmp_path):
    from ft.adapters.relational.models import CashTransactionModel

    first_source = tmp_path / "first.csv"
    second_source = tmp_path / "second.csv"
    first_source.write_bytes(b"first statement")
    second_source.write_bytes(b"different overlapping statement")
    sessions, unit_of_work, service = _service([_cash_row()])

    first = service.import_statement(_command(first_source))
    second = service.import_statement(_command(second_source))

    assert first.count == 1
    assert second.count == 0
    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 1
    with unit_of_work(sessions, "workspace-a") as uow:
        assert uow.snapshot.load()["accounts"]["cash"]["Cash"]["CNY"] == "-12.34"
        uow.commit()


def test_statement_digest_and_parser_use_same_immutable_capture(tmp_path):
    from ft.application.statement_import import StatementImportService

    source = tmp_path / "statement.csv"
    source.write_bytes(b"captured bytes")
    sessions, unit_of_work = _database()
    with unit_of_work(sessions, "workspace-a") as uow:
        uow.accounts.add_raw({"name": "Cash", "type": "cash", "currency": "CNY"})
        uow.commit()

    seen = {}

    class ReplacingParser:
        def parse(self, command):
            captured = Path(command.source_path)
            seen["captured"] = captured
            source.write_bytes(b"replacement bytes")
            assert captured != source
            assert captured.read_bytes() == b"captured bytes"
            return [_cash_row()]

    service = StatementImportService(
        unit_of_work(sessions, "workspace-a"), ReplacingParser()
    )
    result = service.import_statement(_command(source))

    assert result.count == 1
    assert not seen["captured"].exists()
    with sessions() as session:
        from ft.adapters.relational.models import CashTransactionModel
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 1


def test_statement_import_rolls_back_raw_and_formal_facts_on_any_invalid_row(tmp_path):
    from ft.adapters.relational.models import CashTransactionModel

    source = tmp_path / "alipay.csv"
    source.write_bytes(b"invalid batch")
    sessions, _unit_of_work, service = _service([
        _cash_row(), _cash_row(record_id="bad", amount="not-a-number"),
    ])

    with pytest.raises(ValueError, match="decimal"):
        service.import_statement(_command(source))

    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 0


def test_statement_import_rejects_decimal_scale_before_any_commit(tmp_path):
    source = tmp_path / "alipay.csv"
    source.write_bytes(b"scale")
    sessions, _unit_of_work, service = _service([
        _cash_row(amount="0.1234567890123456789"),
    ])

    with pytest.raises(ValueError, match="18 decimal places"):
        service.import_statement(_command(source))

    with sessions() as session:
        from ft.adapters.relational.models import CashTransactionModel
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 0


def test_dfzq_statement_import_writes_investment_event_and_projection(tmp_path):
    from ft.adapters.relational.models import InvestmentEventModel
    from ft.application.statement_import import StatementImportService

    source = tmp_path / "dfzq.pdf"
    source.write_bytes(b"pdf")
    sessions, unit_of_work = _database()
    with unit_of_work(sessions, "workspace-a") as uow:
        uow.accounts.add_raw({
            "name": "IBKR", "type": "security", "currency": "USD",
            "base_currencies": ["USD"],
        })
        uow.commit()
    parser = FakeStatementParser([{
        "occurred_at": "2026-07-17 09:00:00", "record_type": "funding",
        "record_subtype": "external",
        "from_ticker": "", "to_ticker": "usd", "from_amount": "0",
        "to_amount": "100", "price": "1", "commission": "0",
        "commission_asset": "", "currency": "USD", "account_name": "IBKR",
        "note": "seed",
    }])
    service = StatementImportService(unit_of_work(sessions, "workspace-a"), parser)

    assert service.import_statement(_command(
        source, account="IBKR", currency="USD"
    )).count == 1

    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(InvestmentEventModel)) == 1
    with unit_of_work(sessions, "workspace-a") as uow:
        position = uow.snapshot.load()["accounts"]["security"]["IBKR"]["positions"]["usd"]
        assert position["shares"] == "100"
        uow.commit()


@pytest.mark.parametrize("source", [
    "alipay", "wechat", "icbc", "icbc-debit", "ccb-debit", "dfzq",
])
def test_statement_parser_dispatches_every_contracted_provider(monkeypatch, tmp_path, source):
    from ft.adapters.statement_import import StatementParser

    path = tmp_path / "statement"
    path.write_bytes(b"source")
    calls = []
    monkeypatch.setattr(
        "ft.adapters.statement_import._parse_cash_statement",
        lambda command: calls.append(("cash", command.source)) or [_cash_row()],
    )
    monkeypatch.setattr(
        "ft.adapters.statement_import._parse_dfzq_statement",
        lambda command: calls.append(("dfzq", command.source)) or [{"action": "deposit"}],
    )

    rows = StatementParser().parse(_command(path, source=source))

    assert rows
    assert calls == [("dfzq" if source == "dfzq" else "cash", source)]


def test_icbc_parser_uses_private_temp_files_and_never_exposes_password_in_argv(
    monkeypatch, tmp_path,
):
    from ft.convert import _read_icbc_raw

    source = tmp_path / "statement.pdf"
    source.write_bytes(b"encrypted")
    calls = []

    def fake_decrypt(input_path, output_path, password, *, timeout=30):
        calls.append((str(input_path), str(output_path), password))
        Path(output_path).write_bytes(b"decrypted")

    monkeypatch.setattr("ft.importers.pdf_tools.decrypt_pdf", fake_decrypt)
    monkeypatch.setattr("ft.importers.pdf_tools.extract_pdf_text", lambda _path: "信用卡\n")

    rows, bill_type, tracking = _read_icbc_raw(str(source), "top-secret")

    assert rows == []
    assert bill_type == "icbc_credit"
    assert tracking == []
    assert calls[0][0] == str(source)
    assert Path(calls[0][1]).parent != tmp_path
    assert list(tmp_path.iterdir()) == [source]


def test_pdf_decryption_uses_mode_0600_password_file_and_cleans_it(monkeypatch, tmp_path):
    from ft.importers.pdf_tools import decrypt_pdf

    source = tmp_path / "source.pdf"
    output = tmp_path / "output.pdf"
    source.write_bytes(b"pdf")
    seen = {}

    def fake_run(argv, **_kwargs):
        assert "top-secret" not in " ".join(argv)
        password_path = Path(next(arg.split("=", 1)[1] for arg in argv if arg.startswith("--password-file=")))
        seen["path"] = password_path
        seen["mode"] = password_path.stat().st_mode & 0o777
        seen["value"] = password_path.read_text(encoding="utf-8")
        output.write_bytes(b"decrypted")
        return type("Result", (), {"returncode": 0})()

    monkeypatch.setattr("ft.importers.pdf_tools.subprocess.run", fake_run)

    decrypt_pdf(source, output, "top-secret")

    assert seen["mode"] == 0o600
    assert seen["value"] == "top-secret\n"
    assert not seen["path"].exists()
