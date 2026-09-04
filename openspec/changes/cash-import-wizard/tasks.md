## 1. 思考与规格一致性

- [x] 1.1 复核 `statement-import`、`transaction-relations`、`cash-ledger-browser` delta 与 `design.md` 的输入、输出、错误和确认边界一致
- [x] 1.2 在实现前确认当前 `HEAD` 为最新 `origin/refactor/web`，记录比较基线和澄清结论；中途发现远端新增 `77712be` 后再次合并并复核

## 2. UI 原型与交互合同

- [x] 2.1 完成第一版 Narrative Workflow 原型，覆盖选择文件、自动识别、标准化表格、四项摘要、自动配对、待手动配对、跳过、错误、加载、禁用和成功状态
- [x] 2.2 使用原型静态检查和最终页面 Playwright 的 320、375、414、768 px 检查，确认无页面级横向溢出；原型路径和 Hallmark 选择已写入 `design.md`
- [x] 2.3 根据用户审查 Flow-Back 重做 Index-First 原型：删除常驻解释文案、说明区和装饰卡片，保留完整标准字段，并将窄屏表格改为单条字段布局
- [x] 2.4 根据用户反馈将关系建议改为紧凑列表：全部 / 自动 / 待处理筛选、每页 20 / 50 / 100 条和分页器；非自动关系在行内选择关联类型、对侧流水或暂不处理，所有行末尾始终显示拒绝图标，拒绝后删除线弱化并用同一位置的撤销图标恢复原状态
- [x] 2.4a 根据用户反馈将第二、三步回退入口统一为显著次级按钮“上一步”，保留顶部步骤导航作为辅助跳转
- [x] 2.5 用户已确认当前原型的信息密度、步骤导航、预览字段、关系列表密度、筛选、分页、行内配对和拒绝 / 撤销图标交互（2026-08-12）；进入生产同步

## 3. 后端失败测试

- [x] 3.1 新增现金渠道自动识别测试：唯一匹配、无法识别、多渠道冲突、敏感错误不泄露
- [x] 3.2 新增预览合同测试：只返回标准化字段、四项摘要、暂不支持和预览不写入数据库
- [x] 3.3 新增确认一致性测试：摘要变化、渠道变化和已有导入关系决定会使确认边界失效或整批回滚；账户币种失败关闭由既有导入合同覆盖
- [x] 3.4 新增关系预览与确认测试：自动配对、待手动配对、手动选择和跳过；重复确认沿用现有幂等关系业务键
- [x] 3.5 使用 Docker PostgreSQL 16 的专用 `finance_tracker_test` 运行现金幂等、关系跨批次和 1k 性能契约矩阵，SQLite / PostgreSQL 均通过

## 4. Application 与 API 实现

- [x] 4.1 实现现金文件自动识别入口并复用现有声明渠道解析器和失败关闭语义
- [x] 4.2 扩展导入预览 DTO 为标准化字段表格和四项摘要，移除 Web 层对来源快照的传递
- [x] 4.3 实现不落库的关系建议计算，复用正式关系规则、候选排序、精确 Decimal 和工作区边界
- [x] 4.4 实现确认请求的摘要校验、手动配对决定校验和单事务导入 / 关系 / 投影刷新
- [x] 4.5 增加 `/detect`、预览和确认路由参数及 Web API 序列化；主流程 Playwright 覆盖三条请求
- [x] 4.6 修复关系预览的事实归一化：已存在业务行使用真实数据库事实，只有待新增业务行作为关系扫描种子；确认时忽略不包含本次新建流水的关系决定
- [x] 4.7 支持加密 PDF 导入密码：识别、预览和确认复用密码请求头，缺少密码与密码错误返回稳定脱敏错误码

## 5. Web 页面实现

- [x] 5.1 将“导入账单”入口导航到独立 `CashImportPage`，移除生产流程对 `ImportDrawer` 的依赖
- [x] 5.2 实现选择文件、自动识别和文件替换后的状态清理
- [x] 5.3 实现标准化预览表格、数量摘要、行状态、表格自身横向滚动和加载 / 错误 / 空状态
- [x] 5.4 实现关系配对步骤：自动配对只读展示、待手动配对选择对侧、跳过和返回
- [x] 5.5 实现确认导入、成功摘要、防重复提交、错误停留和返回收支账本
- [x] 5.6 补齐键盘焦点、可访问名称、按钮命中区域和 320 / 375 / 414 / 768 px 响应式样式
- [x] 5.7 原型确认后，将当前低文案、紧凑关系列表和分页设计同步到 `CashImportPage` 和生产样式；拒绝决定提交 `rejected` 状态，非自动关系默认待处理
- [x] 5.8 根据长流水浏览反馈，将第二、三步操作栏移到步骤标题下方；删除这两步内容末尾的重复操作栏，选择文件和成功页保持原布局
- [x] 5.9 在选择文件步骤增加加密 PDF 密码输入和错误状态；密码通过当前步骤的“下一步”提交，预览 / 确认阶段密码失效时回到该步骤并清空密码，密码只保存在当前页面内存

## 6. 审查

- [x] 6.1 独立进行产品 / 范围复核，确认只改收支账本、预览只展示标准化字段且手动配对可跳过
- [x] 6.2 独立进行工程 / 安全复核，确认预览无写入、确认摘要校验与事务、敏感错误不回显和双后端等价
- [x] 6.3 按 Hallmark audit rubric 审查 `CashImportPage` 与新增样式；仓库未提供 `hallmark` 可执行命令（命令返回 `command not found`），因此采用人工审查 + Playwright 作为等价证据：0 critical、0 major、0 minor，320/375/414/768 均通过
- [x] 6.4 最终 diff 复核确认生产入口不再引用 `ImportDrawer`，旧组件保留作为可回滚兼容物；OpenSpec、测试、API、回滚边界已回写
- [x] 6.5 用户确认当前原型后，执行最终 UI Hallmark audit 与截图复核：`hallmark audit web/src/pages/CashImportPage.tsx` 因仓库环境缺少命令返回 `command not found`；人工审查 + Playwright 视觉测试通过，1440 / 390 px 截图为 `/tmp/cash-import-production-relations-1440.png`、`/tmp/cash-import-production-rejected-1440.png`、`/tmp/cash-import-production-relations-390.png`，0 critical / major / minor
- [x] 6.6 顶部操作栏复核：第二、三步的操作栏均位于步骤标题和长内容之间，内容末尾无重复；移动端按钮无换行且无页面级横向溢出
- [x] 6.7 安全复核密码边界：请求头、CORS、日志、错误响应、URL、页面状态和数据库均不泄露密码；`tests/contract/test_web_api.py` 覆盖 CORS 预检

