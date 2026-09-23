## 1. 思考与范围锁定

- [x] 1.1 复核 proposal、三份 delta spec、`openspec/project-context.md`、`DOMAIN_GLOSSARY.md`、`docs/ui-design-rules.md` 与现有导入实现，确认文件集合、密码、混合渠道、幂等和回滚边界。
- [x] 1.2 记录 `grill-me`/`grilling` 澄清结论：最多 20 个文件、每个 100 MB、客户端按本地 SHA-1 去重、同名不同内容保留、失败文件阻止整批继续、最终失败整批回滚、旧单文件 API/CLI 保持兼容。
- [x] 1.3 完成 Cross-platform Impact Check：共享契约、文案和语义 ID 同步影响 Web、Android、iOS；文件选择器为平台适配差异；Native 第一阶段覆盖当前导入页，不新增伪页面。

## 2. 计划、设计与原型

- [x] 2.1 完成 `design.md`，记录按文件临时对象、全有或全无扫描、聚合关系规划、事务边界、敏感信息、SQLite/PostgreSQL 和回滚策略。
- [x] 2.2 完成 `prototype/index.html`，覆盖空状态、文件追加/删除、低存在感文件名清单、焦点/禁用、扫描中、逐文件密码/错误和全量就绪状态。
- [x] 2.3 以 320、375、390、414、768、1440 px 打开原型，确认无横向滚动、文本不溢出且主操作顺序清晰；记录 Hallmark 预检和人工设计结论。
- [x] 2.4 运行 `openspec validate multi-file-cash-import --type change --strict`，并在交付前运行 `openspec validate --all --strict`，修复 proposal、delta spec、design、prototype 和 tasks 的一致性问题。

## 3. 失败测试与共享合同

- [x] 3.1 为临时存储和导入会话增加批量文件 manifest、文件级状态、顺序/批次摘要校验、密码不落盘、TTL/成功清理和旧单文件兼容的失败测试。
- [x] 3.2 为现金导入应用服务增加混合渠道扫描、来源账户合并、同渠道重复/跨渠道同 ID、批量预览和跨文件关系的失败测试。
- [x] 3.3 为最终确认增加任一文件写入/关系/投影失败时全量回滚、批量幂等重试和会话集合变化拒绝的失败测试。
- [x] 3.4 为共享 TypeScript SHA-1、批量请求/响应契约、密码映射和客户端去重增加失败测试；保留单文件 API/CLI 契约测试。
- [x] 3.5 为 Web 与 Native 状态序列增加失败测试：追加、删除、同内容去重、同名异内容保留、下一步前不扫描、逐文件密码、错误阻断和提交失败可重试。

## 4. 后端临时会话与批量应用服务

- [x] 4.1 扩展 `CashImportStagingStore` 的内存和 R2 实现，按文件保存原始对象/扫描草稿/预览草稿，并保留旧 `source` 对象路径。
- [x] 4.2 实现批量 scan/preview/commit 会话服务：逐文件解析、密码重试、全量就绪门禁、文件集合摘要校验、会话清理和不记录密码。
- [x] 4.3 将现金导入扫描、来源账户分组、映射应用和标准化预览改为一次处理全量文件，并在响应中保留每条流水所属渠道。
- [x] 4.4 让关系规划接收全量文件流水一次生成计划，使用来源限定稳定引用，支持跨文件配对并拒绝不一致的计划/映射摘要。
- [x] 4.5 让 statement import 的批量路径按 `(source_type, record_id)` 做幂等，并在同一 UOW 中完成账户映射、现金流水、关系和投影写入；旧单渠道路径保持原约束。
- [x] 4.6 扩展 Web 路由读取批量 JSON 和受限的按文件密码头，返回文件级状态/错误；保持旧 raw body、单文件 JSON、错误码和 CLI 调用合同。

## 5. 共享客户端、Web 与 Native

