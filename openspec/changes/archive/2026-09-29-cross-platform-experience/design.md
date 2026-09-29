## Context

详见 `proposal.md`。当前 Web 是完整且已通过浏览器 QA 的用户界面事实源；Expo Native 只完成了登录、工作区、收支账本、手工记账、凭证和账单导入的首个纵向切片，页面状态与文案仍由 Native 独立维护。现有共享包已经覆盖 API contracts、纯业务 core 和基础 design tokens，但还没有描述“页面有哪些区域、哪些动作、哪些状态以及这些内容如何在不同窗口宽度落地”的 presentation 层。

本变更只改变客户端 presentation 和测试边界，不改变 Application Service、HTTP 合同、财务计算、数据库、认证协议或导入幂等语义。第一阶段的事实范围是 Web 当前已提供并且 Native 已有入口的流程：认证、工作区选择/创建、收支账本、手工记账、凭证查看、账单选择/扫描、账户映射、流水核对、关系审查和导入成功。Web 已有但 Native 还没有完整任务面的分类管理、投资账本和工作区管理列为显式缺口。

## Goals / Non-Goals

**Goals:**

- 用一份无平台副作用的 `presentation` package 约束跨端 copy、screen regions、actions、states、semantic IDs、responsive tiers 和允许的平台差异。
- 保留 Web DOM/CSS 与 React Native 的渲染自由度，但强制两端在用户可观察的产品语义上收敛。
- 以逻辑窗口宽度而不是设备型号支持 Android 手机、折叠屏、平板、自由窗口以及 iPhone/iPad；大屏 Native 复用 Web 的导航和信息结构。
- 重写当前 Expo 页面，使它们消费共享文案/标识/布局契约，并保留服务端为事实源的写入与错误处理。
- 通过共享契约测试和 Web 真实浏览器 QA，验证结构、操作、状态与结果，不把跨平台截图像素相等作为门禁。

**Non-Goals:**

- 不把现有 Web 改写为 React Native Web，不建立跨平台 DOM/RN 组件库。
- 不在本阶段实现 Native 分类管理、投资账本、成员/邀请/删除工作区管理页面；这些能力进入缺口登记和后续 change。
- 不新增离线缓存、客户端财务计算、数据库 schema、HTTP 业务字段或导入幂等策略。
- 不按具体设备型号、操作系统版本或用户代理写布局分支；不以屏幕缩放模拟响应式。
- 不引入 Maestro、商店发布、真机 CI 或跨平台像素级截图比较作为本阶段必须条件。

## Decisions

### 1. 新增 presentation package，而不是把 UI 组件放进共享包

`packages/presentation/src/` 只包含无 React/DOM/Native 依赖的值和纯函数：

```text
copy.ts                 # 从 Web 事实源整理的共享用户可见文案
screen-contracts.ts     # 页面区域、操作、状态和响应式不变量
responsive.ts           # width -> compact/regular/wide
semantic-ids.ts         # Web data-testid 与 Native testID 的稳定名称
platform-differences.ts # 允许的 surface 差异和必须保持的不变量
journeys.ts             # 第一阶段 parity journey 的语义步骤
index.ts
```

包不得导入 `react`、`react-native`、`window`、`HTMLElement` 或浏览器文件类型。这样 Web 可以继续使用既有 DOM/CSS，Native 可以继续使用 `View`、`FlatList`、系统文件选择器和原生导航，同时共享产品合同。

备选方案是用 `react-native-web` 共享组件。它会迫使现有 Web 表格、键盘/焦点、桌面信息密度和 Portal 行为迁移到另一套 renderer，短期风险大于收益；本变更选择共享语义，不共享渲染器。

### 2. Web 事实源的迁移顺序

先从 `web/src/AccessApp.tsx`、`web/src/App.tsx` 及第一阶段 Web 页面提取已存在的标题、字段标签、按钮、状态和导航文案，写入 `copy.ts`；随后 Web 用共享值替换跨端重复字符串，并为关键区域补 semantic IDs。只有 Web 的平台特有能力（例如浏览器键盘快捷键、HTML file input、hover）保留在 Web 自己的实现中。

