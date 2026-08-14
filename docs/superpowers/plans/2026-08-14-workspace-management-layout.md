# 工作区管理纵向板块实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将工作区管理页的功能板块统一改为从上到下排列，并让删除按钮紧跟在“删除工作区”标题下方。

**Architecture:** 保留现有 `WorkspaceManagement` 的数据请求、权限判断、成员操作、邀请和删除确认流程，只调整页面 DOM 信息架构与 CSS。四个一级板块按 `工作区信息 → 成员 → 邀请成员 → 删除工作区` 排列；“工作区信息”内部保留桌面双列、移动单列，危险区在所有视口保持标题后接按钮。

**Tech Stack:** React + TypeScript、CSS、Vitest、Playwright、OpenSpec、真实 Chromium。

## Global Constraints

- UI 文案不得暴露数据库、服务边界或实现细节；只保留完成工作区管理任务所需的标题、标签和操作。
- 桌面单项表单最大宽度规则继续适用；信息管理板块可以使用右侧可用宽度。
- 320、375、390、414、768、1440 px 均不得产生横向滚动；1440 px 与 390 px 保存截图。
- 生产页面实现前必须有已确认的原型；本次原型已由用户确认。
- 最终生产 UI 必须完成 Hallmark `audit`，并将 finding 与结论写回 OpenSpec tasks。
- 已获用户授权提交、推送、创建指向 `refactor/web` 的 PR 并合入；不得部署或修改无关文件。

---

### Task 1: 为纵向板块结构补充失败回归测试

**Files:**
- Modify: `web/tests/AccessApp.test.tsx:109-158`
- Modify: `web/tests/workspace-navigation.e2e.ts:41-86`

**Interfaces:**
- Consumes: 现有管理员工作区管理测试夹具与 `/workspace-management` 路由。
- Produces: 可验证四个板块 DOM 顺序、删除标题与按钮顺序、桌面/移动实际布局顺序的回归断言。

- [ ] **Step 1: Write the failing Vitest assertions**

在管理员工作区管理测试中增加：

```ts
const main = screen.getByRole("main");
expect([...main.querySelectorAll(":scope > section h2")].map(node => node.textContent)).toEqual([
  "工作区信息", "成员", "邀请成员", "删除工作区",
]);
const dangerSection = main.querySelector('[aria-labelledby="workspace-delete-title"]');
expect([...dangerSection!.querySelectorAll(":scope > h2, :scope > button")].map(node => node.tagName)).toEqual(["H2", "BUTTON"]);
```

- [ ] **Step 2: Run the focused test and verify it fails for the missing structure**

Run: `cd web && npm test -- --run tests/AccessApp.test.tsx`

Expected: the existing implementation fails because it has no visible “工作区信息” heading and the member/invite wrapper is not four direct vertical sections.

- [ ] **Step 3: Add the Playwright layout assertions**

在管理员桌面/移动场景中增加：

```ts
await expect(page.locator(".workspace-management-page > section h2")).toHaveText([
  "工作区信息", "成员", "邀请成员", "删除工作区",
]);
const dangerSection = page.locator('[aria-labelledby="workspace-delete-title"]');
const dangerTitle = dangerSection.getByRole("heading", { name: "删除工作区" });
const dangerButton = dangerSection.getByRole("button", { name: "删除工作区" });
expect((await dangerButton.boundingBox())!.y).toBeGreaterThan((await dangerTitle.boundingBox())!.y);
```

- [ ] **Step 4: 保留失败证据并进入实现**

记录失败测试后再进入生产代码修改；不提交一个明知失败的中间状态，测试与生产布局在同一可验证实现提交中保持范围聚焦。

### Task 2: 实现生产页面的纵向板块布局

**Files:**
- Modify: `web/src/AccessApp.tsx:117-151`
- Modify: `web/src/styles.css:68-70`

**Interfaces:**
- Consumes: Task 1 的 DOM 合同与现有工作区 API/权限状态。
- Produces: 保持现有交互合同的四个纵向 `section`，以及标题下方的危险操作按钮。

- [ ] **Step 1: 将 WorkspaceManagement 的 JSX 改为四个一级 section**

按以下顺序输出：

```tsx
<section className="workspace-management-section workspace-identity" aria-labelledby="workspace-identity-title">
  <div className="workspace-section-head"><h2>工作区信息</h2></div>
  {/* 保留现有工作区名称输入、保存按钮、固定 ID 和复制按钮 */}
</section>
<section className="workspace-management-section workspace-members" aria-labelledby="workspace-members-title">{/* 保留现有成员列表与成员操作 */}</section>
<section className="workspace-management-section workspace-invite" aria-labelledby="workspace-invite-title">{/* 保留现有邀请权限与链接结果 */}</section>
{admin && <section className="workspace-management-section workspace-danger-zone" aria-labelledby="workspace-delete-title">
  <h2>删除工作区</h2>
  <button ...>删除工作区</button>
</section>}
```