## 7. 测试、QA 与发布准备

- [x] 7.1 运行受影响 Python 测试、Web Vitest、生产构建、Playwright 主流程 / 响应式和生产预览
- [x] 7.2 运行 `openspec validate --all --strict`、`openspec doctor`、`git diff --check`、Python compileall 和 TypeScript 构建检查
- [x] 7.3 记录实际验证证据如下；完整 Python 回归首次发现两项旧性能门禁被关系扫描拖慢，已通过仅在 Web `RelationService` 组合路径启用预览扫描修复，并重跑受影响性能矩阵通过
- [x] 7.4 发布准备：无迁移；回滚入口为恢复 `CashLedgerPage` 对 `ImportDrawer` 的调用；观察识别失败、确认失败、重复导入和待手动数量；已按用户授权提交并推送到 `bill-import-multistep-flow`，PR #44 保持 Draft，未部署

## 8. 反思

- [x] 8.1 将导入会话的摘要校验、标准字段 DTO、内存关系适配层和确认事务边界沉淀到 `design.md` 与测试合同
- [x] 8.2 沉淀重复导入关系回归：纯已存在账单不得生成重复关系建议或触发投影重建，新增流水与既有流水的关系仍可确认
- [x] 8.3 沉淀加密 PDF 的缺少密码 / 密码错误回归和页面重试路径

## 9. 账户映射 Flow-Back：思考与计划

- [x] 9.1 显式运行 `grill-me` 的 `/grilling` session，锁定 6 个现金渠道、来源账户扫描、建议仅预选且不暴露规则、强制确认、数据库事实源、YAML 退出现金路径、最终事务、账户币种扩充和新账户草稿
- [x] 9.2 使用 `domain-glossary` 更新来源账户身份、账户别名、账户映射建议、账户映射确认和账单账户映射，并区分来源账户与对方账号
- [x] 9.3 更新 proposal、`statement-import` / `transaction-relations` / `cash-ledger-browser` delta 与 design，并通过严格 OpenSpec 一致性校验

## 10. 账户映射 UI 原型

- [x] 10.1 按 Hallmark `Workbench / Account Mapping Grid` 和 `docs/ui-design-rules.md` 重做 `prototype/index.html`，创建项目级原型 token 与 Hallmark 预检记录
- [x] 10.2 覆盖选择文件、正常映射、扫描中、空结果、账号冲突、币种待扩充、创建新账户草稿、流水预览、关系配对和成功状态；“确认映射”在不完整时禁用且所有预选仍需人工确认
- [x] 10.3 使用真实浏览器检查 320 / 375 / 414 / 768 / 1440 / 390 px、键盘焦点、关键点击、页面级横向溢出和按钮文字换行，并保存 1440 / 390 px 截图
- [x] 10.4 运行 Hallmark slop test、合同检查、中文文案检查和范围化术语搜索，将 finding、修复和证据回写本文件；等待用户确认原型后再实施生产 UI

## 11. 账户映射失败测试

- [x] 11.1 先添加来源账户扫描失败测试：六个现金渠道、同名不同身份、文件级身份、空身份、对方账号隔离和掩码输出
- [x] 11.2 先添加建议优先级失败测试：历史映射、唯一 `account_identifier` / `card_tail`、多目标冲突、失效账户、币种待扩充、新账户草稿和工作区隔离
- [x] 11.3 先添加预览 / 确认失败测试：映射不完整、全部预选仍需确认、文件 / 渠道 / 分组变化、映射并发变化和加密 PDF 重试
- [x] 11.4 先添加事务与幂等失败测试：首次 upsert、映射改选、账户币种扩充、新账户创建、既有行不搬迁、新行使用新映射、任一步回滚和重复确认

## 12. Persistence、Application 与 API 实现

- [x] 12.1 为 SQLite / PostgreSQL 增加 `statement_account_mappings` 迁移、模型、仓储协议和工作区范围实现
- [x] 12.2 实现来源账户提取与扫描服务，把现金解析拆成来源解析、账户扫描、映射应用和标准化预览
- [x] 12.3 实现历史映射与唯一账户别名建议、账户 / 币种验证、账户币种扩充草稿、新账户草稿和映射版本并发校验，不自动创建账户别名
- [x] 12.4 实现 `/cash-import/scan` 或等价合同，并扩展 preview / commit 的完整映射决定、重解析和脱敏错误
- [x] 12.5 将新账户、账户币种扩充、账单账户映射、现金流水、关系决定和投影刷新纳入最终单事务；既有业务行保持原账户
- [x] 12.6 将现金导入 CLI 与现金转换切换到数据库映射服务，确认新现金路径不再读取 `mapping.yaml`；投资路径保持不变

## 13. Web 四步页面实现

- [x] 13.1 用户确认原型后，把状态机调整为选择文件、映射账户、核对流水、关系配对和成功结果
- [x] 13.2 实现来源账户分组、隐形预选、系统账户选择、账户币种扩充 / 新账户草稿、完整性校验和确认映射；修改映射时清除后续状态
- [x] 13.3 实现扫描中、空结果、别名冲突、账户失效、币种待扩充、账户草稿编辑和并发失效状态，不暴露实现术语
- [x] 13.4 保留流水预览、关系配对、密码处理和最终确认行为，并补齐键盘、焦点和响应式布局

## 14. 审查

- [x] 14.1 独立产品 / 范围复核强制确认、隐形匹配规则、账户草稿、YAML 兼容边界和既有流水不搬迁语义
- [x] 14.2 独立工程 / 安全复核来源身份、脱敏、工作区、账户币种扩充、新账户原子性、并发、事务、双后端和回滚
- [x] 14.3 对最终 Web UI 运行 Hallmark `audit`，修复全部 critical / major finding 后重审；环境无 `hallmark` 可执行文件，已记录尝试并完成人工等价审查，无 critical / major finding
- [x] 14.4 最终 diff 复核 artifact 偏离、范围外改动、遗漏测试和现金路径残留 `mapping.yaml` 读取

## 15. 测试、QA 与发布准备

