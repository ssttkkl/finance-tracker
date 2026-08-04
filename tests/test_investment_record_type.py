from decimal import Decimal

import pytest


def test_investment_repository_uses_record_type_and_record_subtype(tmp_path):
    from ft.adapters.relational import create_relational_engine
    from ft.adapters.relational.uow import (
        RelationalUnitOfWork,
        create_schema,
        create_session_factory,
        ensure_workspace,
    )
    from ft.domain.accounts import AccountDTO

    engine = create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'record-type.db'}")
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "record-type")
    uow = RelationalUnitOfWork(sessions, "record-type")
    with uow as unit:
        unit.accounts.add(AccountDTO("Broker", "security", active=True))
        unit.investments.add("security", {
            "occurred_at": "2026-08-04 09:00:00",
            "record_type": "funding",
            "record_subtype": "external",
            "to_ticker": "usd",
            "to_amount": "12.34",
            "from_amount": "0",
            "currency": "USD",
            "account_name": "Broker",
            "source_type": "fixture",
            "record_id": "funding-1",
            "source_payload": {"native_type": "wire_in"},
        })
        row = unit.investments.list()[0]
        unit.commit()

    assert row["record_type"] == "funding"
    assert row["record_subtype"] == "external"
    assert "action" not in row
    assert Decimal(row["to_amount"]) == Decimal("12.34")
    assert row["source_payload"] == {"native_type": "wire_in"}


@pytest.mark.parametrize(
    ("record_type", "record_subtype"),
    [
        ("funding", "tax"),
        ("expense", "external"),
        ("reversal", "reward"),
        ("snapshot", "interest"),
    ],
)
def test_investment_record_type_subtype_combinations_fail_closed(record_type, record_subtype):
    from ft.domain.investment_record_type import validate_investment_record_subtype

    with pytest.raises(ValueError, match="record_type|record_subtype"):
        validate_investment_record_subtype(record_type, record_subtype)


@pytest.mark.parametrize(
    ("record_type", "record_subtype"),
    [
        ("funding", "external"),
        ("funding", "subaccount"),
        ("trade", "security"),
        ("income", "interest"),
        ("expense", "tax"),
        ("reversal", "funding_withdrawal"),
        ("subscription", "ipo_refund"),
        ("adjustment", "fx_net"),
        ("snapshot", "position"),
    ],
)
def test_investment_economic_fact_type_subtype_combinations_are_accepted(record_type, record_subtype):
    from ft.domain.investment_record_type import validate_investment_record_subtype

    assert validate_investment_record_subtype(record_type, record_subtype) == (
        record_type,
        record_subtype,
    )


def test_investment_mappers_normalize_funding_and_non_funding_cash_events():
    from ft.importers.dfzq import map_dfzq_to_investment_event
    from ft.importers.ibkr import map_ibkr_to_investment_event
    from ft.importers.schwab import map_schwab_to_investment_event
    from ft.importers.usmart_hk import map_usmart_hk_to_investment_event

    ibkr_tax = map_ibkr_to_investment_event({
        "action": "外国预扣税", "date": "2026-08-01", "net": "-0.14",
    }, "IBKR", "USD")
    ibkr_fx = map_ibkr_to_investment_event({
        "action": "外汇交易组成部分", "date": "2026-08-01", "net": "-2.1", "code": "USD.HKD",
    }, "IBKR", "USD")
    usmart_transfer = map_usmart_hk_to_investment_event({
        "kind": "cash", "date": "2026-08-01", "flag": "转入到日内融账户",
        "flag_norm": "转入到日内融账户", "ccy": "USD", "amount": Decimal("-10"),
        "note": "转入到日内融账户",
    }, "盈立")
    usmart_refund = map_usmart_hk_to_investment_event({
        "kind": "cash", "date": "2026-08-01", "flag": "出金退款",
        "flag_norm": "出金退款", "ccy": "USD", "amount": Decimal("10"),
        "note": "出金退款",
    }, "盈立")
    dfzq_funding = map_dfzq_to_investment_event({
        "action": "DEPOSIT", "date": "2026-08-01", "amount": Decimal("10"),
        "shares": Decimal("0"), "fee": Decimal("0"), "price": Decimal("0"),
    }, "东方证券")
    schwab_interest = map_schwab_to_investment_event({
        "type": "DOI", "date": "2026-08-01", "amount": "-0.4", "description": "Interest Adjusted",
    }, "Schwab", "USD")

    assert (ibkr_tax["record_type"], ibkr_tax["record_subtype"]) == ("expense", "tax")
    assert (ibkr_fx["record_type"], ibkr_fx["record_subtype"]) == (
        "adjustment", "fx_net",
    )
    assert (usmart_transfer["record_type"], usmart_transfer["record_subtype"]) == (
        "funding", "subaccount",
    )
    assert (usmart_refund["record_type"], usmart_refund["record_subtype"]) == (
        "reversal", "funding_withdrawal",
    )
    assert (dfzq_funding["record_type"], dfzq_funding["record_subtype"]) == (
        "funding", "external",
    )
    assert (schwab_interest["record_type"], schwab_interest["record_subtype"]) == (
        "expense", "interest",
    )

    for event in (ibkr_tax, ibkr_fx, usmart_refund, schwab_interest):
        assert event["record_type"] not in {"deposit", "withdraw", "fee", "fx_adjustment"}
