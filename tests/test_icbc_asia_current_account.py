"""工银亚洲活期账户 CSV 的解析与双后端导入契约。"""
from __future__ import annotations

import csv
from decimal import Decimal
from pathlib import Path

import pytest
import yaml
from sqlalchemy import select

from conftest import postgres_test_backend_params, reset_postgres_schema


HEADERS = [
    "序號", "交易時間", "", "起息日期", "業務類型", "摘要", "收入金額", "支出金額",
    "餘額", "對方賬號", "對方戶名", "憑證號", "匯率", "備註", "交易場所",
]


def _row(
    sequence: str,
    *,
    income: str = "",
    expense: str = "",
    counterparty_account: str = "",
    counterparty: str = "",
    business_type: str = "轉賬",
    summary: str = "本地轉賬",
    note: str = "測試備註",
    location: str = "",
) -> list[str]:
    return [
        sequence, "2026-07-01", "09:10:11", "2026-07-01", business_type, summary,
        income, expense, "1000.50", counterparty_account, counterparty, "", "", note, location,
    ]


def _write_statement(
    path: Path,
    rows: list[list[str]],
    *,
    account: str = "1234567890125678",
    bank_account: str = "",
    currency: str = "港幣",
    headers: list[str] | None = None,
) -> Path:
    with path.open("w", encoding="utf-16", newline="") as stream:
        writer = csv.writer(stream, delimiter="\t", lineterminator="\r\n")
        writer.writerows([
            ["銀行名稱：", "工銀亞洲"],
            ["銀行賬號：", bank_account],
            ["下掛賬戶：", account],
            ["賬戶類型：", "活期戶"],
            ["幣種：", currency],
            ["日期：", "2026-07-01", "-", "2026-07-31"],
            headers or HEADERS,
            *rows,
        ])
    return path


def _write_mapping(
    path: Path,
    tail: str = "5670",
    account: str = "工银亚洲港币账户",
) -> Path:
    path.write_text(
        yaml.safe_dump(
            {
                "rules": [{
                    "source": f"icbc_asia_current_account_{tail}",
                    "match": "*",
                    "account": account,
                    "currency": "HKD",
                }],
                "default": "error",
            },
            allow_unicode=True,
        ),
        encoding="utf-8",
    )
    return path


def test_parse_preserves_complete_source_row_and_counterparty_account(tmp_path):
    from ft.convert import _read_icbc_asia_current_account_raw

    source_row = _row(
        "1", income="100.50", counterparty_account="99887766", counterparty="测验收款人"
    )
    statement = _write_statement(tmp_path / "currentaccounthistory.csv", [source_row])

    records, bill_type, tracking = _read_icbc_asia_current_account_raw(str(statement))

    assert bill_type == "icbc_asia_current_account"
    assert tracking == []
    assert len(records) == 1
    record = records[0]
    assert record["date"] == "2026-07-01 09:10:11"
    assert record["amount"] == Decimal("100.50")
    assert record["currency"] == "HKD"
    assert record["card_number"] == "5670"
    assert record["counterparty"] == "测验收款人"
    assert record["counterparty_account"] == "99887766"
    assert record["_source_payload"] == dict(zip(HEADERS, source_row, strict=True))
    assert record["_source_payload"][""] == "09:10:11"
    assert set(record["_source_payload"]) == set(HEADERS)


def test_verified_masked_counterparty_account_is_restored_without_changing_source_row(tmp_path):
    from ft.convert import _build_output_row, _read_icbc_asia_current_account_raw

    source_row = _row(
        "1", expense="100.50", counterparty_account="879825****47", counterparty="测验收款人",
    )
    statement = _write_statement(
        tmp_path / "currentaccounthistory.csv",
        [source_row],
        bank_account="879825074240",
    )

    records, bill_type, _tracking = _read_icbc_asia_current_account_raw(str(statement))
    output = _build_output_row(records[0], bill_type=bill_type, account="工银亚洲账户")

    assert output["counterparty_account"] == "879825074247"
    assert records[0]["_source_payload"]["對方賬號"] == "879825****47"


