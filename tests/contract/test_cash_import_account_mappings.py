"""账户默认只从实际成功使用的整组选择发布，建议不跨来源范围。"""
from dataclasses import replace

import pytest
from sqlalchemy import select

from ft.adapters.relational.models import AccountAliasModel, AccountModel, StatementAccountMappingModel
from ft.application.cash_import_funding import scan_cash_funding
from tests.contract.test_cash_import_commit import configured, _commit, _plan, _results, _service
from tests.contract.test_cash_import_operations import _as_source, _file, _selections
from tests.contract.test_parsed_cash_import_context import USER, context_runtime


def _mappings(runtime):
    with runtime.sessions() as session:
        return [(row.source_type, row.identity_kind, row.source_account_key, str(row.account_id), row.revision)
            for row in session.scalars(select(StatementAccountMappingModel).where(
                StatementAccountMappingModel.workspace_id == runtime.workspace_id).order_by(StatementAccountMappingModel.id))]


def _suggest(runtime, files):
    from ft.application.cash_import_account_mappings import CashImportAccountMappings
    with runtime.sessions() as session:
        result = CashImportAccountMappings(session, runtime.workspace_id).suggest(scan_cash_funding(files).groups)
        assert not session.new and not session.dirty
        return result


def _preview(runtime, files, choices=(), account_id="101"):
    service = _service(runtime)
    staged = service._staging.create(workspace_id=runtime.workspace_id, user_id=USER, files=files)
    plan = service.preview(staged.session_id, expected_session_revision=1,
        account_selections=_selections(files, account_id=account_id), row_choices=list(choices))
    return service, plan


def _override(ref, account_id="102"):
    return {"row_ref": ref, "action": "apply", "patch": {"account_id": account_id,
        "components": [{"ordinal": 0, "account_id": account_id, "amount": "-12.5", "currency": "CNY"}]}}


@pytest.mark.parametrize("draft", [False, True])
def test_only_final_confirmation_saves_used_mapping_and_replay_is_noop(context_runtime, draft):
    runtime, file = context_runtime, _file()
    if draft:
        row = file.document.rows[0]
        file = replace(file, document=replace(file.document, rows=(replace(row,
            fields={**row.fields, "occurred_at": None}, issue_codes=("time_unreadable",)),)))
    service, plan = _plan(runtime, (file,))
    assert _mappings(runtime) == []
    receipt = _commit(service, plan)
    assert receipt["summary"]["saved_draft" if draft else "created_posted"] == 1
    group, = scan_cash_funding((file,)).groups
    expected = [("alipay", "funding_group_v1", group.source_account_ref, "101", 1)]
    assert _mappings(runtime) == expected
    assert _commit(service, plan)["receipt_id"] == receipt["receipt_id"]
    assert _mappings(runtime) == expected
    service, plan = _plan(runtime, (replace(file, file_ref="file_b", client_file_ref="client_b"),))
    _commit(service, plan, "synthetic-second-import")
    assert _mappings(runtime) == expected
    suggestion = _suggest(runtime, (file,))[group.source_account_ref]
    assert suggestion["account_id"] == "101" and suggestion["basis"] == "confirmed_mapping"
    assert type(suggestion["expected_account_revision"]) is int


@pytest.mark.parametrize("success", ["none", "one", "override_only"])
def test_any_row_failure_rolls_back_all_shared_account_defaults(context_runtime, monkeypatch, success):
    from ft.application.cash_record_writer import CashRecordWriter
    runtime, first, second = context_runtime, _file(), _file(letter="b", provider="002")
    if success == "one":
        second = replace(second, document=replace(second.document,
            rows=(replace(second.document.rows[0], payment_method="其他合成支付账户"),)))
    files = (first, second)
    choices = [_override("file_b:r1")] if success == "override_only" else []
    service, plan = _preview(runtime, files, choices)
    original = CashRecordWriter.save
    def save(self, session, operation, source_rows):
        result = original(self, session, operation, source_rows)
        if success == "none" or operation["row_refs"] == ["file_a:r1"]:
            raise ValueError("synthetic row failure after write")
        return result
    monkeypatch.setattr(CashRecordWriter, "save", save)
    from tests.contract.test_cash_import_commit import _assert_atomic_failure
    _assert_atomic_failure(runtime, lambda: _commit(service, plan))
    assert _mappings(runtime) == []


