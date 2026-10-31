"""Export anonymous post-operation import preview preparation contracts."""
from __future__ import annotations

import argparse
from dataclasses import asdict, replace
from datetime import timedelta
from decimal import Decimal
import json
from pathlib import Path

from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
from ft.application.cash_import_funding import scan_cash_funding
from ft.application.cash_import_operations import CashImportOperationPlanner, _wire
from ft.application.cash_import_plans import CashImportPlanError
from ft.application.cash_import_source_plan import CashImportSourcePlanner
from ft.application.cash_relation_context import CashRelationGraphService
from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.models import (
    AccountAliasModel, AccountModel, CashEntryMemberModel, CashEntryModel, CashInvestmentFundingRelationModel,
    CashTransactionComponentModel, CashTransactionModel, InvestmentEventModel,
)

from export_cash_import_operation_contracts import NOW, WORKSPACE, connect, files, seed


def export(database_url: str, output: Path) -> None:
    engine, sessions, jdbc = connect(database_url)
    try:
        seed(sessions)
        with sessions.begin() as session:
            session.add(AccountModel(id=9203, workspace_id=WORKSPACE, name="合成投资", type="investment",
                active=True, currencies=["CNY"], metadata_json={}, revision=1, created_at=NOW, updated_at=NOW))
            session.add(AccountModel(id=9204, workspace_id=WORKSPACE, name="合成现金二", type="cash",
                active=True, currencies=["CNY"], metadata_json={}, revision=1, created_at=NOW, updated_at=NOW))
            for identifier, amount, record_type in (
                (7503, "-12.30", "consumption"),
                (7504, "12.30", "refund"),
                (7505, "-7.00", "investment_out"),
            ):
                session.add(CashTransactionModel(id=identifier, workspace_id=WORKSPACE, account_id=9201,
                    state="posted", record_origin="import", cash_granularity="atomic", source_type="fixture",
                    record_id=f"TYPE-{identifier}", source_payload={}, occurred_at=NOW, amount=amount,
                    currency="CNY", counterparty="类型变化对照", counterparty_account="",
                    counterparty_account_attrs=[], note="", record_type=record_type,
                    record_subtype="not_applicable", row_revision=1, created_at=NOW))
            session.add(CashTransactionModel(id=7506, workspace_id=WORKSPACE, account_id=None,
                state="posted", record_origin="import", cash_granularity="aggregate", source_type="fixture",
                record_id="TYPE-7506", source_payload={}, occurred_at=NOW, amount="-12.30",
                currency="CNY", counterparty="多现金部分", counterparty_account="",
                counterparty_account_attrs=[], note="", record_type="consumption",
                record_subtype="not_applicable", row_revision=1, created_at=NOW))
            session.add(CashTransactionModel(id=7507, workspace_id=WORKSPACE, account_id=9201,
                state="posted", record_origin="legacy", cash_granularity="atomic", source_type="icbc_debit",
                record_id="BANK-7507", source_payload={"摘要": "支付宝", "备注": "商户来源备注"},
                occurred_at=NOW + timedelta(days=1, hours=1), amount="-5.00", currency="CNY",
                counterparty="商户", counterparty_account="", counterparty_account_attrs=[], note="人工银行备注",
                record_type="consumption", record_subtype="not_applicable", row_revision=1, created_at=NOW,
                relation_metadata={"summary": "支付宝", "card_number": "1234"}))
            session.flush()
            for identifier, amount in ((7503, "-12.30"), (7504, "12.30"), (7505, "-7.00")):
                session.add(CashTransactionComponentModel(id=identifier + 2000, workspace_id=WORKSPACE,
                    cash_transaction_id=identifier, account_id=9201, amount=amount, currency="CNY", ordinal=0,
                    label="", source_key="", metadata_json={}, created_at=NOW))
                session.add(CashEntryModel(id=f"entry-{identifier}", workspace_id=WORKSPACE,
                    root_transaction_id=identifier, revision=1, category_id=None, note="",
                    counterparty="类型变化对照", counterparty_account="", counterparty_account_attrs=[],
                    manual_fields=[], split_inherit=None))
            for part_id, account_id, amount, ordinal in ((9506, 9201, "-6.00", 0), (9507, 9204, "-6.30", 1)):
                session.add(CashTransactionComponentModel(id=part_id, workspace_id=WORKSPACE,
                    cash_transaction_id=7506, account_id=account_id, amount=amount, currency="CNY",
                    ordinal=ordinal, label="", source_key="", metadata_json={}, created_at=NOW))
            session.add(CashEntryModel(id="entry-7506", workspace_id=WORKSPACE,
                root_transaction_id=7506, revision=1, category_id=None, note="", counterparty="多现金部分",
                counterparty_account="", counterparty_account_attrs=[], manual_fields=[], split_inherit=None))
            session.add(CashTransactionComponentModel(id=9508, workspace_id=WORKSPACE,
                cash_transaction_id=7507, account_id=9201, amount="-5.00", currency="CNY", ordinal=0,
                label="", source_key="", metadata_json={}, created_at=NOW))
            session.add(AccountAliasModel(workspace_id=WORKSPACE, account_id=9201, alias_type="card_tail",
                alias_value="1234", created_at=NOW, updated_at=NOW))
            session.add(AccountAliasModel(workspace_id=WORKSPACE, account_id=9201, alias_type="account_identifier",
                alias_value="fixture-cash-9201", created_at=NOW, updated_at=NOW))
            session.flush()
            for identifier in (7503, 7504, 7505, 7506):
                session.add(CashEntryMemberModel(workspace_id=WORKSPACE, transaction_id=identifier,
                    entry_id=f"entry-{identifier}", standalone_values={}, standalone_manual_fields=[]))
            investment = InvestmentEventModel(workspace_id=WORKSPACE, account_id=9203, record_type="funding",
                record_subtype="external", currency="CNY", occurred_at=NOW, payload={"fixture": "funding"},
                to_amount="7.00")
            session.add(investment)
            session.flush()
            session.add(CashInvestmentFundingRelationModel(workspace_id=WORKSPACE,
                cash_transaction_component_id=9505, investment_event_id=investment.id,
                direction="cash_to_investment", status="accepted", active_slot="active", rule_id="fixture"))
            relations = CurrentCashRelationRepository(session, WORKSPACE)
            for kind, secondary, role in (("payment_mirror", 7503, "expense"), ("refund_offset", 7504, "refund")):
                relations.add({"kind": kind, "state": "confirmed", "primary_transaction_id": 7501,
                    "secondary_transaction_id": secondary, "primary_component_id": 9501,
                    "secondary_component_id": secondary + 2000, "anchor_transaction_id": 7501,
                    "anchor_component_id": 9501, "primary_role": "expense", "secondary_role": role,
                    "applied_amount": "12.30"})
        source_files = files()
        selections = [{"source_account_ref": group.source_account_ref, "account_id": "9201",
                       "expected_account_revision": 1} for group in scan_cash_funding(source_files).groups]
        choices = [{"row_ref": "f_a" + "0" * 31 + ":r1", "action": "keep_existing"},
                   {"row_ref": "f_a" + "0" * 31 + ":r2", "action": "apply",
                    "patch": {"note": "人工备注", "category_id": "op-food"}},
                   {"row_ref": "f_w" + "0" * 31 + ":r1", "action": "apply", "patch": {"note": "新备注"}}]
        draft_choices = [{"row_ref": choice["row_ref"], "action": "defer"} for choice in choices]
        builder = CashImportBusinessPlanBuilder(WORKSPACE, clock=lambda: NOW)
        cases = []
        for name, previous, entry_choices, extra in (
            ("normal", None, [], []),
            ("retained_target", {"entry_targets": {"ie-good": {"parent_ids": ["7501"]}}},
             [{"edit_ref": "ie-good"}], [7502]),
            ("file_scope_fallback", None, [], []),
            ("draft_only", None, [], []),
            ("type_change_remove_mirror", None, [], []),
            ("type_change_restore_graph_error", None, [], []),
            ("type_change_remove_funding", None, [], []),
            ("type_change_aggregate", None, [], []),
        ):
            with sessions() as session:
                selected_choices = draft_choices if name == "draft_only" else choices
                source_plan = CashImportSourcePlanner(session, WORKSPACE).prepare(source_files)
                if name == "file_scope_fallback":
                    ref = "f_a" + "0" * 31 + ":r2"
                    source_plan = replace(source_plan, rows={**source_plan.rows,
                        ref: replace(source_plan.rows[ref], source_account_key=None, provider_id=None)})
                raw = CashImportOperationPlanner(session, WORKSPACE).prepare(source_files,
                    account_selections=selections, row_choices=selected_choices, source_plan=source_plan)
                source_rows = {ref: {"source_type": row.source_type,
                    "source_account_key": row.source_account_key, "file_digest": row.file_digest,
                    "parser_version": row.parser_version, "table_key": row.table_key,
                    "position": row.position, "provider_id": row.provider_id}
                    for ref, row in raw.source_plan.rows.items()}
                operations, rows, candidate_sets, state = builder._prepare_operations(
                    session, source_files, {"account_selections": selections, "row_choices": selected_choices,
                    "entry_choices": entry_choices}, previous, source_plan)
                if name.startswith("type_change_"):
                    changed = next(op for op in operations if op["transaction_id"] == "7501")
                    changed["planned_action"] = "update_posted"
                    changed["type_changed"] = True
                    changed["values"]["record_type"] = "investment_out"
                    if name == "type_change_restore_graph_error":
                        changed["values"]["amount"] = Decimal("-6.15")
                        changed["values"]["components"][0]["amount"] = Decimal("-6.15")
                    if name == "type_change_remove_funding":
                        changed["transaction_id"] = "7505"
                        changed["matching_record_id"] = "TYPE-7505"
                        changed["values"].update(record_type="consumption", record_id="TYPE-7505",
                            source_type="fixture", amount=Decimal("-7.00"), components=[{
                                "component_id": "9505", "account_id": "9201", "amount": Decimal("-7.00"),
                                "currency": "CNY", "ordinal": 0}])
                    if name == "type_change_aggregate":
                        changed["transaction_id"] = "7506"
                        changed["matching_record_id"] = "TYPE-7506"
                        changed["values"].update(record_id="TYPE-7506", source_type="fixture",
                            account_id=None, cash_granularity="aggregate", amount=Decimal("-12.30"),
                            components=[{"component_id": "9506", "account_id": "9201",
                                "amount": Decimal("-6.00"), "currency": "CNY", "ordinal": 0},
                                {"component_id": "9507", "account_id": "9204",
                                "amount": Decimal("-6.30"), "currency": "CNY", "ordinal": 1}])
                accounts, candidates, retained = state
                existing = {int(op["transaction_id"]) for op in operations if op.get("transaction_id")}
                context = CashRelationGraphService(session, WORKSPACE).load(existing | candidates | set(extra))
                cases.append({"name": name, "raw_operations": _wire(list(raw.operations)),
                    "source_rows": source_rows, "previous_plan": previous, "entry_choices": entry_choices,
                    "additional_seed_ids": extra, "operations": _wire(operations), "rows": _wire(rows),
                    "candidate_sets": candidate_sets, "accounts": accounts,
                    "candidate_ids": sorted(candidates), "retained_parent_ids": sorted(retained),
                    "context_seed_ids": sorted(context.seed_ids),
                    "context_transaction_ids": sorted(context.transaction_ids), "context_token": context.token})
                snapshot = builder._snapshot(session, context, operations, accounts, rows, normalized_operations=True)
                discovery_seeds = {part.component_id for part in snapshot.components.values()
                    if snapshot.parent_ops.get(part.transaction_id, {}).get("planned_action") in {"create_posted", "finalize_draft"}}
                proposals, failures = builder._discover(snapshot, discovery_seeds, accounts, session)
                cases[-1].update(matching_facts=[_wire(asdict(fact)) for fact in builder._matching_facts(snapshot, accounts)],
                    discovery_seeds=sorted(discovery_seeds), proposals=[_wire(asdict(proposal)) for proposal in proposals],
                    discovery_failures=failures)
                cases[-1]["snapshot"] = {
                    "values": _wire(snapshot.values),
                    "facts": {key: _wire(asdict(value)) for key, value in snapshot.facts.items()},
                    "components": {key: _wire(asdict(value)) for key, value in snapshot.components.items()},
                    "edges": {str(key): _wire(asdict(value)) for key, value in snapshot.edges.items()},
                    "funding": {str(key): _wire(value) for key, value in snapshot.funding.items()},
                    "parent_ops": {key: value["operation_ref"] for key, value in snapshot.parent_ops.items()},
                    "entry_public": _wire(snapshot.entry_public),
                    "removed_relation_ids": sorted(snapshot.removed_relations),
                    "removed_funding_ids": sorted(snapshot.removed_funding),
                    "operations": _wire(operations),
                    "rows": _wire(rows),
                    "projections": [{"projection_id": item.projection_id, "net_amount": _wire(item.net_amount),
                        "economic_type": item.economic_type.value if item.economic_type else None,
                        "member_ids": list(item.member_ids), "relation_ids": [edge.id for edge in item.relations],
                        "compositions": list(item.compositions), "funding_relation_id": item.funding_relation_id,
                        "state": item.state, "attention_count": item.attention_count}
                        for item in snapshot.build().projections],
                }
        try:
            builder._entry_parents([{"edit_ref": "missing"}], {"entry_targets": {}})
            raise AssertionError("missing entry target unexpectedly accepted")
        except CashImportPlanError as error:
            invalid_target_error = error.code
        output.mkdir(parents=True, exist_ok=True)
        (output / "database.json").write_text(json.dumps({"jdbc_url": jdbc}) + "\n")
        (output / "preparation-cases.json").write_text(json.dumps(
            [case for case in cases if not case["name"].startswith("type_change_")], ensure_ascii=False, indent=2) + "\n")
        (output / "snapshot-cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n")
        (output / "invalid-target.json").write_text(json.dumps({"code": invalid_target_error}) + "\n")
    finally:
        engine.dispose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    export(args.database_url, args.output)
