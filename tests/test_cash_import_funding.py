from copy import deepcopy
from dataclasses import replace
from decimal import Decimal, localcontext

import pytest

from ft.adapters.cash_statement_rows import ParsedCashDocument, ParsedCashRow
from ft.application.parsed_import_staging import ParsedImportFile
from ft.domain.cash_source_matching import SourceCoverage


def _file(source="alipay", methods=("招商银行储蓄卡(1234)",), *, amount="-30", owner="owner", label=None, ref="a", issues=(), product=""):
    display_names = {"icbc_credit": "工商银行信用卡", "icbc_debit": "工商银行借记卡",
        "ccb_debit": "建设银行", "icbc_asia": "工银亚洲"}
    rows = tuple(ParsedCashRow(index, {"amount": amount, "currency": "CNY", "occurred_at": "2026-10-01T10:00:00+00:00",
        "record_type": "consumption", "record_subtype": "not_applicable", "note": "  原始备注  ",
        "source_payload": {"付款方式": method, "商品说明": product}}, method, issue_codes=issues,
        source_explanation={"source_display_name": display_names[source] + (f"({owner[-4:]})" if source in display_names and owner else "")}
            if source in display_names else {}) for index, method in enumerate(methods, 1))
    document = ParsedCashDocument(source, "table", "native-v1", rows, owner, owner, SourceCoverage(), len(rows), len(rows))
    return ParsedImportFile(ref, ref, "fixture.csv", ref * 64, 100, source, "parsed", document, source_scope_label=label)


def _scan(*files):
    from ft.application.cash_import_funding import scan_cash_funding
    return scan_cash_funding(files)


def _resolve(scan, *, selections=None, accounts=None):
    from ft.application.cash_import_funding import resolve_cash_funding
    if selections is None:
        selections = {group.source_account_ref: {"account_id": str(index), "expected_account_revision": 3}
            for index, group in enumerate(scan.groups, 1)}
    if accounts is None:
        accounts = {str(index): {"id": str(index), "revision": 3, "active": True, "type": "cash", "currencies": ["CNY"]}
            for index, _ in enumerate(scan.groups, 1)}
    return resolve_cash_funding(scan, selections=selections, accounts=accounts)


def test_missing_mapping_keeps_known_values_and_does_not_block_mapped_row():
    scan = _scan(_file(methods=("银行卡(1234)", "余额")))
    first, second = _resolve(scan, selections={scan.groups[1].source_account_ref: {"account_id": "2", "expected_account_revision": 3}})
    assert not first.ready and first.fields["account_id"] is None and "account_missing" in first.issue_codes
    assert first.fields["amount"] == "-30" and first.fields["components"][0]["amount"] == "-30"
    assert second.ready and second.fields["account_id"] == "2"


def test_unknown_composite_allocation_keeps_one_parent_and_each_known_account():
    scan = _scan(_file(methods=("银行卡(1234)&余额&红包",)))
    assert len(scan.groups) == 2
    row, = _resolve(scan)
    assert not row.ready and row.fields["cash_granularity"] == "aggregate" and row.fields["account_id"] is None
    assert [component["amount"] for component in row.fields["components"]] == [None, None]
    assert [component["account_id"] for component in row.fields["components"]] == ["1", "2"]
    assert "allocation_missing" in row.issue_codes and row.fields["amount"] == "-30"


def test_explicit_allocations_are_signed_and_amount_is_not_part_of_group_identity():
    scan = _scan(_file(methods=("银行卡(1234)10元&余额20元", "银行卡(1234)20元&余额10元")))
    assert len(scan.groups) == 2
    rows = _resolve(scan)
    assert all(row.ready for row in rows)
    assert [component["amount"] for component in rows[0].fields["components"]] == ["-10", "-20"]
    assert rows[0].group_refs == rows[1].group_refs


@pytest.mark.parametrize("method", ["银行卡(1234)&银行卡(1234)", "银行卡(1234)10元&银行卡(1234)20元"])
def test_repeated_funding_account_is_one_component(method):
    row, = _resolve(_scan(_file(methods=(method,))))
    assert row.ready and row.fields["cash_granularity"] == "atomic"
    assert len(row.fields["components"]) == 1 and row.fields["components"][0]["amount"] == "-30"


def test_two_source_labels_mapped_to_one_internal_account_need_no_allocation():
    scan = _scan(_file(methods=("银行卡(1234)&余额",)))
    selections = {group.source_account_ref: {"account_id": "1", "expected_account_revision": 3} for group in scan.groups}
    row, = _resolve(scan, selections=selections)
    assert row.ready and row.fields["cash_granularity"] == "atomic" and len(row.fields["components"]) == 1
    assert row.fields["account_id"] == "1" and row.fields["components"][0]["amount"] == "-30"


