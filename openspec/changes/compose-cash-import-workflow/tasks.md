## 1. 思考

- [x] 1.1 通过 `grill-me → grilling` 明确实现文件 → 账户映射 → 待确认优先核对（可切换全部）→ 一次确认 → 实际结果；导入核对只读。读取现金导入合同、账本展示合同、当前 change、项目 UI 规则和 Compose 实现。
- [x] 1.2 原账户创建／退出补偿与财务规划规则不变；新范围仅扩展计划只读序号分页，并在进入预览前复用独立账户更新命令补充已选目标账户缺失币种。
- [x] 1.3 Cross-platform Impact Check：`commonMain` 的页面结构、文案、状态、确认与错误语义影响 Web、Android、iOS。文件选择控件保留平台适配；Native 仅执行编译与单元测试，不宣称设备 QA。

## 2. 计划

- [x] 2.1 更新 proposal、design 与两个 delta，记录全屏对话框、三步信息结构、文件解析状态、账户预填回选、只读核对与收据结果。
- [x] 2.2 记录已删除的冗余标题、账户编辑入口和导入内编辑能力；保留 Compose FinanceTheme、现有 M3 组件和项目 token。
- [x] 2.3 保留 `average-snail` 新增的安全诊断展示合同；不把目标分支旧核对编辑器及其 UI 动作带回只读导入。

## 3. 任务拆分与一致性

- [x] 3.1 对齐账户创建、币种自动补充、文件状态、步骤导航、待确认默认筛选与无待确认时自动查看全部、序号分页和右侧滚动条、只读详情、实际收据和退出恢复场景；对应 delta、共享页面、服务端分页协议和浏览器回归一致。
- [x] 3.2 Web 回归覆盖文件状态、账户创建和回选、确认范围、结果收据及错误诊断；断言前两步不显示副标题，核对步骤保留说明，核对详情不出现输入和编辑操作。
- [x] 3.3 对齐草稿、通用待确认状态与关系候选的行内提示；关系待确认标签仅来自详情中的延后候选，其余待确认按草稿／具体原因显示。
- [x] 3.4 明确备注字段合同：账单业务备注独立于关系说明和核对原因，后两者不得覆盖或拼入备注。

## 4. 构建

