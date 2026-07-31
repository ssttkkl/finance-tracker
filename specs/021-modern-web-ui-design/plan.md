# 实施方案：现代化收支账本界面

**Feature**：`021-modern-web-ui-design`
**日期**：2026-07-30
**规格**：[spec.md](spec.md)
**设计依据**：`/Users/huangwenlong/.gstack/projects/finance-tracker/huangwenlong-spec-20-progress-design-20260730-021-modern-web-ui.md`

## 摘要

在不改变 `020-cash-ledger-browser-web` 的数据读取、筛选、稳定 cursor、投影版本处理、证据详情和可访问性合同的前提下，重构 Web 展示层为技术感、工具化的收支账本工作台。主列表默认折叠筛选，以自动连续加载和始终可用的“加载更多”回退浏览多批收支记录；不再显示上一页/下一页或主列表的投影技术说明。实现使用 Hallmark 的 modern-minimal / Workbench / Cobalt 适配方向：冷色浅表面、单一钴蓝操作强调、现有中文正文与等宽数值字体、精确边界和高密度审阅结构。

本 feature 不新增产品功能，不修改 `web/src/api/`、Python 后端、数据库、迁移、依赖清单或 020/022 artifacts。视觉快照仅验证展示层，不是新业务能力。

## 技术上下文

**语言/版本**：TypeScript 5.7、React 19、CSS；Python 3.11+ 仅作为既有本机 API 运行时，不在本 feature 修改。
**主要依赖**：Vite 6、React、`@fontsource/noto-sans-sc`、`@fontsource/ibm-plex-mono`、Vitest、Playwright。
**存储**：不适用。021 不读写数据库、不改变 SQLite/PostgreSQL 查询或合同。
**测试**：Vitest + Testing Library、Playwright 开发服务器测试、Playwright 生产预览测试、Hallmark 响应式/反模式检查。
**目标平台**：受信任本机上的 Chromium 浏览器；宽屏与移动浏览器视口。
**项目类型**：既有 Python 本机 API + Vite/React 单页 Web 的纯展示层改造。
**性能目标**：不降低 020 已有“100 条收支记录在 2 分钟内完成筛选、自动或手动加载更多和证据详情查看”的验收；不新增 API 端点、请求参数、读取范围、运行时依赖或后台任务。自动连续加载允许改变既有 `fetchCashPage` 的调用时机和次数。
**约束**：保留现有 API 请求、金额十进制字符串、表格语义、键盘焦点、详情 `inert` 背景、减少动效；窄屏交互元素最小 44 px；所有颜色和字体只使用命名令牌。
**范围**：允许修改 `web/src/pages/`、`web/src/components/`、`web/src/styles.css`、可选 `web/tokens.css` 和 `web/tests/`；禁止修改 `web/src/api/`、`src/ft/`、`migrations/`、依赖清单、020/022 artifacts。

## Constitution Check

### 研究前检查

| 原则 | 结论 | 证据 |
|---|---|---|
| 财务正确性与可审计性 | 通过 | 不重新计算金额，不改变投影、关系或证据字段；列表继续显示 API 的精确十进制字符串。 |
| Spec Kit 规格驱动 | 通过，实施前置未满足 | 021 已有独立中文规格、设计记录、研究、状态模型和兼容性合同；必须在 020 收敛后继续 tasks/analyze/implement。 |
| 测试先行与验证证据 | 通过 | 任务必须先增加/调整组件、可访问性、浏览器和视觉快照失败测试，再替换展示层。 |
| 显式数据库选择与行为等价 | 不适用且受保护 | 不修改数据库、持久化、查询或后端；既有双后端合同只作为回归边界。 |
| 清晰边界与最小复杂度 | 通过 | 不新增服务、路由、共享组件库或前端依赖；展示结构只消费现有状态和事件。 |

**实施基线**：020 的 artifacts、实现和本轮收敛验证已冻结并推送为 `49449210a5ec1705085249b46f23d6fe66b1aab5`（`spec-20-progress`）。021 实施必须从该提交创建独立 feature 分支或 worktree；交接时该 SHA 同步记录在 `tasks.md`。

### 设计后检查

- 未引入数据库 schema、迁移、事务、并发或查询变化，因此无需新增 PostgreSQL/SQLite 矩阵。
- 未引入 API 端点、请求参数、读取范围、外部依赖、后台作业、认证面或凭据。自动连续加载只改变既有 `fetchCashPage` 的调用时机和次数。
- CSS 令牌只限本 feature 页面；不建立跨 feature 设计系统，避免提前抽象。
- 视觉快照复用现有去标识化 fixture；禁止用真实财务数据作为快照资产。
- 结论：**通过，但在 020 基线收敛前不得实施。**

## 设计契约

### Hallmark 预检记录

