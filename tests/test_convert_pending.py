import csv

import pytest

from ft import models


@pytest.fixture
def tmp_ft_home(monkeypatch, tmp_path):
    monkeypatch.setattr(models, "FT_DIR", tmp_path)
    monkeypatch.setattr(models, "RECORDS_DIR", tmp_path / "records")
    monkeypatch.setattr(models, "ACCOUNTS_PATH", tmp_path / "accounts.yaml")
    monkeypatch.setattr(models, "PENDING_DIR", tmp_path / "pending")
    return tmp_path


def _make_alipay_csv(rows: list[list[str]], path):
    header = ["交易时间", "交易分类", "交易对方", "商品说明", "收/支", "金额", "收/付款方式"]
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(header)
        for row in rows:
            writer.writerow(row)


def test_convert_weak_refund_writes_output_without_creating_pending(tmp_ft_home, monkeypatch):
    from ft.convert import do_convert

    bill_path = tmp_ft_home / "bill.csv"
    output_path = tmp_ft_home / "converted.csv"
    _make_alipay_csv([
        ["2026-01-01 12:00:00", "消费", "商家A", "买书", "支出", "100.00", "工商银行信用卡(1200)"],
        ["2026-01-05 10:00:00", "退款", "商家A", "退款-买书", "收入", "100.00", "工商银行信用卡(1200)"],
    ], bill_path)
    monkeypatch.setattr("ft.convert.load_rules", lambda: ([], None))
    monkeypatch.setattr(
        "ft.convert._build_output_row",
        lambda rec, **kwargs: {
            "record_id": rec["record_id"],
            "date": rec["date"], "amount": rec["amount"], "currency": rec["currency"],
            "counterparty": rec["counterparty"], "description": rec["description"],
            "category": rec["category"], "account_name": "支付宝", "source": "支付宝",
            "bill_source": "alipay", "offset_group": rec.get("offset_group", ""),
            "offset_role": rec.get("offset_role", ""), "offset_strength": rec.get("offset_strength", ""),
            "offset_source": rec.get("offset_source", ""), "offset_rule_hint": rec.get("offset_rule_hint", ""),
            "offset_match_type": rec.get("offset_match_type", ""), "proposed_action": rec.get("proposed_action", "leave_as_is"),
        },
    )
    monkeypatch.setattr(
        "ft.convert._prepare_convert_rows",
        lambda path, source, password=None: (
            [
                {"record_id": "c_000001", "date": "2026-01-01 12:00:00", "amount": -100.0, "currency": "CNY", "counterparty": "商家A",
                 "description": "买书", "category": "expense", "payment_method": "余额", "offset_group": "refund_000001",
                 "offset_role": "expense", "offset_strength": "weak", "offset_source": "alipay_status",
                 "offset_rule_hint": "refund_cp_match", "offset_match_type": "partial", "proposed_action": "leave_as_is"},
                {"record_id": "c_000002", "date": "2026-01-02 09:00:00", "amount": 30.0, "currency": "CNY", "counterparty": "商家A",
                 "description": "退款", "category": "income", "payment_method": "余额", "offset_group": "refund_000001",
                 "offset_role": "refund", "offset_strength": "weak", "offset_source": "alipay_status",
                 "offset_rule_hint": "refund_cp_match", "offset_match_type": "partial", "proposed_action": "merge_refund_into:c_000001"},
            ],
            "alipay",
            [{
                "expense": {"_fact_id": "c_000001", "date": "2026-01-01 12:00:00", "amount": -100.0, "currency": "CNY", "counterparty": "商家A", "description": "买书", "payment_method": "余额"},
                "refund": {"_fact_id": "c_000002", "date": "2026-01-02 09:00:00", "amount": 30.0, "currency": "CNY", "counterparty": "商家A", "description": "退款", "payment_method": "余额"},
                "match_type": "partial",
                "match_strength": "weak",
                "pending_required": True,
                "rule_hint": "refund_desc_fallback",
                "candidate_count": 2,
            }],
        ),
    )

    do_convert(str(bill_path), "alipay", str(output_path))

    sessions = list((models.PENDING_DIR / "convert").glob("*")) if (models.PENDING_DIR / "convert").exists() else []
    assert sessions == []
    assert output_path.exists()

    with output_path.open(encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 2
    expense = next(row for row in rows if row["category"] == "expense")
    refund = next(row for row in rows if row["category"] == "income")
    assert expense["offset_group"] == "refund_000001"
    assert expense["offset_role"] == "expense"
    assert expense["offset_strength"] == "weak"
    assert expense["offset_rule_hint"] == "refund_cp_match"
    assert expense["offset_match_type"] == "partial"
    assert refund["offset_group"] == "refund_000001"
    assert refund["offset_role"] == "refund"
    assert refund["offset_strength"] == "weak"
    assert refund["offset_rule_hint"] == "refund_cp_match"
    assert refund["offset_match_type"] == "partial"
    assert refund["proposed_action"] == "merge_refund_into:c_000001"
