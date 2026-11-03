## 1. 思考

- [x] 1.1 复核 `import/cash-import-session`、`import/statement-import` 合同和服务分发。确认 Web 批量扫描、密码重试、预览、确认均须走同一 `CashImportSessionService`；另确认单一原子组成项必须跟随已确认的账户 ID/名称。
- [x] 1.2 明确数据导入决定：按 `~/.ft/mapping.yaml` 的历史命名规则映射；用户决定保留「余额宝」「余利宝」并分别建户；组件采用相同的已确认映射，原来源名称和金额保留。

## 2. 计划

- [x] 2.1 验收标准：批量扫描返回会话令牌；密码重试复用令牌；预览和确认调用相同会话服务；已确认映射应用到父流水和原子组成项，不修改金额或原始来源证据；扫描和预览不写账本。
- [x] 2.2 Cross-platform Impact Check：Web Compose 受影响，原批量扫描响应缺少会话令牌，预览也未通过临时会话服务；Native Compose 共用相同 API 和 Application Service，因此服务修复对两端生效；共享页面代码未改。没有新增页面或平台覆盖缺口。
- [x] 2.3 回写 `cash-import-session` 批量会话分发合同和 `statement-import` 原子组成项映射合同；不改解析、用户映射选择或金额语义。

## 3. 任务拆分与一致性

- [x] 3.1 增加服务解析回归，覆盖批量扫描、密码重试、预览和确认四个方法均从临时会话服务分发。
- [x] 3.2 增加原子组成项映射回归，检查目标账户、来源键、金额、来源行快照以及提交持久化结果。
- [x] 3.3 对照 delta spec、design、proposal、主规格与实现，确认所有新增保证及限制均有记录。

## 4. 构建

- [x] 4.1 先运行新增回归并确认失败根因：服务解析测试分别暴露缺少批量扫描和预览分发入口；原子组成项测试暴露映射目标名称缺失。
- [x] 4.2 将 `scan_import_batch`、`scan_import_batch_session`、`preview_import_batch_session`、`commit_import_batch_session` 加入会话服务分发集合。
- [x] 4.3 单原子组成项按解析后的目标账户 ID/名称更新关联，同时保留来源键、金额和来源行快照；多组成项的明确分摊路径不变。
- [x] 4.4 更新 `docs/feature-map.md` 的 F-05 实现证据；页面总数、双端覆盖状态和对齐 TODO 不变。

## 5. 审查

- [x] 5.1 产品/范围复核：只修复既有账单导入流程；按用户决定保留并新建余额宝、余利宝账户，没有扩大文件来源范围。
- [x] 5.2 工程复核：会话服务仍是临时数据唯一入口；未更改 TTL、确认边界、幂等键或事务合同；账户与流水只在用户明确确认时写入。
- [x] 5.3 最终 diff 复核：回归执行时 `HEAD=e0b0054531ea30a7340e8aac4e51efc3840db4e7`，比较基线为同一提交；之后的交付提交见 7.5。未发现范围外改动；`git diff --check` 通过。UI 结构/样式未变，不适用 Hallmark UI audit。

## 6. 测试与 QA

- [x] 6.1 重跑受影响测试：
  - 命令：`PYTHONPATH=tests .venv/bin/python -m pytest -q tests/test_application_web_services.py tests/test_cash_import_session_service.py tests/test_cash_import_wizard.py tests/contract/test_web_api.py`
  - 结果：2026-10-13 08:46 UTC，`89 passed, 4 skipped, 1 warning in 24.68s`。唯一警告为 Starlette 提醒 `httpx` 集成将弃用，未影响结果。