这使迁移具有单向基线：如果 Web 文案或操作语义改变，下一次跨平台影响检查会同时触达 Native 和共享 journey；不会先以当前 Native 文案反推 Web。

### 3. 响应式合同使用逻辑窗口宽度

统一划分如下：

| presentation | 逻辑宽度 | Web | Native |
|---|---:|---|---|
| `compact` | `< 600` | 手机宽度 DOM | Android phone、iPhone、窄分屏 |
| `regular` | `600–1023` | 平板/窄桌面布局 | Android tablet portrait、iPad portrait、窄窗口 |
| `wide` | `>= 1024` | 桌面导航和高密度内容 | iPad landscape、Android tablet landscape、大窗口 |

Web 继续由 CSS media queries 控制布局；Native 通过 `useWindowDimensions()` 读取逻辑 dp 宽度，再把 `LayoutClass` 传给 Shell 和页面。Native 不读取型号、屏幕物理像素或 User-Agent。

`wide` Native 的结构合同是 Web 的结构合同：侧边导航、页面标题、筛选区、摘要/列表、详情/操作区的先后关系和主要列信息不变。DOM `<table>` 可替换为带同等字段层级的 Native 列表，popover 可替换为 sheet，浏览器 file input 可替换为系统文件选择器；这些替换必须在 `platform-differences.ts` 登记。

### 4. Native Shell 负责窗口骨架，页面负责业务区域

`NativeShell` 增加 `useResponsiveLayout` 和响应式容器：

- `compact`：顶部标题和菜单入口，单列内容，页面内操作纵向堆叠；
- `regular`：仍以单列为主，但扩大内容宽度、允许工具栏/摘要在同一行；
- `wide`：左侧导航 rail + Web 对应的内容工作区，内容最大宽度和横向 padding 使用共享 component tokens；
- 所有等级都保留 safe-area、至少 44 dp 的触控命中区、可滚动内容和错误/加载反馈。

页面组件只声明自己的 semantic regions 和 actions，不复制设备判断。导航 item 由 Shell 根据 layout class 渲染，路由和服务端调用仍由页面/Session 负责。

### 5. 第一阶段以共享 hooks/状态语义约束 Native，而不复制 Web handler

现有 `packages/core` 继续承载导入 session、账本加载和写入 payload 的纯状态；本变更不把 DOM handler 搬入共享包。`presentation` 只定义这些状态在屏幕上必须被看见的名称和区域。Native 重写页面时保留既有 API client、`core` reducer 和 `SessionProvider`，但所有标题、按钮、空/错误/成功消息和 test ID 改为共享值。

这样避免把 `window`、Portal、焦点恢复或 `react-native` 混进业务状态，也避免 Web 和 Native 因为各自另写 reducer 而发生状态转换漂移。

### 6. 测试以结构和旅程为主

共享包单元测试验证：宽度分类边界、语义 ID 唯一性、每个第一阶段 screen 的 regions/actions/states 完整性、允许差异必须有 invariant，以及 journey 步骤引用的 ID 都存在。

Web Playwright 在 `390×844`、`768×1024`、`1440×900` 运行 compact/regular/wide 检查，验证区域存在、关键操作、状态和无横向滚动；移动端页面给出同名 Native `testID`，后续 Maestro/Detox 可以直接消费，不在本阶段引入新的 runner。真实浏览器截图和 Hallmark audit 负责视觉层级与响应式人工复核，不做 Web/Native 像素 diff。

### 7. 没有持久化迁移，回滚按 package/页面边界进行

所有改动都是客户端源码、共享包、OpenSpec、测试和规则文件。服务端无需部署顺序或数据库迁移。若 Native 重写出现问题，可以回滚 mobile 页面到本 change 基线；Web 先使用共享 copy/ID 的改动仍保持用户行为不变。若共享 package 导致构建阻断，最小回滚单位是移除 workspace 依赖并恢复 Web/Mobile 的兼容导出，不回滚或修改账本数据。