def test_note_patch_and_same_account_coalescing_keep_both_source_proofs(context_runtime):
    runtime, file = context_runtime, _file()
    file = replace(file, document=replace(file.document, rows=(replace(file.document.rows[0],
        payment_method="合成支付甲 & 合成支付乙"),)))
    service, plan = _preview(runtime, (file,), [{"row_ref": "file_a:r1", "action": "apply", "patch": {"note": "人工备注"}}])
    receipt = _commit(service, plan)
    assert receipt["summary"]["created_posted"] == 1
    assert {row[2] for row in _mappings(runtime)} == {group.source_account_ref for group in scan_cash_funding((file,)).groups}
    assert all(row[3:] == ("101", 1) for row in _mappings(runtime))


def test_explicitly_deferred_draft_remembers_eligible_account(context_runtime):
    runtime, file = context_runtime, _file()
    service, plan = _preview(runtime, (file,), [{"row_ref": "file_a:r1", "action": "defer"}])
    assert _commit(service, plan)["summary"]["saved_draft"] == 1
    assert [row[3:] for row in _mappings(runtime)] == [("101", 1)]


def test_relation_failure_rolls_back_all_account_defaults(context_runtime, monkeypatch):
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    from tests.contract.test_cash_import_commit import _mirror_files
    runtime, files = context_runtime, _mirror_files()
    service, plan = _plan(runtime, files)
    original = CurrentCashRelationRepository.add
    def fail(self, values):
        original(self, values)
        raise ValueError("synthetic relation failure")
    monkeypatch.setattr(CurrentCashRelationRepository, "add", fail)
    from tests.contract.test_cash_import_commit import _assert_atomic_failure
    _assert_atomic_failure(runtime, lambda: _commit(service, plan))
    assert _mappings(runtime) == []


@pytest.mark.parametrize("existing", [False, True])
def test_currency_ineligible_draft_does_not_create_or_replace_default(context_runtime, existing):
    runtime, file = context_runtime, _file()
    if existing:
        service, plan = _plan(runtime, (file,))
        _commit(service, plan)
        file = _file(letter="b", provider="002")
    before = _mappings(runtime)
    with runtime.sessions.begin() as session:
        session.get(AccountModel, 102).currencies = ["USD"]
    service, plan = _preview(runtime, (file,), account_id="102")
    assert _commit(service, plan, "synthetic-currency-draft")["summary"]["saved_draft"] == 1
    assert _mappings(runtime) == before


def test_changing_default_does_not_switch_or_invalidate_frozen_account_choice(context_runtime):
    runtime, file = context_runtime, _file()
    service, plan = _plan(runtime, (file,))
    _commit(service, plan)
    second = _file(letter="b", provider="002")
    service, plan = _plan(runtime, (second,))
    with runtime.sessions.begin() as session:
        mapping = session.scalar(select(StatementAccountMappingModel).where(
            StatementAccountMappingModel.workspace_id == runtime.workspace_id,
            StatementAccountMappingModel.identity_kind == "funding_group_v1"))
        mapping.account_id, mapping.revision = 102, 2
        session.get(AccountModel, 101).name = "账户展示名称已变化"
    receipt = _commit(service, plan, "synthetic-frozen-choice")
    assert receipt["summary"]["created_posted"] == 1 and receipt["summary"]["skipped"] == 0
    assert [row[3:] for row in _mappings(runtime)] == [("101", 3)]


