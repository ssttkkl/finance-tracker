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


def test_convert_pending_does_not_write_formal_output(tmp_ft_home, monkeypatch):
    from ft.convert import do_convert

    bill_path = tmp_ft_home / "bill.csv"
    output_path = tmp_ft_home / "converted.csv"
    refund_path = tmp_ft_home / "converted_refunds.csv"
    _make_alipay_csv([
        ["2026-01-01 12:00:00", "消费", "商家A", "买书", "支出", "100.00", "工商银行信用卡(1200)"],
        ["2026-01-05 10:00:00", "退款", "商家A", "退款-买书", "收入", "100.00", "工商银行信用卡(1200)"],
    ], bill_path)
    monkeypatch.setattr("ft.convert.load_rules", lambda: ([], None))
    monkeypatch.setattr(
        "ft.convert._prepare_convert_rows",
        lambda path, source, password=None: (
            [{"date": "2026-01-01 12:00:00", "amount": -70.0, "currency": "CNY", "counterparty": "商家A",
              "description": "买书", "category": "expense", "payment_method": "余额"}],
            "alipay",
            [{
                "expense": {"date": "2026-01-01 12:00:00", "amount": -100.0, "currency": "CNY", "counterparty": "商家A", "description": "买书", "payment_method": "余额"},
                "refund": {"date": "2026-01-02 09:00:00", "amount": 30.0, "currency": "CNY", "counterparty": "商家A", "description": "退款", "payment_method": "余额"},
                "match_type": "partial",
                "match_strength": "weak",
                "pending_required": True,
            }],
        ),
    )
    monkeypatch.setattr(
        "ft.convert._build_output_row",
        lambda rec, **kwargs: {
            "date": rec["date"], "amount": rec["amount"], "currency": rec["currency"],
            "counterparty": rec["counterparty"], "description": rec["description"],
            "category": rec["category"], "account_name": "支付宝", "source": "支付宝",
            "bill_source": "alipay", "transfer_account": "", "locked": "",
        },
    )

    do_convert(str(bill_path), "alipay", str(output_path))

    sessions = list((models.PENDING_DIR / "convert").glob("*"))
    assert len(sessions) == 1
    assert not output_path.exists()
    assert not refund_path.exists()
    assert (sessions[0] / "ai_working.csv").exists()
    assert (sessions[0] / "proposed_output.csv").exists()
    assert (sessions[0] / "proposed_refunds.csv").exists()
