# 实施任务

## 1. 思考

- [x] 明确页面用户任务、非目标、信息层级和响应式边界。
- [x] 读取现有工作区接口、认证壳、统一账本路由和测试基线。
- [x] 复核工作区 ID 不变、名称可改的业务语义。

## 2. 计划

- [x] 创建 `proposal.md`、`design.md` 和独立原型。
- [x] 用户确认原型后进入正式页面实施。
- [x] 确认后的原型路径、状态和文案预算回写设计记录。

## 3. 任务拆分与一致性

- [x] 增加工作区管理页面结构回归，覆盖一级入口、路径路由、唯一当前项和无返回链接。
- [x] 增加管理员与非管理员渲染回归，覆盖名称、固定 ID、成员和邀请操作。
- [x] 增加 1440 px、390 px 真实浏览器场景和无横向滚动断言。

## 4. 构建

- [x] 重构工作区管理 React 页面，复用现有 API，并修复真实 QA 暴露的邀请 token 返回缺陷。
- [x] 调整桌面和移动布局，确保右侧内容区独立滚动。
- [x] 更新用户可见文案并运行范围化实现术语搜索。

## 5. 审查

- [x] 进行产品/范围与工程 diff 复核。
- [x] 对最终 `/workspace-management` UI 运行 Hallmark `audit`。
- [x] 记录 finding、采纳/拒绝理由和回写位置。

  - 范围复核：页面保持为与两本账本同级的一级路由，标题固定为“工作区管理”，名称与固定 ID 分离，桌面端成员/邀请横向展开，未引入工作区删除或角色语义变化。
  - 工程复核：发现并修复邀请接口此前在不可达代码后返回 `null` 的缺陷；补充了登录态成员只读回归，并确认工作区名称更新、成员操作和邀请仍沿用现有权限边界。
  - Hallmark audit：按 `.agents/skills/hallmark/references/verbs/audit.md` 对 `web/src/AccessApp.tsx`、`web/src/App.tsx`、`web/src/styles.css` 和最终 1440/390 截图执行只读审查。页面匹配现有 `Workbench / Ledger Grid` stamp，未发现 AI 模板结构、设计系统漂移、实现术语、重复教学文案或无效卡片；0 critical、0 major、0 minor。审查结论回写于本节和 `design.md` 的 Hallmark 小节。

## 6. 测试与 QA

- [x] 运行受影响 Vitest、生产构建、`git diff --check` 和 OpenSpec 严格校验。
- [x] 使用真实浏览器验证管理员保存名称、复制 ID、成员操作和创建邀请。
- [x] 使用 1440 px 与 390 px 截图检查信息密度、滚动边界和横向溢出。

  - `uv run pytest tests/test_user_workspace_access.py -q`：11 passed，1 warning。
  - `npm test -- --run`：9 个测试文件、95 tests passed；`VITE_FT_API_ORIGIN=http://127.0.0.1:8000 npm run build`：通过。
  - `openspec validate redesign-workspace-management --strict`、`openspec doctor`、`git diff --check`：通过。
  - 全量 `openspec validate --all --strict`：23 passed、1 failed；唯一失败为既有空目录 `cloudflare-access-web-deployment`，本次变更严格校验通过，未修改该无关目录。
  - 真实 Chromium QA：管理员登录、打开 `/workspace-management`、临时修改并恢复名称、复制 `default` 固定 ID、创建邀请链接均通过；1440px 与 390px 横向溢出均为 0，当前导航仅为“工作区管理”，浏览器控制台错误为 0。截图保留在 `/tmp/workspace-management-qa-desktop-final.png` 和 `/tmp/workspace-management-qa-mobile-final.png`。
  - `ruff` 未执行：当前虚拟环境没有 `ruff` 可执行文件；未将其计入通过项。

## 7. 发布

- [x] 记录当前 HEAD、比较基线、命令、结果和未运行项。
- [x] 未获得新的提交/推送授权前保留工作树，不执行外部写入。

  - 基线/HEAD：`4ec6b38`；本次改动保持在工作树，未提交、未推送。
  - 未运行项：PostgreSQL 专用 `_test` 矩阵未补跑，原因是当前未配置 `FT_TEST_POSTGRES_URL`；本次没有新增迁移或数据库结构变更。`ruff` 因环境缺少可执行文件未运行。

## 8. 反思

- [x] 记录本次“按用户任务组织页面、删除常驻说明”的可复用决策。
