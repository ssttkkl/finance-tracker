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


def _row(
    *, account_name, record_id, amount, record_type, occurred_at,
    counterparty_account="", source_type="fixture", currency="CNY", note="",
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
        "note": note or ("转账支取" if amount.startswith("-") else "转账存入"),
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
            assert not hasattr(relation, "evidence_json")
    finally:
        engine.dispose()


@pytest.mark.parametrize("backend", postgres_test_backend_params())
@pytest.mark.parametrize("counterparty_account", ["123456781", "1234567812"])
def test_icbc_asia_currency_subaccount_matches_registered_canonical_identifier(
    tmp_path, backend, counterparty_account,
):
    from ft.adapters.relational.models import AccountModel, TransactionRelationModel
    from ft.application.relations import RelationService
    from ft.domain.relations import RelationKind, RelationStatus

    engine, sessions, uow = _backend(tmp_path, backend)
    try:
        with uow as session:
            for name in ("来源账户", "错误候选", "工银亚洲账户"):
                session.accounts.add_raw({"name": name, "type": "cash", "currency": "HKD"})
            target = session._state().session.scalar(select(AccountModel).where(
                AccountModel.workspace_id == "counterparty-account",
                AccountModel.name == "工银亚洲账户",
            ))
            assert target is not None
            session.account_aliases.add(
                alias_type="account_identifier", alias_value="123456780", account_id=target.id,
            )
            out_id = session.cashflows.add("cash", _row(
                account_name="来源账户", record_id="out", amount="-100.00",
                record_type="transfer_out", occurred_at="2026-01-01 10:00:00",
                counterparty_account=counterparty_account,
                source_type="icbc_asia_current_account",
            ))
            session.cashflows.add("cash", _row(
                account_name="错误候选", record_id="wrong", amount="100.00",
                record_type="transfer_in", occurred_at="2026-01-01 10:00:01",
            ))
            target_fact_id = session.cashflows.add("cash", _row(
                account_name="工银亚洲账户", record_id="target", amount="100.00",
                record_type="transfer_in", occurred_at="2026-01-01 10:00:02",
            ))
            session.commit()

        result = RelationService(uow).check(seed_fact_ids=[out_id], trigger="manual_range")
        assert result.ok is True

        with sessions() as session:
            relation = session.scalar(select(TransactionRelationModel).where(
                TransactionRelationModel.workspace_id == "counterparty-account",
                TransactionRelationModel.kind == RelationKind.TRANSFER_PAIR.value,
                TransactionRelationModel.status == RelationStatus.ACCEPTED.value,
            ))
            assert relation is not None
            assert {relation.primary_fact_id, relation.secondary_fact_id} == {out_id, target_fact_id}
    finally:
        engine.dispose()


@pytest.mark.parametrize("backend", postgres_test_backend_params())
def test_non_icbc_asia_account_prefix_does_not_match_registered_identifier(tmp_path, backend):
    from ft.adapters.relational.models import AccountModel, TransactionRelationModel
    from ft.application.relations import RelationService
    from ft.domain.relations import RelationKind

    engine, sessions, uow = _backend(tmp_path, backend)
    try:
        with uow as session:
            for name in ("来源账户", "目标账户"):
                session.accounts.add_raw({"name": name, "type": "cash", "currency": "CNY"})
            target = session._state().session.scalar(select(AccountModel).where(
                AccountModel.workspace_id == "counterparty-account",
                AccountModel.name == "目标账户",
            ))
            assert target is not None
            session.account_aliases.add(
                alias_type="account_identifier", alias_value="123456780", account_id=target.id,
            )
            out_id = session.cashflows.add("cash", _row(
                account_name="来源账户", record_id="out", amount="-100.00",
                record_type="transfer_out", occurred_at="2026-01-01 10:00:00",
                counterparty_account="123456781", source_type="icbc_debit",
            ))
            session.cashflows.add("cash", _row(
                account_name="目标账户", record_id="target", amount="100.00",
                record_type="transfer_in", occurred_at="2026-01-01 10:00:02",
            ))
            session.commit()

        result = RelationService(uow).check(seed_fact_ids=[out_id], trigger="manual_range")
        assert result.ok is True

        with sessions() as session:
            relation = session.scalar(select(TransactionRelationModel).where(
                TransactionRelationModel.workspace_id == "counterparty-account",
                TransactionRelationModel.kind == RelationKind.TRANSFER_PAIR.value,
            ))
            assert relation is None
    finally:
        engine.dispose()


@pytest.mark.parametrize("backend", postgres_test_backend_params())
def test_icbc_asia_canonical_identifier_without_currency_digit_does_not_match(tmp_path, backend):
    from ft.adapters.relational.models import AccountModel, TransactionRelationModel
    from ft.application.relations import RelationService
    from ft.domain.relations import RelationKind

    engine, sessions, uow = _backend(tmp_path, backend)
    try:
        with uow as session:
            for name in ("来源账户", "工银亚洲账户"):
                session.accounts.add_raw({"name": name, "type": "cash", "currency": "HKD"})
            target = session._state().session.scalar(select(AccountModel).where(
                AccountModel.workspace_id == "counterparty-account",
                AccountModel.name == "工银亚洲账户",
            ))
            assert target is not None
            session.account_aliases.add(
                alias_type="account_identifier", alias_value="123456780", account_id=target.id,
            )
            out_id = session.cashflows.add("cash", _row(
                account_name="来源账户", record_id="out", amount="-100.00",
                record_type="transfer_out", occurred_at="2026-01-01 10:00:00",
                counterparty_account="12345678", source_type="icbc_asia_current_account",
            ))
            session.cashflows.add("cash", _row(
                account_name="工银亚洲账户", record_id="target", amount="100.00",
                record_type="transfer_in", occurred_at="2026-01-01 10:00:02",
            ))
            session.commit()

        result = RelationService(uow).check(seed_fact_ids=[out_id], trigger="manual_range")
        assert result.ok is True

        with sessions() as session:
            relation = session.scalar(select(TransactionRelationModel).where(
                TransactionRelationModel.workspace_id == "counterparty-account",
                TransactionRelationModel.kind == RelationKind.TRANSFER_PAIR.value,
            ))
            assert relation is None
    finally:
        engine.dispose()


@pytest.mark.parametrize("backend", postgres_test_backend_params())
def test_icbc_cross_border_remittance_uses_canonical_account_without_persisting_evidence(
    tmp_path, backend,
):
    from ft.adapters.relational.models import AccountModel, TransactionRelationModel
    from ft.application.relations import RelationService
    from ft.domain.relations import RelationKind, RelationStatus

    engine, sessions, uow = _backend(tmp_path, backend)
    try:
        with uow as session:
            for name in ("工行借记卡", "工银亚洲账户"):
                session.accounts.add_raw({"name": name, "type": "cash", "currency": "CNY"})
            asia = session._state().session.scalar(select(AccountModel).where(
                AccountModel.workspace_id == "counterparty-account",
                AccountModel.name == "工银亚洲账户",
            ))
            assert asia is not None
            session.account_aliases.add(
                alias_type="account_identifier", alias_value="123456780", account_id=asia.id,
            )
            out_id = session.cashflows.add("cash", _row(
                account_name="工行借记卡", record_id="cross-border-out", amount="-100.00",
                record_type="transfer_out", occurred_at="2026-05-24 13:44:06",
                counterparty_account="123456781", source_type="icbc_debit", note="跨境汇款",
            ))
            in_id = session.cashflows.add("cash", _row(
                account_name="工银亚洲账户", record_id="asia-in", amount="100.00",
                record_type="transfer_in", occurred_at="2026-05-24 13:44:13",
                source_type="icbc_asia_current_account", note="FPS Transfer",
            ))
            session.commit()

        result = RelationService(uow).check(seed_fact_ids=[in_id], trigger="manual_range")
        assert result.ok is True

        with sessions() as session:
            relation = session.scalar(select(TransactionRelationModel).where(
                TransactionRelationModel.workspace_id == "counterparty-account",
                TransactionRelationModel.kind == RelationKind.TRANSFER_PAIR.value,
                TransactionRelationModel.status == RelationStatus.ACCEPTED.value,
            ))
            assert relation is not None
            assert {relation.primary_fact_id, relation.secondary_fact_id} == {out_id, in_id}
            assert relation.rule_id == "transfer_pair.icbc_debit.icbc_asia.cross_border.v1"
            assert not hasattr(relation, "evidence_json")
            assert relation.candidate_fact_ids == []
    finally:
        engine.dispose()


@pytest.mark.parametrize("backend", postgres_test_backend_params())
def test_payment_method_full_account_identifier_verifies_mirror_without_leaking_value(tmp_path, backend):
    from ft.adapters.relational.models import AccountModel, TransactionRelationModel
    from ft.application.relations import RelationService
    from ft.domain.relations import RelationKind, RelationStatus

    engine, sessions, uow = _backend(tmp_path, backend)
    try:
        with uow as session:
            session.accounts.add_raw({"name": "结算账户", "type": "cash", "currency": "CNY"})
            account = session._state().session.scalar(select(AccountModel).where(
                AccountModel.workspace_id == "counterparty-account",
                AccountModel.name == "结算账户",
            ))
            assert account is not None
            session.account_aliases.add(
                alias_type="account_identifier",
                alias_value="6222000000001234",
                account_id=account.id,
            )
            platform_id = session.cashflows.add("cash", {
                **_row(
                    account_name="结算账户", record_id="platform", amount="-100.00",
                    record_type="consumption", occurred_at="2026-01-01 10:00:00",
                ),
                "source_type": "alipay",
                "source_payload": {"收/付款方式": "招商银行储蓄卡（6222 0000 0000 1234）"},
                "note": "消费",
            })
            bank_id = session.cashflows.add("cash", {
                **_row(
                    account_name="结算账户", record_id="bank", amount="-100.00",
                    record_type="consumption", occurred_at="2026-01-01 10:00:05",
                ),
                "source_type": "icbc_debit",
                "source_payload": {"摘要": "扣款"},
                "note": "扣款",
            })
            session.commit()

        result = RelationService(uow).check(seed_fact_ids=[platform_id], trigger="manual_range")
        assert result.ok is True

        with sessions() as session:
            relation = session.scalar(select(TransactionRelationModel).where(
                TransactionRelationModel.workspace_id == "counterparty-account",
                TransactionRelationModel.kind == RelationKind.PAYMENT_MIRROR.value,
                TransactionRelationModel.status == RelationStatus.ACCEPTED.value,
            ))
            assert relation is not None
            assert {relation.primary_fact_id, relation.secondary_fact_id} == {platform_id, bank_id}
            assert not hasattr(relation, "evidence_json")
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
