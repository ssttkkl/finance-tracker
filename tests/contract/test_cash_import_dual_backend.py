from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor

from sqlalchemy import func, select
import pytest


class _SourceParser:
    def parse_source_rows(self, _command):
        return [{
            "record_id": "dual-row-1", "bill_source": "alipay", "source_type": "alipay",
            "payment_method": "账户余额", "currency": "CNY", "amount": "-12.50",
            "date": "2026-08-12", "counterparty": "咖啡店", "counterparty_account": "",
            "category": "expense", "record_type": "consumption", "record_subtype": "not_applicable",
            "note": "消费", "_source_payload": {"原始": "expense"},
        }]


@pytest.mark.parametrize("runtime_name", ["cash_web_runtime", "postgres_cash_web_runtime"])
def test_cash_import_idempotency_is_equivalent_on_sqlite_and_postgres(request, runtime_name):
    from ft.adapters.relational import ensure_workspace
    from ft.adapters.relational.models import AccountModel, CashTransactionModel
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.application.cash_projections import CashProjectionService

    runtime = request.getfixturevalue(runtime_name)
    workspace_id = f"cash-import-dual-{runtime_name}"
    ensure_workspace(runtime.sessions, workspace_id)
    with runtime.sessions.begin() as session:
        CashProjectionService.initialize_in_session(session, workspace_id)
        session.add(AccountModel(
            workspace_id=workspace_id,
            name="支付宝余额",
            type="cash",
            currencies=["CNY"],
            active=True,
            metadata_json={},
        ))
    service = CashLedgerCommandService(
        runtime.sessions,
        workspace_id,
        parser=_SourceParser(),
    )

    scan = service.scan_import(b"dual-backend-statement", filename="statement.csv")
    mapping = [{
        "group_id": scan["groups"][0]["group_id"],
        "account_id": scan["accounts"][0]["id"],
    }]
    kwargs = {
        "source": "",
        "currency": None,
        "filename": "statement.csv",
        "preview_digest": scan["digest"],
        "preview_channel": scan["channel"],
        "mapping": mapping,
        "idempotency_key": f"dual-backend-{runtime_name}",
    }
    first = service.commit_import(b"dual-backend-statement", **kwargs)
    second = service.commit_import(b"dual-backend-statement", **kwargs)

    assert second == first
    with runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel).where(
            CashTransactionModel.workspace_id == workspace_id,
        )) == 1


def test_postgres_cash_import_confirmation_serializes_same_idempotency_key(postgres_cash_web_runtime):
    from ft.adapters.relational import ensure_workspace
    from ft.adapters.relational.models import AccountModel, CashTransactionModel
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.application.cash_projections import CashProjectionService

    runtime = postgres_cash_web_runtime
    workspace_id = "cash-import-postgres-concurrent"
    ensure_workspace(runtime.sessions, workspace_id)
    with runtime.sessions.begin() as session:
        CashProjectionService.initialize_in_session(session, workspace_id)
        session.add(AccountModel(
            workspace_id=workspace_id,
            name="支付宝余额",
            type="cash",
            currencies=["CNY"],
            active=True,
            metadata_json={},
        ))

    content = b"dual-backend-concurrent-statement"
    parser = _SourceParser()
    scanner = CashLedgerCommandService(runtime.sessions, workspace_id, parser=parser)
    scan = scanner.scan_import(content, filename="statement.csv")
    mapping = [{
        "group_id": scan["groups"][0]["group_id"],
        "account_id": scan["accounts"][0]["id"],
    }]
    kwargs = {
        "source": "",
        "currency": None,
        "filename": "statement.csv",
        "preview_digest": scan["digest"],
        "preview_channel": scan["channel"],
        "mapping": mapping,
        "idempotency_key": "postgres-concurrent-commit",
    }

    def confirm():
        return CashLedgerCommandService(
            runtime.sessions, workspace_id, parser=_SourceParser(),
        ).commit_import(content, **kwargs)

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = [future.result() for future in [pool.submit(confirm), pool.submit(confirm)]]

    assert results[0] == results[1]
    with runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel).where(
            CashTransactionModel.workspace_id == workspace_id,
        )) == 1


