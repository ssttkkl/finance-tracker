## 1. 思考与计划

- [x] 1.1 记录同长度末位 `0` 规范账号的术语、范围和不变量。

## 2. 测试与数据契约

- [x] 2.1 先修改工银亚洲解析与双后端导入测试，使其断言 `...0`、`...1` 均产生 `...0` 的路由尾号，且完整账号继续区分业务行键。
- [x] 2.2 先修改 SQLite/PostgreSQL 关系契约，覆盖规范账号、扩展后缀、截断账号和非工银亚洲来源。

## 3. 实施

- [x] 3.1 修改工银亚洲账号标准化、路由尾号和展示输入，不改写完整账号业务行键或来源行快照。
- [x] 3.2 修改仅限工银亚洲的对方账号候选筛选，使用规范账号前缀并拒绝截断号码。
- [x] 3.3 同步词表、主规格和导入文档，并备份后修正 `~/.ft/mapping.yaml` 的来源键。

## 4. 审查

- [x] 4.1 复核产品范围、账号隐私、幂等边界和非工银亚洲隔离，记录发现与结论。

## 5. 测试与 QA

- [x] 5.1 在 SQLite 与本地 PostgreSQL 运行受影响导入和关系契约测试。
- [x] 5.2 对 `~/.ft/bills` 的工银亚洲账单执行幂等复跑，确认不产生新事实。
- [x] 5.3 运行相称回归、`compileall`、构建、`git diff --check` 和 OpenSpec 严格校验；10 万事实财富性能门禁按既有用户豁免不运行。

## 6. 发布准备

- [x] 6.1 记录本地映射备份路径、回滚方式和未变更的历史事实。

## 7. 反思

- [x] 7.1 用回归测试固化“末位标准化而非截断”的规则。

## 8. 同步与归档

- [x] 8.1 将 delta 同步到主规格；完成后才归档本变更。

## 证据与复核

- 基线与当前 `HEAD`：`a586ea1`；本次只更正工银亚洲规范账号、受控对方账号筛选、相关规格、文档和测试，不改动数据库 schema、正式事实、完整子账号业务行键或来源行快照。
- 测试先行：更新解析和映射回归后，旧实现把 `…74240` 和 `…74241` 错误产生 `7424`，导致 7 项失败；末位置零实现后，`uv run pytest tests/test_icbc_asia_current_account.py tests/contract/test_dual_backend_counterparty_account_transfer_matching.py -q` 为 `22 passed, 10 skipped`。
- SQLite 与 PostgreSQL：以本地 `finance_tracker_test` 运行工银亚洲和对方账号契约为 `32 passed`；重建其 `public` schema 后运行 `tests/test_mapping_import_dual_backend.py` 为 `2 passed`。SQLite 受影响关系与映射回归为 `67 passed, 5 skipped`。组合运行时，映射 Alembic 测试与前序直接建表测试共享同一 PostgreSQL schema 而产生初始化冲突；分组重置 schema 后均通过，非产品回归。
- 本地账本：备份 `/Users/huangwenlong/.ft/mapping.yaml.before-canonical-account-20260804-103613` 后，将来源键从 `icbc_asia_current_account_7424` 改为 `icbc_asia_current_account_4240`。6 份 `currentaccounthistory*.csv` 均复跑为“该账单已导入”；数据库完整性检查通过，66 条工银亚洲流水仍全部归入 `工银亚洲账户(4240)`。
- 工程检查：`uv run python -m compileall -q src`、`uv build`、`git diff --check`、`openspec validate normalize-icbc-asia-currency-subaccounts --strict`、`openspec validate --all --strict` 和 `openspec doctor` 通过。10 万事实财富性能门禁按用户豁免未运行；完整套件的长运行无法由当前执行器保留最终退出码，未记作完整回归通过。
- 产品、工程与隐私复核：未发现阻断性问题。完整子账号仍仅保留于来源元数据和业务行键；规范账号只提供路由尾号；账号家族筛选仅限 `icbc_asia_current_account` 转出流水，且关系不持久化账号原文或命中过程。
- 回滚：恢复上述映射备份即可恢复本地路由配置；本次未修改 `cash_transactions`，不需要数据库回滚。
