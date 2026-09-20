## 1. 思考与范围锁定

- [x] 1.1 阅读 `openspec/project-context.md`、交易关系与账单导入主规格，核对当前 `HEAD`、基线和工作树范围
- [x] 1.2 复核 Web 四步导入、CLI 导入、`RelationService.check()` 和 `StatementImportService` 的现状差异，并在本变更记录根因与非目标
- [x] 1.3 运行 `openspec validate --all --strict` 的变更前基线检查，记录命令、结果和未解决环境条件

## 2. 失败回归测试先行

- [x] 2.1 添加去标识化微信夹具：同一订单的退款双行与奈雪、多店宝网络等其他同额消费并存，先证明当前 Web 规划会产生错误候选
- [x] 2.2 添加共享规划器失败测试，要求 Phase A 先占用微信结构化退款双行，不能被商户同额退款规则抢占
- [x] 2.3 添加 Web 预览与 CLI 规划结果等价测试，覆盖关系类型、端点、状态、规则 ID 和结构化证据
- [x] 2.4 添加预览只读、重复预览不写关系和重复确认幂等测试
- [x] 2.5 添加自动接受、手动选择、跳过、拒绝和拒绝后重复扫描的关系决定测试
- [x] 2.6 添加预览上下文变化、关系端点冲突、投影校验失败时确认回滚测试

## 3. 共享关系规划器

- [x] 3.1 定义统一事实快照、虚拟预览事实引用、关系计划、稳定 `proposal_key` 和计划上下文摘要的数据结构
- [x] 3.2 抽取 `RelationService` 的事实装配、别名索引、剩余退款、已确认关系和占用集合加载逻辑
- [x] 3.3 抽取 Phase A 平台退款提案生成，使其复用 `match_phase_a_platform_refunds` 且不在规划阶段写数据库
- [x] 3.4 保证统一事实装配保留 `source_payload`、`offset_role`、平台状态、交易号和 `offset_group` 等结构化来源元数据
- [x] 3.5 让规划器固定执行 Phase A → B → C → D，并统一传入 `DefaultRefundTextGates`、关系上下文和剩余金额
- [x] 3.6 将当前 `_persist_proposal` 中的业务键、人工决定保护、端点互斥、剩余退款和状态升级规则拆为可复用的计划校验与应用逻辑
- [x] 3.7 移除或修正 `skip_platform_import_refund_seeds` 的无效语义，避免 Web 以未生效开关绕过统一阶段

## 4. 统一导入确认事务

- [x] 4.1 实现 `apply_plan_in_uow`，在现有导入 UoW 内校验计划摘要、工作区、端点、金额、候选和关系类型
- [x] 4.2 将 Web 关系决定从事实 ID 转换为稳定业务行引用，并支持实际 fact ID 映射、已存在事实和跨工作区拒绝
- [x] 4.3 统一自动接受、待审核、手动选择、跳过和拒绝的保存语义；拒绝必须保留可审计状态或等价抑制证据
- [x] 4.4 在所有关系决定应用完成后只刷新一次受影响收支投影，并确保任一失败回滚流水、映射、关系、余额和投影
- [x] 4.5 修改 `StatementImportService` 使用同一 UoW 的关系规划与应用入口，移除 Web 四步路径对 `run_relation_check=False` 的依赖
- [x] 4.6 保留 `RelationService.check()` 作为 CLI 兼容包装器，使独立关系检查和 CLI 导入使用相同规划器与默认决定策略

## 5. Web 预览与 CLI 接入

- [x] 5.1 删除 Web 专属的直接 `run_relation_phases` 编排，改由共享规划器接收现有事实和虚拟导入事实
- [x] 5.2 为预览响应增加稳定建议标识和计划上下文摘要，保持现有页面字段兼容并避免暴露内部代理键或敏感来源值
- [x] 5.3 确认请求重新解析、映射、规划并校验摘要；账本、关系、别名或规则版本变化时返回可识别的陈旧错误
- [x] 5.4 验证 Web 四步正常、空关系、待审核、拒绝、确认成功和确认失败状态，确保页面可继续使用现有决定控件
- [x] 5.5 保持 CLI 现金导入的参数、输出和默认关系状态合同不变，并增加 CLI/Web 对照夹具验证
- [x] 5.6 让 CLI 数据库映射适配器仅逐行过滤 `import_composite_payment_unresolved`，保留跳过元数据；其余来源身份和映射错误继续失败关闭
- [x] 5.7 将组合支付跳过数量加入 CLI `OperationResult.details` 和成功输出，并覆盖文件部分跳过、全部跳过与未知错误三条路径

