# Cash Record Type Contract

## Import Output

每条现金导入输出行必须包含：

```json
{
  "record_type": "consumption|refund|reversal|withdrawal_in|withdrawal_out|transfer_in|transfer_out|repayment|income|investment_in|investment_out|interest|fee|fx_in|fx_out|other"
}
```

`record_type` 必须与来源原生字段映射一致；`category` 仍为 `expense` 或 `income`，两者可以同时存在且语义不同。

## Required Examples

| 来源行 | `category` | `record_type` |
|---|---|---|
| 微信 `商户消费` + 支出 | `expense` | `consumption` |
| 微信 `转账` + 收入 | `income` | `transfer_in` |
| 微信/支付宝 P2P 转账或红包 + 退回/退款状态 | 依来源金额 | `transfer_reversal` |
| 支付宝 `信用借还` | 依账单金额 | `repayment` |
| 工行/建行 `退货` | `income` | `refund` |
| 工行/建行 `撤销交易` / `冲正` | `income` | `reversal` |
| 微信 `零钱提现` 或银行 `取现` | `expense` | `withdrawal_out` |
| 银行 `支付机构提现` 入账 | `income` | `withdrawal_in` |
| 建行 `代理收款` | 依账单金额 | `repayment` |
| 建行 `无卡自助交易` / `无卡支付` | `expense` | `consumption` |
| 工行 `工资` | `income` | `income` |
| 未知来源语义 | 依账单金额 | `other` |

## Persistence

`cash_transactions.record_type` 为非空字段；`source_payload` 仍保留原始分类字段和 `record_type` 的导入快照。导入失败时不留下半批现金流水。
