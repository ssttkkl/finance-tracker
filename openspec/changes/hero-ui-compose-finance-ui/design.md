## Context

现有 Compose `commonMain` 同时使用 Material3、Foundation 和生成的 Kotlin token；`design/finance-ui.lib.pen` 现在作为基于 HeroUI 真实 DOM 的 Finance 组件库，旧 `finance-design-system.lib.pen` 只保留历史资料。Cash Ledger 的现有实现分布在 `presentation` 页面与 `FeatureComponents.kt`，跨端语义 ID 和窗口等级已有基础合同。详见 `proposal.md` 与本变更的 delta specs。

## Goals / Non-Goals

**Goals:**

- 建立 HeroUI CSS variables → Git 中的 Finance semantic token → Pen variables / Compose token 的可追溯同步链。
- 以 HeroUI 官方代码/CSS 和真实组件 DOM 表达上游视觉与行为参考；Browser Import 参考稿和 HeroUI Pen 组件库必须可编辑，Finance 库表达产品组件所有权。
- 用 Compose Unstyled `2.10.0` 为首批原语提供焦点、键盘、禁用和无障碍行为。
- 将 Cash Ledger 及已确认的 F-01、F-02、F-04 至 F-10 迁移到可复用 Finance 组件，验证三种逻辑窗口等级及完整状态。
- 让 Registry 和 Screen Contract 能被设计评审、实现和自动化测试共同查询。

**Non-Goals:**

- 不一次性删除 Material3 依赖；日期、时间和平台对话框可继续使用现有实现。
- 不改变账本 API、金额精度、持久化、权限和领域规则。
- 不把 HeroUI 运行时代码、CSS 或 Figma 资产打包进 Compose runtime。
- 不在本阶段实现完整 DataTable 的列调整、虚拟化或导出能力。

## Decisions

### 1. 采用代码优先的四层边界

HeroUI 官方代码（`@heroui/react`、`@heroui/styles` 和 CSS variables）是上游事实源。保存版本固定的主题 CSS 来源副本；临时 Gallery 暴露真实 DOM，Pen Browser Import 逐组件生成可编辑的 `heroui-reference.pen`，经清理和组件化生成 `heroui.lib.pen`。`finance-ui.lib.pen` 引用 HeroUI 视觉语义并拥有业务变体，`cash-ledger.pen` 引用 Finance 组件，Compose `finance-ui` 包保存运行时实现。每一层记录来源与映射，不把手工绘制的占位组件冒充 Browser Import 产物。

替代方案是把整个 Figma Kit 直接导入并当作 Pen 组件库，或让页面直接引用 HeroUI 参考库；Figma import 不保证 Variables、component variants 和 slots 的一比一语义映射，后者也会让业务合同依赖外部命名，因此不采用。

### 2. 以 semantic token 兼容现有 token

从版本固定的 `@heroui/styles` 提取浅色、深色 CSS variables，整理到 `design/source/heroui-theme.css` 并记录包版本；在现有 `core.tokens.json`、`light.tokens.json` 和 `dark.tokens.json` 中建立 Finance semantic role 的映射，生成器输出 Compose token 与 Pen 消费清单。通过 pen.dev 官方工具把相同语义值写入 Pen variables，并核对浅色、深色主题。项目 token 是 Finance 产品值的 Git 事实源，HeroUI CSS 是其上游设计来源，`.pen` 不反向修改 token。

深色主题的支出金额和危险提示使用 Finance Pen 库已有的 `color.semantic.danger` 值 `#F04438`，使其在深色表面上的正文对比度达到 4.72:1；危险按钮使用深色前景。该可读性调整保留 HeroUI 的语义角色，不改变金额正负或危险操作语义。

替代方案是一次性重命名旧 token，但会扩大未迁移页面风险，因此延后。

### 3. 按模块引入 Compose Unstyled

版本目录固定 `2.10.0`，只引入首批实际需要的 `composeunstyled-button`、`composeunstyled-text-field`、`composeunstyled-dropdown-menu`、`composeunstyled-dialog` 及其传递依赖。Finance Primitive 包装原语并注入 token；DataTable、Amount 和金融组合控件保留 Foundation 实现。

