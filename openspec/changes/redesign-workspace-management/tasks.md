# 实施任务

## 1. 思考

- [x] 明确页面用户任务、非目标、信息层级和响应式边界。
- [x] 读取现有工作区接口、认证壳、统一账本路由和测试基线。
- [x] 复核工作区 ID 不变、名称可改的业务语义。

## 2. 计划

- [x] 创建 `proposal.md`、`design.md` 和独立原型。
- [x] 用户确认原型后进入正式页面实施。
- [x] 确认后的原型路径、状态和文案预算回写设计记录。
- [x] 用户确认纵向板块设计后，更新原型的信息架构与可见板块标题。

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

  - 范围复核：页面保持为与两本账本同级的一级路由，标题固定为“工作区管理”，名称与固定 ID 分离，四个功能板块按从上到下排列；新增删除仅限当前管理员、精确名称确认和删除后切换，不改变角色语义。
  - 工程复核：发现并修复邀请接口此前在不可达代码后返回 `null` 的缺陷；补充了登录态成员只读回归，并确认工作区名称更新、成员操作、邀请和管理员删除仍沿用现有权限边界。
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

  - 基线/HEAD：`abef5eaccd38ec4c37c386ef5dce36fa41a95d1e`；本次改动保持在工作树，未提交、未推送。
  - 当时未运行项：工作区删除扩展初始实现阶段尚未配置 PostgreSQL 专用 `_test` 矩阵；后续第 10 节已使用本机 PostgreSQL 补跑。`ruff` 因环境缺少可执行文件未运行。

## 8. 反思

- [x] 记录本次“按用户任务组织页面、删除常驻说明”的可复用决策。

## 9. 工作区删除扩展

- [x] 9.1 补充管理员、非管理员、名称确认不匹配、级联数据清理和删除后会话切换的失败回归测试。
- [x] 9.2 更新 Access Application Service 与认证路由，实现名称确认、管理员授权、事务级联删除和其他会话状态清理。
- [x] 9.3 更新工作区管理 API 客户端、确认弹层、管理员可见性和删除后的账本/创建页导航。
- [x] 9.4 更新原型与最终 UI 状态，检查 320/375/390/414/768/1440 px 无溢出和确认弹层可键盘操作。
- [x] 9.5 运行受影响测试、全量回归、类型检查、构建、`git diff --check` 与 OpenSpec 校验；补跑可用 PostgreSQL `_test` 矩阵并记录未运行项。
- [x] 9.6 对最终 `/workspace-management` 运行 Hallmark `audit`，修复 critical/major finding 并回写审查结论。
- [x] 9.7 完成范围/工程/最终 diff 复核，记录当前 HEAD、比较基线、验证命令、风险和交付准备。

### 9.4–9.7 验证证据

- UI 原型：`openspec/changes/redesign-workspace-management/prototype/index.html` 已加入管理员删除区和名称确认状态；真实 Chromium QA 使用 1440 px、390 px，`document.documentElement.scrollWidth` 分别不超过视口宽度，确认输入自动获得焦点，截图为 `/tmp/workspace-management-delete-desktop.png` 和 `/tmp/workspace-management-delete-mobile.png`。
- Hallmark `audit`：审查 `web/src/AccessApp.tsx`、`web/src/App.tsx`、`web/src/styles.css` 及上述截图；0 critical、0 major、0 minor。危险操作被独立分隔，删除影响在确认层明确列出，移动端按钮可触达。
- 验证命令与结果（2026-08-14）：最终 `env -u FT_TEST_POSTGRES_URL uv run pytest -q` → 1409 passed、175 skipped、1 warning；`npm test -- --run` → 10 个测试文件、102 tests passed；`VITE_FT_API_ORIGIN=http://127.0.0.1:8000 npm run build` → 通过；`FT_E2E_WEB_PORT=5184 npm run test:e2e -- workspace-navigation.e2e.ts` → 2 passed；`openspec validate --all --strict` → 23 passed、0 failed；`openspec doctor`、`uv run alembic heads`、`git diff --check` → 通过。
- PostgreSQL：本机 `127.0.0.1:55433/finance_tracker_test` 已完成真实矩阵；工作区契约 → 18 passed、1 warning，访问性能 → SQLite/PostgreSQL 各 1 passed，删除性能 → SQLite/PostgreSQL 各 2 passed（其中索引存在性与删除 p95 各一项）。数据库只用于专用 `_test` 测试并由 fixture 在每次矩阵前重置 public schema。
- 范围/工程/最终 diff 复核：范围符合管理员专属、名称精确确认、永久级联删除和删除后切换语义；工程复核确认认证路由仍由 Application Service 做权限校验，事务使用集合更新清理活动会话指针，并通过可回滚索引迁移优化大工作区删除；最终 diff 未发现未覆盖的测试、权限或用户文案回归。当前 HEAD 与比较基线均为 `abef5eaccd38ec4c37c386ef5dce36fa41a95d1e`，工作树保持未提交，未执行提交、推送、归档或部署。

## 10. PostgreSQL 矩阵与删除性能扩展

