## 1. 思考与计划

- [x] 1.1 检查样本结构、现有账单解析、来源快照和账户映射边界，确认这是 A 类新增导入渠道。
- [x] 1.2 记录受限 CSV 协议、无标题时间列、文件级元数据、幂等与隐私决策。

## 2. 测试先行与一致性

- [x] 2.1 新增去标识化 UTF-16 制表符账单测试，覆盖收入、支出、完整来源行、对方账号和账户路由。
- [x] 2.2 新增重叠导出、无法区分的重复业务行、错误表头、币种和金额方向的失败回归测试。
- [x] 2.3 新增 SQLite/PostgreSQL 导入等价测试，覆盖正式字段、来源快照和幂等结果。

## 3. 构建

- [x] 3.1 实现工银亚洲活期账户 CSV 解析、严格校验、精确金额、币种和发生时间转换。
- [x] 3.2 实现来源账户尾号路由、稳定业务行键、对方账号提升和文件名来源推断。
- [x] 3.3 接入统一现金导入、银行关系渠道识别及记录类型分类边界。

## 4. 文档与规格同步

- [x] 4.1 更新导入命令与映射示例文档，说明来源名、账户路由和失败边界。
- [x] 4.2 完成主规格同步，确认无标题原始列规则与现有来源快照合同一致。

## 5. 审查

- [x] 5.1 完成产品/范围与工程复核，检查金额、时间、幂等、隐私、失败模式和回滚边界。
- [x] 5.2 完成最终范围化 diff 复核，按严重级别记录 finding 和处置结果。

## 6. 测试与 QA

- [x] 6.1 运行新增解析与导入测试、受影响转换和关系测试、`git diff --check`、`compileall`、构建与 OpenSpec 严格校验。
- [x] 6.2 在本地 PostgreSQL 专用测试库运行真实双后端矩阵，并记录命令、版本、结果和残余风险。
- [x] 6.3 运行适用的完整回归与性能检查；不适用项记录原因和补跑条件。

## 7. 发布准备

- [x] 7.1 记录映射配置前置条件、显式导入方式、回滚方法和观察项；不自动导入真实账单。

## 8. 反思

- [x] 8.1 记录本次无凭证号银行账单的防漏记规则与回归测试沉淀。

## 审查与验证记录

- 范围与工程复核：覆盖 UTF-16 制表符解析、精确金额、文件级币种、无标题时间列、来源快照、对方账号、映射路由、行级幂等、隐私和回滚。未发现 critical、major、minor finding；样本没有下挂账户尾号的实际格式差异已回写规格和设计，采用通用映射回退而非猜测尾号。
- 最终 diff 复核：仅新增工银亚洲解析、统一导入接入、银行渠道分类、测试与文档；未发现来源行快照混入派生字段、真实账单泄露或数据库迁移需求。
- 当前 `HEAD`：`dd77c95ad48ff737bb25afd0e78b4c800b770b7e`；比较基线：`8c18ed7ecff6b31cd5adcc18becb4e4e09035f55`。
- 已执行：`uv run pytest tests/test_icbc_asia_current_account.py tests/test_complete_statement_source_payload.py tests/test_convert.py tests/test_statement_import_mapping.py tests/test_postgres_statement_import.py tests/contract/test_dual_backend_record_type.py tests/contract/test_dual_backend_icbc_refund_pairing.py -q`（258 passed, 8 skipped）；`FT_TEST_POSTGRES_URL=... uv run pytest tests/test_icbc_asia_current_account.py -q`（9 passed）；`uv run pytest tests/contract tests/unit -q`（253 passed, 8 skipped）；其他应用、转换、关系、集成、财富功能分组均通过，合计证据见本次终端记录。
- PostgreSQL：仅使用本机专用 `finance_tracker_test`；最终已重建 `public` schema 并迁移至 `20260803_16`。新来源 PostgreSQL 分支、映射 PostgreSQL 用例均通过。
- 工具验证：`uv build`、`uv run python -m compileall -q src`、`git diff --check`、`openspec validate --all --strict`、`openspec doctor` 通过。
- 未完成的全套单命令：`uv run pytest -q` 被执行环境中断，已以目录和文件分组补跑受影响及主要功能回归。既有 10 万事实现金投影性能门禁与 CSV 解析无共享路径，本次不适用；若调整投影、关系索引或导入批处理性能，需补跑 `tests/test_cash_projection_performance.py` 和 `tests/test_wealth_performance.py`。
- 发布准备：先按 `docs/import-flow.md` 添加 `icbc_asia_current_account` 映射并确保目标账户支持账单币种，再显式运行 `ft import FILE --source icbc-asia-current-account`。失败不写入事实；已导入事实需要撤销时使用既有逻辑删除流程。不得自动导入 `Downloads` 或 `~/.ft/bills` 的真实账单。
