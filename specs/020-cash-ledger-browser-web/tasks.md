# Tasks：收支账本浏览 Web

**输入**：`specs/020-cash-ledger-browser-web/` 下的 `spec.md`、`plan.md`、`research.md`、
`data-model.md`、`contracts/` 和 `quickstart.md`

**测试要求**：所有可执行行为、财务规则、数据、迁移、兼容性和接口变更都必须先编写会因目标行为缺失而
失败的测试，再完成最小实现。持久化行为必须使用同一合同矩阵验证文件型 SQLite 与真实 PostgreSQL；
SQLite 自动化测试只能操作 `/Users/huangwenlong/.ft/finance-tracker.db` 的临时副本。

**组织方式**：任务按用户故事分组。每项任务中的 `FR-*` 和 `SC-*` 用于追踪规格覆盖；执行状态全部从
未完成开始，旧“原始流水列表”实现的完成记录不沿用。

## 格式：`[ID] [P?] [Story] 描述`

- **[P]**：可与同阶段其他 `[P]` 任务并行，且不会修改同一文件。
- **[Story]**：对应 `spec.md` 中的用户故事。
- 每项任务都给出准确文件路径；测试任务必须先运行并确认因目标行为缺失而失败。

## Phase 1：准备共享测试基础

**目标**：建立去标识化场景和双后端断言，后续故事复用同一业务输入。

- [X] T001 建立单成员、同笔支付、部分退款、全额退款、内部转账、非生效关系和非法退款的去标识化场景构造器，并保留确定性 ID 与 `Decimal` 金额，写入 `tests/cash_projection_assertions.py`（FR-001～FR-008、SC-001、SC-002）。
- [X] T002 [P] 建立投影、成员、证据、游标和稳定错误对象的规范化跨后端比较辅助函数，写入 `tests/contract/cash_projection_response_matrix.py`（FR-019、FR-020、SC-004～SC-006）。
- [X] T003 [P] 建立从 `FT_TEST_SQLITE_SOURCE` 复制到临时目录且校验源文件大小、mtime 与摘要不变的 pytest fixture，并建立 `FT_TEST_POSTGRES_URL`/`FT_REQUIRE_TEST_POSTGRES` 真实库 fixture，写入 `tests/conftest.py`（FR-020、SC-004）。

---

## Phase 2：阻断性领域与持久化基础

**目标**：先建立派生 schema、端口和稳定错误边界，完成后才进入用户故事。

**关键门禁**：本阶段未完成前不得实现任何用户故事。

- [X] T004 为 5 张投影派生表、唯一归属、活动/暂存约束、索引、升降级和事实表内容不变编写失败迁移测试，写入 `tests/test_cash_projection_migration.py`（FR-009、FR-011、FR-015）。
- [X] T005 在 `src/ft/adapters/relational/models.py` 和 `migrations/versions/20260729_11_cash_projections.py` 实现 T004 的 5 张表、约束与索引，确保 downgrade 只删除派生表（FR-009、FR-015）。
- [X] T006 [P] 为投影输入/输出、经济类型、组成方式、成员角色、构建结果及稳定投影错误编写失败类型合同测试，写入 `tests/test_cash_projection_types.py`（FR-003、FR-008、FR-013、FR-019）。
- [X] T007 在 `src/ft/domain/cash_projection.py` 和 `src/ft/domain/errors.py` 实现 T006 的不可变类型、受控枚举与脱敏领域错误（FR-003、FR-008、FR-013、FR-019）。
- [X] T008 [P] 为投影状态锁、来源图读取、数据集写入/发布、连通组替换和只读查询端口编写失败接口测试，写入 `tests/test_cash_projection_ports.py`（FR-009～FR-012、FR-020）。
- [X] T009 在 `src/ft/repositories/protocols.py` 和 `src/ft/adapters/relational/projections.py` 建立 T008 的共享仓储端口与关系型适配器骨架，不在适配器复制领域规则（FR-009～FR-012、FR-020）。

**Checkpoint**：迁移可安全升降级，领域类型、错误与持久化端口足以承载后续故事。

---

## Phase 3：用户故事 1 - 浏览关系处理后的收支（Priority：P1）MVP

**目标**：所有有效现金流水先形成收支投影；账本只展示可见的消费和收入，不混合原始流水。

**独立测试**：用固定场景构建投影，验证单成员、同笔支付、部分/全额退款、内部转账和非生效关系的
成员归属、金额、主记录字段、经济类型、时间和显示状态。

### 用户故事 1 的测试

- [X] T010 [P] [US1] 为单成员自投影、正负/零金额分类、仅 `accepted` 关系生效及完整唯一归属编写失败领域测试，写入 `tests/test_cash_projection.py`（FR-001～FR-003、FR-008、SC-001）。
- [X] T011 [P] [US1] 为同笔支付先归并且只取主记录金额、字段整体取主记录、稳定 `projection_id` 与确定性输出编写失败领域测试，写入 `tests/test_cash_projection.py`（FR-003、FR-004、FR-007、SC-002）。
- [X] T012 [P] [US1] 为普通转账、信用还款及受控 subtype 生成隐藏内部转账投影编写失败领域测试，写入 `tests/test_cash_projection.py`（FR-004、FR-008、SC-002）。
- [X] T013 [P] [US1] 为部分退款保留消费时间并精确冲销、全额退款隐藏、多笔退款顺序、消费与退款两侧均有同笔支付镜像时退款边归一，以及退款不形成收入编写失败领域测试，写入 `tests/test_cash_projection.py`（FR-003、FR-005、FR-019、SC-002、SC-006）。
- [X] T014 [P] [US1] 为退款方向错误、跨币种、超额退款、缺端点、多根、方向环和不兼容关系失败关闭编写失败领域测试，写入 `tests/test_cash_projection.py`（FR-006）。

### 用户故事 1 的实现

