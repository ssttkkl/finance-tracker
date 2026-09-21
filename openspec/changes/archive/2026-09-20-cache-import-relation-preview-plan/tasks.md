## 1. 思考与范围锁定

- [x] 1.1 记录现有预览/确认二次规划、`import_relation_preview_stale` 出口和候选校验短路的可复现根因。
- [x] 1.2 记录已确认的产品边界：导入会话内账单、解析结果和账户映射冻结，只有关系上下文变化时回到配对确认。
- [x] 1.3 记录 `grill-me` `/grilling` session 在当前运行时不可调用，并将用户已确认的目标、非目标、验收和风险写入 artifacts。
- [x] 1.4 运行变更前 OpenSpec、受影响 Python/Vitest 基线并记录当前 `HEAD`、比较基线和工作树状态。

## 2. 合同与测试先行

- [x] 2.1 编写并运行失败回归：预览生成的关系计划由导入会话保存，确认命中计划时不再次调用候选规划器。
- [x] 2.2 编写并运行失败回归：现有可配对流水或关系改变后，确认返回 `import_relation_reconfirmation_required`、不写入流水/映射/关系/投影，并可重新预览。
- [x] 2.3 编写并运行失败回归：有效 `proposal_key` 加入不属于建议的对侧流水必须失败关闭；合法缓存候选、跳过和拒绝决定仍可保存。
- [x] 2.4 编写并运行 Web 失败回归：重新确认结果会刷新预览、清除旧决定、回到配对步骤并展示业务文案。
- [x] 2.5 为缓存缺失、会话过期、重复确认和幂等重试补齐错误/恢复合同，确保它们不被误标为关系重新确认成功。
- [x] 2.6 编写并运行 Web 失败回归：成功态从第一步重新开始会丢弃已完成会话，使用者必须重新选择文件。

## 3. 预览计划与确认事务

- [x] 3.1 在导入会话中加入服务端拥有的预览配对计划序列化，使用稳定流水引用、建议键、合法候选、证据、计划摘要和关系上下文摘要；不新增账本 schema。
- [x] 3.2 将关系上下文摘要计算抽离为不生成候选的确定性校验，并保证虚拟预览流水与实际落库流水的时间和稳定身份一致。
- [x] 3.3 修改确认导入：加载缓存计划，在同一工作单元中校验计划摘要与关系上下文，命中时解析实际流水并应用缓存计划，不再重新运行配对器。
- [x] 3.4 修改关系计划应用和决定校验，逐项验证建议键、锚点、工作区和对侧候选；保留使用者可选择的既有关系种类，并继续执行金额、端点、关系图和投影不变量。
- [x] 3.5 将关系上下文失效和最终不变量冲突统一转换为 `409 import_relation_reconfirmation_required`，保证全部写入回滚且不自动替换对侧。

## 4. Web 恢复流程

- [x] 4.1 扩展 Web 导入错误类型，保留稳定错误码和导入会话令牌，区分重新确认配对与其他失败。
- [x] 4.2 修改收支导入处理页面：收到重新确认结果后使用冻结会话重新加载预览、清空旧配对决定、切换到配对步骤并聚焦可继续操作的控件。
- [x] 4.3 将用户文案统一为“相关流水已变化，请重新确认配对。”，删除旧的“配对建议已经变化，请重新预览账单。”恢复路径。
- [x] 4.4 审计旧导入抽屉或直连入口，确保其不会绕过会话缓存合同；若仍可达，则路由到相同恢复流程或以受控方式淘汰。
- [x] 4.5 修复成功态步骤导航：从第一步开始新导入时清除已完成令牌和所有会话派生状态，不改变尚未完成会话的“上一步”体验。

## 5. 范围、工程、安全与设计审查

- [x] 5.1 完成范围复核：确认未改变匹配规则、CLI 语义、账户映射流程或账本 schema。
- [x] 5.2 完成工程与安全复核：检查会话/工作区隔离、输入篡改、幂等、事务回滚、敏感来源数据和 SQLite/PostgreSQL 一致性。
- [x] 5.3 按 `docs/ui-design-rules.md` 完成可见文案必要性、术语和响应式影响审查，并运行 Hallmark `audit`；记录 finding 和修复。

## 6. 验证与浏览器 QA

- [x] 6.1 运行受影响 Python 单元、导入、关系、会话和 API 契约测试；再运行完整 Python 回归、`compileall`、`git diff --check`。
- [x] 6.2 使用同一 Application Service 在 SQLite 和名称以 `_test` 结尾的真实 PostgreSQL 库运行缓存命中、关系失效、候选篡改、幂等和回滚矩阵。
- [x] 6.3 运行 Web Vitest、TypeScript 检查和生产构建。
- [x] 6.4 使用生产预览真实浏览器完成正常导入、关系变化重新确认、空配对、已完成会话重新开始和会话错误状态；检查键盘焦点、网络/控制台，并在 1440 px、390 px（及适用 320/375/414/768 px）记录截图。
- [x] 6.5 运行 `openspec validate cache-import-relation-preview-plan --strict`、`openspec validate --all --strict` 和 `openspec doctor`，把实际命令、结果、当前 `HEAD`、比较基线和未解决风险写入本文件。

## 7. 发布准备

- [x] 7.1 记录无数据迁移的发布顺序、会话缓存兼容性、回滚步骤和观察项。
- [x] 7.2 未获新的明确授权前不提交、推送、创建 PR、合并或部署；若获授权，按小步验证后执行并记录目标。

## 8. 反思与防复发