- [x] 6.2 `openspec --version` 为 `1.7.0`；`openspec validate --all --strict` 通过（26 项，0 失败）；`openspec doctor` 通过；`git diff --check` 通过。包构建尝试未能运行：环境没有 `uv` 可执行文件，虚拟环境也未安装 `build` 模块；受影响 Python 模块已由上述测试导入并执行。完整包构建需在有 `uv` 或 `build` 模块的环境补跑。
- [x] 6.3 真实浏览器 QA：Codex In-app Browser，`http://127.0.0.1:5186/w/aba98eed-8f7b-4a53-b58b-469190501fae/cash-import`。重新选择 8 份支付宝/微信文件，扫描返回 19 组来源账户，按已确认映射进入流水核对；浏览器网络日志显示扫描 `200`、预览 `200`，页面显示「查看 全部 6790」，样例行均标为「已存在」。页面视口为 `736×843`、文档宽度 `736`，无横向溢出；浏览器控制台错误为 0。截图已在本轮 CUA 检查中捕获并查看，但当前浏览器接口未提供持久化截图路径。未再次执行确认，避免对已完成导入重复写入。此前修复前预览的 `500 AttributeError: preview_import_batch_session` 已随服务分发修复消失。
- [ ] 6.4 PostgreSQL 双后端门禁未完成：本机没有配置 `FT_TEST_POSTGRES_URL`，且当前环境未检测到 `docker` 或 `psql`，无法在本轮准备专用库。需在可用环境准备数据库名以 `_test` 结尾的 PostgreSQL 测试库，再对同一 Application Service 合同补跑 SQLite/PostgreSQL 矩阵；未将缺失项计入通过。
- [x] 6.5 真实 SQLite 导入后只读审计：解析输入 6,790 行，其中 149 行是不同重叠导出文件间的重复业务行；6,641 个唯一来源标识写入 6,641 条现金流水（支付宝 3,191、微信 3,450）。无重复 `(source_type, record_id)`、无无效组成项账户引用、无来源快照缺失。共 10 个目标账户、19 条确认映射、234 条合法自动关系；6 条组合支付均按确认金额分摊，组成项金额合计与父流水一致。余额宝和余利宝分别建户。重复行的核心规范字段一致，来源快照保留首次导入版本。
- [x] 6.6 响应式与键盘 QA 适用性：本变更不修改页面、控件或布局；本轮在现有 `736×843` 浏览器宽度检查扫描与预览。当前 CUA 浏览器工具未提供视口设置接口，故未对 1440/390 px 另做视口覆盖；Tab 按键尝试在当前长预览页超时，故未完成键盘焦点遍历。两项均是剩余 Web QA 限制，不以静态检查代替。

## 7. 发布

- [x] 7.1 使用 `CashImportSessionService` 按用户确认映射导入 `~/.ft/bills` 的 8 份支付宝/微信账单，导入事务成功。输入共 6,790 行，批次去重后新增 6,641 条，跳过已存在重复 149 条；创建 10 个账户并保存 19 条账户映射、234 条已接受关系。
- [x] 7.2 已只读核对 `~/.ft/finance-tracker-compose-dev.sqlite3` 中的账户、现金流水、组成项、来源快照及关系计数；余额宝和余利宝独立存在。导入结果无法通过回滚本次会话撤销；如需撤销必须先按来源业务 ID 进行单独审查并使用受控账本操作。
- [x] 7.3 后端 PID `89815` 监听 `127.0.0.1:8000`，Compose Web PID `77693` 监听 `127.0.0.1:5186`；均在本轮检查时运行。未执行 Git 提交或推送。
- [ ] 7.4 PostgreSQL 双后端 `_test` 矩阵仍未完成；完成该门禁前保持 OpenSpec change 活跃，不归档。
- [x] 7.5 用户于 2026-10-13 明确要求提交并推送当前仓库 `refactor/kmp` 分支。提交 `46ac577`（`fix: repair batch cash import session mapping`）已推送至 `origin/refactor/kmp`。

## 8. 反思

- [x] 8.1 防复发：服务解析白名单需要同步覆盖批量扫描、密码重试、预览和确认，不可只覆盖扫描方法；增加对应回归测试。来源账户映射必须应用于原子组成项账户关联，同时保留来源快照证据及精确金额。
- 执行记录时间：2026-10-13 08:46 UTC；回归执行时 `HEAD` 与比较基线均为 `e0b0054531ea30a7340e8aac4e51efc3840db4e7`。交付提交 `46ac577` 已推送至 `origin/refactor/kmp`。
