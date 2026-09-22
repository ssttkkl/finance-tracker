## 1. 思考

- [x] 1.1 按 `$openspec-explore` 读取项目上下文、当前主规格、active change、领域词表和相关代码；记录本次结论：完整退款钻石是合法投影语义，自动匹配非法结果是接受边界缺少校验，不以改变匹配业务含义解决。
- [x] 1.2 用脱敏事实复现完整关系图：消费镜像 A↔C、退款镜像 B↔D、A→B 和 C→D 两条 `refund_offset`；确认投影折叠后只计算一次退款，并区分真正超额退款与不完整半图。
- [x] 1.3 盘点所有自动入口和接受路径：Phase A-D 规划、退款匹配、支付镜像 reconcile、系统升级、缓存确认、关系持久化和最终投影；标记匹配器输出边界与自动接受边界，确认人工已确认/驳回/取代关系不被自动修改。
- [x] 1.4 检查组件流水、父交易、组合支付、预览虚拟事实、`open_leg` 和 `applied_amount` 的现有语义，记录投影适配必须保留的字段与边界。

## 2. 计划

- [x] 2.1 更新 proposal：拆分“完整退款钻石投影合法”和“自动接受前合法性闸门”两部分，明确匹配规则、证据、排序和业务含义不变。
- [x] 2.2 更新 delta spec：增加完整退款钻石投影场景和自动结果告警/不展示场景；明确合法但不唯一的候选仍保持既有待审核语义，非法结果不得降级为待审核。
- [x] 2.3 更新 design：确定后置接受闸门、`ProjectionValidationSnapshot`、组件→父交易映射、预览 ID、告警脱敏、替换前验证、受影响闭包和 `open_leg` 合同；明确不自动补边、不改变对侧选择。
- [x] 2.4 完成 Cross-platform Impact Check：共享关系规划结果受影响；Web/Native/CLI 只消费过滤后的结果，合法建议保持原展示语义，非法结果不展示且无用户告警；不触发 UI 原型，因为不改变页面布局、路由或交互结构。

## 3. 任务拆分与一致性

- [x] 3.1 建立 requirement/scenario → 失败测试 → 实现任务映射，至少覆盖完整退款钻石、真正超额退款、非法自动结果告警且不展示、合法待审核候选、组件/父交易适配、替换零写入、缓存确认和双后端。
- [x] 3.2 明确内部候选稳定标识、匹配器原始结果、合法性校验结果和对外过滤结果的边界；确认预览、CLI、确认和持久化不改变既有关系语义。
- [x] 3.3 明确告警原因码、脱敏字段、规划运行/候选稳定标识去重或聚合、日志保留和工作区隔离规则；确认告警不进入 API、缓存、临时会话或用户文案。
- [x] 3.4 明确旧自动关系取代链、人工保护、幂等键、上下文 digest、来源证据和工作区边界在接受闸门与替换事务中的一致性规则。
- [x] 3.5 运行 OpenSpec 变更状态与严格校验，确认 proposal、delta spec、design 和本八阶段任务清单没有“修改匹配业务含义”“自动补边”或“向用户展示非法原因”的矛盾表述。

## 4. 构建

- [x] 4.1 先添加失败回归测试：完整退款钻石投影通过；未镜像且真正超额的独立退款仍失败；完整图和半图不会被混淆。
- [x] 4.2 添加匹配语义保持测试：同一规则版本、证据和输入下，合法结果的关系类型、对侧、证据和排序与现有基线一致；接受闸门不得补齐缺失关系或重排候选。
- [x] 4.3 添加非法自动结果测试：内部候选触发关系冲突、未折叠退款超额或投影错误时，只产生脱敏告警，不进入自动结果、`pending_review`、预览、确认请求或持久化。
- [x] 4.4 添加合法但不唯一候选测试：不违反关系图约束的多候选继续按既有规则进入待手动配对，不被自动选择、删除或重排。
- [x] 4.5 添加组件/父交易投影快照测试：组合支付、拆分流水、`cash_granularity`、`applied_amount`、时区、预览虚拟 ID 与实际 ID 的语义等价，以及缺失映射失败关闭。
- [x] 4.6 添加 `open_leg` 回归测试：保持现有锚点型待审核语义，不进入投影、不消耗退款剩余金额；绑定具体对侧后再执行完整关系图校验。
- [x] 4.7 实现统一 `ProjectionValidationSnapshot` 及组件→父交易窄适配；禁止聚合总额与单组件金额直接比较，禁止把 `preview:*` 直接转换为投影 ID。
- [x] 4.8 在所有自动接受入口接入后置合法性闸门：匹配器先按既有规则产出内部结果，闸门只验证、不补边、不换对侧、不改排序；失败写入脱敏告警并过滤。
- [x] 4.9 实现自动 reconcile/upgrade/replace 的先验证后变更顺序；完整替换图校验失败时旧关系、取代链、投影版本和占用集合必须零写入变化。
- [x] 4.10 在导入确认解析缓存计划后重新验证候选；上下文或组合法性变化时失败关闭，不替换对侧、不补关系、不拆解降级、不产生部分关系写入。
- [x] 4.11 通过现有安全诊断通道记录 `projection_invalid` 等内部脱敏原因码/计数，不扩展预览协议，不记录密码、Token、完整账号、文件路径或原始整行账单。
- [x] 4.12 为多组成项建立预览/持久化等价的 `component_ordinal` 稳定引用；保留父流水 `record_id` 作为导入幂等身份，并覆盖缓存关系计划确认回归。