@pytest.mark.parametrize("runtime_name", ["cash_web_runtime", "postgres_cash_web_runtime"])
def test_refund_relation_metadata_survives_import_on_both_backends(request, runtime_name, tmp_path):
    from ft.adapters.relational import RelationalUnitOfWork, ensure_workspace
    from ft.adapters.relational.models import AccountModel, CashTransactionModel, TransactionRelationModel
    from ft.application.cash_projections import CashProjectionService
    from ft.application.relations import RelationService
    from ft.application.statement_import import StatementImportService
    from ft.domain.imports import StatementImportCommand
    from ft.domain.relations import RelationKind, RelationStatus

    runtime = request.getfixturevalue(runtime_name)
    workspace_id = f"refund-metadata-{runtime_name}"
    ensure_workspace(runtime.sessions, workspace_id)
    with runtime.sessions.begin() as session:
        CashProjectionService.initialize_in_session(session, workspace_id)
        session.add(AccountModel(
            workspace_id=workspace_id,
            name="微信测试账户",
            type="cash",
            currencies=["CNY"],
            active=True,
            metadata_json={},
        ))

    rows = [
        {
            "record_id": "wechat-origin-contract",
            "bill_source": "wechat",
            "source_type": "wechat",
            "account_name": "微信测试账户",
            "currency": "CNY",
            "amount": "-9.90",
            "occurred_at": "2023-10-09T05:51:23+00:00",
            "counterparty": "瑞幸咖啡",
            "record_type": "refund",
            "record_subtype": "not_applicable",
            "payment_method": "零钱",
            "status": "已全额退款",
            "txn_type": "商户消费",
            "merchant_order_id": "contract-order-1",
            "txn_id": "contract-txn-1",
            "source_payload": {
                "交易对方": "瑞幸咖啡", "金额": "-9.90", "状态": "已全额退款",
            },
            "relation_metadata": {
                "offset_role": "expense", "offset_group": "contract-refund-1",
            },
        },
        {
            "record_id": "wechat-refund-contract",
            "bill_source": "wechat",
            "source_type": "wechat",
            "account_name": "微信测试账户",
            "currency": "CNY",
            "amount": "9.90",
            "occurred_at": "2023-10-09T05:51:43+00:00",
            "counterparty": "瑞幸咖啡",
            "record_type": "refund",
            "record_subtype": "not_applicable",
            "payment_method": "零钱",
            "status": "已全额退款",
            "txn_type": "商户退款",
            "merchant_order_id": "contract-order-1",
            "txn_id": "contract-order-1",
            "source_payload": {
                "交易对方": "瑞幸咖啡", "金额": "9.90", "状态": "已全额退款",
            },
            "relation_metadata": {
                "offset_role": "refund", "offset_group": "contract-refund-1",
            },
        },
        {
            "record_id": "wechat-decoy-contract",
            "bill_source": "wechat",
            "source_type": "wechat",
            "account_name": "微信测试账户",
            "currency": "CNY",
            "amount": "-9.90",
            "occurred_at": "2023-10-09T06:30:00+00:00",
            "counterparty": "奈雪",
            "record_type": "consumption",
            "record_subtype": "not_applicable",
            "payment_method": "零钱",
            "status": "支付成功",
            "txn_type": "商户消费",
            "merchant_order_id": "decoy-order-1",
            "txn_id": "decoy-txn-1",
            "source_payload": {
                "交易对方": "奈雪", "金额": "-9.90", "状态": "支付成功",
            },
        },
    ]

    class Parser:
        def parse(self, _command):
            return [dict(row) for row in rows]

    unit_of_work = RelationalUnitOfWork(runtime.sessions, workspace_id)
    service = StatementImportService(
        unit_of_work,
        Parser(),
        relation_service=RelationService(unit_of_work),
    )
    source = tmp_path / "wechat-contract.xlsx"
    source.write_bytes(b"wechat-contract")
    result = service.import_statement(
        StatementImportCommand(str(source), source="wechat", currency="CNY")
    )

    assert result.count == 3
    with runtime.sessions() as session:
        facts = list(session.scalars(select(CashTransactionModel).where(
            CashTransactionModel.workspace_id == workspace_id,
        )))
        relations = list(session.scalars(select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == workspace_id,
            TransactionRelationModel.kind == RelationKind.REFUND_OFFSET.value,
            TransactionRelationModel.status == RelationStatus.ACCEPTED.value,
        )))
        assert len(facts) == 3
        assert len(relations) == 1
        assert sum(bool(fact.relation_metadata) for fact in facts) == 2
        assert all("offset_role" not in (fact.source_payload or {}) for fact in facts)


