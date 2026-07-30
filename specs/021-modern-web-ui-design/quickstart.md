# 验证指南：现代化收支账本界面

本指南验证 Feature 021 只改变展示层，不改变 `020-cash-ledger-browser-web` 的既有行为。

## 前置条件

1. 020 的 artifacts 和实现已收敛为明确提交；021 implementer worktree 从该提交创建，并在 `plan.md` 记录基线 SHA。
2. 当前终端已具备 Node.js 20+、Python 3.11+、项目依赖和 Playwright 浏览器。
3. 使用现有去标识化 fixture 或测试 API；不得使用真实账本数据作为视觉快照。

## 快速验证

在仓库根目录运行受影响的前端组件和无障碍测试：

```bash
cd web
npm test -- CashTable.test.tsx CashLedgerPage.test.tsx accessibility.test.tsx runtime.test.tsx
```

预期：筛选、8 列顺序、备注位置、金额、状态、焦点恢复和表头语义均通过。

运行完整前端测试与构建：

```bash
npm test
npm run build
```

预期：TypeScript 与 Vite 构建通过；不修改 `web/src/api/`、Python 后端或依赖清单。

运行浏览器和生产预览验证：

```bash
npm run test:e2e
npm run test:preview
```

预期：

- 键盘可完成筛选、自动或手动加载更多、打开/关闭证据详情。
- 列表末端自动连续加载；“加载更多”始终作为键盘、辅助技术、自动加载失败与浏览器不支持自动触发时的回退。
- 宽屏详情并列，窄屏详情全屏。
- 页面没有横向溢出，触控目标至少 44 px。
- 减少动效下详情没有空间过渡，背景不可交互。

## 本轮验证证据（2026-07-30）

已在 021 独立 worktree 运行，均使用去标识化 fixture：

- `npm test`：4 个文件、21 项 Vitest 测试通过。
- `npm run build`：TypeScript 与 Vite 生产构建通过。
- `npm run test:e2e`：3 项开发服务器 Playwright 测试通过，覆盖 320、375、390、414、768、1024、1440 px 的适用场景。
- `npm run test:preview`：首次因 `127.0.0.1:8766` 被遗留 Python 进程占用失败；停止该遗留进程后重跑，1 项生产预览测试通过。
- `npx playwright test -c tests/playwright.visual.config.ts --update-snapshots`：8 项视觉测试通过，覆盖默认/展开筛选、追加中、追加失败、已显示全部记录、390 px 分类/导入渠道、宽屏并列与窄屏全屏详情；生成或重生成去标识化候选快照，用户已于 2026-07-30 人工批准，快照仅纳入当前 worktree，尚未创建 Git 提交。

- `npx playwright test -c tests/playwright.visual.config.ts`：2026-07-30 在 `web/` 目录重跑。实施者确认候选差异仅来自已批准的筛选摘要、加载状态和视觉层级变更，更新 4 张受影响基线后复跑，8 项视觉测试通过。

尚待完成的门禁：gstack `qa` 已通过 Skill 发起，但其强制要求干净 worktree；当前 worktree 有本 feature 的未提交实施改动，且本任务未获提交或暂存授权，故无法继续。自动化浏览器、生产预览与视觉回归均已通过，但风险是缺少独立浏览器 QA；待允许提交或安全暂存后重新执行 gstack `qa`。不得以 shell 的 `specify analyze`、`specify converge` 或 `hallmark audit` 代替对应 Skill。
本轮最终静态审计已通过：`git diff --check` 无输出，且禁止路径 `web/src/api/`、`src/ft/`、`migrations/`、依赖清单、`specs/020-*` 与 `specs/022-*` 的 name-only 审计无输出。T029 后运行 `npm test -- CashTable.test.tsx CashLedgerPage.test.tsx accessibility.test.tsx`，3 个文件、19 项测试通过；`npm run build` 通过。

视觉快照已由用户逐项核对并批准，仅纳入当前 worktree，尚未创建 Git 提交。

## 多视口视觉快照

运行新增的视觉快照测试：

```bash
npm run test:e2e -- cash-ledger.visual.e2e.ts
```

首次创建或更新快照时，实施者逐项检查：

| 视口 | 必查内容 |
|---|---|
| 1440 × 900 | 默认/展开筛选、表格密度、金额轨道、连续加载状态、并列证据详情 |
| 1024 × 768 | 主区和详情最小宽度、无覆盖、加载更多或追加失败状态 |
| 768 × 1024 | 顶部导航、默认折叠筛选、表格语义与焦点 |
| 390 × 844 | 卡片信息层级、分类/导入渠道、备注/金额/查看入口、全屏详情 |
| 320 / 375 / 414 / 768 px | 无横向溢出、按钮不换行、focus ring、触控目标和表头语义 |

只有实施者确认差异来自批准的展示层改造，才可更新基线快照。若快照显示 API 文案、字段、焦点、请求行为或业务状态变化，必须拒绝更新并回到对应 artifact。

## Hallmark 检查

1. 核对样式文件首行 Hallmark 戳记包含 modern-minimal、Workbench、Cobalt 适配、预发评分和所用令牌。
2. 在 320、375、414、768 px 实机或浏览器视口检查：无横向溢出、关键可点击文字不换行、焦点清晰、表头仍有原生语义。
3. 检查颜色、字体和动效只使用命名令牌；禁止渐变文字、伪造浏览器/终端 chrome、图表、营销 hero、虚构指标、玻璃拟态和 `transition: all`。
4. 运行 Hallmark slop-test，修复所有适用 gate；记录不适用的 gate 及理由。

## 最终边界检查

在交付前检查 diff：

```bash
git diff --name-only <021-baseline-sha>...HEAD
```

允许的实现路径仅为：

- `web/src/pages/`
- `web/src/components/`
- `web/src/styles.css`
- `web/tokens.css`（如创建）
- `web/tests/`
- `specs/021-modern-web-ui-design/`

以下路径出现改动即失败，除非主 session 先 Flow-Back 修改 021 artifacts 并重新通过门禁：

- `web/src/api/`
- `src/ft/`
- `migrations/`
- `package.json`、锁文件或其他依赖清单
- `specs/020-cash-ledger-browser-web/`
- `specs/022-investment-ledger-browser-web/`

最后运行 gstack `review` 与 gstack `qa`，并使用 `$speckit-converge` 对照 `spec.md`、`plan.md`、`tasks.md` 和实现收敛。
