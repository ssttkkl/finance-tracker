from ft.adapters.statement_import import _dfzq_rows
from ft.domain.imports import StatementImportCommand


def test_dfzq_rows_map_buy_sell_and_cash_with_exact_decimal_text(tmp_path, monkeypatch):
    from ft import mapping as mapping_mod
    import yaml

    mapping = tmp_path / "mapping.yaml"
    mapping.write_text(
        yaml.safe_dump(
            {
                "rules": [
                    {
                        "source": "dfzq",
                        "match": "*",
                        "account": "东方证券",
                        "currency": "CNY",
                    }
                ],
                "default": "error",
            },
            allow_unicode=True,
        ),
        encoding="utf-8",
    )
    monkeypatch.setattr(mapping_mod, "MAPPING_PATH", mapping)

    records = [
        {"date": "2026-07-01", "action": "BUY", "ticker": "600000.ss",
         "amount": "-10.123456789012345678", "shares": "2", "price": "5",
         "fee": "0.1", "note": "buy"},
        {"date": "2026-07-02", "action": "SELL", "ticker": "600000.ss",
         "amount": "12", "shares": "1", "price": "12", "fee": "0.2", "note": "sell"},
        {"date": "2026-07-03", "action": "DEPOSIT", "ticker": "",
         "amount": "3.5", "shares": "0", "price": "0", "fee": "0", "note": "cash"},
    ]
    command = StatementImportCommand(
        source_path="statement.pdf", source="dfzq", currency="CNY",
    )

    rows = _dfzq_rows(records, command)

    assert rows[0]["from_amount"] == "10.123456789012345678"
    assert rows[0]["to_ticker"] == "600000.ss"
    assert rows[0]["account_name"] == "东方证券"
    assert rows[1]["from_ticker"] == "600000.ss"
    assert rows[2]["action"] == "deposit"
    assert rows[2]["to_amount"] == "3.5"


def test_statement_output_only_promotes_provider_stable_ids():
    from ft.convert import _build_output_row

    base = {
        "date": "2026-07-01 09:00:00",
        "amount": "-1",
        "category": "expense",
    }
    icbc = _build_output_row(
        {**base, "_fact_id": "icbc_debit_a1b2c3d4e5f6"},
        bill_type="icbc_debit", account="Card", currency="CNY",
    )
    alipay_fallback = _build_output_row(
        {
            **base,
            "_fact_id": "alipay_000001",
            "record_id": "alipay_000001",
            "txn_id": "",
        },
        bill_type="alipay", account="Wallet", currency="CNY",
    )
    alipay_provider_id = _build_output_row(
        {**base, "_fact_id": "alipay_order-1", "txn_id": "order-1"},
        bill_type="alipay", account="Wallet", currency="CNY",
    )
    ccb_hash = _build_output_row(
        {**base, "_fact_id": "ccb_debit_abc123"},
        bill_type="ccb_debit", account="Card", currency="CNY",
    )

    assert icbc["record_id"] == "icbc_debit_a1b2c3d4e5f6"
    assert alipay_fallback["record_id"] == ""
    assert alipay_provider_id["record_id"] == "order-1"
    assert ccb_hash["record_id"] == "ccb_debit_abc123"


def test_dfzq_rows_reject_amount_scale_over_18(tmp_path, monkeypatch):
    from ft import mapping as mapping_mod
    import yaml
    import pytest

    mapping = tmp_path / "mapping.yaml"
    mapping.write_text(
        yaml.safe_dump(
            {
                "rules": [
                    {
                        "source": "dfzq",
                        "match": "*",
                        "account": "东方证券",
                        "currency": "CNY",
                    }
                ],
                "default": "error",
            },
            allow_unicode=True,
        ),
        encoding="utf-8",
    )
    monkeypatch.setattr(mapping_mod, "MAPPING_PATH", mapping)

    command = StatementImportCommand(
        source_path="statement.pdf", source="dfzq", currency="CNY",
    )
    records = [{
        "date": "2026-07-01", "action": "DEPOSIT", "ticker": "",
        "amount": "0.1234567890123456789", "shares": "0", "price": "0",
        "fee": "0", "note": "",
    }]

    with pytest.raises(ValueError, match="at most 18"):
        _dfzq_rows(records, command)