@pytest.mark.parametrize("runtime_name", ["cash_web_runtime", "postgres_cash_web_runtime"])
def test_shared_new_account_draft_creates_one_account_on_both_backends(request, runtime_name):
    from ft.adapters.relational import ensure_workspace
    from ft.adapters.relational.models import AccountModel, CashTransactionModel, StatementAccountMappingModel
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.application.cash_projections import CashProjectionService

    runtime = request.getfixturevalue(runtime_name)
    workspace_id = f"cash-import-shared-draft-{runtime_name}"
    ensure_workspace(runtime.sessions, workspace_id)
    with runtime.sessions.begin() as session:
        CashProjectionService.initialize_in_session(session, workspace_id)

    class SourceParser:
        def parse_source_rows(self, _command):
            return [
                {
                    "record_id": "shared-wallet-row", "bill_source": "alipay", "source_type": "alipay",
                    "payment_method": "账户余额", "currency": "CNY", "amount": "-1.00",
                    "date": "2026-08-15", "counterparty": "钱包商户", "counterparty_account": "",
                    "category": "expense", "record_type": "consumption", "record_subtype": "not_applicable",
                    "note": "钱包", "_source_payload": {"原始": "wallet"},
                },
                {
                    "record_id": "shared-credit-row", "bill_source": "alipay", "source_type": "alipay",
                    "payment_method": "花呗", "currency": "USD", "amount": "-2.00",
                    "date": "2026-08-15", "counterparty": "花呗商户", "counterparty_account": "",
                    "category": "expense", "record_type": "consumption", "record_subtype": "not_applicable",
                    "note": "花呗", "_source_payload": {"原始": "credit"},
                },
            ]

    service = CashLedgerCommandService(runtime.sessions, workspace_id, parser=SourceParser())
    scan = service.scan_import(b"shared-draft", filename="statement.csv")
    assert len(scan["groups"]) == 2
    mapping = [
        {
            "group_id": group["group_id"],
            "mapping_revision": None,
            "new_account": {
                "draft_id": "shared-draft-1", "name": "共享钱包", "type": "cash",
                "currencies": list(group["currencies"]),
            },
        }
        for group in scan["groups"]
    ]
    preview = service.preview_import(
        b"shared-draft", source="", currency=None, filename="statement.csv", mapping=mapping,
    )
    assert {item["account_name"] for item in preview["items"]} == {"共享钱包"}
    result = service.commit_import(
        b"shared-draft", source="", currency=None, filename="statement.csv",
        preview_digest=scan["digest"], preview_channel=scan["channel"], mapping=mapping,
    )
    assert result["new_rows"] == 2
    with runtime.sessions() as session:
        account = session.scalar(select(AccountModel).where(
            AccountModel.workspace_id == workspace_id, AccountModel.name == "共享钱包",
        ))
        assert account is not None
        assert account.currencies == ["CNY", "USD"]
        assert session.scalar(select(func.count()).select_from(AccountModel).where(
            AccountModel.workspace_id == workspace_id, AccountModel.name == "共享钱包",
        )) == 1
        assert session.scalar(select(func.count()).select_from(CashTransactionModel).where(
            CashTransactionModel.workspace_id == workspace_id, CashTransactionModel.account_id == account.id,
        )) == 2
        assert session.scalar(select(func.count()).select_from(StatementAccountMappingModel).where(
            StatementAccountMappingModel.workspace_id == workspace_id, StatementAccountMappingModel.account_id == account.id,
        )) == 2
