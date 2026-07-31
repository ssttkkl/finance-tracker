# Tasks：现代化收支账本界面

**Input**：`specs/021-modern-web-ui-design/` 的当前 artifacts。所有展示与交互变更遵循测试先行；021 不修改 API、后端、持久化或依赖。

## Phase 1：Living Spec 与测试门禁

- [X] T001：020 冻结基线已完成，021 从 `49449210a5ec1705085249b46f23d6fe66b1aab5` 的独立 worktree 开始。
- [X] T002：已使用 `$domain-glossary` 审校 `DOMAIN_GLOSSARY.md` 与 021 artifacts；主列表统一使用“收支记录”“分类”“导入渠道”，证据详情使用“投影结果”“主记录”“投影成员”“来源行快照”等审计术语。
- [X] T003：已使用 `$chinese-documentation` 审校 021 artifacts 的新增中文内容，并通过 `git diff --check`。
- [X] T004：已复核 021 只改造既有收支账本展示层，明确排除月度摘要、净现金流、跨币种计算、投资账本、API、数据模型与持久化。
- [X] T005：已复核允许路径仅限 `web/src/pages/`、`web/src/components/`、`web/src/styles.css`、可选 `web/tokens.css`、`web/tests/` 和 021 artifacts；禁止修改 `web/src/api/`、`src/ft/`、`migrations/`、依赖清单、020/022 artifacts。
- [X] T006：已确认 021 使用独立 feature worktree，实施分支从 020 冻结基线创建。
- [ ] T007 [P] 在 `web/tests/CashLedgerPage.test.tsx` 先补失败测试：默认折叠、首批替换/追加、同一 `cursor` 防重、追加失败重试、末批终止、筛选或版本更新时取消追加与重置。
- [ ] T008 [P] 在 `web/tests/CashTable.test.tsx`、`web/tests/accessibility.test.tsx` 先补失败测试：桌面八列顺序、主列表无技术说明、移动端真实“分类/导入渠道”文本、加载更多的可访问状态，以及证据详情既有焦点与 `inert` 回归。
- [ ] T009 [P] 在 `web/tests/cash-ledger.e2e.ts` 先补失败测试：自动追加三批、键盘“加载更多”回退、同一 `cursor` 一次请求、追加失败保留条目且可重试、向上回看已加载条目、四视口无横向溢出。
- [ ] T010 在 `web/tests/cash-ledger.visual.e2e.ts` 先补快照场景：默认/展开筛选、追加中、追加失败、全部加载完、390 px 分类/导入渠道、宽屏并列与窄屏全屏详情。

- [X] T011：已在 `spec.md`、`plan.md`、`research.md`、`data-model.md`、`contracts/web-ui-compatibility.md`、本文件和 `quickstart.md` 回写已批准 A 审计工作台：默认折叠筛选、主列表用户术语收敛、移动端分类/导入渠道、自动连续加载与“加载更多”回退；已运行 `$speckit-analyze`，无 CRITICAL/HIGH 问题。

## Phase 2：用户故事 1——筛选与连续浏览

- [ ] T012 在 `web/src/pages/CashLedgerPage.tsx` 保留既有 `AbortController`、请求代次、筛选和版本更新逻辑；将单页与 `cursor` 栈改为累计收支记录、下一个 `cursor`、首批加载、追加加载和追加错误状态。筛选/版本更新时中止、清空并从首批读取；追加成功按 `projection_id` 去重，失败不清空收支记录。
- [ ] T013 在 `web/src/components/CashFilters.tsx` 与 `web/src/styles.css` 默认折叠原生 `<details>`，保留所有字段、即时筛选与键盘语义；呈现已批准范围摘要。
- [ ] T014 在 `web/src/components/Pagination.tsx` 保留文件路径但实现 `LoadMoreControl`：`sentinel` 触发与“加载更多”共用防重入的 `loadMore()`；提供加载、失败重试和全部加载完文字，不再渲染上一页/下一页。
- [ ] T015 在 `web/src/components/CashTable.tsx` 与 `web/src/styles.css` 保留桌面八列、`caption`、表头和金额合同；收敛主列表技术术语，移动卡片用真实 DOM 显示分类与导入渠道。
- [ ] T016 在 `web/src/components/StatusView.tsx`、`web/src/pages/CashLedgerPage.tsx` 与 `web/src/styles.css` 移除主列表投影技术说明，保留错误合同；追加失败使用局部文字状态和重试入口。
- [ ] T017 运行 T007～T009 受影响测试，确认旧显式分页行为已由批准的连续加载合同替换，且不新增 API 端点、请求参数或读取范围。

## Phase 3：用户故事 2、响应式与视觉收敛

- [ ] T018 在 `EvidenceDetail.tsx`、页面容器与样式中保留并验证关闭自动获焦、`Escape`、Tab 焦点圈定、背景 `inert`、关闭回焦、1024 px 并列和窄屏全屏详情；只调整 A 原型的视觉层级。
- [ ] T019 在 `styles.css` 完成 A 审计工作台令牌、默认折叠筛选、列表末端状态、真实移动字段、`overflow-x: clip`、`:focus-visible`、44 px 触控目标和减少动效；明确验证输入、`select`、按钮的默认、hover、`:focus-visible`、active、disabled、loading、error、success 8 种状态；不得以 `display: none` 隐藏表头。
- [ ] T020 运行所有组件、无障碍与 E2E 回归；确认 320、375、414、768、1024、1440 px 无横向溢出。
- [ ] T021 人工审批去标识化视觉快照，记录视口、场景、核对日期及展示层差异理由；将快照纳入待提交变更集，不得在未获提交授权时创建 Git 提交。
- [ ] T022 运行 `npm test`、`npm run build`、`npm run test:e2e`、`npm run test:preview`、视觉快照；在 `quickstart.md` 记录实际证据与未运行项。
- [ ] T023 使用 `$speckit-converge`、gstack `/review` 和 gstack `/qa` 收敛；审计禁止路径、未跟踪快照与最终 diff。

## 顺序

T011 是实施前置。T007～T010 先失败后，按 T012～T016 最小实现；随后执行 T017～T023。实现不得改动 `web/src/api/`、`src/ft/`、迁移、依赖或 020/022 artifacts。
