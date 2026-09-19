## 1. 思考

- [x] 1.1 阅读 `openspec/project-context.md`、现金导入规格、词表、现有导入服务、解析器和测试，确认根因是自动识别把完整解析当格式探测。
- [x] 1.2 通过需求澄清确认：无密码工行 PDF 应进入账户映射；密码错误、格式错误和解析失败必须可重试；自动识别由解析器提供快速格式判断；取消/重选、SQLite、构建和浏览器流程需要回归。
- [x] 1.3 比较“服务层 PDF 特判”和“解析器自带 `can_parse`”两种方案，采用后者，避免银行格式知识泄漏到应用服务层。

## 2. 计划

- [x] 2.1 完成 `proposal.md`、`design.md` 和 delta spec：定义 `can_parse` 契约、唯一匹配规则、密码错误边界和非目标。
- [x] 2.2 记录风险：探测误判、密码 PDF、零/多匹配、旧测试替身兼容；明确不修改金额、来源快照、映射和持久化语义。
- [x] 2.3 完成 Cross-platform Impact Check：Web 和现有 Native 导入页均受共享扫描 API 影响；本次不改 Native UI，Native 真机 QA 作为未完成的残余风险记录；共享层只复用既有错误和响应合同。
- [x] 2.4 UI 原型门禁：不适用。本变更不新增或调整页面结构、样式、控件和可见文案；最终 UI 形成后无需 Hallmark audit，但仍需浏览器验证行为。

## 3. 任务拆分与一致性

- [x] 3.1 将任务拆为“失败回归测试 → 解析器探测契约 → 自动识别两阶段流程 → 格式探测实现 → 浏览器 QA → 发布记录”。
- [x] 3.2 对照 delta spec 检查场景覆盖：唯一匹配、工行信用卡、工行借记卡、零/多匹配、密码错误和唯一匹配后的解析失败。

## 4. 构建（TDD：先红后绿）

- [x] 4.1 先新增失败回归测试：带 `can_parse` 的解析器必须先被全部探测，唯一匹配时只完整解析一次，零/多匹配时完整解析次数为零；初次运行按预期失败。
- [x] 4.2 为 `StatementParser` 增加 `can_parse(command) -> bool` 契约，并让内置现金来源都提供不执行完整解析的格式判断。
- [x] 4.3 为 PDF 增加有界的首页 word stream 探测；工行信用卡与借记卡探测互斥，并沿用统一密码异常。
- [x] 4.4 重构现金导入自动识别为“先探测、后唯一解析”，同时保留仅供旧注入式测试替身使用的兼容回退。
- [x] 4.5 新增回归测试转绿，并补齐现有工行解析器、现金导入和密码流程测试；真实样本验证为信用卡/借记卡各唯一匹配。

## 5. 审查

- [x] 5.1 产品/范围复核：确认没有把服务层特判、文件名猜测或多解析器完整尝试留在生产主路径。
- [x] 5.2 工程复核：独立审查发现扩展名依赖、探测边界、异常传播和新路径密码测试 4 项 Important；已逐项修复并补测，最终未发现阻断项。
- [x] 5.3 安全复核：确认密码不写日志/响应/持久化，探测不泄露原始账单内容和解析器异常。
- [x] 5.4 最终 diff 复核：确认 artifact 与实现一致，无金额/来源/映射/事务回归；独立审查提出的 Native 影响记录也已回写，测试覆盖每项 requirement。

## 6. 测试与 QA