替代方案是 composite build 或完整依赖集合，前者增加构建维护成本，后者扩大二进制和 API 面积。

### 4. Registry 是参考 → Finance → Compose 的稳定边界

在 `docs/design-system/` 保存 YAML/JSON Registry 与 Screen Contract 示例，组件名、变体、状态、插槽、语义 ID 和 Compose 符号必须唯一。Registry 记录 HeroUI 参考来源、Finance 组件所有权和 Compose 实现；实现和测试只依赖 Registry 中的稳定名称，不从 Pen 节点 ID 或页面局部结构推断组件。

### 5. Cash Ledger 先迁移组合层

先为 Button、TextField、Select、DataTable、FilterBar、Amount 建立失败测试，再替换 Cash Ledger 的调用点。页面 ViewModel、API 和金额展示逻辑保持原有边界；加载、空、错误和成功状态通过既有 `CashLedgerUiState` 映射到 Finance 组件。

### 6. 已确认页面按共享组件迁移

用户已确认 F-01、F-02、F-04 至 F-10 的正式 Pen 设计稿。以现有 React Web 的区域顺序为布局基线，先校准 Finance 的浅深色 token、按钮、字段、选择器和表面，再按页面迁移认证与工作区、收支与导入、分类与投资。Compose `commonMain` 同时服务 Web、Android 和 iOS；保留现有 ViewModel、API、金额格式、权限检查及语义 ID。移动端单列，宽屏保持 Web 的筛选、列表、详情与操作关系；日期、时间、文件选择和确认对话框沿用平台可操作控件。每批以编译、测试和浏览器截图对照 Pen 验收。

## Risks / Trade-offs

- [Compose Unstyled API 与当前 Kotlin/Compose 版本不兼容] → 在接入任务中先编译最小原语样例；若某模块不兼容，锁定兼容模块版本并记录，不把 Material3 引入新 Primitive。
- [Browser Import 不可用或导入图层失真] → 通过官方 CLI 连接 Pen Desktop，按组件导入真实 Gallery DOM，逐项核对图层和导出图；不可用时将设计链路标记为阻断，不以 Figma 或手工占位稿替代。Figma Kit 只用于视觉核对。
- [旧 Material3 控件与新 Finance 主题并存造成视觉差异] → F-01 至 F-10 共享页面统一使用 Finance 主题和首批 Finance 控件；日期、时间、文件与系统对话框保留平台控件，并在浅深色截图中复核。
- [DataTable 状态或响应式回归] → 先建立 `390`、`768`、`1440` 宽度的语义和截图检查，复杂表格能力延期到独立变更。
- [Registry 与 Pen 资产漂移] → 在 CI 增加 Registry 名称、Compose 符号和生成 token 的结构校验；人工设计审查记录在 tasks。

## Migration Plan

1. 从 HeroUI 代码/CSS 整理 token 来源，建立临时 Gallery；将真实 DOM 导入 Pen，生成 Pen variables、HeroUI 参考稿和库，再补 Finance token、Compose Unstyled 依赖与 Registry。
2. 为原语与页面状态补测试，完成 Finance Primitive、主题适配器和 Cash Ledger 组件调用点。
3. 按已确认的 Pen 稿迁移 F-01/F-02/F-04 至 F-10，运行 Compose 测试、Web 构建与真实浏览器 QA。
4. 发现回归时回退对应共享页面的 Finance 组件调用；保留 token 兼容别名和 Registry，避免影响其他页面。

## Clarification Record

