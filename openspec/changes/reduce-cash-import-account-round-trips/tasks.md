# 减少现金导入账户补齐往返

分级：A（涉及币种集合写入、新增公共路由、共享客户端与后端多模块；按完整流程执行）。

## 1. 思考

- [x] 1.1 阅读 `openspec/project-context.md`、`docs/shared/product-coding-rules.md`、账户与导入主规格、`docs/import/cash-import-flow.md`、`DOMAIN_GLOSSARY.md`，以及 `ImportAccountViewModel.ensureCurrencies`、`AccountCommandService`、`CASH_MUTATION` 收据和现有合同测试。
- [x] 1.2 现状确认：点“下一步”对每个目标账户执行 `GET by-id` → 缺币种 `PATCH` → 成功后再 `GET`；选择账户时已读取同一账户。3 个目标账户、1 个缺币种且无快照时为 6 个请求。
- [x] 1.3 与用户确认：批量接口按“每账户独立命令 + 逐项结果”；客户端复用选择时修订并用 CAS 处理并发；从最新 `main` 新建分支、新 change、新 PR。

## 2. 计划

- [x] 2.1 产出 `proposal.md`、两份 delta 与 `design.md`。
- [x] 2.2 明确事务边界：批量中每项独立事务与独立收据；整批不共享事务；币种并集在服务端命令内完成。
- [x] 2.3 明确回滚：客户端恢复逐账户 GET+PATCH，批量路由可保留或删除；无 schema 迁移。

## 3. 任务拆分

- [x] 3.1 后端失败合同测试先行：逐项成功、币种并集、空并集仍推进修订、重复项/非法项、修订冲突、幂等重放、权限、未知结果与部分成功。
- [x] 3.2 客户端失败测试先行：快照复用、一次批量提交、逐项采纳、冲突/失败/未知、能力位保留、退出补偿键、批量撤销。
- [x] 3.3 真实浏览器冒烟：选择账户 → 下一步 → 核对页，确认只发一次批量补齐、无逐账户 PATCH，并正确进入预览。

## 4. 构建

- [x] 4.1 后端：新增请求/响应协议与路由；`AccountCommandService` 增加补齐币种命令入口；逐项映射既有错误；整批预检失败返回 `commit_state=not_committed`；来源建议增加目标账户快照（名称、类型、状态、币种、修订、可写能力）。
- [x] 4.2 后端：合同测试转绿；SQLite 与真实 PostgreSQL 同一矩阵（46 passed）。
- [x] 4.3 客户端：新增批量请求、领域类型与 Ktor/Demo 实现；`ensureCurrencies` 优先使用选择快照或建议快照、缺失才补读，然后一次批量；全部预检通过后才持久化恢复键；逐项采纳并保留未知结果键。
- [x] 4.4 客户端：ViewModel 单测转绿；位置、失败、未知结果与单条命令一致；批量成功后可撤销。
- [x] 4.5 文档：更新 `docs/import/cash-import-flow.md`、`docs/import/cash-import-redesign-contracts.md`、`docs/import/feature-map.md` 与 `.agents/skills/cash-statement-import/references/api-contract.md`。
- [x] 4.6 跨端影响：共享 Compose 同时服务 Web 与 Native；无页面结构、文案或视觉变化。Web 走真实浏览器 QA；Native 仅做 iOS 目标编译与共享 wasm 单测（Android SDK 外接卷未挂载）。

## 5. 审查

- [x] 5a 范围化工程复核（产品/范围、工程、最终 diff）：权限、修订、幂等、部分成功、恢复键、请求边界与回滚均通过；发现项与处置见下。
- [x] 5b 代码库防劣化检查：本会话运行时未注册 `$codebase-degradation-review`、`$contract-design-review`、`$ui-reuse-review`（`skill` 返回 unknown），按三个技能文件的范围与检查项完成等价的独立只读审查。
  - 契约设计：新路由与单账户命令共用 `AccountCommandService`、`_authorize`、`expected_revision` 与收据；只允许币种；请求/响应模型禁止非法组合；未复制内部模型。保留理由：`CurrencyRequirementResult` 与 `AccountView` 分层是请求边界隔离，不合并。
  - UI 复用：本次未改任何 Compose 组件、布局、token 或用户可见文案，不适用；未发现重复业务 UI 或绕过 token。
