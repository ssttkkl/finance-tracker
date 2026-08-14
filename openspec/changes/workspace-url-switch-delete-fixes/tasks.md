## 1. 思考与范围

- [x] 1.1 进入 `/grilling` session，明确规范 URL 采用固定工作区 ID、切换立即重载、含微信流水的工作区可删除，以及无权深链接失败关闭。
- [x] 1.2 阅读 `openspec/project-context.md`、主规格、工作区相关历史变更、`DOMAIN_GLOSSARY.md`、UI 约束、认证服务、路由壳、删除模型和现有测试，确认根因与非目标。

## 2. 计划与原型

- [x] 2.1 完成 proposal、delta specs 和 design，记录路由合同、删除事务边界、双后端和回滚策略。
- [x] 2.2 按 UI 约束创建 `prototype/index.html`，覆盖正常、空、加载、切换失败、无权深链接、删除确认/失败/成功状态以及 320/375/414/768 px 自检。

## 3. 任务拆分与一致性

- [x] 3.1 检查 proposal、specs、design、prototype 和本任务清单的一致性，确认 `workspace-context-routing` 与 `cash-ledger-browser` 的每项 requirement 都有测试和验证任务。
- [x] 3.2 记录实现边界：只在工作区删除事务内解除受限依赖，不改变账户级删除语义；URL ID 不替代服务端成员授权。

## 4. 构建（测试先行）

- [x] 4.1 先增加后端失败回归：SQLite 与 PostgreSQL（可用时）删除包含 `source_type="wechat"` 现金流水、分类、收支投影和关系的工作区，并断言无残留、会话切换和失败回滚。已加入 SQLite 回归，先复现 503 数据库约束错误，修复后 `uv run pytest tests/test_user_workspace_access.py::test_admin_deletes_workspace_with_imported_wechat_cash_and_projection -q` 通过；PostgreSQL 待专用 `_test` 数据库可用时补跑。
- [x] 4.2 先增加前端失败回归：成功切换后 URL 与账本内容立即变更、切换失败保留旧状态、直接打开 `/w/<workspaceId>/...` 选择成员工作区、无权深链接恢复当前工作区、子页面切换保留子路由、旧路径规范化、删除失败重试和删除后的 URL 回跳。`web/tests/AccessApp.test.tsx` 已覆盖，初始实现前两项新回归失败，修复后 13/13 通过（全量 Vitest 114/114）。
- [x] 4.3 实现工作区删除依赖清理顺序，保留名称精确确认、管理员权限、单条活动会话集合更新、事务回滚和剩余工作区选择。访问服务在同一事务中按投影、关系、流水、账户受限引用、财富读模型和分类顺序集合删除。
- [x] 4.4 实现工作区 URL 路由工具、兼容路径规范化和深链接会话同步；所有统一账本导航及邀请/删除回跳使用规范前缀。新增 `web/src/routing.ts`，认证壳与统一导航均使用 `/w/<id>/...`。
- [x] 4.5 以活动工作区 ID 绑定账本壳生命周期，切换成功后重新挂载并重新读取收支、分类、导入和投资页面；切换失败不得乐观更新。`App` 使用活动工作区 ID 作为 React `key`，切换接口失败保持旧 state、URL 和页面。
- [x] 4.6 更新受影响 Vitest、Playwright 和后端测试夹具，确保固定 ID、可编辑名称、微信来源和去标识化数据不混用。单元夹具覆盖固定 ID 与 `source_type="wechat"`；分类、流水、工作区和生产预览用例的 URL 断言均已迁移到 `/w/<id>/...`。

## 5. 审查

