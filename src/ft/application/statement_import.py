"""原始账单的原子导入流程，来源快照随账本记录保存。"""
from __future__ import annotations

from dataclasses import replace
from decimal import Decimal
import hashlib
import json
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


def _row_record_id(row: dict, occurrences: dict[str, int]) -> str:
    """解析业务行标识；数据源未提供 ID 时，根据行内容生成稳定标识。"""
    identity = str(row.get("record_id") or "").strip()
    if identity:
        return identity
    payload = _json_safe(row)
    identity_payload = {
        key: value for key, value in payload.items()
        if key not in {"account_name", "raw_record_id", "source_payload", "source_type"}
    }
    canonical = json.dumps(
        identity_payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
    )
    base_identity = hashlib.sha256(canonical.encode()).hexdigest()
    occurrences[base_identity] = occurrences.get(base_identity, 0) + 1
    return f"{base_identity}:{occurrences[base_identity]}"


class StatementImportService:
    def __init__(self, unit_of_work, parser, relation_service=None):
        self._uow = unit_of_work
        self._parser = parser
        self._relations = relation_service

    def import_statement(self, command) -> OperationResult:
        path = Path(command.source_path)
        with path.open("rb") as source:
            content = source.read(MAX_STATEMENT_BYTES + 1)
        if len(content) > MAX_STATEMENT_BYTES:
            raise ValueError("账单超过 100 MiB 输入上限")
        with tempfile.TemporaryDirectory(prefix="ft-statement-") as temp_dir:
            captured_path = Path(temp_dir) / f"source{path.suffix}"
            captured_path.write_bytes(content)
            captured_command = replace(command, source_path=str(captured_path))
            parsed = [dict(row) for row in self._parser.parse(captured_command)]
        import_meta = {}
        rows = []
        for row in parsed:
            if "_import_meta" in row:
                import_meta = dict(row.pop("_import_meta") or {})
            rows.append(row)
        if not rows:
            acc = import_meta.get("acceptance") or {}
            if acc.get("source_lines") and (
                acc.get("skipped_unpaid_closed", 0) + acc.get("skipped_failed_repay", 0)
            ) >= acc.get("source_lines", 0):
                return OperationResult(
                    ok=True,
                    count=0,
                    message="导入完成",
                    details={
                        "batch_id": None,
                        "duplicate": False,
                        "by_account": {},
                        "new_cash_fact_ids": [],
                        "acceptance": acc,
                        "import_refund_relations": [],
                    },
                )
            raise ValueError("账单中没有可导入的记录")

        source_type = str(command.source or "").strip()
        for row in rows:
            if not row.get("account_name"):
                raise ValueError(
                    "账单记录缺少 account_name；账户映射规则必须为每条记录解析目标账户"
                )
            raw_currency = row.get("currency") or command.currency or "CNY"
            row["currency"] = str(raw_currency).upper()

        with self._uow as uow:
            account_cache: dict[str, object] = {}
            for row in rows:
                key = row["account_name"]
                if key in account_cache:
                    continue
                account = uow.accounts.find(row["account_name"])
                if account is None:
                    raise ValueError(f"找不到账户：{row['account_name']}")
                account_cache[key] = account

            occurrences: dict[str, int] = {}
            prepared: list[tuple[dict, str]] = []
            for row in rows:
                record_id = _row_record_id(row, occurrences)
                prepared.append((row, record_id))

            existing_targets = uow.imports.existing_fact_targets(
                source_type=source_type,
                record_ids=[rid for _, rid in prepared],
            )
            for row, record_id in prepared:
                expected = (row["account_name"], row["currency"])
                existing_target = existing_targets.get(record_id)
                if existing_target is not None and existing_target != expected:
                    raise ValueError(
                        "该账单记录已导入其他账户，不能更改归属"
                    )

            snapshot = uow.snapshot.load(lock=True)
            imported_count = 0
            by_account: Counter[str] = Counter()
            new_cash_fact_ids: list[str] = []
            for row, record_id in prepared:
                if record_id in existing_targets:
                    continue
                account = account_cache[row["account_name"]]
                payload = _json_safe(row)
                formal = {
                    **row,
                    "source_type": source_type,
                    "record_id": record_id,
                    "source_payload": payload,
                }
                if account.type in {"cash", "loan", "lend"}:
                    fact_id = uow.cashflows.add(account.type, formal)
                    new_cash_fact_ids.append(fact_id)
                    if row.get("category") not in {"transfer", "transfer_in", "transfer_out"}:
                        uow.snapshot.update_balance(
                            snapshot, account.name, account.type, row["currency"], row["amount"]
                        )
                elif account.type in {"security", "crypto"}:
                    apply_investment_event(snapshot, formal, default_currency=row["currency"])
                    uow.investments.add(account.type, formal)
                else:
                    raise ValueError(f"不支持导入到 {account.type} 类型的账户")
                existing_targets[record_id] = (row["account_name"], row["currency"])
                imported_count += 1
                by_account[account.name] += 1
            if imported_count:
                snapshot["updated_at"] = max(str(row.get("date", "")) for row in rows)
            uow.snapshot.save(snapshot)
            uow.commit()
            saved_imported_count = imported_count
            saved_by_account = dict(by_account)
            saved_new_cash_fact_ids = list(new_cash_fact_ids)

        import_refund_relations = []
        relation_details = None
        if saved_new_cash_fact_ids and self._relations is not None:
            try:
                check_result = self._relations.check(
                    seed_fact_ids=saved_new_cash_fact_ids,
                    trigger="import",
                    seed_ref=",".join(saved_new_cash_fact_ids[:8]),
                )
                relation_details = check_result.details
            except Exception as exc:  # noqa: BLE001
                relation_details = {"error": str(exc), "status": "failed"}
        acceptance = import_meta.get("acceptance") or {}
        if not acceptance.get("source_lines"):
            acceptance = {
                "source_lines": saved_imported_count,
                "skipped_unpaid_closed": 0,
                "skipped_failed_repay": 0,
                "fact_lines": saved_imported_count,
                "published": saved_imported_count,
            }
        else:
            acceptance = {
                **acceptance,
                "published": saved_imported_count,
            }
        no_new = saved_imported_count == 0
        return OperationResult(
            ok=True,
            count=saved_imported_count,
            message="没有新增账本记录" if no_new else "导入完成",
            details={
                "batch_id": None,
                "duplicate": no_new,
                "new_rows": saved_imported_count,
                "by_account": saved_by_account,
                "new_cash_fact_ids": saved_new_cash_fact_ids,
                "acceptance": acceptance,
                "import_refund_relations": import_refund_relations,
                "relation_check": relation_details,
            },
        )
