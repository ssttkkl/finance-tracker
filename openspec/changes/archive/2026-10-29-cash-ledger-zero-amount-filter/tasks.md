## 1. 思考

- [x] 1.1 阅读现金账本浏览规格、项目上下文、词表、未归档变更及当前 UI/API/查询代码；确认原规格要求全额退款默认展示。
- [x] 1.2 完成 `$grill-me → grilling` 并锁定金额判定、默认重置行为、开关位置和响应式排列。
- [x] 1.3 梳理投影读取链路：服务端筛选先于分页、总数、月份汇总、待确认数和游标；金额为空表示未知。

## 2. 计划

- [x] 2.1 创建提案及 `cash-ledger/cash-ledger-browser` delta，明确关闭筛选后全额退款仍可查看。
- [x] 2.2 记录实现选择、精确十进制和 NULL 处理、查询缓存／游标、回滚方式及风险。
- [x] 2.3 信息架构：搜索 → 左侧可换行快捷筛选（待确认、分割线、全部、支出、收入、转账）＋右侧固定且垂直居中的筛选入口 → 列表；待确认、分割线和全部保持同组；详细筛选金额组在金额控件后增加独占一行的“隐藏0元流水”开关，各尺寸沿用上方金额控件共同的左侧内容留白。移除筛选卡下方重复的待确认行。
- [x] 2.4 UI 门禁：按 `SwitchItem.kt` 使用 Material 3 `ListItem` 与尾随 `Switch`，整行使用单一 `Role.Switch` 交互，尾随 `Switch` 不另行接收输入；开关标签沿用上方金额控件共同的左侧内容留白，不叠加额外横向缩进，也不移到特定金额列，保留上下各 8 dp 留白；不添加说明文案。各尺寸保持整行宽度，开关留在行末。待确认＋分割线＋全部仍为不可拆分组，其余类型项按空间自动换行；“筛选”固定于右侧并相对整体垂直居中，不使用横向滚动。Material 3 Switch overview/specs、Lists overview/specs、Chips overview/specs、Divider overview/specs、Expressive usability、adaptive layout 已由真实 Chrome 读取；SwitchItem 引用组件已检查；Hallmark 工具不可用，按 skill 手工预检／审计并在验证记录中注明。
- [x] 2.5 Cross-platform Impact Check：Web、Android、iOS 均影响共享筛选状态、文案和布局；共享 API／服务端影响所有客户端查询；Android/iOS 做相称编译与单元测试，Web 做真实浏览器冒烟与演示，不扩展原生设备 QA。
- [x] 2.6 确认 `origin/main` 已在当前隔离 worktree 快进合并至 `89682e5237e7bbe341ffc4b79ec84c2e8825a88c`，未改动独立的 `pr104-alipay-test-fixture-contract`。

## 3. 任务拆分

- [x] 3.1 检查提案、delta、设计与验收场景一致；按“测试先失败 → 后端查询合同 → Compose 过滤／布局 → 文档地图 → 审查验证”执行。

## 4. 构建