@pytest.mark.parametrize("method", ["银行卡(1234)10元&余额10元", "银行卡(1234)40元&余额", "银行卡(1234)31元"])
def test_explicit_amount_conflicts_remain_drafts(method):
    row, = _resolve(_scan(_file(methods=(method,))))
    assert not row.ready and "component_total_mismatch" in row.issue_codes


def test_partial_allocation_is_not_filled_from_the_remainder():
    row, = _resolve(_scan(_file(methods=("银行卡(1234)10元&余额",))))
    assert [part["amount"] for part in row.fields["components"]] == ["-10", None]
    assert not row.ready and "allocation_missing" in row.issue_codes


@pytest.mark.parametrize("method", ["红包&优惠券", "", " & ", "30元"])
@pytest.mark.parametrize("amount", ["0", None, "-10", "10"])
def test_missing_alipay_funding_defaults_to_wallet_for_any_direction(method, amount):
    file = _file(methods=(method,), amount=amount)
    original_payload = deepcopy(file.document.rows[0].fields["source_payload"])
    scan = _scan(file)
    group, = scan.groups
    row, = _resolve(scan)
    assert group.display_name == "支付宝余额"
    assert row.group_refs == (group.source_account_ref,)
    assert "funding_unrecognized" not in row.issue_codes
    assert row.fields["components"][0]["account_id"] == "1"
    assert file.document.rows[0].fields["source_payload"] == original_payload


@pytest.mark.parametrize("amount", ["0", "-12", "12", None])
def test_yuebao_product_description_is_the_specific_missing_account_fallback(amount):
    file = _file(methods=("",), amount=amount, product="余额宝-2025.06.19-收益发放")
    scan = _scan(file)
    group, = scan.groups
    row, = _resolve(scan)
    assert group.display_name == "余额宝"
    assert row.group_refs == (group.source_account_ref,)
    assert "funding_unrecognized" not in row.issue_codes
    assert file.document.rows[0].fields["source_payload"]["商品说明"] == "余额宝-2025.06.19-收益发放"


def test_identified_payment_account_takes_precedence_over_yuebao_description():
    scan = _scan(_file(methods=("招商银行储蓄卡(1234)",), product="余额宝收益发放"))
    group, = scan.groups
    assert group.display_name == "招商银行储蓄卡(1234)"


def test_unknown_alipay_direction_still_gets_wallet_fallback_without_hiding_direction_issue():
    scan = _scan(_file(methods=("",), issues=("direction_unrecognized",)))
    row, = _resolve(scan)
    assert scan.groups[0].display_name == "支付宝余额"
    assert "funding_unrecognized" not in row.issue_codes
    assert "direction_unrecognized" in row.issue_codes


def test_missing_wechat_funding_is_a_row_issue_not_an_account_mapping_group():
    scan = _scan(_file(source="wechat", methods=("",), owner=None))
    row, = _resolve(scan, selections={})
    assert scan.groups == () and row.group_refs == ()
    assert not row.ready and "funding_unrecognized" in row.issue_codes
    assert row.fields["components"][0]["account_id"] is None


def test_amount_without_a_funding_name_uses_wallet_fallback():
    scan = _scan(_file(methods=("30元",)))
    group, = scan.groups
    row, = _resolve(scan)
    assert group.display_name == "支付宝余额"
    assert row.group_refs == (group.source_account_ref,)
    assert "funding_unrecognized" not in row.issue_codes
    assert row.fields["components"][0]["account_id"] == "1"


def test_known_and_unidentified_payment_parts_keep_separate_preview_components():
    scan = _scan(_file(methods=("银行卡(1234)&30元",)))
    assert len(scan.groups) == 1 and scan.groups[0].display_name == "银行卡(1234)"
    row, = _resolve(scan)
    assert row.group_refs == (scan.groups[0].source_account_ref,)
    assert not row.ready and "funding_unrecognized" in row.issue_codes and "allocation_missing" in row.issue_codes
    assert [part["account_id"] for part in row.fields["components"]] == ["1", None]
    assert [part["amount"] for part in row.fields["components"]] == [None, "-30"]


def test_bank_group_uses_the_display_name_prepared_by_its_channel_converter():
    file = _file("ccb_debit", ("ignored by bank group identity",), owner="6217000000000000001")
    group, = _scan(file).groups
    assert group.display_name == "建设银行(0001)"


def test_bank_labels_with_missing_card_type_remain_distinct_from_full_card_labels():
    scan = _scan(_file(methods=("工商银行(3697)", "工商银行信用卡(3697)")))
    assert len(scan.groups) == 2
    assert [group.display_name for group in scan.groups] == ["工商银行(3697)", "工商银行信用卡(3697)"]


