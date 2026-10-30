from types import SimpleNamespace

import pytest

from ft.adapters.relational.repositories import RelationalRelationRepository
from ft.application.cash_ledger import CashLedgerCommandService
from ft.application.relations import RelationService
from ft.application.statement_import import StatementImportService


class Untouched:
    def __getattr__(self, name):
        raise AssertionError(f"input rejection must precede {name}")

    def __enter__(self):
        raise AssertionError("input rejection must precede the transaction")


@pytest.mark.parametrize("subtype", ["", None, "currency_exchange"])
@pytest.mark.parametrize("operation", ["add", "update", "repository", "supersede"])
def test_removed_relation_field_rejected_before_transaction_or_endpoint_reads(subtype, operation):
    payload = {
        "kind": "transfer_pair", "primary_fact_id": "1", "secondary_fact_id": "2",
        "subtype": subtype,
    }
    with pytest.raises(ValueError, match="relation_data_invalid"):
        if operation == "repository":
            repository = RelationalRelationRepository(Untouched(), "workspace")
            repository.add(payload)
        elif operation == "supersede":
            RelationService(Untouched()).supersede("3", replacement=payload)
        else:
            service = object.__new__(CashLedgerCommandService)
            service._uow = Untouched()
            if operation == "add":
                service.add_relation(payload)
            else:
                service.update_relation("3", payload)


@pytest.mark.parametrize("subtype", ["", None, "p2p_return"])
@pytest.mark.parametrize("status", ["accepted", "rejected", "skipped", "ignored"])
@pytest.mark.parametrize("operation", ["statement", "statement_decisions", "plan", "cached"])
def test_removed_decision_field_rejected_before_any_batch_work(subtype, status, operation):
    decisions = [{"status": "skipped"}, {"status": status, "subtype": subtype}]
    with pytest.raises(ValueError, match="import_relation_candidate_invalid"):
        if operation in {"statement", "statement_decisions"}:
            service = StatementImportService(Untouched(), Untouched(), relation_service=Untouched())
            if operation == "statement":
                service.import_statement(Untouched(), relation_decisions=decisions)
            else:
                service._apply_relation_decisions(Untouched(), decisions, fact_id_by_record_id={})
        else:
            service = RelationService(Untouched())
            if operation == "plan":
                service.apply_import_plan_in_uow(Untouched(), seed_ids=[], relation_decisions=decisions)
            else:
                service.apply_cached_import_plan_in_uow(
                    Untouched(), cached_plan={}, relation_decisions=decisions,
                )


@pytest.mark.parametrize("subtype", ["", None, "p2p_return"])
def test_removed_cache_field_requires_reconfirmation_before_fact_reads(subtype):
    service = RelationService(Untouched())
    with pytest.raises(ValueError, match="import_relation_reconfirmation_required"):
        service._cached_proposals_in_uow(
            Untouched(), {"version": 1, "proposals": [{"subtype": subtype}]},
        )


@pytest.mark.parametrize("operation", ["context", "statement_enabled", "statement_disabled"])
@pytest.mark.parametrize("subtype", ["", None, "currency_exchange"])
def test_removed_cached_proposal_rejected_before_import_or_context_reads(operation, subtype):
    plan = {"version": 1, "plan_digest": "digest", "external_context_digest": "context",
        "proposals": [{"subtype": subtype}]}
    with pytest.raises(ValueError, match="import_relation_reconfirmation_required"):
        if operation == "context":
            RelationService(Untouched()).validate_cached_import_plan_context_in_uow(Untouched(), plan)
        else:
            service = StatementImportService(Untouched(), Untouched(),
                run_relation_check=operation == "statement_enabled")
            service.import_statement(Untouched(), cached_relation_plan=plan)


@pytest.mark.parametrize("matcher", ["_decision_matches", "_decision_primary_matches"])
def test_removed_field_cannot_match_a_proposal(matcher):
    proposal = SimpleNamespace(primary_fact_id="1", secondary_fact_id="2")
    with pytest.raises(ValueError, match="import_relation_candidate_invalid"):
        getattr(RelationService, matcher)(proposal, {"primary_fact_id": "1", "subtype": ""}, [])


@pytest.mark.parametrize("runtime_name", ["cash_web_runtime", "postgres_cash_web_runtime"])
def test_removed_fields_preserve_all_stored_facts_and_revisions(request, runtime_name):
    from sqlalchemy import select
    from ft.adapters.relational.models import Base
    from ft.adapters.relational.uow import RelationalUnitOfWork

    runtime = request.getfixturevalue(runtime_name)
    uow = RelationalUnitOfWork(runtime.sessions, runtime.workspace_id)
    relations = RelationService(uow)
    ledger = CashLedgerCommandService(runtime.sessions, runtime.workspace_id, relation_service=relations)
    statement = StatementImportService(uow, Untouched(), relation_service=relations)

    def snapshot():
        with runtime.sessions() as session:
            return {table.name: sorted((tuple(row) for row in session.execute(select(table))), key=repr)
                for table in Base.metadata.sorted_tables}

    before = snapshot()
    for subtype in (None, "", "ordinary_transfer"):
        payload = {"primary_fact_id": "1002", "secondary_fact_id": "1003",
            "kind": "payment_mirror", "subtype": subtype}
        for action in (lambda: ledger.add_relation(payload), lambda: ledger.update_relation("1", payload)):
            with pytest.raises(ValueError, match="relation_data_invalid"):
                action()
        with uow as transaction:
            with pytest.raises(ValueError, match="relation_data_invalid"):
                transaction.relations.add(payload)
            for status in ("accepted", "rejected", "skipped", "ignored"):
                decisions = [{**payload, "status": status}]
                actions = (
                    lambda: relations.apply_import_plan_in_uow(transaction, seed_ids=[], relation_decisions=decisions),
                    lambda: relations.apply_cached_import_plan_in_uow(transaction, cached_plan={}, relation_decisions=decisions),
                    lambda: statement._apply_relation_decisions(transaction, decisions, fact_id_by_record_id={}),
                )
                for action in actions:
                    with pytest.raises(ValueError, match="import_relation_candidate_invalid"):
                        action()
            transaction.commit()
        assert snapshot() == before