## 6. 审查与验证

- [x] 6.1 运行受影响 Python 单元、应用服务、关系和导入集成测试，修复失败回归并记录证据
- [x] 6.2 运行 Web Vitest、TypeScript 检查、生产构建和 Playwright；覆盖主流程、错误/空状态、关键键盘焦点及 390 / 1440 px 视口
- [x] 6.3 使用真实浏览器验证 Web 四步导入：选择文件、账户映射、流水预览、关系确认、拒绝/跳过和成功结果；记录 URL、视口、截图、控制台/网络错误
- [x] 6.4 运行 SQLite 导入关系契约矩阵，覆盖精确金额、来源快照、幂等、拒绝、回滚和投影一致性
- [x] 6.5 使用本机 Docker 中名称以 `_test` 结尾的专用 `finance_tracker_test` PostgreSQL 数据库，临时配置 `FT_TEST_POSTGRES_URL` 并补跑关系/导入契约矩阵
- [x] 6.6 运行 `openspec validate --all --strict`、`openspec doctor`、`git diff --check`，并完成范围、工程、安全和最终 diff 复核
- [x] 6.7 在本文件记录实际命令、结果、当前 `HEAD`、比较基线、执行时间、审查 finding、残余风险和回滚观察项

## 7. 本轮回归：预览/确认确定性与 CLI 组合支付

- [x] 7.1 先添加失败测试，证明无 offset 时间会造成预览摘要与实际事实表示差异，并证明虚拟 ID tie-breaker 会改变退款剩余证据
- [x] 7.2 添加共享规范时间测试，覆盖支付宝、微信、银行卡和工银亚洲来源时区，以及原始来源快照不被改写
- [x] 7.3 添加稳定事实排序测试，覆盖镜像、转账、退款、阶段 seed、候选 evidence 和 `proposal_key` 列表顺序
- [x] 7.4 添加 Web 预览/确认与 CLI 同批标准化流水的关系端点、规则、状态和证据等价测试
- [x] 7.5 使用真实 `~/.ft/bills` 在两个全新 SQLite 数据库复跑 CLI 和浏览器导入，记录账本、跳过行和关系数量对照
- [x] 7.6 运行受影响测试、构建、真实浏览器 QA、SQLite 契约矩阵和最终 diff 复核；准确记录 PostgreSQL `_test` 矩阵是否可用

## 验证证据

