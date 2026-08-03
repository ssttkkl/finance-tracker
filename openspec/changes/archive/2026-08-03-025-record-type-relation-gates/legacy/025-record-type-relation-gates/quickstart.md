# Quickstart: 关系配对正式类型闸门

```bash
uv run pytest -q tests/test_record_type_relation_gates.py
uv run pytest -q tests/test_transaction_relations_refund.py tests/test_transaction_relations_transfer.py tests/test_transaction_relations_payment_mirror.py
uv run pytest -q tests/test_record_type.py tests/test_platform_refund_matchers.py
env -u FT_DATABASE_URL uv run pytest -q
```

P0 验证使用新的临时 SQLite 库：从 `.ft/bills` 全量导入，再重建关系和收支投影。比较
临时库与业务库的 `transaction_relations.kind`、`subtype`、`status`、`confidence` 和
`rule_id`，确认差异可由类型路线或日期型证据收紧解释。不得在此流程中覆盖
`/Users/huangwenlong/.ft/finance-tracker.db`。

本轮全量验证已导入 11,394 条现金流水和 497 条证券流水，并连续运行两次全量关系扫描以
确认统计稳定。P2P 转账、红包和群收款的退回均应显示为 `transfer_reversal`，且不应出现在
任何 `transaction_relations` 端点中。

此前的关系重建对比副本为：

```text
/Users/huangwenlong/.ft/finance-tracker.db.record-type-relations-check-20260801-v2
```

该副本生成 3,403 条关系；旧规则快照为
`/Users/huangwenlong/.ft/finance-tracker.db.before-record-type-rebuild-20260801-2330`。

本轮退款候选验证还必须覆盖：普通候选及自动确认边界为 15 天，订单/交易号锁定候选可到 30 天；已接受的 `payment_mirror` 镜像行只计一个候选；部分退款和全额退款在最高证据等级中均可选择最近且唯一的候选；最近并列保持 `pending_review`。

本轮实现验证使用独立临时 SQLite 库，导入 `.ft/bills` 全量账单后清空并重建关系和收支投影；验证结束不覆盖正式库。重点核对 `自助侠` 的镜像候选折叠、全额退款最近匹配、15 天边界，以及 `candidate_count` 按经济事件计数。
