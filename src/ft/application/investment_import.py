"""Investment statement import service.

Orchestrates import flow: formal investment_events (+ ephemeral batch ids) → snapshot
with atomic transaction guarantees.
"""
from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Any

from ft.domain.application import OperationResult
from ft.domain.investment_projection import apply_investment_event, normalize_base_tickers
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
            source: Parser identifier (including 'usmart-hk' / 'usmart_hk')
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
                message=f"找不到文件：{source_path}",
            )
        except Exception as e:
            return OperationResult(
                ok=False,
                message=f"读取文件失败：{e}",
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
                    message=f"找不到账户：{account_name}",
                )

            if account.type not in {"security", "crypto"}:
                uow.rollback()
                return OperationResult(
                    ok=False,
                    message=f"账户类型必须是 security 或 crypto，当前为：{account.type}",
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
                    message=f"解析账单失败：{e}",
                )

            if not transactions:
                uow.rollback()
                return OperationResult(
                    ok=False,
                    message="账单中没有可导入的交易记录",
                )

            # Resolve currency: CLI override, else source-specific default.
            # IBKR: no silent USD/CNY — use 总结.基础货币 or require --currency.
            # Schwab: CLI or USD (statement is US$).
            # uSmart HK rows carry native currencies; CLI is fallback only.
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
                            "导入 IBKR 账单时必须通过 --currency 指定币种，"
                            "或确保 CSV 中存在 总结.基础货币"
                        ),
                    )
            elif source == "schwab":
                resolved_currency = (resolved_currency or "USD").upper()
            elif source in {"usmart-hk", "usmart_hk"}:
                resolved_currency = (resolved_currency or "USD").upper()
            else:
                resolved_currency = (resolved_currency or "CNY").upper()

            try:
                base_tickers = normalize_base_tickers(
                    next(
                        (
                            row.get("base_currencies")
                            for row in uow.accounts.list_raw()
                            if row.get("name") == account_name
                        ),
                        None,
                    )
                )
                event_count = self._import_transactions(
                    uow,
                    transactions,
                    account_name,
                    account.type,
                    resolved_currency,
                    source=source,
                    base_tickers=base_tickers,
                )
            except Exception as e:
                uow.rollback()
                return OperationResult(
                    ok=False,
                    message=f"导入失败：{e}",
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
                    message=f"校准结果校验失败：{e}",
                )
            except Exception as e:
                uow.rollback()
                return OperationResult(
                    ok=False,
                    message=f"更新校准结果失败：{e}",
                )

            uow.commit()

        no_new = event_count == 0
        return OperationResult(
            ok=True,
            count=event_count,
            message=(
                "没有可导入的新记录"
                if no_new
                else f"已导入 {event_count} 条投资事件"
            ),
            details={
                "duplicate": no_new,
                "new_rows": event_count,
                "batch_id": None,
                **(
                    {"ignored_trade_mirrors": int(transactions[0].get("_usmart_ignored_trade_mirrors", 0))}
                    if transactions and "_usmart_ignored_trade_mirrors" in transactions[0]
                    else {}
                ),
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
            import tempfile
            from ft.importers.dfzq import parse_dfzq_text
            from ft.importers.pdf_tools import decrypt_pdf, extract_pdf_text

            # Plain-text fixtures / pre-extracted mutool output (no PDF tools needed).
            suffix = source_path.suffix.lower()
            if suffix in {".txt", ".text"}:
                text = source_path.read_text(encoding="utf-8", errors="replace")
                return parse_dfzq_text(text.splitlines())

            # Secure PDF path: password-file via pdf_tools (no subprocess in application).
            with tempfile.TemporaryDirectory(prefix="ft-dfzq-") as temp_dir:
                pdf_path = Path(temp_dir) / "statement.pdf"
                if password:
                    decrypt_pdf(source_path, pdf_path, password)
                    extract_path = pdf_path
                else:
                    extract_path = source_path
                text = extract_pdf_text(extract_path)
            return parse_dfzq_text(text.splitlines())

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

        if source in {"usmart-hk", "usmart_hk"}:
            from ft.importers.usmart_hk import parse_usmart_hk_text
            from ft.importers.pdf_tools import decrypt_pdf, extract_pdf_text
            import tempfile

            if source_path.suffix.lower() in {".txt", ".text"}:
                return parse_usmart_hk_text(source_path.read_text(encoding="utf-8", errors="replace"))
            with tempfile.TemporaryDirectory(prefix="ft-usmart-hk-") as temp_dir:
                decrypted = Path(temp_dir) / "statement.pdf"
                decrypt_pdf(source_path, decrypted, password)
                return parse_usmart_hk_text(extract_pdf_text(decrypted))

        raise ValueError(f"不支持的数据源：{source}")


    def _import_transactions(
        self,
        uow,
        transactions: list[dict],
        account_name: str,
        account_type: str,
        currency: str,
        source: str = "dfzq",
        base_tickers=None,
    ) -> int:
        """Import novel investment events by (source_type, record_id)."""
        from decimal import Decimal as _Dec

        if source == "ibkr":
            from ft.importers.ibkr import (
                construct_source_identity,
                map_ibkr_to_investment_event as map_to_event,
            )
            source_type = "ibkr_csv"
        elif source == "schwab":
            from ft.importers.schwab import (
                construct_source_identity,
                map_schwab_to_investment_event as map_to_event,
            )
            source_type = "schwab_csv"
        elif source == "dfzq":
            from ft.importers.dfzq import (
                construct_source_identity,
                map_dfzq_to_investment_event as map_to_event,
            )
            source_type = "dfzq_pdf"
        elif source in {"usmart-hk", "usmart_hk"}:
            from ft.importers.usmart_hk import (
                construct_source_identity,
                map_usmart_hk_to_investment_event as map_to_event,
            )
            source_type = "usmart_hk_pdf"
        else:
            raise ValueError(f"不支持导入该投资数据源：{source}")

        metadata_keys = {
            "ibkr": {"_ibkr_base_currency", "_ibkr_ending_cash"},
            "schwab": {"_schwab_ending_cash"},
            "usmart-hk": {
                "_id_seq", "_profile", "_usmart_ignored_trade_mirrors",
                "_usmart_statement_profile",
            },
            "usmart_hk": {
                "_id_seq", "_profile", "_usmart_ignored_trade_mirrors",
                "_usmart_statement_profile",
            },
        }.get(source, set())

        record_ids = []
        payloads = []
        for txn in transactions:
            record_id = str(construct_source_identity(txn) or "").strip()
            if record_id.startswith(f"{source_type}:"):
                record_id = record_id[len(source_type) + 1 :]
            payload = {
                k: (format(v, "f") if isinstance(v, _Dec) else v)
                for k, v in txn.items()
                if k not in metadata_keys
            }
            record_ids.append(record_id)
            payloads.append(payload)

        existing_targets = uow.imports.existing_fact_targets(
            source_type=source_type, record_ids=record_ids,
        )
        snapshot = uow.snapshot.load(lock=True)
        count = 0
        for txn, record_id, payload in zip(transactions, record_ids, payloads):
            if record_id in existing_targets:
                continue
            event = map_to_event(txn, account_name, currency)
            apply_investment_event(
                snapshot, event, default_currency=currency, base_tickers=base_tickers,
            )
            event["source_type"] = source_type
            event["record_id"] = record_id
            event["source_payload"] = payload
            uow.investments.add(account_type, event)
            existing_targets[record_id] = (account_name, currency)
            count += 1
        if count:
            uow.snapshot.save(snapshot)
        return count
