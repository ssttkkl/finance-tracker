"""Dual-backend contract tests for DFZQ import.

Constitution IV: Verify PostgreSQL and SQLite produce identical results.
"""
import pytest
from decimal import Decimal


@pytest.mark.skip(reason="T028: Implementation not yet complete")
@pytest.mark.parametrize("backend", ["postgresql", "sqlite"])
def test_dfzq_import_dual_backend(backend, backend_uow_factory):
    """Same DFZQ statement should produce identical results on both backends."""
    # Setup backend-specific UoW
    uow = backend_uow_factory(backend)

    from ft.adapters.relational.uow import ensure_workspace
    from ft.domain.accounts import AccountDTO

    workspace_id = "test_workspace"
    ensure_workspace(uow._session_factory, workspace_id, name="Test")

    with uow as session:
        session.accounts.add(AccountDTO("东方证券", "security", active=True))
        session.commit()

    from ft.application.investment_import import InvestmentImportService
    service = InvestmentImportService(uow)

    # Import statement
    result = service.import_statement(
        source="dfzq",
        source_path="tests/fixtures/dfzq/sample_statement.txt",
        account_name="东方证券",
    )

    assert result.ok is True
    assert result.count == 6

    # Query events
    with uow as session:
        events = session.investments.list()
        snapshot = session.snapshot.load()

    # Return for cross-backend comparison
    return {
        "backend": backend,
        "event_count": len(events),
        "total_deposit": sum(
            Decimal(e.get("to_amount", 0))
            for e in events
            if e.get("action") == "deposit"
        ),
        "positions": snapshot["accounts"]["security"]["东方证券"]["positions"],
        "batch_id": result.batch_id,
    }


@pytest.mark.skip(reason="T028: Implementation not yet complete")
def test_dfzq_backend_equivalence(postgresql_result, sqlite_result):
    """Assert PostgreSQL and SQLite produce identical results."""
    assert postgresql_result["event_count"] == sqlite_result["event_count"]
    assert postgresql_result["total_deposit"] == sqlite_result["total_deposit"]

    # Verify position equivalence
    pg_positions = postgresql_result["positions"]
    sqlite_positions = sqlite_result["positions"]

    assert set(pg_positions.keys()) == set(sqlite_positions.keys())

    for ticker in pg_positions.keys():
        pg_pos = pg_positions[ticker]
        sqlite_pos = sqlite_positions[ticker]

        assert Decimal(pg_pos["shares"]) == Decimal(sqlite_pos["shares"])
        assert Decimal(pg_pos["total_cost"]) == Decimal(sqlite_pos["total_cost"])
        assert pg_pos["cost_currency"] == sqlite_pos["cost_currency"]


@pytest.mark.skip(reason="T028: Implementation not yet complete")
@pytest.mark.parametrize("backend", ["postgresql", "sqlite"])
def test_dfzq_idempotency_dual_backend(backend, backend_uow_factory):
    """Idempotency should work identically on both backends."""
    uow = backend_uow_factory(backend)

    from ft.adapters.relational.uow import ensure_workspace
    from ft.domain.accounts import AccountDTO

    workspace_id = "test_workspace"
    ensure_workspace(uow._session_factory, workspace_id, name="Test")

    with uow as session:
        session.accounts.add(AccountDTO("东方证券", "security", active=True))
        session.commit()

    from ft.application.investment_import import InvestmentImportService
    service = InvestmentImportService(uow)

    # First import
    result1 = service.import_statement(
        source="dfzq",
        source_path="tests/fixtures/dfzq/sample_statement.txt",
        account_name="东方证券",
    )

    # Second import (duplicate)
    result2 = service.import_statement(
        source="dfzq",
        source_path="tests/fixtures/dfzq/sample_statement.txt",
        account_name="东方证券",
    )

    # Both backends should detect duplicate
    assert result2.ok is True
    assert result2.count == 0
    assert result2.duplicate is True
    assert result2.batch_id == result1.batch_id


@pytest.mark.skip(reason="T028: Implementation not yet complete")
@pytest.mark.parametrize("backend", ["postgresql", "sqlite"])
def test_dfzq_snapshot_validation_dual_backend(backend, backend_uow_factory):
    """Snapshot validation should work identically on both backends."""
    uow = backend_uow_factory(backend)

    from ft.adapters.relational.uow import ensure_workspace
    from ft.domain.accounts import AccountDTO

    workspace_id = "test_workspace"
    ensure_workspace(uow._session_factory, workspace_id, name="Test")

    with uow as session:
        session.accounts.add(AccountDTO("东方证券", "security", active=True))
        session.commit()

    from ft.application.investment_import import InvestmentImportService
    service = InvestmentImportService(uow)

    # Normal import should succeed on both
    result = service.import_statement(
        source="dfzq",
        source_path="tests/fixtures/dfzq/sample_statement.txt",
        account_name="东方证券",
    )

    assert result.ok is True

    # TODO: Test with NaN-producing fixture
    # Both backends should reject with ValueError


# Pytest fixtures for parametrized backends
@pytest.fixture
def backend_uow_factory():
    """Factory to create UoW for specified backend."""

    def factory(backend: str):
        if backend == "postgresql":
            # TODO: Setup PostgreSQL test database
            pass
        elif backend == "sqlite":
            # TODO: Setup SQLite test database
            pass
        else:
            raise ValueError(f"Unknown backend: {backend}")

    return factory


@pytest.fixture
def postgresql_result(backend_uow_factory):
    """Run test on PostgreSQL and return results."""
    # TODO: Implementation
    pass


@pytest.fixture
def sqlite_result(backend_uow_factory):
    """Run test on SQLite and return results."""
    # TODO: Implementation
    pass
