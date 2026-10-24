## Why

在用户提供的收支账本截图中，`REGULAR` / `WIDE` 月份表格容器的右边缘比上方筛选卡片向内缩了一段，形成不一致的右侧留白。用户确认要对齐表格整体边缘。当前实现中的 16 dp 列表右侧缩进和吸顶月份栏缩进共同造成了该差异。

## What Changes

- 让 `REGULAR` / `WIDE` 的月份表格容器和吸顶月份栏与上方筛选卡片的右边缘对齐。
- 移除列表视口和吸顶月份栏外侧多出的 16 dp 缩进。
- 保留表格行内容两侧 16 dp 的内缩、月份容器全宽分隔线，以及覆盖列表的 40 dp 滚动条交互热区。
- `COMPACT` 和账本业务行为保持现有表现。

## Non-Goals

- 不调整表格列顺序、列宽、行高、颜色或筛选卡片尺寸。
- 不改变筛选、分页、滚动定位、批量操作、财务结果或用户可见文案。
- 不修改旧 React 页面或投资账本。

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `cash-ledger/cash-ledger-browser`：更新 Compose `REGULAR` / `WIDE` 表格容器与筛选卡片的右边缘对齐合同。

## Impact

- 共享实现：`compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt`，影响 Compose Web、Android 和 iOS 的 `REGULAR` / `WIDE` 布局。
- 规格与功能地图：`openspec/changes/align-cash-ledger-table-filter-edge/`、`docs/feature-map.md`。
- 不影响 API、持久化、财务计算、权限或数据库。
- 回滚方式：恢复 `CashLedgerScreen.kt` 中列表和吸顶月份栏的右侧 16 dp 缩进，并还原本变更的规格和功能地图条目。