- [X] T015 [US1] 在 `src/ft/domain/cash_projection.py` 实现纯领域构建器，固定执行同笔支付归并、内部转账分类、退款冲销，并使 T010～T014 通过（FR-001～FR-008、FR-019、SC-001、SC-002、SC-006）。
- [X] T016 [US1] 为投影条目、成员和已采用关系的批量替换、唯一归属约束及相同输入幂等写入编写失败 SQLite 仓储测试，写入 `tests/test_relational_cash_projections.py`（FR-009、FR-012）。
- [X] T017 [US1] 在 `src/ft/adapters/relational/projections.py` 实现 T016 的数据集持久化、规范排序与完整性校验（FR-009、FR-012）。
- [X] T018 [US1] 为首次全量构建、空工作区、幂等重建、活动数据集发布和 `projection.unavailable` 编写失败应用测试，写入 `tests/test_application_cash_projections.py`（FR-011～FR-014）。
- [X] T019 [US1] 在 `src/ft/application/cash_projections.py` 实现首次/全量构建编排、规则版本、来源摘要、构建计数和无活动数据集错误，使 T018 通过（FR-011～FR-014）。
- [X] T020 [P] [US1] 为 `ft projections rebuild` 与 `ft projections status` 的成功、空状态、幂等、脱敏输出和稳定失败码编写失败 CLI 测试，写入 `tests/test_cash_projection_cli.py`（FR-012～FR-014）。
- [X] T021 [US1] 在 `src/ft/cli.py` 和 `src/ft/runtime.py` 接入 T020 的显式维护命令，禁止命令自动迁移、回退或输出财务明细（FR-012～FR-014、FR-020）。
- [X] T022 [P] [US1] 为可见投影列表的日期、账户、对方、分类、币种、金额、经济类型、组成方式筛选和稳定排序编写失败应用查询测试，写入 `tests/test_application_web_queries.py`（FR-008、FR-016、FR-017、FR-019）。
- [X] T023 [US1] 在 `src/ft/application/web_queries.py` 和 `src/ft/adapters/relational/web_queries.py` 实现活动数据集一次性版本读取、可见投影筛选与稳定分页查询（FR-008、FR-016、FR-017、FR-019）。
- [X] T024 [US1] 为 `GET /api/v1/accounts?view=cash`、`GET /api/v1/cash-projections`、十进制字符串、时区和删除旧原始流水路由编写失败 API 合同测试，写入 `tests/contract/test_web_api.py`（FR-016、FR-019、FR-021）。
- [X] T025 [US1] 在 `src/ft/web/routes.py`、`src/ft/web/serialization.py` 和 `src/ft/web/app.py` 实现 T024 的投影 API 与稳定错误对象，并移除 `/cash-transactions` 与 `/evidence/cash/{id}`（FR-013、FR-016、FR-019、FR-021）。
- [X] T026 [P] [US1] 为“收支账本”、投影表格、经济类型分段、组成方式筛选、隐藏内部转账/全额退款及不请求旧端点编写失败前端测试，写入 `web/tests/CashLedgerPage.test.tsx`（FR-008、FR-022、SC-002）。
- [X] T027 [US1] 在 `web/src/api/cashLedger.ts`、`web/src/api/types.ts`、`web/src/pages/CashLedgerPage.tsx`、`web/src/components/CashFilters.tsx` 和 `web/src/components/CashTable.tsx` 实现 T026 的投影列表，保持浅色、高密度、静默专业型界面（FR-016、FR-022）。

**Checkpoint**：用户故事 1 可单独演示；列表只包含关系处理后的可见消费与收入投影。

---

## Phase 4：用户故事 2 - 核对投影的完整证据（Priority：P1）

**目标**：从每个投影回查主记录、全部成员、生效关系、未生效提示和退款时间线。

**独立测试**：打开单成员及“同笔支付 + 退款”投影的证据，核对列表字段来源、全部成员和关系依据。

### 用户故事 2 的测试

- [X] T028 [P] [US2] 为证据聚合、成员与关系确定性顺序、退款时间线、未生效关系提示和批量读取无 N+1 编写失败应用测试，写入 `tests/test_application_cash_projection_evidence.py`（FR-002、FR-007、FR-018、FR-019）。
- [X] T029 [P] [US2] 为来源行白名单脱敏、证据缺失说明、隐藏投影可按 ID 读取和跨类型同 ID 隔离编写失败仓储测试，写入 `tests/test_relational_cash_projection_evidence.py`（FR-018）。
- [X] T030 [P] [US2] 为 `GET /api/v1/evidence/cash-projections/{projection_id}` 的成功、未找到、不可用和证据不完整响应编写失败合同测试，写入 `tests/contract/test_web_api.py`（FR-013、FR-018、FR-019）。

### 用户故事 2 的实现

- [X] T031 [US2] 在 `src/ft/adapters/relational/web_queries.py` 和 `src/ft/application/web_queries.py` 实现 T028～T029 的投影证据批量查询、端点类型隔离与白名单脱敏（FR-002、FR-007、FR-018、FR-019）。
- [X] T032 [US2] 在 `src/ft/web/routes.py` 和 `src/ft/web/serialization.py` 实现 T030 的投影证据 API 与稳定错误合同（FR-013、FR-018、FR-019）。
- [X] T033 [P] [US2] 为投影结果、主记录、全部成员、生效关系、未生效提示、退款时间线和证据缺失文案编写失败组件测试，写入 `web/tests/CashLedgerPage.test.tsx` 和 `web/tests/CashTable.test.tsx`（FR-018、FR-023）。
- [X] T034 [US2] 在 `web/src/components/EvidenceDetail.tsx`、`web/src/pages/CashLedgerPage.tsx` 和 `web/src/format.ts` 实现 T033 的证据详情，列表字段始终整体采用主记录，退款时间只在详情展示（FR-007、FR-018、FR-019、FR-023）。

**Checkpoint**：用户故事 2 可独立验证每个投影的事实、关系和计算依据。

---

## Phase 5：用户故事 3 - 在源数据变更后获得原子、一致的投影（Priority：P2）

**目标**：现金流水和关系变更只重建受影响连通组；失败时源数据与投影一并回滚，全量失败保留旧版本。

**独立测试**：分别执行事实新增/删除、关系确认/替换、转账、增量失败和全量失败，比较事务前后源事实、
活动数据集、未受影响投影和版本。

### 用户故事 3 的测试

- [X] T035 [P] [US3] 为手工记账、余额校准、账单导入、逻辑删除、关系合并/拆分和关系状态变化计算变更前后完整连通组编写失败应用测试，并用边界测试证明当前投资现金录入与同步服务不写 `cash_transactions`，写入 `tests/test_application_cash_projections.py`（FR-010）。
- [X] T036 [P] [US3] 为增量只替换受影响投影、未受影响代理行不重写、版本单调增加及相同输入幂等编写失败事务测试，写入 `tests/test_application_cash_projections.py`（FR-010、FR-012）。
- [X] T037 [P] [US3] 为未初始化时阻断源写、投影校验失败回滚事实与投影、稳定脱敏错误编写失败事务测试，写入 `tests/test_application_cash_projections.py`（FR-006、FR-010、FR-013、SC-007）。
- [X] T038 [P] [US3] 为关系候选在接受前阻断非法退款/非法图、`RelationService.check()` 不暴露 `str(exc)` 且不开启无意义二次 Unit of Work 编写失败测试，写入 `tests/test_transaction_relations_projection.py`（FR-006、FR-010、FR-014）。
- [X] T039 [P] [US3] 为 `ft transfer` 同事务创建两条现金流水、已确认 `transfer_pair` 和隐藏内部转账投影编写失败测试，写入 `tests/test_transfer_phase_c.py`（FR-004、FR-010）。
- [X] T040 [P] [US3] 为全量构建暂存隔离、发布前来源摘要复核、成功原子切换、失败保留旧活动版本和退休数据集有界清理编写失败测试，写入 `tests/test_application_cash_projections.py`（FR-011、FR-012、SC-007）。
- [X] T041 [P] [US3] 为业务事务回滚后独立短事务记录失败状态、迟到诊断不覆盖新健康状态及诊断事务再次失败编写失败测试，写入 `tests/test_application_cash_projections.py`（FR-014、SC-007）。
- [X] T042 [P] [US3] 为 PostgreSQL 首次状态缺失时先锁 `workspaces` 行、状态锁顺序、并发来源变化和 SQLite `BEGIN IMMEDIATE` 忙碌行为编写失败集成测试，写入 `tests/integration/test_cash_projection_concurrency.py`（FR-010、FR-011、FR-020）。

