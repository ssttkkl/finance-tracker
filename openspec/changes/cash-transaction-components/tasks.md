## 1. 规格与术语

- [x] 1.1 运行 `openspec validate --all --strict`，修正 proposal、delta spec、design 和 tasks 的一致性问题。
- [x] 1.2 复核 `DOMAIN_GLOSSARY.md` 和本变更文案，统一使用“现金流水父记录”“支付组成项”“现金粒度”“账户余额分录”。
- [x] 1.3 在 `tasks.md` 记录当前 `HEAD`、比较基线、变更范围和未解决风险。

实施记录：当前 `HEAD=b48207d`，比较基线为 `origin/refactor/web=4f4633d`；工作树保留未提交的本变更文件，未执行提交、推送、部署或真实账单写入。`openspec validate cash-transaction-components --strict --no-interactive` 已通过。该记录创建时生产 Web/Native UI 尚未实施；当前实现与验证状态见文末最新记录。

实施记录（转账端点小步）：`FactView` 及关系展开保留父级 `cash_granularity`；普通转账与个人换汇 matcher 只接受 `atomic` 组成项，aggregate 组成项首期失败关闭。验证命令：`PYTHONPATH=tests:.:src uv run pytest tests/test_source_agnostic_transfer_matching.py tests/test_transaction_relations_transfer.py tests/test_transfer_phase_c.py tests/test_transaction_relations_open_leg.py tests/test_cash_transaction_components.py -q`，结果 `36 passed, 7 skipped`；`git diff --check` 通过。完成提交后记录实际 `HEAD`。

## 2. 失败契约测试（先红）

- [x] 2.1 新增父流水/组成项守恒、`atomic`/`aggregate` 派生和非法输入测试，先确认当前实现失败。
- [x] 2.2 新增支付宝 `&` 多账户预览阻塞、Decimal 分摊确认、重复确认幂等和 `source_payload` 保留测试。
- [x] 2.3 新增 component-to-component `payment_mirror`、部分/多笔 `refund_offset`、singleton `transfer_pair` 和 applied amount 超额拒绝测试。
- [x] 2.4 新增组成项余额快照、账户筛选、父级投影展示和财富现金流来源 revision 测试。
- [x] 2.5 新增 SQLite/PostgreSQL 同一契约矩阵测试，覆盖工作区外键、精确金额和关系端点类型。

## 3. 数据模型与持久化

- [x] 3.1 在 `cash_transactions` 增加 `cash_granularity`，放宽 aggregate 父流水的 `account_id`，并新增父级守恒相关约束/索引。
- [x] 3.2 新增 `cash_transaction_components` 模型、复合工作区外键、Decimal 字段、顺序唯一约束和账户索引。
- [x] 3.3 重建现金关系模型，使现金端点为 component 外键并保存 `applied_amount`；同步投资资金关系模型。
- [x] 3.4 更新 schema 创建、revision 触发器和测试 fixture；删除旧模型依赖，不添加兼容迁移。

## 4. 现金写入与余额

- [x] 4.1 扩展现金仓储与 UoW，提供父流水和组成项的原子新增、更新、删除及守恒校验。
- [x] 4.2 改造手工流水新增/编辑，使普通流水自动维护 singleton component，组合流水可增删多个 component，界面不暴露 `cash_granularity`。
- [x] 4.3 改造余额快照、现金列表账户过滤和删除/编辑影响计算，使账户余额只读取 component。
- [x] 4.4 更新现金事实读取边界，确保关系扫描、投影和财富读取能得到父字段与 component 分配。

## 5. 支付平台导入

- [x] 5.1 扩展支付宝 `&` 支付方式解析，区分真实账户项与红包/优惠类非账户项，并保留原始行不变。
- [x] 5.2 扩展导入预览数据结构，返回 component 草稿、缺失分摊金额错误和可操作的阻塞状态。
- [x] 5.3 扩展导入确认 API/服务，接收 Decimal 分摊并在同一事务写入父流水、组成项、映射和快照。
- [x] 5.4 用 `~/.ft/bills` 的真实支付宝样本做全量导入校准，记录各 `&` 桶的解析、阻塞和成功计数。

## 6. 关系匹配与投资资金

