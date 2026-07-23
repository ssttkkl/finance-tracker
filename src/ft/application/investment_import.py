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
        currency: str | None = None,
        password: str = None,
    ) -> OperationResult:
        """Import investment statement from file.

        Args:
            source: Parser identifier ('dfzq', 'ibkr', 'schwab', 'binance', 'okx', 'polymarket')
            source_path: Path to statement file
            account_name: Target account name
            currency: Default currency. For dfzq defaults to CNY when unset;
                for ibkr uses CLI value or 总结.基础货币 (no silent USD/CNY);
                for schwab uses CLI value or USD when unset.
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

        # Digest is job/audit metadata only (010); not a formalization gate.
        source_digest = f"sha256:{hashlib.sha256(file_content).hexdigest()}"

        with self._uow as uow:
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
                transactions = self._parse_statement(
                    source, source_path, password, currency=currency
                )
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

            # Resolve currency: CLI override, else source-specific default.
            # IBKR: no silent USD/CNY — use 总结.基础货币 or require --currency.
            # Schwab: CLI or USD (statement is US$).
            # DFZQ / others: CLI or CNY.
            resolved_currency = currency
            if source == "ibkr":
                base = (transactions[0].get("_ibkr_base_currency") or "").strip()
                if resolved_currency:
                    resolved_currency = resolved_currency.upper()
                elif base:
                    resolved_currency = base.upper()
                else:
                    uow.rollback()
                    return OperationResult(
                        ok=False,
                        message=(
                            "Currency required for IBKR import: pass --currency "
                            "or ensure 总结.基础货币 is present in the CSV"
                        ),
                    )
            elif source == "schwab":
                resolved_currency = (resolved_currency or "USD").upper()
            else:
                resolved_currency = (resolved_currency or "CNY").upper()

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
                    resolved_currency,
                    source_path,
                    file_content,
                    source=source,
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

        no_new = event_count == 0
        return OperationResult(
            ok=True,
            count=event_count,
            message=(
                "No new rows to import"
                if no_new
                else f"Imported {event_count} transactions"
            ),
            details={
                "duplicate": no_new,
                "new_rows": event_count,
                "batch_id": batch_id,
            },
        )

    def _parse_statement(
        self,
        source: str,
        source_path: Path,
        password: str = None,
        currency: str | None = None,
    ) -> list[dict[str, Any]]:
        """Parse statement file based on source type.

        Returns a list of transaction dicts. For ibkr, each dict may carry
        ``_ibkr_base_currency`` on the list via a side-channel attribute is
        not used; currency resolution for ibkr happens in import_statement.
        """
        if source == "dfzq":
            import subprocess
            import tempfile
            import os
            from ft.importers.dfzq import parse_dfzq_text

            # Plain-text fixtures / pre-extracted mutool output (no PDF tools needed).
            suffix = source_path.suffix.lower()
            if suffix in {".txt", ".text"}:
                text = source_path.read_text(encoding="utf-8", errors="replace")
                return parse_dfzq_text(text.splitlines())

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

        if source == "ibkr":
            from ft.importers.ibkr import parse_ibkr_csv

            statement = parse_ibkr_csv(source_path)
            # Stash summary on first row metadata for currency resolution upstream
            rows = list(statement.transactions)
            for row in rows:
                row["_ibkr_base_currency"] = statement.base_currency
                row["_ibkr_ending_cash"] = str(statement.ending_cash)
            return rows

        if source == "schwab":
            from ft.importers.schwab import parse_schwab_csv

            statement = parse_schwab_csv(source_path)
            rows = list(statement.transactions)
            for row in rows:
                row["_schwab_ending_cash"] = str(statement.ending_cash)
            return rows

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
        source: str = "dfzq",
    ) -> int:
        """Import transactions: create raw_records and events."""
        import hashlib

        if source == "ibkr":
            from ft.importers.ibkr import (
                construct_source_identity,
                map_ibkr_to_investment_event as map_to_event,
            )
            source_type = "ibkr_csv"
            media_type = "text/csv"
        elif source == "schwab":
            from ft.importers.schwab import (
                construct_source_identity,
                map_schwab_to_investment_event as map_to_event,
            )
            source_type = "schwab_csv"
            media_type = "text/csv"
        elif source == "dfzq":
            from ft.importers.dfzq import (
                construct_source_identity,
                map_dfzq_to_investment_event as map_to_event,
            )
            source_type = "dfzq_pdf"
            media_type = "text/plain"
        else:
            raise ValueError(f"Unsupported investment source for import: {source}")

        # Create raw_file record
        content_digest = f"sha256:{hashlib.sha256(file_content).hexdigest()}"
        raw_file_id = uow.imports.add_raw_file(
            batch_id=batch_id,
            source_path=str(file_path),
            content_digest=content_digest,
            size_bytes=len(file_content),
            media_type=media_type,
        )

        from decimal import Decimal as _Dec

        # Prepare raw_records (strip private parse-side keys from payload)
        raw_records = []
        for txn in transactions:
            source_identity = construct_source_identity(txn)
            payload = {
                k: (format(v, "f") if isinstance(v, _Dec) else v)
                for k, v in txn.items()
                if not (
                    str(k).startswith("_ibkr_") or str(k).startswith("_schwab_")
                )
            }
            raw_records.append({
                "source_identity": source_identity,
                "payload": payload,
            })

        # Create/reuse raw_records by business identity
        raw_record_ids = uow.imports.add_raw_records(
            batch_id=batch_id,
            raw_file_id=raw_file_id,
            source_type=source_type,
            records=raw_records,
        )

        # 010: skip raw_ids that already have formal facts (cash or investment)
        existing_targets = uow.imports.formal_fact_targets(raw_record_ids)

        # Load snapshot for projection updates of novel events only
        snapshot = uow.snapshot.load(lock=True)

        count = 0
        for txn, raw_record_id in zip(transactions, raw_record_ids):
            if raw_record_id in existing_targets:
                continue
            event = map_to_event(txn, account_name, currency)
            apply_investment_event(snapshot, event, default_currency=currency)
            event["raw_record_id"] = raw_record_id
            uow.investments.add(account_type, event)
            count += 1

        # Save snapshot only when novel events changed projection
        if count:
            uow.snapshot.save(snapshot)

        return count

    def _complete_batch(self, uow, batch_id: str) -> None:
        """Mark batch as completed."""
        uow.imports.complete_batch(batch_id)
