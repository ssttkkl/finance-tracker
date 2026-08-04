"""标准化对方账号转账匹配的 SQLite/PostgreSQL 契约。"""
from __future__ import annotations

import pytest
from sqlalchemy import select

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
        url = f"sqlite+pysqlite:///{tmp_path / 'normalized-transfer.db'}"
    else:
        from conftest import require_test_postgres_url

        url = require_test_postgres_url()
        if url is None:
            pytest.skip("未设置 FT_TEST_POSTGRES_URL，跳过真实 PostgreSQL 契约测试")
        reset_postgres_schema(url)
    engine = create_relational_engine(url)
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "normalized-transfer")
    ensure_workspace(sessions, "other-workspace")
    return engine, sessions, RelationalUnitOfWork(sessions, "normalized-transfer")


def _row(
    *, account_name, record_id, amount, record_type, record_subtype, occurred_at,
    counterparty_account="", currency="CNY", source_type="fixture",
):
    return {
        "account_name": account_name,
        "record_id": record_id,
        "source_type": source_type,
        "source_payload": {"原始字段": record_id},
        "occurred_at": occurred_at,
        "amount": amount,
        "currency": currency,
        "counterparty": "示例对手方",
        "counterparty_account": counterparty_account,
        "note": "任意原始文本",
        "category": "transfer",
        "record_type": record_type,
        "record_subtype": record_subtype,
    }


def _relation(session):
    from ft.adapters.relational.models import TransactionRelationModel
    from ft.domain.relations import RelationKind

    return session.scalar(select(TransactionRelationModel).where(
        TransactionRelationModel.workspace_id == "normalized-transfer",
        TransactionRelationModel.kind == RelationKind.TRANSFER_PAIR.value,
    ))


@pytest.mark.parametrize("backend", postgres_test_backend_params())
def test_ordinary_transfer_uses_workspace_scoped_account_identifier(tmp_path, backend):
    from ft.adapters.relational.models import AccountAliasModel, AccountModel
    from ft.application.relations import RelationService
    from ft.domain.relations import RelationStatus

    engine, sessions, uow = _backend(tmp_path, backend)
    try:
        with uow as session:
            for name in ("来源账户", "错误候选", "目标账户"):
                session.accounts.add_raw({"name": name, "type": "cash", "currency": "CNY"})
            target = session._state().session.scalar(select(AccountModel).where(
                AccountModel.workspace_id == "normalized-transfer", AccountModel.name == "目标账户",
            ))
            assert target is not None
            session.account_aliases.add(
                alias_type="account_identifier", alias_value="6222000000001234", account_id=target.id,
            )
            out_id = session.cashflows.add("cash", _row(
                account_name="来源账户", record_id="out", amount="-1000.00",
                record_type="transfer_out", record_subtype="ordinary_transfer",
                occurred_at="2026-01-01 10:00:00", counterparty_account="6222000000001234",
            ))
            session.cashflows.add("cash", _row(
                account_name="错误候选", record_id="wrong", amount="1000.00",
                record_type="transfer_in", record_subtype="ordinary_transfer",
                occurred_at="2026-01-01 10:00:01",
            ))
            target_id = session.cashflows.add("cash", _row(
                account_name="目标账户", record_id="target", amount="1000.00",
                record_type="transfer_in", record_subtype="ordinary_transfer",
                occurred_at="2026-01-01 10:00:02",
            ))
            session.commit()
        with sessions.begin() as session:
            other = AccountModel(workspace_id="other-workspace", name="其他账户", type="cash")
            session.add(other)
            session.flush()
            session.add(AccountAliasModel(
                workspace_id="other-workspace", alias_type="account_identifier",
                alias_value="6222000000001234", account_id=other.id,
            ))

        assert RelationService(uow).check(seed_fact_ids=[out_id], trigger="manual_range").ok
        with sessions() as session:
            relation = _relation(session)
            assert relation is not None
            assert relation.status == RelationStatus.ACCEPTED.value
            assert {relation.primary_fact_id, relation.secondary_fact_id} == {out_id, target_id}
            assert relation.subtype == "ordinary_transfer"
    finally:
        engine.dispose()