- [x] 15.1 运行受影响 Python 测试、SQLite 完整回归、Web Vitest、TypeScript 和生产构建
- [x] 15.2 使用显式 `_test` 数据库运行真实 PostgreSQL 同一契约矩阵，记录 URL 的脱敏形式、命令和结果
- [x] 15.3 使用生产构建和真实浏览器覆盖四步主流程、正常 / 错误 / 空状态、键盘、320 / 375 / 414 / 768 / 1440 / 390 px 与控制台 / 网络错误
- [x] 15.4 运行 `openspec validate --all --strict`、`openspec doctor`、`git diff --check` 和范围化敏感 / 术语搜索
- [x] 15.5 记录发布顺序、数据库迁移、现金路径切换、观察项和可执行回滚；未获用户授权前不提交、推送、创建 PR 或部署

## 16. 反思

- [x] 16.1 沉淀来源账户身份与对方账号隔离、建议不等于确认、匹配规则不暴露、账户草稿只在最终确认生效、数据库单事实源和既有流水不搬迁的防复发测试或规则

## 17. 账户草稿与币种扩充 Flow-Back

- [x] 17.1 根据用户确认，将现有账户缺少来源币种改为可继续的账户币种扩充草稿；“确认映射”与“下一步”均不写入账户配置
- [x] 17.2 将“创建新账户”定义为会话内新账户草稿，系统预选账户类型但允许修改；草稿信息显示在对应映射行的系统账户选择下方，不在当前行展开字段，也不做列表级总汇总
- [x] 17.3 删除映射行中的“曾确认”“账号匹配”“需要选择”等标签和匹配依据；保留来源账户证据，不暴露匹配规则
- [x] 17.4 将新账户、账户币种扩充、账单账户映射、现金流水、关系和投影纳入最终确认的同一事务，并补充并发 / 冲突回写

## 18. 支付宝组合支付 Flow-Back

- [x] 18.1 先为支付宝支付组成项写失败回归：优惠 / 抵扣 / 立减 / 券被剥离，分期归一到基础账户，同一账户重复项去重，原始 `source_payload` 不变
- [x] 18.2 为多个真实资金账户无分摊金额写失败回归：扫描返回 `import_composite_payment_unresolved`；本次后续修订将其收敛为逐行跳过，零金额纯抵扣行归入支付宝余额
- [x] 18.3 实现支付宝来源账户规范化、历史组合映射兼容和多资金账户问题行识别；保持现金路径不读取 `mapping.yaml`
- [x] 18.4 增加脱敏 API 错误和 Web 重新选择文件状态，并确认不展示猜测账户
- [x] 18.5 用 Downloads 中 6 份支付宝账单做来源分类校验：无 `&` 优惠后缀账户组、无附加项命名账户、多个真实资金账户按合同识别为问题行
- [x] 18.6 运行受影响 Python / SQLite / PostgreSQL 契约测试、Web Vitest、构建、`openspec validate --all --strict`、`git diff --check` 和安全 / diff 复核；回写当前 `HEAD`、基线、命令、结果和残余风险

## 21. 加密 PDF 识别失败修复

- [x] 21.1 先将此前账户映射兼容性改动保存到 `stash@{0}`，重启当前工作树后端与前端，并用隔离 SQLite 工作区复现加密 PDF 未输入密码的真实浏览器流程；同时用 `/Users/huangwenlong/.ft/bills` 中的用户样本只读验证真实密码可解锁，真实账单和密码不进入仓库
- [x] 21.2 根因确认：在后端找不到 `qpdf` / `mutool` 时，`decrypt_pdf` 抛出工具缺失异常，被渠道探测吞掉后错误显示为“文件识别失败”；补充缺失 `qpdf` 且文件加密时的失败回归测试，先红后绿
- [x] 21.3 让 PDF 密码异常可由 `pdfminer` 解析异常稳定归一化；缺少命令行工具时，工行现金 PDF 使用带密码的 `pdfplumber` 文本层后备，并保留 25 MiB 输出限制
- [x] 21.4 重启修复后的后端，在真实浏览器中复测未输入密码、错误密码和响应式页面状态，确认页面显示密码输入与重试，不再显示通用识别失败

## 22. 工行 PDF 解析器与选择文件步骤收敛

- [x] 22.1 先补信用卡禁止调用 `qpdf` / `mutool`、借记卡通过 `pdfplumber.open` 的失败回归，再将工行信用卡文本解析固定到 `pdfplumber`，保留投资来源的命令行解析路径；验证缺少 `qpdf` 时仍能返回稳定密码状态
- [x] 22.2 移除选择文件步骤的“重新识别”和重复“选择账单文件”操作，将底部主操作统一为“下一步”；无密码扫描成功自动进入账户映射，有密码时由“下一步”提交密码，命中本地 `/Encrypt` 标记时不提前请求扫描
- [x] 22.3 返回选择文件步骤时保留文件、扫描结果和导入令牌；有扫描结果时“下一步”直接回到账户映射，不重复上传或扫描，并补齐 Vitest 回归
- [x] 22.4 重启隔离前后端并用真实 Chromium 复测无密码、有密码、错误密码、返回后继续和 1440 / 390 px 响应式状态；记录截图、网络与控制台结果
- [x] 22.5 完成最终 diff、安全 / 设计人工复核，运行 OpenSpec、受影响 Python / Web 测试、构建与 `git diff --check`

## Verification evidence

