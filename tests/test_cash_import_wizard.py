from decimal import Decimal
import hashlib
from pathlib import Path

from sqlalchemy import func, select


class ChannelParser:
    def __init__(self, rows_by_source):
        self.rows_by_source = rows_by_source

    def parse(self, command):
        rows = self.rows_by_source.get(command.source)
        if rows is None:
            raise ValueError("unsupported parser")
        return [dict(row) for row in rows]


def _row(record_id="row-1", **overrides):
    row = {
        "record_id": record_id,
        "occurred_at": "2026-08-12T09:24:00+08:00",
        "amount": "-12.50",
        "currency": "CNY",
        "counterparty": "咖啡店",
        "counterparty_account": "",
        "counterparty_account_attrs": [],
        "note": "拿铁",
        "category": "餐饮",
        "record_type": "consumption",
        "record_subtype": "not_applicable",
        "account_name": "支付宝余额",
        "account_type": "cash",
        "bill_source": "alipay",
        "source_type": "alipay",
        "source_payload": {"交易对方": "咖啡店", "金额": "12.50"},
    }
    row.update(overrides)
    return row


def _service(tmp_path, rows_by_source):
    from test_postgres_adapter import _database
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.adapters.relational import ensure_workspace

    sessions, unit_of_work = _database()
    ensure_workspace(sessions, "wizard-workspace")
    with unit_of_work(sessions, "wizard-workspace") as uow:
        uow.accounts.add_raw({"name": "支付宝余额", "type": "cash", "currency": "CNY"})
        uow.commit()
    return sessions, CashLedgerCommandService(
        sessions,
        "wizard-workspace",
        parser=ChannelParser(rows_by_source),
    )


def test_cash_import_detects_unique_channel_and_preview_is_read_only(tmp_path):
    from ft.adapters.relational.models import CashTransactionModel

    source = tmp_path / "statement.csv"
    source.write_bytes(b"test statement")
    sessions, service = _service(tmp_path, {"alipay": [_row()]})

    detected = service.detect_import(source.read_bytes(), filename=source.name)
    assert detected["channel"] == "alipay"
    assert detected["digest"]

    preview = service.preview_import(
        source.read_bytes(), source="", currency=None, filename=source.name,
    )

    assert preview["channel"] == "alipay"
    assert preview["summary"] == {
        "total": 1, "new": 1, "existing": 0, "unsupported": 0,
    }
    assert set(preview["columns"]) == {
        "occurred_at", "amount", "currency", "account_name", "counterparty",
        "counterparty_account", "record_type", "record_subtype", "category",
        "note", "channel", "status",
    }
    assert "source_payload" not in preview["items"][0]
    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 0


def test_cash_import_commit_requires_matching_preview_digest(tmp_path):
    from ft.adapters.relational.models import CashTransactionModel

    source = tmp_path / "statement.csv"
    source.write_bytes(b"test statement")
    sessions, service = _service(tmp_path, {"alipay": [_row()]})

    try:
        service.commit_import(
            source.read_bytes(),
            source="alipay",
            currency=None,
            filename=source.name,
            preview_digest="not-the-file-digest",
        )
    except ValueError as exc:
        assert str(exc) == "import_preview_stale"
    else:
        raise AssertionError("stale preview must be rejected")

    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 0


def test_cash_import_detection_fails_closed_for_ambiguous_channels(tmp_path):
    source = tmp_path / "statement.csv"
    source.write_bytes(b"ambiguous statement")
    _sessions, service = _service(tmp_path, {
        "alipay": [_row()],
        "wechat": [_row(bill_source="wechat", source_type="wechat")],
    })

    try:
        service.detect_import(source.read_bytes(), filename=source.name)
    except ValueError as exc:
        assert str(exc) == "import_channel_unrecognized"
    else:
        raise AssertionError("ambiguous channels must fail closed")


