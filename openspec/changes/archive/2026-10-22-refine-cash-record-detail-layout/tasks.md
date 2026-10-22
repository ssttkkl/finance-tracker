## 1. 思考与需求澄清

- [x] 1.1 显式使用项目 `$grill-me` → `$grilling`、`$openspec-explore` 和 `$domain-glossary`，阅读项目上下文、UI 规则、主规格、词表、active changes、源码和既有测试。
- [x] 1.2 确认范围为收支详情与其中的关联流水；用户追加确认金额作为摘要重心、类型/时间下移、流水信息拆除卡片，投资详情及模态外框不在范围内。
- [x] 1.3 阅读 M3 Typography overview、Applying type、Type scale & tokens 原文及 Compose 官方文字尺度，记录现状文字角色和基线问题。
- [x] 1.4 在现有 Compose 离线演示中打开实际详情，捕获 390/768/1440 px 现状截图，记录问题而不以截图代替行为验证。

## 2. 计划与设计

- [x] 2.1 使用 `$openspec-propose` 创建 proposal、delta spec、design，并以 `$openspec-update-change` 回写用户追加决定。
- [x] 2.2 记录 `$hallmark redesign` 的项目预检、信息架构、文字角色、将删除的冗余样式、自查和回滚；不产出 Pen 或 HTML 原型。
- [x] 2.3 完成 Cross-platform Impact Check：Compose Web、Android、iOS 与共享呈现层受影响；旧 React/Expo、API 和持久化不受影响，详见 design。

## 3. 任务拆分与一致性

- [x] 3.1 独立复核 proposal、完整 MODIFIED requirement、ADDED requirement、design 和 tasks 的一致性，修复阻断项。
- [x] 3.2 运行 change 严格校验和 `git diff --check`，确认实施范围和验收可执行。
- [x] 3.3 在实施前补充能够因旧布局而失败的相称测试：类型/时间位于字段组、摘要层级、平铺主信息、关联展开及长内容无遮挡；记录失败证据。

## 4. 构建

- [x] 4.1 调整摘要为金额主导及次级交易对方，将类型/时间放入字段组；同义类型去重，含义不同的类型保留。
- [x] 4.2 将流水信息改为面板中平铺字段，移除主信息卡片容器及额外内边距，保留独立折叠关联区域和全部数据。
- [x] 4.3 调整主要值与辅助信息的 M3 角色、首行基线和局部间距，确保长备注、分类路径、账户、交易对方和金额自然换行。
- [x] 4.4 调整关联流水的分组与记录层级，横向容纳不下时重排名称和金额；保留现有关联管理与危险确认。
- [x] 4.5 更新功能地图 F-04 的实现证据、跨端覆盖和待验事项；如新增领域术语则先更新词表，否则记录无需变更的结论。

- [x] 4.6 按用户演示反馈，为 Regular/Wide Side Sheet 详情金额上方补 24 dp 留白；Compact 不叠加，重新验证三窗口及最终 UI。

## 5. 审查

- [x] 5.1 独立复核产品范围、工程边界及最终 diff，确认未修改全局字体、模态容器、金额/关系/权限逻辑及范围外表单。
- [x] 5.2 在最终 Compose UI 调用 `$hallmark audit`，对照三窗口实际截图和验收审查层级、对齐、文字必要性、对比度及长文本；修复 critical/major 后复审。
- [x] 5.3 运行范围化术语搜索和可见文案必要性自检，按 `$chinese-documentation` 复核适用文本；记录 finding、处置和残余限制。

## 6. 测试与 QA

- [x] 6.1 运行受影响测试、共享 Compose 完整回归、相称 Web 构建、Android 构建和 iOS 共享编译，记录实际命令与结果。
- [x] 6.2 在生产演示/预览使用真实浏览器覆盖单源/关联详情、折叠/展开、正常/加载/失败重试/只读、长内容、编辑返回、关闭和既有键盘行为。
- [x] 6.3 在 390/768/1440 px 检查实际文字角色、几何尺寸、首行对齐和正文滚动，覆盖浅/深色及必要的放大字体；截图只保存在忽略目录。
- [ ] 6.4 Native 通过实际客户端或设备测试验证三窗口的长内容、对齐、滚动、关联和操作；分别记录 Android/iOS 已验与未验，不把共享编译计作实际 iOS UI QA。
- [x] 6.5 运行 `openspec validate --all --strict`、`openspec doctor` 和 `git diff --check`；复核性能与安全边界。无存储变更，SQLite/PostgreSQL 矩阵不适用；若实施触及存储则 Flow-Back 升级验证。

