"""导入只依赖账户当前资金资格，展示变化不使确认失效。"""
from dataclasses import replace

import pytest
from sqlalchemy import delete

from ft.adapters.relational.models import AccountModel, CashTransactionModel
from tests.contract.test_cash_import_commit import configured, _commit, _plan, _results, _service
from tests.contract.test_cash_import_operations import _file, _selections
from tests.contract.test_parsed_cash_import_context import USER, context_runtime


ACCOUNT_ID = "5001"


def _account(runtime):
    with runtime.sessions.begin() as session:
        session.add(AccountModel(id=int(ACCOUNT_ID), workspace_id=runtime.workspace_id,
            name="合成导入账户", type="cash", active=True, currencies=["CNY", "EUR"]))


def _change(runtime, kind):
    with runtime.sessions.begin() as session:
        account = session.get(AccountModel, int(ACCOUNT_ID))
        if kind == "rename":
            account.name = "合成账户新名称"
        elif kind == "revision":
            account.revision += 1
        elif kind == "other_currency":
            account.currencies = ["CNY", "EUR", "USD"]
        elif kind == "remove_other_currency":
            account.currencies = ["CNY"]
        elif kind == "disable":
            account.active = False
        elif kind == "remove_currency":
            account.currencies = ["USD"]
        elif kind == "delete":
            session.execute(delete(AccountModel).where(AccountModel.id == int(ACCOUNT_ID)))
        else:
            raise AssertionError(kind)


@pytest.mark.parametrize("flow", ["new", "finalize", "financial_patch"])
@pytest.mark.parametrize("change", ["rename", "revision", "other_currency", "remove_other_currency"])
@pytest.mark.parametrize("phase", ["before_preview", "after_preview"])
def test_harmless_account_changes_preserve_current_valid_import(context_runtime, flow, change, phase):
    runtime = context_runtime
    _account(runtime)
    file, draft_id = _file(), None
    if flow == "finalize":
        initial, incomplete = _plan(runtime, (file,), mapped=False)
        saved = _commit(initial, incomplete, "synthetic-incomplete")
        draft_id = _results(initial, saved, "row")[0]["transaction_id"]
        file = _file(letter="b")
    service = _service(runtime)
    staged = service._staging.create(workspace_id=runtime.workspace_id, user_id=USER, files=(file,))
    choices = []
    if flow == "financial_patch":
        choices = [{"row_ref": "file_a:r1", "action": "apply", "patch": {
            "account_id": ACCOUNT_ID, "components": [{"ordinal": 0, "account_id": ACCOUNT_ID,
                "amount": "-12.5", "currency": "CNY"}]}}]
    if phase == "before_preview":
        _change(runtime, change)
    plan = service.preview(staged.session_id, expected_session_revision=1,
        account_selections=[] if choices else _selections((file,), account_id=ACCOUNT_ID), row_choices=choices)
    outcome = "finalized_draft" if flow == "finalize" else "created_posted"
    assert plan["summary"].get("finalize_draft" if flow == "finalize" else "create_posted") == 1
    if phase == "after_preview":
        _change(runtime, change)
    receipt = _commit(service, plan)
    assert receipt["summary"][outcome] == 1 and receipt["summary"]["skipped"] == 0
    result, = _results(service, receipt, "row")
    if draft_id is not None:
        assert result["transaction_id"] == draft_id
    with runtime.sessions() as session:
        record = session.get(CashTransactionModel, int(result["transaction_id"]))
        assert record.state == "posted" and record.account_id == int(ACCOUNT_ID)


@pytest.mark.parametrize("change", ["disable", "remove_currency", "delete"])
def test_lost_account_eligibility_skips_only_the_related_row(context_runtime, change):
    runtime = context_runtime
    _account(runtime)
    first, second = _file(), _file(letter="b", provider="002")
    second = replace(second, document=replace(second.document,
        rows=(replace(second.document.rows[0], payment_method="另一个合成支付账户"),)))
    service = _service(runtime)
    staged = service._staging.create(workspace_id=runtime.workspace_id, user_id=USER, files=(first, second))
    plan = service.preview(staged.session_id, expected_session_revision=1,
        account_selections=[*_selections((first,), account_id=ACCOUNT_ID), *_selections((second,))])
    assert plan["summary"]["create_posted"] == 2
    _change(runtime, change)
    receipt = _commit(service, plan)
    assert receipt["summary"]["created_posted"] == receipt["summary"]["skipped"] == 1
    rows = {row["item_ref"]: row for row in _results(service, receipt, "row")}
    assert rows["file_a:r1"]["outcome"] == "skipped"
    assert rows["file_a:r1"]["code"] == "account_changed"
    assert rows["file_b:r1"]["outcome"] == "created_posted"


def test_all_final_allocation_accounts_are_revalidated_after_manual_patch(context_runtime):
    runtime = context_runtime
    _account(runtime)
    first, second = _file(), _file(letter="b", provider="002")
    second = replace(second, document=replace(second.document,
        rows=(replace(second.document.rows[0], payment_method="另一个合成支付账户"),)))
    service = _service(runtime)
    staged = service._staging.create(workspace_id=runtime.workspace_id, user_id=USER, files=(first, second))
    plan = service.preview(staged.session_id, expected_session_revision=1,
        account_selections=_selections((second,)), row_choices=[{"row_ref": "file_a:r1", "action": "apply", "patch": {
            "account_id": None, "cash_granularity": "aggregate", "components": [
                {"ordinal": 0, "account_id": "102", "amount": "-5", "currency": "CNY"},
                {"ordinal": 1, "account_id": ACCOUNT_ID, "amount": "-7.5", "currency": "CNY"}]}}])
    assert plan["summary"]["create_posted"] == 2
    _change(runtime, "disable")
    receipt = _commit(service, plan)
    assert receipt["summary"]["created_posted"] == receipt["summary"]["skipped"] == 1
    results = {row["item_ref"]: row for row in _results(service, receipt, "row")}
    assert results["file_a:r1"]["outcome"] == "skipped"
    assert results["file_a:r1"]["code"] == "account_changed"
    assert results["file_b:r1"]["outcome"] == "created_posted"