保留 `feedback`、成员操作、邀请结果和删除确认层的现有行为，不增加常驻帮助文字。

- [ ] **Step 2: 将 CSS 从左右网格改为纵向板块**

移除 `.workspace-management-grid` 的左右网格职责、`.workspace-invite` 的左侧边界和 padding；新增统一的 `.workspace-management-section` 上边界与相邻板块间距。仅 `.workspace-identity` 内部继续使用桌面双列，移动端切换单列。`.workspace-danger-zone` 使用 block 布局，按钮 `margin-top` 为统一间距，移动端不再额外改写为另一种顺序。

- [ ] **Step 3: Run the focused tests and verify green**

Run: `cd web && npm test -- --run tests/AccessApp.test.tsx`

Expected: AccessApp tests pass,包括管理员纵向结构、非管理员隐藏删除入口和既有删除确认行为。

- [ ] **Step 4: Run the focused e2e layout flow**

Run: `cd web && FT_E2E_WEB_PORT=5184 npm run test:e2e -- workspace-navigation.e2e.ts`

Expected: route navigation、1440/390 px 无横向滚动、删除确认焦点和删除后路由全部通过。

### Task 3: 同步 OpenSpec 与原型证据

**Files:**
- Modify: `openspec/changes/redesign-workspace-management/design.md`
- Modify: `openspec/changes/redesign-workspace-management/specs/workspace-management/spec.md`
- Modify: `openspec/changes/redesign-workspace-management/tasks.md`
- Modify: `openspec/changes/redesign-workspace-management/prototype/index.html`

**Interfaces:**
- Consumes: 已确认原型与最终生产页面的 DOM/CSS 结果。
- Produces: 记录最终纵向信息架构、删除按钮位置、浏览器截图、审查和验证证据。

- [ ] **Step 1: 核对 artifact 与实现的一致性**

确认 proposal/design/spec 都描述四个板块的纵向顺序，prototype 与生产页面都使用“删除标题在上、按钮在下”的危险区结构。

- [ ] **Step 2: 更新 tasks 验证证据**

记录当前 `HEAD`、比较基线、浏览器 URL/视口、截图路径、控制台/网络错误、测试命令和未运行项；生产 UI 完成后将原型阶段的待办改为完成。

- [ ] **Step 3: 运行 OpenSpec 与差异检查**

Run: `openspec validate redesign-workspace-management --strict` and `git diff --check`

Expected: change valid and no whitespace errors.

### Task 4: 完成验证、独立审查与发布

**Files:**
- Modify: `openspec/changes/redesign-workspace-management/tasks.md`

**Interfaces:**
- Consumes: Tasks 1–3 的实现与测试结果。
- Produces: 可审计的验证记录、GitHub PR 和合入结果。

- [ ] **Step 1: 运行前端与后端验证**

Run `cd web && npm test -- --run`, `cd web && VITE_FT_API_ORIGIN=http://127.0.0.1:8000 npm run build`, SQLite 全量 pytest，以及设置 `FT_TEST_POSTGRES_URL` 指向本机 `_test` 数据库的工作区契约/性能矩阵。

- [ ] **Step 2: 运行真实 Chromium QA 与 Hallmark audit**

验证正常、空、错误、只读、邀请成功、删除确认状态及 320/375/390/414/768/1440 px；保存 1440/390 截图。对 `web/src/AccessApp.tsx`、`web/src/styles.css` 和最终截图执行 Hallmark `audit`，修复 critical/major finding 后重审。

- [ ] **Step 3: 请求独立代码复核并处理 finding**

以最新 target `origin/refactor/web` 为基线，对最终 diff 做范围、工程、UI、测试和安全复核；阻断或重要 finding 必须修复并重新验证。

- [ ] **Step 4: 提交、推送并创建 PR**

确认 `git status -sb`、`git diff --check` 与受影响验证通过后，提交聚焦变更，执行 `git push -u origin $(git branch --show-current)`，创建 base 为 `refactor/web` 的 PR，PR body 写明布局变化、保留的业务行为和验证命令。

- [ ] **Step 5: 合入 PR 并记录结果**

确认 PR 检查通过后合入 `refactor/web`，记录 PR URL、合入 SHA、CI 状态、当前 HEAD、比较基线和未解决风险；不执行部署。