### 用户故事 3 的实现

- [X] T043 [US3] 在 `src/ft/application/cash_projections.py` 和 `src/ft/adapters/relational/projections.py` 实现 T035～T037 的连通组增量替换、版本维护、未初始化阻断和事务回滚（FR-010、FR-012、FR-013、SC-007）。
- [X] T044 [US3] 在 `src/ft/application/relations.py` 接入关系接受前投影验证并修正脱敏错误边界，使 T038 通过（FR-006、FR-010、FR-014）。
- [X] T045 [US3] 在 `src/ft/application/cashflow.py`、`src/ft/application/statement_import.py`、`src/ft/application/relations.py` 和 `src/ft/adapters/statement_import.py` 将全部实际写入 `cash_transactions` 的路径及 `ft transfer` 接入同一投影维护器，并保留投资现金录入与同步服务不写该事实表的边界，使 T035、T039 及写路径回归通过（FR-004、FR-010）。
- [X] T046 [US3] 在 `src/ft/application/cash_projections.py` 和 `src/ft/adapters/relational/projections.py` 实现 T040～T042 的暂存发布、来源摘要复核、锁顺序、有界清理和独立失败诊断事务（FR-011、FR-014、FR-020、SC-007）。

**Checkpoint**：用户故事 3 可证明日常更新与全量重建都不会发布过期、残缺或半提交的账本。

---

## Phase 6：用户故事 4 - 在两个正式后端稳定浏览（Priority：P2）

**目标**：显式选择 SQLite 或 PostgreSQL 后，投影构建、CLI、API、分页、证据和错误合同等价。

**独立测试**：把同一去标识化数据装载到 SQLite 临时副本和本机 PostgreSQL 17.10，运行同一合同矩阵并
比较规范化结果。

### 用户故事 4 的测试

- [X] T047 [P] [US4] 为 SQLite 临时副本上的迁移、首次/重复重建、增量、失败回滚、列表、证据和源文件不变编写失败集成矩阵，写入 `tests/integration/test_cash_projection_sqlite.py`（FR-009～FR-020、SC-004、SC-007）。
- [X] T048 [P] [US4] 为真实 PostgreSQL 上的同一迁移、构建、增量、失败回滚、列表和证据场景编写失败集成矩阵，写入 `tests/integration/test_cash_projection_postgres.py`（FR-009～FR-020、SC-004、SC-007）。
- [X] T049 [US4] 在 `tests/contract/test_cash_projection_parity.py` 使用 T002 的共享断言比较两个后端的投影 ID、成员、净额、类型、隐藏原因、版本、CLI 和稳定错误码（FR-020、SC-004）。
- [X] T050 [P] [US4] 为全部筛选、连续 3 页无重漏、筛选绑定游标、旧版本游标 `409 projection.updated`、金额字符串和上海时区编写双后端失败 Web 合同，写入 `tests/contract/test_web_api.py`（FR-016、FR-017、FR-019、SC-005、SC-006）。
- [X] T051 [P] [US4] 为 schema/工作区/投影不可用、SQLite 忙碌/只读、连接失败及参数错误的稳定脱敏 JSON 编写双后端失败合同，写入 `tests/contract/test_web_api.py`（FR-013、FR-020）。
- [X] T052 [P] [US4] 为 `FT_DATABASE_URL` 显式后端选择、无自动回退/双写/隐式迁移、SQLite 只读动态快照和本机来源限制编写失败运行时测试，写入 `tests/test_relational_runtime.py` 和 `tests/test_cli.py`（FR-020、FR-021）。

### 用户故事 4 的实现

- [X] T053 [US4] 在 `src/ft/adapters/relational/projections.py`、`src/ft/adapters/relational/web_queries.py` 和 `src/ft/application/web_queries.py` 修正 T047～T051 揭示的方言差异，保持共享领域与 Application Service 语义（FR-020、SC-004～SC-006）。
- [X] T054 [US4] 在 `src/ft/adapters/relational/runtime.py`、`src/ft/runtime.py` 和 `src/ft/web/app.py` 实现 T051～T052 的只读运行时、资源释放和稳定存储错误映射，禁止回退、双写及隐式迁移（FR-013、FR-020、FR-021）。

**Checkpoint**：用户故事 4 的共享合同在 SQLite 临时副本和真实 PostgreSQL 上结果等价。

---

## Phase 7：用户故事 5 - 以键盘完成收支账本浏览（Priority：P3）

**目标**：用户可用键盘完成筛选、分页和证据核对，并在宽窄视口理解所有关键状态。

**独立测试**：在 `1440 × 900` 和 `390 × 844` 视口仅发送真实键盘事件，覆盖导航、筛选、连续翻页、
打开/关闭证据，以及加载、空数据、更新、不可用、失败和证据不完整状态。

### 用户故事 5 的测试

- [X] T055 [P] [US5] 为列头和骨架稳定加载、无数据、投影更新后保留筛选并刷新第一页、投影不可用且不请求旧端点、失败重试和证据不完整编写失败组件测试，写入 `web/tests/CashLedgerPage.test.tsx`（FR-023）。
- [X] T056 [P] [US5] 为快速切换筛选/分页/证据时旧响应不得覆盖当前状态、关闭详情后迟到响应失效，以及投影版本更新时关闭旧证据、保留筛选刷新并移动焦点编写失败组件测试，写入 `web/tests/runtime.test.tsx`（FR-016～FR-018、FR-023、FR-024）。
- [X] T057 [P] [US5] 为筛选、分页、投影行和模态证据的可访问名称、可见焦点、焦点限制与关闭后返回编写失败测试，写入 `web/tests/accessibility.test.tsx`（FR-024、SC-008）。
- [X] T058 [P] [US5] 用真实键盘事件和明确视口编写宽屏打开详情后主表重排且不被遮盖、窄屏可展开筛选/全屏详情、44 × 44 px 触摸目标、无重叠和 reduced motion 的失败 E2E，写入 `web/tests/cash-ledger.e2e.ts`（FR-024、SC-008、SC-009）。
- [X] T059 [P] [US5] 为独立 Node 生产预览的构建时 API 来源、固定 `127.0.0.1:5173` 严格端口及自包含 API 替身编写失败测试，写入 `web/tests/runtime-preview.e2e.ts` 和 `web/tests/preview-api-server.mjs`（FR-021）。
- [X] T060 [P] [US5] 在至少 100 条投影数据下，用浏览器自动化计时筛选、连续翻页和证据查看流程，编写会在总时长超过 2 分钟时失败的验收测试，写入 `web/tests/cash-ledger.e2e.ts`（SC-003）。