- [x] 6.1 将 `payment_mirror` matcher/index 从父 fact 展开为 component 视图，保留父流水的时间、商户和来源证据。
- [x] 6.2 将 `refund_offset` 剩余金额和 open-leg 锚点改为 component，支持部分退款、分次退款及平台/银行退款镜像。
- [x] 6.3 将普通 `transfer_pair` 改为 singleton component 端点；aggregate 转账首期失败关闭，换汇保留双币种语义。
- [x] 6.4 将现金—投资资金调拨候选、唯一性和投影引用改为 component 端点。
- [x] 6.5 更新关系审查、确认、取代、幂等和跨工作区校验，确保不能混入父流水 ID。

## 7. 投影、财富与客户端

- [x] 7.1 重写现金投影构建输入和校验：按 component 计算账户维度，按父流水聚合列表金额和证据。
- [x] 7.2 更新 projection schema/query，使 aggregate 不伪造单一账户，支持组成项详情和账户筛选。
- [x] 7.3 更新财富现金流读取和 source revision，组件变化可触发可重建读模型。
- [x] 7.4 更新 Web/Native 手工流水表单、导入预览和详情展示，覆盖正常、阻塞、错误、空状态及键盘/响应式行为。
- [x] 7.5 执行跨端影响检查；若出现 UI 结构变化，创建原型并完成 Hallmark audit。

## 8. 审查与验证

- [x] 8.1 运行受影响单元/集成/契约测试、类型检查、构建和 `git diff --check`，逐项记录结果。
- [x] 8.2 在专用名称以 `_test` 结尾的 PostgreSQL 数据库配置 `FT_TEST_POSTGRES_URL`，补跑同一契约矩阵；未配置时记录为未完成。
- [x] 8.3 使用真实浏览器验证导入预览补齐、确认、错误/空状态、组成项编辑、账户筛选和桌面/移动宽度，并记录 URL、视口、截图和控制台结果。
- [x] 8.4 完成产品/工程/安全/最终 diff 复核，修复阻断 finding 并回写 `tasks.md`。
- [x] 8.5 记录发布准备、重建数据库步骤、回滚方式、当前 `HEAD` 和残余风险；用户已明确授权当前仓库向 `refactor/web` 创建并合入 PR，待实际发布后回写结果。

实施记录（任务 2.1）：当前 `HEAD=b48207d`，比较基线为 `origin/refactor/web=4f4633d`；新增 `tests/test_cash_transaction_components.py` 覆盖组成项数量派生、客户端粒度字段忽略、币种/方向/守恒/空分配拒绝、失败原子性和更新时端点身份保持。验证命令：`PYTHONPATH=tests:.:src python3 -m pytest tests/test_cash_transaction_components.py -q`，结果 `6 passed, 1 warning`。警告为现有 `pytest-asyncio` 未等待协程的运行时警告，不影响测试结果。

实施记录（任务 2.2、2.4、2.5）：支付宝 `&` 解析、预览阻塞、Decimal 分摊、确认幂等和来源载荷保留测试已加入 `tests/test_statement_account_mapping.py`、`tests/test_cash_import_wizard.py`；组成项余额、账户筛选、父级投影、财富现金流和来源 revision 测试已加入 `tests/test_cash_transaction_components.py`、`tests/test_application_cash_projection_evidence.py`、`tests/test_relational_cash_projection_evidence.py` 及相关投影测试；SQLite/PostgreSQL 同一测试入口和工作区边界契约已同步 fixture 与 contract tests。范围验证：`PYTHONPATH=tests:.:src python3 -m pytest tests/test_statement_account_mapping.py tests/test_cash_import_wizard.py -q`，结果 `78 passed, 1 warning`；资金调拨、组件、投影和关系范围验证结果为 `20 passed, 11 skipped, 1 warning` 与 `13 passed, 4 skipped, 2 warnings`。

实施记录（任务 3—7.3）：模型、迁移、现金仓储/UoW、导入、component 关系端点、资金调拨、现金投影、Web 查询和财富来源均已实现。迁移 36 明确重建组件关系表，不提供旧父流水关系兼容路径；历史迁移保留测试改为在迁移 35 断点验证旧 schema，避免把新约束伪装成兼容迁移。额外修复包括 active source identity 的软删除唯一索引、重复导入的来源关系元数据清除、同批重复 provider ID 的 transient parent 处理，以及替代退款关系写入前先释放旧 accepted edge。