- [x] 6.1 运行受影响 Python 测试、工行解析测试和现金导入测试；记录命令、结果、当前 `HEAD`、比较基线和时间；补正后全量回归为 `1515 passed, 158 skipped, 52 deselected`。
- [x] 6.2 运行 TypeScript/Vitest、生产构建和适用的 Web 测试；确认服务端错误状态与既有页面合同。
- [x] 6.3 运行 OpenSpec 严格校验、`git diff --check`、编译检查和范围化 diff 复核。
- [x] 6.4 使用真实 Chromium 执行 Web QA：桌面与移动宽度，导入扫描成功进入账户映射，密码错误、解析失败、取消/重选和键盘焦点；记录浏览器、URL、视口、截图路径、控制台/网络错误和结果。Native 导入页复用相同服务端合同，但本次未执行真机 QA，残余风险已记录在 `design.md`。
- [x] 6.5 PostgreSQL 双后端：不适用。本次不改变存储行为、数据库 schema、事务或查询；`FT_TEST_POSTGRES_URL` 未配置，也没有可用的本地专用 `_test` 数据库，因此保留未运行项，残余风险为未新增数据库契约证据。
- [x] 6.6 性能/安全专项：真实样本首页探测每份约 `0.128–0.180 s`，信用卡/借记卡互斥；补充 PDF word 数量、XLS/XLSX 列数和 CSV 前缀边界，确认探测不输出敏感信息。

## 7. 发布

- [x] 7.1 已记录交付证据、已知残余风险、观察项和回滚方式；delta spec 已同步到主规格；未获用户明确授权，未执行 commit、push、PR、合并或部署。

## 8. 反思

- [x] 8.1 已将“解析器必须先快速探测、唯一匹配后才完整解析”回写到 delta spec、主规格和 design；兼容测试替身回退及 PostgreSQL 未运行条件已记录，无其他长期规则。

## 验证证据

- **基线与时间:** 当前 `HEAD` 为 `13db8ac8ae497f70a1c8fc3b6339ae113e355b7b`，工作树基线为该提交；最终验证时间为 `2026-09-20 01:57`（Asia/Shanghai）。未提交，无法用新 commit 作为变更 `HEAD`。
- **OpenSpec:** `openspec validate fix-icbc-plain-pdf-scan --strict` → PASS；`openspec/specs/statement-import/spec.md` 已合并快速格式探测 requirement。
- **Python:** 补正后 `PYTHONPATH=tests:.:src uv run pytest -q tests/test_statement_parser_probe.py tests/test_cash_import_wizard.py tests/test_convert.py tests/test_ccb_debit.py` → `274 passed, 1 skipped`；最终 `PYTHONPATH=tests:.:src uv run pytest -q -m 'not performance'` → `1515 passed, 158 skipped, 52 deselected`，耗时 `141.74 s`。针对性测试还覆盖无后缀文件、探测密码错误、探测异常停止和受控 word/column 边界。
- **静态检查:** `git diff --check`、`PYTHONPATH=tests:.:src uv run python -m compileall -q src tests/test_statement_parser_probe.py tests/test_cash_import_wizard.py` → PASS。
- **Web:** `npm ci` 使用仓库锁文件完成；`npm run test:web` → `150 passed`；`npm run build:web` → PASS；`npm run test:e2e --workspace finance-tracker-web` → `44 passed`（含 `导入处理页面可以返回重新选择、取消后再次进入`）；`npm run test:preview --workspace finance-tracker-web` → `16 passed`。真实 Chromium URL 为 `http://127.0.0.1:5174`（E2E）和 `http://127.0.0.1:5173`（生产预览）；覆盖 `320/375/390/414/768/1440 px`，导入主流程、错误/密码、取消/重选和键盘路径，相关截图包括 `/tmp/cash-import-production-1440.png`、`/tmp/cash-import-production-390.png`、`/tmp/cash-import-encrypted-password-390.png`、`/tmp/cash-import-encrypted-password-error-390.png`、`/tmp/cash-import-preview-production-1440.png` 和 `/tmp/cash-import-preview-production-390.png`；受影响导入测试控制台错误和请求失败均为空。
- **真实 PDF 样本:** 使用项目已有的临时解密流程验证 3 份账单样本：2 份仅匹配信用卡，1 份仅匹配借记卡；首页双探测耗时约 `0.128–0.180 s`，未输出账单内容或密码。
- **审查结论:** 产品/工程/安全/最终 diff 复核无阻断 finding；独立审查后补正扩展名依赖、探测边界、异常传播、密码测试和 Native 影响记录。Hallmark audit 不适用，因为没有 UI 结构、样式或可见文案变更。回滚方式为回滚应用版本，不需要数据迁移或修复。