- 基线：比较基线和当前 `HEAD` 均为 `4ddca0dd151762a9dfc76182845eae77d5b67b3e`（`Merge pull request #60 from ssttkkl/alipay-bill-import-balance-mapping`）；工作树中保留了既有未跟踪的 `.codex/worktrees/`、`.vite/`、`docs/superpowers/plans/2026-08-01-icbc-refund-pairing.md` 和 `web/.lan-vite.config.mjs`，未纳入本变更。
- Python（最终，2026-08-15）：`uv run pytest -q` 通过 1497 项、跳过 179 项、1 个既有 Starlette 弃用警告；受影响回归 `uv run pytest -q tests/test_import_relation_planning.py tests/test_statement_account_mapping.py tests/test_cash_import_wizard.py tests/test_import_scan_refund_boundary.py tests/test_import_no_relation_write.py tests/test_relations_pipeline_order.py tests/test_transaction_relations_refund.py tests/test_transaction_relations_open_leg.py` 通过 121 项、跳过 7 项。曾出现的 2 个失败来自稳定候选排序对整数 fact ID 的字符串比较，已修复并由上述全量回归验证。
- Web（最终）：`npm run build` 通过；`npm test -- --run` 通过 119 项。真实浏览器使用 `http://127.0.0.1:5190/w/default/cash-import`，桌面 `1440x1000` 完成选择文件、映射账户、流水预览、74 条自动配对和确认导入；移动 `390x844` 成功页无横向溢出（`innerWidth=390`、`scrollWidth=390`）。网络 `scan`、`preview`、`commit` 均为 200，除登录态探测的预期 401 外无错误；截图为 `/tmp/ft-final-browser-rerun-relations-1440.png`、`/tmp/ft-final-browser-rerun-success-390.png`。
- OpenSpec：`openspec validate unify-import-relation-planning --type change --strict` 通过，`openspec validate --all --strict` 为 27 项通过，`openspec doctor` 通过，`git diff --check` 通过。
- 数据库：使用专用 `finance_tracker_test` PostgreSQL 数据库临时配置 `FT_TEST_POSTGRES_URL`，关系/导入矩阵 `uv run pytest -q tests/test_transaction_relations_open_leg.py tests/test_import_relation_planning.py tests/test_statement_account_mapping.py tests/contract/test_row_idempotent_import.py` 通过 73 项；连接配置未写入仓库或持久 shell 环境。
- 失败先行：实现前的新时间摘要、虚拟 ID 稳定排序和组合支付场景回归均按预期失败；实现后由 121 项受影响测试、1497 项全量测试和 73 项 PostgreSQL 矩阵转绿。
- 本轮复现（2026-08-15）：真实 `~/.ft/bills` 的首个支付宝账单在浏览器确认时返回 409 `配对建议已经变化，请重新预览账单。`；差异首先来自预览 naive 时间与落库 UTC 时间，其次来自虚拟事实 ID 与实际自增 ID 参与领域 tie-breaker。CLI 同一账单在数据库账户扫描阶段以 `import_composite_payment_unresolved` 整批失败。本轮实现必须覆盖上述两条回归，并不得把其他映射错误改成跳过。
- 本轮真实账单对照（2026-08-15，两个全新 SQLite 库）：CLI 逐个导入 `~/.ft/bills` 中 3 个支付宝 CSV、3 个微信 XLSX、2 个建行 XLS、2 个工行信用卡 PDF 和 1 个工行借记卡 PDF；Web 使用同一 `CashLedgerCommandService` 完成同批 11 个文件的导入，另用真实浏览器 QA 在全新库完成首个支付宝文件的完整四步流程。CLI 与 Web 均为 11389 条现金事实、3205 条关系；按来源业务行归一化后 3205 条关系的端点、类型、状态、规则 ID 和候选证据全部相等。首个支付宝文件两边均为 1362 条新事实、5 条 `import_composite_payment_unresolved` 跳过；浏览器配对页为 74 条自动配对，确认后库内为 1362 条事实、74 条 `refund_offset/accepted` 关系。
- CLI 输出验证：首个支付宝 CLI 命令输出 `已向当前数据库导入 1362 条` 和 `已跳过 5 条无法唯一归属的组合支付`；其余 10 个文件均成功导入，未出现未映射账户错误。
- 审查：范围复核确认 Web 不再直接编排 `run_relation_phases`，CLI `RelationService.check()` 与导入确认均经过共享 `RelationPlan`；工程复核确认预览只读、确认摘要陈旧失败和同一 UoW 投影刷新；安全复核确认用户决定使用稳定 `proposal_key`，候选端点不匹配时返回 `import_relation_candidate_invalid`。未发现本变更范围内的阻断性 finding。
- 发布与回滚：未提交、未推送、未部署；当前工作树是可回滚状态。若需回滚，恢复本变更涉及的应用服务、路由、Web API 字段和关系规划器改动即可，不触及既有账本数据迁移。

## 8. 本轮微信退款配对回归与来源元数据修复

