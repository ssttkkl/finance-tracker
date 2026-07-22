"""007 FR-052–055: business-day mirror + refund dual + diamond."""
from __future__ import annotations
from decimal import Decimal
from datetime import datetime, timezone

from ft.domain.relations import (
    FactView,
    FactType,
    RelationStatus,
    RelationKind,
    evaluate_payment_mirror,
    match_diamond_bank_refunds,
    business_day_shanghai,
    fact_is_bank_date_only,
    RULE_PAYMENT_MIRROR_BANK_DATE_ONLY_V1,
    RULE_PAYMENT_MIRROR_REFUND_DUAL_SOURCE_V1,
    RULE_REFUND_DIAMOND_V1,
)


def _fv(id, amount, *, account, bill_source, description="", counterparty="",
        occurred="", raw_date="", category=""):
    return FactView(
        id=id,
        amount=Decimal(str(amount)),
        currency="CNY",
        account_id=account,
        account_name=account,
        account_type="cash",
        occurred_at=occurred or "2023-01-01 00:00:00",
        counterparty=counterparty,
        description=description,
        category=category,
        bill_source=bill_source,
        source=bill_source,
        fact_type=FactType.CASH.value,
        raw_payload={"date": raw_date} if raw_date else None,
    )


def test_business_day_from_raw_date_only():
    bank = _fv("b", -1, account="a", bill_source="ccb_debit",
               occurred=datetime(2023, 6, 26, 16, 0, 0, tzinfo=timezone.utc),
               raw_date="2023-06-27")
    assert fact_is_bank_date_only(bank)
    assert str(business_day_shanghai(bank)) == "2023-06-27"


def test_bank_date_only_mirror_accepts():
    bank = _fv("b", -45, account="ccb", bill_source="ccb_debit",
               description="消费", counterparty="网易",
               occurred=datetime(2023, 12, 26, 16, 0, 0, tzinfo=timezone.utc),
               raw_date="2023-12-27")
    plat = _fv("p", -45, account="ccb", bill_source="alipay",
               description="网易云音乐-会员自动续费", counterparty="网易云音乐",
               occurred=datetime(2023, 12, 27, 14, 12, 0, tzinfo=timezone.utc),
               raw_date="2023-12-27 22:12:22")
    prop = evaluate_payment_mirror(plat, [bank])
    assert prop is not None
    assert prop.status == RelationStatus.ACCEPTED.value
    assert prop.rule_id == RULE_PAYMENT_MIRROR_BANK_DATE_ONLY_V1


def test_refund_dual_source_mirror():
    bank = _fv("b", 20.93, account="ccb", bill_source="ccb_debit",
               description="消费退货", counterparty="消费",
               occurred=datetime(2023, 7, 2, 16, 0, 0, tzinfo=timezone.utc),
               raw_date="2023-07-02")
    plat = _fv("p", 20.93, account="ccb", bill_source="alipay",
               description="退款-短裤", counterparty="店",
               occurred="2023-07-02 16:01:05",
               raw_date="2023-07-02 16:01:05")
    prop = evaluate_payment_mirror(plat, [bank])
    assert prop is not None
    assert prop.status == RelationStatus.ACCEPTED.value
    assert prop.rule_id == RULE_PAYMENT_MIRROR_REFUND_DUAL_SOURCE_V1


def test_diamond_refund():
    bank_pay = _fv("bp", -19.90, account="icbc", bill_source="icbc_debit",
                   description="消费", counterparty="支付宝网络",
                   raw_date="2023-07-06 10:27:27")
    bank_ref = _fv("br", 19.90, account="icbc", bill_source="icbc_debit",
                   description="退款", counterparty="支付宝网络",
                   raw_date="2023-07-06 12:28:29")
    plat_pay = _fv("pp", -19.90, account="ali", bill_source="alipay",
                   description="饭盒", counterparty="店",
                   raw_date="2023-07-06 10:27:03")
    plat_ref = _fv("pr", 19.90, account="ali", bill_source="alipay",
                   description="退款-饭盒", counterparty="店",
                   raw_date="2023-07-06 12:28:28")
    props = match_diamond_bank_refunds(
        [bank_pay, bank_ref, plat_pay, plat_ref],
        accepted_mirrors=[(bank_ref.id, plat_ref.id), (plat_pay.id, bank_pay.id)],
        accepted_platform_refunds=[(plat_pay.id, plat_ref.id)],
        open_or_pending_bank_refund_ids=[bank_ref.id],
    )
    assert len(props) == 1
    assert props[0].rule_id == RULE_REFUND_DIAMOND_V1
    assert props[0].status == RelationStatus.ACCEPTED.value
    assert props[0].primary_fact_id == bank_pay.id
    assert props[0].secondary_fact_id == bank_ref.id


def test_fact_is_bank_date_only_yyyy_mm_dd_len10():
    """raw date length 10 YYYY-MM-DD is always date-only (no 16:00 fallback needed)."""
    from ft.domain.relations import fact_is_bank_date_only, is_date_only_business_string
    assert is_date_only_business_string("2024-09-07") is True
    assert len("2024-09-07") == 10
    bank = _fv(
        "b", -8, account="ccb", bill_source="ccb_debit",
        description="消费",
        # formal occurred_at deliberately wrong day (UTC skew) — raw wins
        occurred=datetime(2024, 9, 6, 16, 0, 0, tzinfo=timezone.utc),
        raw_date="2024-09-07",
    )
    assert fact_is_bank_date_only(bank) is True


def test_fact_is_bank_date_only_false_for_full_datetime_raw():
    from ft.domain.relations import fact_is_bank_date_only
    bank = _fv(
        "b", -8, account="icbc", bill_source="icbc_debit",
        description="消费",
        occurred=datetime(2024, 9, 7, 4, 25, 44, tzinfo=timezone.utc),
        raw_date="2024-09-07 12:25:44",
    )
    assert fact_is_bank_date_only(bank) is False
