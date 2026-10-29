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
- 运行失败测试、对应后端验证及最终 diff 检查。

## 4. 构建

- [x] 修正失败测试夹具，不改生产逻辑。

## 5. 审查

- [x] 确认无编号场景改用银行来源，支付宝来源继续提供有效编号。
- [x] 确认生产校验、性能专用测试和用户可见行为未改动；diff 未发现阻断 finding。
- [x] 确认支付宝文档夹具按文件标记和行序生成不同的合成编号，不覆盖显式输入值。

## 6. 测试与 QA

- [x] 修复前复现 2 个代表用例：`test_reimport_of_identity_draft_preserves_its_unresolved_candidate_set` 报 `platform_transaction_id_required`；`test_preview_rejected_document_namespace_does_not_block_other_legal_rows` 报 `import_session_document_invalid`。
- [x] CI 失败清单中的受影响 SQLite 用例：26 passed，381 deselected，34 warnings，12.81 秒。
- [x] `PYTHONPATH=tests:.:src uv run pytest -q tests/test_cash_source_documents.py tests/test_cash_hotpath_equivalence.py`：65 passed，0.22 秒。
- [x] `openspec validate --all --strict`：25 passed，0 failed；`openspec doctor` 和 `git diff --check` 通过。
- [x] 不运行性能专项测试，原因：本次明确排除性能测试。
- [ ] 推送后复查 PR #104 的 SQLite、PostgreSQL 及其他非性能检查；当前等待新提交触发的 CI。

## 7. 发布

- [ ] 记录提交、比较基线、CI 结果；推送到 PR #104 的同一分支。

## 8. 反思

- [x] 通用无编号场景按真实来源合同选用银行夹具；涉及支付宝的测试由夹具保证有效编号。

## 验证证据

- 分支：`feat/alipay-transaction-identity`；本地验证时 `HEAD=b7e903a337360e64530798378585513c64e4834e`，比较基线 `origin/main=849c2a69c59509e27e5a789574646a83b92a0022`。验证时间：2026-10-29 04:53 UTC。
- 本机未配置 `FT_TEST_POSTGRES_URL`。本次只修改测试夹具，不改存储行为；PostgreSQL 合同由推送后的 PR 矩阵验证。
- OpenSpec CLI：`openspec 1.7.0`；Node.js：`v26.8.1`。