- [x] 8.1 记录历史好版本 `a8731f0`、当前基线 `c2456db` 和真实微信账单隔离库的可复现配对结果，明确商户退款与转账退回的范围差异
- [x] 8.2 先添加失败回归测试，覆盖独立 `relation_metadata` 重载、微信结构化角色抢占同额干扰项，以及 ISO 8601 `T/+00:00` 时间解析
- [x] 8.3 添加 SQLite / PostgreSQL 等价的 `cash_transactions.relation_metadata` 可空迁移，并更新运行时 schema revision
- [x] 8.4 让导入、现金仓储、事实装配和关系规划分别保存/读取 `source_payload` 与关系派生元数据；同一业务行重导时只刷新派生元数据而不复制流水
- [x] 8.5 修复平台退款时间解析，保持无效时间失败关闭并保留现有转账退回排除规则
- [x] 8.6 用最新代码在全新 SQLite 库 CLI 导入真实微信账单，核对 1170 条事实、商户退款端点、规则 ID、状态和错误干扰项
- [x] 8.7 运行受影响单元、关系、导入幂等和 SQLite 契约测试，记录失败先行与转绿结果
- [x] 8.8 准备名称以 `_test` 结尾的专用 PostgreSQL 数据库并补跑同一导入/关系契约矩阵；未配置时准确记录未完成及补跑条件
- [x] 8.9 运行 `openspec validate --all --strict`、`openspec doctor`、`git diff --check`，完成范围/工程/安全/最终 diff 复核并记录 finding
- [x] 8.10 未获用户提交、推送或部署授权前保留工作树；记录回滚方式、未解决风险、当前 `HEAD` 和比较基线

### 8.x 本轮执行证据（2026-08-16）

- 历史定位：关系回归基线为 `c2456dbc7a2f1dc78488dd6de2a62741b0ced57e`；随后将工作树快进到真正最新的 `origin/refactor/web` `1e85480a23fcc9a3d97481e5feb829cfbd7e35bb`。中间的 `74da6f0`、`1e85480` 只改移动端缩放和登录后工作区入口，未改 `src/ft` 导入/关系代码。`a8731f0`（`fix(007): match import refund pairs by provider txn record_id`）在真实账单上得到 11 条历史导入退款关系，其中 10 条商户退款和 1 条 QQ 红包转账退回。基线导入库为 `/Users/huangwenlong/.ft/finance-tracker-wechat-2023-good-a8731f0-20260816.db`，工作区为 `wechat-2023-good-a8731f0`。
- 失败先行：修复前运行 `uv run pytest -q tests/test_platform_refund_matchers.py tests/test_import_relation_planning.py tests/test_015_idempotency.py`，结果为 `5 failed, 21 passed`；修复后同一组为 `26 passed`，加入来源行映射回归后为 `27 passed`。
- 持久化修复：新增迁移 `20260816_34_cash_relation_metadata.py`，将关系派生字段存入独立可空 JSON 列；原始 `source_payload` 保持来源快照，不再混入 `offset_*` 派生字段。同一账单重导验证为 1170 条事实不增加重复行，并可刷新/清除关系元数据。
- 最新代码实账：在关系基线 `c2456db` 的修复代码上使用全新库 `/Users/huangwenlong/.ft/finance-tracker-wechat-2023-fixed-final-20260816.db`，通过 CLI 导入 `/Users/huangwenlong/.ft/bills/微信支付账单流水文件(20230612-20240611)_20260613010545.xlsx`；随后工作树已快进到最新 `1e85480`，且该提交链未改关系实现。结果为 1170 条事实、20 条带关系元数据的流水、10 条 `refund_offset/accepted` 商户退款关系、0 条待审核关系。10 条端点均为同一商户的退款与入账，规则为 `scan.wechat.full_status_pay.v1`（9 条）或 `scan.wechat.partial_embedded.v1`（1 条），未出现截图中的跨商户错误配对。当前规则仍按既有合同排除 `transfer_reversal`，因此不把 QQ 红包转账退回计入商户退款数量。
- SQLite/影响范围验证：`uv run pytest -q tests/test_alembic_migration.py tests/test_015_idempotency.py tests/test_statement_import_mapping.py tests/test_postgres_statement_import.py tests/test_import_relation_planning.py tests/test_platform_refund_matchers.py tests/test_transaction_relations_refund.py tests/test_record_type_relation_gates.py tests/test_import_scan_refund_boundary.py tests/test_import_no_relation_write.py tests/test_relations_pipeline_order.py tests/test_transaction_relations_open_leg.py tests/test_transaction_relations_cross_batch.py tests/contract/test_row_idempotent_import.py` 结果为 `145 passed, 16 skipped`；`python -m compileall -q src tests` 通过；加入本轮通用平台标题回归后的最终全量 `uv run pytest -q` 结果为 `1503 passed, 180 skipped, 1 warning`。
- 最新基线补验：快进到 `1e85480` 后，上述关系回归仍为 `145 passed, 16 skipped`；在锁文件依赖安装后，`web` 的 `npm test -- --run` 为 `11` 个测试文件、`125 passed`，`npm run build` 通过。`npm ci` 报告 1 个既有 high severity audit advisory，未执行超出本变更范围的自动升级，作为残余依赖风险记录。
- PostgreSQL：使用名称以 `_test` 结尾的专用 `finance_tracker_test` 数据库。新增双后端真实关系导入契约 `tests/contract/test_cash_import_dual_backend.py::test_refund_relation_metadata_survives_import_on_both_backends` 结果为 `2 passed`；除一个既有映射测试的共享 schema 隔离问题外，导入/关系契约矩阵结果为 `46 passed`。该映射测试在重置专用测试库后单独运行结果为 `2 passed`；其失败原因是测试本身未在每次运行前清理共享 PostgreSQL schema，不是本次业务断言失败，作为残余测试隔离风险保留记录。
- 最终门禁：本节回写后重跑 `openspec validate --all --strict`（29 项通过）、`openspec doctor`（通过）、`git diff --check`（通过）和 `python -m compileall -q src tests`（通过）；范围复核确认本轮未提交改动围绕来源元数据持久化、平台退款时间解析、建行钻石证据、通用平台标题退款配对、对应回归测试和 OpenSpec 记录，最新远端的 Web 提交已快进纳入工作树，无提交、推送或部署。当前 `HEAD` 为 `1e85480a23fcc9a3d97481e5feb829cfbd7e35bb`；尚未获得外部写授权，故保留工作树。回滚方式为撤销本轮应用/迁移/测试与规格改动，不触及上述隔离数据库。
- 需求澄清：仓库要求的 `grill-me` `/grilling` session 在当前运行时没有可调用入口，已向用户说明，未伪造该 session 的执行结果；其余范围、非目标、验收与风险已写入本变更 artifacts。

