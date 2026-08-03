## 1. 测试先行

- [x] 1.1 新增零金额开户提示行过滤及跨币种相同交易行的 SQLite/PostgreSQL 导入回归，使当前实现失败。

## 2. 构建

- [x] 2.1 跳过零金额开户提示行，并将已解析账单币种纳入工银亚洲业务行键，保持来源行快照不变。

## 3. 审查与验证

- [x] 3.1 复核幂等边界、来源快照、金额币种和双后端契约；未发现问题。
- [x] 3.2 已运行 `FT_TEST_POSTGRES_URL=... uv run pytest tests/test_icbc_asia_current_account.py tests/test_cli.py tests/test_complete_statement_source_payload.py tests/test_convert.py tests/test_statement_import_mapping.py tests/test_postgres_statement_import.py -q`（286 passed, 1 skipped）、`compileall`、`uv build`、`git diff --check`、`openspec validate --all --strict` 与 `openspec doctor`。

## 4. 本机数据修复与导入

- [x] 4.1 已创建并校验 `~/.ft/finance-tracker.db.before-icbc-asia-identity-repair-20260803-193234`；预检确认开户提示行 1 条、待更新业务行键 32 条，均与已复制账单唯一对应。
- [x] 4.2 已逻辑删除 1 条开户提示行，并在单一 SQLite 事务中更新 32 条业务行键；6 份账单重跑均幂等。活跃流水 66 条且业务行键唯一（HKD 29、USD 28、CNY 9）。