def test_government_subsidy_is_not_a_funding_group_or_component_and_raw_text_survives():
    method = "工商银行信用卡(1200)&政府补贴"
    file = _file(methods=(method,))
    group, = _scan(file).groups
    row, = _resolve(_scan(file))
    assert group.display_name == "工商银行信用卡(1200)"
    assert len(row.fields["components"]) == 1
    assert file.document.rows[0].fields["source_payload"]["付款方式"] == method


@pytest.mark.parametrize("method", ["优惠券10元", "红包5元&优惠券5元"])
def test_zero_payment_does_not_treat_promotion_face_value_as_funding(method):
    row, = _resolve(_scan(_file(methods=(method,), amount="0")))
    assert row.ready and row.fields["components"][0]["amount"] == "0"


@pytest.mark.parametrize("patch,code", [
    ({"active": False}, "account_unavailable"), ({"type": "security"}, "account_unavailable"),
    ({"currencies": ["USD"]}, "account_currency_missing"),
])
def test_unavailable_account_only_affects_its_rows(patch, code):
    scan = _scan(_file(methods=("银行卡(1234)", "余额")))
    accounts = {str(i): {"id": str(i), "revision": 3, "active": True, "type": "cash", "currencies": ["CNY"]} for i in (1, 2)}
    accounts["1"].update(patch)
    rows = _resolve(scan, accounts=accounts)
    assert not rows[0].ready and code in rows[0].issue_codes and rows[1].ready


def test_new_account_revision_revalidates_current_eligibility_without_reselection():
    scan = _scan(_file())
    row, = _resolve(scan, accounts={"1": {"id": "1", "revision": 4,
        "active": True, "type": "cash", "currencies": ["CNY", "USD"]}})
    assert row.ready and row.fields["account_id"] == "1" and not row.issue_codes


def test_wechat_default_scope_reuses_groups_but_named_sources_are_isolated():
    files = [_file("wechat", ("零钱",), owner=None, ref="a"), _file("wechat", ("零钱",), owner="nickname", ref="b"),
             _file("wechat", ("零钱",), owner=None, label="工作", ref="c")]
    scan = _scan(*files)
    assert len(scan.groups) == 2
    assert scan.rows[0].group_refs == scan.rows[1].group_refs != scan.rows[2].group_refs


def test_bank_uses_complete_owner_and_never_collapses_identical_card_tails():
    scan = _scan(_file("ccb_debit", ("建设银行",), owner="6212000000001234", ref="a"),
                 _file("ccb_debit", ("建设银行",), owner="6222000000001234", ref="b"))
    assert len(scan.groups) == 2
    assert all("6212000000001234" not in group.display_name and "6222000000001234" not in group.display_name for group in scan.groups)
    assert scan.rows[0].group_refs != scan.rows[1].group_refs


def test_unknown_bank_owner_is_file_local_and_credit_uses_each_row_owner():
    first = _file("icbc_credit", ("信用卡", "信用卡"), owner=None)
    document = replace(first.document, rows=tuple(replace(row, source_account_identifier=owner)
        for row, owner in zip(first.document.rows, ("6222000000001234", "6222000000005678"))))
    scan = _scan(replace(first, document=document), _file("icbc_debit", ("借记卡",), owner=None, ref="b"),
                 _file("icbc_debit", ("借记卡",), owner=None, ref="c"))
    assert len(scan.groups) == 4


def test_distinct_masked_credit_cards_are_separate_local_mapping_groups():
    file = _file("icbc_credit", ("信用卡", "信用卡"), owner=None)
    rows = tuple(replace(row, fields={**row.fields, "source_payload": {"交易卡号": masked}})
        for row, masked in zip(file.document.rows, ("6222****1234", "6222****5678")))
    scan = _scan(replace(file, document=replace(file.document, rows=rows)))
    assert len(scan.groups) == 2 and scan.rows[0].group_refs != scan.rows[1].group_refs
    assert all(group.identity_kind == "file_local" for group in scan.groups)


def test_financial_precision_and_source_text_survive_low_decimal_context():
    amount = "12345678901234567890.123456789012345678"
    file = _file(methods=("银行卡(1234)",), amount="-" + amount)
    before = deepcopy(file)
    with localcontext() as context:
        context.prec = 6
        row, = _resolve(_scan(file))
    assert row.ready and Decimal(row.fields["components"][0]["amount"]) == Decimal("-" + amount)
    assert file == before and row.fields["note"] == "  原始备注  " and row.fields["source_payload"] == file.document.rows[0].fields["source_payload"]


@pytest.mark.parametrize("method", ["银行卡(1234)¥1e3", "银行卡(1234)-10元", "银行卡(1234)10元20元"])
def test_invalid_allocation_tokens_are_not_silently_replaced_by_parent(method):
    row, = _resolve(_scan(_file(methods=(method,))))
    assert not row.ready and "allocation_amount_invalid" in row.issue_codes


