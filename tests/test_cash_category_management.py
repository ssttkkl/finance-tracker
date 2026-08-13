"""收支分类目录的 Application Service 合同。"""
from __future__ import annotations

from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config


def _sessions(tmp_path):
    from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace

    database = tmp_path / "cash-categories.db"
    root = Path(__file__).parents[1]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", f"sqlite+pysqlite:///{database}")
    command.upgrade(config, "head")
    engine = create_relational_engine(f"sqlite+pysqlite:///{database}")
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "category-workspace")
    ensure_workspace(sessions, "other-workspace")
    return engine, sessions


def test_category_tree_enforces_workspace_depth_and_sibling_name_rules(tmp_path):
    from ft.application.cash_categories import CashCategoryService

    engine, sessions = _sessions(tmp_path)
    try:
        service = CashCategoryService(sessions, "category-workspace")
        root = service.create(name="生活")
        child = service.create(name="日常", parent_id=root["id"], expected_revision=root["revision"])
        assert child["path"] == ["生活", "日常"]
        assert child["depth"] == 2
        assert service.list()["revision"] == 2

        with pytest.raises(ValueError, match="category.duplicate_name"):
            service.create(name=" 日常 ", parent_id=root["id"], expected_revision=child["revision"])

        current = child
        for name in ("三级", "四级", "五级"):
            current = service.create(name=name, parent_id=current["id"], expected_revision=current["revision"])
        with pytest.raises(ValueError, match="category.depth_limit"):
            service.create(name="六级", parent_id=current["id"], expected_revision=current["revision"])

        with pytest.raises(ValueError, match="category.cycle"):
            service.move(root["id"], parent_id=current["id"], expected_revision=current["revision"])

            with pytest.raises(ValueError, match="category.not_found"):
                CashCategoryService(sessions, "other-workspace").move(
                root["id"], parent_id=None, expected_revision=0,
                )
    finally:
        engine.dispose()


def test_category_move_keeps_stable_id_and_rewrites_descendant_paths(tmp_path):
    from ft.application.cash_categories import CashCategoryService

    engine, sessions = _sessions(tmp_path)
    try:
        service = CashCategoryService(sessions, "category-workspace")
        work = service.create(name="工作")
        food = service.create(name="餐饮")
        lunch = service.create(name="午餐", parent_id=food["id"], expected_revision=food["revision"])

        moved = service.move(food["id"], parent_id=work["id"], expected_revision=lunch["revision"])
        assert moved["id"] == food["id"]
        assert moved["path"] == ["工作", "餐饮"]
        listed = service.list()
        descendant = next(item for item in listed["items"] if item["id"] == lunch["id"])
        assert descendant["path"] == ["工作", "餐饮", "午餐"]
    finally:
        engine.dispose()


def test_category_edit_and_delete_report_usage_and_require_current_revision(tmp_path):
    from datetime import datetime, timezone
    from decimal import Decimal

    from ft.adapters.relational.models import AccountModel, CashTransactionModel
    from ft.application.cash_categories import CashCategoryService

    engine, sessions = _sessions(tmp_path)
    try:
        service = CashCategoryService(sessions, "category-workspace")
        category = service.create(name="餐饮")
        with sessions.begin() as session:
            session.add(AccountModel(id=901, workspace_id="category-workspace", name="账户", type="cash"))
            session.add(CashTransactionModel(
                id=902, workspace_id="category-workspace", account_id=901,
                occurred_at=datetime(2026, 8, 12, tzinfo=timezone.utc), amount=Decimal("-10"),
                currency="CNY", counterparty="商家", category_id=category["id"],
                record_type="consumption", record_subtype="not_applicable",
            ))
        edited = service.update(category["id"], name="外食", description="午餐和晚餐", expected_revision=category["revision"])
        assert edited["name"] == "外食"
        assert edited["path"] == ["外食"]

        impact = service.deletion_impact(category["id"])
        assert impact["direct_usage_count"] == 1
        with pytest.raises(ValueError, match="category.revision_conflict"):
            service.delete(category["id"], expected_revision=category["revision"], expected_category_revision=category["revision"], expected_usage_count=1, confirmed=True)

        latest = service.list()["revision"]
        with pytest.raises(ValueError, match="category.delete_confirmation_required"):
            service.delete(category["id"], expected_revision=latest, expected_category_revision=impact["category_revision"], expected_usage_count=1, confirmed=False)
        removed = service.delete(category["id"], expected_revision=latest, expected_category_revision=impact["category_revision"], expected_usage_count=1, confirmed=True)
        assert removed["cleared_transaction_count"] == 1
        with sessions() as session:
            assert session.get(CashTransactionModel, 902).category_id is None
    finally:
        engine.dispose()