- [x] 4.1 将账单导入改为账本上的 Material 3 全屏对话框；保留后台账本、会话恢复、关闭保护和结果返回。步骤统一为“上一步／下一步”；前两步不显示副标题，核对步骤保留简短业务说明。
- [x] 4.2 分离文件选择和来源账户映射；文件上传期间显示行内假进度与 loading，移除立即生效；文件解析完成前不能进入映射。
- [x] 4.3 每个来源账户下拉框提供“＋新建账户”。预填来源名称和账单币种；名称包含“花呗”或“信用卡”时创建为 `loan`，其余为 `cash`；保存后返回并选中。既有账户币种不足时不提示，点击下一步按目标账户汇总来源币种并一次补齐，更新账户修订后再生成计划；沿用独立账户命令和退出补偿。ViewModel 单测与 Chromium 覆盖唯一身份建议、跨来源合并币种、保留原币种、修订回填及无额外缺币种提示。
- [x] 4.4 有待确认项时默认只显示待确认，可切换全部；没有待确认项时自动显示全部。列表按页连续加载并使用主页右侧序号滚动条；筛选只改变查看范围。详情保持只读，整份计划一次确认，实际结果读取收据。Chromium 覆盖 47 条、准确筛选总数、绝对序号加载、右侧滚动条和详情关闭后的同项／位置恢复。
- [x] 4.5 保留 `average-snail` 的 `CashImportDiagnostic` 只读诊断、账户和文件错误状态；不新增行级恢复操作。
- [x] 4.6 在导入核对列表区分草稿和通用待确认事项，删除正式／已确认标签及“手动配对”称呼；普通账本行保持原有状态呈现。关系类型只在其详情证据可见时标注；Web 390 px 与 1440 px 回归通过。
- [x] 4.7 核对行原样显示账单业务备注，并以独立“关联”和“需核对”说明展示关系摘要与核对原因；桌面断言各文本节点，窄屏断言同一可访问行完整包含三项。
- [x] 4.8 文件状态为“需要文件密码”时隐藏文件问题列表中的重复密码要求文案，保留状态、密码框和重新选择文件操作；Chromium 覆盖不重复提示。
- [x] 4.9 按目标账户聚合多个已选来源组的币种缺项，复用 `ImportAccountViewModel` 独立更新命令一次补齐；ViewModel 测试覆盖合并、保留旧币种、修订回填、失败和未知结果核实。
- [x] 4.10 新增导入项 PagingSource，切换待确认／全部时清空锚点并按主页筛选规则定位；复用 `CashLedgerScrollbar` 和 `LazyColumn`，移除“加载更多”按钮，支持未加载序号跳转与详情返回保留位置。Chromium 47 项分页回归及屏幕截图通过。
- [x] 4.11 先为按 `start_index` 读页、总数、筛选隔离和游标兼容写后端与 Ktor 失败测试，再实现只读计划接口；未加存储表或迁移。定向后端测试 19 passed；18 个 PostgreSQL 专用变体因该只读计划变更无持久化行为而不适用。
- [x] 4.12 导入详情复用维护模块的 `CashRecordSummary`、草稿提示和“账单原文 查看”折叠入口；账户、分类和备注以标签／值行排入内容流。草稿与缺项置于同一橙色提示区，以“草稿”为标题并按字段说明缺项；关系、差异与原文保持只读。维护模块改动后 Web 390／768／1440 px 用例通过，保留导入专属差异／关联展示；详细证据见维护 change 的 4.8。
- [x] 4.13 详情按摘要、关联流水、账单原文排列；关联区仅在多笔实际关联或有候选时出现并默认展开，候选嵌入对应关系且只读，原文默认折叠。
- [x] 4.13.1 测试先行：390 px 全屏和 1440 px 右侧详情的浏览器回归覆盖顺序、默认展开／折叠、实际成员、候选标识及无候选时隐藏关联区；证据不足错误提示回归先失败后通过。
- [x] 4.13.2 按已确认信息架构调整导入预计详情；来源差异和待确认状态归入摘要区域，关联成员与候选不提供接受、拒绝或改选；`insufficient_evidence` 不显示重复的通用导入错误。
- [x] 4.13.3 复核主页摘要与导入预计摘要的展示层级一致；原始字段只在末尾的“账单原文”折叠区读取和呈现。

### 4.14 文件重试错误提示修复与导入异常复核

- [x] 4.14.1 思考／澄清：真实工行试跑中，文件密码重试曾返回 HTTP 422，文件步骤却显示“核对选择无效”；预览也曾返回一次 422。两轮未保存响应错误码，后续同样的文件、预览和提交成功，不能断定服务端根因。文件请求的 `import_payload_invalid` 与预览的 `plan_input_invalid` 当前映射同一文案，是可确认的错误提示缺陷。
- [x] 4.14.2 计划：将文件请求输入错误与核对选择错误分开呈现；先用文件重试 422 的浏览器回归复现，再修改现有错误映射。仅在取得原始状态、错误码和安全诊断后修复无法稳定复现的 422 根因，不猜测或改写账单写入规则；当前 Compose 版本保持不变。
- [x] 4.14.3 测试先行：Chromium 模拟密码文件重试返回 `import_payload_invalid`，旧界面实际显示“核对选择无效”，目标提示断言失败；未写入真实账本。
- [x] 4.14.4 构建：`import_payload_invalid` 使用已有通用导入错误文案，`plan_input_invalid` 保留核对选择文案；文件状态、重试与预览行为未变。
- [x] 4.14.5 审查：文件重试的输入错误已不再声称“核对选择无效”；预览选择错误保留原提示。模拟 422 只证明文案分流，早先真实 422 未保存响应错误码，无法归因；不以通用异常文案掩盖服务端故障。
- [x] 4.14.6 验证：Chromium 导入与当前详情浏览器回归 37/37，Wasm 单测、Android 主机编译／单测、Node 3/3、OpenSpec strict 35/35 和 diff 检查通过；真实两份工行账单仅预览重试 200／计划 200，先前服务端 422 未复现，保留待观察。
- [x] 4.14.7 发布准备：README、导入流程、功能地图与本次错误映射核对，无新增入口、流程、页面或覆盖状态，故无需修改；最新演示入口 `http://127.0.0.1:5187/` 可用。未获 `develop` 的提交推送授权，本次保留工作树；整体导入流程演示仍按 6.5 待用户验收。
- [x] 4.14.8 反思：后续真实试跑保留 HTTP 状态、安全错误码、环节及诊断编号，不再仅凭 422 和共用文案归因；虚拟列表 QA 按可见行扫描，自动化定位失败不等同于产品滚轮错误。

