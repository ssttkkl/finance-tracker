"""新配对读取完整来源原文，不能读取人工备注或漏掉 PDF 列名映射。"""
from datetime import datetime, timezone
from decimal import Decimal
from types import SimpleNamespace

import pytest

from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
from ft.domain.cash_relation_policy import RelationEndpoint


@pytest.mark.parametrize("source,payload,expected", [("alipay", {"商品说明": "原始描述"}, "原始描述"),
    ("wechat", {"商品": "原始商品", "备注": "原始备注"}, "原始商品"),
    ("wechat", {"商品": "", "备注": "不得回退"}, ""),
    ("icbc_debit", {"备\n注": "  完整原文\n ", "摘要": "转账"}, "  完整原文\n ")])
def test_matching_note_uses_the_same_source_mapping_as_record_notes(source, payload, expected):
    part = RelationEndpoint("part", "parent", Decimal("-1"), "CNY", "101", "consumption", "not_applicable")
    value = {"source_type": source, "source_payload": payload, "source_explanation": {}, "record_id": "opaque",
        "amount": Decimal("-1"), "record_type": "consumption", "occurred_at": datetime(2026, 7, 1, tzinfo=timezone.utc),
        "counterparty": "商户", "counterparty_account": "", "counterparty_account_attrs": [], "note": "人工备注不用于配对",
        "cash_granularity": "atomic"}
    snapshot = SimpleNamespace(components={"part": part}, values={"parent": value})
    fact, = CashImportBusinessPlanBuilder("workspace")._matching_facts(snapshot, {"101": {"name": "账户", "type": "cash"}})
    assert fact.note == expected and value["note"] == "人工备注不用于配对"
