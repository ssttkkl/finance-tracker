from decimal import Decimal
import hashlib
from pathlib import Path

import pytest
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


def test_cash_import_skips_unresolved_alipay_rows_but_imports_other_rows(tmp_path):
    from ft.adapters.relational.models import AccountModel, CashTransactionModel

    source = tmp_path / "alipay.csv"
    source.write_bytes(b"mixed composite payment")
    sessions, service = _service(tmp_path, {"alipay": [
        _row(
            "ambiguous",
            payment_method="账户余额&花呗分期(3期)",
            amount="-3020.00",
        ),
        _row("valid", payment_method="账户余额"),
    ]})

    scan = service.scan_import(source.read_bytes(), filename=source.name)
    assert scan["unresolved_count"] == 1
    group = scan["groups"][0]
    with sessions() as session:
        account_id = session.query(AccountModel.id).filter_by(
            workspace_id="wizard-workspace", name="支付宝余额",
        ).scalar()

    mapping = [{
        "group_id": group["group_id"],
        "account_id": account_id,
        "mapping_revision": group["suggestion"]["mapping_revision"],
    }]
    preview = service.preview_import(
        source.read_bytes(), source="", currency=None, filename=source.name,
        mapping=mapping,
    )
    assert preview["summary"] == {
        "total": 2, "new": 1, "existing": 0, "unsupported": 1, "unresolved": 1,
    }
    assert {item["status"] for item in preview["items"]} == {"new", "unresolved"}
    unresolved = next(item for item in preview["items"] if item["status"] == "unresolved")
    assert unresolved["account_name"] == ""
    assert unresolved["record_id"] == "ambiguous"
    assert preview["relations"] == []

    result = service.commit_import(
        source.read_bytes(), source="", currency=None, filename=source.name,
        preview_digest=scan["digest"], preview_channel="alipay", mapping=mapping,
    )
    assert result["new_rows"] == 1
    assert result["skipped_rows"] == 1
    with sessions() as session:
        rows = session.query(CashTransactionModel).all()
        assert len(rows) == 1
        assert rows[0].record_id == "valid"


def test_cash_import_rejects_file_with_only_unresolved_alipay_rows_without_writes(tmp_path):
    from ft.adapters.relational.models import AccountModel, CashTransactionModel, StatementAccountMappingModel

    source = tmp_path / "alipay.csv"
    source.write_bytes(b"ambiguous composite payment")
    sessions, service = _service(tmp_path, {"alipay": [_row(
        payment_method="账户余额&花呗分期(3期)",
        amount="-3020.00",
    )]})

    with pytest.raises(ValueError, match="import_composite_payment_unresolved"):
        service.scan_import(source.read_bytes(), filename=source.name)

    with sessions() as session:
        assert session.query(AccountModel).count() == 1
        assert session.query(StatementAccountMappingModel).count() == 0
        assert session.query(CashTransactionModel).count() == 0


def test_cash_import_promotes_legacy_alipay_combo_mapping_to_canonical_key(tmp_path):
    from ft.adapters.relational.models import AccountModel, StatementAccountMappingModel

    source = tmp_path / "alipay.csv"
    source.write_bytes(b"legacy combo mapping")
    raw_method = "工商银行信用卡(1200)&工商银行立减金"
    sessions, service = _service(tmp_path, {"alipay": [_row(payment_method=raw_method)]})

    with sessions() as session:
        account_id = session.query(AccountModel.id).filter_by(
            workspace_id="wizard-workspace", name="支付宝余额",
        ).scalar()
    with service._uow as uow:
        uow.statement_account_mappings.upsert(
            source_type="alipay", identity_kind="payment_method",
            source_account_key=raw_method, account_id=account_id, confirmed_by="web",
        )
        uow.commit()

    scan = service.scan_import(source.read_bytes(), filename=source.name)
    group = scan["groups"][0]
    assert group["suggestion"]["account_id"] == account_id
    assert group["suggestion"]["mapping_revision"] == 1

    service.commit_import(
        source.read_bytes(), source="", currency=None, filename=source.name,
        preview_digest=scan["digest"], preview_channel="alipay",
        mapping=[{
            "group_id": group["group_id"], "account_id": account_id,
            "mapping_revision": 1,
        }],
    )

    with sessions() as session:
        canonical = session.query(StatementAccountMappingModel).filter_by(
            workspace_id="wizard-workspace",
            source_type="alipay",
            identity_kind="payment_method",
            source_account_key="工商银行信用卡(1200)",
        ).one()
        assert canonical.account_id == account_id