- [x] 5.1 在 `packages/core` 增加跨 Web/Native 的本地 SHA-1、文件集合校验和批量导入状态辅助函数，并完成单元测试。
- [x] 5.2 扩展 `packages/contracts`、`packages/api-client` 和 `web/src/api/cashLedger.ts` 的批量类型与方法；批量密码只进入受限请求头，旧方法签名继续可用。
- [x] 5.3 更新 presentation 文案、语义 ID 和共享状态定义，复核中文术语、空格、标点及用户可见文案必要性。
- [x] 5.4 改造 Web `CashImportPage`：多选追加、SHA-1 去重、数量/大小校验、低存在感文件列表/删除、下一步后统一扫描、逐文件密码/错误和聚合映射/预览/确认。
- [x] 5.5 改造 Native 文件选择适配器和导入页：iOS/Android 多选或等价追加、同一 SHA-1 去重、同一阶段顺序、逐文件密码/错误和批量重试。
- [x] 5.6 对 Web/Native 代码做跨端合同复核，确认没有在选择阶段提前上传、没有显示文件正文、没有局部成功状态，且最终提交失败仍保留可重试会话。

## 6. 审查与验证

- [x] 6.1 完成产品/范围复核：逐项对照用户确认的行为、非目标、旧兼容和验收标准；将 finding 及采纳/延期理由回写本文件。
- [x] 6.2 完成工程/安全复核：检查工作区隔离、对象私有性、日志脱敏、密码生命周期、文件大小/数量边界、幂等、混合渠道 ID 和事务回滚；修复阻断或 major finding 后重审。
- [x] 6.3 完成设计复核和 Hallmark `audit`（若动作可用）：覆盖信息层级、文件列表存在感、焦点/禁用/错误、移动单列、320/375/390/414/768/1440 px；记录目标、输出、finding 和结论。
- [x] 6.4 运行 `openspec validate --all --strict`、`openspec doctor`、`git diff --check`、受影响 Python/TypeScript/Vitest 测试、类型检查和生产构建。
- [x] 6.5 准备名称以 `_test` 结尾的专用 PostgreSQL 数据库，设置 `FT_TEST_POSTGRES_URL`，运行与 SQLite 相同的批量导入契约矩阵；已用一次性本地 PostgreSQL 集群和 `finance_tracker_test` 补跑完成。
- [x] 6.6 启动生产预览并完成真实浏览器 QA：Web 主流程、空/正常/密码/解析错误/提交失败、关键点击、键盘焦点，以及 390/1440 px；记录浏览器、URL、视口、步骤、截图路径、控制台/网络错误和结果。
- [ ] 6.7 完成 Native 可用环境下的 iOS/Android 导入选择与失败重试验证；已将 `agent-device` 升级到 `0.21.0`，其 `manual-qa` 门禁首行和操作契约有效。Android `Medium_Phone_API_36`（`emulator-5554`）使用 `EXPO_PUBLIC_FT_API_ORIGIN=http://10.0.2.2:8787 npm run android --workspace finance-tracker-mobile -- --device 'Medium_Phone_API_36'` 成功构建、安装并登录；在系统选择器中长按进入多选，选中 `cash_import_browser_refund.csv` 与 `transactions_1y_sample.csv` 后回到导入页显示 `已选择 2/20` 和两份文件名；重复选择前者仍为 `2/20`，删除后为 `1/20`；点击「下一步」才开始扫描，加入不可识别文件时按文件显示「无法识别，请删除后重试」，删除后合法文件可继续进入「映射账户」。Android 模拟器通过 ADB 注入的 CSV 初始被 DownloadsProvider 标为 `text/comma-separated-values` 且系统卡片不可打开，已在实现中对 Android 使用 `*/*` 选择器并在 QA fixture 上将 MIME 归一为 `text/csv` 后完成上述交互；证据截图为 `/tmp/android-picker-selection-mode.png`、`/tmp/android-picker-multiselect.png`。iOS `iPhone 17 Pro`（iOS 26.3.1，UDID `D0A5F360-E10A-43E8-BEA6-37340026EDBD`）已成功构建、安装并进入 Files；Files 中可显示两份 fixture 并通过系统「选择」操作同时勾选，但模拟器的系统文件场景点击「完成/打开」未回调 Expo DocumentPicker，未能把选择结果送回 Native 导入页，因此 iOS 的应用内文件清单、密码和失败重试仍需在真实 iOS 设备或可回调的系统文件 provider 上补跑；证据截图为 `/tmp/ios-picker-fresh-wait.png`、`/tmp/ios-recovery-two-selected.png`。
- **iOS Native 根因排查（2026-09-24）**：在同一个 `iPhone 17 Pro` 模拟器上，已分别验证 Shared File Provider 文件和应用自身 `Documents/Finance Tracker` 文件：选中 CSV 后系统「打开」按钮可见且为启用态，但使用 `agent-device` 的完整无障碍引用、坐标点击均无状态变化；系统「取消」可返回应用。随后在同一会话打开 Safari，系统下载确认框能被识别出「下载」「显示」「关闭」三个系统按钮，但三者同样均无法被 `agent-device` 触发。应用侧 `pickStatementFiles` 的 `multiple: true` 调用、Expo `DocumentPickerModule` 的 delegate/Promise 回调链与标准实现一致；本机 `expo-document-picker` `57.0.1` 与 `57.0.2` iOS 源码无差异。可用运行时只有 iOS 26.3.1，iOS 18 runtime profile 已缺失，也没有已连接的物理 iPhone。因此当前证据将该次失败分类为 iOS 模拟器系统文件 provider/自动化执行链问题，而非已确认的应用业务 bug；iOS 门禁仍保持未完成，必须在真实 iOS 设备或能实际触发系统「打开」的 provider 上补跑，不能据此宣称产品已通过。
- [x] 6.8 复核最终 diff 与 artifacts：无越界改动、无遗漏测试、无未记录风险；记录当前 `HEAD`、比较基线、命令、时间和未解决风险。