| 信号 | 现状 | 保留/引入决策 |
|---|---|---|
| 字体 | 已安装 `Noto Sans SC`、`IBM Plex Mono` | 保留，不新增 Cobalt 原始字体依赖；正文/界面使用前者，金额/时间/标识使用后者。 |
| 配色 | 浅灰底、白表面、蓝色交互，色值散落 | 引入受限 CSS 令牌，改为冷色浅表面 + 单一钴蓝操作强调。 |
| 动效 | 无动效库；详情只在非减弱动效下有 CSS 过渡 | 保留无依赖方针；只允许明确的 `transform` / `opacity` 过渡。 |
| 布局 | 侧栏/顶部导航、筛选、表格/卡片、详情并列或全屏 | 保留信息架构与所有交互合同；重组展示容器和层级。 |
| 框架 | Vite + React 19 | 遵循现有组件和全局样式约定。 |

### Hallmark 选择与适配

- **类型**：modern-minimal。
- **宏观结构**：Workbench。它在本 feature 中表示“真实工作台优先”的页面层级，不引入宣传页 hero、截图叙事或 CTA。
- **主题**：Cobalt 的适配版本。使用冷色纸面、钴蓝信号、1 px 边界、紧凑圆角、数值轨道；不采用命令面板、代码 hero、伪造窗口 chrome、营销页导航/页脚或新增字体。
- **导航**：只重构已有产品标识和当前“收支账本”指示；不得新增链接、空白槽位、投资入口或命令入口。
- **富化**：无。使用真实列表、筛选和证据内容作为工作台主体；不得增加插图、图表、虚构指标或装饰图形。
- **预发评分**：Philosophy 5、Hierarchy 5、Execution 5、Specificity 5、Restraint 5、Variety 4。实施时在样式文件首行记录相同格式的 Hallmark 戳记，并在最终检查中重新评分。

### 令牌与视觉规则

1. 在 `web/tokens.css`（新增时）或 `web/src/styles.css` 顶部定义 `--color-*`、`--font-*`、`--space-*`、`--radius-*`、`--rule-*`、`--dur-*`、`--ease-*`、`--z-*`。
2. 全部颜色使用 OKLCH 令牌；任何组件样式不得内联颜色值或字体族。`--color-accent` 填充表面必须同时使用已验证对比度的 `--color-accent-ink`。
3. 使用 4 px 间距比例、1 px 边界和紧凑圆角。深度来自布局、字体权重和边界，不使用堆叠阴影、玻璃拟态或渐变。
4. 标题保持中文业务含义；默认不添加 section eyebrow、编号、营销 CTA 或虚构数值。
5. 金额、时间和标识保留 `font-variant-numeric: tabular-nums` 和等宽样式；收入/支出必须同时依赖正负号、文本/上下文和辅助颜色。
6. 输入、select、按钮定义默认、hover、focus-visible、active、disabled、loading、error、success 的样式契约；不得因状态改变边框宽度或产生布局位移。

### 响应式与无障碍规则

- 在 `html` 与 `body` 使用 `overflow-x: clip`，不使用 `hidden`。
- 所有主要可点击文案保持 `white-space: nowrap`；父布局应重排而非让按钮文字换行。
- 1440 × 900 与 1024 × 768 保留详情并列；768 × 1024 以下保持顶部导航；390 × 844 保留卡片列表与全屏详情。Hallmark 另检 320、375、414、768 px。
- 保留 `<caption>`、`<thead>`、`<th scope="col">` 与卡片字段关联。移动端表头可视觉隐藏，但不得 `display: none`。
- focus ring 立即出现、至少 3:1；`prefers-reduced-motion: reduce` 下没有空间运动；所有触控按钮高度至少 44 px。

### 连续加载状态机

`CashLedgerPage` 保留既有账户读取、证据详情、`AbortController`、递增请求代次、筛选变更与 `projection.updated` 处理。列表状态从单页和游标栈演进为累计收支记录、下一个 `cursor`、首批加载、追加加载和追加错误：

```text
首批成功：替换累计收支记录，保存下一个 `cursor`
追加成功：按服务端排序追加，并按 `projection_id` 去重
追加失败：保留已加载收支记录，显示文字错误与人工重试
筛选变化/版本更新：取消请求，递增代次，清空累计收支记录和 `cursor`，从 `null` 游标重读首批
```

列表末端的 `sentinel` 使用 `IntersectionObserver` 预加载下一批；`observer` 与“加载更多”按钮共用防重入的 `loadMore()`。同一 `cursor` 在任意时刻最多一个请求；`hasMore=false`、追加中、追加错误和组件卸载时不得自动触发。浏览器不支持 `observer`、键盘/辅助技术使用者或自动加载失败时，始终保留“加载更多”按钮。移除上一页/下一页，不向使用者暴露 `cursor`。

## 展示层架构

```text
既有本机 API（不改）
       │
web/src/api/*（不改）
       │
CashLedgerPage（保留状态、请求取消、游标、版本更新、焦点恢复）
       │
       ├── LedgerWorkspaceShell（既有单页框架和当前页指示）
       ├── LedgerHeader（标题和现有说明）
       ├── CashFiltersBar（字段与 `onChange` 合同不变，默认折叠的范围摘要）
       ├── CashTable（8 列语义和查看入口不变，宽屏/卡片层级重组）
       ├── LoadMoreControl（自动连续加载的可访问回退、追加失败重试与终止状态）
       └── EvidenceDetail（焦点、Escape、inert 不变，审阅分组重组）
```

