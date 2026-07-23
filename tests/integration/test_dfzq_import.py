"""Integration tests for DFZQ import flow.

Constitution III: Test-first - these tests define the expected behavior of the
complete import pipeline before implementation.
"""
import pytest
from decimal import Decimal


@pytest.mark.skip(reason="T026: Implementation not yet complete - write test first")
def test_dfzq_import_full_flow(tmp_path, relational_uow):
    """Full DFZQ import: PDF → batch → events → snapshot."""
    # Setup: Create workspace and account
    from ft.adapters.relational.uow import ensure_workspace
    from ft.domain.accounts import AccountDTO

    workspace_id = "test_workspace"
    ensure_workspace(relational_uow._session_factory, workspace_id, name="Test Workspace")

    with relational_uow as uow:
        uow.accounts.add(AccountDTO("东方证券", "security", active=True))
        uow.commit()

    # Import statement
    from ft.application.investment_import import InvestmentImportService

    service = InvestmentImportService(relational_uow)
    statement_path = "tests/fixtures/dfzq/sample_statement.txt"

    result = service.import_statement(
        source="dfzq",
        source_path=statement_path,
        account_name="东方证券",
    )

    # Verify result
    assert result.ok is True
    assert result.count == 6  # 5 transactions + 1 CHECKIN
    assert result.batch_id is not None

    # Verify events created
    with relational_uow as uow:
        events = uow.investments.list()
        assert len(events) == 6

        # Verify first event (deposit)
        assert events[0]["action"] == "deposit"
        assert Decimal(events[0]["to_amount"]) == Decimal("10000.00")

        # Verify snapshot updated
        snapshot = uow.snapshot.load()
        positions = snapshot["accounts"]["security"]["东方证券"]["positions"]

        # Final balance should match CHECKIN amount
        assert "cny" in positions
        # Cash after all transactions (verify with CHECKIN)


@pytest.mark.skip(reason="T026: Implementation not yet complete")
def test_dfzq_import_creates_raw_records(relational_uow):
    """Import should create raw_records with provenance."""
    from ft.adapters.relational.uow import ensure_workspace
    from ft.domain.accounts import AccountDTO

    workspace_id = "test_workspace"
    ensure_workspace(relational_uow._session_factory, workspace_id, name="Test")

    with relational_uow as uow:
        uow.accounts.add(AccountDTO("东方证券", "security", active=True))
        uow.commit()

    from ft.application.investment_import import InvestmentImportService
    service = InvestmentImportService(relational_uow)

    result = service.import_statement(
        source="dfzq",
        source_path="tests/fixtures/dfzq/sample_statement.txt",
        account_name="东方证券",
    )

    assert result.ok is True

    # Verify raw_records created with source_identity
    # TODO: Add raw_records query method to UoW
    # raw_records = uow.imports.list_raw_records(batch_id=result.batch_id)
    # assert len(raw_records) == 6
    # assert all(r["source_identity"].startswith("dfzq:") for r in raw_records)


@pytest.mark.skip(reason="T026: Implementation not yet complete")
def test_dfzq_import_validates_snapshot(relational_uow):
    """Import should call validate_investment_snapshot before commit."""
    from ft.adapters.relational.uow import ensure_workspace
    from ft.domain.accounts import AccountDTO

    workspace_id = "test_workspace"
    ensure_workspace(relational_uow._session_factory, workspace_id, name="Test")

    with relational_uow as uow:
        uow.accounts.add(AccountDTO("东方证券", "security", active=True))
        uow.commit()

    from ft.application.investment_import import InvestmentImportService
    service = InvestmentImportService(relational_uow)

    # Normal import should succeed
    result = service.import_statement(
        source="dfzq",
        source_path="tests/fixtures/dfzq/sample_statement.txt",
        account_name="东方证券",
    )

    assert result.ok is True

    # TODO: Test with corrupted data that produces NaN
    # Should raise ValueError from validate_investment_snapshot


@pytest.mark.skip(reason="T026: Implementation not yet complete")
def test_dfzq_import_account_not_found(relational_uow):
    """Import should fail with clear error if account doesn't exist."""
    from ft.adapters.relational.uow import ensure_workspace

    workspace_id = "test_workspace"
    ensure_workspace(relational_uow._session_factory, workspace_id, name="Test")

    from ft.application.investment_import import InvestmentImportService
    service = InvestmentImportService(relational_uow)

    result = service.import_statement(
        source="dfzq",
        source_path="tests/fixtures/dfzq/sample_statement.txt",
        account_name="NonExistentAccount",
    )

    assert result.ok is False
    assert "not found" in result.message.lower()


@pytest.mark.skip(reason="T026: Implementation not yet complete")
def test_dfzq_import_wrong_account_type(relational_uow):
    """Import should fail if account is not security/crypto type."""
    from ft.adapters.relational.uow import ensure_workspace
    from ft.domain.accounts import AccountDTO

    workspace_id = "test_workspace"
    ensure_workspace(relational_uow._session_factory, workspace_id, name="Test")

    with relational_uow as uow:
        uow.accounts.add(AccountDTO("银行账户", "cash", active=True))
        uow.commit()

    from ft.application.investment_import import InvestmentImportService
    service = InvestmentImportService(relational_uow)

    result = service.import_statement(
        source="dfzq",
        source_path="tests/fixtures/dfzq/sample_statement.txt",
        account_name="银行账户",
    )

    assert result.ok is False
    assert "security" in result.message.lower() or "crypto" in result.message.lower()


@pytest.mark.skip(reason="T026: Implementation not yet complete")
def test_dfzq_import_transaction_atomicity(relational_uow):
    """Import failure should rollback all changes (no partial facts)."""
    from ft.adapters.relational.uow import ensure_workspace
    from ft.domain.accounts import AccountDTO

    workspace_id = "test_workspace"
    ensure_workspace(relational_uow._session_factory, workspace_id, name="Test")

    with relational_uow as uow:
        uow.accounts.add(AccountDTO("东方证券", "security", active=True))
        uow.commit()

    from ft.application.investment_import import InvestmentImportService
    service = InvestmentImportService(relational_uow)

    # TODO: Create corrupted fixture that fails mid-import
    # result should be ok=False and no events/raw_records should persist