### 用户故事 5 的实现

- [X] T061 [US5] 在 `web/src/pages/CashLedgerPage.tsx`、`web/src/components/StatusView.tsx`、`web/src/components/Pagination.tsx` 和 `web/src/api/cashLedger.ts` 实现 T055～T056 的文字状态、版本更新时关闭旧证据并保留筛选刷新、焦点恢复、请求取消与响应序号保护（FR-023、FR-024）。
- [X] T062 [US5] 在 `web/src/components/CashFilters.tsx`、`web/src/components/CashTable.tsx`、`web/src/components/EvidenceDetail.tsx` 和 `web/src/styles.css` 实现 T057～T058、T060 的键盘、焦点、宽窄屏、无重叠和高密度操作流（FR-022～FR-024、SC-003、SC-008、SC-009）。
- [X] T063 [US5] 在 `web/package.json`、`web/vite.config.ts` 和 `web/playwright.preview.config.ts` 实现 T059 的独立 Node 前端运行合同（FR-021）。

**Checkpoint**：五个用户故事均可独立验收，页面不需要鼠标或原始流水回退路径。

---

## Phase 8：收敛、评审与本地验收

**目标**：完成跨故事回归、双后端证据、真实账本备份验证和 Web QA。

- [X] T064 [P] 按 `DOMAIN_GLOSSARY.md` 和中文文档规范同步 `README.md`、`docs/README.md`、`specs/020-cash-ledger-browser-web/contracts/` 与 `specs/020-cash-ledger-browser-web/quickstart.md`，删除“消费账本”和旧原始流水合同并运行 `git diff --check`（FR-012～FR-023）。
- [X] T065 运行 `$speckit-converge` 对照 `specs/020-cash-ledger-browser-web/spec.md`、`plan.md`、`tasks.md` 和实现；如发现缺口，先回写正确 artifact，再补失败测试与实现。
- [X] T066 使用 `FT_TEST_SQLITE_SOURCE=/Users/huangwenlong/.ft/finance-tracker.db` 运行 `tests/integration/test_cash_projection_sqlite.py`、`tests/integration/test_web_sqlite.py` 和共享合同，确认只修改临时副本并在 `specs/020-cash-ledger-browser-web/quickstart.md` 记录命令与结果（FR-020、SC-004）。
- [X] T067 使用本机 `postgresql+psycopg:///finance_tracker_test` 和 `FT_REQUIRE_TEST_POSTGRES=1` 运行 `tests/integration/test_cash_projection_postgres.py`、`tests/integration/test_web_postgres.py` 和共享合同，在 `specs/020-cash-ledger-browser-web/quickstart.md` 记录真实 PostgreSQL 结果（FR-020、SC-004）。
- [X] T068 按 `plan.md` 的已批准例外，运行 `uv run pytest tests/ -q -k 'not test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets'`、`uv build`、`uv run alembic heads`、`npm test`、`npm run test:e2e`、`npm run test:preview` 和 `npm run build`；在 `specs/020-cash-ledger-browser-web/quickstart.md` 记录排除范围、风险、结果和恢复路径（SC-001～SC-009）。
- [X] T069 运行隔离环境的直接 `codex exec` 只读评审以完成 gstack 代码评审门禁，修复所有阻断性 finding；涉及需求或方案缺口时先更新 `specs/020-cash-ledger-browser-web/spec.md` 或 `plan.md`，再补测试并重新评审。标准 gstack 包装器依赖 Claude 配置和不可用的交互工具，按用户明确指示未运行 Claude（T079～T089）。
- [X] T070 在 T103 历史关系修复完成后，若 `/Users/huangwenlong/.ft/finance-tracker.db.pre-020-projection.bak` 不存在则建立该初始备份；若已存在则保留不覆盖，并另建带时间戳的重建前备份。随后显式升级 schema、对 `default` 工作区运行 `ft projections rebuild/status`，核对成员数与脱敏输出，并把证据写入 `specs/020-cash-ledger-browser-web/quickstart.md`（FR-011～FR-015、FR-032）。
  - 已解除的阻断：历史已确认关系存在混用退款冲销与内部转账的连通组，以及具有两个主记录的同笔支付归并组；按 T101～T103 的审计修复后重试重建。
- [X] T071 启动绑定本机的 Python API 与独立 Node 前端，使用真实 `default` 工作区运行 gstack `qa`，覆盖主流程、空态、错误态、纯键盘以及 `1440 × 900`/`390 × 844`，将结果回写 `specs/020-cash-ledger-browser-web/quickstart.md`（FR-021～FR-024、SC-008、SC-009）。
- [X] T072 检查最终 `git diff`、未跟踪文件、`specs/020-cash-ledger-browser-web/tasks.md` 勾选状态和真实数据库备份；确认未提交、未推送、未创建 PR，并保留可访问的本地服务地址。

---

## Phase 9：收敛

- [X] T073 将宽屏证据详情改为主表与详情并列重排，并以浏览器测试验证详情不会遮挡主表（plan: 已确认界面设计，contradicts）。
- [X] T074 在前端提供交易对方、最低金额和最高金额筛选控件，并验证完整筛选条件传递到投影 API（FR-016，missing）。
- [X] T075 在投影更新后将焦点先置于可确认的更新提示，再由该提示进入刷新的首个证据入口，并覆盖键盘路径（plan: 已确认界面设计，partial）。

## Phase 10：评审回流