def test_infers_current_account_history_source():
    from ft.domain.imports import infer_statement_source

    assert infer_statement_source("currentaccounthistory7.csv") == "icbc-asia-current-account"


@pytest.mark.parametrize(
    ("rows", "currency", "headers", "message"),
    [
        ([_row("1", income="1", expense="1")], "港幣", None, "收入金額"),
        ([_row("1", income="1")], "未知币种", None, "币种"),
        ([_row("1", income="1")], "港幣", [*HEADERS[:-1], "错误列"], "表头"),
    ],
)
def test_rejects_invalid_statement_structure(tmp_path, rows, currency, headers, message):
    from ft.convert import _read_icbc_asia_current_account_raw

    statement = _write_statement(
        tmp_path / "currentaccounthistory.csv", rows, currency=currency, headers=headers
    )

    with pytest.raises(ValueError, match=message):
        _read_icbc_asia_current_account_raw(str(statement))


def test_rejects_indistinguishable_duplicate_rows(tmp_path):
    from ft.convert import _read_icbc_asia_current_account_raw

    first = _row("1", expense="100.50", counterparty_account="99887766", counterparty="测验收款人")
    second = ["2", *first[1:]]
    statement = _write_statement(tmp_path / "currentaccounthistory.csv", [first, second])

    with pytest.raises(ValueError, match="无法安全区分"):
        _read_icbc_asia_current_account_raw(str(statement))


def test_skips_zero_amount_account_opening_row(tmp_path):
    from ft.convert import _read_icbc_asia_current_account_raw

    opening = _row("1", income="0.00", business_type="新開戶")
    transaction = _row("2", income="1.00", business_type="轉賬")
    statement = _write_statement(tmp_path / "currentaccounthistory.csv", [opening, transaction])

    records, _, _ = _read_icbc_asia_current_account_raw(str(statement))

    assert len(records) == 1
    assert records[0]["_source_payload"]["序號"] == "2"


def test_business_identity_distinguishes_file_currency(tmp_path):
    from ft.convert import _read_icbc_asia_current_account_raw

    row = _row("1", income="1.00")
    hkd = _write_statement(tmp_path / "currentaccounthistory-hkd.csv", [row], currency="港幣")
    usd = _write_statement(tmp_path / "currentaccounthistory-usd.csv", [row], currency="美元")

    hkd_records, _, _ = _read_icbc_asia_current_account_raw(str(hkd))
    usd_records, _, _ = _read_icbc_asia_current_account_raw(str(usd))

    assert hkd_records[0]["_source_payload"] == usd_records[0]["_source_payload"]
    assert hkd_records[0]["_fact_id"] != usd_records[0]["_fact_id"]


def test_uses_generic_mapping_key_when_statement_omits_account_tail(tmp_path):
    from ft.convert import _read_icbc_asia_current_account_raw

    statement = _write_statement(tmp_path / "currentaccounthistory.csv", [_row("1", income="1")], account="")

    records, _source_type, _tracking = _read_icbc_asia_current_account_raw(str(statement))

    assert records[0]["card_number"] == ""
    assert records[0]["payment_method"] == "工银亚洲活期账户"


def test_prefers_bank_account_over_subaccount_for_routing_and_identity(tmp_path):
    from ft.convert import _read_icbc_asia_current_account_raw

    row = _row("1", income="1")
    first = _write_statement(
        tmp_path / "currentaccounthistory-first.csv",
        [row],
        account="1234567890125678",
        bank_account="8765432100004321",
    )
    second = _write_statement(
        tmp_path / "currentaccounthistory-second.csv",
        [row],
        account="1234567890125678",
        bank_account="8765432100009876",
    )

    first_records, _, _ = _read_icbc_asia_current_account_raw(str(first))
    second_records, _, _ = _read_icbc_asia_current_account_raw(str(second))

    assert first_records[0]["card_number"] == "4320"
    assert first_records[0]["payment_method"] == "工银亚洲活期账户(4320)"
    assert first_records[0]["_fact_id"] != second_records[0]["_fact_id"]


