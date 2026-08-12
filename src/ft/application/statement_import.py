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
from ft.domain.relations import RelationKind, RelationStatus, ordered_fact_pair


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
        if key not in {
            "account_name",
            "raw_record_id",
            "source_payload",
            "source_type",
            "_counterparty_account_reconstruction_proof",
        }
    }
    canonical = json.dumps(
        identity_payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
    )
    base_identity = hashlib.sha256(canonical.encode()).hexdigest()
    occurrences[base_identity] = occurrences.get(base_identity, 0) + 1
    return f"{base_identity}:{occurrences[base_identity]}"


class StatementImportService:
    def __init__(self, unit_of_work, parser, relation_service=None, *, enforce_account_currencies: bool = False):
        self._uow = unit_of_work
        self._parser = parser
        self._relations = relation_service
        self._enforce_account_currencies = enforce_account_currencies

    def _apply_relation_decisions(
        self,
        uow,
        decisions,
        *,
        fact_id_by_record_id: dict[str, int],
        new_fact_ids: set[int] | None = None,
    ) -> list[str]:
        if not decisions:
            return []
        if not isinstance(decisions, list):
            raise ValueError("导入关系决策格式无效")
        accepted_ids: list[str] = []

        def resolve(value, *, required: bool = True) -> int | None:
            if value in (None, ""):
                if required:
                    raise ValueError("导入关系缺少流水记录")
                return None
            text = str(value)
            if text.startswith("preview:"):
                text = text.removeprefix("preview:")
            if text in fact_id_by_record_id:
                return int(fact_id_by_record_id[text])
            try:
                return int(text)
            except (TypeError, ValueError) as exc:
                raise ValueError("导入关系引用的流水不存在") from exc

        for decision in decisions:
            if not isinstance(decision, dict):
                raise ValueError("导入关系决策格式无效")
            decision_status = str(decision.get("status") or "accepted")
            if decision_status in {"skipped", "ignored"}:
                continue
            kind = str(decision.get("kind") or "")
            if kind not in {item.value for item in RelationKind}:
                raise ValueError("导入关系类型无效")
            primary = resolve(
                decision.get("primary_fact_id") or decision.get("primary_record_id")
            )
            secondary = resolve(
                decision.get("secondary_fact_id") or decision.get("secondary_record_id"),
                required=decision_status != "rejected",
            )
            if primary == secondary:
                raise ValueError("导入关系不能关联同一条流水")
            record_ids = [primary] + ([secondary] if secondary is not None else [])
            records = uow.cashflows.get_many(record_ids)
            if any(
                records.get(item) is None or records[item].get("deleted")
                for item in record_ids
            ):
                raise ValueError("导入关系引用的流水不存在")
            # A repeated import may submit stale automatic decisions from an
            # older preview. Existing facts and their relations are already
            # authoritative; re-applying such a decision can create a second
            # graph edge and invalidate the cash projection. Relation choices
            # are actionable here only when at least one endpoint is a fact
            # created by this import confirmation.
            if new_fact_ids is not None and not any(
                item in new_fact_ids for item in record_ids
            ):
                continue
            if decision_status == "rejected":
                # 拒绝只记录本次导入会话的决定，不创建或确认关系。
                continue
            if decision_status != "accepted":
                raise ValueError("导入关系状态无效")
            subtype = str(decision.get("subtype") or "")
            if kind == RelationKind.TRANSFER_PAIR.value and not subtype:
                subtype = "ordinary_transfer"
            left, right = ordered_fact_pair(primary, secondary)
            existing = uow.relations.find_by_business_key(
                kind=kind, fact_a=left, fact_b=right, subtype=subtype,
            )
            if existing is not None:
                if existing.get("status") == RelationStatus.ACCEPTED.value:
                    accepted_ids.append(str(existing["id"]))
                    continue
                relation_id = existing["id"]
                relation = uow.relations.update_status(
                    relation_id,
                    status=RelationStatus.ACCEPTED.value,
                    decided_by="web",
                    decision_reason="import_confirmed",
                )
            else:
                relation_id = uow.relations.add({
                    "kind": kind,
                    "subtype": subtype,
                    "primary_fact_id": primary,
                    "secondary_fact_id": secondary,
                    "primary_fact_type": "cash",
                    "secondary_fact_type": "cash",
                    "anchor_fact_id": primary,
                    "status": RelationStatus.ACCEPTED.value,
                    "rule_id": str(decision.get("rule_id") or "manual.import.v1"),
                    "created_by": "web",
                    "decided_by": "web",
                    "decision_reason": "import_confirmed",
                })
                relation = {
                    "id": relation_id,
                    "kind": kind,
                    "subtype": subtype,
                    "primary_fact_id": primary,
                    "secondary_fact_id": secondary,
                    "status": RelationStatus.ACCEPTED.value,
                }
            endpoint_relations = uow.relations.list_for_facts(
                [primary, secondary], active_only=True,
            )
            if self._relations is not None:
                self._relations._validate_transfer_endpoint_availability(
                    uow, [primary, secondary], str(relation_id), relations=endpoint_relations,
                )
            from ft.application.cash_projections import CashProjectionService
            try:
                projection_status = CashProjectionService.maintain_if_ready_in_session(
                    uow._state().session,
                    uow.workspace_id,
                    {primary, secondary},
                    known_component_ids={primary, secondary},
                )
            except Exception as exc:  # noqa: BLE001 - convert graph failures to import errors.
                raise ValueError("导入关系无法形成有效收支投影") from exc
            if projection_status is None and self._relations is not None:
                self._relations._validate_projection_acceptance(
                    uow, relation, other_fact_id=None,
                )
            accepted_ids.append(str(relation_id))
        return accepted_ids

    def import_statement(self, command, *, relation_decisions: list[dict] | None = None) -> OperationResult:
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

        parsed_source_types = set()
        for row in rows:
            parsed_source_type = str(
                row.get("bill_source")
                or row.get("source_type")
                or (command.source if row.get("action") else "")
            ).strip()
            if not parsed_source_type and command.source not in {"icbc", "icbc-debit"}:
                # 预路由的非工行测试/应用行仍由命令来源标识；工行必须由解析器提供正式渠道。
                parsed_source_type = str(command.source or "").strip()
            if command.source == "icbc" and parsed_source_type not in {
                "icbc_credit", "icbc_debit",
            }:
                raise ValueError("工行账单必须提供 icbc_credit 或 icbc_debit 正式导入渠道")
            if command.source == "icbc-debit" and parsed_source_type != "icbc_debit":
                raise ValueError("工行借记卡账单必须提供 icbc_debit 正式导入渠道")
            parsed_source_types.add(parsed_source_type)
        if not parsed_source_types or "" in parsed_source_types:
            raise ValueError("账单记录缺少 bill_source，无法确定正式导入渠道")
        if len(parsed_source_types) != 1:
            raise ValueError("同一账单不能混合多个正式导入渠道")
        source_type = next(iter(parsed_source_types))
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
                if self._enforce_account_currencies and str(row["currency"]).upper() not in set(account.currencies):
                    raise ValueError(
                        f"账户 {account.name} 暂不支持 {row['currency']}，请更新账户配置后重新导入"
                    )
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

            formal_rows: list[tuple[dict, str, object, dict]] = []
            cash_items: list[tuple[str, dict]] = []
            for row, record_id in prepared:
                account = account_cache[row["account_name"]]
                payload = row.get("source_payload")
                if account.type in {"cash", "loan", "lend"} and (
                    not isinstance(payload, dict) or not payload
                ):
                    raise ValueError("账单记录缺少完整来源行快照")
                if payload is None:
                    payload = _json_safe(row)
                formal = {
                    **row,
                    "source_type": source_type,
                    "record_id": record_id,
                    "source_payload": _json_safe(payload),
                }
                formal_rows.append((row, record_id, account, formal))
                if account.type in {"cash", "loan", "lend"}:
                    cash_items.append((account.type, formal))

            snapshot = uow.snapshot.load(lock=True)
            imported_count = 0
            updated_count = 0
            by_account: Counter[str] = Counter()
            new_cash_fact_ids: list[str] = []
            created_cash_fact_ids: list[str] = []
            cash_results = iter(uow.cashflows.merge_import_batch(cash_items))
            cash_result_by_formal_id = {}
            for _account_type, formal in cash_items:
                cash_result_by_formal_id[id(formal)] = next(cash_results)

            for row, record_id, account, formal in formal_rows:
                if account.type in {"cash", "loan", "lend"}:
                    result = cash_result_by_formal_id[id(formal)]
                    fact_id = result["fact_id"]
                    created = result["created"]
                    source_changed = result["source_changed"]
                    previous = result["previous"]
                    current = result["current"]
                    if created:
                        imported_count += 1
                    if source_changed:
                        new_cash_fact_ids.append(fact_id)
                    if created:
                        created_cash_fact_ids.append(fact_id)
                    if not created and source_changed:
                        updated_count += 1
                    if created and row.get("category") not in {"transfer", "transfer_in", "transfer_out"}:
                        uow.snapshot.update_balance(
                            snapshot, account.name, account.type, row["currency"], row["amount"]
                        )
                    elif not created and source_changed and current is not None:
                        if previous and previous.get("category") not in {"transfer", "transfer_in", "transfer_out"}:
                            uow.snapshot.update_balance(
                                snapshot,
                                previous["account_name"],
                                previous.get("account_type") or account.type,
                                previous["currency"],
                                -previous["amount"],
                            )
                        if row.get("category") not in {"transfer", "transfer_in", "transfer_out"}:
                            uow.snapshot.update_balance(
                                snapshot,
                                current["account_name"],
                                current.get("account_type") or account.type,
                                current["currency"],
                                current["amount"],
                            )
                elif account.type in {"security", "crypto"}:
                    if record_id in existing_targets:
                        continue
                    apply_investment_event(snapshot, formal, default_currency=row["currency"])
                    uow.investments.add(account.type, formal)
                    imported_count += 1
                else:
                    raise ValueError(f"不支持导入到 {account.type} 类型的账户")
                existing_targets[record_id] = (row["account_name"], row["currency"])
                by_account[account.name] += 1
            fact_id_by_record_id = {
                str(record_id): int(cash_result_by_formal_id[id(formal)]["fact_id"])
                for row, record_id, account, formal in formal_rows
                if account.type in {"cash", "loan", "lend"}
            }
            imported_relation_ids = self._apply_relation_decisions(
                uow,
                relation_decisions,
                fact_id_by_record_id=fact_id_by_record_id,
                new_fact_ids={int(item) for item in created_cash_fact_ids},
            )
            if imported_count or updated_count:
                snapshot["updated_at"] = max(str(row.get("date", "")) for row in rows)
            uow.snapshot.save(snapshot)
            if new_cash_fact_ids:
                from ft.application.cash_projections import CashProjectionService
                CashProjectionService.maintain_if_ready_in_session(
                    uow._state().session,
                    uow.workspace_id,
                    {int(item) for item in new_cash_fact_ids},
                    new_fact_ids={int(item) for item in created_cash_fact_ids},
                )
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
                "updated_rows": updated_count,
                "by_account": saved_by_account,
                "new_cash_fact_ids": saved_new_cash_fact_ids,
                "acceptance": acceptance,
                "import_refund_relations": import_refund_relations,
                "relation_check": relation_details,
                "imported_relation_ids": imported_relation_ids,
            },
        )