- [x] 4.1 先为 SQLite/PostgreSQL 现金投影读取增加失败合同：默认过滤精确零、关闭后全额退款可见、金额未知草稿保留、待确认数／总数／月份汇总一致、游标绑定开关。SQLite 首次红测：`PYTHONPATH=tests:.:src uv run pytest -q tests/contract/test_cash_projection_lifecycle_reads.py`，5 failed、25 passed、30 skipped；失败集中于新筛选及现有全额退款默认可见断言，符合预期。PostgreSQL 配置与覆盖范围待检查。
- [x] 4.2 增加 Compose 过滤模型/API 参数/离线演示仓库的失败覆盖，确认初始和重置值为开启、关闭不持久化。`cd compose && ./gradlew :shared:compileTestKotlinJs` 首次红测，报新字段 `CashFilters.hideZeroAmount` 缺失；之前误探测的 `:shared:compileTestKotlinMetadata` 不是仓库任务，未作为验证证据。
- [x] 4.3 实现服务端默认开启的布尔筛选，在 SQLite 使用精确十进制比较并显式保留 NULL，在 PostgreSQL 使用精确 Decimal 比较；确认所有读取派生数据使用同一条件。
- [x] 4.4 更新共享 Compose 布局：快捷项在左侧可用空间内自动换行，“待确认＋分割线＋全部”保持同组；“筛选”固定右侧并相对快捷项整体高度垂直居中；所有尺寸不使用横向滚动。移除下方重复待确认行，保留详细确认状态筛选。
- [x] 4.5 更新中英文文案、模块功能地图及现金账本指南；保留完整主规格 delta 并在归档时同步主规格。核对用户文案与术语，不新增领域词条。
- [x] 4.6 更新 `.agents/skills/openspec-apply-change/SKILL.md`，记录现金投影筛选条件贯通、跨后端精确金额和游标绑定的可复用接线要求。
- [x] 4.7 将金额组中的“隐藏0元流水”从 `FilterChip` 改为独占一行的 `ListItem`＋Material 3 `Switch`，整行统一切换；保留默认开启、即时生效和重置行为。
- [x] 4.8 根据用户反馈，所有尺寸都保持整行宽度，开关标签沿用上方金额控件共同的左侧内容留白；不额外增加横向 padding，也不把文字移到“最高金额”列。保留上下各 8 dp 留白。

## 5. 审查

- [x] 5.1 产品／范围复核：默认隐藏与可还原路径、全额退款投影及详情证据、未知金额草稿、统计资格和新开／重置默认值符合已确认范围；没有额外改动退款关系或金额事实。
- [x] 5.2 工程复核：过滤在分页、总数、待确认数和月份派生数据之前统一应用；精确 Decimal 比较、NULL 保留、SQLite／PostgreSQL 条件等价、游标绑定筛选值、服务端类型校验及快照版本路径一致；无持久化迁移。未发现工程 finding。
- [x] 5.3 设计复核：Hallmark 工具不可调用，按 `.agents/skills/hallmark/references/verbs/audit.md` 及反模式清单完成手工替代审计，并独立复看 390×844、768×1024 实际页面。筛选层级清晰；待确认、分割线和全部同组；筛选入口留在右侧；开关行沿金额组共同左侧内容留白、整行可操作且上下留白一致；键盘 Space 可切换；页面无横向溢出、裁切或额外说明文案。`CashHideZeroAmountSwitch` 不含 Hallmark 页面印章或模板结构。0 critical、0 major、0 minor，无需修复或重审；未声称运行 Hallmark 工具。
- [x] 5.4 最终 diff 审查：OpenSpec delta／主规格、后端与共享实现、双后端合同、现金账本和导入说明、产品及模块功能地图、skill 接线约定相互一致。范围、工程、设计及文档复核均无未处理 finding；无 glossary 更新，因为未新增领域概念。审查范围为本变更涉及的全部 `git diff` 与 change artifact。

## 6. 测试与 QA

