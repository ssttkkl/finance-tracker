## 1. 思考、计划与一致性

- [x] 1.1 阅读项目上下文、收支账本主规格、词表、现有查询和前端筛选代码，完成 proposal、delta spec、设计和筛选原型。
- [x] 1.2 更新词表，定义“经济类型筛选树”及其与活动数据集、经济类型和内部转账子类型的关系。
- [x] 1.3 确认范围：不新增经济类型、数据库字段、迁移、关系匹配规则或真实账本写操作；保留旧银证筛选兼容入口。

## 2. 失败回归测试

- [x] 2.1 增加 SQLite 与本机 PostgreSQL 失败回归，覆盖活动可见投影的类型—子类型聚合、隐藏条目排除、子类型父级约束和 cursor 绑定。
- [x] 2.2 更新 HTTP 失败回归，要求列表响应序列化经济类型筛选树。
- [x] 2.3 增加前端失败回归，覆盖数据库驱动的分组选择、父级切换、子类型请求、未知标签回退、加载禁用和键盘访问。

## 3. 构建

- [x] 3.1 扩展收支投影读取 DTO、筛选规范化和路由参数，保持旧银证筛选兼容性。
- [x] 3.2 在关系型读取层从活动数据集的可见投影聚合类型—子类型树，并用绑定条件实现子类型筛选。
- [x] 3.3 更新前端传输类型、筛选状态和筛选摘要，使父级与子类型形成规范请求。
- [x] 3.4 以原生分组选择控件渲染后端树，保留未知值回退、加载禁用、焦点可见和窄屏布局。

## 4. 审查

- [x] 4.1 完成产品/范围复核，确认筛选树只反映读取数据且不改变收支、关系或投影语义。
- [x] 4.2 完成工程与安全复核，检查 SQLite/PostgreSQL 快照等价、cursor 绑定、参数校验、响应兼容性和未知值处理。
- [x] 4.3 使用 Hallmark 规则审查最终筛选组件与原型，记录可访问性、状态和响应式 finding。
- [x] 4.4 完成最终范围化 diff 复核，记录 finding、采纳结论与残余风险。

## 5. 测试与 QA

- [x] 5.1 在 SQLite 与本机 PostgreSQL 运行受影响的查询、HTTP 和投影证据契约矩阵。
- [x] 5.2 运行前端单元测试、构建、生产预览、视觉回归，并在 320、375、414 和 768 px 核验筛选控件。
- [x] 5.3 运行 `openspec validate --all --strict`、`openspec doctor`、Python 编译检查、构建和 `git diff --check`。
- [x] 5.4 在本 change 记录比较基线、`HEAD`、实际命令、结果、未运行项和残余风险。

## 6. 发布准备

- [x] 6.1 记录发布与回滚：该 change 无迁移或真实账本写入，回滚只需回退应用代码；未经明确授权不提交、推送或创建 PR。

## 7. 反思与规格同步

- [x] 7.1 记录“筛选选项来自活动投影数据集而非前端枚举”的防复发规则。
- [x] 7.2 在验证完成后将 delta spec 同步到收支账本主规格，并复核归档前一致性。

## 审查记录

### 产品与范围复核

- **范围**：`filter_options.economic_types`、`economic_type`/`transfer_subtype` 读取参数、版本化 cursor 和收支账本筛选控件。
- **结论**：筛选树只从活动数据集中的可见收支投影读取；未修改收支金额、关系匹配、投影构建、数据库 schema、迁移或真实账本。旧 `economic_type=bank_security_transfer` 保持兼容。
- **finding**：无未解决 finding。

### 工程与安全复核

- **主要 finding（已修复）**：首次成功读取后再次发起请求时，类型控件没有进入禁用状态，违反加载期间禁用的交互契约。已将“已成功读取选项”和“当前正在读取”拆分为独立状态，并新增前端失败回归；刷新时保留上一次成功的树，读屏器获得“正在读取可用经济类型”的说明。
- **数据与接口**：后端在当前工作区、活动数据集和可见投影范围内聚合；子类型进入完整筛选摘要和 cursor；错误父级组合失败关闭为 `invalid_filter`；响应字段为追加字段；未知数据库值只按原值展示，不会被前端改写。
- **安全结论**：参数经边界规范化并限制长度，查询使用绑定条件；无新权限、外部网络、来源账单或日志暴露面。