- [x] 5c 独立复核 finding 与处置：
  - **M1（修复）**：恢复键曾在读取/预检之前逐个持久化，若中途失败会留下未发送的 pending。现改为先完成全部读取、快照校验、100 项与 64 键容量预检，再统一持久化；持久化中途失败会回滚已写入键。授权预检的存储失败返回 `account_unavailable` + `commit_state=not_committed`，客户端按确定未执行清键。
  - **M2（修复）**：原 proposal/spec 对自动建议的读取次数表述矛盾。已改为“已有快照不重复读取；缺少快照只补读一次”，并修正请求数承诺为“有快照 1–2 个；自动建议读取次数不变，写请求合并为 1 个且写后不回读”。
  - **M3（修复）**：本文件按实际命令、结果与范围重写，不再提前勾选未执行门禁。
  - **m1（修复）**：`account_changed` / `account_not_found` 后移除该账户快照，下一次操作重新读取，重复点“下一步”可自愈。
  - **m2（部分修复）**：未知结果优先于确定失败（处理完全部成功项后再抛未知）；逐账户错误行内展示不在本次 UI 范围内，保持现有单一可操作错误。
  - **m3（修复）**：客户端识别 `commit_state=not_committed` 为确定未执行；其余按既有状态码分类，保持与单账户命令一致。
  - **m4（修复）**：成功项的收据只保留在 `account.receipt_id`；错误模型增加 `receipt_id` 与 `commit_state`，冲突收据不再丢失。
  - **m5（保留并记录）**：批量命令仍以 `account_update` 建模，`patch` 保存的是并集。未提交时用普通 `PATCH` 重发同一并集等价于批量补齐；已提交时会得到 `idempotency_conflict`，随后按原幂等键读取收据核实，不会生成新键。账户界面当前未暴露 `retryOriginal`；若未来暴露，仍应优先走批量入口。
  - **m6（保留）**：批量循环不吞程序异常，按仓库异常边界失败关闭；已提交项可由各自收据按恢复键核实，不返回伪造的部分成功。
  - **m7（修复）**：e2e 按方法统计 `GET` / `PATCH`，多账户用例断言 `GET by-id = 2`、`PATCH by-id = 0`、batch = 1。
  - **m8（修复）**：`.agents/skills/cash-statement-import/references/api-contract.md` 已补批量入口及 per-item 键说明。
  - **m9（修复）**：单测 fake 的批量结果不再携带 `capabilities`，与真实收据视图一致；用例断言客户端从选择快照保留能力位。
  - **m10（修复）**：提交前移除 `compose/qa/web/node_modules` 临时符号链接，不进入提交。
  - **m11（部分修复）**：客户端增加 100 项与 64 个恢复键预检；整请求只在服务端明确拒绝码（`account_payload_invalid`、`workspace_forbidden`、`idempotency_key_required`）或 `not_committed` 时清键，408/429 等中间层错误保留恢复键。64 KB 正文由服务端统一校验，客户端不重复估算。

## 6. 测试与 QA

- [x] 6.1 `OPENSPEC_TELEMETRY=0 openspec validate --all --strict` → 30 passed，0 failed；`openspec doctor` 根与引用正常。
- [x] 6.2 后端定向合同：`FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:55432/ft_cash_import_currency_test PYTHONPATH=tests:.:src <venv>/python -m pytest -q tests/contract/test_account_command_api.py` → 46 passed（SQLite + 真实 PostgreSQL 16），含批量、冲突、幂等重放、权限、未知结果与部分成功。
- [x] 6.3 后端完整回归（A 类）：`PYTHONPATH=tests:.:src <uv.lock 匹配的 venv>/bin/python scripts/run_backend_tests.py --shard-index 0 --shard-count 1` → **4677 passed、1468 skipped、73 deselected、0 failed**（24:49）。首次误用仓库根旧 `.venv` 时 75 个失败集中在 multipart 解析（缺 `python-multipart`），与本次改动无关，已用正确环境重跑。
- [x] 6.4 共享 Compose：`:shared:wasmJsBrowserTest` → **507 tests / 0 failed**；`:webApp:compileKotlinWasmJs` 与 `:shared:compileKotlinIosSimulatorArm64` BUILD SUCCESSFUL。
- [x] 6.5 真实浏览器冒烟（Playwright + 生产 wasm 构建 + Google Chrome，`http://127.0.0.1:<动态端口>`）：
  - 视口 1440×900 与 390×900；走通“文件 → 账户准备 → 下一步 → 核对”。
  - 单账户自动建议用例：建议载荷携带账户快照，`GET by-id = 0`、`PATCH by-id = 0`、`currency-requirements = 1`。
  - 双账户自动建议用例：`GET by-id = 0`、`PATCH by-id = 0`、`currency-requirements = 1`，预览携带两个账户的新修订。
  - 全量 `compose-cash-import.e2e.ts` → **46 passed、0 failed**（含新增建议分组与无建议 390/1440 用例）；一次全量运行出现 1 个既有 CMP-10623 语义镜像偶发（关闭后 400 ms 仍可见任务），单独重跑通过。截图 `test-results/compose-cash-import.e2e.ts-42a87--for-every-selected-account/account-batch-currency-1440.png`。
  - 无控制台错误；无网络 4xx/5xx（mock 响应除外）。
- [x] 6.6 `git diff --check` 干净；docs/spec 链接与术语按 `$domain-glossary`、`$chinese-documentation` 复核。

## 7. 发布与反思

- [x] 7.1 交付：提交 `a2bb97ef`、`70d7faec`、`06c02819`，分支 `fix-cash-import-account-round-trips`，PR #110（base `main`）。回滚：客户端恢复逐账户 `GET + PATCH`，批量路由可保留或删除；已写入的账户币种是既有事实，无数据库迁移。剩余风险：旧服务端响应缺少建议快照时仍需一次读取；批量结果为 200 + 逐项状态，调用方必须检查每项 `status`；批量中的程序异常按失败关闭返回 500，已提交项凭各自收据核实。
- [x] 7.2 用户决策反思：本次确认了三个取舍——批量只作传输聚合、每项保留独立幂等键与收据；客户端复用选择快照并用 CAS 处理并发；从最新 `main` 新建分支/change/PR。前两条是本次业务方案，暂不升级为全局规则；若后续出现第二个批量写入口，再评估是否把“批量不得合并收据或事务”写入 `docs/shared/product-coding-rules.md`。
