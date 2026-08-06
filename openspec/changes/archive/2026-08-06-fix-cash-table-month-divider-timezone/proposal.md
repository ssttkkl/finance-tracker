## Why

收支账本的月份分割行直接截取 `occurred_at` 的 UTC 月份，而页面把同一时间按 `Asia/Shanghai` 显示。跨月边界的流水因此会显示为 7 月日期，却被归入 6 月汇总区。

## What Changes

- 让投影表格的月份分组与页面发生时间使用相同的 `Asia/Shanghai` 时区。
- 新增 UTC 与 `Asia/Shanghai` 月份不同的跨月回归测试。

## Non-Goals

- 不修改流水发生时间、数据库存储、月度汇总数据或 API 合同。
- 不改变日期展示格式、排序、筛选和窄屏卡片布局。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `020-cash-ledger-browser-web`：规定月份分割行与发生时间展示使用同一时区。

## Impact

- 受影响代码：`web/src/components/CashTable.tsx`、`web/tests/CashTable.test.tsx`。
- 不影响持久化、财务金额计算、API、依赖或正式数据库后端。
- 回滚仅需恢复前端月份键计算；既有流水和月度汇总数据不受影响。