## 7. 发布准备

- [x] 7.1 用户查看最终 Compose 演示后明确要求提 PR 并合入 `main`，作为当前演示确认；iOS 实际 UI 验证缺口仍单独记录。
- [x] 7.2 记录最终 HEAD、比较基线、执行时间、验证和回滚证据；按新分支的具体授权决定提交、推送、PR 和合并。
- [x] 7.3 实现、审查和 delta 同步完成后归档 change。主规格与 delta 三项需求逐段相同；保留 iOS 实机 QA 一项已知未完成记录。

## 8. 反思

- [x] 8.1 记录本轮可复用的文字层级、类型信息去重和长文本排版决策；只有适用于其他页面的结论才提升为共享规范。

## 当前证据与状态

- 日期：2026-10-22；比较基线与当前 HEAD：`adc46b7129c838481173ced7d668a932fbb2de98`；工作分支：`codex/refine-cash-record-detail-layout`。
- 工具：Node.js `v26.8.1`、OpenSpec `1.7.0`；已确认满足最低版本。
- 现状浏览器：Playwright Chromium，URL `http://127.0.0.1:5186/w/demo-workspace/`，视口 390×900、768×900、1440×900，打开演示“山海咖啡”流水；截图在 `compose/qa/local-evidence/refine-cash-record-detail-layout/before-{390,768,1440}.png`。
- 首次截图脚本在 768 px 点击行正文未打开详情而超时；改为实际点击该行的 `ledger.open-record` 按钮后，768/1440 px 成功截图。此处仅是现状阅读，尚未执行本 change 实现后的浏览器 QA，不记为验收通过。
- M3 官网普通抓取仅返回 JavaScript 提示；已用实际 Chromium 成功加载并阅读三个 typography 页面全文，未将搜索摘要当作原文。
- 源码盘点由独立复核上下文 `/root/inspect_management` 只读完成：现金详情与关联共用正文；投资详情独立；公共模态同时服务新增/编辑/关联选择；现有测试主要约束容器和行为，尚无详情排版 Native 专项覆盖。
- 规划时状态：规划阶段完成后进入共享 Compose 实现；主规格在实现验收前保持原样。后续实现、QA 和演示反馈以本文末尾记录为准。
- 规划时记录：旧分支 `codex/unify-management-settings` 的 PR/合并授权不适用于本 change。用户在最终演示后另行明确授权本分支 PR 与合入 `main`，以该最新指令为准。

### 规划一致性复核与验证

- 独立复核上下文 `/root/inspect_management` 检查四类 artifacts。首轮 1 major：遗漏另一条仍要求主要信息卡片表面的既有 requirement；1 minor：大字体纵排例外未贯穿规格。均已采纳并修复：将“Compose 收支账本按窗口提供一致的流水浏览结构”完整纳入 MODIFIED，并统一默认分列、分列时基线对齐、空间不足时标签在上和值在下。
- 第二轮结论：0 critical、0 major、0 minor，无剩余阻断项；两条 MODIFIED 分别保留全部 11 个和 5 个原场景，未改的列表、滚动、权限、焦点和操作合同无遗漏。
- 实际命令：`openspec validate refine-cash-record-detail-layout --strict` 通过；`openspec validate --all --strict` 为 32 passed、0 failed；`openspec doctor` 为 root ok；`git diff --check` 通过。另只读比较两条 requirement 的场景名称集合，确认原场景完整保留；检查所有新 artifacts 无行尾空白。
- `$domain-glossary` 复核未引入新领域概念，现有“流水类型”“关联流水”等词条继续适用，本轮无需修改词表。按 `$chinese-documentation` 检查新增中文文档的中英文空格、标点和代码标识符；无新增产品字符串。
- 此处验证针对规划文档，不代表代码测试、运行时 QA 或最终 UI audit 已完成。最新文字回写后应在进入实施前再次运行范围化校验。

### 测试先行

- `npm run test:e2e --prefix compose/qa/web -- --grep '详情排版 390：时间'`：生产 Web 构建成功，真实浏览器因详情内找不到“发生时间”字段而失败，符合旧布局现状；不是环境或选择器缺失造成的误报。失败 trace 位于忽略的 `compose/qa/web/test-results/compose-cash-detail.e2e.ts-F-04-详情排版-390：时间进入平铺字段且摘要只有金额与对方/trace.zip`。


### 首轮实现验证与演示反馈（顶部留白调整前）