- Current implementation evidence (2026-08-14, uncommitted worktree): `uv run pytest -q` → 1437 passed, 175 skipped, 1 warning; explicit PostgreSQL target against Docker PostgreSQL 16 database `finance_tracker_test` with `FT_TEST_POSTGRES_URL` → 70 passed, 1 warning; `cd web && npm test -- --run` → 104 passed; `npm run build` → passed; final production Chromium import / responsive QA was rerun after the large-confirmation transport and password-header changes. `openspec validate --all --strict` → 24 passed, 0 failed; `openspec doctor` → root and OpenSpec root ok; `git diff --check` → passed. `ruff` was unavailable in the environment (`Failed to spawn: ruff`). No commit, push, PR or deployment was performed in this continuation.
- ICBC parser and select-step optimization (2026-09-04, `HEAD` `efa313b`, uncommitted): targeted Python regressions `tests/test_postgres_statement_import.py tests/test_cash_import_wizard.py` → 46 passed; full Python regression → 1473 passed, 177 skipped, 2 unrelated failures (`test_application_queries.py` existing valuation expectation and full-suite SQLite 100k P95 budget); the performance test passed in isolation. Real user PDF read-only check → no-password `required`, correct password `icbc_debit`, 1206 rows and 35 tracking pairs; raw bytes contain `/Encrypt`. Web Vitest → 112 passed; production build → passed; focused import E2E and preview E2E → 2 and 2 passed; final full import E2E → 27 passed, 1 unrelated existing dark-navigation color assertion failed (`tests/cash-category-management.e2e.ts`). The new import E2E covers local password prompt, zero pre-submit scan requests, wrong-password clearing, correct-password header forwarding, return-and-next without rescan, 390 / 1440 px overflow checks, and no page errors or failed requests. Screenshots: `/tmp/cash-import-encrypted-password-390.png`, `/tmp/cash-import-encrypted-password-error-390.png`, `/tmp/cash-import-encrypted-password-1440.png`. `openspec validate --all --strict` → 26 passed, 0 failed; `openspec doctor` → ok; Python compileall and `git diff --check` → passed. The in-app browser runtime reported no available browser, so repository Playwright was used for real Chromium QA; Hallmark audit was unavailable and manual visual / security review found no critical or major finding. PostgreSQL was not applicable because no storage schema or persistence contract changed. No commit, push, PR or deployment was performed.
- Alipay combo-payment follow-up (2026-08-15, current `HEAD` `2a5dce137c7378c1a8416735b68d0e3f61aa49e3`, comparison `origin/refactor/web` `58041150dd4eec76ee3509fbefe308d08d070771`, uncommitted): `uv run pytest -q tests/test_statement_account_mapping.py tests/test_cash_import_wizard.py tests/test_complete_statement_source_payload.py tests/contract/test_web_api.py` → 93 passed, 4 skipped; focused legacy / normalization / no-write regressions → passed; `cd web && npm test -- --run` → 108 passed; `npm run build` → passed; `openspec validate --all --strict` → 25 passed, 0 failed; `git diff --check` and `python -m compileall -q src tests` → passed. Dedicated PostgreSQL 16 Docker database `finance_tracker_test` via `FT_TEST_POSTGRES_URL` and `uv run pytest -q tests/contract/test_web_api.py tests/integration/test_web_postgres.py` → 45 passed, 1 warning; temporary container stopped after verification.
- Downloads evidence: all 6 local files were parsed with `StatementParser`; the 2024–2026 five files normalized to 12 / 7 / 6 / 6 / 6 source groups with zero unresolved rows; the 2023 file had 5 unresolved rows (`账户余额&花呗分期(3期)`, `工商银行储蓄卡(3697)&账户余额` and `建设银行储蓄卡(2820)&账户余额`) while retaining 7 usable source groups and 1362 usable rows. All 6 files preserved raw `&` payment text in `source_payload`; no discount-only account group was generated. The previous whole-file blocking browser evidence is superseded by Flow-Back 19.
- Browser QA: production Vite preview `http://127.0.0.1:5173/cash-import`, isolated API stub, uploaded `/tmp/支付宝交易明细-qa.csv` copied from `Downloads/支付宝交易明细(20260512-20260812).csv`; 1440×900 and 390×844. The page stayed at “选择文件”, displayed `账单包含无法准确归属的组合支付，请拆分后重试。`, exposed no guessed account, had `document.body.scrollWidth <= window.innerWidth`, and had no console errors. Screenshots: `/tmp/cash-import-composite-1440.png`, `/tmp/cash-import-composite-390.png`.
- Full Python regression was `1465 passed, 177 skipped, 1 warning` with one unrelated existing SQLite 100k-fact performance sample at `5.014248625s` versus the `5s` P95 budget; the isolated performance test was rerun and passed (`1 passed, 1 skipped`), so this remains an environment-noise observation rather than an import failure. No commit, push, PR, merge or deployment was performed.
- Current review conclusion: product review confirmed forced mapping confirmation, hidden preselection rules, row-level draft explanations, final-confirm-only account writes, existing-row account preservation and database-backed cash paths. Engineering / security review confirmed opaque source-group identifiers, workspace scoping, optimistic mapping revisions, active-account validation, atomic account / currency / mapping / import writes and redacted errors. Design review confirmed row-level explanation placement, 840 px shell / 720 px content intent, mobile single-column layout and no page-level overflow at the exercised production widths. Hallmark `audit` remains unavailable; manual audit found no critical or major finding. The PostgreSQL migration compatibility adjustment for cash categories was required by the real backend run and is covered by migration tests.

- Account-mapping Flow-Back (2026-08-14): baseline and current `HEAD` are `c8a9fb801a82c9064b03be243325dae5ba186d13` on `clarify-income-expense-import-account-mapping`; only glossary, `cash-import-wizard` artifacts / prototype and Hallmark metadata changed, with no production source edit. `openspec validate cash-import-wizard --strict` → valid; `git diff --check` → passed. Standalone prototype script parsing and token reference check → 1 script parsed, 62 tokens declared, 49 used, 0 missing.

## 19. 组合支付逐行跳过 Flow-Back

- [x] 19.1 将多个真实资金账户的组合支付改为逐行问题收集：混合账单保留可识别分组和行级问题；整份均无法识别时继续失败关闭
- [x] 19.2 为扫描、预览、确认补充失败回归：无法识别行显示为 `unresolved`、不进入映射 / 关系 / 写入；其他流水正常导入，业务行标识保持稳定
- [x] 19.3 更新 Web 类型、状态提示和确认门禁：只有普通不可支持项阻断；无法识别项显示跳过数量并允许确认
- [x] 19.4 用最早一份支付宝账单验证 5 条问题行单独跳过，其余流水可正常预览和提交；其他 Downloads 账单无回归
- [x] 19.5 重新运行受影响测试、SQLite / PostgreSQL 契约、构建、浏览器桌面 / 移动 QA、`openspec validate --all --strict`、`git diff --check` 并记录证据

## 20. Flow-Back 19 验证证据