## 5. 审查

- [x] 5.1 做产品/范围复核：确认完整退款钻石被视为合法，真正非法自动结果才被过滤；确认匹配条件、证据、排序、关系类型和人工待审核含义未改变。
- [x] 5.2 做工程/财务正确性复核：检查所有自动接受入口、组件/父交易适配、替换、缓存计划和最终投影使用同一合法性合同；阻断性 finding 修复后重新复核。
- [x] 5.3 做安全与隐私复核：检查投影快照、候选引用、告警、测试夹具、日志和错误响应不泄露真实账单内容；确认告警不越过工作区和用户边界。
- [x] 5.4 做跨端复核：确认 Web/CLI/Native 复用同一过滤结果和原有合法建议语义；无布局或文案变更时记录不需要原型的理由，若最终出现 UI 改动则补做 Hallmark `audit`。

## 6. 测试与 QA

- [x] 6.1 运行受影响的关系、投影、导入规划、导入会话、组件、告警和幂等测试；再运行完整 Python 测试集及相称的类型/编译检查，记录实际命令、当前 `HEAD`、基线和结果。
- [x] 6.2 运行 `git diff --check`、`openspec validate --all --strict` 和 `openspec doctor`；未运行项记录准确原因、残余风险和补跑条件。
- [x] 6.3 在 SQLite 上运行关系与导入契约矩阵，验证完整退款钻石通过、真正非法半图不落库且只告警、自动重配失败零写入、合法流水可导入、重复确认结果稳定。
- [ ] 6.4 准备名称以 `_test` 结尾的专用 PostgreSQL 测试库，显式设置 `FT_TEST_POSTGRES_URL`，补跑同一 Application Service 契约矩阵；未配置时明确记录 PostgreSQL 验证未完成，不得计为通过。
- [x] 6.5 使用真实生产预览浏览器验证导入关系建议：合法建议保持既有展示、非法自动结果不展示且无告警文案、合法待手动配对、错误/空状态、拒绝或暂不处理、键盘焦点，以及 `1440px` 和 `390px` 视口；记录 URL、步骤、截图路径、控制台/网络错误和结果。
- [x] 6.6 对最终 Web 可见结果执行适用的 Hallmark `audit`；若无视觉或交互 finding，仍记录审查范围与结论；Native 复用同一规划协议时补充可用 QA 或登记覆盖缺口。
- [ ] 6.7 用约 2,500 条事实、约 1,532 个 proposal 的去标识夹具运行规划性能基线，验证候选校验复用快照与受影响闭包，不对每个候选重复构建全库投影；同时验证告警聚合不会无界增长，且无关历史组件不会被错误归因。

## 7. 发布

- [x] 7.1 记录发布前检查、完整退款钻石自动接受成功率、非法自动结果告警率、半图 `projection.invalid_relation` 发生率、合法候选数量变化、重配失败零写入和人工配对成功率观察项；确认无需数据库迁移或外部资源写入。
- [x] 7.2 记录回滚策略：代码回滚会重新开放非法自动结果进入用户流程，不能作为安全业务回滚；如需回滚必须暂停受影响自动关系导入并安排修复版本。
- [x] 7.3 完成最终 diff、artifact 偏离、测试证据和未解决风险复核；用户已明确授权本次提交、推送和创建 PR，合并与部署未执行。

## 8. 反思

- [x] 8.1 将完整退款钻石、真正超额退款、非法自动结果只告警不展示、组件/父交易组合支付和重配零写入场景固化为去标识化长期回归夹具。
- [x] 8.2 记录“保持匹配器语义、在接受边界做合法性闸门、内部告警不进入用户合同”的可复用工程原则，防止未来规则升级再次把投影安全问题混入匹配业务含义。
- [x] 8.3 若发现历史非法活动关系需要修复，另行创建带审计、回滚和用户确认边界的 change，不在本 change 中静默处理。

## 执行记录

