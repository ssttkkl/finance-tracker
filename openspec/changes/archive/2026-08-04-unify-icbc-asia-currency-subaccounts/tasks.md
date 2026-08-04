## 1. 思考与计划

- [x] 1.1 核对工银亚洲解析器、映射路由、业务行键与对方账号候选边界。
- [x] 1.2 定义工银亚洲主账号：完整子账号去除末位；主账号用于路由，完整子账号用于幂等。

## 2. 测试与数据契约

- [x] 2.1 先添加工银亚洲多币种子账号路由到同一账户、完整子账号仍区分业务行键的失败回归。
- [x] 2.2 先添加 SQLite/PostgreSQL 对方账号回归：工银亚洲主账号可匹配币种位和扩展后缀，非工银亚洲来源不使用前缀规则。

## 3. 实施

- [x] 3.1 在工银亚洲解析器中分别保留完整币种子账号和主账号路由尾号，并更新映射和展示输入。
- [x] 3.2 在转账候选筛选中增加仅限工银亚洲转出流水的唯一主账号前缀匹配，保留既有完整账号和尾号逻辑。
- [x] 3.3 更新导入与账户标识文档，不引入数据库迁移或来源快照字段。

## 4. 审查

- [x] 4.1 完成产品/范围、工程和隐私复核：确认主账号不扩展到其他来源，且不写入关系过程数据。
- [x] 4.2 完成范围化 diff 复核，确认完整子账号只出现在测试夹具和业务行身份计算中。

## 5. 测试与 QA

- [x] 5.1 运行新增回归、受影响导入和关系测试，以及 SQLite/PostgreSQL 契约矩阵。
- [x] 5.2 运行 `compileall`、构建、`git diff --check` 与 OpenSpec 严格校验；10 万事实财富性能门禁按用户既有豁免不运行。

## 6. 发布准备

- [x] 6.1 记录主账号尾号映射配置、历史库不自动改写及备份回滚方式。

## 7. 反思

- [x] 7.1 记录“主账号前缀仅适用于工银亚洲转出流水”的防回归规则。

## 8. 同步与归档

- [x] 8.1 同步 delta 到主规格并归档变更。

## 证据与复核

- 基线与当前 `HEAD`：`a586ea1`；本次仅修改工银亚洲导入路由、工银亚洲受控关系筛选、其规格、文档与测试，不改动数据库模型、迁移、来源行快照或关系表结构。
- 测试先行：新增多币种子账号归并和主账号候选筛选回归在实现前失败；实现后 `uv run pytest -q tests/test_icbc_asia_current_account.py tests/contract/test_dual_backend_counterparty_account_transfer_matching.py` 为 `20 passed, 10 skipped`。
- 真实 PostgreSQL：清空专用 `finance_tracker_test.public` 后，以 `FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:5432/finance_tracker_test` 运行同一测试集合，结果为 `30 passed`。未触碰 `~/.ft`。
- 受影响回归：`uv run pytest -q tests/test_statement_import_mapping.py tests/test_mapping_import_dual_backend.py tests/test_postgres_statement_import.py tests/test_transaction_relations_aliases.py tests/test_transaction_relations_transfer.py tests/test_transfer_phase_c.py tests/test_relations_index_injection.py tests/contract/test_dual_backend_record_type.py` 为 `91 passed, 6 skipped`。
- 工程验证：`uv run python -m compileall -q src`、`uv build`、`git diff --check`、`openspec validate --all --strict` 和 `openspec doctor` 全部通过；10 万事实财富性能门禁按用户既有豁免未运行。
- 产品、工程与隐私复核：主账号规则仅改变工银亚洲自身路由，并只在 `icbc_asia_current_account` 的转出候选中启用；关系记录未保存账号原文、命中种类或过程字段。未发现阻断性问题。
- 范围复核：完整子账号只用于解析得到的业务行键；主账号尾号只作为 mapping 路由输入。升级既有库不改写历史流水；重建或重导时将规则改为 `icbc_asia_current_account_<主账号尾号>`。回滚时恢复旧 mapping 规则和重建前数据库备份。
- 防回归：测试覆盖币种子账号、扩展后缀、无后缀主账号和非工银亚洲来源，防止规则扩展为通用账号前缀匹配。
