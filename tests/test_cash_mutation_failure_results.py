"""配对发现失败按真实原因和成员计数，已有关系组不重复列出。"""
from ft.application.cash_plan_execution import CashPlanWriteOperations


def test_duplicate_discovery_failures_have_one_safe_stable_result_and_group_failure_is_separate():
    failures = [{"code": "relation_discovery_failed", "parent_ids": ["20", "10"]},
        {"code": "relation_discovery_failed", "parent_ids": ["10", "20", "20"]},
        {"code": "relation_discovery_failed", "parent_ids": ["10", "20"], "suggestion_id": "sg_1"}]
    result, = CashPlanWriteOperations._discovery_results({"relation_failures": failures}, {"10": "100", "20": "200"})
    assert result["result_kind"] == "relation" and result["outcome"] == "failed" and result["code"] == "relation_discovery_failed"
    assert result["transaction_id"] == "100"
    repeated, = CashPlanWriteOperations._discovery_results({"relation_failures": failures[:1]}, {"10": "100", "20": "200"})
    assert repeated == result


def test_unresolved_virtual_members_do_not_expose_a_virtual_transaction_reference():
    virtual = str(2 ** 63 + 100)
    result, = CashPlanWriteOperations._discovery_results({"relation_failures": [
        {"code": "relation_discovery_failed", "parent_ids": [virtual]}]}, {})
    assert result["transaction_id"] is None and virtual not in str(result)