- 当前工作树基线与变更范围：`HEAD=f9a194b647ec1d9a8423c1f9d29c202d103d0280`，以该提交作为本次工作树比较基线；实现提交为 `bfc168d`，组件稳定引用修复提交为 `36f468b`，已推送到 `codex/prevent-illegal-auto-relations`，并创建 PR `#89` 指向 `refactor/web`。本 change 未增加数据库迁移或外部资源写入；合并与部署未执行。
- 需求与范围复核结论：完整退款钻石是合法投影语义；自动匹配器仍按原规则、证据、对侧选择和排序产出内部候选，合法性闸门只负责校验和过滤，不补边、不换对侧、不改变合法候选的待审核语义。
- 受影响测试：
  - `PYTHONPATH=tests uv run pytest -q tests/test_import_relation_planning.py tests/test_cash_projection.py tests/test_transaction_relations_open_leg.py tests/test_transaction_relations_payment_mirror.py tests/test_transaction_relations_refund.py tests/test_transaction_relations_cross_batch.py tests/test_cash_import_wizard.py tests/test_cash_import_session_service.py`：`185 passed, 21 skipped`。
- 完整 Python 集：`PYTHONPATH=tests uv run pytest -q` 首次结果 `1559 passed, 186 skipped`，仅 `tests/test_wealth_performance.py::test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets[sqlite]` 冷启动性能断言受环境噪声影响失败；隔离重跑该测试为 `1 passed, 1 skipped`（PostgreSQL 跳过）。
- 组件引用修复后的完整 Python 集：`PYTHONPATH=tests:src uv run pytest -q`：`1561 passed, 186 skipped`，仅有既有 `httpx`/Starlette 弃用警告。
  - 四个关键回归：完整退款钻石、非法自动退款过滤/告警、重复退款镜像过滤、关系类型冲突过滤：`4 passed, 1 skipped`。
  - `git diff --check`、`uv run python -m compileall -q src tests`：通过。
  - `openspec validate --all --strict`：`33 passed, 0 failed`；`openspec doctor`：Root 通过，References 无问题。
- SQLite 契约与自动接受路径：已覆盖完整退款钻石只计算一次、真正超额半图仍失败、非法自动候选不进入规划/待审核/确认/持久化、自动重配校验失败旧关系零写入、缓存确认重新校验和告警去重。
- Web 验证：`npm run test:web` 为 `151 passed`（15 files）；`npm run build:web` 通过；`npm run typecheck:shared` 的 5 个包通过。真实浏览器使用 `http://127.0.0.1:5176/cash-import`，覆盖关系建议正常/空/错误状态、自动/待审核筛选、拒绝/撤销、Enter 键焦点以及 `320/375/390/414/768/1440px`；无控制台或网络错误。截图：`/tmp/cash-import-relations-production-390.png`、`/tmp/cash-import-relations-production-1440.png`。
- Hallmark `audit`：复核 `web/src/pages/CashImportPage.tsx` 关系建议区域及对应样式；本 change 未改变布局、路由、交互结构或用户可见文案，无视觉/交互 finding。Native 复用同一过滤协议，但本次未启动 Native 客户端，保留为覆盖缺口。
- 未完成项与补跑条件：`6.4` 未完成，因为当前未设置 `FT_TEST_POSTGRES_URL`；准备名称以 `_test` 结尾的专用 PostgreSQL 数据库并设置该变量后，补跑同一契约矩阵。`6.7` 未完成，因为尚未准备约 `2,500` 条事实、约 `1,532` 个 proposal 的去标识化专项夹具；现有规划已复用事实快照并做告警有界去重，但专项基线仍需单独补跑。
- 发布与回滚观察：上线前观察完整退款钻石接受成功率、`projection_invalid` 告警率、半图 `projection.invalid_relation` 发生率、合法候选数量和人工配对成功率。代码回滚可能重新开放非法自动结果，不能作为业务安全回滚；如需回滚，先暂停受影响自动关系导入并安排修复版本。
- 增量修复与本地账单验证：发现组合支付的多组成项在预览与持久化后的稳定引用碰撞后，新增 `component_ordinal` 并保持单组成项既有引用不变；`PYTHONPATH=tests:src uv run pytest -q tests/test_import_relation_planning.py tests/test_cash_import_wizard.py tests/test_transaction_relations_cross_batch.py tests/test_transaction_relations_projection.py`：`61 passed, 14 skipped`。在隔离 SQLite 临时库和临时工作区中启动后端 `127.0.0.1:8011`、Web `127.0.0.1:5186`，使用用户提供的 6 条组成项分摊确认 4 份支付宝账单；4 份均通过缓存关系计划确认，未再出现 `import_relation_reconfirmation_required`。库内父流水/组成项计数一致性通过，6 条分摊守恒检查通过，`CashLedgerQueryService.list_cash_projections(limit=1)` 成功返回投影。
