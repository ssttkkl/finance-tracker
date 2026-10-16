## Why

Compose 的收支账本、流水新增和详情虽然覆盖了现有业务流程，但列表层级、跨尺寸排布和详情信息组织不够清楚，浏览与定位流水的效率和视觉完成度都低于旧 React Web 参考。需要围绕现有账本任务重整 Compose 页面，同时保留已建立的财务语义和操作能力。

## What Changes

- 重构 Compose 收支账本的筛选、月度汇总、记录列表、批量操作和加载状态的层级与布局；顶部搜索复用现有交易对方筛选，固定快捷项为全部、支出、收入和转账，分类等其他筛选按需打开。
- `COMPACT` 以 Bottom Sheet 展示详细筛选，`REGULAR` / `WIDE` 使用模态弹窗；保留所有既有筛选、汇总、分页、分类、删除和导入能力，不增加搜索规则或业务筛选能力。
- 在 `COMPACT` 使用可扫读的流水卡片，在 `REGULAR` 和 `WIDE` 使用紧凑表格；完整保留账户、分类、金额、来源和主要操作信息。
- 所有窗口统一使用浮动操作菜单承载记一笔、导入账单及批量操作。Compact 默认隐藏行复选框，长按进入选择模式；Regular/Wide 保留行复选框。移除「全选当前已加载」，选择模式展示已选数量。
- 选择模式下的设置分类在模态对话框中完成选择和应用；删除继续使用现有危险确认。
- 将新增、查看和编辑整合为同一组响应式模态信息面板：`COMPACT` 全屏，`REGULAR` / `WIDE` 右侧面板；打开时账本保持可见但不可操作，支持关闭和 Escape/系统返回。面板沿用 Compose 与平台默认焦点行为，不增加专属焦点请求或关闭后恢复；导入导航等其他功能已有的焦点行为保留。
- 重组收支详情、凭证、关联流水、关系操作和编辑表单的信息层级；摘要保持在卡片外，主要流水信息与关联流水各自使用并列的 Material 3 Card；关联流水卡默认收起，展开后仍在该卡内按当前流水与关联流水分组展示，不叠加模态弹层或嵌套卡片；保留现有字段、CRUD、危险确认、权限、错误和成功语义，不增加业务能力。
- 将 FinanceTheme 现有浅/深色表面 token 映射到 Material 3 `surfaceContainer*` 角色，供账本、流水详情及其他 Compose 页面中的 Material 3 默认表面组件统一读取；不为组件另造颜色。
- 为编辑、关联、新增关系、保存、删除等主要操作增加有意义的 Material 图标并保留文字标签；批量删除、流水删除和解散关联使用危险色，删除确认按钮也使用危险色。
- 为共享 Compose 页面补充真实浏览器与三类窗口的回归证据，并更新功能地图和 OpenSpec 记录。

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `cash-ledger/cash-ledger-browser`: 明确 Compose 账本在三类窗口下的列表布局、模态信息面板和详情/编辑呈现合同。

## Impact

- 共享 Compose UI：`CashLedgerScreen.kt`、`CashLedgerDetails.kt`，影响 Compose Web、Android 和 iOS。
- 全局主题：`FinanceTheme.kt` 将 FinanceTheme 现有浅/深色 token 映射到 Material 3 表面容器角色；这可能改变所有 Compose 页面中采用 Material 3 默认容器色的卡片与弹窗颜色。
- Compose 演示仓库：`DemoRepositories.kt` 仅规范化合成流水详情的 projection ID；不改变生产仓库、API、持久化或财务行为。
- Compose 浏览器 QA：`compose/qa/web/tests/compose-pages.e2e.ts`、本地预览构建新鲜度门禁、每次运行独立端口和 Playwright 服务启动配置。
- Compose 交互回归：覆盖浮动操作菜单、Compact 长按选择、跨尺寸选择状态和批量分类对话框。
- 共享模块回归：`compose/shared/src/commonTest/kotlin/com/finance/tracker/DemoFinanceRepositoryTest.kt`。
- 功能盘点：`docs/feature-map.md`。
- 不改变 API、领域规则、金额处理、持久化、数据库 schema、依赖或旧 React Web。
- 可回滚方式：还原本次 Compose UI、QA、功能地图及 OpenSpec change 内文件；无数据迁移或运行时兼容步骤。