- Current `HEAD` `2a5dce137c7378c1a8416735b68d0e3f61aa49e3`, comparison `origin/refactor/web` `58041150dd4eec76ee3509fbefe308d08d070771`; worktree remains uncommitted and `.wrangler/` is unrelated untracked user state.
- `uv run pytest -q tests/test_statement_account_mapping.py tests/test_cash_import_wizard.py tests/test_complete_statement_source_payload.py tests/contract/test_web_api.py` → 95 passed, 4 skipped, 1 warning; `cd web && npm test -- --run` → 108 passed; `cd web && npm run build` → passed; `python -m compileall -q src tests` and `git diff --check` → passed.
- Dedicated PostgreSQL 16 container `finance-tracker-alipay-test`, database `finance_tracker_test`, URL configured through `FT_TEST_POSTGRES_URL` → `tests/contract/test_web_api.py tests/integration/test_web_postgres.py`: 45 passed, 1 warning. A direct PostgreSQL service-level mixed import then returned `scan_unresolved=1`, preview `total=2/new=1/existing=0/unsupported=1/unresolved=1`, commit `new_rows=1/skipped_rows=1`, persisted rows `1`; database reset and container stopped.
- Downloads evidence: `支付宝交易明细(20230614-20240613).csv` has 1367 rows, 7 usable source groups, 5 unresolved rows and 1362 usable rows; the other five Alipay files have 0 unresolved rows and 12 / 7 / 6 / 6 / 6 groups. Raw payment text remains in `source_payload` and no discount-only group is generated.
- Browser QA used production Vite preview `http://127.0.0.1:5173/cash-import` with isolated API stub and real copied sample `/tmp/支付宝交易明细-qa.csv`; 1440×900 and 390×844 completed select → mapping → preview → relations → confirm. Preview showed 1367 total, 1362 new and 5 unresolved; success showed 5 skipped. Both widths had `document.body.scrollWidth === window.innerWidth`, no console messages, and all stub requests returned 200. Screenshots: `/tmp/cash-import-partial-mapping-1440.png`, `/tmp/cash-import-partial-preview-1440.png`, `/tmp/cash-import-partial-success-1440.png`, `/tmp/cash-import-partial-mapping-390.png`, `/tmp/cash-import-partial-preview-390.png`, `/tmp/cash-import-partial-success-390.png`.
- Hallmark `audit` remains unavailable; manual UI audit covered the mapping warning, row status, enabled confirmation, success count, desktop sidebar and mobile header/menu. No critical or major finding remains.
- Account-mapping Flow-Back (2026-08-14): 用户确认现有账户币种不足时允许明确选择，并在对应映射行的账户选择下方以小字展示账户币种扩充草稿；账户币种和新账户均延迟到最终“确认导入”与流水同事务写入。用户同时确认删除匹配规则标签，原型本轮只更新 OpenSpec 与原型，不进入生产实现。
- Account-mapping browser QA: real Chromium covered `mapping`、`mapping-loading`、`mapping-empty`、`mapping-conflict`、`mapping-currency`、`select`、`password`、`preview`、`relations`、`success` at 320 / 375 / 390 / 414 / 768 / 1440 px → 60 / 60 state-viewport combinations passed, with zero page-level horizontal overflow, zero touch targets below 44 px and zero wrapped clickable labels. A separate 320–1920 px width sweep found 0 overflow or wrapping failures. The keyboard path reached “上一步” → disabled “确认映射” with its described reason → three account selects, and select focus rings computed as 2 px visible cobalt. The core click path changed “花呗” from unselected to selected, enabled explicit mapping confirmation, advanced through preview / relations and reached success; conflict resolution, currency expansion, loading, empty and PDF password errors were checked separately. Console errors: 0.
- Account-mapping visual QA: 1440 px screenshot `/tmp/cash-import-account-mapping-1440.png`; 390 px screenshot `/tmp/cash-import-account-mapping-390.png`. Hallmark pre-emit critique `P5 H5 E4 S5 R5 V4`; the current manual audit found no critical, major or minor finding. WCAG contrast checks: ink / paper 15.76:1, body / paper 11.26:1, neutral / paper 6.82:1, muted / paper 5.27:1, accent text / accent 5.36:1, focus / accent 3.10:1, error / paper 7.14:1. Chinese documentation review and user-visible implementation-term search passed; prototype copy does not expose table names, database IDs, source-account keys or matching-rule labels.
- Account-mapping Flow-Back browser verification: `gstack browse` opened the local prototype in real Chromium. At 320 / 375 / 390 / 414 / 768 / 1440 px the final mapping page reported zero page-level horizontal overflow and zero wrapped clickable labels. Selecting “创建新账户” displayed `将创建「花呗」 · 贷款账户 · CNY` directly below that row's account selector; its row-level “修改” action opened an in-viewport dialog and saved edited name / type. Selecting the currency-short existing account displayed `将为「招行人民币（8821）」新增 USD` directly below that row's account selector, kept “确认映射” enabled, and allowed the preview → relations → success path. No list-level commitment summary remained. Console messages: 0. Hallmark `audit` executable remains unavailable; manual audit followed `references/verbs/audit.md` and `references/slop-test.md`.
- Baseline: implementation started at `04caf0c9c412e1cc72963290a1b34968965d2515`; current `HEAD` includes the latest `origin/refactor/web` ancestor `a622bf2ede142672ac2e94f3ea75a3dddb4fca26` and the pushed wizard commits through `746a0e7f38cb56eedfe16727de0bb1aba2db03e8`.
- OpenSpec: `openspec validate --all --strict` → 20 passed, 0 failed; `openspec doctor` → root and OpenSpec root ok.
- Backend: `uv run pytest -q tests/test_cash_import_wizard.py` → 7 passed; affected import / ledger / relation suite → 33 passed, 4 skipped; SQLite + PostgreSQL targeted matrix with `FT_TEST_POSTGRES_URL=postgresql+psycopg://.../finance_tracker_test` → 6 passed; `git diff --check` and `uv run python -m compileall -q src tests/test_cash_import_wizard.py` passed.
- Web: `npm ci`; `npm test -- --run` → 47 passed; `npm run build` passed; full `npm run test:e2e` → 12 passed; `npm run test:preview -- --grep "独立导入处理页面|生产预览在窄屏"` → 2 passed; responsive import test covered 320/375/414/768 px.
- Full Python regression after the optimization → 1301 passed, 155 skipped, 2 existing SQLite performance RSS failures in the 1k / 10k cases when the entire suite shares one process. `uv run pytest -q tests/test_cash_projection_performance.py` in isolation → 12 passed, 11 skipped; the SQLite / PostgreSQL 1k matrix passed. The full-suite failures are cumulative process RSS, not import result or timing assertions.
- PostgreSQL: Docker container `quantdinger-db` PostgreSQL 16 was used; dedicated database `finance_tracker_test` was created and reset by the test fixtures. Remaining unrun condition: rerun the full suite with the same `FT_TEST_POSTGRES_URL` if full post-fix matrix evidence is required.
- Prototype v2 review: Playwright opened the prototype at 320 / 375 / 390 / 414 / 768 / 1440 px; every viewport reported zero page-level horizontal overflow and one-line step labels. The interactive path covered file recognition, preview, skipped manual pairing and success; `loading`, `error`, `unsupported` and `empty` states were opened separately. Visible explanatory paragraphs in the normal preview were `0`; the banned implementation-term scan returned no matches. Production `web/src` remains unchanged pending user approval.
- Prototype v3 review: Playwright opened the compact relation list at 390 / 1440 px; both reported zero page-level horizontal overflow. The default state showed 20 rows per page and `第 1 / 63 页`; next-page navigation changed the label to `第 2 / 63 页`, page-size `100` reduced the total to `13` pages, and the `待处理` filter kept only manual rows while retaining inline pairing / `暂不处理`. Screenshots: `/tmp/cash-import-prototype-v3-relations-1440.png`, `/tmp/cash-import-prototype-v3-relations-390.png`. Production `web/src` remains unchanged.
- Prototype v5 review: manual relation rows now offer three distinct decisions: choose a candidate (`已配对`), `暂不处理` (`待处理`), or `拒绝配对` (`已拒绝`). Playwright confirmed rejected and paired rows leave the `待处理` filter while remaining visible under `全部`; the 390 px viewport remained free of page-level horizontal overflow. Screenshot: `/tmp/cash-import-prototype-v5-rejected-1440.png`. Production `web/src` remains unchanged.
- Prototype v6 review: replaced the reject select option with a final-column icon button. Clicking it marks the pending row `已拒绝`, applies a strikethrough and muted styling, disables the candidate select, and removes the row from `待处理`; clicking the same location's restore icon returns it to `待处理`. The prototype keeps rejected rows in `全部`, preserves the compact six-column desktop table and single-row mobile layout, and production `web/src` remains unchanged.
- Prototype v6 browser verification: reject click changed the row to `rejected`, `已拒绝`, disabled its row selects, changed the accessible action to `撤销拒绝`, and reduced the `待处理` count from 52 to 51; restore returned the saved pre-rejection state. The reject / restore action remains visible for automatic, pending and accepted rows; non-automatic rows expose an inline type select while automatic rows remain read-only. Real viewports 320 / 375 / 390 / 414 / 768 / 1440 px all reported no page-level horizontal overflow. `hallmark audit openspec/changes/cash-import-wizard/prototype/index.html` remains unavailable because the repository environment has no `hallmark` executable.
- Hallmark: `hallmark audit openspec/changes/cash-import-wizard/prototype/index.html` was attempted but the repository environment has no `hallmark` executable (`command not found`); manual screenshot review plus the Playwright checks above found no critical, major, or minor UI issue. The prototype was subsequently confirmed by the user and its interaction contract was synchronized to production; final production review is recorded in item 6.5 and the evidence below.
- Production UI follow-up: `uv run pytest -q tests/test_cash_import_wizard.py` → 8 passed; `npm test -- --run` → 49 passed; `npm run build` → passed; import E2E → 2 passed; preview E2E → 1 passed; temporary visual audit → 1 passed. Screenshots: `/tmp/cash-import-production-relations-1440.png`, `/tmp/cash-import-production-rejected-1440.png`, `/tmp/cash-import-production-relations-390.png`. `hallmark audit web/src/pages/CashImportPage.tsx` remained unavailable (`command not found`), so manual review covered copy density, table state, focusable controls, rejection styling and page-level overflow.
- Top action bar follow-up: `npm test -- --run` → 76 passed; `npm run build` → passed; `FT_E2E_WEB_PORT=5175 npm run test:e2e -- --grep "独立导入处理页面|导入处理页面在四个目标宽度"` → 2 passed; `FT_E2E_WEB_PORT=5176 npm run test:e2e -- --grep "独立导入处理页面自动识别渠道并完成三步确认"` → 1 passed with real bounding-box ordering; `FT_PREVIEW_WEB_PORT=5178 npm run test:preview -- --grep "生产预览可打开流水编辑和独立导入处理页面|当前持仓在目标响应式宽度保持可见且无横向溢出"` → 2 passed; `openspec validate --all --strict` → 20 passed; `openspec doctor` → ok; `git diff --check` → passed. 第二、三步顶部操作栏均早于长内容，底部无重复操作栏；本地服务仍运行于 `127.0.0.1:8000` 和 `127.0.0.1:5174`。
- Import repeat regression follow-up: `pytest -q tests/test_cash_import_wizard.py tests/test_cash_ledger_management.py tests/test_transaction_relations_payment_mirror.py tests/test_transaction_relations_refund.py` → 100 passed, 6 skipped; `npm test -- --run` → 76 passed; `npm run build` → passed; `git diff --check`、Python compileall、`openspec validate --all --strict` → 20 passed、`openspec doctor` → ok. Three real WeChat XLSX files were replayed against a temporary SQLite copy of `/Users/huangwenlong/.ft/finance-tracker.db`: all three completed with `new_rows=0`, `preview_relations=0`, `submitted_decisions=0`; the original database mtime and size were unchanged. `FT_TEST_POSTGRES_URL` was not configured, so PostgreSQL evidence remains to be rerun with the explicit `_test` database URL.
- Encrypted PDF follow-up: `uv run pytest -q tests/test_cash_import_wizard.py tests/contract/test_web_api.py tests/test_cash_ledger_management.py tests/test_transaction_relations_payment_mirror.py tests/test_transaction_relations_refund.py` → 128 passed, 10 skipped; `cd web && npm test -- --run` → 79 passed; `cd web && npm run build` → passed; `openspec validate --all --strict` → 20 passed; `openspec doctor` → ok; `git diff --check` → passed. The password UI was checked in the prototype at 1440 px and 390 px for `pdf-password` / `pdf-password-error`: password input and retry were visible, invalid-password state cleared the value, and page-level horizontal overflow was false. Real `qpdf` 12.3.2 verification confirmed encrypted PDF behavior: no password required, wrong password failed, correct password succeeded. Hallmark audit remained unavailable because the repository environment has no `hallmark` executable; manual review plus browser checks were used. `FT_TEST_POSTGRES_URL` was not configured for this follow-up, so no new PostgreSQL run was claimed.
- ICBC parser / select-step follow-up (2026-08-15): `uv run pytest -q` → 1462 passed, 177 skipped, 1 warning; affected Python suite → 310 passed, 5 skipped, 1 warning; `cd web && npm test -- --run` → 108 passed; `npm run build` → passed; `openspec validate --all --strict` → 25 passed, 0 failed; `openspec doctor` → ok; `git diff --check` and Python compileall → passed. Isolated backend and frontend were restarted in tmux session `codex-finance-qa`; the backend used SQLite with `PATH=/usr/bin:/bin` to verify the cash path without `qpdf` / `mutool`. Real Chromium at `http://127.0.0.1:5174/cash-import` covered password-required + correct password, wrong password, decrypted no-password auto-advance, return + next without rescan, keyboard focus, and 320 / 375 / 390 / 414 / 768 / 1440 px widths. Screenshots: `/tmp/ft-browser-qa.cK8EJC/icbc-pdfplumber-mapping-390.png`, `/tmp/ft-browser-qa.cK8EJC/icbc-wrong-password-1440.png`, `/tmp/ft-browser-qa.cK8EJC/icbc-no-password-auto-mapping-390.png`, `/tmp/ft-browser-qa.cK8EJC/icbc-return-next-mapping-390.png`. All tested widths had no page-level overflow; expected password-related API 400 responses were observed, and cleared final console / network buffers had no unexpected errors. Hallmark remained unavailable; manual visual and security review found no critical or major finding. PostgreSQL was not applicable because this change does not alter storage schema or persistence contracts.
- Preview visual follow-up (2026-08-14): restored the PR 44 preview visual baseline in `web/src/styles.css`: wide import shell, raised stage surface, four summary cards, dark sticky table header, 1250 px table rhythm, row hover and contained horizontal scrolling. The current four-step account-mapping semantics remain unchanged. Real Chromium QA at `http://127.0.0.1:5189/cash-import` used 1440×900 and 390×844 viewports; screenshots are `.gstack/qa-reports/screenshots/pr44-preview-1440-viewport-final.png` and `.gstack/qa-reports/screenshots/pr44-preview-390-viewport-final.png`. Both widths had zero page-level horizontal overflow; the table overflow stayed inside `.standard-table-wrap`; after clearing the browser buffers, there were no console errors or network failures. `cd web && npm test -- --run` → 104 passed; `npm run build` → passed; the WeChat placeholder-account regression test → 1 passed; `git diff --check` → passed. Hallmark executable/action remained unavailable, so manual visual review found no critical, major or minor finding.
- Import commit projection follow-up (2026-08-14): reproduced the browser 503 with a ready SQLite projection state and four accepted WeChat refund relations; the underlying failure was `IntegrityError` on the `(workspace_id, dataset_id, projection_id)` cash projection identity, not a transient SQLite lock. During one import, relation decisions were individually maintaining the active projection and the batch-level import maintenance inserted the same endpoints again. Relation decisions now persist first and the import maintains affected projection components once after the complete decision set. New regression `test_cash_import_mapping_and_relations_rebuild_ready_projection_once` passed after the intentional pre-fix failure. Real Chromium retry against `http://127.0.0.1:5189/cash-import` returned `200` with no console errors; the isolated QA workspace contains 4 accounts, 985 cash rows, 4 accepted relations, and a ready projection with 981 visible rows / 985 members. Affected Python suites → 35 passed and 11 passed / 9 skipped; compileall and `git diff --check` passed.
- Import commit completion and idempotency follow-up (2026-08-14): the same new-account + ready-projection scenario succeeded on PostgreSQL `finance_tracker_test` with 1 account, 2 cash rows and 1 accepted relation. In real Chromium, the second WeChat bill reused 4 stored mappings plus one explicit existing-account selection, committed 1176 new rows and 7 relations with HTTP 200 and no console errors; repeating that exact bill showed `全部 1176 / 待新增 0 / 已存在 1176`, committed HTTP 200, and left the workspace at 2161 cash rows and 11 accepted relations. Full Python regression → 1433 passed, 175 skipped, 1 warning; Web Vitest → 104 passed; Web build → passed; `openspec validate --all --strict` → 24 passed; `git diff --check` → passed.
- Encrypted PDF recognition follow-up (2026-08-15): `stash@{0}` retains the four pre-existing account-mapping edits before this reproduction. With the isolated stack at `http://127.0.0.1:5174` and backend `PATH=/usr/bin:/bin` (no `qpdf` / `mutool`), real Chromium upload of a generated password-protected PDF reproduced `POST /api/v1/cash-import/scan` → `400` with the old UI message “文件识别失败，请重试。”; after the fix the same flow returned `400` with `import_password_required` and displayed “账单密码” plus “重新识别”. Entering a wrong password displayed “账单密码错误，请重试。” without exposing the password. Screenshots: `/tmp/ft-browser-qa.cK8EJC/repro-no-qpdf-before-fix.png` and `/tmp/ft-browser-qa.cK8EJC/repro-no-qpdf-after-fix.png`.
- Final-confirm-only persistence follow-up (2026-08-14): confirmed the product boundary in the service and Web flow: scan and mapping confirmation read the database, mapped preview explicitly rolls back, and account drafts, currency expansion, statement mappings, cash rows, relations and projection updates are only performed by final commit. Added regression coverage for both an existing-account currency draft and a new-account draft; `uv run pytest -q tests/test_statement_account_mapping.py::test_preview_applies_explicit_mapping_without_creating_accounts_or_expanding_currency tests/test_statement_account_mapping.py::test_preview_new_account_draft_does_not_write_account_or_mapping tests/test_cash_import_wizard.py::test_cash_import_preview_exposes_domain_relation_suggestions_without_writing` → 3 passed.
- Same-workspace full cash acceptance (2026-08-14): the 31 files under `/Users/huangwenlong/.ft/bills` were classified as 11 cash bills and 20 investment statements. All 11 cash bills were committed and then replayed in the single isolated SQLite workspace `QA 503 reproduce` (`4ec6ea32-0703-4eb0-bdcd-477e136e30ed`): 11,394 cash rows, 84 accounts, 85 statement mappings, 410 transaction relations / projection relations; channel totals were Alipay 3,059, CCB debit 952, ICBC credit 2,847, ICBC debit 1,205 and WeChat 3,331. Replay of all 11 files produced zero new rows and zero new relations, with unchanged row / relation counts. Query found zero account names containing `/`.
- Parser edge acceptance (2026-08-14): targeted regressions confirmed Alipay rows with a blank payment method use the explicit `支付宝余额` source group, WeChat transfer-in rows with `/` and `已存入零钱` use `零钱`, and ICBC credit rows with a missing card field recover the masked tail from the source snapshot; all targeted parser tests passed.
- Browser same-workspace spot check (2026-08-14): in workspace `全量账单验收 QA` (`5d87ee66-6d47-4b2e-bb38-f7b2198ef33d`), the Alipay bill committed through the UI with 1,028 rows. A fresh scan of the ICBC credit PDF reused the three persisted accounts and mapped revisions, previewed 2,500 existing rows, then confirmed through the JSON request body with HTTP 200 and displayed `0 待新增 / 0 已更新 / 2500 已存在`. The request body contained no password; `X-FT-Statement-Password` carried it. After clearing browser buffers, the final flow had zero console errors.
- Large-confirmation transport follow-up (2026-08-14): 721 relation decisions are accepted by the API contract test without serializing them into the URL; the Web test asserts the commit URL has no `relations` query and the password is not included in the JSON body. The first browser retry with an old mapping revision correctly returned a stale-mapping conflict; rescanning and confirming with the current revision succeeded, confirming the concurrency guard rather than a storage failure.