实施记录（原型与跨端检查）：用户于 2026-09-21 确认交互边界：不新增独立分配工作台，只在现有导入第 3 步 `核对流水` 的流水行内编辑；所有未分配组合支付行全部展开且无折叠态；金额使用正数绝对值并显示已填/待分配，沿用现有下一步门禁和第三步文案。已据此重写 `prototype/index.html`：保留现有 stepper、汇总筛选和流水表，在每条待补分配父行下直接放置紧凑组成项输入区，并覆盖正常、待补齐（两条未分配行同时展开）、加载、空、错误、成功、禁用和键盘焦点状态。Hallmark 预检继续使用 Cobalt / Noto Sans SC / IBM Plex Mono 设计 token；Cross-platform Impact Check 已写入 `design.md`，并登记 Native 当前预览缺少组成项编辑器这一实现缺口。生产 UI 尚未实施，等待用户查看修订后的原型。

任务 5.4 暂缓：本阶段不读取或写入 `~/.ft/bills` 真实财务数据，未执行真实支付宝样本校准；后续需由用户提供明确的数据授权和可用样本后单独补跑。

状态回写：该暂缓记录属于早期执行阶段；后续已在只读授权范围内完成样本校准，详见本文末“最终交付验证记录”。校准没有写入账单、数据库或外部服务。

实施记录（阶段 6—8 初步验证）：

- `PYTHONPATH=tests:.:src python3 -m pytest -q --tb=short`：`1527 passed, 185 skipped, 2 failed, 33 warnings`，耗时 `356.70s`。两项失败均为 `ModuleNotFoundError: No module named 'xlwt'`：`tests/test_complete_statement_source_payload.py::test_ccb_keeps_full_row_and_extracts_only_counterparty_account`、`tests/test_statement_parser_probe.py::test_statement_parser_can_parse_spreadsheets_by_content_not_suffix`。项目依赖已声明 `xlwt`，但当前执行环境未安装；未擅自安装依赖。
- 受影响修复回归：`5 passed, 2 warnings`，覆盖性能分页索引、关联组规模夹具、同批重复 provider ID、两种资金调拨方向的证据输出。
- `openspec validate cash-transaction-components --strict --no-interactive`：通过。
- `git diff --check`：通过。
- `FT_TEST_POSTGRES_URL`：未设置，PostgreSQL `_test` 契约矩阵未执行，185 个跳过项不能计入双后端完整验证。
- `npm run build:web`：未执行成功，当前工作树没有安装 Web 依赖，`tsc: command not found`；未执行网络安装。
- 原型浏览器检查：已尝试用本机 Chrome 无头加载 `prototype/index.html` 并生成 1440/390 px 截图；当前 sandbox 拦截 GUI/浏览器进程，升级权限也被环境拒绝，因此没有截图、控制台或网络结果，不能宣称原型浏览器检查通过。
- 当前 `HEAD=b48207d`，比较基线 `origin/refactor/web=4f4633d`；未提交、未推送、未部署。生产浏览器 QA、Hallmark 最终 `audit`、Native 实机 QA 和发布回滚复核留待原型确认及生产 UI 实施后执行。

实施记录（修订原型静态检查）：`node` 提取并编译原型脚本通过；检查确认不存在旧 Workbench 结构、存在待补分配状态、所有示例未分配行均使用行内 `allocation-detail-row`，并保留移动断点与 `overflow-x: clip`。`openspec validate cash-transaction-components --strict --no-interactive`：通过；`git diff --check`：通过。尝试使用本机浏览器运行时和 macOS Quick Look 生成 1440/390 px 截图，但浏览器列表为空，Quick Look 渲染因当前环境禁止提升权限而被拒绝；无 PNG、控制台或网络证据，仍不能宣称视觉 QA 通过。

实施记录（原型细节修订）：根据用户标注，移除行内重复的标题、组成部分数量、已填金额和“请补齐金额”状态；剩余金额移动到原状态提示的位置并右对齐。组成部分输入改为一项一行，桌面与移动端均保持纵向排列。该细节仍属于原型阶段，生产 Web/Native UI 未改动。

实施记录（生产 UI 落地与跨端检查）：用户确认后已将交互落到现有导入第 3 步 `核对流水`，没有新增分配工作台、侧栏或折叠态。Web 使用 `TransactionTable` 的紧邻详情行渲染每个未分配组合支付；Native 在同一预览列表中渲染全部 `preview.items`，每条组成项各占一行。Web/Native 均使用共享 `allocationBalance` / `allocationMatches` 的精确十进制守恒逻辑，未完成或超额时禁用下一步和确认；Web/Native 详情页均展示已保存的支付组成项。`CashRecord.account_id` 同步为可空，证据转换保留 `cash_granularity` 与 `components`，避免把多账户父流水伪造成账户 `0`。