def test_cash_import_password_is_required_and_forwarded_to_every_stage(tmp_path):
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.adapters.relational import ensure_workspace
    from ft.importers.pdf_tools import PDFPasswordInvalidError, PDFPasswordRequiredError
    from test_postgres_adapter import _database

    source = tmp_path / "statement.pdf"
    source.write_bytes(b"encrypted pdf")
    sessions, unit_of_work = _database()
    ensure_workspace(sessions, "wizard-password-workspace")
    with unit_of_work(sessions, "wizard-password-workspace") as uow:
        uow.accounts.add_raw({"name": "支付宝余额", "type": "cash", "currency": "CNY"})
        uow.commit()
    calls = []

    class PasswordParser:
        def parse(self, command):
            calls.append(command.password)
            if command.password is None:
                raise PDFPasswordRequiredError("PDF password required")
            if command.password != "correct-password":
                raise PDFPasswordInvalidError("PDF password invalid")
            return [_row()]

    service = CashLedgerCommandService(
        sessions, "wizard-password-workspace", parser=PasswordParser(),
    )

    try:
        service.detect_import(source.read_bytes(), filename=source.name)
    except PDFPasswordRequiredError:
        pass
    else:
        raise AssertionError("encrypted PDF without a password must request one")

    detected = service.detect_import(
        source.read_bytes(), filename=source.name, password="correct-password",
    )
    assert detected["channel"] == "alipay"
    preview = service.preview_import(
        source.read_bytes(), source="", currency=None, filename=source.name,
        password="correct-password",
    )
    assert preview["summary"]["new"] == 1
    result = service.commit_import(
        source.read_bytes(), source="alipay", currency=None, filename=source.name,
        password="correct-password", preview_digest=detected["digest"],
        preview_channel="alipay",
    )
    assert result["new_rows"] == 1
    assert calls[:6] == [None] * 6
    assert calls[6:]
    assert all(value == "correct-password" for value in calls[6:])


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


def test_wechat_preview_and_commit_use_hard_refund_plan_before_same_amount_candidates(tmp_path):
    from ft.adapters.relational.models import TransactionRelationModel
    from ft.application.relations import RelationService

    source = tmp_path / "wechat.xlsx"
    source.write_bytes(b"wechat fixture")
    rows = [
        _row(
            record_id="wechat-origin",
            account_name="支付宝余额",
            bill_source="wechat",
            source_type="wechat",
            amount="-9.90",
            counterparty="瑞幸",
            occurred_at="2023-10-09T13:51:23+08:00",
            source_payload={
                "merchant_order_id": "M-100", "txn_id": "T-100",
                "status": "支付成功", "txn_type": "消费",
            },
        ),
        _row(
            record_id="wechat-refund",
            account_name="支付宝余额",
            bill_source="wechat",
            source_type="wechat",
            amount="9.90",
            counterparty="瑞幸",
            record_type="refund",
            occurred_at="2023-10-09T13:51:43+08:00",
            source_payload={
                "merchant_order_id": "M-100", "txn_id": "M-100",
                "status": "退款成功", "txn_type": "商户退款",
            },
        ),
        _row(
            record_id="naixue",
            account_name="支付宝余额",
            bill_source="wechat",
            source_type="wechat",
            amount="-9.90",
            counterparty="奈雪",
            occurred_at="2023-10-08T18:40:36+08:00",
        ),
        _row(
            record_id="duodianbao",
            account_name="支付宝余额",
            bill_source="wechat",
            source_type="wechat",
            amount="-9.90",
            counterparty="多店宝网络",
            occurred_at="2023-10-09T20:15:11+08:00",
        ),
    ]
    sessions, service = _service(tmp_path, {"wechat": rows})
    service._relation_service = RelationService(service._uow)

    preview = service.preview_import(
        source.read_bytes(), source="", currency=None, filename=source.name,
    )

    assert preview["relation_digest"]
    refund_preview = [item for item in preview["relations"] if item["kind"] == "refund_offset"]
    assert len(refund_preview) == 1
    assert refund_preview[0]["primary"]["counterparty"] == "瑞幸"
    assert refund_preview[0]["secondary"]["counterparty"] == "瑞幸"

    result = service.commit_import(
        source.read_bytes(), source="", currency=None, filename=source.name,
        preview_digest=preview["file"]["digest"],
        preview_relation_digest=preview["relation_digest"],
        preview_channel=preview["channel"],
    )

    assert result["new_rows"] == 4
    with sessions() as session:
        relations = session.query(TransactionRelationModel).filter(
            TransactionRelationModel.workspace_id == "wizard-workspace",
            TransactionRelationModel.kind == "refund_offset",
        ).all()
        assert len(relations) == 1
        assert relations[0].rule_id.startswith("scan.wechat")


