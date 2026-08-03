"""对方账号转账候选收敛的 SQLite/PostgreSQL 契约。"""
from __future__ import annotations

import json
from sqlalchemy import select
import pytest

from conftest import postgres_test_backend_params, reset_postgres_schema


def _backend(tmp_path, backend):
    from ft.adapters.relational import (
        create_relational_engine,
        create_schema,
        create_session_factory,
        ensure_workspace,
    )
    from ft.adapters.relational.uow import RelationalUnitOfWork

    if backend == "sqlite":
        url = f"sqlite+pysqlite:///{tmp_path / 'counterparty-account.db'}"
    else:
        from conftest import require_test_postgres_url

        url = require_test_postgres_url()
        if url is None:
            pytest.skip("未设置 FT_TEST_POSTGRES_URL，跳过真实 PostgreSQL 对方账号契约测试")
        reset_postgres_schema(url)
    engine = create_relational_engine(url)
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "counterparty-account")
    ensure_workspace(sessions, "other-workspace")
    return engine, sessions, RelationalUnitOfWork(sessions, "counterparty-account")


def _row(*, account_name, record_id, amount, record_type, occurred_at, counterparty_account=""):
    return {
        "account_name": account_name,
        "record_id": record_id,
        "source_type": "fixture",
        "source_payload": {"原始字段": record_id},
        "occurred_at": occurred_at,
        "amount": amount,
        "currency": "CNY",
        "counterparty": "示例对手方",
        "counterparty_account": counterparty_account,
        "note": "转账支取" if amount.startswith("-") else "转账存入",
        "category": "transfer",
        "record_type": record_type,
    }


@pytest.mark.parametrize("backend", postgres_test_backend_params())
def test_counterparty_account_matching_is_workspace_scoped_and_redacted(tmp_path, backend):
    from ft.adapters.relational.models import AccountAliasModel, AccountModel, TransactionRelationModel
    from ft.application.relations import RelationService
    from ft.domain.relations import RelationKind, RelationStatus

    engine, sessions, uow = _backend(tmp_path, backend)
    try:
        with uow as session:
            for name in ("来源账户", "错误候选", "目标账户"):
                session.accounts.add_raw({"name": name, "type": "cash", "currency": "CNY"})
            target = session._state().session.scalar(select(AccountModel).where(
                AccountModel.workspace_id == "counterparty-account",
                AccountModel.name == "目标账户",
            ))
            assert target is not None
            session.account_aliases.add(
                alias_type="account_identifier",
                alias_value="6222000000001234",
                account_id=target.id,
            )
            out_id = session.cashflows.add("cash", _row(
                account_name="来源账户", record_id="out", amount="-1000.00",
                record_type="transfer_out", occurred_at="2026-01-01 10:00:00",
                counterparty_account="6222-0000-0000-1234",
            ))
            session.cashflows.add("cash", _row(
                account_name="错误候选", record_id="wrong", amount="1000.00",
                record_type="transfer_in", occurred_at="2026-01-01 10:00:01",
            ))
            target_fact_id = session.cashflows.add("cash", _row(
                account_name="目标账户", record_id="target", amount="1000.00",
                record_type="transfer_in", occurred_at="2026-01-01 10:00:02",
            ))
            session.commit()

        with sessions.begin() as session:
            other = AccountModel(
                workspace_id="other-workspace", name="其他账户", type="cash",
            )
            session.add(other)
            session.flush()
            session.add(AccountAliasModel(
                workspace_id="other-workspace",
                alias_type="account_identifier",
                alias_value="6222000000001234",
                account_id=other.id,
            ))

        result = RelationService(uow).check(seed_fact_ids=[out_id], trigger="manual_range")
        assert result.ok is True

        with sessions() as session:
            relation = session.scalar(select(TransactionRelationModel).where(
                TransactionRelationModel.workspace_id == "counterparty-account",
                TransactionRelationModel.kind == RelationKind.TRANSFER_PAIR.value,
                TransactionRelationModel.status == RelationStatus.ACCEPTED.value,
            ))
            assert relation is not None
            assert relation.primary_fact_id == out_id
            assert relation.secondary_fact_id == target_fact_id
            assert relation.evidence_json["counterparty_account_match"] == "exact"
            evidence = json.dumps(relation.evidence_json, ensure_ascii=False)
            assert "6222" not in evidence
    finally:
        engine.dispose()


@pytest.mark.parametrize("backend", postgres_test_backend_params())
def test_account_identifier_alias_normalizes_separators_and_rejects_invalid_values(tmp_path, backend):
    from ft.adapters.relational.models import AccountModel

    engine, sessions, uow = _backend(tmp_path, backend)
    try:
        with uow as session:
            session.accounts.add_raw({"name": "目标账户", "type": "cash", "currency": "CNY"})
            account = session._state().session.scalar(select(AccountModel).where(
                AccountModel.workspace_id == "counterparty-account",
                AccountModel.name == "目标账户",
            ))
            assert account is not None
            session.account_aliases.add(
                alias_type="account_identifier",
                alias_value="6222-0000-0000-1234",
                account_id=account.id,
            )
            with pytest.raises(ValueError, match="card_tail"):
                session.account_aliases.add(
                    alias_type="card_tail", alias_value="12A4", account_id=account.id,
                )
            with pytest.raises(ValueError, match="account_identifier"):
                session.account_aliases.add(
                    alias_type="account_identifier", alias_value="示例账户", account_id=account.id,
                )
            aliases = session.account_aliases.list()
            assert aliases[0]["alias_value"] == "6222000000001234"
            session.commit()
    finally:
        engine.dispose()