- 已完成金额/交易对方层级、平铺字段、类型去重、首行基线及长字段/关联金额重排；`$openspec-apply-change` 已应用共享 Compose 实现。
- `./gradlew :shared:compileKotlinWasmJs :shared:compileKotlinIosSimulatorArm64 :androidApp:assembleDebug --no-configuration-cache` 通过（43 s）；共享 `:shared:wasmJsTest` 的 JUnit 结果为 136 tests、0 failures、0 errors。iOS 仅共享编译通过，未完成 iPhone/iPad 实际 UI QA。
- `FT_COMPOSE_PREVIEW_PORT=5192 node compose/qa/web/scripts/start-compose-demo.mjs` 完成正常模式生产构建（12 min 11 s）；`npm run test:node --prefix compose/qa/web` 3 tests 通过。产物既有大小提示为 Wasm 7.42 MiB、JS 534 KiB；未增加依赖或资源。
- Chromium 151.0.7922.34，生产预览 `/w/workspace-1/`，合成 API 数据：390/768/1440 px 分别覆盖主摘要、平铺字段、编辑返回、Escape 后实际背景可交互、关联折叠/展开及滚动、长字段、操作菜单/删除取消；另覆盖 loading、503/retry、只读及同义/异义类型。新增 16 个场景均取得成功结果（最后整组 15 passed、1 个点击工具取矩形竞争失败，修复为原子采样后该场景单独重跑通过；不声称单次整组 16 通过）。成功场景无 console/page/static/network 错误。768 px 长字段用深色，其余浅色。
- Compose Web 的关闭 Dialog DOM 镜像残留（CMP-10623）通过真实场景截图差异及重新打开产生新 API GET 共同验证关闭，避免仅凭残留 DOM 判断。两项原有 modal 测试仍有镜像坐标限制：跨 resize 几何差异 252 px、旧 more helper 实际点中编辑；未记录为通过。新增各窗口独立上下文覆盖对应实际操作，不修改公共外框或焦点行为。
- Android API 36 专用只读模拟器 `emulator-5556`，density 320，在 780×1800、1536×2048、2880×2000 px（390/768/1440 dp）分别运行 `ANDROID_SERIAL=emulator-5556 ./gradlew :shared:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.finance.tracker.CashRecordDetailDeviceTest --no-configuration-cache`，三次各 3/3 tests 通过。覆盖真实入口、平铺边界、编辑返回、基线、320 dp / fontScale 1.6 长值重排。原 `5554` QA 模拟器冻结/offline，未删除锁或数据；新实例使用 `-read-only -no-snapshot-load`。
- 独立工程复核 `/root/inspect_management`：0 critical/major；1 minor 为缺少类型去重断言，已补充相同与不同显示标签两种场景并通过。独立 `$hallmark audit` 阅读共享代码和 7 张实际截图：0 critical、0 major、0 minor。此结论针对留白调整前，调整后重新 audit。
- 截图、失败 trace 和成功结果保存在忽略目录 `compose/qa/local-evidence/refine-cash-record-detail-layout/qa-first-run/`、`qa-last-full-run/`、`qa-final/`；不提交浏览器状态或财务数据。实际 headed Chromium 已向用户展示生产 Compose 的单源及关联详情。
- 用户追加：Side Sheet 金额上方缺少 margin。Flow-Back 到 design，限定 Regular/Wide 详情正文顶部 24 dp，Compact 沿用已有容器留白；新增几何断言并重新构建、QA、审查。该反馈不改变领域语义、操作或容器尺寸。
- 财务金额格式化、来源值、API、权限、持久化均无变更；SQLite/PostgreSQL 契约矩阵不适用。无新输入、日志、依赖或外部回调，安全与性能风险仅局部文字测量；保留常规相称构建与实际滚动检查。

- 顶部留白 RED：对旧生产产物运行真实 Chromium、768×1000 px，摘要 top=89、标题 bottom=88，实测间距 1 px（分隔线），目标为 25 px（24 dp 留白＋1 dp 分隔线），符合缺陷。新增断言要求三窗口均为 25 px。测试启动器因 Native 测试源变化触发强制重建，已停止该轮并直接对原产物测量；不将未执行的 Playwright 轮次算作失败用例。

### 文字自检与反思

