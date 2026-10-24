"""原子草稿只传真实标量修改，资金联动使用来源处理后的有效值。"""
from decimal import Decimal

import pytest

from sqlalchemy import select

from ft.adapters.relational.models import AccountModel
from tests.contract.test_cash_import_commit import configured
from tests.contract.test_cash_record_maintenance import _draft, _service as _records
from tests.contract.test_cash_mutation_business_plan import _preview, _members
from tests.contract.test_cash_mutation_commit import _service, _commit
from tests.contract.test_cash_source_issue_resolution import _issue
from tests.contract.test_parsed_cash_import_context import context_runtime


def test_account_only_patch_preserves_adopted_source_amount_and_currency(context_runtime):
    runtime, identifier = context_runtime, _draft(context_runtime)
    with runtime.sessions.begin() as session:
        session.scalar(select(AccountModel).where(AccountModel.workspace_id == runtime.workspace_id, AccountModel.id == 101)).currencies = ["CNY", "USD"]
    choice = _issue(runtime, identifier, fields={"amount": "-14.0010", "currency": "USD"})
    service = _service(runtime)
    before = _records(runtime).read(identifier)
    view = _preview(service, runtime, identifier, intent="resolve_issue", patch={"account_id": "101"}, issue_resolutions=[choice])
    values = _members(service, view)[identifier]["fields"]
    assert values["amount"] == "-14.0010" and values["currency"] == "USD"
    assert [(part["amount"], part["currency"], part["account_id"]) for part in values["components"]] == [("-14.0010", "USD", "101")]
    assert _records(runtime).read(identifier) == before
    assert _commit(service, view)["status"] == "completed"
    current = _records(runtime).read(identifier)
    assert current["state"] == "draft" and Decimal(current["fields"]["components"][0]["amount"]) == Decimal("-14.0010")


def test_scalar_draft_save_updates_unique_cash_part_without_expanding_old_values(context_runtime):
    runtime, identifier = context_runtime, _draft(context_runtime)
    service = _service(runtime)
    view = _preview(service, runtime, identifier, intent="save_draft", patch={"account_id": "101", "amount": "-19.00200"})
    values = _members(service, view)[identifier]["fields"]
    assert values["components"][0]["amount"] == "-19.00200" and values["components"][0]["account_id"] == "101"
    assert _commit(service, view)["status"] == "completed"
    current = _records(runtime).read(identifier)
    assert current["state"] == "draft" and Decimal(current["fields"]["amount"]) == Decimal("-19.00200")


def test_explicit_parts_and_split_drafts_are_not_implicitly_rewritten(context_runtime):
    runtime, identifier = context_runtime, _draft(context_runtime)
    records, service = _records(runtime), _service(runtime)
    before = records.read(identifier)
    part = before["fields"]["components"][0]
    explicit = {**part, "amount": "-9.000"}
    view = _preview(service, runtime, identifier, intent="save_draft", patch={"account_id": "101", "components": [explicit]})
    assert _members(service, view)[identifier]["fields"]["components"] == [explicit]
    records.save_draft(identifier, expected_revision=before["row_revision"], idempotency_key="synthetic-split-linkage", patch={
        "cash_granularity": "aggregate", "components": [{**part, "amount": "-6"}, {"ordinal": 1, "amount": "-6.5", "currency": "CNY", "account_id": None}]})
    split = records.read(identifier)
    view = _preview(service, runtime, identifier, intent="save_draft", patch={"account_id": "101", "amount": "-20"})
    assert _members(service, view)[identifier]["fields"]["components"] == split["fields"]["components"]


@pytest.mark.parametrize("part_amount", [None, "-15.0020"])
def test_source_explicit_atomic_part_is_preserved_when_only_account_is_changed(context_runtime, part_amount):
    runtime, identifier = context_runtime, _draft(context_runtime)
    with runtime.sessions.begin() as session:
        session.scalar(select(AccountModel).where(AccountModel.workspace_id == runtime.workspace_id, AccountModel.id == 101)).currencies = ["CNY", "USD"]
    before = _records(runtime).read(identifier)
    part = before["fields"]["components"][0]
    choice = _issue(runtime, identifier, fields={"amount": "-14", "currency": "USD", "components": [{**part, "amount": part_amount, "currency": "USD"}]})
    service = _service(runtime)
    view = _preview(service, runtime, identifier, intent="resolve_issue", patch={"account_id": "101"}, issue_resolutions=[choice])
    values = _members(service, view)[identifier]["fields"]
    assert values["components"][0]["amount"] == part_amount
    assert values["components"][0]["currency"] == "USD" and values["components"][0]["account_id"] == "101"


def test_source_explicit_empty_parts_are_not_reconstructed_from_parent_values(context_runtime):
    runtime, identifier = context_runtime, _draft(context_runtime)
    choice = _issue(runtime, identifier, fields={"components": []})
    service = _service(runtime)
    view = _preview(service, runtime, identifier, intent="resolve_issue", patch={"account_id": "101"}, issue_resolutions=[choice])
    fields = _members(service, view)[identifier]["fields"]
    assert fields["components"] == [] and fields["account_id"] == "101"
    assert _commit(service, view)["status"] == "completed"
    current = _records(runtime).read(identifier)
    assert current["state"] == "draft" and current["fields"]["components"] == []
    assert current["fields"]["account_id"] == "101"
