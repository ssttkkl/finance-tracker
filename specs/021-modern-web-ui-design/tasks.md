# Tasks：现代化收支账本界面

**Input**：`specs/021-modern-web-ui-design/` 的当前 artifacts。所有展示与交互变更遵循测试先行；021 不修改 API、后端、持久化或依赖。

## Phase 1：Living Spec 与测试门禁

- [X] T001～T011：020 冻结基线、术语审校、展示范围与原始视觉/合同测试基础已完成。
- [X] T012 在 `spec.md`、`plan.md`、`contracts/web-ui-compatibility.md`、本文件和 `quickstart.md` 回写已批准 A 审计工作台：默认折叠筛选、主列表用户术语收敛、移动端分类/导入渠道、自动连续加载与“加载更多”回退；已于 2026-07-30 运行 `$speckit-analyze`，初次发现 C1/I1/L1 并完成 artifact 修正；复跑分析确认 CRITICAL/HIGH 已清零。
- [X] T013 [P] 在 `web/tests/CashLedgerPage.test.tsx` 先补失败测试：默认折叠、首批替换/追加、同 cursor 防重、追加失败重试、末批终止、筛选或版本更新时取消追加与重置。
- [X] T014 [P] 在 `web/tests/CashTable.test.tsx`、`web/tests/accessibility.test.tsx` 先补失败测试：桌面八列顺序、主列表无技术说明、移动端真实“分类/导入渠道”文本、加载更多的可访问状态，以及证据详情既有焦点与 inert 回归。
- [X] T015 [P] 在 `web/tests/cash-ledger.e2e.ts` 先补失败测试：自动追加三批、键盘“加载更多”回退、同 cursor 一次请求、追加失败保留条目且可重试、向上回看已加载条目、四视口无横向溢出。
- [X] T016 在 `web/tests/cash-ledger.visual.e2e.ts` 先补快照场景：默认/展开筛选、追加中、追加失败、全部加载完、390 px 分类/导入渠道、宽屏并列与窄屏全屏详情。

## Phase 2：用户故事 1——筛选与连续浏览

- [X] T017 在 `web/src/pages/CashLedgerPage.tsx` 保留既有 `AbortController`、请求代次、筛选和版本更新逻辑；将单页与 cursor 栈改为累计记录、下一个 cursor、首批加载、追加加载和追加错误状态。筛选/版本更新时中止、清空并从首批读取；追加成功按 `projection_id` 去重，失败不清空记录。
- [X] T018 在 `web/src/components/CashFilters.tsx` 与 `web/src/styles.css` 默认折叠原生 `<details>`，保留所有字段、即时筛选与键盘语义；呈现已批准范围摘要。
- [X] T019 在 `web/src/components/Pagination.tsx` 保留文件路径但实现 `LoadMoreControl`：sentinel 触发与“加载更多”共用防重入 `loadMore()`；提供加载、失败重试和全部加载完文字，不再渲染上一页/下一页。
- [X] T020 在 `web/src/components/CashTable.tsx` 与 `web/src/styles.css` 保留桌面八列、caption、表头和金额合同；收敛主列表技术术语，移动卡片用真实 DOM 显示分类与导入渠道。
- [X] T021 在 `web/src/components/StatusView.tsx`、`web/src/pages/CashLedgerPage.tsx` 与 `web/src/styles.css` 移除主列表投影技术说明，保留错误合同；追加失败使用局部文字状态和重试入口。
- [X] T022 运行 T013～T015 受影响测试，确认旧显式分页行为已由批准的连续加载合同替换，且不新增 API 请求。

## Phase 3：用户故事 2、响应式与视觉收敛