实施记录（Hallmark audit，人工回退）：因当前运行时未提供可调用的 Hallmark audit 工具，按 `hallmark` audit rubric 对 `web/src/pages/CashImportPage.tsx`、`web/src/components/TransactionTable.tsx`、`web/src/styles.css`、`mobile/src/app/(app)/import.tsx`、`mobile/src/app/(app)/record.tsx` 和 `prototype/index.html` 逐项复核。范围覆盖信息架构、冗余文案删除、Cobalt/Noto Sans SC/IBM Plex Mono token、键盘焦点、禁用/错误/空/成功状态、320/375/390/414/768/1440 断点和跨端语义。结果：`0 critical`、`0 major`、`0 minor`；没有发现需要回写代码的视觉或交互 finding。真实浏览器截图无法生成，另记为 8.3 的验证阻断，不将静态审查当作运行时通过。

实施记录（产品/工程/安全/最终 diff 复核）：产品范围复核确认三项用户要求均落地：红框冗余信息移除、剩余金额右移、组成项逐行展示；工程复核确认共享精确十进制校验与服务端守恒校验仍为双重门禁，组件详情行只在 `components.length > 1` 时出现且不改变普通流水布局；安全复核未发现新增外部写入、凭据、原始账单泄露或未校验金额路径；最终 diff 复核未发现阻断 finding。已采纳 `CashRecord.account_id` 可空契约修正，并在 `web/src/pages/CashLedgerPage.tsx` 保留组成项详情数据。

实施记录（交付前验证）：当前仍为 `HEAD=b48207d`、比较基线 `origin/refactor/web=4f4633d`。`openspec validate --all --strict`、`openspec doctor`、`git diff --check`、`python3 -m compileall -q src tests`、原型脚本静态检查均通过；原型检查确认 4 个示例分配区均保持展开、无旧标题/账户数量/重复警告文案，并保留 320/375/390/414/768/1440 断点声明。Bun 对全部受影响 TS/TSX 文件转译通过；共享分配 helper smoke 为 `incomplete(52.00)` / `complete(0.00)`。受影响后端回归：`PYTHONPATH=tests:.:src python3 -m pytest tests/test_cash_import_wizard.py tests/test_cash_transaction_components.py tests/test_application_web_queries.py tests/contract/test_web_api.py -q --tb=short`，结果 `114 passed, 6 skipped, 2 warnings`。

Web 自动化与构建仍未完成：`npm run test:web -- --run` 因工作树无 `node_modules` 报 `vitest: command not found`；`npm run build:web` 因 `tsc: command not found` 退出。此前依赖安装因环境无法访问 npm registry 且升级权限被拒绝，未引入未审计依赖。真实浏览器 QA 仍未完成：浏览器运行时列表为空，无法取得 390/1440 截图、控制台或网络证据；PostgreSQL `_test` 矩阵仍因 `FT_TEST_POSTGRES_URL` 未配置未执行；真实 `~/.ft/bills` 校准仍因未获数据授权未执行。完整回归此前为 `1527 passed, 185 skipped, 2 failed, 33 warnings`，两项失败均为既有测试缺少声明依赖 `xlwt`。

状态回写：以上是依赖和浏览器环境准备前的历史记录，已由下述最终验证记录取代；保留它以说明此前的阻断原因。

## 最终交付验证记录（2026-09-21）

本节记录实施完成后的实际证据，并取代上文早期的“未完成”状态。实施开始时 `HEAD=b48207d`、原始比较基线为 `origin/refactor/web=4f4633d`；最终提交前重新 fetch 后，PR 实际目标基线为 `origin/refactor/web=48a733f`。本节写入前仍未提交、未推送、未部署，也未写入真实财务数据。

### 需求校准与实现

- 在只读范围校准 `~/.ft/bills` 样本：12 个文件（`.csv` 3、`.pdf` 4、`.xls` 2、`.xlsx` 3），其中支付宝文件 3 个；解析错误 0，现金事实 3060 行，识别组合支付 6 行（5 个双组成项、1 个三组成项），6 行均为 `requires_allocation`，可直接确认 0 行。输出未包含姓名、账户名或金额明细，也未写入任何文件或数据库。
- Web/Native 均保留现有导入第 3 步 `核对流水`；所有未分配组合支付行同时展开，没有折叠、侧栏或独立工作台。红框中的重复标题、组成部分数量、已填金额和重复提示均移除；剩余/已匹配状态放在右侧；每个组成部分单独成行。金额守恒使用共享精确十进制 helper，并由服务端再次校验。
- Cross-platform Impact Check：Web、Native 导入预览和共享 DTO/语义 ID 均受影响；Native 详情页同步展示已保存组成项，值、标签、阻塞和确认结果与 Web 一致，平台只在输入控件形态上有差异。

