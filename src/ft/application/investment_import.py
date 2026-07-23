"""Investment statement import service.

Orchestrates import flow: batch → raw_records → investment_events → snapshot
with atomic transaction guarantees.
"""
from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Any

from ft.domain.application import OperationResult
from ft.domain.investment_projection import apply_investment_event
from ft.domain.investment_validation import validate_investment_snapshot


class InvestmentImportService:
    """Application service for importing investment statements.

    Constitution I: Ensures idempotency, provenance, and transaction atomicity.
    Constitution III: All behavior tested via integration and contract tests.
    Constitution IV: Works identically on PostgreSQL and SQLite.
    """

    def __init__(self, unit_of_work):
        self._uow = unit_of_work

    def import_statement(
        self,
        source: str,
        source_path: str | Path,
        account_name: str,
        currency: str = "CNY",
        password: str = None,
    ) -> OperationResult:
        """Import investment statement from file.

        Args:
            source: Parser identifier ('dfzq', 'binance', 'okx', 'polymarket')
            source_path: Path to statement file
            account_name: Target account name
            currency: Default currency (default: CNY)
            password: PDF password for encrypted statements (optional)

        Returns:
            OperationResult with batch_id, count, and duplicate flag

        Constitution I: Atomic transaction - all or nothing.
        """
        source_path = Path(source_path)

        # Read file content for digest calculation
        try:
            file_content = source_path.read_bytes()
        except FileNotFoundError:
            return OperationResult(
                ok=False,
                message=f"File not found: {source_path}",
            )
        except Exception as e:
            return OperationResult(
                ok=False,
                message=f"Failed to read file: {e}",
            )

        # Calculate source_digest for idempotency
        source_digest = f"sha256:{hashlib.sha256(file_content).hexdigest()}"

        with self._uow as uow:
            # Check for duplicate batch
            existing_batch = self._find_existing_batch(
                uow, source, source_digest
            )
            if existing_batch:
                uow.rollback()
                return OperationResult(
                    ok=True,
                    count=0,
                    message="Statement already imported",
                    details={
                        "duplicate": True,
                        "batch_id": existing_batch["id"],
                    },
                )

            # Verify account exists and is correct type
            account = uow.accounts.find(account_name)
            if account is None:
                uow.rollback()
                return OperationResult(
                    ok=False,
                    message=f"Account not found: {account_name}",
                )

            if account.type not in {"security", "crypto"}:
                uow.rollback()
                return OperationResult(
                    ok=False,
                    message=f"Account must be security or crypto type, got: {account.type}",
                )

            # Parse statement
            try:
                transactions = self._parse_statement(source, source_path, password)
            except Exception as e:
                uow.rollback()
                return OperationResult(
                    ok=False,
                    message=f"Failed to parse statement: {e}",
                )

            if not transactions:
                uow.rollback()
                return OperationResult(
                    ok=False,
                    message="No transactions found in statement",
                )

            # Create import batch
            batch_id = self._create_batch(
                uow,
                source,
                source_digest,
                str(source_path.name),
                account_name,
            )

            # Create raw_records and events
            try:
                event_count = self._import_transactions(
                    uow,
                    batch_id,
                    transactions,
                    account_name,
                    account.type,
                    currency,
                    source_path,
                    file_content,
                )
            except Exception as e:
                uow.rollback()
                return OperationResult(
                    ok=False,
                    message=f"Import failed: {e}",
                )

            # Update snapshot with validation
            try:
                snapshot = uow.snapshot.load(lock=True)
                # Events already written, replay to update snapshot
                # (In current architecture, events are written with snapshot update)
                # Here we validate the final state
                validate_investment_snapshot(snapshot)
                uow.snapshot.save(snapshot)
            except ValueError as e:
                uow.rollback()
                return OperationResult(
                    ok=False,
                    message=f"Snapshot validation failed: {e}",
                )
            except Exception as e:
                uow.rollback()
                return OperationResult(
                    ok=False,
                    message=f"Snapshot update failed: {e}",
                )

            # Mark batch complete
            self._complete_batch(uow, batch_id)

            uow.commit()

        return OperationResult(
            ok=True,
            count=event_count,
            message=f"Imported {event_count} transactions",
            details={
                "duplicate": False,
                "batch_id": batch_id,
            },
        )

    def _find_existing_batch(self, uow, source_kind: str, source_digest: str) -> dict | None:
        """Find existing completed batch by source_digest."""
        batches = uow.imports.list_batches()
        for batch in batches:
            if (batch["source_kind"] == source_kind
                and batch["source_digest"] == source_digest
                and batch["status"] == "completed"):
                return batch
        return None

    def _parse_statement(self, source: str, source_path: Path, password: str = None) -> list[dict[str, Any]]:
        """Parse statement file based on source type."""
        if source == "dfzq":
            import subprocess
            import tempfile
            import os
            from ft.importers.dfzq import parse_dfzq_text

            # 1. Decrypt PDF if password provided
            tmp_pdf = None
            pdf_path = str(source_path)
            if password:
                tmp_pdf = tempfile.NamedTemporaryFile(suffix=".pdf", delete=False)
                try:
                    subprocess.run(
                        ["qpdf", f"--password={password}", "--decrypt", str(source_path), tmp_pdf.name],
                        check=True, timeout=30, capture_output=True
                    )
                    pdf_path = tmp_pdf.name
                except subprocess.CalledProcessError as e:
                    if tmp_pdf:
                        os.unlink(tmp_pdf.name)
                    raise ValueError(f"PDF decryption failed: {e.stderr.decode('utf-8', errors='replace')}")
                except Exception as e:
                    if tmp_pdf:
                        os.unlink(tmp_pdf.name)
                    raise ValueError(f"PDF decryption failed: {e}")

            # 2. Extract text with mutool
            try:
                result = subprocess.run(
                    ["mutool", "draw", "-F", "text", pdf_path],
                    capture_output=True, check=True, timeout=60
                )
                text = result.stdout.decode("utf-8", errors="replace")
                lines = text.split("\n")
            except subprocess.CalledProcessError as e:
                if tmp_pdf:
                    os.unlink(pdf_path)
                raise ValueError(f"Text extraction failed: {e.stderr.decode('utf-8', errors='replace')}")
            except Exception as e:
                if tmp_pdf:
                    os.unlink(pdf_path)
                raise ValueError(f"Text extraction failed: {e}")
            finally:
                # Clean up temp PDF
                if tmp_pdf and os.path.exists(pdf_path):
                    os.unlink(pdf_path)

            # 3. Parse DFZQ text
            return parse_dfzq_text(lines)
        else:
            raise ValueError(f"Unsupported source: {source}")

    def _create_batch(
        self,
        uow,
        source_kind: str,
        source_digest: str,
        source_ref: str,
        target_account: str,
    ) -> str:
        """Create import batch record."""
        return uow.imports.start_batch(
            source_kind=source_kind,
            source_digest=source_digest,
            source_ref=source_ref,
            target_account_name=target_account,
        )

    def _import_transactions(
        self,
        uow,
        batch_id: str,
        transactions: list[dict],
        account_name: str,
        account_type: str,
        currency: str,
        file_path: Path,
        file_content: bytes,
    ) -> int:
        """Import transactions: create raw_records and events."""
        from ft.importers.dfzq import (
            construct_source_identity,
            map_dfzq_to_investment_event,
        )
        import hashlib

        # Create raw_file record
        content_digest = f"sha256:{hashlib.sha256(file_content).hexdigest()}"
        raw_file_id = uow.imports.add_raw_file(
            batch_id=batch_id,
            source_path=str(file_path),
            content_digest=content_digest,
            size_bytes=len(file_content),
            media_type="text/plain",  # DFZQ text after mutool extraction
        )

        # Prepare raw_records
        raw_records = []
        for txn in transactions:
            source_identity = construct_source_identity(txn)
            raw_records.append({
                "source_identity": source_identity,
                "payload": txn,
            })

        # Create raw_records in database (handles idempotency)
        raw_record_ids = uow.imports.add_raw_records(
            batch_id=batch_id,
            raw_file_id=raw_file_id,
            source_type="dfzq_pdf",
            records=raw_records,
        )

        # Load snapshot for replay
        snapshot = uow.snapshot.load(lock=True)

        # Create events linked to raw_records
        count = 0
        for txn, raw_record_id in zip(transactions, raw_record_ids):
            # Map to investment event
            event = map_dfzq_to_investment_event(txn, account_name, currency)

            # Apply event to snapshot
            apply_investment_event(snapshot, event, default_currency=currency)

            # Store event with raw_record_id linkage
            event["raw_record_id"] = raw_record_id
            uow.investments.add(account_type, event)

            count += 1

        # Save updated snapshot
        uow.snapshot.save(snapshot)

        return count

    def _complete_batch(self, uow, batch_id: str) -> None:
        """Mark batch as completed."""
        uow.imports.complete_batch(batch_id)