- [X] T076 为 SQLite/PostgreSQL 证据成员和退款时间线金额字符串增加等价合同，统一为无指数的规范十进制格式，再实现后端无关序列化（FR-019、FR-020，partial）。
- [X] T077 为重建与列表并发读取增加合同，在活动状态、页面和关系摘要原本可分离的 SQL 间从另一连接提交增量维护，证明状态、数据集、游标校验、页面、关系摘要和下一页游标由同一条查询快照产生，再实现活动状态 CTE 单语句查询（FR-017、FR-020，contradicts）。
- [X] T078 为待配对退款的自动接受增加方向合同，证明自动与人工确认均以原消费为主记录、退款为对侧流水，再统一持久化路径（FR-005～FR-007，contradicts）。
- [X] T079 [P] 在 `tests/test_cash_projection.py` 和双后端投影契约中先覆盖同号内部转账、金额或币种不一致的同笔支付、内部转账与退款混用；再在 `src/ft/domain/cash_projection.py` 实现关系种类不变量和稳定 `projection.invalid_relation`（FR-006、FR-025、SC-010）。
- [X] T080 [P] 在 `tests/integration/test_cash_projection_concurrency.py` 先覆盖全量构建期间修改 `counterparty`、`category`、`note` 或 `source_type` 必须拒绝发布；再在 `src/ft/adapters/relational/projections.py` 让来源摘要覆盖所有投影与证据输入字段（FR-007、FR-011、FR-026、SC-010）。
- [X] T081 [P] 在 `tests/integration/test_web_postgres.py` 和 `tests/integration/test_web_sqlite.py` 先覆盖证据读取期间的并发重建只能返回单一版本；再在 `src/ft/adapters/relational/web_queries.py` 以单条查询或显式快照事务读取证据（FR-018、FR-020、FR-027、SC-010）。
- [X] T082 [P] 在 `tests/test_cash_projection_cli.py` 先覆盖不存在 SQLite 目标的 `ft projections status` 不创建文件或旁路文件、投影错误不输出 traceback；再在 `src/ft/cli.py` 和只读引擎装配处实现严格只读状态路径与稳定 CLI 失败输出（FR-012、FR-028）。
- [X] T083 [P] 在 `tests/contract/test_web_api.py`、`tests/integration/test_web_sqlite.py`、`tests/integration/test_web_postgres.py` 和 `web/src/pages/CashLedgerPage.test.tsx` 先覆盖运行期存储错误、`invalid_filter`、`invalid_cursor` 与通用请求失败；再在 `src/ft/adapters/relational/web_queries.py`、`src/ft/web/` 和 `web/src/pages/CashLedgerPage.tsx` 实现稳定映射与可修正文案（FR-023、FR-029、SC-010）。
- [X] T084 运行 T079～T083 的 SQLite 与本机 PostgreSQL 契约矩阵、受影响前端测试和完整验收命令；将结果回写 `specs/020-cash-ledger-browser-web/quickstart.md`，再重新执行直接 Codex CLI 评审（FR-025～FR-029、SC-010）。
- [X] T085 [P] 在 `tests/test_cash_projection.py` 和双后端投影契约中先覆盖同币种金额绝对值不一致、同号端点、`currency_exchange` 同币种和有效跨币种换汇的 `transfer_pair`；再在 `src/ft/domain/cash_projection.py` 于隐藏内部转账前验证全部端点不变量并返回 `projection.invalid_relation`（FR-025、SC-010，Codex P1）。
- [X] T086 [P] 在 `tests/test_cash_projection_cli.py` 先覆盖 PostgreSQL `ft projections status` 的写入被数据库拒绝，以及 SQLite 快照引擎或清理异常不泄露 traceback；再在 `src/ft/adapters/relational/dialect.py`、`src/ft/cli.py` 和运行时错误映射处实现数据库级只读与稳定脱敏失败（FR-028、FR-029，Codex P2）。
- [X] T087 运行 T085～T086 的 SQLite 临时副本和本机 PostgreSQL 契约矩阵、完整验收命令，并更新 `specs/020-cash-ledger-browser-web/quickstart.md`；之后以临时 `HOME` 隔离全局 skill 扫描，重新执行受限只读直接 Codex CLI 评审（FR-025、FR-028、FR-029、SC-010）。
  - [X] 验证：T085～T086 双后端目标集、SQLite 临时副本、本机 PostgreSQL、前端测试、构建和获批范围内的完整验收均已通过；详见 `quickstart.md` 的 2026-07-29 记录。
  - [X] 直接 Codex CLI 评审：临时 `HOME`、只读沙箱且禁止读取 `.agents` 和 `.claude` 的复审已通过；首轮 P2/P3 已由 T089 修复，复审无可操作问题。
- [X] T088 [P] 先在 `tests/test_cash_projection.py` 和 `tests/contract/test_cash_projection_parity.py` 覆盖普通转账、信用还款、换汇和银证转账等 `transfer_pair` 在异币种且金额不等时仍可构建隐藏内部转账投影，并保留同币种金额不等和同号端点失败；再在 `src/ft/domain/cash_projection.py` 使全部内部转账仅在同币种时强制等额，保持 `currency_exchange` 必须异币种（FR-025、SC-004、SC-010）。
- [X] T089 [P] 在 `tests/test_cash_projection.py` 先覆盖 `transfer_pair` 任一端为零时失败，并在 `tests/contract/test_cash_projection_parity.py` 覆盖至少一个零金额端点的双后端失败；同时将四种 subtype 的异币种不等额场景断言为相同的隐藏内部转账投影，包括经济类型、隐藏原因、子类型、成员和净额，而非只断言重建可用（FR-025、SC-004、SC-010，Codex P2/P3）。
- [X] T090 [P] 先在 `tests/test_cash_projection.py` 和 `tests/contract/test_cash_projection_parity.py` 覆盖两端均为零且同币种的 `refund_offset` 生成隐藏全额退款投影、保留成员和关系，以及单侧零金额或零金额跨币种退款失败；再在 `src/ft/domain/cash_projection.py` 实现该受限例外，并验证 SQLite 与 PostgreSQL 等价（FR-005、FR-006、SC-002、SC-004）。
- [X] T091 运行 T090 的完整 SQLite 临时副本与本机 PostgreSQL 验收矩阵和完整 Python 回归；随后以临时 `HOME` 在只读沙箱运行直接 Codex CLI 复审零金额退款规则。真实 SQLite 若在备份后发生外部变化，保留既有备份不覆盖，并另建当前状态的重建前备份后才可重试 `ft projections rebuild`（FR-005、FR-006、SC-002、SC-004）。
  - [X] 验证：目标双后端矩阵 `38 passed`；获批性能排除下的完整 Python 回归 `1108 passed, 9 skipped, 2 deselected, 1 warning`；隔离 Codex CLI 复审为 CLEAR，无可操作问题。
  - [X] 真实 SQLite：保留旧备份，另建 `finance-tracker.db.pre-020-projection-rebuild-20260729-2214.bak` 后重建；状态稳定返回 `uninitialized` 和 `projection.invalid_relation`。该失败由 T070 继续跟踪，不自动修改账本记录或关系。