## 23. 钱包规范身份、共享新账户草稿与预览显示名称 Flow-Back

- [x] 23.1 先补支付宝 `账户余额` / `余额` / `支付宝余额`、微信 `零钱` / `微信零钱` 的规范身份失败回归，并验证历史来源键仍能命中映射、原始来源快照不变
- [x] 23.2 先补共享新账户草稿失败回归：多个来源组引用同一 `draft_id` 时预览目标一致，币种安全合并，名称冲突与草稿不一致失败关闭
- [x] 23.3 先补预览 `record_subtype` 显示名称回归：已知枚举显示中文，`not_applicable` 显示 `—`，未知值不泄露内部枚举
- [x] 23.4 实现支付宝 / 微信来源身份规范化与旧键兼容；不改变金额、来源快照、关系规则或既有流水
- [x] 23.5 实现前端会话级共享新账户草稿：候选去重、跨行选择、编辑同步、草稿引用清理和映射请求携带 `draft_id`
- [x] 23.6 实现后端按 `draft_id` 校验共享草稿并在最终事务中只创建一次账户，所有来源组映射到同一账户；预览阶段保持无写入
- [x] 23.7 实现流水预览业务细分中文显示名称映射，未知值使用安全中文兜底，内部字段合同保持不变
- [x] 23.8 用 Downloads 中支付宝、微信账单验证规范分组数量与名称：支付宝钱包仅为 `支付宝余额`，微信钱包仅为 `微信零钱`
- [x] 23.9 运行受影响 Python / SQLite / PostgreSQL 契约测试、Web Vitest、构建、真实浏览器响应式 QA、Hallmark 等价审查、OpenSpec 校验、`git diff --check` 和最终 diff 复核
- [x] 23.10 回写当前 `HEAD`、基线、命令、结果、截图、未完成的 PostgreSQL 条件和残余风险；未获单独授权不提交、推送、创建 PR 或部署