@pytest.mark.parametrize("backend", postgres_test_backend_params())
def test_cross_border_cross_currency_uses_normalized_fields_not_source(tmp_path, backend):
    from ft.adapters.relational.models import AccountModel
    from ft.application.relations import RelationService
    from ft.domain.relations import RelationStatus

    engine, sessions, uow = _backend(tmp_path, backend)
    try:
        with uow as session:
            for name in ("境内账户", "境外账户"):
                session.accounts.add_raw({"name": name, "type": "cash", "currency": "CNY"})
            target = session._state().session.scalar(select(AccountModel).where(
                AccountModel.workspace_id == "normalized-transfer", AccountModel.name == "境外账户",
            ))
            assert target is not None
            session.account_aliases.add(
                alias_type="account_identifier", alias_value="123456780", account_id=target.id,
            )
            out_id = session.cashflows.add("cash", _row(
                account_name="境内账户", record_id="out", amount="-100.00", currency="CNY",
                record_type="transfer_out", record_subtype="cross_border_remittance",
                occurred_at="2026-05-24 13:47:00", counterparty_account="123456780",
                source_type="unrelated-a",
            ))
            in_id = session.cashflows.add("cash", _row(
                account_name="境外账户", record_id="in", amount="108.00", currency="HKD",
                record_type="transfer_in", record_subtype="ordinary_transfer",
                occurred_at="2026-05-24 13:47:07", source_type="unrelated-b",
            ))
            session.commit()

        assert RelationService(uow).check(seed_fact_ids=[out_id], trigger="manual_range").ok
        with sessions() as session:
            relation = _relation(session)
            assert relation is not None
            assert relation.status == RelationStatus.ACCEPTED.value
            assert {relation.primary_fact_id, relation.secondary_fact_id} == {out_id, in_id}
            assert relation.subtype == "cross_currency_remittance"
    finally:
        engine.dispose()


@pytest.mark.parametrize("backend", postgres_test_backend_params())
def test_cross_border_same_currency_uses_full_targeted_window(tmp_path, backend):
    from ft.adapters.relational.models import AccountModel
    from ft.application.relations import RelationService
    from ft.domain.relations import RelationStatus

    engine, sessions, uow = _backend(tmp_path, backend)
    try:
        with uow as session:
            for name in ("境内账户", "境外账户"):
                session.accounts.add_raw({"name": name, "type": "cash", "currency": "USD"})
            target = session._state().session.scalar(select(AccountModel).where(
                AccountModel.workspace_id == "normalized-transfer", AccountModel.name == "境外账户",
            ))
            assert target is not None
            session.account_aliases.add(
                alias_type="card_tail", alias_value="4245", account_id=target.id,
            )
            out_id = session.cashflows.add("cash", _row(
                account_name="境内账户", record_id="out", amount="-4000.00", currency="USD",
                record_type="transfer_out", record_subtype="cross_border_remittance",
                occurred_at="2026-01-23 11:38:22", counterparty_account="123456784245",
            ))
            in_id = session.cashflows.add("cash", _row(
                account_name="境外账户", record_id="in", amount="4000.00", currency="USD",
                record_type="transfer_in", record_subtype="ordinary_transfer",
                occurred_at="2026-01-26 02:45:44",
            ))
            session.commit()

        assert RelationService(uow).check(seed_fact_ids=[out_id], trigger="manual_range").ok
        with sessions() as session:
            relation = _relation(session)
            assert relation is not None
            assert relation.status == RelationStatus.ACCEPTED.value
            assert {relation.primary_fact_id, relation.secondary_fact_id} == {out_id, in_id}
            assert relation.subtype == "ordinary_transfer"
    finally:
        engine.dispose()


@pytest.mark.parametrize("backend", postgres_test_backend_params())
def test_internal_cross_currency_transfer_allows_the_same_account(tmp_path, backend):
    from ft.adapters.relational.models import AccountModel
    from ft.application.relations import RelationService
    from ft.domain.relations import RelationStatus

    engine, sessions, uow = _backend(tmp_path, backend)
    try:
        with uow as session:
            session.accounts.add_raw({"name": "多币种账户", "type": "cash", "currency": "CNY"})
            account = session._state().session.scalar(select(AccountModel).where(
                AccountModel.workspace_id == "normalized-transfer", AccountModel.name == "多币种账户",
            ))
            assert account is not None
            session.account_aliases.add(
                alias_type="account_identifier", alias_value="123456780", account_id=account.id,
            )
            out_id = session.cashflows.add("cash", _row(
                account_name="多币种账户", record_id="out", amount="-100.00", currency="CNY",
                record_type="transfer_out", record_subtype="internal_account_transfer",
                occurred_at="2026-05-24 13:47:00", counterparty_account="123456780",
            ))
            in_id = session.cashflows.add("cash", _row(
                account_name="多币种账户", record_id="in", amount="108.00", currency="HKD",
                record_type="transfer_in", record_subtype="internal_account_transfer",
                occurred_at="2026-05-24 13:47:07",
            ))
            session.commit()

        assert RelationService(uow).check(seed_fact_ids=[out_id], trigger="manual_range").ok
        with sessions() as session:
            relation = _relation(session)
            assert relation is not None
            assert relation.status == RelationStatus.ACCEPTED.value
            assert {relation.primary_fact_id, relation.secondary_fact_id} == {out_id, in_id}
            assert relation.subtype == "currency_exchange"
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
                AccountModel.workspace_id == "normalized-transfer", AccountModel.name == "目标账户",
            ))
            assert account is not None
            session.account_aliases.add(
                alias_type="account_identifier", alias_value="6222-0000-0000-1234", account_id=account.id,
            )
            with pytest.raises(ValueError, match="account_identifier"):
                session.account_aliases.add(
                    alias_type="account_identifier", alias_value="示例账户", account_id=account.id,
                )
            assert session.account_aliases.list()[0]["alias_value"] == "6222000000001234"
            session.commit()
    finally:
        engine.dispose()
