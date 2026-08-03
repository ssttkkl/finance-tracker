# Statement Import Contract: 工行退款摘要

## Parsed row

工行解析器向导入适配器提供以下字段：

```json
{
  "bill_source": "icbc_credit",
  "summary": "退货",
  "refund_signal": "icbc_credit_return",
  "counterparty": "山葵村烤肉",
  "_raw_cp": "美团支付-美团App山葵村烤肉"
}
```

`icbc_debit` 使用对应的 `icbc_debit_return`。`summary` 不是 `退货` 时，`refund_signal` 必须为空字符串。

## Persisted row

导入后 `source_type` 等于 `bill_source`，`source_payload` 保留上述字段；关系扫描从正式持久化字段读取，不读取导入过程中的临时 tracking relation。

## Relation result

同账户、同币种、同规范化对手方、同绝对金额且退货时间不早于消费的两条现金流水，若退款行满足正式工行信号，则可形成 `refund_offset`；完整退款在现有时间窗口内为 `accepted/strong`。