def test_cash_import_rejected_planned_relation_is_auditable(tmp_path):
    from ft.adapters.relational.models import TransactionRelationModel
    from ft.application.relations import RelationService

    source = tmp_path / "alipay.xlsx"
    source.write_bytes(b"alipay fixture")
    rows = [
        _row(record_id="expense-1", amount="-10.00", counterparty="咖啡店"),
        _row(
            record_id="refund-1", amount="5.00", counterparty="咖啡店",
            record_type="refund", category="退款",
        ),
    ]
    sessions, service = _service(tmp_path, {"alipay": rows})
    service._relation_service = RelationService(service._uow)

    preview = service.preview_import(
        source.read_bytes(), source="", currency=None, filename=source.name,
    )
    assert len(preview["relations"]) == 1
    relation = preview["relations"][0]

    result = service.commit_import(
        source.read_bytes(), source="", currency=None, filename=source.name,
        preview_digest=preview["file"]["digest"],
        preview_relation_digest=preview["relation_digest"],
        preview_channel=preview["channel"],
        relation_decisions=[{
            "proposal_key": relation["id"],
            "kind": relation["kind"],
            "primary_record_id": relation["primary"]["record_id"],
            "status": "rejected",
        }],
    )

    assert result["new_rows"] == 2
    with sessions() as session:
        persisted = session.query(TransactionRelationModel).filter(
            TransactionRelationModel.workspace_id == "wizard-workspace",
        ).one()
        assert persisted.status == "rejected"
        assert persisted.decided_by == "web"
        assert persisted.decision_reason == "import_rejected"


def test_cash_import_relation_plan_stale_rolls_back_new_cash_rows(tmp_path):
    from ft.adapters.relational.models import CashTransactionModel
    from ft.application.relations import RelationService

    source = tmp_path / "alipay.xlsx"
    source.write_bytes(b"alipay fixture")
    rows = [
        _row(record_id="expense-1", amount="-10.00", counterparty="咖啡店"),
        _row(
            record_id="refund-1", amount="5.00", counterparty="咖啡店",
            record_type="refund", category="退款",
        ),
    ]
    sessions, service = _service(tmp_path, {"alipay": rows})
    service._relation_service = RelationService(service._uow)
    preview = service.preview_import(
        source.read_bytes(), source="", currency=None, filename=source.name,
    )

    rows[0]["amount"] = "-11.00"
    with pytest.raises(ValueError, match="import_relation_preview_stale"):
        service.commit_import(
            source.read_bytes(), source="", currency=None, filename=source.name,
            preview_digest=preview["file"]["digest"],
            preview_relation_digest=preview["relation_digest"],
            preview_channel=preview["channel"],
        )

    with sessions() as session:
        assert session.query(CashTransactionModel).count() == 0


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