## Responsive and Hallmark Review

- 设计系统预检：沿用 `packages/design-tokens/src/index.ts` 的 Cobalt token、`Noto Sans SC`、`IBM Plex Mono`、4pt spacing、3/5px radius 和最小 44px touch target；不新增色彩或字体。
- Hallmark 选择：`Index-First` macrostructure；`N3 Side-rail` navigation；`Ft1 Mast-headed` session/status footer；enrichment 为 `none`。这与最近两次 `Narrative Workflow` 和较早的 `Workbench / Account Mapping Grid` 不重复。
- 原型：`prototype/index.html`，覆盖登录/工作区入口、收支账本 normal/empty/error/loading、手工记账入口、导入步骤和成功状态；使用真实 Web 文案，不依赖后端或网络。
- 原型检查：必须在 320、375、414、768 和 1440 CSS px 打开；根节点 `overflow-x: clip`，按钮和导航不换行，移动端单列，大屏显示 rail 与工作区，表单最大宽度不超过 720px。
- 最终实现形成后必须再次执行 Hallmark `audit`，并把 target、输出、按严重级别排序的 finding、修复/延期理由写入 `tasks.md`。

## Risks / Trade-offs

- [Web 当前页面文案散落在多个组件中] → 先以 Web 字符串扫描和测试快照建立 copy 清单，迁移后禁止第一阶段跨端文案在 `web/` 或 `mobile/` 新增。
- [Native 大屏结构与 Web 侧栏意外分叉] → Shell 使用 layout class 统一 rail/content 骨架，parity contract 检查区域顺序；设备 QA 使用 iPad/Android tablet 的窗口宽度而不是型号快照。
- [Native 列表替代表格导致字段遗漏] → screen contract 为每个记录区域列出可观察字段；Native `testID` 和结构测试必须覆盖账户、金额、分类、来源、详情入口。
- [共享文案修改破坏现有 Web 测试] → 先保留现有 Web 文案作为事实值，测试优先验证等值迁移；任何有意修改文案另开需求或回写 proposal。
- [Expo 原生工具链阻断真机验证] → 不把原生编译成功伪装成 parity 通过；记录 SDK/Xcode/设备条件，先完成 export、TypeScript、共享合同和可运行 Web QA。
- [把 platform difference 当成任意差异] → 每条差异必须同时登记 invariant；未登记的用户可观察差异在 parity review 中按 defect 处理。

## Migration Plan

1. 在隔离 worktree 中完成 proposal、delta specs、design、原型和 tasks，并通过 OpenSpec strict 校验。
2. 先添加共享 presentation package 的失败测试，再实现 copy、screen contracts、responsive classifier、semantic IDs、journeys 和平台差异登记。
3. 把 Web 第一阶段页面迁移到共享 copy/semantic IDs，确保 Web 既有行为和浏览器测试保持通过。
4. 重写 `NativeShell` 的响应式骨架，再按认证/工作区、账本、记录/凭证、导入/关系四组迁移 Native；每组先补失败回归测试再实现。
5. 运行 Web compact/regular/wide 真实浏览器 QA、Native TypeScript/export 与可用模拟器检查；若原生构建受已知 Expo/Xcode 环境问题阻断，记录准确错误和补跑条件。
6. 完成产品范围、工程、设计、parity 和最终 diff 独立复核，修复 critical/major finding 后重跑 Hallmark audit。
7. 本变更完成单变更验证后，只提交直接相关文件到现有 `feat/cross-platform-experience`，不把未完成实现推送为最终 PR；待 `add-pr-quality-gates` 合入后，在该 feature 分支集中执行一次联合验收，再推送并创建 `feat/cross-platform-experience` → `refactor/web` 的 PR。与本变更无关的既有脏文件保持原样；change 归档和主规格同步留待最终验证完成后执行。

## Open Questions

无。Maestro/Detox runner、Native 分类/投资/工作区管理页面和商店发布均已明确留到后续变更，不影响本阶段设计或任务拆分。