def test_cash_import_detection_does_not_leak_parser_error_details(tmp_path):
    source = tmp_path / "statement.csv"
    source.write_bytes(b"broken statement")

    class ErrorParser:
        def parse(self, _command):
            raise ValueError("secret-account=62220000 token=secret-token")

    from ft.application.cash_ledger import CashLedgerCommandService
    from test_postgres_adapter import _database
    from ft.adapters.relational import ensure_workspace

    sessions, unit_of_work = _database()
    ensure_workspace(sessions, "wizard-error-workspace")
    service = CashLedgerCommandService(
        sessions, "wizard-error-workspace", parser=ErrorParser(),
    )
    try:
        service.detect_import(source.read_bytes(), filename=source.name)
    except ValueError as exc:
        assert str(exc) == "import_channel_unrecognized"
        assert "secret-token" not in str(exc)
    else:
        raise AssertionError("parser details must not escape channel probing")


def test_cash_import_channel_mismatch_is_stale_and_does_not_write(tmp_path):
    from ft.adapters.relational.models import CashTransactionModel

    source = tmp_path / "statement.csv"
    source.write_bytes(b"test statement")
    sessions, service = _service(tmp_path, {"alipay": [_row()]})

    try:
        service.commit_import(
            source.read_bytes(), source="alipay", currency=None, filename=source.name,
            preview_channel="wechat",
        )
    except ValueError as exc:
        assert str(exc) == "import_preview_stale"
    else:
        raise AssertionError("channel changes must invalidate the preview")

    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 0


def test_cash_import_preview_exposes_domain_relation_suggestions_without_writing(tmp_path):
    from ft.application.relations import RelationService

    source = tmp_path / "statement.csv"
    source.write_bytes(b"test statement")
    sessions, service = _service(tmp_path, {"alipay": [_row(account_name="建行储蓄")]})
    service._relation_service = RelationService(service._uow)
    with service._uow as uow:
        uow.accounts.add_raw({"name": "建行储蓄", "type": "cash", "currency": "CNY"})
        uow.commit()
    service.create_record({
        "occurred_at": "2026-08-12T09:24:03+08:00",
        "amount": "-12.50",
        "currency": "CNY",
        "account_name": "建行储蓄",
        "counterparty": "咖啡店",
        "counterparty_account": "",
        "note": "快捷支付",
        "category": "餐饮",
        "record_type": "consumption",
        "record_subtype": "not_applicable",
    })

    preview = service.preview_import(
        source.read_bytes(), source="", currency=None, filename=source.name,
    )

    assert preview["relations"]
    assert preview["relations"][0]["kind"] == "payment_mirror"
    assert preview["relations"][0]["automatic"] is True
    assert preview["relations"][0]["secondary"]["preview"] is False


def test_cash_import_confirm_persists_selected_relation_in_same_transaction(tmp_path):
    from ft.adapters.relational.models import TransactionRelationModel

    source = tmp_path / "statement.csv"
    source.write_bytes(b"test statement")
    _sessions, service = _service(tmp_path, {"alipay": [_row()]})
    existing = service.create_record({
        "occurred_at": "2026-08-12T09:24:03+08:00",
        "amount": "-12.50",
        "currency": "CNY",
        "account_name": "支付宝余额",
        "counterparty": "咖啡店",
        "counterparty_account": "",
        "note": "快捷支付",
        "category": "餐饮",
        "record_type": "consumption",
        "record_subtype": "not_applicable",
    })

    result = service.commit_import(
        source.read_bytes(), source="alipay", currency=None, filename=source.name,
        preview_digest=hashlib.sha256(source.read_bytes()).hexdigest(),
        preview_channel="alipay",
        relation_decisions=[{
            "kind": "payment_mirror",
            "primary_record_id": "row-1",
            "secondary_fact_id": existing["record"]["id"],
        }],
    )

    assert result["new_rows"] == 1
    with service._sessions() as session:
        relation = session.query(TransactionRelationModel).one()
        assert relation.status == "accepted"
        assert relation.created_by == "web"


def test_cash_import_rejected_relation_does_not_create_relation_or_block_import(tmp_path):
    from ft.adapters.relational.models import CashTransactionModel, TransactionRelationModel

    source = tmp_path / "statement.csv"
    source.write_bytes(b"test statement")
    sessions, service = _service(tmp_path, {"alipay": [_row()]})

    result = service.commit_import(
        source.read_bytes(), source="alipay", currency=None, filename=source.name,
        preview_digest=hashlib.sha256(source.read_bytes()).hexdigest(),
        preview_channel="alipay",
        relation_decisions=[{
            "kind": "payment_mirror",
            "primary_record_id": "row-1",
            "status": "rejected",
        }],
    )

    assert result["new_rows"] == 1
    with sessions() as session:
        assert session.query(CashTransactionModel).count() == 1
        assert session.query(TransactionRelationModel).count() == 0


