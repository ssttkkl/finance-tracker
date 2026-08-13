## 1. 思考

- [x] 1.1 运行项目 `/grilling`：环境未提供可执行命令；用户已明确邮箱密码、角色、邀请和既有 default 工作区归属，结论记录在 proposal/design。
- [x] 1.2 阅读项目上下文、词表、工作区模型、Web API、迁移和主规格；将其判定为身份、权限、持久化和公共 API 的 A 类变更。

## 2. 计划

- [x] 2.1 完成 proposal、delta 规格和设计，确定会话、角色和邀请的失败关闭策略。
- [x] 2.2 使用 Hallmark 创建并审查认证与工作区流程 UI 原型，覆盖正常、错误、加载、邀请和 320/375/414/768 px；用户已确认登录、邀请、创建工作区和成员管理四个独立原型页面。

## 3. 任务拆分与一致性

- [x] 3.1 覆盖注册、登录、登出、创建与切换工作区、角色授权、邀请、旧数据归属、拒绝路径和双后端迁移。

## 4. 构建

- [x] 4.1 先增加失败的认证、授权、邀请与迁移回归测试。
- [x] 4.2 新增认证/成员/邀请持久化模型和 Alembic 迁移。
- [x] 4.3 实现密码、会话和工作区授权 application service 与 Web 路由。
- [x] 4.4 将账本 Web 服务按会话成员工作区动态装配，并施加角色写入授权。
- [x] 4.5 实现认证壳、登录注册、工作区切换和成员邀请界面。
- [x] 4.6 更新部署和环境配置文档。
- [x] 4.7 为认证、工作区、邀请和成员 HTTP 接口新增 SQLite/PostgreSQL 固定负载 p95 性能门禁。

## 5. 审查

- [x] 5.1 独立完成产品范围、工程和安全复核并记录 finding、采纳与结论。
  - 产品范围：确认四个流程保持独立页面，邀请角色仅在创建时指定；无阻断 finding。
  - 工程：发现 runtime schema revision 未升级、迁移清单缺新 revision，已更新为 `20260813_27`；发现邀请页角色被硬编码，已新增 invitation preview 合同并由 UI 使用。
  - 工程（性能门禁）：固定负载 HTTP 测试先暴露 `AccessService` 在关闭 ORM session 后继续读取绑定用户，以及创建工作区时工作区/成员记录尚未 flush 即更新活动工作区的 SQLite 外键错误；已改为在有效 session 内构造状态、在写入会话前 flush 工作区和成员记录，并以双后端矩阵复核。
  - 安全：发现跨 Render Service 的 API origin 仅允许 localhost、账本 fetch 未携带 Cookie，已改为精确 HTTPS origin + credentials；会话仅保存 token 摘要，Cookie 为 HttpOnly / Secure（HTTPS）/ SameSite=Lax。残余风险：第一版无邮箱验证、找回密码或 CSRF token，生产扩大使用前需评估。
- [x] 5.2 对最终 UI 运行 Hallmark audit；修复所有 critical 与 major finding 后重新审计。
  - 目标：`web/src/AccessApp.tsx`、`web/src/styles.css`。结论：0 critical、0 major、0 minor；补充统一的通用 SVG 图标，并移除了 access 页面不必要的渐变背景。预检标记与响应式、对比度检查结果保留在 CSS 顶部。

## 6. 测试与 QA

- [x] 6.1 运行认证/授权、账本、迁移和 Web 测试，完成生产构建与 `git diff --check`。
  - `uv run pytest tests/test_user_workspace_access.py tests/test_storage_configuration.py tests/test_alembic_migration.py tests/test_cli.py -q`：62 passed、2 skipped。
  - `npm test`：49 passed；`npm run build`：通过；`npm run test:preview`：3 passed（含 390 px 无横向滚动）。
  - `uv run alembic heads`：`20260813_27 (head)`；`uv build` 和 `git diff --check`：通过。
- [x] 6.2 运行 OpenSpec 严格校验、完整回归和真实 PostgreSQL 契约矩阵；缺少 `FT_TEST_POSTGRES_URL` 时记录准确补跑条件。
  - `openspec validate user-workspace-access --strict`：通过。`openspec validate --all --strict` 受仓库既有空目录 `openspec/changes/cloudflare-access-web-deployment/` 阻断，不属于本变更。
  - SQLite 覆盖已通过。未设置 `FT_TEST_POSTGRES_URL`，PostgreSQL 矩阵未完成；补跑条件：提供可连接且数据库名以 `_test` 结尾的 URL，例如 `postgresql+psycopg://…/finance_tracker_test`，再运行 `FT_REQUIRE_TEST_POSTGRES=1 uv run pytest`。
  - 性能门禁：使用固定 2 次预热、8 个有效样本的 FastAPI HTTP 矩阵，认证 p95 预算为 1.5 s，其余新增接口为 250 ms。以本机 `psql` 专用 `finance_tracker_test` 临时配置 `FT_TEST_POSTGRES_URL` 后，`uv run pytest tests/test_user_workspace_access_performance.py -q -s`：2 passed、1 warning。SQLite p95：注册 44.7 ms、登录 47.0 ms、其余接口 1.4–3.0 ms；PostgreSQL p95：注册 41.4 ms、登录 40.6 ms、其余接口 2.1–5.8 ms。随后 `uv run pytest tests/test_user_workspace_access.py tests/test_user_workspace_access_performance.py -q`：12 passed、1 warning。
  - 真实浏览器 QA：以临时 SQLite API 和生产构建前端运行独立 Chromium 双会话流程。管理员注册、创建工作区、创建「仅可查看」邀请；成员注册并接受邀请；管理员在成员页将其更新为「可编辑」后移除。成员角色实测 `viewer → editor`，移除后成员控件消失；390 px 宽度无横向滚动。浏览器未记录页面异常或 console error。测试结束后已停止临时 API/预览服务，未写入仓库数据。

## 7. 发布

- [x] 7.1 记录迁移、部署、回滚、Cookie 与首次管理员观察项。
  - 先执行 `alembic upgrade head` 至 `20260813_27`，再启动 API；Render API 设置 `FT_DATABASE_URL` 与精确 `FT_WEB_ORIGIN`，前端构建时设置 `VITE_FT_API_ORIGIN`。回滚时先停止公开 Web 入口，不能恢复匿名账本 API。观察首次 `admin@ssttkkl.fun` 注册后的 default admin 成员关系，以及 HTTPS Cookie / CORS 登录流程。

## 8. 反思

- [x] 8.1 记录成员关系和现有工作区安全归属的防复发决策。
  - 默认工作区不会因任意注册自动共享；只有指定管理员首次注册时才获得 `default` 的管理员成员关系。最后一个管理员不可移除或降级，邀请角色不可在接受端修改，回归测试覆盖这些边界。