- 主事实源：HeroUI 官方代码、`@heroui/styles` 和 CSS variables。
- Pen 边界：`heroui-reference.pen` 来自 Gallery 的真实 DOM；`heroui.lib.pen` 保存清理后的 HeroUI 组件；`finance-ui.lib.pen` 保存基于 HeroUI 图层和语义变量的 Finance 组件；`cash-ledger.pen` 按 Web 区域顺序引用 Finance 组件。Figma Kit 只作视觉核对。
- 页面信息架构：导航（由宿主壳层提供）→ 页头与「记一笔／导入账单」→ 筛选区 → 收支记录 → 分页反馈；移动端将记录表转换为单列记录行，桌面端保留 DataTable。
- 删除的冗余信息：移除页面级教学区、实现来源说明、重复的状态解释和旧稿中的装饰性侧栏文案；状态只保留用户需要采取行动的短文案。
- 当前设计稿：`design/cash-ledger.pen` 已由 Pen CLI 重建，含 390、768、1440 主画布、加载/空/错误/禁用/成功/焦点状态和危险确认画布。已用同一稿临时调整到 320、375、414 px 检查移动端边界，再恢复 390 px 基准；六个要求宽度均无页面级横向溢出或文字裁切。用户已确认该稿与其余九份页面稿。
- 本次范围：已落地的 token、Compose 组件及 F-01 至 F-10 页面接入均按上述来源重新核对；`heroui-reference.pen`、`heroui.lib.pen`、`finance-ui.lib.pen` 和十份页面稿形成设计链路。浏览器与 Native 验证结果记录在 `tasks.md`。历史 HTML 原型不作为实施门禁。
- 需求澄清：已按仓库要求调用 `grilling` skill，并由用户确认以上三项建议。

## Pen 设计自审记录

- **审查范围：** `design/cash-ledger.pen` 的 Compact（390 px）、Regular（768 px）、Wide（1440 px）、状态画布和危险确认画布；同时覆盖 320、375、414 px 的 Compact 临时宽度检查。
- **已修复 finding：** 将 Button 库的 `Primary`、`Secondary` 示例文案覆盖为「记一笔」「导入账单」「加载更多」「重试」「取消」「确认删除」；隐藏日期组件重复的 `Account name` 标签并改为「选择日期」；将 Compact 筛选字段改为单列并补足字段高度；将错误状态操作从卡片外溢出收回卡片内；将状态标题改为「收支账本状态」。
- **复核结论：** 390 px 移动布局保持单列，320/375/414 px 均无横向溢出；768/1440 px 保留 Web 侧边导航、完整六字段筛选、月份汇总、收支表格、分页反馈和详情区域；加载、空、错误、禁用、成功、焦点和危险确认均可见且没有重叠或裁切。
- **证据：** Pen CLI 导出截图暂存于 `/tmp/cash-ledger-previews/`；正式预览写入 `design/previews/`。HeroUI Table 导入层仍有 3 条来源几何 partial-clip 提示，属于官方 DOM 边界的资产级残余风险，不影响页面级布局。
- **跨端影响：** 本轮仅调整 Pen 视觉组织和文案覆盖；Web、Native 与共享语义 ID、业务 API、金额和分页合同不变。Native 页面未因本轮设计稿调整而新增实现范围，继续按现有 Cross-platform Impact Check 记录为待实现对齐项。

## 其余页面的 Web 基线与 Pen 设计

2026-09-30 使用 Playwright Chromium 和去标识化 API fixture 打开现有 React Web 的真实页面，分别检查 390 与 1440 px。工作区内页面沿用侧栏/移动顶栏、页头、任务区的顺序；认证、创建与邀请保持窄内容区；收支流水保留移动全屏、桌面抽屉的承载差异。Pen 通过 `pen interactive` 从 `finance-ui.lib.pen` 的 HeroUI 派生组件创建按钮、字段和选择控件，不直接编辑 `.pen` 内部结构。`tools/pen/rebuild-web-pages.mjs` 记录可重复的 CLI 操作。

| 功能 | Web 信息架构基线 | Pen 稿 |
|------|------------------|--------|
| F-01 认证 | 登录/注册标题 → 邮箱与密码 → 提交 → 模式切换 | `design/login.pen` |
| F-02 工作区入口 | 工作区名称 → 创建；已有工作区由壳层切换，进入失败可重试 | `design/workspace-entry.pen` |
| F-04 收支流水 | 金额与币种 → 流水类型/账户 → 时间/交易对象/分类/备注 → 保存；桌面抽屉、移动全屏 | `design/cash-record.pen` |
| F-05 账单导入 | 选择文件 → 映射账户 → 核对流水 → 配对 → 确认 | `design/cash-import.pen` |
| F-06 邀请 | 工作区与角色 → 接受/暂不加入；无效邀请返回登录 | `design/invitation.pen` |
| F-07 分类 | 搜索与层级目录 → 选择后编辑/新增 → 删除影响确认 | `design/cash-categories.pen` |
| F-08 持仓 | 账户/排序/标的/币种/时间筛选 → 估值摘要 → 持仓列表 | `design/investment-holdings.pen` |
| F-09 事件 | 日期/账户/类型/标的筛选 → 事件列表 → 分页与详情 | `design/investment-events.pen` |
| F-10 工作区管理 | 名称与固定 ID → 成员角色 → 邀请链接 → 删除确认 | `design/workspace-management.pen` |