- [x] 6.1 先运行新增 SQLite 合同用例并确认在实现前因缺少筛选行为失败；实现后重跑受影响 SQLite 测试及现金投影读取/API 测试。SQLite：`PYTHONPATH=tests:.:src uv run pytest -q tests/contract/test_cash_projection_lifecycle_reads.py`，32 passed、32 PostgreSQL cases skipped（待配置后补跑），22 warnings，20.92s。
- [x] 6.2 人工创建并使用独立 `ft_cash_zero_filter_test` PostgreSQL 数据库，通过临时测试角色显式设置 `FT_TEST_POSTGRES_URL` 运行同一合同矩阵，完成后删除测试库和角色：`FT_REQUIRE_TEST_POSTGRES=1 PYTHONPATH=tests:.:src uv run pytest -q tests/contract/test_cash_projection_lifecycle_reads.py`，64 passed、43 warnings，72.40s；无跳过项。
- [x] 6.3 重新运行受影响的 Compose shared 编译和单元测试：`cd compose && ./gradlew :shared:compileTestKotlinJs :shared:jsBrowserTest :shared:testAndroidHostTest :shared:iosSimulatorArm64Test`，BUILD SUCCESSFUL，41s；未运行原生设备交互 QA。
- [x] 6.4 在 390×844 和 768×1024 Chrome 视口检查开关标签沿用上方金额控件共同的左侧内容留白、行保持全宽、上下留白及整行／Switch 即时切换；确认页面无横向滚动或文字裁切。
- [x] 6.5 按本轮反馈重建并重启 Compose Wasm 演示，提供最新入口，暂停等待用户确认；之后只处理用户明确反馈，再重建和复演示。
- [x] 6.6 用户确认方向后完成 Hallmark checklist 人工替代审计与独立设计复核；没有 critical、major、minor 或阻断 finding。范围化搜索了共享 Compose 文案、现金账本与导入现状文档，没有多余帮助文案或过时的“零金额退款始终显示”描述。
- [x] 6.7 2026-10-29 16:08 Asia/Shanghai 归档前运行 `openspec validate --all --strict`，26 passed、0 failed；`openspec doctor` 根目录正常且无外部 references；`git diff --check` 无输出。归档后 16:10–16:11 再运行同一严格校验，25 passed、0 failed；doctor 与 diff check 均通过，并核对 12 个更新过的 Markdown 文件中 272 个本地链接和锚点均有效。最终实现 HEAD 仍为比较基线 `89682e5237e7bbe341ffc4b79ec84c2e8825a88c`；无遗留风险，工作树未提交。

## 7. 发布

- [x] 7.1 核对 README、现金账本指南、产品及现金账本／导入功能地图、词表和接口示例。已更新受默认筛选影响的现状说明；词表无需更新（没有新领域概念），不修改历史归档文档；其他接口示例不展示该读取参数且无需变更。
- [x] 7.2 已将完整 delta 同步至 `openspec/specs/cash-ledger/cash-ledger-browser/spec.md`，并归档至本目录。发布准备：实现和文档在合并后的 `origin/main` 基线上完成验证，变更留在未提交工作树；没有提交、推送或外部发布授权。回滚时恢复列表默认条件并移除开关及筛选参数即可，无数据迁移或既有关系事实改写。

## 8. 反思

- [x] 8.1 防复发覆盖包含已知零值与未知金额分离断言、SQLite／PostgreSQL 相同读取合同、筛选变化后旧游标拒绝，以及共享 UI 新开／重置默认开启、当前会话可切换且不持久化。

## 验证证据

