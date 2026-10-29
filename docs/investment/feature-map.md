# 投资模块

[返回产品总览](../feature-map.md) · [跨端覆盖与待办](../feature-map.md#跨端覆盖与待办)

本模块包含 **2 项页面级功能**：F-08 投资持仓、F-09 投资流水。银证转账在投资流水详情中只读展示；投资账单与连接器接入边界见 [导入模块](../import/feature-map.md#无独立页面的能力)。

## F-08 投资持仓

**入口：** `/w/<workspaceId>/investment-holdings`。

**主路径：** 查看持仓和组合估值 → 按投资账户、标的、表现周期或显示币种调整展示 → 选择持仓打开详情 → 关闭返回列表。

**分支与恢复：** 可合并／拆分同一标的持仓行，或刷新估值／行情；无匹配持仓显示空结果，账户或组合读取失败可重新加载。报价、汇率或估值不完整时显示状态与原因，不把缺失值当作完整估值；刷新不可用或失败时保留当前内容并反馈结果。

### 实现与验证证据

持仓筛选、估值和表现已实现。各窗口共用非全屏基础对话框，正文滚动与关闭操作分区，关闭入口在左属于产品例外；尺寸及间距见 [UI 设计规范](../shared/ui-design-rules.md) 和实现证据。既有 Web E2E、Android compact／regular／wide 主题布局 QA 通过；iPad 11 已验估值／详情，iPad 13 wide 已验深色布局。

- [`InvestmentScreens.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentScreens.kt)
- [`InvestmentDisplayLogic.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentDisplayLogic.kt)

## F-09 投资流水

**入口：** `/w/<workspaceId>/investment-events`。

**主路径：** 按日期、投资账户、记录类型和标的筛选 → 浏览分页事件 → 打开事件详情查看来源证据与关系 → 返回列表。记录类型包括资金供给、交易、收入、支出、冲回、认购、调整和快照。

**分支与恢复：** 筛选变化后重载第一页，无匹配结果显示空状态；首屏、账户或后续分页失败可重试，详情证据失败可在详情内重试。银证转账关系只读，不提供候选确认或拒绝。

### 实现与验证证据

事件筛选、分页和证据详情已实现。证据详情与估值详情共用各窗口限宽基础对话框的呈现规则，正文与关闭操作分区，关闭入口在左。既有 Web E2E、Android 详情／分页 QA 通过；iPad 11 已验详情与加载更多。

- [`InvestmentScreens.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentScreens.kt)
- [`InvestmentDisplayLogic.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentDisplayLogic.kt)
- [`InvestmentEventScreen.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentEventScreen.kt)

## 相关规格

- [持仓页面](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentScreens.kt)
- [投资流水页面](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentEventScreen.kt)
- [持仓估值规格](../../openspec/specs/investment/portfolio-valuation/spec.md)
- [投资事件规格](../../openspec/specs/investment/investment-event-model/spec.md)
- [银证转账关系规格](../../openspec/specs/investment/cash-investment-funding-relations/spec.md)
