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