- 比较基线：`89682e5237e7bbe341ffc4b79ec84c2e8825a88c`（合并后的 `origin/main`）。
- 当前 HEAD／比较基线：`89682e5237e7bbe341ffc4b79ec84c2e8825a88c`；与合并后的 `origin/main` 比较，工作树保留未提交变更。2026-10-29 15:43 Asia/Shanghai 执行 `cd compose && ./gradlew :shared:compileKotlinWasmJs`，BUILD SUCCESSFUL，6s；`node compose/qa/web/scripts/start-compose-offline-demo.mjs`，BUILD SUCCESSFUL，1m45s。2026-10-29 16:04 Asia/Shanghai 执行 `cd compose && ./gradlew :shared:compileTestKotlinJs :shared:jsBrowserTest :shared:testAndroidHostTest :shared:iosSimulatorArm64Test`，BUILD SUCCESSFUL，41s，覆盖 JS、Android Host 与 iOS Simulator 共享测试；没有执行原生设备 QA。
- 最新 Chrome（Playwright 1.62.1）烟测在 `http://127.0.0.1:5186/`（重定向到 `/w/demo-workspace/`），390×844 中文视口检查开关和金额控件左侧内容留白一致、默认开启、标签可整行点击切换；768×1024 检查开关行展示、点击关闭与键盘 Space 重新开启。两个视口 `document.documentElement.scrollWidth` 和 `document.body.scrollWidth` 均等于视口宽度；无文本裁切、`pageerror` 或请求失败。截图：`/tmp/cash-ledger-final-390-zh.png`、`/tmp/cash-ledger-final-768-keyboard-initial.png`、`/tmp/cash-ledger-final-768-keyboard-click-off.png`、`/tmp/cash-ledger-final-768-keyboard-space.png`。用户已确认此版。
- 浏览器烟测：Google Chrome（Playwright 1.62.1），`http://127.0.0.1:5186/`（重定向到 `/w/demo-workspace/`），390×844。操作：打开“筛选”→滚动详情面板至“确认状态”→选择“待确认”→“完成”；观察“待确认＋分割线＋全部”保持一组，支出留在首行、收入和转账折到次行，“筛选”固定右侧并垂直居中。`document.documentElement.scrollWidth`、`document.body.scrollWidth` 均为 390；无 pageerror 或请求失败。零元筛选在打开面板截图中默认选中；重置开启状态此前已在本变更的 UI 冒烟中核对。截图：`/tmp/cash-ledger-wrapped-before-390.png`、`/tmp/cash-ledger-zero-filter-wrap-panel-390.png`、`/tmp/cash-ledger-zero-filter-wrap-pending-390.png`。诊断控制台见到 Kotlin Wasm `wasmExports.memory` 弃用提示；不影响页面操作。Hallmark 工具不可用，最终设计复核待用户确认后依 skill checklist 手工完成。
- 当前 Compose Wasm 演示使用本轮最新代码，服务运行于 `http://127.0.0.1:5186/`；用户已确认本次留白和整行呈现。
- UI 官方设计依据：Material 3 Switch overview/specs、Lists overview/specs、Chips overview/specs、Divider overview/specs、Expressive usability、adaptive layout 已由真实 Chrome 读取正文；Switch 作即时开关，ListItem 仅有一个 `Role.Switch` 操作且目标不低于 48 dp。Compose Material 3 当前版本为 1.12.0-alpha03；本轮改动后需验证 shared 平台编译和浏览器交互。
- Hallmark：当前工具目录没有可调用的 Hallmark audit 工具；按 `.agents/skills/hallmark/references/verbs/audit.md` 手工执行并记录，不声称工具已运行。
- 浏览器演示：首版于 390×844 Chrome 暴露筛选按钮随单行内容被挤出视口的问题，反馈后改为固定右侧并自动换行；随后将开关行改为各尺寸完整宽度布局，标签沿用上方金额控件共同的左侧内容留白。用户已确认最终呈现方向。
- 审查结论：产品／范围、工程、独立设计与最终 diff 复核均无 finding。Hallmark 工具不可用，按其 audit 清单人工替代；真实页面无 Hallmark stamp，结构不是营销页的 AI 模板。跨端影响覆盖 Web、Android、iOS 共享层和服务端；Web 做代表视口真实浏览器烟测，原生端仅完成规则要求的编译／共享单测。
- 文档收尾：更新 README、现金账本指南、现金账本与产品／导入功能地图；同步现金账本浏览主规格；更新 `.agents/skills/openspec-apply-change/SKILL.md`。`DOMAIN_GLOSSARY.md` 无需改动，因为无新领域词条；历史 archive 文档保持不变。链接／锚点、OpenSpec 与 diff 检查的最终结果写在 6.7。
- 归档：2026-10-29 将 `cash-ledger-zero-amount-filter` 归档至 `openspec/changes/archive/2026-10-29-cash-ledger-zero-amount-filter/`；主规格已包含完整修改要求及场景。