当前证据：2026-10-27 19:42 CST，`HEAD=1ee935eb88e1c3bac05685feec4d032221a2fcb3`，比较基线同该提交。Chromium 文件重试 422 用例先出现“核对选择无效”而失败，映射修复后在完整当前详情／导入浏览器回归中 37/37 通过（命令见维护 change 4.10）；Wasm 单测、Android 主机编译／单测、Node 3/3、OpenSpec strict 35/35 与 diff 检查通过。使用 `~/.ft/bills` 两份工行 PDF 对本机数据库执行仅预览试跑，上传 201、密码重试 200、预览 200，**未提交账单**；无法由现有证据归因较早两次 422，保留待观察。安全运行记录在 `~/.ft/reports/icbc-import-live-20261027-fix-check/preview-recheck.log`。Cross-platform Impact Check：错误码文案映射在 `commonMain`，Web／Android／iOS 均受影响；Web 真实浏览器和 Android 编译／单测已覆盖，按用户决定不运行 iOS 或原生设备 QA。当前详情演示已获用户确认，整体导入流程演示仍待确认。


## 5. 审查

- [x] 5.1 最终产品与工程复核账户建议、下一步币种补齐、完整计划提交、绝对序号边界、筛选总数和旧游标兼容；无 blocking／major finding。用户确认后的交互演示仍是 6.5 检查点。
- [x] 5.2 按 Hallmark `audit` 参考和 `docs/ui-design-rules.md` 对照当前核对截图及响应式合同复核分页列表、右侧滚动条、主页行复用、侧滑详情及滚动位置恢复；无 critical／major finding。Hallmark runtime 不可用，按规则完成并记录人工替代审查。
- [x] 5.3 对照主页详情和 390／1440 px 导入截图复核标题、摘要、字段行、单个橙色提示卡、关联与原文入口；无额外字段卡片、重复标题或“需要留意”分区。依 `docs/ui-design-rules.md` 删除无效卡片和重复分组标题；字段行使用 Compose Foundation `Column`／`Row` 与既有标签／正文层级，不新增 M3 组件。通过真实 Chromium 读取 M3 `Grids & spacing: Spacing` 正文：近邻与留白用于隐式分组，边框、分隔线、阴影用于显式边界；因此保留一致的 16 dp 区域节奏但不包字段卡。M3 Cards overview/specs 与 Side sheets specs 已用于上一轮容器和提示卡核对，本轮未改侧板测量。390／768／1440 px 截图复核；无 critical／major finding。Hallmark runtime 不可用，按其 audit 指引完成人工审查。

## 6. 测试与 QA