def test_cash_import_mapping_and_relations_rebuild_ready_projection_once(tmp_path):
    from ft.adapters.relational.models import AccountModel, CashTransactionModel, TransactionRelationModel
    from ft.adapters.relational import ensure_workspace
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.application.cash_projections import CashProjectionService
    from ft.application.relations import RelationService
    from test_postgres_adapter import _database

    class SourceParser:
        def parse_source_rows(self, _command):
            return [
                {
                    "record_id": "expense-1", "bill_source": "alipay", "source_type": "alipay",
                    "payment_method": "账户余额", "currency": "CNY", "amount": "-10.00",
                    "date": "2026-08-14", "counterparty": "咖啡店", "counterparty_account": "",
                    "category": "expense", "record_type": "consumption", "record_subtype": "not_applicable",
                    "note": "消费", "_source_payload": {"原始": "expense"},
                },
                {
                    "record_id": "refund-1", "bill_source": "alipay", "source_type": "alipay",
                    "payment_method": "账户余额", "currency": "CNY", "amount": "5.00",
                    "date": "2026-08-15", "counterparty": "咖啡店", "counterparty_account": "",
                    "category": "income", "record_type": "refund", "record_subtype": "not_applicable",
                    "note": "退款", "_source_payload": {"原始": "refund"},
                },
            ]

    sessions, unit_of_work = _database()
    workspace_id = "ready-mapping-relation-workspace"
    ensure_workspace(sessions, workspace_id)
    with sessions.begin() as session:
        CashProjectionService.initialize_in_session(session, workspace_id)
    service = CashLedgerCommandService(
        sessions, workspace_id, parser=SourceParser(),
        relation_service=RelationService(unit_of_work(sessions, workspace_id)),
    )

    scan = service.scan_import(b"fixture", filename="statement.csv")
    mapping = [{
        "group_id": scan["groups"][0]["group_id"],
        "mapping_revision": None,
        "new_account": {"name": "支付宝余额", "type": "cash", "currencies": ["CNY"]},
    }]
    preview = service.preview_import(
        b"fixture", source="", currency=None, filename="statement.csv", mapping=mapping,
    )
    assert len(preview["relations"]) == 1
    relation = preview["relations"][0]
    decision = {
        "kind": relation["kind"],
        "primary_record_id": relation["primary"]["record_id"],
        "secondary_record_id": relation["secondary"]["record_id"],
        "status": "accepted",
    }

    result = service.commit_import(
        b"fixture", source="", currency=None, filename="statement.csv",
        preview_digest=scan["digest"], preview_channel=scan["channel"],
        mapping=mapping, relation_decisions=[decision],
    )

    assert result["new_rows"] == 2
    with sessions() as session:
        assert session.scalar(select(AccountModel).where(
            AccountModel.workspace_id == workspace_id,
            AccountModel.name == "支付宝余额",
        )) is not None
        assert session.scalar(select(func.count()).select_from(CashTransactionModel).where(
            CashTransactionModel.workspace_id == workspace_id,
        )) == 2
        assert session.scalar(select(func.count()).select_from(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == workspace_id,
            TransactionRelationModel.status == "accepted",
        )) == 1


def test_cash_import_commit_reuses_same_idempotency_result(tmp_path):
    from ft.adapters.relational.models import CashTransactionModel
    from ft.adapters.relational import ensure_workspace
    from ft.application.cash_ledger import CashLedgerCommandService
    from test_postgres_adapter import _database

    source = tmp_path / "statement.csv"
    source.write_bytes(b"test statement")
    sessions, unit_of_work = _database()
    workspace_id = "cash-import-idempotency-workspace"
    ensure_workspace(sessions, workspace_id)
    with unit_of_work(sessions, workspace_id) as uow:
        uow.accounts.add_raw({"name": "支付宝余额", "type": "cash", "currency": "CNY"})
        uow.commit()

    class SourceParser:
        def parse_source_rows(self, _command):
            return [{
                "record_id": "row-1", "bill_source": "alipay", "source_type": "alipay",
                "payment_method": "账户余额", "currency": "CNY", "amount": "-12.50",
                "date": "2026-08-12", "counterparty": "咖啡店", "counterparty_account": "",
                "category": "expense", "record_type": "consumption", "record_subtype": "not_applicable",
                "note": "消费", "_source_payload": {"原始": "expense"},
            }]

    service = CashLedgerCommandService(
        sessions,
        workspace_id,
        parser=SourceParser(),
    )
    scan = service.scan_import(source.read_bytes(), filename=source.name)
    mapping = [{
        "group_id": scan["groups"][0]["group_id"],
        "mapping_revision": scan["groups"][0].get("mapping_revision"),
        "account_id": scan["accounts"][0]["id"],
    }]

    kwargs = {
        "source": "",
        "currency": None,
        "filename": source.name,
        "preview_digest": scan["digest"],
        "preview_channel": scan["channel"],
        "mapping": mapping,
        "idempotency_key": "cash-import-commit-1",
    }
    first = service.commit_import(source.read_bytes(), **kwargs)
    second = service.commit_import(source.read_bytes(), **kwargs)

    assert second == first
    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 1

    with pytest.raises(ValueError, match="import_idempotency_conflict"):
        service.commit_import(
            b"a different source", source="", currency=None, filename=source.name,
            preview_channel=scan["channel"], mapping=mapping,
            idempotency_key="cash-import-commit-1",
        )


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
