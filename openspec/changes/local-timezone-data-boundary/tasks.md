## 1. 思考与规格一致性

- [X] 1.1 复核 `proposal.md`、全部 delta spec、`design.md` 与当前 `openspec/specs/` 的时区合同，确认导入器是唯一保留来源时区解释的边界；未改动导入器目录。
- [X] 1.2 运行 `openspec validate --all --strict`，结果为 32 项通过（2026-08-06，当前 HEAD `204cb8a`）。

## 2. 失败测试：时间边界与 Web 合同

- [X] 2.1 为 Web API 增加浏览器 IANA 时区参数、非法时区失败、UTC 半开边界和 cursor 绑定时区的失败测试；SQLite 已执行，PostgreSQL 参数化契约因未设置 `FT_TEST_POSTGRES_URL` 跳过。
- [X] 2.2 为 Web API 增加同一 UTC 时间在不同请求时区下产生不同月份汇总、日期筛选结果一致且可解释的失败测试。
- [X] 2.3 为前端格式化和月份键增加本地时区回归测试，证明不传 `timeZone`，并覆盖 UTC 月末跨入浏览器本地下月的场景。
- [X] 2.4 为后端非导入器时间解析、手工写入、关系匹配、资金调拨和财富日桶增加无 offset/非零 offset/UTC round-trip 的失败测试。
- [X] 2.5 更新原有依赖 `Asia/Shanghai` 固定行为的测试名称、夹具和断言，先确认它们因目标语义尚未实现而失败。

## 3. 后端 UTC 与 aware 时间边界

- [X] 3.1 将 relational repository、query adapter、cashflow、investment projection 和其他手工写入路径的 naive 时间补全改为 UTC 或明确拒绝，并统一输出 ISO 8601 offset。
- [X] 3.2 将关系核心、镜像/转账/退款相关日期分桶、汇率业务日期和现金—投资资金调拨窗口改为 UTC 归一化；保留导入器目录不变。
- [X] 3.3 将 wealth application、relational runtime、wealth facts/read model 的固定上海边界改为 UTC aware 边界，保持两个后端的结果等价。
- [X] 3.4 清理非导入器对 `Asia/Shanghai`、`SHANGHAI`、`WORKSPACE_TZ` 和等价固定地区常量的依赖；保留必要的 `timezone.utc` 或输入时区转换。

## 4. Web 请求时区与本地展示

- [X] 4.1 在 Web API route、query service 和 relational Web repository 中加入有效 IANA `timezone` 的校验、UTC 边界转换、月度汇总时区和 cursor 绑定。
- [X] 4.2 在前端 API client 每次列表/续读请求中发送浏览器 IANA 时区，并保持日期输入的本地自然日语义。
- [X] 4.3 移除前端发生时间和月份 formatter 的固定 `timeZone`，让表格、证据详情和月份分割行共享浏览器本地时区。
- [X] 4.4 更新 TypeScript 类型、API fixtures、组件和端到端测试，覆盖正常、空结果、错误、加载、键盘和响应式受影响路径。

## 5. 审查与验证

- [X] 5.1 运行受影响 Python 单元/集成/契约测试及 Web Vitest、TypeScript 检查和构建；证据：当前 HEAD `204cb8a`、比较基线同为 `204cb8a`；`uv run pytest` 为 1264 passed/142 skipped，`npm test -- --run` 为 43 passed，`npm run build` 通过（2026-08-06）。
- [X] 5.2 SQLite 与真实 PostgreSQL 的同一时间契约矩阵：临时 SQLite 与本地 Docker PostgreSQL 均通过；使用 `FT_TEST_POSTGRES_URL` 指向 `finance-tracker-postgres-test`（`127.0.0.1:55432`，PostgreSQL 16.14）及 `FT_REQUIRE_TEST_POSTGRES=1 uv run pytest`，结果为 `1424 passed, 10 skipped, 1 warning`（2026-08-07）。
- [X] 5.3 运行 Web Playwright QA；`npm run test:e2e` 为 6 passed，覆盖 320/375/414/768 px、错误/空/加载、键盘、跨月和 `America/Los_Angeles` 浏览器时区请求（2026-08-06）。另以 `.ft/bills` 可解析真实账单重建临时 SQLite `/tmp/finance-tracker-real-bills.XJhqQ9/ledger.db`，导入现金记录 11394 条并重建投影 11394 条；启动真实 API `:8765` 与前端 `:5173` 后，浏览器读取 `Asia/Shanghai` 请求并通过页面、证据详情、API 200 响应及 320/375/414/768 px 无横向溢出检查（2026-08-07）。东方证券及盈立加密 PDF 因缺少密码未导入，IBKR 第三个 CSV 因缺少 `Transaction History / 总结` 区段未导入；均为导入器输入阻塞，不属于本时区变更范围。
- [X] 5.4 按 Hallmark `audit` 规则对最终 Web UI 做只读审计：时区展示、响应式、焦点/键盘路径、交互状态均无 critical/major finding；不改动视觉结构。
- [X] 5.5 最终范围复核：`rg` 未在 `src/ft` 或 `web/src` 发现固定地区时区常量；`openspec validate --all --strict` 为 32 passed，`openspec doctor` 正常，`git diff --check` 通过（2026-08-07）。

## 6. 发布准备与反思

- [X] 6.1 记录本变更不涉及 schema/数据迁移，回滚为代码回滚；未获用户授权，不提交、推送或创建 PR。
- [X] 6.2 已记录固定地区时区回归测试和导入器隔离规则；未新增领域术语，无需更新 `DOMAIN_GLOSSARY.md`，主规格 delta 保留在本 change 目录待归档。
