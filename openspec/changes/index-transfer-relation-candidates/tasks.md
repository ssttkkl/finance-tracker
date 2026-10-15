## 1. 思考

- [x] 1.1 阅读项目上下文、交易关系主规格、账单导入变更、候选索引及转账扫描实现。
- [x] 1.2 用真实导入确认故障：约 2,500 条待导入行、约 6,322 条既有现金流水；首次预览超时 360 秒，候选索引使预览降至约 51 秒；提交超时 360 秒且后端继续满载，数据库没有新增行。
- [x] 1.3 确认范围：保留现有关系匹配语义，只优化候选查找；不改账单行、账户映射和数据库内容。
- [x] 1.4 根据真实提交路径扩展范围：优化缓存关系计划的重复投影校验，保留待审核候选独立检查与原持久化顺序。

## 2. 计划

- [x] 2.1 记录候选索引、原规则最终复核和选中入账流水发现既有出账流水的设计。
- [x] 2.2 Cross-platform Impact Check：Compose Web、Native 均受益于共用 API 的预览性能改进；共享层 API 合同不变；无需页面、功能地图或跨端 TODO 修改。
- [x] 2.3 Hallmark 与 UI 设计审查不适用：没有 UI、文案或交互变更。
- [x] 2.4 记录缓存计划提交前与重协调后校验的共享条件及待审核候选边界。

## 3. 任务拆分与一致性

- [x] 3.1 先添加转账候选索引边界与关系结果回归用例，并确认新用例因索引缺失或全量扫描而失败。
- [x] 3.2 先添加规划器完整投影校验复用回归用例，并确认相同已确认候选批次被重复校验。
- [x] 3.3 对照 proposal、design、任务和交易关系主规格，并结合受影响测试，确认未引入匹配规则或财务语义变化。
- [x] 3.4 为缓存计划合法性检查和重协调后的持久化添加调用次数回归用例，并确认旧实现会逐候选重复校验。
- [x] 3.5 添加现金投影关系边按组件分配的回归用例，并确认旧实现会把完整关系列表传给每个组件。
- [x] 3.6 添加待审核候选局部投影校验回归用例，确认候选只包含其触及的已确认连通组件，并验证局部/完整图的投影校验结果一致。
- [x] 3.7 为规范付款镜像替换批次添加一次性投影校验调用次数回归用例。

## 4. 构建

- [x] 4.1 为标准转账和购汇实现时间有界的候选索引。
- [x] 4.2 改用索引候选运行 Phase C，并预先计算所选正向流水标识。
- [x] 4.3 在关系规划阶段复用已确认候选批次的完整投影校验结果；待审核候选保留独立校验。
- [x] 4.4 优化缓存计划合法性检查与重协调后持久化的重复投影校验；真实账单提交仍超过 7 分钟，确认剩余热点位于现金投影构建。
- [x] 4.5 按连通组件分组投影关系边，消除每个组件扫描完整关系集合的重复工作。
- [x] 4.6 对待审核候选只重建其触及的已确认关系组件，仍对每个候选独立验证。
- [x] 4.7 对规范付款镜像替换批次只校验一次，并在逐条持久化期间复用结果。
- [ ] 4.8 单独重跑真实工行信用卡大账单尚未完成。本次最终恢复基线并只重导支付宝，避免重复提交既有银行流水；此前 8 份现金账单批量扫描和预览记录见 `exclude-unallocated-components-from-import-relations` 4.2。最终库已核实工行信用卡 2,847 条。若需要关闭此性能验收，应在隔离副本上单独重扫并记录耗时，不要对正式库重复提交。

## 5. 审查

- [x] 5.1 复核候选边界、选中入账端、唯一性与确定性结果；既有规则由边界及双向唯一性回归保护。
- [x] 5.2 独立复核完整现金导入相关差异、索引候选和批次提交边界；无新增 finding。

## 6. 测试与 QA

- [x] 6.1 受影响六文件回归 317 passed、18 skipped；全量 SQLite + 专用 PostgreSQL `_test` 回归 1,815 passed、2 skipped、3 failed。三项失败分别为 PostgreSQL 关联编辑性能 p95 101.44 ms（100 ms 门槛；隔离重跑的 SQLite/PostgreSQL 两参数均通过）、两项旧 Mobile CI 测试读取已移除的工作流/旧版文档。`openspec validate --all --strict`、`openspec doctor` 与 `git diff --check` 的本轮最终结果见下方验证记录。
- [x] 6.2 真实账单 8 文件批量 API 预览为 7,342 行、6 条待分摊、0 条未解析/不支持、280 条关系候选；最终支付宝修复批次 1,366 新增、0 更新、0 跳过。未改 UI，浏览器视觉 QA 不适用。
- [x] 6.3 不改 schema、SQL 或持久化合同；仍通过要求 `_test` 专用库的全量双后端回归，结果和单项性能隔离重跑记录见 6.1。

## 7. 发布

- [x] 7.1 验证记录：`HEAD=47b713c`，分支 `enhanced-ape`；全量回归于 2026-10-15（Asia/Shanghai）完成。最终 OpenSpec 与差异检查结果见下方。残余项为 4.8 的隔离 ICBC 性能重扫和全量回归列出的既有失败。
- [x] 7.2 本次未提交或推送；当前分支不是先前授权的 `main`，不扩大目标分支授权。

## 8. 反思

- [x] 8.1 防复发由转账候选索引窗口边界、选中正向端点、完整批次校验复用和真实预览/导入审计共同保护；未新增领域术语。

### 验证记录

- 时间：2026-10-15 05:19 Asia/Shanghai；基线 `HEAD=47b713c`。`openspec --version` → 1.7.0；`openspec validate --all --strict` → 31 passed、0 failed；`openspec doctor` → Root OK；`git diff --check` → 通过。
- 受影响测试：`PYTHONPATH=tests uv run --offline pytest -q tests/test_transaction_relations_cross_batch.py tests/test_cash_import_wizard.py tests/test_import_relation_planning.py tests/test_convert.py tests/test_cash_projection.py tests/test_transfer_phase_c.py` → 317 passed、18 skipped（当时未配置 PG）。
- 全量测试：`PYTHONPATH=tests FT_REQUIRE_TEST_POSTGRES=1 FT_TEST_POSTGRES_URL=<专用 finance_tracker_test 数据库 URL> uv run --offline pytest -q` → 1,815 passed、2 skipped、3 failed、5 subtests passed；其中 PostgreSQL 性能用例单次 p95 超门槛 1.44 ms，隔离重跑同一用例 SQLite/PostgreSQL 两参数均通过；两项 Mobile CI 测试因 HEAD 已移除其所要求的旧工作流和旧文档而失败，非本次改动文件。
- 隔离性能重跑：`PYTHONPATH=tests FT_REQUIRE_TEST_POSTGRES=1 FT_TEST_POSTGRES_URL=<finance_tracker_test URL> uv run --offline pytest -q tests/test_cash_projection_performance.py::test_fixed_10k_cash_related_delete_modes_meet_100ms_budget` → 2 passed（SQLite、PostgreSQL 各一组参数）。
- 残余风险：真实银行账单已经导入，但本次没有单独重扫 ICBC 大账单；不对正式库重复导入。Mobile CI 两项旧断言仍与当前退役状态不符。未提交、未推送。
