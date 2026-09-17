## Why

当前 Expo 客户端只覆盖 Web 首个纵向切片的一部分，而且两端分别维护文案、页面状态和布局判断；同一个任务在 Web 与 Native 上容易出现内容顺序、操作语义、状态反馈和响应式行为漂移。现在以 Web 已验证的行为为事实源建立跨端 presentation 契约，可以在不把 DOM 与 React Native 强行合并的前提下，重写现有 Expo 页面并覆盖手机、平板和大屏 Native 场景。

## What Changes

- 新增共享 `presentation` package，集中维护跨端用户可见文案、页面区域、可用操作、状态集合、语义测试 ID、响应式布局等级和已登记的平台差异。
- 将 Web Compact、Regular、Wide 与 Native Phone、Tablet、Large 的对应关系写成可执行契约；Native 使用逻辑窗口宽度适配 Android 多设备形态以及 iPhone/iPad，不按具体设备型号分支。
- 以当前 Web 的认证、工作区选择、收支账本、手工记账、凭证查看、账单导入和关系审查为事实源，重写 Expo 已有页面，使信息结构、文案、主要操作和状态转换对齐。
- 为 Native 大屏提供与 Web 相同的信息架构、导航层级、内容顺序、筛选/详情/操作区域关系和密度；仅允许把 DOM 控件替换为等价的 Native 控件。
- 为 Web 和 Native 增加稳定的语义测试 ID 与共享 parity journey，先建立 Web Compact 的浏览器门禁，并为 Native UI 测试保留可消费的 `testID` 合同。
- 在 `AGENTS.md` 增加跨平台影响检查规则；分类管理、投资账本和工作区管理等尚未进入现有 Expo 纵向切片的 Web 页面登记为显式缺口，不在本阶段复制业务能力。

## Capabilities

### New Capabilities

- `cross-platform-presentation`: 定义 Web 与 Native 共用的用户可观察 presentation、文案、语义 ID、响应式等级、平台差异和 parity journey 合同。

### Modified Capabilities

- `cash-ledger-browser`: 为已有的 Web 响应式账本浏览行为补充跨端语义标识，并明确 Native 复用相同信息结构与状态语义。
- `workspace-entry`: 让认证和工作区入口的 Native 渲染遵循 Web 已验证的文案、状态和响应式 presentation 合同。

## Impact

- 代码：新增 `packages/presentation`；修改 `packages/design-tokens`、Web 入口/账本/导入页面及测试；重写 `mobile/src/components`、认证、工作区、账本、记录和导入页面。
- 工程规则：修改根级 `AGENTS.md`，补充跨平台影响检查、允许差异登记和大屏 Native 对齐要求。
- 测试：新增共享契约单元测试、Web Compact/Regular/Wide parity hooks 和 Mobile `testID` 覆盖；补充响应式尺寸矩阵记录。
- API、数据库、金额精度、持久化和服务端领域规则不变；本变更不新增数据库迁移，不引入离线事实源，也不要求像素级跨平台截图相等。
- 依赖：不新增运行时 UI 框架；沿用现有 Expo、React Native、Vite、Vitest 和 Playwright。Maestro/真机 CI 不作为本阶段硬依赖。

本变更与 `add-pr-quality-gates` 分别完成开发和相称的单变更验证后，使用现有 `feat/cross-platform-experience` 作为合并后的 feature 分支，以 `refactor/web` 为基线并向其创建一个 PR。两个 worktree 中与各自 OpenSpec tasks 无直接关系的既有脏文件只保留、不纳入、不删除；联合验收延后到两个变更合并后集中执行一次。
