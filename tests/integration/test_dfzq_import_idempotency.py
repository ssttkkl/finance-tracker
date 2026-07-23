"""Idempotency tests for DFZQ import.

Constitution III: Test-first - verify duplicate detection behavior.
"""
import pytest
from decimal import Decimal


@pytest.mark.skip(reason="T027: Implementation not yet complete")
def test_dfzq_import_duplicate_file_returns_success(relational_uow):
    """Importing same file twice should return success with count=0."""
    from ft.adapters.relational.uow import ensure_workspace
    from ft.domain.accounts import AccountDTO

    workspace_id = "test_workspace"
    ensure_workspace(relational_uow._session_factory, workspace_id, name="Test")

    with relational_uow as uow:
        uow.accounts.add(AccountDTO("东方证券", "security", active=True))
        uow.commit()

    from ft.application.investment_import import InvestmentImportService
    service = InvestmentImportService(relational_uow)

    # First import
    result1 = service.import_statement(
        source="dfzq",
        source_path="tests/fixtures/dfzq/sample_statement.txt",
        account_name="东方证券",
    )

    assert result1.ok is True
    assert result1.count == 6
    batch_id1 = result1.batch_id

    # Second import (same file)
    result2 = service.import_statement(
        source="dfzq",
        source_path="tests/fixtures/dfzq/sample_statement.txt",
        account_name="东方证券",
    )

    assert result2.ok is True
    assert result2.count == 0  # No new events
    assert result2.duplicate is True
    assert result2.batch_id == batch_id1  # Same batch ID

    # Verify event count unchanged
    with relational_uow as uow:
        events = uow.investments.list()
        assert len(events) == 6


@pytest.mark.skip(reason="T027: Implementation not yet complete")
def test_dfzq_import_duplicate_via_source_digest(relational_uow, tmp_path):
    """Duplicate detection via source_digest (file content hash)."""
    from ft.adapters.relational.uow import ensure_workspace
    from ft.domain.accounts import AccountDTO

    workspace_id = "test_workspace"
    ensure_workspace(relational_uow._session_factory, workspace_id, name="Test")

    with relational_uow as uow:
        uow.accounts.add(AccountDTO("东方证券", "security", active=True))
        uow.commit()

    from ft.application.investment_import import InvestmentImportService
    service = InvestmentImportService(relational_uow)

    # Import original file
    original_path = "tests/fixtures/dfzq/sample_statement.txt"
    result1 = service.import_statement(
        source="dfzq",
        source_path=original_path,
        account_name="东方证券",
    )

    assert result1.ok is True
    batch_id1 = result1.batch_id

    # Copy file to different location
    import shutil
    copied_path = tmp_path / "copied_statement.txt"
    shutil.copy(original_path, copied_path)

    # Import copied file (different path, same content)
    result2 = service.import_statement(
        source="dfzq",
        source_path=str(copied_path),
        account_name="东方证券",
    )

    # Should detect duplicate via content hash
    assert result2.ok is True
    assert result2.count == 0
    assert result2.duplicate is True
    assert result2.batch_id == batch_id1


@pytest.mark.skip(reason="T027: Implementation not yet complete")
def test_dfzq_import_modified_file_creates_new_batch(relational_uow, tmp_path):
    """Modified file should create new batch (different source_digest)."""
    from ft.adapters.relational.uow import ensure_workspace
    from ft.domain.accounts import AccountDTO

    workspace_id = "test_workspace"
    ensure_workspace(relational_uow._session_factory, workspace_id, name="Test")

    with relational_uow as uow:
        uow.accounts.add(AccountDTO("东方证券", "security", active=True))
        uow.commit()

    from ft.application.investment_import import InvestmentImportService
    service = InvestmentImportService(relational_uow)

    # Import original
    result1 = service.import_statement(
        source="dfzq",
        source_path="tests/fixtures/dfzq/sample_statement.txt",
        account_name="东方证券",
    )

    batch_id1 = result1.batch_id

    # Create modified file
    modified_path = tmp_path / "modified_statement.txt"
    with open("tests/fixtures/dfzq/sample_statement.txt", "r", encoding="utf-8") as f:
        content = f.read()

    with open(modified_path, "w", encoding="utf-8") as f:
        f.write(content + "\n\n")  # Add whitespace

    # Import modified file
    result2 = service.import_statement(
        source="dfzq",
        source_path=str(modified_path),
        account_name="东方证券",
    )

    # Different digest → new batch
    assert result2.ok is True
    assert result2.batch_id != batch_id1
    # But may fail on source_identity collision (same transactions)


@pytest.mark.skip(reason="T027: Implementation not yet complete")
def test_dfzq_import_overlapping_records_fails(relational_uow, tmp_path):
    """Two different files with overlapping transactions should fail on source_identity collision."""
    from ft.adapters.relational.uow import ensure_workspace
    from ft.domain.accounts import AccountDTO

    workspace_id = "test_workspace"
    ensure_workspace(relational_uow._session_factory, workspace_id, name="Test")

    with relational_uow as uow:
        uow.accounts.add(AccountDTO("东方证券", "security", active=True))
        uow.commit()

    from ft.application.investment_import import InvestmentImportService
    service = InvestmentImportService(relational_uow)

    # Import file 1
    result1 = service.import_statement(
        source="dfzq",
        source_path="tests/fixtures/dfzq/sample_statement.txt",
        account_name="东方证券",
    )

    assert result1.ok is True

    # Create file 2 with overlapping transaction (same date, ticker, amount)
    # This would have same source_identity but different source_digest
    # Should fail with unique constraint violation or explicit error

    # TODO: Create overlapping fixture and verify error message includes batch_id reference
