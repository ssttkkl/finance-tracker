## 1. 思考与计划

- [x] 1.1 核对真实样本元数据与当前解析器，确认「銀行賬號」未被识别是根因。
- [x] 1.2 记录直接账户标识优先、下挂账户回退和不泄露完整账号的边界。

## 2. 测试先行

- [x] 2.1 新增去标识化回归，覆盖「銀行賬號」优先于尾号不同的「下掛賬戶」。

## 3. 构建

- [x] 3.1 修正账户标识提取顺序，并保持路由、幂等键与来源行快照边界。

## 4. 审查

- [x] 4.1 复核账户标识不会进入 `source_payload`，且回退逻辑不改变既有支持格式；未发现问题。

## 5. 测试与验证

- [x] 5.1 已运行 `FT_TEST_POSTGRES_URL=... uv run pytest tests/test_icbc_asia_current_account.py -q`（10 passed）、`uv run pytest tests/test_complete_statement_source_payload.py tests/test_convert.py tests/test_statement_import_mapping.py -q`（221 passed, 1 skipped）、`git diff --check` 与 `openspec validate prefer-icbc-asia-bank-account-identifier --strict`。

## 6. 发布准备与反思

- [x] 6.1 无需迁移；未导入真实账单；映射使用优先识别的银行账号末 4 位。当前 `HEAD`：`dd77c95ad48ff737bb25afd0e78b4c800b770b7e`；比较基线：`8c18ed7ecff6b31cd5adcc18becb4e4e09035f55`。