def _backend(tmp_path, backend):
    from ft.adapters.relational import (
        create_relational_engine,
        create_schema,
        create_session_factory,
        ensure_workspace,
    )
    from ft.adapters.relational.uow import RelationalUnitOfWork

    if backend == "sqlite":
        url = f"sqlite+pysqlite:///{tmp_path / 'icbc-asia-current-account.db'}"
    else:
        from conftest import require_test_postgres_url

        url = require_test_postgres_url()
        if url is None:
            pytest.skip("未设置 FT_TEST_POSTGRES_URL，跳过真实 PostgreSQL 工银亚洲导入契约测试")
        reset_postgres_schema(url)
    engine = create_relational_engine(url)
    create_schema(engine)
    sessions = create_session_factory(engine)
    workspace = "icbc-asia-current-account"
    ensure_workspace(sessions, workspace)
    return engine, sessions, RelationalUnitOfWork(sessions, workspace)


@pytest.mark.parametrize("backend", postgres_test_backend_params())
def test_statement_import_is_backend_equivalent_and_overlap_idempotent(
    tmp_path, monkeypatch, backend
):
    from ft import mapping as mapping_mod
    from ft.adapters.relational.models import CashTransactionModel
    from ft.adapters.statement_import import StatementParser
    from ft.application.statement_import import StatementImportService
    from ft.domain.imports import StatementImportCommand

    engine, sessions, uow = _backend(tmp_path, backend)
    try:
        monkeypatch.setattr(
            mapping_mod,
            "MAPPING_PATH",
            _write_mapping(tmp_path / "mapping.yaml", tail="4320"),
        )
        with uow as entered:
            entered.accounts.add_raw({
                "name": "工银亚洲港币账户", "type": "cash", "currency": "HKD",
            })
            entered.commit()

        shared = _row(
            "1", expense="100.50", counterparty_account="99887766", counterparty="测验收款人"
        )
        novel = _row("2", income="5.25", counterparty_account="", counterparty="")
        first_path = _write_statement(
            tmp_path / "currentaccounthistory-a.csv",
            [shared],
            bank_account="8765432100004321",
        )
        second_path = _write_statement(
            tmp_path / "currentaccounthistory-b.csv",
            [["9", *shared[1:]], novel],
            bank_account="8765432100004321",
        )
        service = StatementImportService(uow, StatementParser())

        first = service.import_statement(StatementImportCommand(source_path=str(first_path)))
        second = service.import_statement(StatementImportCommand(source_path=str(second_path)))

        assert first.ok is True
        assert first.count == 1
        assert second.ok is True
        assert second.count == 1
        with sessions() as session:
            facts = list(session.scalars(select(CashTransactionModel).order_by(CashTransactionModel.amount)))
            assert len(facts) == 2
            expense, income = facts
            assert expense.source_type == "icbc_asia_current_account"
            assert expense.record_id.startswith("icbc_asia_current_account_")
            assert expense.amount == Decimal("-100.50")
            assert expense.currency == "HKD"
            assert expense.counterparty == "测验收款人"
            assert expense.counterparty_account == "99887766"
            assert expense.source_payload["對方賬號"] == "99887766"
            assert expense.source_payload == dict(zip(HEADERS, shared, strict=True))
            assert expense.source_payload[""] == "09:10:11"
            assert income.amount == Decimal("5.25")
            assert income.counterparty == ""
            assert income.counterparty_account == ""
    finally:
        engine.dispose()