### Hallmark UI 审查

- **目标**：`web/src/components/CashFilters.tsx`、`web/src/styles.css` 与 `prototype/index.html`。
- **方法**：本地没有可执行的 `hallmark` 二进制，按 `hallmark audit` 规则人工审查组件范围的反模式、可访问性、状态和响应式。
- **结果**：`0 critical · 0 major · 0 minor`。控件采用原生 `select` 与 `optgroup`，未引入悬停专属功能或自定义弹层；加载、禁用、焦点、未知值、空类型树和错误保留状态均有可访问语义。生产控件与原型在 320、375、414、768 px 无横向溢出，键盘路径由组件与浏览器测试覆盖。

### 最终范围化 diff 复核

- **比较基线**：`4fa34f0f95d69d04de3819bd0a07298b37113c78`；当前 `HEAD`：`194f589f6abdbebc03726e006daab5e12cf956f7`。
- **范围**：仅复核本 change 的查询 DTO、关系型读取、HTTP 边界、筛选组件、前端类型与测试，以及本 change 的 OpenSpec artifacts；工作树中既有的投资事件、银证转账展示和来源行快照改动不在本 change 内，已保留且未回退。
- **结论**：无未解决的范围、兼容性或安全 finding；`git diff --check` 通过。

## 验证证据

- `FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:5432/finance_tracker_filter_hierarchy_test FT_REQUIRE_TEST_POSTGRES=1 uv run pytest -q tests/test_application_web_queries.py::test_projection_filter_options_and_subtype_filter_follow_active_visible_data tests/contract/test_web_api.py::test_projection_api_contract_and_old_routes_are_absent tests/test_relational_cash_projection_evidence.py`：`10 passed`。覆盖 SQLite 与真实 PostgreSQL 的活动可见数据聚合、子类型筛选、cursor、HTTP 序列化及投影证据。
- `cd web && npm test && npm run build`：`40 passed`，生产构建通过。
- `cd web && FT_E2E_WEB_PORT=5177 npm run test:e2e`：`3 passed`；`npm run test:visual`：`10 passed`；`FT_PREVIEW_WEB_PORT=5180 npm run test:preview`：`2 passed`。
- `openspec validate --all --strict`：规格同步前后均为 `31 passed`；`openspec doctor`、`uv run python -m compileall -q src/ft` 与 `git diff --check` 通过。
- 全量命令 `FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:5432/finance_tracker_filter_hierarchy_test FT_REQUIRE_TEST_POSTGRES=1 uv run pytest -q` 结果为 `1411 passed, 10 skipped, 2 failed`。两项失败均在未受本 change 影响的 `tests/test_wealth_performance.py::test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets`：SQLite 冷路径 p95 `5.600586750 s` 超过 `5 s` 门禁，PostgreSQL 冷路径 p95 `10.780261125 s` 超过 `6.5 s` 门禁；热路径均通过。该性能风险保留给财富读模型相关变更处理，不在本 change 放宽门禁或引入无关优化。
- 全量回归结束后已删除本次创建的专用 PostgreSQL 测试库 `finance_tracker_filter_hierarchy_test`（约 `26 MB` 生成夹具）；共享 `finance_tracker_test` 与真实 `.ft` 数据库均未操作。

## 发布与反思

- 本 change 无迁移、无真实账本写入。回滚只需回退本 change 的应用与前端代码；响应追加字段可由旧客户端忽略，旧银证筛选链接继续有效。
- 未执行提交、推送、创建 PR、归档或真实 `.ft` 数据库操作。
- 防复发规则：筛选选项必须由同一活动投影数据集聚合并随列表返回；新增经济类型或内部转账子类型时，不得向前端补写可选值枚举。

## 补充修复

- `cross_currency_remittance` 是已知内部转账子类型，筛选控件必须显示为「跨币种汇款」；已补充失败回归，未知子类型仍按原始值回退。