def test_classifying_a_projection_updates_all_members_and_batches_are_atomic(tmp_path):
    from datetime import datetime, timezone
    from decimal import Decimal

    from ft.adapters.relational.models import (
        AccountModel, CashProjectionDatasetModel, CashProjectionMemberModel,
        CashProjectionModel, CashProjectionStateModel, CashTransactionModel,
    )
    from ft.application.cash_categories import CashCategoryService
    from ft.application.cash_classification import CashClassificationService

    engine, sessions = _sessions(tmp_path)
    try:
        categories = CashCategoryService(sessions, "category-workspace")
        dining = categories.create(name="餐饮")
        shopping = categories.create(name="购物", expected_revision=dining["revision"])
        now = datetime(2026, 8, 12, tzinfo=timezone.utc)
        with sessions.begin() as session:
            session.add(AccountModel(id=1001, workspace_id="category-workspace", name="分类账户", type="cash"))
            session.add(CashProjectionStateModel(
                workspace_id="category-workspace", active_dataset_id="dataset-1", projection_version=7,
                source_revision=0, availability="ready", last_build_status="succeeded",
                projection_count=2, member_count=3, updated_at=now,
            ))
            session.add(CashProjectionDatasetModel(
                id="dataset-1", workspace_id="category-workspace", state="active", source_revision=0,
                source_digest="digest", rules_version="cash-projection-v1", created_at=now, published_at=now,
            ))
            session.flush()
            for transaction_id in (1101, 1102, 1103):
                session.add(CashTransactionModel(
                    id=transaction_id, workspace_id="category-workspace", account_id=1001,
                    occurred_at=now, amount=Decimal("-10"), currency="CNY", counterparty="商家",
                    category_id=dining["id"], record_type="consumption", record_subtype="not_applicable",
                ))
            session.flush()
            session.add_all((
                CashProjectionModel(
                    id=1201, workspace_id="category-workspace", dataset_id="dataset-1", projection_id="cash:1101",
                    root_cash_transaction_id=1101, economic_type="expense", net_amount=Decimal("-10"), currency="CNY",
                    occurred_at=now, account_id=1001, counterparty="商家", category_id=dining["id"], category_path=dining["path"][0],
                    visible=True, member_count=2, accepted_relation_count=0, built_projection_version=7,
                ),
                CashProjectionModel(
                    id=1202, workspace_id="category-workspace", dataset_id="dataset-1", projection_id="cash:1103",
                    root_cash_transaction_id=1103, economic_type="expense", net_amount=Decimal("-10"), currency="CNY",
                    occurred_at=now, account_id=1001, counterparty="商家", category_id=dining["id"], category_path=dining["path"][0],
                    visible=True, member_count=1, accepted_relation_count=0, built_projection_version=7,
                ),
            ))
            session.flush()
            session.add_all((
                CashProjectionMemberModel(
                    workspace_id="category-workspace", dataset_id="dataset-1", projection_row_id=1201,
                    cash_transaction_id=1101, roles_json=["root"], ordinal=0,
                ),
                CashProjectionMemberModel(
                    workspace_id="category-workspace", dataset_id="dataset-1", projection_row_id=1201,
                    cash_transaction_id=1102, roles_json=["mirror"], ordinal=1,
                ),
                CashProjectionMemberModel(
                    workspace_id="category-workspace", dataset_id="dataset-1", projection_row_id=1202,
                    cash_transaction_id=1103, roles_json=["root"], ordinal=0,
                ),
            ))

        classifier = CashClassificationService(sessions, "category-workspace")
        result = classifier.set_category(projection_ids=["cash:1101"], projection_version=7, category_id=shopping["id"])
        assert result["updated_transaction_count"] == 2
        with sessions() as session:
            assert session.scalars(
                __import__("sqlalchemy", fromlist=["select"]).select(CashTransactionModel.category_id)
                .where(CashTransactionModel.id.in_([1101, 1102]))
            ).all() == [shopping["id"], shopping["id"]]

        with pytest.raises(ValueError, match="projection.version_conflict"):
            classifier.set_category(projection_ids=["cash:1101", "cash:1103"], projection_version=6, category_id=None)
        with sessions() as session:
            assert session.scalars(
                __import__("sqlalchemy", fromlist=["select"]).select(CashTransactionModel.category_id)
                .where(CashTransactionModel.id.in_([1101, 1102, 1103])).order_by(CashTransactionModel.id)
            ).all() == [shopping["id"], shopping["id"], dining["id"]]
    finally:
        engine.dispose()