删减项：移除旧 Pen 稿中与 Web 不一致的并排移动端栏位、装饰性说明、重复标题和空泛教学文字；正常态只展示当前任务所需字段。每份新稿包含 320、375、390、414、768、1440 px 画布和加载、空、错误、成功、禁用/焦点状态画布；导入稿另含账户映射与核对流水，分类和工作区稿含危险确认。正式截图在 `design/previews/<页面名>-<宽度>.png` 与 `*-states.png`。

自审第一轮修复分类默认态、投资桌面表格和 Finance 危险按钮对比度，并补齐导入、成员和危险确认操作。第二轮以真实 Web 截图逐项复核，修复下列 finding：

- **重大，已修复：** F-04 新建流水的字段顺序、字段名和双栏行关系偏离 Web，还缺少「对方账号」。现按 Web 的金额与币种 → 交易对方 → 对方账号 → 发生时间 → 账户 → 流水类型 → 分类 → 备注 → 保存组织，移动端保持字段名与控件左右对应，桌面抽屉宽 480 px。
- **重大，已修复：** F-08/F-09 的桌面表格最初是页面局部拼装且缺少关键列。现在参照已导入的 HeroUI Table 图层与 Finance token，在 `finance-ui.lib.pen` 补出持仓与投资事件两个可复用组件，页面通过库引用；补齐估值、表现和事件详情列。
- **一般，已修复：** F-07 示例目录与 Web 层级不一致；F-09 缺少筛选摘要与收起操作、移动样例行数不一致；F-10 成员角色与移除操作脱离所属成员、桌面工作区信息未并排。对应 Pen 画布已调整。
- **一般，已修复：** 状态画布原先只写禁用与焦点说明，现显示实际的焦点字段、禁用按钮和错误重试控件；筛选栏异常箭头改用 Lucide 图标。

2026-09-30 使用 Playwright Chromium 的既有「新建流水沿用信息抽屉」用例补齐 F-04 实页对照，390/1440 px 截图为 `/tmp/finance-web-cash-record-390.png`、`/tmp/finance-web-cash-record-1440.png`，用例通过；先前空白截图不再作为证据。九份稿的六档宽度已重新导出，375/414 px 的导入、投资事件、工作区与收支抽屉边界经目视复核，未见横向裁切或文字重叠。结构核对显示各稿均有六个主画布、一个状态画布、零个未完成 placeholder，并引用 Finance 库组件。CLI 仍只报告 HeroUI 原始 Table 导入层的 3 项几何 partial-clip，页面节点无裁切；这是资产级残余提示，不代表 Compose 页面已经完成迁移。

Cross-platform Impact Check：本轮变动为 Pen 资产及其来源记录，Web 与 Native 运行行为、共享语义 ID、财务数据流不变；下一阶段 Compose `commonMain` 迁移将同时影响 Compose Web、Android、iOS，须先取得稿件确认、补齐相应能力 delta 和失败测试，再按 F-01 至 F-10 页面流程验证。

## 页面实施确认

2026-10-01，用户确认九份正式 Pen 设计稿并授权实施、提交及推送当前 `refactor/kmp` 分支。信息架构及删除的冗余信息以本文件「其余页面的 Web 基线与 Pen 设计」为准。验收为各页主操作、加载/空/错误/禁用/焦点/成功及危险确认保持原业务语义；320/375/390/414/768/1440 px 无页面级横向溢出，390 与 1440 px 对照 Pen 截图复核。共享 `commonMain` 改动影响 Compose Web 与 Native，React Web 仅作布局参考，共享领域和持久化层不受影响。