- 可见文字未新增常驻说明、实现术语或新字符串；保留既有核对字段，类型显示文本相同时只保留一项，含义不同时分别保留。范围化搜索在新增代码/测试及正式文档中仅命中设计记录的“精确十进制”工程边界，未暴露到产品文案；按 `$chinese-documentation` 复核适用中文。
- 可复用决策：金额摘要只设置一个主要文字重心，类型/日期进入可扫描字段；不同字号字段按文字首行基线对齐；长名称与金额按实际文字测量决定同排或纵排；拆除卡片后重新检查正文与固定标题边界的留白，不能只移除容器而遗漏其原内边距。仅登记在当前 change，未扩张为全局页面改造。
- 本次追加 margin 的独立工程复核 `/root/inspect_management` 为 0 critical、0 major、0 minor；确认只读详情参数与滚动内 padding 的作用域正确，Compact 不重复叠加。

### 顶部留白修复后的验证

- Android API 36 在 1440/768/390 dp 三窗口分别重跑 `CashRecordDetailDeviceTest`，均 3/3 通过（3 min 17 s / 50 s / 44 s）；新增实际几何断言在所有窗口得到标题底部至摘要 25 dp（24 dp 留白＋1 dp 分隔线）。包含基线、1.6 倍字体长内容及编辑返回的既有专项检查均通过。iOS `:shared:compileKotlinIosSimulatorArm64` 再次通过；实际 iPhone/iPad UI 尚未验证。
- Android 新结果分别保存在忽略目录 `margin-native-wide/`、`margin-native-regular/`、`margin-native-compact/`。测试后关闭本轮启动的只读 `5556` 模拟器；未改动冻结 `5554` 的数据或锁。
- Node 预览测试再次 3/3 通过；`openspec validate --all --strict` 再次为 32 passed、0 failed，`openspec doctor` root ok，`git diff --check` 通过。HEAD 与比较基线保持 `adc46b7129c838481173ced7d668a932fbb2de98`，未提交/推送。

- Web 重新生产构建通过（8 min 15 s，49 tasks）；`npm run test:e2e --prefix compose/qa/web -- --grep '详情排版'` 本次完整运行 **16 passed（1.6 min）**。Chromium 151.0.7922.34，URL 为启动器随机本地端口的 `/w/workspace-1/`；视口 390/768/1440×1000，长字段为对应宽度×1100。三窗口 summary 与 header 的实际间距断言均为 25 px；全部正常/错误/加载/只读/长内容/关联/操作场景通过，未捕获 console/page/static/network 错误。此成功结果替代首轮新增场景分次通过的限制；两项旧测试的已登记镜像坐标限制仍保留，不声称已修复。
- 本次截图、结果及 trace 目录完整保存在忽略的 `compose/qa/local-evidence/refine-cash-record-detail-layout/margin-web-final/`；其中 `evidence/cash-detail-typography/single-{390,768,1440}.png` 为最终三窗口，另含关联展开/滚动、深色长字段、loading/error/retried/readonly 和菜单确认截图。未将任何运行产物纳入 Git。
- 更新实际 headed Chromium 演示：正常生产 Compose `http://127.0.0.1:5192/w/workspace-1/`，两页分别展示单源详情和关联展开，均实测顶部间距 25 px。合成数据由该浏览器上下文的 QA API fixtures 提供；外部浏览器直接打开该 URL 不自动继承 fixtures。
- 独立复核上下文 `/root/inspect_management` 再次调用 `$hallmark audit`，实际查看 margin 后的三宽单源、1440 关联展开/滚动、768 深色长字段截图，核对源码、design 和 delta：**0 critical、0 major、0 minor**。确认 24 dp 留白、Compact 不叠加、金额重心与平铺字段、固定区域和长文本均无新增视觉问题。
- 演示前交付状态（历史）：Web 和 Android 此范围验证完成；iOS 共享编译通过，实际 iPhone/iPad UI 待验，补跑条件为可用 iOS QA 设备/模拟器；用户查看演示的结论待反馈。其后的用户已明确要求 PR 与合入，授权和演示状态以文末记录为准。

- 用户已确认将当前演示结果通过 PR 合入 `main`；已授权在同仓库该分支提交、推送、创建 PR 与合并。当前 `origin/main` 包含 `#97`，应从最新基线提交/变基。iOS 实机 QA 缺口已在先前交付说明中告知，并保留为已知限制；不会虚报为通过。

- 已将 change 归档至 `openspec/changes/archive/2026-10-22-refine-cash-record-detail-layout/`；两个修改需求和新增需求均已同步至主规格。iOS 实机 QA 仍未完成，原因是本环境没有可用设备；在用户明确授权 PR/合并前已告知该限制，因此按用户指示继续交付并保留为残余验证项。

