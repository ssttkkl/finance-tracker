## Why

收支账本的桌面表格中，过长的交易对方或备注会按内容宽度扩展「交易信息」列，挤压后续列并使其越出表格容器。使用者因而无法稳定查看来源、经济类型、金额和证据入口。

## What Changes

- 为宽屏收支投影表格固定各列的可用宽度，并限制交易信息在本列内显示。
- 交易信息超出可用宽度时以省略号截断；完整字段继续可在既有证据详情中查看。
- 新增浏览器级回归测试，验证长交易信息不会扩大表格的可滚动宽度。

## Non-Goals

- 不改变收支投影、筛选、金额、来源或证据详情的数据合同。
- 不调整窄屏卡片布局，不新增交互入口或依赖。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `020-cash-ledger-browser-web`：补充宽屏投影表格在长交易信息下的列边界和可读性合同。

## Impact

- 受影响代码：`web/src/components/CashTable.tsx`、`web/src/styles.css`、`web/tests/cash-ledger.e2e.ts`。
- 不影响 API、持久化、财务计算、外部依赖或两个正式数据库后端。
- 回滚只需恢复表格列布局样式和列宽声明；数据与运行时合同不受影响。
