"""冻结详情的引用只能读取本计划当前成员，公开形状仍由白名单控制。"""
from copy import deepcopy

import pytest

from ft.application.cash_import_plans import CashImportPlanError, _public_detail, _resolve_detail


def _plan():
    return {"operations": [{"operation_ref": "op_1", "row_refs": ["file_1:r1"], "transaction_id": None,
        "values": {"note": "来源备注", "source_payload": {"备注": "来源备注"},
            "components": [{"component_id": str(2 ** 64), "account_id": "101", "amount": "-1", "currency": "CNY", "ordinal": 0}],
            "source_explanation": {"private": "server-only"}, "preview_transaction_id": "server-only"}}],
        "detail_values": {"102": {"note": "已存对侧", "components": []}}}


def _detail(reference=None):
    return {"members": [{"row_ref": "file_1:r1", "fields_ref": reference or {"operation_ref": "op_1"}}]}


def test_resolved_detail_is_detached_and_uses_existing_public_whitelist():
    plan = _plan()
    before = deepcopy(plan)
    detail = _public_detail(_resolve_detail(plan, _detail()))
    fields = detail["members"][0]["fields"]
    assert fields["note"] == "来源备注" and "component_id" not in fields["components"][0]
    assert "source_explanation" not in fields and "preview_transaction_id" not in fields
    assert "fields_ref" not in detail["members"][0]
    fields["source_payload"]["备注"] = "本地修改"
    assert plan == before


@pytest.mark.parametrize("reference", [{"operation_ref": "missing"}, {"transaction_id": "missing"},
    {"operation_ref": []}, {"transaction_id": 102}, {"operation_ref": "op_1", "transaction_id": "102"}])
def test_bad_frozen_reference_is_a_safe_plan_error(reference):
    with pytest.raises(CashImportPlanError, match="plan_build_failed"):
        _resolve_detail(_plan(), _detail(reference))


def test_another_members_operation_cannot_supply_this_members_fields():
    plan = _plan()
    plan["operations"][0]["row_refs"] = ["file_2:r1"]
    with pytest.raises(CashImportPlanError, match="plan_build_failed"):
        _resolve_detail(plan, _detail())


def test_another_stored_transaction_cannot_supply_this_members_fields():
    detail = {"members": [{"transaction_id": "103", "fields_ref": {"transaction_id": "102"}}]}
    with pytest.raises(CashImportPlanError, match="plan_build_failed"):
        _resolve_detail(_plan(), detail)


def test_duplicate_operation_references_cannot_pick_an_arbitrary_value():
    plan = _plan()
    plan["operations"].append(deepcopy(plan["operations"][0]))
    with pytest.raises(CashImportPlanError, match="plan_build_failed"):
        _resolve_detail(plan, _detail())


def test_bad_frozen_component_shape_keeps_the_safe_plan_error():
    plan = _plan()
    plan["operations"][0]["values"]["components"] = None
    with pytest.raises(CashImportPlanError, match="plan_build_failed"):
        _resolve_detail(plan, _detail())