- [X] T023 在 `EvidenceDetail.tsx`、页面容器与样式中保留并验证关闭自动获焦、`Escape`、Tab 焦点圈定、背景 inert、关闭回焦、1024 px 并列和窄屏全屏详情；只调整 A 原型的视觉层级。
- [X] T024 在 `styles.css` 完成 A 审计工作台令牌、默认折叠筛选、列表末端状态、真实移动字段、`overflow-x: clip`、focus-visible、44 px 触控目标和减少动效；不得以 `display:none` 隐藏表头。
- [X] T025 运行所有组件、无障碍与 E2E 回归；确认 320、375、414、768、1024、1440 px 无横向溢出。
- [X] T026 人工批准去标识化视觉快照并纳入当前 worktree，记录视口、场景、核对日期及展示层差异理由；未创建 Git 提交。
- [X] T027 运行 `npm test`、`npm run build`、`npm run test:e2e`、`npm run test:preview`、视觉快照；在 `quickstart.md` 记录实际证据与未运行项。
- [X] T028.1 使用 `$speckit-converge` 对照 `spec.md`、`plan.md`、`tasks.md` 和实现；如追加任务，完成后重跑收敛。已核对 16 项功能需求、8 项成功标准、连续加载状态机、视觉矩阵、证据详情焦点合同及 Constitution；发现的展示层缺口已拆为 T030～T034 并完成，复核后无待追加任务。
- [X] T028.2 使用 `$hallmark audit` 审计当前 021 页面与样式；将 actionable finding 回写 tasks 并最小修复、复验。发现并完成 T029：侧栏链接色值已改用命名令牌。
- [X] T028.3 使用 gstack `review` 完成代码评审；修复阻断性 finding 并重审。以 `refactor/web` 为基准审查了连续加载、防迟到响应、无障碍、移动字段语义与 CSS 令牌；发现并完成 T030～T034。复审未发现未解决的阻断问题。
- [X] T028.4 使用 gstack `qa` 完成浏览器 QA；在提交 `3822ecd` 的干净 worktree 覆盖默认/展开筛选、桌面/390 px 布局、详情 `Escape` 关闭、筛选与控制台。初次发现 QA 预览 API 缺少证据端点，已通过 T035 补齐并验证成功详情；复跑后无未解决问题。- [X] T028.5 运行 `git diff --check`、禁止路径审计及必要前端测试，在 `quickstart.md` 记录最终状态。

## 顺序

T012 是 artifact 对齐前置，已完成；T013～T016 先失败后，按 T017～T021 最小实现；随后完成 T022～T027，并依次执行 T028.1～T028.5。实现不得改动 `web/src/api/`、`src/ft/`、迁移、依赖或 020/022 artifacts。

## Phase 4：Convergence

- [X] T029 按 `$hallmark audit` 结果将 `web/src/styles.css` 中侧栏链接的内联色值提升为命名令牌，并复验令牌约束与多视口样式。
- [X] T030 按 Hallmark 审计与 gstack `review` 结果：在 `CashFilters.tsx` 生成实际筛选摘要；在 `CashLedgerPage.tsx` 固化同一 cursor 的追加防重入与重试；在 `Pagination.tsx` 补齐加载区域的无障碍状态，并降低 `styles.css` 中加载控制区的视觉权重；补齐并运行相关组件测试。
- [X] T031 复核 `cash-ledger-append-loading` 与 `cash-ledger-empty` 的视觉快照差异；由实施者依据已批准的展示层范围判断差异是否可接受。若差异仅来自本 feature 的筛选摘要、加载状态或视觉层级变更，可更新对应基线并重跑 `npx playwright test -c tests/playwright.visual.config.ts`；否则按 Flow-Back 追加修复任务。已确认差异限于筛选摘要、加载状态与视觉层级；更新 4 张受影响快照后，8 项视觉测试通过。
- [X] T032 按 FR-004、FR-016 及术语规范，将主列表常规加载和空状态中的“收支投影”改为“收支记录”；保留投影不可用等审计/错误合同的必要技术语义，更新断言并复跑组件与视觉测试。
- [X] T033 按 Hallmark 审计结论移除无实际工作区名称或状态含义的“本机账本”眼眉，避免将装饰性文字置于页面标题上方；同步更新组件、浏览器和视觉回归断言。
- [X] T034 按 `plan.md` 令牌约束，将 `styles.css` 中窄屏导航边界与证据详情阴影的内联 OKLCH 色值提升为命名令牌，保持现有视觉效果并复跑样式相关回归。
- [X] T035 按 gstack `qa` 发现的预览夹具缺口，在 `web/tests/preview-api-server.mjs` 提供去标识化证据详情响应，并在 `runtime-preview.e2e.ts` 验证成功详情；生产预览与完整前端测试通过。