### 23.11 验证证据（2026-08-15）

- 需求澄清：按仓库要求显式读取并进入 `grill-me` `/grilling` 需求澄清门禁；当前运行时未提供可执行的 Skill session API，澄清结论已回写本节及 `proposal.md` / `design.md` / delta spec。范围锁定为钱包规范身份、会话共享新账户草稿和预览业务细分显示名称，不改变金额、来源快照、关系语义或迁移。
- 回归测试先红后绿：新增 `tests/test_statement_account_mapping.py`、`tests/test_mapping.py`、`tests/test_cash_import_wizard.py`、`web/tests/CashImportPage.test.tsx` 以及 `tests/contract/test_cash_import_dual_backend.py`；共享 `draft_id` 不一致、名称冲突、预览无写入和只创建一次账户均有覆盖。最终 `uv run pytest -q` → **1492 passed, 179 skipped, 1 warning**；`FT_TEST_POSTGRES_URL=postgresql+psycopg://quantdinger:***@127.0.0.1:5432/finance_tracker_test uv run pytest -q tests/test_statement_account_mapping.py tests/test_mapping.py tests/test_cash_import_wizard.py tests/contract/test_cash_import_dual_backend.py tests/test_mapping_import_dual_backend.py` → **71 passed**（SQLite / PostgreSQL）；新增旧精确映射优先于规范值通配映射回归及受影响本地矩阵 → **75 passed, 4 skipped**；`uv run python -m compileall -q src tests/test_mapping.py tests/test_statement_account_mapping.py tests/test_cash_import_wizard.py tests/contract/test_cash_import_dual_backend.py` → 通过。
- Web：`cd web && npm ci`；`npm test -- --run` → **119 passed**；`npm run build` → 通过。真实 Chromium 使用隔离 Vite `http://127.0.0.1:5176/w/workspace-qa/cash-import` 和 API 路由桩，390×844 与 1440×900 覆盖上传、映射、跨行选择同一待创建账户、编辑同步和预览；页面到达“核对流水”，`ordinary_transfer` 显示为“普通转账”，无原始枚举泄露，控制台错误 / 网络失败均为 0。截图：`/tmp/finance-tracker-cash-import-390.png`、`/tmp/finance-tracker-cash-import-1440.png`；390 px 页面级 `scrollWidth=390`，表格溢出仅在容器内。
- Downloads 真实账单：通过 `CashLedgerCommandService.scan_import` 验证 `支付宝交易明细(20260512-20260812).csv`（188 行、0 个问题）的钱包组 **31** 行且唯一名称为 `支付宝余额`；`微信支付账单流水文件(20260512-20260812)_20260812163147.xlsx`（212 行、0 个问题）的钱包组 **109** 行且唯一名称为 `微信零钱`。历史支付宝账单中的 `账户余额` / `余额` 也合并到 `支付宝余额`，来源快照字段未变；旧 `mapping.yaml` 的 `账户余额` / `零钱` 规则通过别名回退仍可命中。
- 规范与差异：`openspec validate --all --strict --no-interactive` → **27 passed, 0 failed**；`openspec doctor` → root / OpenSpec root ok；`git diff --check` → 通过。当前 `HEAD=57704e424d48dbfd30cfe78682e2a53251544ab8`，本次变更未创建提交。Hallmark `audit` 动作在仓库环境不可用（`hallmark` 命令不存在），已按同等人工审查完成 390 / 1440 截图、焦点 / 关键点击 / 页面级溢出和文案泄露检查，0 critical / major / minor。当前工作树保留未提交改动；用户尚未授权提交、推送、创建 PR 或部署。
