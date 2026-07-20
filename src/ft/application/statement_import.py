"""Atomic original-statement import orchestration."""
from __future__ import annotations

from datetime import datetime
from dataclasses import replace
from decimal import Decimal
import hashlib
import json
import mimetypes
from pathlib import Path
import tempfile
from collections import Counter

from ft.domain.application import OperationResult
from ft.domain.investment_projection import apply_investment_event


MAX_STATEMENT_BYTES = 100 * 1024 * 1024


def _json_safe(value):
    if isinstance(value, Decimal):
        return format(value, "f")
    if isinstance(value, dict):
        return {key: _json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_safe(item) for item in value]
    return value


class StatementImportService:
    def __init__(self, unit_of_work, parser):
        self._uow = unit_of_work
        self._parser = parser

    def import_statement(self, command) -> OperationResult:
        path = Path(command.source_path)
        with path.open("rb") as source:
            content = source.read(MAX_STATEMENT_BYTES + 1)
        if len(content) > MAX_STATEMENT_BYTES:
            raise ValueError("statement exceeds 100 MiB input limit")
        digest = f"sha256:{hashlib.sha256(content).hexdigest()}"
        with tempfile.TemporaryDirectory(prefix="ft-statement-") as temp_dir:
            captured_path = Path(temp_dir) / f"source{path.suffix}"
            captured_path.write_bytes(content)
            captured_command = replace(command, source_path=str(captured_path))
            rows = [dict(row) for row in self._parser.parse(captured_command)]
        if not rows:
            raise ValueError("statement contains no supported records")

        # Normalize currencies; account_name must already be set by parser/mapping.
        for row in rows:
            if not row.get("account_name"):
                raise ValueError(
                    "statement row missing account_name; mapping must resolve every row"
                )
            raw_currency = row.get("currency") or command.currency or "CNY"
            row["currency"] = str(raw_currency).upper()

        with self._uow as uow:
            batch_id = uow.imports.start_batch(
                source_kind=command.source,
                source_digest=digest,
                source_ref=path.name,
                target_account_name=None,
                target_account_currency=None,
            )
            existing = uow.imports.get_batch(batch_id)
            if existing["status"] == "completed":
                uow.commit()
                return OperationResult(
                    ok=True, count=0, message="already imported",
                    details={"batch_id": batch_id, "duplicate": True, "by_account": {}},
                )

            # Account identity is name-only; a statement row owns its currency.
            account_cache: dict[str, object] = {}
            for row in rows:
                key = row["account_name"]
                if key in account_cache:
                    continue
                account = uow.accounts.find(row["account_name"])
                if account is None:
                    raise ValueError(f"account not found: {row['account_name']}")
                account_cache[key] = account

            raw_file_id = uow.imports.add_raw_file(
                batch_id=batch_id, source_path=path.name, content_digest=digest,
                size_bytes=len(content), media_type=mimetypes.guess_type(path.name)[0] or "application/octet-stream",
            )
            raw_items = []
            occurrences: dict[str, int] = {}
            for index, row in enumerate(rows, 1):
                payload = _json_safe(row)
                identity = str(row.get("record_id") or "")
                if not identity:
                    identity_payload = {
                        key: value for key, value in payload.items()
                        if key not in {"account_name", "raw_record_id"}
                    }
                    canonical = json.dumps(
                        identity_payload, ensure_ascii=False, sort_keys=True,
                        separators=(",", ":"),
                    )
                    base_identity = hashlib.sha256(canonical.encode()).hexdigest()
                    occurrences[base_identity] = occurrences.get(base_identity, 0) + 1
                    identity = f"{base_identity}:{occurrences[base_identity]}"
                raw_items.append({
                    "source_identity": f"{command.source}:{identity}",
                    "source_line": index,
                    "payload": payload,
                })
            raw_ids = uow.imports.add_raw_records(
                batch_id=batch_id, raw_file_id=raw_file_id,
                source_type=command.source, records=raw_items,
            )
            existing_targets = uow.imports.formal_fact_targets(raw_ids)
            for row, raw_id in zip(rows, raw_ids, strict=True):
                expected = (row["account_name"], row["currency"])
                existing_target = existing_targets.get(raw_id)
                if existing_target is not None and existing_target != expected:
                    raise ValueError(
                        "statement record was already imported to a different account"
                    )

            seen_fact_ids = set(existing_targets)
            rows_to_import = []
            for row, raw_id in zip(rows, raw_ids, strict=True):
                if raw_id in seen_fact_ids:
                    continue
                seen_fact_ids.add(raw_id)
                rows_to_import.append((row, raw_id))

            snapshot = uow.snapshot.load(lock=True)
            imported_count = 0
            by_account: Counter[str] = Counter()
            for row, raw_id in rows_to_import:
                account = account_cache[row["account_name"]]
                row["raw_record_id"] = raw_id
                if account.type in {"cash", "loan", "lend"}:
                    fact_id = uow.cashflows.add(account.type, row)
                    if row.get("category") not in {"transfer", "transfer_in", "transfer_out"}:
                        uow.snapshot.update_balance(
                            snapshot, account.name, account.type, row["currency"], row["amount"]
                        )
                    uow.imports.append_revision(
                        cash_transaction_id=fact_id, before={}, after=_json_safe(row),
                        actor_type="statement_import", reason="initial statement import",
                    )
                elif account.type in {"security", "crypto"}:
                    apply_investment_event(snapshot, row, default_currency=row["currency"])
                    fact_id = uow.investments.add(account.type, row)
                    uow.imports.append_revision(
                        investment_event_id=fact_id, before={}, after=_json_safe(row),
                        actor_type="statement_import", reason="initial statement import",
                    )
                else:
                    raise ValueError(f"unsupported account type: {account.type}")
                imported_count += 1
                by_account[account.name] += 1
            if imported_count:
                snapshot["updated_at"] = max(str(row.get("date", "")) for row in rows)
            uow.snapshot.save(snapshot)
            uow.imports.complete_batch(batch_id)
            uow.commit()
        return OperationResult(
            ok=True, count=imported_count, message="imported",
            details={
                "batch_id": batch_id,
                "duplicate": False,
                "by_account": dict(by_account),
            },
        )