- [x] 6.1 当前详情实现 `cd compose && ./gradlew :shared:compileAndroidMain :shared:testAndroidHostTest --no-configuration-cache --console=plain`：BUILD SUCCESSFUL。Web E2E runner 同时编译 `:shared:compileKotlinWasmJs`。未验证 iOS，符合本次用户指示；未运行原生设备 QA。
- [x] 6.2 `npm run test:node`：3 passed；`FT_COMPOSE_PREVIEW_PORT=5227 npm run test:e2e -- tests/compose-cash-import.e2e.ts --reporter=line`：Chromium 26 passed，包含账户币种补齐、零待确认默认全部、分页跳转与详情返回。
- [x] 6.3 Chromium 1440×900 的 47 项计划中滚到第 47 项，确认请求读取绝对后续页、右侧滚动条贴列表右边、详情为右侧面板并在返回后保留该项与位置；390 px、768 px、1440 px 详情视口均有回归。截图：`compose/qa/web/test-results/compose-cash-import.e2e.ts-44007-s-to-the-same-list-position/import-review-paged-first-page.png`、`import-review-paged-before-detail.png`、`import-review-paged-detail.png`、`import-review-paged-after-detail.png`。页面／网络诊断无错误；已知 CMP-10623 关闭弹层后无障碍语义树不刷新仍待上游修复。
- [x] 6.4 无新持久化规则或存储变化，双后端、性能和安全专项不适用；Web、Android 共享代码与 Android Host 单测通过。未运行设备 QA。
- [ ] 6.5 当前反馈版 Compose 离线演示已重新构建并启动，含关联流水默认展开、账单原文默认折叠及证据不足文案修正；`http://127.0.0.1:5299/w/demo-workspace/cash-import` 返回 HTTP 200，使用合成数据，等待用户确认；此检查点不代表 change 完成或可归档。
- [x] 6.6 Chromium 回归检查普通已确认记录无标签、草稿与待确认关系标签及底色、关系筛选与窄屏排版；390 px、1440 px 各 1 项通过。
- [x] 6.7 Chromium 回归断言流水业务备注、关联摘要和核对原因分别呈现，桌面与窄屏各 1 项通过；修复前两视口均因缺少业务备注节点而失败。
- [x] 6.8 Chromium 核对详情标题、类型／时间摘要及无卡片字段行；确认草稿标题与缺项正文位于同一橙色卡片、没有“流水信息”重复标题和独立“需要留意”分区；确认原文入口唯一、默认折叠且可展开；390／768／1440 px 只读内容通过，截图见 `compose/qa/web/test-results/compose-cash-import.e2e.ts-0364f-y-the-account-selection-390/import-detail-390.png` 和 `compose/qa/web/test-results/compose-cash-import.e2e.ts-53efe--the-account-selection-1440/import-detail-1440.png`。

## 7. 发布

- [x] 7.1 收尾复核 README、导入流程、功能地图和词表：README 无入口／命令变化；更新 `docs/cash-import-flow.md` 的币种补齐与序号分页协议、`docs/feature-map.md` 的实际接入状态；无新领域术语，不需改词表。
- [x] 7.2 已按用户明确授权合并到 `average-snail`；本地合并提交，不推送、不部署。

## 8. 反思

- [x] 8.1 加入账户币种不影响身份建议、绝对页范围与总数、右侧滚动条和详情位置恢复回归；保留 CMP-10623 为已知缺陷，不升级 Compose 依赖。

## 当前演示检查点

按用户要求，每轮反馈修改后完成相称编译和 Web 视觉校验，再启动最新演示供确认。本轮加入主页同款分页流水、右侧序号滚动条、待确认默认筛选和下一步账户币种补齐，并调整两处详情的关联与原文顺序。最新版离线演示：`http://127.0.0.1:5299/w/demo-workspace/cash-import`。使用合成数据，不连接 API。

仍待确认：用户演示验收。CMP-10623 导致关闭弹层后语义镜像停止刷新的缺陷保留，按用户决定不升级 Compose；浏览器回归用页面截图核对实际画布状态。

## 当前审查与验证

验证时间：2026-10-27 18:18 CST。当前 `HEAD=1ee935eb88e1c3bac05685feec4d032221a2fcb3`；工作树包含本任务原有的未提交代码，未推送或部署。