def test_category_reorder_changes_sibling_order_and_requires_current_revision(tmp_path):
    from ft.application.cash_categories import CashCategoryService

    engine, sessions = _sessions(tmp_path)
    try:
        service = CashCategoryService(sessions, "category-workspace")
        first = service.create(name="第一项")
        second = service.create(name="第二项", expected_revision=first["revision"])

        moved = service.reorder(second["id"], direction="before", expected_revision=second["revision"])

        assert [item["name"] for item in service.list()["items"]] == ["第二项", "第一项"]
        assert moved["name"] == "第二项"
        with pytest.raises(ValueError, match="category.revision_conflict"):
            service.reorder(first["id"], direction="after", expected_revision=second["revision"])
    finally:
        engine.dispose()


def test_category_move_rejects_duplicate_destination_sibling(tmp_path):
    from ft.application.cash_categories import CashCategoryService

    engine, sessions = _sessions(tmp_path)
    try:
        service = CashCategoryService(sessions, "category-workspace")
        destination = service.create(name="目标")
        source = service.create(name="来源", expected_revision=destination["revision"])
        service.create(name="重复", parent_id=destination["id"], expected_revision=source["revision"])
        duplicate = service.create(name="重复", expected_revision=service.list()["revision"])

        with pytest.raises(ValueError, match="category.duplicate_name"):
            service.move(duplicate["id"], parent_id=destination["id"], expected_revision=duplicate["revision"])
    finally:
        engine.dispose()


def test_relation_maintenance_adopts_display_root_category_for_all_members(tmp_path):
    from datetime import datetime, timezone
    from decimal import Decimal

    from ft.adapters.relational.models import (
        AccountModel, CashCategoryModel, CashTransactionModel, TransactionRelationModel,
    )
    from ft.application.cash_categories import CashCategoryService
    from ft.application.cash_projections import CashProjectionService

    engine, sessions = _sessions(tmp_path)
    try:
        categories = CashCategoryService(sessions, "category-workspace")
        base = categories.create(name="根分类")
        other = categories.create(name="其他", expected_revision=base["revision"])
        now = datetime(2026, 8, 12, tzinfo=timezone.utc)
        with sessions.begin() as session:
            session.add(AccountModel(id=2001, workspace_id="category-workspace", name="关系账户", type="cash"))
            session.add_all((
                CashTransactionModel(
                    id=2101, workspace_id="category-workspace", account_id=2001,
                    occurred_at=now, amount=Decimal("-10"), currency="CNY", counterparty="商家",
                    category_id=base["id"], record_type="consumption", record_subtype="not_applicable",
                ),
                CashTransactionModel(
                    id=2102, workspace_id="category-workspace", account_id=2001,
                    occurred_at=now, amount=Decimal("-10"), currency="CNY", counterparty="商家",
                    category_id=other["id"], record_type="consumption", record_subtype="not_applicable",
                ),
                TransactionRelationModel(
                    workspace_id="category-workspace", kind="payment_mirror", subtype="",
                    primary_fact_id=2101, secondary_fact_id=2102,
                    primary_fact_type="cash", secondary_fact_type="cash",
                    ordered_fact_a=2101, ordered_fact_b=2102, anchor_fact_id=2101,
                    status="accepted", created_by="fixture",
                ),
            ))
        CashProjectionService(sessions, "category-workspace").rebuild()
        with sessions.begin() as session:
            CashProjectionService.maintain_if_ready_in_session(session, "category-workspace", {2101, 2102})
        with sessions() as session:
            rows = session.query(CashTransactionModel.category_id).filter(
                CashTransactionModel.id.in_((2101, 2102)),
            ).order_by(CashTransactionModel.id).all()
            assert [row[0] for row in rows] == [base["id"], base["id"]]
    finally:
        engine.dispose()