### main 最新基线回归与合入验证

- 以最新 `origin/main` `808cd68120eb5506d5c76ae0eb748e33ae50a8b2` 检查 PR #98 时，发现它移除了流水列表右侧 16 dp padding 后，桌面行末详情按钮中心与滚动条命中区重叠。真实浏览器 768×1000 px 上按钮中心 x=712、滚动条命中区起点 x=712，导致按钮点击被滚动条画布截获，详情无法打开。先在当前最新主干生产构建上复现该问题，随后给按钮点击区增加 16 dp 末端内缩，并新增中心点避开滚动条的回归断言；这是列表入口 hit target 的小修复，不改变详情布局或业务行为。
- 用户授权目标为同仓库 PR #98 合入 `main`。PR: https://github.com/ssttkkl/finance-tracker/pull/98；分支 `codex/refine-cash-record-detail-layout`。本记录在推送后补录最终提交、CI 和 merge commit。
- 生产 Web 预览 `http://127.0.0.1:5192/`：`npm run test:e2e --prefix compose/qa/web -- --grep '详情排版'` 16/16 通过，覆盖 Chromium 151、390/768/1440×1000、详情入口、单源/关联、长内容、加载、错误重试、只读和菜单确认。再加无遮罩入口截图后，`npm run test:e2e --prefix compose/qa/web -- --grep '时间进入平铺字段'` 3/3 通过；截图 `compose/qa/web/test-results/evidence/cash-detail-typography/ledger-entry-{390,768,1440}.png` 可见桌面入口没有挤压金额列或破坏行对齐。未捕获 console、page、静态资源或网络错误。
- API 36 Android 只读模拟器 `emulator-5556`、density 320：运行 `ANDROID_SERIAL=emulator-5556 ./gradlew :shared:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.finance.tracker.CashRecordDetailDeviceTest --no-configuration-cache`，分别在 2880×2000、1536×2048、780×1800 px（1440/768/390 dp）运行，每窗 3/3 通过，合计 9/9。Android 已完成三窗口实际设备测试；iOS `:shared:compileKotlinIosSimulatorArm64 --no-configuration-cache` 通过，但仍无可用 iPhone/iPad 设备进行真实 UI QA。
- `npm run test:node --prefix compose/qa/web` 3/3 通过。Web 生产构建成功（49 tasks，6 min 16 s）。最终 UI Hallmark audit 覆盖 `ledger-entry-{390,768,1440}.png` 和最新单源、关联滚动、长文本截图：0 critical、0 major、0 minor；行末图标向左内缩后仍清晰并与金额列保持间距。
- Cross-platform Impact Check：该末端内缩发生在共享 `CashLedgerScreen.kt`，影响 Web 和 Native 的桌面表格 hit target；Web 通过三宽浏览器验证，Android 通过三窗真实模拟器验证，iOS 共享编译通过但实际 iOS UI QA 未完成；Compact 沿用卡片行入口且没有变化。API、持久化、财务金额与权限语义均未变化，数据库双后端矩阵不适用。
- 审查 finding：最新 main 引入的滚动条点击覆盖为 1 个高优先级交互缺陷，已用几何回归断言及 16 dp 内缩修复；最终 Hallmark audit 无视觉 finding。残余限制仅为 iOS 真机/模拟器 UI QA，后续补跑条件为可用 iOS 设备或模拟器。本次新截图与测试产物不提交到 Git。
- 最终 PR #98 checks：PR Checks run `39708453330` 的 Compose client 13m 及 PostgreSQL 9m27s、SQLite 4m14s 均通过；Compose CI run `39708453327` 的 Compose shared/native（共享测试、Web/Android 普通与离线构建及 iOS simulator checks）22m55s 通过。性能 job 首轮曾失败：PostgreSQL 10k 相关编辑 p95 100.359 ms，超 100 ms 门槛 0.359 ms，且同组 49 项通过；只重跑失败 job 后 10m06s 通过。此次变更未触及后端或查询，重跑结果按基准边缘波动记录，不调整性能门槛。
- PR #98 当前实现提交 `cc227781d93a691e1a1d2be9361f748879ac12d4`，目标 `main`，基线 `808cd68120eb5506d5c76ae0eb748e33ae50a8b2`；用户已明确授权创建 PR 并合并。该实现提交已通过以上 CI 与本地验证。最后的归档记录提交仅补充本段 CI 结果；若其改变 PR head，按仓库要求等待新 head 对应检查后再合并。