### 展示层数据流与阴影路径

```text
既有 filters / cursor / selected
             │
             ▼
CashLedgerPage 的既有请求和状态机
             │
   ┌─────────┼──────────────────────────┐
   ▼         ▼                          ▼
就绪列表   空列表                      错误/版本更新
   │         │                          │
   ▼         ▼                          ▼
重构表格   重构空状态                  重构状态视图
   │
   ├─ 打开证据 → 既有证据请求 → 重构详情
   ├─ 无选择值 → 不渲染详情
   ├─ 空条目 → 显示既有空状态
   └─ 请求失败 → 显示既有错误与重试
```

不存在新的数据持久化、转换或读取路径。视觉组件接收现有 props，禁止自行发起请求。

## 项目结构

```text
specs/021-modern-web-ui-design/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── contracts/
│   └── web-ui-compatibility.md
├── quickstart.md
└── tasks.md                         # 由 $speckit-tasks 生成

web/
├── tokens.css                       # 可选：仅 021 页面令牌
├── src/
│   ├── pages/CashLedgerPage.tsx
│   ├── components/CashFilters.tsx
│   ├── components/CashTable.tsx
│   ├── components/EvidenceDetail.tsx
│   ├── components/Pagination.tsx
│   ├── components/StatusView.tsx
│   └── styles.css
└── tests/
    ├── CashLedgerPage.test.tsx
    ├── CashTable.test.tsx
    ├── accessibility.test.tsx
    ├── cash-ledger.e2e.ts
    └── cash-ledger.visual.e2e.ts    # 新增：固定 fixture 的视觉快照
```

**结构决策**：保留单页 React 应用。页面状态不抽取为新服务或全局状态；仅重构现有页面和组件的展示标记、局部容器及样式。`web/src/api/` 不在 021 文件清单中。

## 测试与验证策略

### 测试先行顺序

1. **冻结基线**：确认 020 已收敛提交，记录 SHA；运行 020 现有前端测试、浏览器测试、构建与生产预览作为重构前证据。
2. **合同回归测试**：先扩展 Vitest 与 E2E，锁定 API 调用、筛选值、8 列顺序、备注位置、无关系摘要、连续加载、投影更新、错误码文案、证据焦点和表头语义。
3. **视觉失败测试**：先创建固定 fixture 的多视口截图断言，覆盖默认/展开筛选、首批加载、追加加载、追加失败、全部加载完、空、错误、并列详情与全屏详情；首次生成基线必须人工批准。
4. **展示层重构**：仅在失败测试明确后，由 implementer 逐组件替换展示标记和样式，并持续运行受影响测试。
5. **Hallmark 验收**：检查令牌、字体、焦点、交互状态、动效、对比度和 58 项 slop-test；修复所有失败项。
6. **全量验证**：运行完整 Vitest、构建、开发 E2E、生产预览 E2E、视觉快照与 gstack `qa`；最终检查 diff，确认禁止路径无变更。

### 视觉快照矩阵

| 场景 | 1440×900 | 1024×768 | 768×1024 | 390×844 |
|---|---:|---:|---:|---:|
| 默认折叠筛选与首批列表 | ✓ | ✓ | ✓ | ✓ |
| 展开筛选 | ✓ |  | ✓ | ✓ |
| 首批加载与追加加载 | ✓ |  |  | ✓ |
| 追加失败与全部加载完 | ✓ | ✓ |  | ✓ |
| 空状态 | ✓ |  |  | ✓ |
| 错误状态 | ✓ |  |  | ✓ |
| 证据详情 | 并列 | 并列 |  | 全屏 |

Hallmark 人工检查另覆盖 320、375、414、768 px：横向溢出、单行可点击文案、表头语义、长文本、焦点和触控目标。

### Hallmark 最终检查

- 预发自评：P/H/E/S/R/V 均不低于 3 分。
- 反模式：无渐变文字、无伪造 chrome、无营销 hero、无图表/虚构指标、无玻璃拟态、无通用卡片堆叠、无跨 feature 导航。
- 令牌：所有颜色与字体均来自命名令牌。
- 对比度：正文至少 4.5:1，交互边界和 focus ring 至少 3:1；钴蓝填充使用 `--color-accent-ink`。
- 响应式：通过 gate 34、49、50–57 的适用项；表格没有图片网格，不适用 gate 50/53/56/57 时在验证记录中明确说明。

## 实施交接与回滚

- 主 session 在 artifacts 完整、`$speckit-analyze` 无 CRITICAL/HIGH 问题后，调用 `speckit_implementer`，并在独立 worktree 中执行 `$speckit-implement`。
- implementer 只能触及本方案的允许路径；发现 API、文案语义、领域行为或 020 合同缺口时必须停止，并返回主 session Flow-Back。
- 不存在数据迁移或部署顺序变化。若展示层回归，使用 `git revert` 回退 021 提交即可恢复 020 的既有 UI；视觉快照基线必须随回退一并恢复。

## Complexity Tracking

无 Constitution 违例，不需要复杂度豁免。