- [x] 10.1 先增加固定规模工作区删除性能门禁，覆盖 SQLite/PostgreSQL 参数、p95 时间预算、单条会话集合更新和索引存在性；先验证当前实现因缺少索引而失败。
- [x] 10.2 为 `user_sessions.active_workspace_id` 增加模型索引与 Alembic 迁移，更新运行时 schema head 和迁移回归清单。
- [x] 10.3 运行本机 `psql` 准备名称以 `_test` 结尾的专用数据库，显式设置 `FT_TEST_POSTGRES_URL`，补跑工作区契约与性能矩阵。
- [x] 10.4 记录 SQLite/PostgreSQL 的性能样本、p95、查询形态、迁移结果和任何未解决风险，完成最终范围/工程/diff 复核。

### 10.1–10.4 验证证据

- 失败优先证据：新增门禁首次在 SQLite 上因缺少 `ix_user_sessions_active_workspace` 失败；加入模型索引和 `20260814_31_workspace_delete_performance.py` 后转绿。删除请求实际使用一条按 `active_workspace_id` 过滤的集合 `UPDATE`，另有一条仅用于恢复当前登录会话的定点更新。
- 双后端配置：本机 Homebrew PostgreSQL 16.14 通过 `psql -h 127.0.0.1 -p 55433` 连接，专用库为 `finance_tracker_test`；执行命令显式设置 `FT_TEST_POSTGRES_URL='postgresql+psycopg://huangwenlong@127.0.0.1:55433/finance_tracker_test'`，未触碰其他数据库。
- PostgreSQL 迁移复核首次发现 `20260812_27_cash_categories.py` 使用 SQLite 专属 `INSERT OR IGNORE`；已改为按方言使用 SQLite `INSERT OR IGNORE` 与 PostgreSQL `ON CONFLICT (workspace_id) DO NOTHING`，SQLite/PG 均可从空库升级到 `20260814_31`。
- 删除性能门禁：固定夹具为 1,000 个活动会话、1,000 条现金流水和 50 个账户，1 次预热、5 次采样，p95 预算 1,000 ms；本机 macOS 26.2 arm64 观测 SQLite p95 约 19.7 ms、PostgreSQL p95 约 25.5 ms，均通过索引存在性、集合更新、级联删除和替代工作区切换断言。
- 双后端契约：上述环境下 `FT_TEST_POSTGRES_URL='postgresql+psycopg://huangwenlong@127.0.0.1:55433/finance_tracker_test' uv run pytest tests/test_user_workspace_access.py -q` → 18 passed、1 warning；同一环境运行 `tests/test_user_workspace_access_performance.py -q -s` → SQLite/PostgreSQL 各 1 passed；同一环境运行 `tests/test_user_workspace_delete_performance.py -q -s -k postgresql` → 2 passed、2 deselected、1 warning。SQLite 删除性能场景也已单独通过。
- 余留风险：`ruff` 仍因当前环境没有可执行文件未运行；本地 PostgreSQL 性能结果仅作为固定硬件上的门禁证据，不替代生产容量压测。提交、推送、归档和部署仍未授权。

## 11. 工作区管理纵向板块原型

- [x] 11.1 将工作区信息、成员、邀请成员和删除工作区改为固定的纵向顺序。
- [x] 11.2 为每个板块补充可见标题、统一上边界线和一致间距；移除成员/邀请左右分栏及邀请左侧竖线；删除区按钮改为标题下方的描边操作。
- [x] 11.3 使用真实 Chromium 完成 320/375/390/414/768/1440 px 检查并保存 1440 px、390 px 原型截图。
- [x] 11.4 用户确认原型后，实施生产页面并补跑受影响测试、Hallmark audit 和最终浏览器 QA。

### 11.1–11.3 验证证据

- 原型路径：`openspec/changes/redesign-workspace-management/prototype/index.html`；板块 DOM 顺序为“工作区信息 → 成员 → 邀请成员 → 删除工作区”，每段均有可见标题、上边界线和统一间距。
- 真实 Chromium：1440/390 px 的 `document.documentElement.scrollWidth` 分别为 1440/390；320、375、414、768 px 的 scrollWidth 也分别等于视口宽度，均无横向溢出。截图为 `/tmp/workspace-management-prototype-1440.png`、`/tmp/workspace-management-prototype-390.png`，删除确认截图为 `/tmp/workspace-management-prototype-delete-confirm-390.png`。
- 设计修正：删除区在 1440/390 px 均验证为标题下方 16 px 放置描边按钮，不再采用标题右侧对齐；标题与按钮之间没有空白占位。
- 原型状态：`?state=loading` 显示加载状态并隐藏管理板块；`?state=empty` 显示空成员；`?state=error` 显示保存错误；`?mode=viewer` 禁用写操作并隐藏删除区；创建邀请显示成功结果；点击删除入口后确认层可见且输入框自动获得焦点。控制台无错误。
- 生产实现：`web/src/AccessApp.tsx` 将四个板块改为直接纵向 section，`web/src/styles.css` 仅保留工作区信息内部桌面双列，删除区标题下方紧跟描边按钮；成员、邀请、删除确认和权限逻辑未改变。
