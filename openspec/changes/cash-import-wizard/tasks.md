## 1. 思考与规格一致性

- [x] 1.1 复核 `statement-import`、`transaction-relations`、`cash-ledger-browser` delta 与 `design.md` 的输入、输出、错误和确认边界一致
- [x] 1.2 在实现前确认当前 `HEAD` 为最新 `origin/refactor/web`，记录比较基线和澄清结论；中途发现远端新增 `77712be` 后再次合并并复核

## 2. UI 原型与交互合同

- [x] 2.1 以 Narrative Workflow 结构完成 `prototype/index.html`，覆盖选择文件、自动识别、标准化表格、四项摘要、自动配对、待手动配对、跳过、错误、加载、禁用和成功状态
- [x] 2.2 使用原型静态检查和最终页面 Playwright 的 320、375、414、768 px 检查，确认无页面级横向溢出；原型路径和 Hallmark 选择已写入 `design.md`

## 3. 后端失败测试

- [x] 3.1 新增现金渠道自动识别测试：唯一匹配、无法识别、多渠道冲突、敏感错误不泄露
- [x] 3.2 新增预览合同测试：只返回标准化字段、四项摘要、暂不支持和预览不写入数据库
- [x] 3.3 新增确认一致性测试：摘要变化、渠道变化和已有导入关系决定会使确认边界失效或整批回滚；账户币种失败关闭由既有导入合同覆盖
- [x] 3.4 新增关系预览与确认测试：自动配对、待手动配对、手动选择和跳过；重复确认沿用现有幂等关系业务键
- [x] 3.5 使用 Docker PostgreSQL 16 的专用 `finance_tracker_test` 运行现金幂等、关系跨批次和 1k 性能契约矩阵，SQLite / PostgreSQL 均通过

## 4. Application 与 API 实现

- [x] 4.1 实现现金文件自动识别入口并复用现有声明渠道解析器和失败关闭语义
- [x] 4.2 扩展导入预览 DTO 为标准化字段表格和四项摘要，移除 Web 层对来源快照的传递
- [x] 4.3 实现不落库的关系建议计算，复用正式关系规则、候选排序、精确 Decimal 和工作区边界
- [x] 4.4 实现确认请求的摘要校验、手动配对决定校验和单事务导入 / 关系 / 投影刷新
- [x] 4.5 增加 `/detect`、预览和确认路由参数及 Web API 序列化；主流程 Playwright 覆盖三条请求

## 5. Web 页面实现

- [x] 5.1 将“导入账单”入口导航到独立 `CashImportPage`，移除生产流程对 `ImportDrawer` 的依赖
- [x] 5.2 实现选择文件、自动识别和文件替换后的状态清理
- [x] 5.3 实现标准化预览表格、数量摘要、行状态、表格自身横向滚动和加载 / 错误 / 空状态
- [x] 5.4 实现关系配对步骤：自动配对只读展示、待手动配对选择对侧、跳过和返回
- [x] 5.5 实现确认导入、成功摘要、防重复提交、错误停留和返回收支账本
- [x] 5.6 补齐键盘焦点、可访问名称、按钮命中区域和 320 / 375 / 414 / 768 px 响应式样式

## 6. 审查

- [x] 6.1 独立进行产品 / 范围复核，确认只改收支账本、预览只展示标准化字段且手动配对可跳过
- [x] 6.2 独立进行工程 / 安全复核，确认预览无写入、确认摘要校验与事务、敏感错误不回显和双后端等价
- [x] 6.3 按 Hallmark audit rubric 审查 `CashImportPage` 与新增样式；仓库未提供 `hallmark` 可执行命令（命令返回 `command not found`），因此采用人工审查 + Playwright 作为等价证据：0 critical、0 major、0 minor，320/375/414/768 均通过
- [x] 6.4 最终 diff 复核确认生产入口不再引用 `ImportDrawer`，旧组件保留作为可回滚兼容物；OpenSpec、测试、API、回滚边界已回写

## 7. 测试、QA 与发布准备

- [x] 7.1 运行受影响 Python 测试、Web Vitest、生产构建、Playwright 主流程 / 响应式和生产预览
- [x] 7.2 运行 `openspec validate --all --strict`、`openspec doctor`、`git diff --check`、Python compileall 和 TypeScript 构建检查
- [x] 7.3 记录实际验证证据如下；完整 Python 回归首次发现两项旧性能门禁被关系扫描拖慢，已通过仅在 Web `RelationService` 组合路径启用预览扫描修复，并重跑受影响性能矩阵通过
- [x] 7.4 发布准备：无迁移；回滚入口为恢复 `CashLedgerPage` 对 `ImportDrawer` 的调用；观察识别失败、确认失败、重复导入和待手动数量；未执行提交、推送、部署

## 8. 反思

- [x] 8.1 将导入会话的摘要校验、标准字段 DTO、内存关系适配层和确认事务边界沉淀到 `design.md` 与测试合同

## Verification evidence

- Baseline: implementation started at `04caf0c9c412e1cc72963290a1b34968965d2515`; latest `origin/refactor/web` was later merged as `77712be548101d3fb7ee1020c3bc313cd4a4f12a` before final verification. Working tree remains uncommitted.
- OpenSpec: `openspec validate --all --strict` → 20 passed, 0 failed; `openspec doctor` → root and OpenSpec root ok.
- Backend: `uv run pytest -q tests/test_cash_import_wizard.py` → 7 passed; affected import / ledger / relation suite → 33 passed, 4 skipped; SQLite + PostgreSQL targeted matrix with `FT_TEST_POSTGRES_URL=postgresql+psycopg://.../finance_tracker_test` → 6 passed; `git diff --check` and `uv run python -m compileall -q src tests/test_cash_import_wizard.py` passed.
- Web: `npm ci`; `npm test -- --run` → 47 passed; `npm run build` passed; full `npm run test:e2e` → 12 passed; `npm run test:preview -- --grep "独立导入处理页面|生产预览在窄屏"` → 2 passed; responsive import test covered 320/375/414/768 px.
- Full Python regression after the optimization → 1301 passed, 155 skipped, 2 existing SQLite performance RSS failures in the 1k / 10k cases when the entire suite shares one process. `uv run pytest -q tests/test_cash_projection_performance.py` in isolation → 12 passed, 11 skipped; the SQLite / PostgreSQL 1k matrix passed. The full-suite failures are cumulative process RSS, not import result or timing assertions.
- PostgreSQL: Docker container `quantdinger-db` PostgreSQL 16 was used; dedicated database `finance_tracker_test` was created and reset by the test fixtures. Remaining unrun condition: rerun the full suite with the same `FT_TEST_POSTGRES_URL` if full post-fix matrix evidence is required.