def test_cash_import_preview_does_not_duplicate_existing_facts_for_relation_matching(tmp_path):
    from ft.application.relations import RelationService

    source = tmp_path / "statement.csv"
    source.write_bytes(b"test statement")
    rows = [
        _row(record_id="expense-1", counterparty="咖啡店", amount="-10", note="消费"),
        _row(
            record_id="refund-1", counterparty="咖啡店", amount="5",
            record_type="refund", category="income", note="退款",
        ),
    ]
    sessions, service = _service(tmp_path, {"alipay": rows})
    service.commit_import(
        source.read_bytes(), source="alipay", currency=None, filename=source.name,
    )
    service._relation_service = RelationService(service._uow)

    preview = service.preview_import(
        source.read_bytes(), source="alipay", currency=None, filename=source.name,
    )

    assert preview["summary"] == {"total": 2, "new": 0, "existing": 2, "unsupported": 0}
    assert preview["relations"] == []


def test_cash_import_repeat_does_not_apply_relation_decisions_for_existing_facts(tmp_path):
    from ft.adapters.relational.models import TransactionRelationModel

    source = tmp_path / "statement.csv"
    source.write_bytes(b"test statement")
    rows = [
        _row(record_id="expense-1", counterparty="咖啡店", amount="-10", note="消费"),
        _row(
            record_id="refund-1", counterparty="咖啡店", amount="5",
            record_type="refund", category="income", note="退款",
        ),
    ]
    sessions, service = _service(tmp_path, {"alipay": rows})
    service.commit_import(
        source.read_bytes(), source="alipay", currency=None, filename=source.name,
    )

    result = service.commit_import(
        source.read_bytes(), source="alipay", currency=None, filename=source.name,
        preview_digest=hashlib.sha256(source.read_bytes()).hexdigest(),
        preview_channel="alipay",
        relation_decisions=[{
            "kind": "refund_offset",
            "primary_record_id": "expense-1",
            "secondary_record_id": "refund-1",
            "status": "accepted",
        }],
    )

    assert result["new_rows"] == 0
    with sessions() as session:
        assert session.query(TransactionRelationModel).count() == 0


def test_cash_import_mixed_batch_can_pair_new_fact_with_existing_fact(tmp_path):
    from ft.adapters.relational.models import TransactionRelationModel

    source = tmp_path / "statement.csv"
    source.write_bytes(b"test statement")
    sessions, service = _service(tmp_path, {"alipay": [_row(account_name="建行储蓄")]})
    with service._uow as uow:
        uow.accounts.add_raw({"name": "建行储蓄", "type": "cash", "currency": "CNY"})
        uow.commit()
    existing = service.create_record({
        "occurred_at": "2026-08-12T09:24:03+08:00",
        "amount": "-12.50",
        "currency": "CNY",
        "account_name": "建行储蓄",
        "counterparty": "咖啡店",
        "counterparty_account": "",
        "note": "快捷支付",
        "category": "餐饮",
        "record_type": "consumption",
        "record_subtype": "not_applicable",
    })
    from ft.application.relations import RelationService
    service._relation_service = RelationService(service._uow)

    preview = service.preview_import(
        source.read_bytes(), source="alipay", currency=None, filename=source.name,
    )
    assert preview["summary"]["new"] == 1
    assert preview["relations"]

    relation = preview["relations"][0]
    result = service.commit_import(
        source.read_bytes(), source="alipay", currency=None, filename=source.name,
        preview_digest=hashlib.sha256(source.read_bytes()).hexdigest(),
        preview_channel="alipay",
        relation_decisions=[{
            "kind": relation["kind"],
            "primary_record_id": relation["primary"]["record_id"],
            "secondary_fact_id": existing["record"]["id"],
            "status": "accepted",
        }],
    )

    assert result["new_rows"] == 1
    with sessions() as session:
        assert session.query(TransactionRelationModel).count() == 1