- [X] T092 [P] 在 `tests/test_transaction_relations_projection.py` 先覆盖退款冲销经同笔支付连接到候选内部转账时，自动扫描把候选保留为 `pending_review` 并写入 `relation.kind_conflict`，同时原始账单导入可提交；在 `tests/contract/test_cash_projection_parity.py` 覆盖人工确认和 SQLite/PostgreSQL 投影构建仍拒绝该冲突图（FR-030、SC-010）。
- [X] T093 在 `src/ft/application/relations.py` 实现自动确认的完整已确认关系连通组互斥检查，复用完整投影校验并将 `refund_offset` + `transfer_pair` 冲突候选降为 `pending_review`；不得把 `payment_mirror` 本身视为冲突（FR-030）。
- [X] T094 运行 T092～T093 的 SQLite 临时副本与本机 PostgreSQL 契约矩阵、受影响关系测试和完整 Python 回归；更新 `specs/020-cash-ledger-browser-web/quickstart.md`，再由独立子代理只读复审（FR-030、SC-010）。
- [X] T095 [P] 在 `tests/test_transaction_relations_payment_mirror.py` 先覆盖两笔对两笔、字段完全一致的同笔支付候选按 `occurred_at ASC, id ASC` 一对一自动确认；覆盖两侧数量不等保留 `pending_review`，以及已有同一渠道对配对后的重扫不产生交叉关系（FR-031）。
- [X] T096 在 `src/ft/domain/relations/mirror/match.py` 和 `src/ft/application/relations.py` 实现按渠道对和完整匹配字段分组的确定性同笔支付配对，并使已确认配对占用两端；不改变候选识别范围、不新增表或关系审查界面（FR-031）。
- [X] T097 运行 T095～T096 的 SQLite 临时副本与本机 PostgreSQL 契约矩阵、受影响关系测试和完整 Python 回归；更新 `specs/020-cash-ledger-browser-web/quickstart.md`，再由独立子代理只读复审（FR-031、SC-004、SC-010）。
- [X] T098 [P] 在 `tests/test_transaction_relations_projection.py` 先覆盖带有 `relation.kind_conflict` 的系统双边待审核候选重扫后仍保持 `pending_review` 和原原因；在 `tests/test_transaction_relations_payment_mirror.py` 先覆盖一个端点同时匹配多个渠道对时，规范化渠道对顺序只确认一条关系且不会产生共享端点的交叉关系；两类场景均覆盖 SQLite 与本机 PostgreSQL（FR-030、FR-031、SC-010）。
- [X] T099 在 `src/ft/application/relations.py` 的既有系统候选升级路径重新执行完整连通组冲突检查；在 `src/ft/domain/relations/mirror/match.py` 以稳定渠道对顺序即时占用端点，确保所有渠道对间一对一；不得新增候选识别范围、表或关系审查界面（FR-030、FR-031）。
- [X] T100 运行 T098～T099 的 SQLite 临时副本、本机 PostgreSQL 契约矩阵、受影响关系测试和获批范围内的完整 Python 回归；记录既有财富冷构建性能豁免，更新 `quickstart.md`，再由独立子代理只读复审（FR-030、FR-031、SC-004、SC-010）。
- [X] T101 [P] 在真实 SQLite 的只读连接中核对授权的 7 条关系及 8 条账单的工作区、状态、种类、端点、时间和金额；在临时 SQLite 副本与本机 PostgreSQL 去标识化 fixture 中先验证带全部前置条件的 3 条 `accepted→rejected` 更新可原子完成，任一前置条件不符时零行更新且事务回滚。对目标与保留关系逐字段断言 `created_*`、`decided_by`、`decided_at`、端点和 `evidence_json` 不变（FR-032、SC-010）。
- [X] T102 在临时 SQLite 副本上执行精确的单事务修复，验证只将 `1541`、`2643`、`2834` 标为 `rejected`，保留原始账单、关系证据、创建和既有决定字段及 `1054`、`3085`、`1339`、`3055`；演练修复后验收失败时对精确主文件、同名 `-wal` 和 `-shm` 的恢复路径，确认恢复后摘要、关系和账单快照一致；运行投影重建并记录状态、成员数和关系依据（FR-011～FR-015、FR-032）。
- [X] T103 在停止本机写入进程并以 `sqlite3 .backup` 创建、校验完整性与摘要的时间戳备份后，对 `/Users/huangwenlong/.ft/finance-tracker.db` 执行 T101 已验证的单事务历史关系修复；前置条件失败时不提交。完成后只读复核 7 条关系和 8 条账单未被删除且审计字段不变，将修复原因与备份摘要写入 `quickstart.md`；若提交后验收失败，按 plan 的精确恢复步骤恢复备份并复核（FR-032）。
- [X] T104 运行 T070、T071 和 T072：重建真实 `default` 工作区投影，启动独立 Node 前端与本机 API 执行 gstack `qa`，再检查最终 diff、任务状态、备份、未提交/未推送状态和本地服务地址；不得把历史修复扩展为删除账单或自动重扫关系（FR-011～FR-024、FR-032、SC-008～SC-010）。

---

## Phase 11：收支账本列表列调整

**目标**：让账本列表直接呈现主记录备注，同时将组成方式从列表移回筛选和证据详情，减少重复的关系信息。

- [X] T105 [US5] 先在 `web/tests/CashLedgerPage.test.tsx`、`web/tests/accessibility.test.tsx` 和 `web/tests/CashTable.test.tsx` 编写或更新失败断言：宽屏表头中的“备注”紧跟“交易对方”，“组成方式”不作为列表列或行内容出现，备注值及缺失占位可读；组成方式筛选和证据详情中的已采用关系保持可用。不得保留要求列表关系摘要的过期断言（FR-016、FR-018、FR-022、FR-024、SC-008、SC-009）。
- [X] T106 [US5] 在 `web/src/components/CashTable.tsx` 和 `web/src/styles.css` 实现 T105：移除列表关系摘要与“组成方式”列，插入“备注”列并紧跟交易对方；同步骨架列数和窄屏投影卡片布局，确保备注可换行且不遮挡金额、操作或后续字段（FR-022、FR-024、SC-009）。
- [X] T107 [US5] 运行完整 Vitest 测试与 `npm run build`，再用独立 Node 前端执行 gstack `qa`，覆盖 `1440 × 900` 与 `390 × 844` 的备注、组成方式筛选、证据详情和键盘入口；将命令和结果写入 `specs/020-cash-ledger-browser-web/quickstart.md`。因用户禁止 Claude 与 Codex CLI 而无法执行 gstack `qa` 时，必须记录原因和等价的独立 Node 浏览器验证证据（FR-016、FR-018、FR-022、FR-024、SC-008、SC-009）。
- [X] T108 [US5] 由独立子代理对 T105～T107 的 diff 和验收证据进行只读复审；已发现窄屏 E2E 的过期关系摘要断言、移动表头语义缺失和组成方式请求覆盖不足，按 T109～T112 回写并修复（FR-016、FR-018、FR-022、FR-024、SC-008、SC-009）。
- [X] T109 [US5] 先在 `web/tests/cash-ledger.e2e.ts`、`web/tests/CashLedgerPage.test.tsx` 和 `web/tests/accessibility.test.tsx` 编写失败断言：窄屏展示备注、不展示列表关系摘要且无横向溢出；选择组成方式后请求包含 `composition=combined`；视觉隐藏的表头仍以原生列头语义关联各字段。运行受影响测试确认因当前实现缺失而失败（FR-016、FR-022、FR-024、SC-008、SC-009）。
- [X] T110 [US5] 在 `web/src/components/CashTable.tsx` 和 `web/src/styles.css` 实现 T109：为各列表列提供原生列头作用域，窄屏仅视觉隐藏表头并保留语义；删除遗留的关系摘要样式。同步 E2E 夹具和断言，使备注、组成方式筛选与证据详情分别在正确位置验证（FR-016、FR-018、FR-022、FR-024、SC-008、SC-009）。
- [X] T111 [US5] 运行完整 Vitest、`npm run build` 和隔离端口 Playwright E2E/生产预览；验证 `1440 × 900` 与 `390 × 844` 下的备注、无列表关系摘要、组成方式请求、证据详情关系、键盘焦点、原生表头语义和无横向溢出。将实际命令、结果和 gstack `qa` 限制记录到 `quickstart.md`（FR-016、FR-018、FR-022、FR-024、SC-008、SC-009）。
- [X] T112 [US5] 由独立子代理只读复审 T109～T111 的 diff 和验收证据；复审为 CLEAR，确认窄屏备注与证据关系、原生列头语义、组成方式请求覆盖及隔离测试边界均符合要求（FR-016、FR-018、FR-022、FR-024、SC-008、SC-009）。

