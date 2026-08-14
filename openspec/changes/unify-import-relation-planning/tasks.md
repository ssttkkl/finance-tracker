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

## 6. 审查与验证

- [x] 6.1 运行受影响 Python 单元、应用服务、关系和导入集成测试，修复失败回归并记录证据
- [x] 6.2 运行 Web Vitest、TypeScript 检查、生产构建和 Playwright；覆盖主流程、错误/空状态、关键键盘焦点及 390 / 1440 px 视口
- [x] 6.3 使用真实浏览器验证 Web 四步导入：选择文件、账户映射、流水预览、关系确认、拒绝/跳过和成功结果；记录 URL、视口、截图、控制台/网络错误
- [x] 6.4 运行 SQLite 导入关系契约矩阵，覆盖精确金额、来源快照、幂等、拒绝、回滚和投影一致性
- [ ] 6.5 准备名称以 `_test` 结尾的专用 PostgreSQL 数据库，配置 `FT_TEST_POSTGRES_URL` 并补跑同一契约矩阵；未配置时准确记录未完成和补跑条件
- [x] 6.6 运行 `openspec validate --all --strict`、`openspec doctor`、`git diff --check`，并完成范围、工程、安全和最终 diff 复核
- [x] 6.7 在本文件记录实际命令、结果、当前 `HEAD`、比较基线、执行时间、审查 finding、残余风险和回滚观察项

## 验证证据

- 基线：比较基线和当前 `HEAD` 均为 `58041150dd4eec76ee3509fbefe308d08d070771`；工作树中保留了既有未跟踪的 `.codex/worktrees/`、`.vite/`、`docs/superpowers/plans/2026-08-01-icbc-refund-pairing.md` 和 `web/.lan-vite.config.mjs`，未纳入本变更。
- Python：`uv run pytest -q tests/test_import_relation_planning.py tests/test_import_scan_refund_boundary.py tests/test_import_no_relation_write.py tests/test_relations_pipeline_order.py tests/test_transaction_relations_refund.py tests/test_cash_import_wizard.py` 通过 63 项；`uv run pytest -q` 通过 1461 项、跳过 177 项，另有既有 `tests/test_wealth_performance.py::test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets[sqlite]` 因 SQLite 冷重建 p95 为 5.165 s 超过 5 s 门槛而失败，与本变更无关。
- Web：`npm run build` 通过；`npm test -- --run` 为 106 项通过、1 项既有 `AccessApp.test.tsx` 导航异步断言失败；`FT_E2E_WEB_PORT=5194 npm run test:e2e` 为 26/27 通过，四步导入确认和 320/375/414/768/1440 px 无横向滚动通过，唯一失败为既有暗色导航颜色数量断言；`FT_PREVIEW_WEB_PORT=5193 FT_PREVIEW_API_PORT=8769 npm run test:preview` 7/7 通过。测试 URL 为 `http://127.0.0.1:5194/`、`http://127.0.0.1:5194/cash-import` 和 `http://127.0.0.1:5193/`；未产生控制台或网络错误报告，导入页面截图保存为 `/tmp/cash-import-production-1440.png` 和 `/tmp/cash-import-production-390.png`。
- OpenSpec：`openspec validate --all --strict` 为 26 项通过、1 项失败；失败为既有 `change/cloudflare-access-web-deployment` 缺少 delta，`openspec validate cloudflare-access-web-deployment --type change --strict` 已确认是该变更自身的 `No deltas found`，本变更 `unify-import-relation-planning` 通过。`openspec doctor` 通过，`git diff --check` 通过。
- 数据库：SQLite 关系导入矩阵已运行；环境未配置 `FT_TEST_POSTGRES_URL`，因此 PostgreSQL `_test` 数据库契约矩阵未运行，补跑条件为准备名称以 `_test` 结尾的专用数据库后设置该变量并重跑同一矩阵。
- 审查：范围复核确认 Web 不再直接编排 `run_relation_phases`，CLI `RelationService.check()` 与导入确认均经过共享 `RelationPlan`；工程复核确认预览只读、确认摘要陈旧失败和同一 UoW 投影刷新；安全复核确认用户决定使用稳定 `proposal_key`，候选端点不匹配时返回 `import_relation_candidate_invalid`。未发现本变更范围内的阻断性 finding。
- 发布与回滚：未提交、未推送、未部署；当前工作树是可回滚状态。若需回滚，恢复本变更涉及的应用服务、路由、Web API 字段和关系规划器改动即可，不触及既有账本数据迁移。
