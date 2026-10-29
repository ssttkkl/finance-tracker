## 1. 思考

- [x] 1.1 阅读主规格、项目上下文、解析、暂存、账户分组、来源匹配及持久绑定实现和测试；按 A 类财务身份规则变更处理。
- [x] 1.2 完成 `grill-me → grilling` 澄清；用户确认订单号跨支付宝账号匹配、编号无效时整份拒绝、重复编号沿用幂等冲突规则。
- [x] 1.3 确认只改变支付宝流水身份与编号边界；资金账户组、账户选择、微信和银行渠道合同保持原有行为。

## 2. 计划

- [x] 2.1 完成 proposal、完整修改需求 delta 和 design，明确固定支付宝交易范围与账号资金组分离，无存量迁移。
- [x] 2.2 确认来源绑定唯一键已有工作区与渠道边界，无需 schema 或迁移改动；安排 SQLite 和真实 PostgreSQL 合同验证。
- [x] 2.3 确认无待定产品决策，跨平台影响仅为共享现金导入后端合同，不改变 Compose UI、Android、iOS 的分组与选择。

## 3. 任务拆分

- [x] 3.1 覆盖必需表头、空白／占位／超长编号整份失败、跨账号重复、单文件及多文件重复和固定字段冲突。
- [x] 3.2 Cross-platform Impact Check：共享现金导入服务及 API 受影响；Compose Web、Android、iOS 的账户组和选择合同不变，无 UI QA。

## 4. 构建

- [x] 4.1 先写解析、暂存、匹配范围、冲突和 API 回归；首次运行按预期在旧行为处失败。
- [x] 4.2 支付宝表头及每条业务行编号双重校验，解析与暂存拒绝无效编号；来源匹配实体拒绝无效平台编号。
- [x] 4.3 支付宝来源流水使用工作区内固定渠道范围，账户组仍按来源账号拆分；固定字段冲突阻断重复编号。
- [x] 4.4 更新词表、功能地图、现金导入文档及导入技能参考。

## 5. 审查

- [x] 5.1 产品／范围审查：跨账号流水身份不会合并资金账户组、复用目标账户选择或迁移已有正式流水账户。
- [x] 5.2 工程审查：工作区和渠道仍进入唯一绑定键；单批冲突比较金额、币种、时间、账户、组成项、精细度及记录类型；无 schema、API 形状或其他渠道改动。
- [x] 5.3 最终 diff 审查：文件范围符合支付宝导入和事实源文档；未发现阻断 finding。
- [x] 5.4 其他渠道只读审查：当前导入来源流水号由支付宝和微信平台解析器输出；微信已要求非空 `交易单号` 并使用工作区固定范围。银行类渠道未向现金来源匹配输出 `provider_id`，使用账号范围与已验证来源证据；`txn_id` 另用于来源关系线索，不是导入幂等编号。本次未改变这些渠道。

## 6. 测试与 QA

- [x] 6.1 目标解析、暂存、范围、批次、来源匹配、资金映射和 HTTP API 回归通过；缺列、空白、占位符及 513 字符编号拒绝整份支付宝账单。
- [x] 6.2 同一导入 API 与存储合同在 SQLite、真实 PostgreSQL 执行；PostgreSQL 使用隔离 `_test` 库 `cash_import_alipay_test`，无跳过项。
- [x] 6.3 `openspec validate --all --strict`、源码 `compileall`、文档合同测试和 `git diff --check` 通过；本次无 UI 改动，不运行浏览器或原生 UI QA。
- [x] 6.4 记录测试基线、比较基线、命令和结果；本次按用户选择运行目标回归，未运行全仓库测试，未覆盖范围不作为通过声明。

## 7. 发布

- [x] 7.1 按最终实现核对 README、docs、技能参考、功能地图和词表；README 无需修改。两个完整 `MODIFIED` 需求已同步到主规格并归档；功能地图已链接到本归档任务。
- [x] 7.2 发布准备完成：新分支基于最新 `origin/main`；用户授权的顺序是先归档再推送并创建 main PR。原 PR #103 已合并，不合并或部署新 PR。无 schema 变更，回滚方式为回退本变更提交。

## 8. 反思

- [x] 8.1 已核对本次 identity 边界、文档和回归一致；其他渠道只读审查未发现需要在本次范围修正的问题，后续若扩展全渠道流水号规则，应另立需求并分别确认渠道范围与可靠性。

## 验证证据

- 分支：`feat/alipay-transaction-identity`；测试时 `HEAD=849c2a69c59509e27e5a789574646a83b92a0022`，比较基线 `origin/main=849c2a69c59509e27e5a789574646a83b92a0022`。验证时间：2026-10-29 CST。
- 双后端目标回归：`PYTHONPATH=tests:.:src uv run --offline pytest tests/test_cash_statement_rows.py tests/test_cash_statement_document.py tests/test_cash_source_scope.py tests/test_cash_source_batch.py tests/test_cash_source_matching.py tests/test_parsed_import_staging.py tests/contract/test_cash_source_storage.py tests/contract/test_cash_import_plan_api.py tests/test_cash_import_funding.py`，设置 `FT_TEST_POSTGRES_URL=postgresql+psycopg://ft_test@127.0.0.1:15439/cash_import_alipay_test`、`FT_REQUIRE_TEST_POSTGRES=1` 和测试容器提供的 `PGPASSWORD` 后，`576 passed, 0 skipped, 121 warnings`，135.92 秒。
- 范围／来源实体补充回归：`PYTHONPATH=tests:.:src uv run --offline pytest tests/test_cash_source_scope.py tests/test_cash_source_matching.py -q` → `64 passed`。
- 文档合同回归：`PYTHONPATH=tests:.:src uv run --offline pytest tests/test_runtime_docs.py tests/test_cash_document_fragments.py -q` → `33 passed`。
- `uv run --offline python -m compileall -q src` 通过；归档前 `openspec validate --all --strict` 为 `25 passed, 0 failed`，归档后为 `24 passed, 0 failed`；两个完整需求与主规格逐块比较一致；`git diff --check` 通过。
- 对 `docs/cash-import-flow.md`、`docs/cash-import-redesign.md`、`docs/cash-import-redesign-contracts.md`、`docs/feature-map.md`、`DOMAIN_GLOSSARY.md` 和两份现金导入技能文档执行本地链接／锚点检查，230 个链接全部有效。
- 剩余风险：未运行全仓库测试；本次改动涉及的 SQLite／真实 PostgreSQL 导入、来源绑定、资金组和 API 合同均已执行。测试输出中的 121 项为既有 Starlette／SQLAlchemy deprecation warnings。