- [x] 8.1 记录预览/确认不得重复运行用户已核对的规划器这一防复发原则，以及候选校验不得仅依赖建议键的回归覆盖。

## 实施、审查与验证证据

- 基线：`HEAD` 为 `5e8aff24cc6f3d02cb047614498c4cbe63f7e00e`，与 `refactor/web` 的比较基线为 `efa313b3e604b38c0dbbd65582da8d84be55ef1e`。变更前已运行 `uv run pytest -q tests/test_cash_import_wizard.py tests/test_cash_import_session_service.py tests/test_import_relation_planning.py`（31 passed）和 `cd web && npm test -- --run tests/CashImportPage.test.tsx`（13 passed）。当前工作树仅含本变更文件，未混入范围外修改。
- 思考与澄清：当前运行时未提供可调用的 `grill-me` `/grilling` session，无法执行该强制技能；已将使用者确认的行为、范围、非目标、失败出口和风险完整写入本 change。使用者明确要求：正常 Web 会话只在预览运行一次配对器，确认只验证外部关系上下文，失效时回到配对确认；CLI 保持原行为。
- 测试先行：新增缓存命中不重跑规划器、关系上下文变更零写入与刷新、候选篡改失败关闭、拒绝可审计、映射变更失败关闭、缺少预览失败关闭、成功态重开清会话等回归。`tests/contract/test_cash_import_dual_backend.py` 覆盖 SQLite 和 PostgreSQL 的命中、幂等、失效和候选篡改；`web/tests/CashImportPage.test.tsx` 覆盖自动刷新、决定清空、焦点与成功后重新开始。
- 工程/安全复核（实现后独立范围化复核）：缓存仅保存在服务端导入暂存区，计划使用稳定来源引用；确认先锁定工作区并校验外部上下文、计划摘要、映射摘要和使用者决定，再写入账户、映射、流水、关系和投影。审查发现并修复两项阻断问题：成功态可复用已完成令牌导致 `import_session_not_found`；无预览确认可回退到旧二次配对路径。候选验证不再只凭 `proposal_key` 短路。未发现剩余 critical/major finding。
- 范围复核：未修改退款、同笔支付、转账或还款规则；CLI 仍走原有一次命令导入；无表结构或迁移。旧 `ImportDrawer` 仅为未连接会话的孤立组件，实际应用路由 `/cash-import` 已使用会话 API，未形成可达的绕过入口。
- UI/Hallmark 审查：已按 `docs/ui-design-rules.md` 和 Hallmark `audit` 清单检查本次恢复与重新开始状态。新增可见业务文案仅为“相关流水已变化，请重新确认配对。”；恢复后焦点进入“配对”标题；390 px 和 1440 px 无页面横向溢出。未发现 critical 或 major finding；没有增加教学区、重复说明或实现术语。
- SQLite 与 PostgreSQL：`FT_TEST_POSTGRES_URL='postgresql+psycopg://postgres@127.0.0.1:55432/finance_tracker_test' FT_REQUIRE_TEST_POSTGRES=1 uv run pytest -q tests/contract/test_cash_import_dual_backend.py` → `13 passed in 7.18s`。测试库名以 `_test` 结尾，使用专用本地 Docker PostgreSQL；SQLite 同一矩阵已在完整回归中执行。
- 最终测试：`uv run pytest -q` → `1518 passed, 183 skipped, 1 warning in 387.36s`；受影响集合 `86 passed, 11 skipped, 1 warning in 18.53s`；`uv run python -m compileall -q src` 通过；`cd web && npm test -- --run` → `127 passed`；`cd web && npm run build`（`tsc -b && vite build`）通过；`git diff --check` 通过。唯一警告为 Starlette `TestClient` 对 `httpx` 的上游弃用提示，未由本变更引入。
- 浏览器 QA：在生产构建预览 `http://127.0.0.1:5175` 和专用 SQLite 数据库上，以合成 CSV `tests/fixtures/cash_import_browser_refund.csv` 走完创建账户、自动退款冲销预览、确认导入（`POST /cash-import/commit` 200）、空配对重复导入、成功态从第 1 步重新开始及重新选择同一账单。关系上下文变更试验得到预期 `409 import_relation_reconfirmation_required`，随后同一令牌 `POST /cash-import/preview` 200，页面保留在“配对”、清空旧决定、显示业务文案并聚焦配对标题。控制台除该预期 409 的浏览器资源记录外无错误；成功和重新开始流程无控制台错误。测试时新注册的合成账号曾因旧 URL 指向无权工作区收到 403，创建其专用测试工作区后不影响导入流程。截图：`/tmp/ft-import-reconfirm-qa.okxbQV/reconfirm-relations-1440.png`、`reconfirm-recovery-1440.png`、`reconfirm-recovery-390.png`、`restart-preview-1440.png`、`restart-preview-390.png`；已检查 320、375、390、414、768、1440 px，页面无横向溢出。
- 发布与回滚：无需迁移。发布顺序为先部署应用，再让新预览自然产生缓存计划；未确认的旧会话或缓存缺失会失败关闭并要求重新确认。回滚为恢复应用版本，暂存计划随 TTL 自然过期，已提交账本无需回迁。观察项为 `import_relation_reconfirmation_required` 的比例、预览后确认成功率、暂存读取失败率及导入确认 409 分布。当前没有提交、推送、PR、合并或部署授权，工作树保持未提交。
- 防复发：任何使用者已核对的规划器结果必须服务端持有并在确认直接应用；确认缺少计划不得回退到重新规划；`proposal_key` 只能标识建议，不能取代锚点和合法候选验证。