@pytest.mark.parametrize("change", ["inactive", "currency"])
def test_posted_mutable_update_does_not_require_remembering_an_ineligible_default(context_runtime, change):
    from tests.contract.test_cash_import_operations import _bind_existing
    from ft.adapters.relational.models import CashTransactionModel
    runtime, original = context_runtime, _file()
    with runtime.sessions.begin() as session:
        _bind_existing(session, runtime, original)
    file = _file(letter="b", note="更新后的来源原文")
    service, plan = _plan(runtime, (file,))
    assert plan["summary"]["update_posted"] == 1
    with runtime.sessions.begin() as session:
        account = session.get(AccountModel, 101)
        if change == "inactive":
            account.active = False
        else:
            account.currencies = ["USD"]
    receipt = _commit(service, plan)
    assert receipt["summary"]["updated_posted"] == 1 and receipt["summary"]["skipped"] == 0
    assert _mappings(runtime) == []
    with runtime.sessions() as session:
        assert session.get(CashTransactionModel, 1003).note == "更新后的来源原文"


@pytest.mark.parametrize("ambiguous", [False, True])
def test_only_exact_unique_eligible_bank_alias_is_suggested(context_runtime, ambiguous):
    runtime, owner = context_runtime, "1234567890123456"
    file = _source(_file(), "ccb_debit", owner)
    group, = scan_cash_funding((file,)).groups
    with runtime.sessions.begin() as session:
        session.add(AccountAliasModel(workspace_id=runtime.workspace_id,
            alias_type="account_identifier", alias_value=owner, account_id=101))
        session.add(AccountAliasModel(workspace_id=runtime.workspace_id,
            alias_type="card_tail", alias_value=owner[-4:], account_id=102))
        if ambiguous:
            session.add(AccountAliasModel(workspace_id=runtime.workspace_id,
                alias_type="account_identifier", alias_value=owner, account_id=102))
    suggestion = _suggest(runtime, (file,))[group.source_account_ref]
    assert suggestion["account_id"] == (None if ambiguous else "101")
    assert suggestion["basis"] == ("none" if ambiguous else "account_alias")
    assert _mappings(runtime) == []


@pytest.mark.parametrize("stage", ["mapping", "receipt"])
def test_mapping_and_records_rollback_together_on_publication_failure(context_runtime, monkeypatch, stage):
    from ft.adapters.relational.cash_mutations import CashMutationRepository
    from ft.application.cash_import_account_mappings import CashImportAccountMappings
    from ft.adapters.relational.models import CashTransactionModel
    runtime = context_runtime
    service, plan = _plan(runtime)
    owner, name = (CashImportAccountMappings, "publish") if stage == "mapping" else (CashMutationRepository, "create")
    original = getattr(owner, name)
    def fail(*args, **kwargs):
        original(*args, **kwargs)
        raise RuntimeError("synthetic publication failure")
    monkeypatch.setattr(owner, name, fail)
    from ft.application.cash_commit_outcome import CashCommitFailure
    with pytest.raises(CashCommitFailure) as failure:
        _commit(service, plan)
    assert failure.value.commit_state == "not_committed"
    assert _mappings(runtime) == []
    with runtime.sessions() as session:
        assert not session.scalar(select(CashTransactionModel).where(CashTransactionModel.source_type == "alipay"))


def _source(file, source, owner="fixture-owner"):
    return _as_source(file, source, owner=owner)


@pytest.mark.parametrize("other_owner", ["other-owner", None])
def test_alipay_mapping_does_not_cross_source_account(context_runtime, other_owner):
    runtime = context_runtime
    source = "alipay"
    owner = None if other_owner is None else "fixture-owner"
    first = _source(_file(), source, owner)
    service, plan = _plan(runtime, (first,))
    _commit(service, plan)
    second = _source(_file(letter="b", provider="002"), source, other_owner)
    own = scan_cash_funding((first,)).groups[0].source_account_ref
    other = scan_cash_funding((second,)).groups[0].source_account_ref
    assert own != other
    assert _suggest(runtime, (first,))[own]["account_id"] == "101"
    assert _suggest(runtime, (second,))[other]["account_id"] is None