- [x] 8.11 使用 `.ft/bills` 中除已单独核验的 2023 微信账单外的 10 份现金账单，在全新 SQLite 库 `/Users/huangwenlong/.ft/finance-tracker-all-other-bills-final-20260816.db` 通过 CLI 逐份导入：支付宝 3 份、微信 2 份、建行 2 份、工行信用卡 2 份、工行借记卡 1 份全部成功，共 10219 条现金事实；第一份支付宝保留 5 条 `import_composite_payment_unresolved` 跳过。全库生成 362 条已接受退款关系、7 条待审核退款关系、2160 条已接受付款镜像关系、28 条已接受转账关系和 16 条银行卡-平台钻石退款关系。工行信用卡关系中不存在肯德基→麦当劳的跨商户误配；其余跨显示名的关系均为来源结构化角色、平台别名或银行标准化名称导致的既有合法配对。
- [x] 8.12 用同一隔离库实测投资账单：IBKR `TRANSACTIONS.1Y.csv` 导入 35 条，`TRANSACTIONS.7D.csv` 因与 1Y 重叠后新增 1 条，合并格式 `U19367228_20260101_20260611.csv` 失败关闭并明确提示缺少 `Transaction History / 总结` 区段；东方证券 PDF 因文本提取失败未导入；16 份盈立证券 PDF 均明确报 `PDF password required`，未猜测或暴力尝试密码。投资库最终有 36 条 `ibkr_csv` 事件。
- [x] 8.13 针对建行钻石退款和平台通用标题误配先行失败测试：前者曾在真实第二份建行流水导入时因证据缺少 `refund_amount` 触发 `decimal.InvalidOperation`，后者先证明 `美团App` 会将肯德基与麦当劳错误配对；最小修复后运行 `uv run pytest -q tests/test_mirror_business_day_diamond.py tests/test_transaction_relations_refund.py tests/test_icbc_refund_pairing.py tests/test_import_scan_refund_boundary.py tests/test_platform_refund_matchers.py tests/test_import_relation_planning.py tests/test_015_idempotency.py tests/contract/test_cash_import_dual_backend.py`，结果 `83 passed, 4 skipped`。
