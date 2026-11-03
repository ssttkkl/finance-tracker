# 修正支付宝交易单号测试夹具

## 1. 思考

- 已检查 PR #104（`b7e903a337360e64530798378585513c64e4834e`）的失败检查与分片日志。
- 非性能后端失败集中在测试夹具违反支付宝必填交易单号规则；生产校验符合已归档变更和主规格。
- 失败还包含未识别流水号和来源文档命名空间测试；这类测试应保留其语义，并为支付宝文档行提供有效编号。

## 2. 计划

- 按 C 类局部修复处理，只修改测试夹具和测试设置。
- 保持支付宝必填编号、跨账号匹配和整份账单拒绝规则不变；性能专用测试不在范围内。

## 3. 任务拆分

- 为无编号通用场景改用允许无平台编号的银行来源夹具。
- 为支付宝来源文档测试补充有效且互不冲突的交易订单号。
- 合并最新 `main` 后，将支付宝流水规则留在新的导入模块功能地图中。
- 运行失败测试、对应后端验证及最终 diff 检查。

## 4. 构建

- [x] 修正失败测试夹具，不改生产逻辑。
- [x] 将支付宝交易号范围和账户分组说明迁入 `docs/import/feature-map.md`，保留主分支的总览结构。

## 5. 审查

- [x] 确认无编号场景改用银行来源，支付宝来源继续提供有效编号。
- [x] 确认生产校验、性能专用测试和用户可见行为未改动；diff 未发现阻断 finding。
- [x] 确认支付宝文档夹具按文件标记和行序生成不同的合成编号，不覆盖显式输入值。
- [x] 确认旧功能地图链接改为模块目录，最新 `main` 总览及导入模块路径有效。

## 6. 测试与 QA

- [x] 修复前复现 2 个代表用例：`test_reimport_of_identity_draft_preserves_its_unresolved_candidate_set` 报 `platform_transaction_id_required`；`test_preview_rejected_document_namespace_does_not_block_other_legal_rows` 报 `import_session_document_invalid`。
- [x] CI 失败清单中的受影响 SQLite 用例：26 passed，381 deselected，34 warnings，12.81 秒。
- [x] `PYTHONPATH=tests:.:src uv run pytest -q tests/test_cash_source_documents.py tests/test_cash_hotpath_equivalence.py`：65 passed，0.22 秒。
- [x] `openspec validate --all --strict`：25 passed，0 failed；`openspec doctor` 和 `git diff --check` 通过。
- [x] 不运行性能专项测试，原因：本次明确排除性能测试。
- [x] 修复提交 `155a6668b110dc6d0405fd449e51acd7cecbdcca` 的 PR CI：SQLite 与 PostgreSQL 各 8 个分片、两个汇总检查及 Compose 两项检查均通过；`Backend (Performance)` 失败，按用户要求排除。
- [x] 合并后的工作树复验：`PYTHONPATH=tests:.:src uv run pytest -q tests/test_runtime_docs.py tests/test_cash_document_fragments.py` 为 33 passed；`openspec validate --all --strict` 为 25 passed，0 failed；`openspec doctor` 与暂存／未暂存 `git diff --check` 通过。此前已复跑受影响 SQLite 用例（26 passed）及来源文档／热路径测试（65 passed）。
- [ ] 合并到最新 `main` 后的远端 PR CI 待新提交推送触发；等待全部非性能检查通过，性能专项按用户要求排除。

## 7. 发布

- [ ] 最新 `main` 从 `849c2a69c59509e27e5a789574646a83b92a0022` 前进到 `8297427e4036ee35f5f4d01d85417f85ad4522e4` 并整理了功能地图目录；已合并到 PR 分支，冲突解决后保留主分支总览并把支付宝流水规则放入 `docs/import/feature-map.md`。
- [ ] 记录合并提交、最新比较基线和新 CI 结果；推送到 PR #104 的同一分支。

## 8. 反思

- [x] 通用无编号场景按真实来源合同选用银行夹具；涉及支付宝的测试由夹具保证有效编号。

## 验证证据

- 分支：`feat/alipay-transaction-identity`；夹具修复提交为 `155a6668b110dc6d0405fd449e51acd7cecbdcca`，其比较基线为 `849c2a69c59509e27e5a789574646a83b92a0022`。该提交 CI 运行 `40820359915`（PR Checks）和 `40820359926`（Compose CI）；SQLite、PostgreSQL、Compose 的非性能检查全部通过，性能检查失败并按范围排除。
- 最新远端 `main` 为 `8297427e4036ee35f5f4d01d85417f85ad4522e4`。本地已合并该提交；合并工作树上的文档合同测试 33 passed、受影响 SQLite 用例 26 passed、来源文档与热路径测试 65 passed，OpenSpec 校验 25 passed，doctor 与 diff 检查通过。此时 `HEAD` 仍为 `155a6668b110dc6d0405fd449e51acd7cecbdcca`，`MERGE_HEAD` 为 `8297427e4036ee35f5f4d01d85417f85ad4522e4`；合并提交和对应远端 CI 尚未完成。
- 本机未配置 `FT_TEST_POSTGRES_URL`。本次不改存储行为；修复提交的真实 PostgreSQL CI 矩阵已通过。合并后的远端 PostgreSQL 与 Compose 验证待新 CI 完成。
- OpenSpec CLI：`openspec 1.7.0`；Node.js：`v26.8.1`。