---

## Phase 15：收支投影性能门禁

- [X] T129 [P] 在 `tests/test_cash_projection_performance.py` 先建立固定、去标识化的 10,000 条有效现金流水工作负载；场景必须合法覆盖单成员、同笔支付关系（`payment_mirror`）、退款冲销关系（`refund_offset`）和转账配对关系（`transfer_pair`）。复用 `tests/test_wealth_performance.py` 的 SQLite/真实 PostgreSQL 双后端、3 次预热、20 个样本、p95 计算和环境输出模式，但正式计时仅覆盖 `CashProjectionService.rebuild()`；SQLite 与 PostgreSQL 的 p95 均不超过 10 秒。PostgreSQL 环境变量缺失时跳过该后端并明确报告，不得伪称通过（SC-004、SC-011）。
- [X] T130 先运行 T129 的 SQLite 参数实例，确认测试实际执行；仅在首次真实 p95 超过 10 秒时向主 session 报告并按 Flow-Back 决定是否修改生产代码。随后在具备 `FT_TEST_POSTGRES_URL` 时运行真实 PostgreSQL 参数实例；执行相称的静态检查，将实际命令、输出、未执行项和风险写入 `quickstart.md`，最后勾选本阶段任务（SC-011）。
  - SQLite 与静态检查已验证：SQLite 参数实例 `1 passed in 103.20s`，`CashProjectionService.rebuild()` 的 p95 为 `6.546 s`，符合 10 秒门禁；`compileall` 与 `git diff --check` 通过。失败基线：真实 PostgreSQL 参数实例 p95 为 `11.584 s`，超过 10 秒门禁。按已批准 Flow-Back，先在 `tests/test_relational_cash_projections.py` 增加批量写入、父投影 ID 受限回查映射、投影成员/投影关系依据的角色与顺序，以及同一数据集重写幂等的失败测试；再且仅在 `src/ft/adapters/relational/projections.py` 使用共享 SQLAlchemy Core 批量 DML helper 替换逐投影 `add()` 加 `flush()`。helper 必须分块插入父投影，按 `(workspace_id, dataset_id, projection_id)` 受限回查代理 ID，严格校验输入与回查的投影标识集合和基数全等，不完整时抛出 `RuntimeError('projection.incomplete')`，随后分块插入成员和关系依据；不得依赖 `RETURNING` 返回顺序、方言分支、原始 DBAPI 或 COPY。

---

## Phase 16：收支投影批量写入 Flow-Back

**目标**：修复固定 10,000 条收支投影重建在真实 PostgreSQL 上 p95 为 `11.584 s`、超过 10 秒门禁的问题；不改变投影业务语义、暂存与事务、删除顺序、来源摘要、发布、SQLite/PostgreSQL 锁、成员角色或顺序。

- [X] T131 [P] 在 `tests/test_relational_cash_projections.py` 先补失败测试，证明数据集替换使用批量写入，父投影 ID 只能按 `(workspace_id, dataset_id, projection_id)` 受限回查映射，投影成员和投影关系依据保留 `role` 与 `ordinal`，且同一数据集重写保持幂等；确认旧逐条 `add()` 加 `flush()` 实现失败（FR-009、FR-012、FR-020、SC-004、SC-011）。
- [X] T132 在 `src/ft/adapters/relational/projections.py` 仅以共享 SQLAlchemy Core 批量 DML helper 实现 T131：使用 `session.execute(insert(Model), mappings)` 分块插入父投影、成员和关系依据；父投影插入后按受限键回查代理 ID，并对输入与回查的投影标识集合和基数做全等校验，异常时抛出 `RuntimeError('projection.incomplete')`。同一 helper 必须供 `replace_dataset` 和 `replace_active_components` 使用；不得使用 `RETURNING` 顺序、方言分支、原始 DBAPI 或 COPY（FR-009、FR-010、FR-012、FR-020、SC-004、SC-011）。
- [X] T133 先运行定向 SQLite 测试；再以 `FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' FT_REQUIRE_TEST_POSTGRES=1` 运行关系型投影测试、Application 投影测试、双后端等价测试和 10,000 条性能测试。若 PostgreSQL p95 仍超过 10 秒，停止且不得放宽门禁；仅在全部通过后勾选 T131～T133，并在 `quickstart.md` 记录实际命令、性能、静态检查、未执行项与风险（SC-004、SC-011）。

---

## Phase 17：父投影回查 bind 参数 Flow-Back

**目标**：避免父投影代理 ID 回查把超过 65,535 个 `projection_id` 放入同一 PostgreSQL `IN` 查询，从而超过数据库的 bind 参数上限；不改变空集路径、Core 批量插入、事务、父代理 ID 映射和既有完整性校验。

- [X] T134 [P] 在 `tests/test_relational_cash_projections.py` 先为远小于数据库上限、但超过可替换共享批次大小常量的投影标识集合写入失败测试；断言回查分为多条受 `(workspace_id, dataset_id)` 限制的 `SELECT`、查询结果合并完整、父代理 ID 映射覆盖全部投影成员。旧的单次回查实现必须失败（FR-009、FR-012、FR-020、SC-004、SC-011）。
- [X] T135 仅在 `src/ft/adapters/relational/projections.py` 定义共享批次大小常量 `PROJECTION_WRITE_BATCH_SIZE = 2000`，让 Core 批量插入与按 `projection_id` 的父投影回查共用它；逐批执行相同 workspace/dataset 受限 `SELECT`，合并后保留输入与回查投影标识集合和基数全等校验。不得改变空集返回、Core 批量插入、事务、父代理 ID 映射、成员角色或顺序（FR-009、FR-010、FR-012、FR-020、SC-004、SC-011）。
- [X] T136 运行 SQLite 关系型投影测试、真实 PostgreSQL 关系型/应用/等价矩阵和 10,000 条性能测试；确认 PostgreSQL p95 不超过 10 秒，并运行 `compileall` 与 `git diff --check`。在 `quickstart.md` 记录实际命令、输出、未执行项与风险（SC-004、SC-011）。

---

### Phase 依赖

- **Phase 1**：立即开始，提供所有故事复用的规范场景与双后端 fixture。
- **Phase 2**：依赖 Phase 1；阻断全部用户故事。
- **用户故事 1（Phase 3）**：依赖 Phase 2；是可浏览投影的 MVP。
- **用户故事 2（Phase 4）**：依赖用户故事 1 的投影身份和只读查询，但证据验收可独立执行。
- **用户故事 3（Phase 5）**：依赖用户故事 1 的构建器与持久化；完成后投影可安全持续维护。
- **用户故事 4（Phase 6）**：依赖用户故事 1～3 的完整后端行为，用同一矩阵证明 SQLite/PostgreSQL 等价。
- **用户故事 5（Phase 7）**：依赖用户故事 1～2 的前端合同，可与用户故事 3 的后端维护任务交错实施。
- **Phase 8**：依赖全部目标用户故事完成。
- **Phase 11**：依赖 Phase 7 的前端表格、筛选和证据详情；仅调整列表呈现，不改变投影 API 或持久化行为。