def test_installment_is_a_base_account_and_does_not_create_a_new_account():
    scan = _scan(_file(methods=("招商银行信用卡分期(1234)", "招商银行信用卡(1234)")))
    assert len(scan.groups) == 1 and all(row.ready for row in _resolve(scan))


def test_wallet_base_identity_is_the_same_with_or_without_explicit_amount():
    scan = _scan(_file(methods=("余额", "余额30元", "账户余额30元", "支付宝余额30元")))
    assert len(scan.groups) == 1 and scan.groups[0].display_name == "支付宝余额"
    assert all(row.ready for row in _resolve(scan))


def test_allocation_wrapping_and_spaces_do_not_create_a_different_wallet():
    from ft.adapters.cash_statement_rows import _normalize_alipay_payment_method
    methods = tuple(_normalize_alipay_payment_method(value) for value in ("余额", "余额( 30 元 )", "余额【 30元 】"))
    scan = _scan(_file(methods=methods))
    assert len(scan.groups) == 1 and all(row.ready for row in _resolve(scan))


@pytest.mark.parametrize("source,method", [
    ("wechat", "银行卡(6222 1234 5678 9012)"), ("wechat", "银行卡(6222-1234-5678-9012)"),
    ("alipay", "银行卡(6222-1234-5678-9012)"),
])
def test_group_labels_mask_formatted_full_account_numbers(source, method):
    group, = _scan(_file(source, (method,))).groups
    digits = "".join(char for char in group.display_name if char.isdigit())
    assert "6222123456789012" not in digits and "9012" in digits


def test_unreadable_row_isolated_and_skipped_file_does_not_create_a_fake_group():
    file = _file(methods=("余额", "银行卡(1234)"))
    bad = replace(file.document.rows[0], fields=None, disposition="skipped", error_code="row_unreadable")
    file = replace(file, document=replace(file.document, rows=(bad, file.document.rows[1])))
    scan = _scan(file, replace(file, file_ref="b", client_file_ref="b", status="failed", document=None, error_code="statement_unreadable"))
    assert len(scan.rows) == 1 and len(scan.groups) == 1 and _resolve(scan)[0].ready


def test_internal_adapter_failure_stops_funding_scan(monkeypatch):
    import ft.application.cash_import_funding as module
    original = module.parse_alipay_payment_components
    def parse(row):
        if row["payment_method"] == "异常":
            raise RuntimeError("sensitive importer error")
        return original(row)
    monkeypatch.setattr(module, "parse_alipay_payment_components", parse)
    with pytest.raises(RuntimeError):
        _scan(_file(methods=("异常", "余额")))


def test_invalid_row_does_not_leave_unused_source_account_groups():
    file = _file(methods=("异常账户", "余额"))
    first, second = file.document.rows
    first = replace(first, fields={**first.fields, "currency": ["invalid"]})
    scan = _scan(replace(file, document=replace(file.document, rows=(first, second))))
    assert len(scan.groups) == 1 and scan.groups[0].row_refs == ("a:r2",)
    assert scan.rows[0].error_code == "funding_unreadable"


def test_too_many_payment_parts_only_fails_that_row():
    scan = _scan(_file(methods=("&".join(["余额"] * 65), "余额")))
    first, second = _resolve(scan)
    assert first.error_code == "funding_unreadable" and second.ready


@pytest.mark.parametrize("choice", [
    {"account_id": 1, "expected_account_revision": 3}, {"account_id": "1", "expected_account_revision": True},
    {"account_id": "1"}, {"account_id": None, "expected_account_revision": 3}, {"arbitrary": "value"}, [],
])
def test_selection_structure_is_strict(choice):
    from ft.application.cash_import_funding import CashFundingInputError
    scan = _scan(_file())
    with pytest.raises(CashFundingInputError):
        _resolve(scan, selections={scan.groups[0].source_account_ref: choice})


def test_a_selection_for_another_scan_is_not_applied_to_current_rows():
    from ft.application.cash_import_funding import CashFundingInputError
    with pytest.raises(CashFundingInputError):
        _resolve(_scan(_file()), selections={"other": {"account_id": "1", "expected_account_revision": 3}})


@pytest.mark.parametrize("stage", ["scan", "resolve"])
def test_internal_funding_failure_stops_instead_of_returning_a_bad_source_row(monkeypatch, stage):
    import ft.application.cash_import_funding as module
    def broken(*_args, **_kwargs):
        raise RuntimeError("synthetic internal funding failure")
    if stage == "scan":
        monkeypatch.setattr(module, "_parts", broken)
        with pytest.raises(RuntimeError): _scan(_file())
    else:
        scan = _scan(_file())
        monkeypatch.setattr(module, "_sum", broken)
        with pytest.raises(RuntimeError): _resolve(scan)