@pytest.mark.parametrize("backend", postgres_test_backend_params())
def test_statement_import_accepts_identical_rows_in_different_file_currencies(
    tmp_path, monkeypatch, backend
):
    from ft import mapping as mapping_mod
    from ft.adapters.relational.models import CashTransactionModel
    from ft.adapters.statement_import import StatementParser
    from ft.application.statement_import import StatementImportService
    from ft.domain.imports import StatementImportCommand

    engine, sessions, uow = _backend(tmp_path, backend)
    try:
        monkeypatch.setattr(mapping_mod, "MAPPING_PATH", _write_mapping(tmp_path / "mapping.yaml"))
        with uow as entered:
            entered.accounts.add_raw({
                "name": "工银亚洲港币账户", "type": "cash", "currency": "HKD",
            })
            entered.commit()

        row = _row("1", income="1.00")
        hkd = _write_statement(tmp_path / "currentaccounthistory-hkd.csv", [row], currency="港幣")
        usd = _write_statement(tmp_path / "currentaccounthistory-usd.csv", [row], currency="美元")
        service = StatementImportService(uow, StatementParser())

        assert service.import_statement(StatementImportCommand(source_path=str(hkd))).count == 1
        assert service.import_statement(StatementImportCommand(source_path=str(usd))).count == 1

        with sessions() as session:
            facts = list(session.scalars(select(CashTransactionModel)))
            assert sorted(fact.currency for fact in facts) == ["HKD", "USD"]
    finally:
        engine.dispose()


@pytest.mark.parametrize("backend", postgres_test_backend_params())
def test_currency_subaccounts_route_to_one_canonical_account(tmp_path, monkeypatch, backend):
    from ft import mapping as mapping_mod
    from ft.adapters.relational.models import CashTransactionModel
    from ft.adapters.statement_import import StatementParser
    from ft.application.statement_import import StatementImportService
    from ft.domain.imports import StatementImportCommand

    engine, sessions, uow = _backend(tmp_path, backend)
    try:
        monkeypatch.setattr(
            mapping_mod,
            "MAPPING_PATH",
            _write_mapping(
                tmp_path / "mapping.yaml", tail="6780", account="工银亚洲账户",
            ),
        )
        with uow as entered:
            entered.accounts.add_raw({
                "name": "工银亚洲账户", "type": "cash", "currency": "HKD",
            })
            entered.commit()

        hkd = _write_statement(
            tmp_path / "currentaccounthistory-hkd.csv", [_row("1", income="1.00")],
            bank_account="123456780", currency="港幣",
        )
        usd = _write_statement(
            tmp_path / "currentaccounthistory-usd.csv", [_row("1", income="1.00")],
            bank_account="123456781", currency="美元",
        )
        service = StatementImportService(uow, StatementParser())

        assert service.import_statement(StatementImportCommand(source_path=str(hkd))).count == 1
        assert service.import_statement(StatementImportCommand(source_path=str(usd))).count == 1

        with sessions() as session:
            facts = list(session.scalars(select(CashTransactionModel).order_by(CashTransactionModel.currency)))
            assert len(facts) == 2
            assert {fact.currency for fact in facts} == {"HKD", "USD"}
            assert len({fact.account_id for fact in facts}) == 1
            assert facts[0].record_id != facts[1].record_id
    finally:
        engine.dispose()


@pytest.mark.parametrize("bank_account", ["12345674240", "12345674241"])
def test_currency_subaccount_keeps_length_and_normalizes_currency_digit_to_zero(
    tmp_path, bank_account,
):
    from ft.convert import _read_icbc_asia_current_account_raw

    statement = _write_statement(
        tmp_path / f"currentaccounthistory-{bank_account}.csv",
        [_row("1", income="1.00")],
        bank_account=bank_account,
    )

    records, _, _ = _read_icbc_asia_current_account_raw(str(statement))

    assert records[0]["card_number"] == "4240"
    assert records[0]["payment_method"] == "工银亚洲活期账户(4240)"