### 每个用户故事内部顺序

1. 先完成该故事的测试任务，并运行确认因目标行为缺失而失败。
2. 再按领域模型、Application Service、持久化、API、前端的依赖顺序完成最小实现。
3. 每个实现任务完成后立即运行对应测试并勾选任务，不集中到最后一次性更新状态。
4. 财务规则、迁移、事务或接口若没有对应的失败证据，不得视为完成。

### 可并行机会

- T002 与 T003 可在 T001 确定场景格式后并行。
- T006 与 T008 可在 T004 运行失败后并行准备；T005、T007、T009 分别跟随自己的测试。
- 用户故事 1 的 T010～T014 可共同先写，但 T015 开始前必须逐项看到预期失败。
- 用户故事 2 的应用、仓储、API 和前端测试可并行准备。
- 用户故事 3 的各类事务测试可并行准备，T043～T046 按共享文件冲突顺序实施。
- 用户故事 4 的 SQLite、PostgreSQL、Web 错误和运行时测试可并行准备，真实 PostgreSQL 不得由 mock 代替。
- 用户故事 5 的状态、竞态、可访问性和预览测试可并行准备，随后按组件所有权合并实现。

## 实施策略

### MVP 优先

1. 完成 Phase 1 和 Phase 2。
2. 完成用户故事 1，得到只展示可见消费/收入投影的“收支账本”。
3. 单独验证固定财务场景、列表 API 和前端不读取旧端点。
4. 在此基础上补齐证据、原子维护、双后端和完整交互，不能把 MVP 当作最终交付。

### 增量交付

1. 领域构建与首次发布证明财务语义正确。
2. 证据详情证明每个结果可追溯。
3. 增量维护和失败回滚证明持久化投影不会与事实源失步。
4. 双后端合同证明 PostgreSQL 与 SQLite 用户可见行为等价。
5. 键盘与响应式验收完成可用性，最后统一评审、QA 和真实账本验证。

## 说明

- 投影是可删除、可重建的派生读模型；现金流水和已确认关系仍是唯一事实源。
- 仅 `accepted` 关系参与投影；其他状态只可作为证据详情中的未生效提示。
- 本 feature 不识别新的换汇或银证转账关系，不增加关系审查 UI，不提供原始流水回退开关。
- PostgreSQL 使用本机安装实例；不得使用容器，也不得升级 gstack。
- 本轮只完成本地实现与验证，不提交、不推送、不创建 PR。

---

## Phase 12：合并 021 审计工作台规范

- [X] T113 按用户明确授权，将 021 的默认折叠筛选、连续加载、主列表术语、移动端字段、焦点、视觉令牌和快照合同映射到本 spec 的 FR-033～FR-040、`plan.md`、`research.md`、`data-model.md` 与 `contracts/web-ui-compatibility.md`；不改变 022 的范围。
- [X] T114 将 021 实现与验证提交 `3822ecd`、`7471a8d` 记录为 020 的展示层交付证据，并以现有前端组件、E2E、生产预览与视觉快照矩阵确认不新增 API、后端、持久化或依赖。
- [X] T115 删除已被本目录完整吸收的 `specs/021-modern-web-ui-design/`，将 `.specify/feature.json` 切回 020；运行 `$speckit-analyze`、`$speckit-converge`、review、QA 和最终回归后记录结果。最终验证已逐项确认：FR-033 默认折叠与范围摘要、FR-034 连续加载/同 cursor 防重入/失败重试、FR-035 取消与迟到响应、FR-036 八列与业务术语、FR-037 移动真实字段和表头语义、FR-038 命名令牌、FR-039 详情焦点和响应式、FR-040 多视口快照。021 删除由用户明确授权；020 成为唯一活跃规格，022 未改动。

---

## Phase 13：发布前审查 Flow-Back 修复

- [X] T116 [P] 先为未初始化工作区的事实源写入、首次显式重建发布和已有活动投影维护失败回滚补充失败测试，覆盖 FR-010、FR-011、FR-013、SC-007 与双后端等价性。
- [X] T117 [P] 先为 Base64 解码后 JSON 顶层为数组、字符串、数字、布尔值或 `null` 的 cursor 补应用和 Web 合同失败测试，断言稳定 `invalid_cursor` 与 HTTP 400，覆盖 FR-017、FR-029。
- [X] T118 [P] 先为投影成员与投影关系依据表的 `dataset_id` 索引补 SQLite 和真实 PostgreSQL 升级、降级、再升级测试，断言事实源不变，覆盖 FR-009、FR-015、FR-020。
- [X] T119 [P] 为账户目录失败后的重试恢复、证据详情 `Escape`、Tab 与 Shift+Tab 焦点圈定补前端回归测试，覆盖 FR-023、FR-024、FR-039。
- [X] T120 修改 `src/ft/application/cash_projections.py`，在 `uninitialized` 时跳过派生维护并允许合法事实源提交；保持 `ready` 时维护失败回滚和读取端投影不可用合同。
- [X] T121 修改 `src/ft/application/web_queries.py`，在 cursor 访问字段前验证 JSON 顶层对象并统一映射为 `invalid_cursor`。
- [X] T122 在 ORM 和新的 Alembic revision 中添加两个派生子表的 `dataset_id` 索引；升级与降级只改变索引，不改写事实源或投影业务数据。
- [X] T123 运行定向、SQLite、真实 PostgreSQL、前端与完整回归；执行 `$speckit-analyze`、`$speckit-converge`、gstack review、gstack QA、`git diff --check`，将实际证据回写 `quickstart.md`。

---

## Phase 14：发布前复审 Flow-Back 修复

- [X] T124 [P] 为未初始化事实源写入与首次显式重建的共享锁域补 PostgreSQL 回归，证明跳过维护前锁定工作区与投影状态，覆盖 FR-010、FR-011、SC-004、SC-010。
- [X] T125 [P] 为完整 cursor 对象中的非法字段类型补应用和 Web 合同失败测试，覆盖 `v: true`、非字符串 `projection_id`、非法 `version`、`workspace`、`filters`、`occurred_at` 及无时区时间，断言 `invalid_cursor` 与 HTTP 400，覆盖 FR-017、FR-029。
- [X] T126 修改 `src/ft/adapters/relational/projections.py` 与 `src/ft/application/cash_projections.py`，在决定未初始化跳过维护前复用工作区—投影状态锁；保持 ready 增量维护、显式首构建和 SQLite 事务语义。
- [X] T127 修改 `src/ft/application/web_queries.py`，严格验证 cursor 合同字段类型，禁止布尔整数和 `projection_id` 宽松字符串转换。
- [X] T128 运行双后端回归、完整验证、收敛、review、QA 和最终差异检查；仅记录实际证据后创建目标为 `refactor/web` 的 PR。