@pytest.mark.parametrize("change", ["inactive", "type", "currency", "workspace"])
def test_suggestions_recheck_account_eligibility_without_writes(context_runtime, change):
    runtime, file = context_runtime, _file()
    group, = scan_cash_funding((file,)).groups
    with runtime.sessions.begin() as session:
        if change == "workspace":
            # 其他工作区合法映射不能成为当前工作区的默认。
            account = session.get(AccountModel, 104)
            session.add(StatementAccountMappingModel(workspace_id=account.workspace_id, source_type="alipay",
                identity_kind="funding_group_v1", source_account_key=group.source_account_ref, account_id=104))
        else:
            session.add(StatementAccountMappingModel(workspace_id=runtime.workspace_id, source_type="alipay",
                identity_kind="funding_group_v1", source_account_key=group.source_account_ref, account_id=101))
            account = session.get(AccountModel, 101)
            if change == "inactive":
                account.active = False
            elif change == "type":
                account.type = "security"
            else:
                account.currencies = ["USD"]
    before = _mappings(runtime)
    expected = "101" if change == "currency" else None
    assert _suggest(runtime, (file,))[group.source_account_ref]["account_id"] == expected
    assert _mappings(runtime) == before


@pytest.mark.parametrize("source,kind,accepted", [
    ("alipay", "payment_method", False), ("icbc_credit", "file_account", True),
    ("ccb_debit", "card_tail", False)])
def test_legacy_mapping_is_only_a_narrow_readonly_hint(context_runtime, source, kind, accepted):
    runtime = context_runtime
    owner = "1234567890123456" if source in {"icbc_credit", "ccb_debit"} else None
    file = _source(_file(), source, owner)
    group, = scan_cash_funding((file,)).groups
    key = owner[-4:] if kind == "card_tail" else owner if kind == "file_account" else group.identity_key
    with runtime.sessions.begin() as session:
        session.add(StatementAccountMappingModel(workspace_id=runtime.workspace_id, source_type=source,
            identity_kind=kind, source_account_key=key, account_id=101))
    before = _mappings(runtime)
    assert _suggest(runtime, (file,))[group.source_account_ref]["account_id"] == ("101" if accepted else None)
    assert _mappings(runtime) == before


def test_wechat_never_reads_persistent_mapping_or_alias_suggestions(context_runtime, monkeypatch):
    from ft.adapters.relational.cash_funding_mappings import CashFundingMappingRepository
    runtime = context_runtime
    file = _source(_file(), "wechat", None)
    group, = scan_cash_funding((file,)).groups
    with runtime.sessions.begin() as session:
        session.add(StatementAccountMappingModel(workspace_id=runtime.workspace_id, source_type="wechat",
            identity_kind="funding_group_v1", source_account_key=group.source_account_ref, account_id=101))
        session.add(StatementAccountMappingModel(workspace_id=runtime.workspace_id, source_type="wechat",
            identity_kind="payment_method", source_account_key=group.identity_key, account_id=101))
        session.add(AccountAliasModel(workspace_id=runtime.workspace_id, alias_type="account_identifier",
            alias_value="1234567890123456", account_id=101))
    def forbidden(*_args, **_kwargs):
        pytest.fail("WeChat suggestions must not query persistent account mappings")
    monkeypatch.setattr(CashFundingMappingRepository, "lookup", forbidden)
    suggestion = _suggest(runtime, (file,))[group.source_account_ref]
    assert suggestion == {"account_id": None, "expected_account_revision": None, "basis": "none"}


def test_wechat_commit_does_not_publish_cross_import_account_mapping(context_runtime):
    runtime, file = context_runtime, _source(_file(), "wechat", None)
    service, plan = _plan(runtime, (file,))
    _commit(service, plan)
    assert _mappings(runtime) == []
    group, = scan_cash_funding((file,)).groups
    assert _suggest(runtime, (file,))[group.source_account_ref]["account_id"] is None