## 7. 发布准备与回滚

- [x] 7.1 记录发布准备：无数据库迁移、旧单文件入口可回退、多文件入口的观察指标和临时对象清理关注点。
- [x] 7.2 记录回滚步骤：关闭批量入口或回退批量调用代码不影响既有单文件数据；确认已成功批次不需要反向迁移，未确认会话按 TTL 清理。
- [x] 7.3 在未获用户明确授权前不执行 commit、push、PR、合并或部署；交付时保留工作树和验证证据。

## 8. 反思与归档

- [x] 8.1 记录本次可复用的跨文件身份、密码隐私、UI 状态机和双后端验证经验；如发现可复用规则，更新对应项目文档。
- [ ] 8.2 确认所有任务、规格 delta、审查和 QA 证据完成后，同步 delta 到主规格并运行归档前校验；未完成的外部环境验证不得伪装完成。

## 验证与审查记录

- **澄清与范围**：已通过仓库要求的 `grill-me`/`grilling` 逐轮澄清。最终合同为最多 20 个文件、单文件 100 MB；本地 SHA-1 仅用于选择会话内去重，同名异内容保留；选择阶段只显示文件名、支持删除和追加；点击「下一步」才扫描；密码按文件显示；任一文件失败阻止映射；关系识别覆盖所有文件所有流水；最终提交失败整批回滚；旧单文件 HTTP/CLI 保持兼容。
- **Cross-platform Impact Check**：共享 contracts、api-client、core SHA-1、presentation copy/semantic IDs、批量状态和来源限定关系引用同时影响 Web、iOS、Android；文件选择器分别使用 Web `multiple` 与 Expo DocumentPicker `multiple`，其余步骤保持同一状态顺序；未新增 Native 伪页面。
- **原型与设计**：`prototype/index.html` 已覆盖空、已选、扫描中、密码、错误、就绪、焦点和禁用状态。使用真实浏览器加载原型，在 320/375/390/414/768/1440 px 检查 `document.body.scrollWidth <= window.innerWidth`，均为 `true`。最终 UI 人工审查结论：文件名清单低存在感、主按钮顺序清晰、删除和错误可发现、移动端单列无溢出；运行时没有可调用的 Hallmark `audit` 动作，因此未声称执行工具 audit，保留等价人工审查证据。
- **产品/工程/安全复核**：复核范围覆盖旧单文件分支、批量会话对象清理、工作区/用户绑定、文件数量/大小、SHA-256 服务端校验、批次摘要、密码仅请求头/内存、来源限定 `(source_type, record_id)`、幂等键和同一 UOW 回滚。无阻断或 major finding；测试覆盖了混合渠道、跨文件关系、密码状态、集合变化、失败回滚和旧合同。
- **SQLite/Python**：`PYTHONPATH=tests uv run pytest -q tests/test_cash_import_staging.py tests/test_cash_import_session_service.py tests/test_cash_import_wizard.py` → `53 passed`；`PYTHONPATH=src python -m compileall -q src tests` → 通过。PostgreSQL：一次性本地集群、数据库 `finance_tracker_test`、临时 `FT_TEST_POSTGRES_URL`，`PYTHONPATH=tests FT_REQUIRE_TEST_POSTGRES=1 uv run pytest -q tests/contract/test_cash_import_dual_backend.py tests/contract/test_web_api.py tests/contract/test_row_idempotent_import.py` → `71 passed, 2 warnings`；集群已停止，连接配置未写入仓库。
- **JavaScript/构建**：`npm run test --workspaces --if-present` → Web `15 files / 152 tests`、Mobile `7 files / 26 tests`、API client `5`、contracts `1`、core `10`、design tokens `2`、presentation `6` 全部通过；Android Native MIME 适配补丁后再次运行 `npm run test --workspace finance-tracker-mobile` → `7 files / 26 tests`、`npm run typecheck --workspace finance-tracker-mobile` → 通过；`npm run typecheck:shared`、`npm run build:web` 均通过；`git diff --check` 通过。
- **真实浏览器 QA**：使用仓库 `browse` Chromium 和 Playwright 生产预览，URL `http://127.0.0.1:5173/w/preview-workspace/cash-import`，预览 API `http://127.0.0.1:8766`。Playwright `npm run test:preview --workspace finance-tracker-web` → `19 passed`，覆盖空状态、SHA-1 重复选择、追加/删除、点击下一步后才扫描、混合渠道统一映射/预览/提交、逐文件密码、解析错误阻断和提交失败保留重试上下文；真实浏览器主流程控制台无错误，网络请求按预期包含 scan → preview → commit。手工截图：`/tmp/multi-file-import-1440-select.png`、`/tmp/multi-file-import-1440-preview.png`、`/tmp/multi-file-import-390.png`、`/tmp/multi-file-import-390-selected.png`；1440/390 截图已人工查看。生产页面在 320/375/414/768/1440/390 px 均检查无横向溢出；键盘 Tab 可聚焦菜单和账户控件。
- **未完成验证与补跑**：Android Native 选择、SHA-1 去重、删除、下一步扫描门禁及解析错误删除重试已完成；Android 账户映射入口也已实际到达。iOS 已验证系统文件列表和多选勾选，但 iOS 26.3.1 模拟器没有把系统文件选择结果回调给 Expo DocumentPicker，因此未声称 iOS Native 清单、逐文件密码和失败重试通过；需在真实 iOS 设备或可回调的系统文件 provider 上补跑。PostgreSQL 双后端契约已完成，不把一次性测试数据库作为仓库配置提交。
- **基线与交付边界**：当前 `HEAD` 为 `d13644ed4befa5a422b14fbf55c80491d876aa9f`，工作分支 `sincere-fly`；比较基线为该 `HEAD`，当前改动仅限本变更涉及的 24 个代码/测试文件、3 个已同步主规格文件及 `openspec/changes/multi-file-cash-import/`。未执行 commit、push、PR、合并或部署；交付保留未提交工作树。
- **发布/回滚**：无数据库迁移；关闭批量入口或回退批量客户端/路由即可恢复旧单文件路径；成功批次无需反向迁移，失败/过期会话由 TTL 清理；上线后观察批量文件状态、密码重试、临时对象清理、整批回滚和幂等重试指标。