### 后端与双后端验证

- SQLite 完整回归：`env -u FT_TEST_POSTGRES_URL -u FT_REQUIRE_TEST_POSTGRES PYTHONPATH=tests:.:src uv run pytest -q --tb=short` → `1556 passed, 184 skipped, 2 warnings`，433.06 秒。
- PostgreSQL 使用专用一次性容器 `ft-cash-components-pg-test`（PostgreSQL 16，数据库名 `finance_tracker_test`，满足 `_test` 约束，端口 55432）。容器仅用于本次测试，并设置 `fsync=off`、`full_page_writes=off`、`synchronous_commit=off`、`autovacuum=off`、`shared_buffers=256MB`；没有触碰生产数据库。完整契约/集成回归结果为 `1765 passed, 2 skipped, 1 failed`；唯一失败是既有 100k 财富冷重建性能门槛的 PostgreSQL p95 宿主机抖动（`8.513s > 6.5s`），不是本变更修改的财富代码或性能测试。相关 PostgreSQL 目标测试隔离复跑：`env FT_TEST_POSTGRES_URL='<redacted>' FT_REQUIRE_TEST_POSTGRES=1 PYTHONPATH=tests:.:src uv run pytest tests/test_wealth_performance.py::test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets -q -k postgresql --tb=short` → `1 passed, 1 deselected`，195.39 秒；因此将该项记录为环境残余风险，不将失败伪报为通过。
- 性能专项完整命令 `env FT_TEST_POSTGRES_URL='<redacted>' FT_REQUIRE_TEST_POSTGRES=1 PYTHONPATH=tests:.:src uv run pytest -m performance -q --tb=short` → `51 passed, 1 failed, 1716 deselected, 2 warnings`，804.63 秒；失败与上述同一 PostgreSQL 财富冷重建 p95 outlier，所有其它性能测试通过。
- 迁移/契约专项及修复后的关系、投影、导入回归均在 SQLite 和专用 PostgreSQL `_test` 库执行；迁移 36 明确为开发期一次性重建，降级抛出 `NotImplementedError`，不提供旧关系数据回填或兼容路径。

### Web/Native 构建、自动化与浏览器 QA

- `npm run test:web` → 15 个文件、151 个测试通过；`npm run test:shared` → contracts/api-client/core/presentation 共 23 个测试通过；`npm run typecheck:shared`、Native `npm run test --workspace finance-tracker-mobile`（7 个文件、26 个测试及 Expo plugin test）和 `npm run typecheck --workspace finance-tracker-mobile` 均通过。
- `VITE_FT_API_ORIGIN='http://127.0.0.1:5174' npm run build:web` → Vite 6.4.3，74 modules 构建通过。
- `npm run test:e2e --workspace finance-tracker-web` → `45 passed`；`npm run test:preview --workspace finance-tracker-web` → `16 passed`。真实浏览器覆盖开发预览 `http://127.0.0.1:5174` 和生产预览 `http://127.0.0.1:5173`；自定义生产 smoke 在 `1440x900` 和 `390x844` 验证两条组成项各自成行、未守恒时下一步禁用、填入 `40.00/52.00` 后可继续、移动端无横向溢出，控制台错误 0、请求失败 0。截图为 `/tmp/cash-import-allocation-production-1440.png` 与 `/tmp/cash-import-allocation-production-390.png`。
- Hallmark audit 工具在当前运行时不可调用，已按同一 audit rubric 完成人工回退审查：`0 critical`、`0 major`、`0 minor`；覆盖信息架构、删除冗余、token、焦点、禁用/错误/空/成功、320/375/390/414/768/1440 响应式范围。Native 实机 QA 不在当前环境可用范围内，已以 Native 测试、类型检查及共享语义契约覆盖，保留真实设备运行时验证风险。

### 交付前门禁与发布准备

- `openspec validate --all --strict` → 42 项通过；`openspec doctor` 通过；`python3 -m compileall -q src tests` 和 `git diff --check` 通过。
- 产品/工程/安全/最终 diff 复核无阻断 finding；已采纳 `CashRecord.account_id` 可空契约和组件端点显式类型修正。未引入凭据、原始账单输出、外部写入或新依赖。
- 发布/回滚：在目标环境删除旧开发数据库并从当前 schema 重建；不对迁移 36 执行降级。若回滚，回退合并提交后按旧代码重新创建旧 schema；专用 PostgreSQL 容器可直接销毁，不承载生产数据。