| 检查 | 命令／范围 | 结果 |
| --- | --- | --- |
| 真实浏览器流程与详情排版 | `cd compose/qa/web && FT_COMPOSE_PREVIEW_PORT=5227 npm run test:e2e -- tests/compose-cash-import.e2e.ts --grep 'import review pages through the ledger rows|import review keeps every member readonly and retains only the account selection' --reporter=line`；随后聚焦同一 390／768／1440 px 详情用例 | 首轮 4 passed；增加账单原文默认折叠及展开断言后 3 passed。确认无“流水信息”卡片／标题，草稿与缺项在同一橙色卡，原文只出现一次且可展开 |
| 当前详情关联与原文 | `FT_COMPOSE_PREVIEW_PORT=5301 npm run test:e2e -- tests/compose-cash-detail.e2e.ts tests/compose-cash-import.e2e.ts --grep '关联流水默认展开|候选嵌入关联区|current relation candidates remain readable' --reporter=line`；证据不足错误文案断言先失败，修正后 `FT_COMPOSE_PREVIEW_PORT=5303 ... --grep '候选嵌入关联区|current relation candidates remain readable'` | 修复后当前详情及导入候选 3 passed。截图见 maintenance tasks 4.9 验证段。整组接受 1/1 浏览器回归及忽略后重读契约另行通过 |
| 分页视觉与交互 | E2E 设为 1440×900，47 项；滚动至第 47 项、打开详情再关闭 | 后续 `start_index` 请求实际发生，右侧滚动条贴列表右缘；第 47 行可见，右侧详情面板关闭后保留筛选、同项和列表位置。相关屏幕截图见 `compose/qa/web/test-results/compose-cash-import.e2e.ts-44007-s-to-the-same-list-position/` |
| Compose Web／Android 编译与单测 | 本轮 `cd compose && ./gradlew :shared:compileAndroidMain :shared:testAndroidHostTest --no-configuration-cache --console=plain`：BUILD SUCCESSFUL，35 actionable tasks（6 executed、29 up-to-date）；Chromium runner 编译 Web Wasm 并通过目标用例。既有 Kotlin 警告，无新增编译错误。未运行 iOS 验证，遵循用户明确决定 |
| 详情设计复核 | 对照 `docs/ui-design-rules.md`、主页详情实现和 390／768／1440 px 浏览器截图 | 去掉只包裹字段的无效卡片及重复标题；金额／对方／类型与时间层级、标签／值行、橙色提示与原文披露清晰。M3 外层侧板测量未变化。Hallmark runtime 不可用，按技能 audit 指引人工复核；无 critical／major finding |
| 后端接口测试 | `uv run pytest tests/contract/test_cash_import_plan_publication.py::test_items_and_processing_details_are_paged_with_plan_and_filter_bound_cursors tests/contract/test_cash_import_plan_publication.py::test_import_items_support_filter_bound_absolute_positions_and_exact_totals tests/contract/test_cash_import_plan_api.py::test_http_import_items_support_absolute_pages_and_return_filtered_total tests/contract/test_cash_import_plan_api.py::test_http_plan_query_errors_are_safe tests/contract/test_cash_import_account_mappings.py::test_suggestions_recheck_account_eligibility_without_writes tests/test_cash_plan_protocol.py::test_plan_openapi_exposes_complete_input_and_bounded_pages_without_dangling_refs -q --disable-warnings --tb=short` | 19 passed，18 个仅 PostgreSQL 的测试变体跳过；本变更仅读取不可变计划并调整建议资格，不涉及持久化／数据库写行为 |
| QA 脚本与 OpenSpec | `cd compose/qa/web && npm run test:node`；`openspec validate --all --strict`；`git diff --check` | Node 3/3 通过；OpenSpec 35/35 通过；差异格式检查通过 |
| 开发演示 | `FT_COMPOSE_PREVIEW_PORT=5299 npm run demo:offline`；Chromium 1440×1000，`zh-CN` | 当前源码的 production Wasm bundle 构建成功（49 个任务）；`http://127.0.0.1:5299/w/demo-workspace/cash-import` 返回 HTTP 200。浏览器无 JS 异常和 API 请求；截图 `/tmp/compose-cash-detail-final-demo-1440-zh.png`。离线演示使用合成内存数据；用户演示确认仍待完成 |

Cross-platform Impact Check：Web、Android、iOS 共用来源账户状态、账户选择和币种补齐语义、筛选状态、分页数据、核对行及详情状态；Compact 切入详情，Regular/Wide 侧栏查看详情。Web Chromium 真实浏览器、Wasm 共享编译和 Android 编译／Host 单测通过；按用户指示不运行 iOS 验证或设备 QA。没有存储改动，双后端业务矩阵不适用。

按 Hallmark `audit` 参考人工检查核对列表和 1440×900 截图：沿用主页行组件、列表分页、右侧滚动条及既有主题 token；筛选和结果计数可见；无 critical／major finding。具体截图：`import-review-paged-before-detail.png` 显示第 47 行及右侧滚动位置，`import-review-paged-detail.png` 显示侧栏详情；关闭后位置由 E2E 断言。Hallmark runtime 不可用，因此此项为按技能 audit 指引完成的人工替代审查，并非调用了 Hallmark 工具。