- [x] 5.1 完成产品/范围、工程、数据安全和最终 diff 独立复核。范围覆盖三项用户诉求及旧路径兼容；确认 URL ID 不作为 API 授权依据、深链接先调用成员校验、切换失败保留旧 state/URL、删除在一个事务中按受限关系顺序集合清理并具备回滚。检查模型后未发现遗漏的 workspace 受限依赖；无 critical/major finding。仓库外 gstack review 清单不可用，已以当前 diff、模型 FK 和测试证据完成独立人工复核，未执行提交/推送。
- [x] 5.2 完成原型与最终 UI 人工复核：核心任务、错误/空/加载/成功状态、删除确认、键盘焦点和 320/375/390/414/768/1440 px 均有覆盖，未发现 critical/major finding。运行时未提供 Hallmark `audit` 动作，已记录为不可用并完成等价人工审查，未声称已执行 Hallmark。
- [x] 5.3 根据独立覆盖审查补齐高风险回归：新增无权深链接、子页面切换和删除失败后重试测试；覆盖审查列出的其余未覆盖分支（PostgreSQL、浏览器历史回退/前进及后端事务竞争）已记录为环境或后续专项验证，不改变本次修复范围。

## 6. 测试与 QA

- [x] 6.1 在 `2026-08-15`、`HEAD=921e18f0899663e6a8187e6a2e3f214aec725ab3`（基线 `git merge-base origin/main HEAD=8c18ed7ecff6b31cd5adcc18becb4e4e09035f55`）完成受影响验证：`uv run pytest tests/test_user_workspace_access.py -q` 为 19 passed、1 skipped；新增删除回归单测通过；`npm test -- --run` 为 10 files/111 tests passed；`npm run build` 通过；`git diff --check` 通过。全量 `uv run pytest -q` 为 1475 passed、177 skipped、1 failed，失败为既有 `tests/test_wealth_performance.py::test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets[sqlite]` 冷 P95 偶发超出 5 秒阈值；随后单独重跑该用例通过（128.85s），未修改性能代码。
- [ ] 6.2 `FT_TEST_POSTGRES_URL` 当前未配置，故未执行 PostgreSQL 矩阵；必须准备可连接的专用数据库且数据库名以 `_test` 结尾后，设置该变量并补跑同一工作区切换/删除契约（含 `tests/test_user_workspace_access.py` 的 PostgreSQL fixture）。这是交付前残余验证风险。
- [x] 6.3 使用真实 Playwright Chromium 完成浏览器 QA。仓库 in-app Browser 尝试时 `agent.browsers.list()` 返回空列表，故记录为不可用并使用 Playwright fallback：工作区筛选用例 5 passed（`FT_E2E_WEB_PORT=5187 npm run test:e2e -- --grep '工作区'`），流水核心 12 passed，生产预览 7 passed（`npm run test:preview`），覆盖 `/w/workspace-1/`、`/w/workspace-2/`、`/w/workspace-2/cash-categories`、旧 `/workspace-management` 规范化及 `/w/preview-workspace/cash-import`；工作区管理检查 320/375/414/768/390/1440 px，无页面横向滚动，截图保存于 `/tmp/workspace-management-delete-desktop.png`、`/tmp/workspace-management-layout-mobile.png`、`/tmp/workspace-management-delete-mobile.png`。Playwright 流程未报告导航、API 或请求失败；未单独采集 console/network 事件。完整 30 项 E2E 为 29 passed、1 failed，失败为既有暗色侧栏断言将 8 个实际导航文字节点写死为 7 个。视觉套件 15 项为 9 passed、6 failed，失败集中于既有快照差异（相同 2178/1872 像素差异），本次未修改 CSS，未更新基线。
- [x] 6.4 `openspec validate --all --strict` 通过（27 passed、0 failed），`openspec doctor` 通过；完成范围化 diff、`git diff --check` 和最终规格—实现—测试一致性复核。未发现需要 Flow-Back 的偏离；未执行提交、推送或部署。

## 7. 发布准备

- [x] 7.1 记录发布准备：前后端需同版本发布，先观察旧顶级路径规范化、深链接权限失败、切换失败和删除数据库错误；回滚时同时回滚前端/API，已成功删除的数据不可恢复。本次不执行提交、推送或部署。

## 8. 反思

- [x] 8.1 防复发规则：工作区 URL 只使用固定 ID，页面数据必须由已验证的服务端活动会话提供；切换必须将 state、URL 和壳生命周期作为一个成功动作；新增 workspace 受限表或投影依赖时必须同步加入删除清单、SQLite/PostgreSQL 契约和残留断言。本次无新的领域术语，未改词表。
