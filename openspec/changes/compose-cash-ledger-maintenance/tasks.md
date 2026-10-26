## 1. 思考

- [x] 1.1 通过 `grill-me → grilling` 确认保留现有外观、原详情容器和在流水内处理关系；对照当前列表、详情与维护协议核实缺口。

## 2. 计划

- [x] 2.1 完成 proposal、delta 与 design，明确列表／详情／补处理边界、公共基础、文件所有权及三模块依赖。
- [x] 2.2 完成独立规划复核及 OpenSpec strict，确认旧 UI delta 不与新责任重复；只声明规划就绪。

## 3. 任务拆分与一致性

- [x] 3.1 先补当前读取、`entry`／来源权限、维护所有意图、公共冲突／继承、草稿保存、计划／收据／核实的协议失败测试；验证 null、清空、修订、字符串金额和工作区隔离。
- [x] 3.2 完成公共基础与宿主：
  - [x] 3.2.1 当前 DTO／domain／Repository、读取及维护 API、公共冲突和定位引用通过测试与构建。
  - [x] 3.2.2 接入编辑宿主和稳定定位行为，冻结集中声明的文件归属。
- [x] 3.3 提交公共基础小步，让导入和编辑模块基于同一版本开发；合成合同夹具、语义 ID／文案与最终 App 集成接口分别登记所有者。

## 4. 构建

- [x] 4.1 以失败测试接列表状态／未知值／统计／待确认快捷筛选和快照翻页；保留原筛选、月汇总及批量选择行为。
- [x] 4.2 以失败测试切换当前详情，整笔公共值总览、待处理区域和折叠只读来源，移除来源成员编辑入口。
- [x] 4.3 以失败测试实现草稿普通保存、明确转正式、来源采用／保留及实际身份问题处理；不自动转正式或换目标。
- [x] 4.4 以失败测试实现已有完整关系组确认／延后／拒绝／解除、公共冲突选择及解除继承；移除任意关联搜索和直接关系写入。
- [x] 4.5 接入编辑器插槽、当前分类／删除影响及保存后的稳定定位，集成合成演示；删除无人使用的旧详情和维护适配，未实现动作保持准确受限状态。

- [x] 4.6 按修改前视觉基线恢复内容层级、业务字段分组及主要操作位置，保持现有新协议和用户已确认流程。

### 4.7 草稿已填资金校验

- [x] 4.7.1 思考／澄清：用户确认草稿已填资金矛盾直接拒绝，合法缺项继续可保存；复用现有全部字段业务分组与详情容器。
- [x] 4.7.2 计划：补 delta 与 design，读取 UI 规则和现有 token；Material 3 Text fields Overview／Specs 正文已由真实浏览器读取，Hallmark 预检不新增层级、组件或视觉样式。
- [x] 4.7.3 测试先行：后端矛盾／坏账户先红；共享表单校验辅助函数缺失编译先红，补联动原子现金部分与矛盾分摊的单测。
- [x] 4.7.4 构建：共享草稿输入仅发送真实修改，校验时模拟后端原子字段联动；错误在当前区域提示并保留输入，不生成补救事项。共享失败派生 API 退役，原命令核实保留。
- [x] 4.7.5 审查：最终 Compose、文字必要性、术语及 Hallmark audit，独立复核清零阻断项。
- [x] 4.7.6 验证：Web 1440×900 浏览器冒烟；Android／iOS 相称编译单测，设备 QA 不运行；后端证据见 lifecycle 4.28。
- [x] 4.7.7 发布：功能地图、文档和技能原位核对，保留整体演示确认待办，按已有授权提交推送同分支。
- [x] 4.7.8 反思：表单与后端共同拒绝明确矛盾，避免修复导入表单却遗漏主页草稿入口。

Cross-platform Impact Check：Web、Android、iOS 的共享校验、错误状态、DTO 和能力接口均受影响，无平台分支或新依赖。当前基线 `0930b73`。新增错误状态 Web 冒烟 1/1、共享 Wasm／Android／iOS 编译与单测通过，独立设计复核及 Hallmark audit 为 0 critical／0 major／0 minor；实际命令、计数与截图见 [lifecycle 4.28](../cash-import-lifecycle-redesign/tasks.md#428-人工输入与原子提交收紧)。已登记禁用控件无障碍语义缺陷按用户要求保留，对应断言不记通过；整体演示确认仍未完成，不关闭 change。

## 5. 审查

- [x] 5.1 在独立上下文复核产品范围、工程、API 可发现性、恢复与隐私边界，记录 finding、处置和回写；阻断项修复后重审。
- [x] 5.2 对实际 Compose 页面执行 Hallmark audit、中文术语与可见文案必要性自检；独立设计复核清零 critical／major，不能用规划自查代替。

## 6. 测试与 QA

- [x] 6.1 在 `compose/` 执行 Wasm 和 Android 主机单测、Web 开发／QA 构建、Android 包及 iOS 共享编译，记录实际 HEAD、命令与结果。本轮 iOS 平台单测及设备／应用包 QA 按用户明确决定不执行。
- [x] 6.2 在 `compose/qa/web/` 执行 `npm run test:node` 和受影响 Playwright，在代表视口冒烟受影响主流程、关键交互和直接相关复现分支，区分 HTTP 合同夹具、真实后端合同与离线演示证据，记录浏览器、URL、步骤、必要截图及 console／network；不默认穷举状态、主题或三类窗口。
- [x] 6.3 按当前默认策略核对 Android、iOS 相称编译和单元测试证据；设备／模拟器交互、截图和端到端 QA 不在本次验收范围，不以未执行阻断，也不记为通过。
- [x] 6.4 使用专用 SQLite 及显式 `_test` PostgreSQL 服务验证受影响真实请求与业务结果，不自动探测数据库、不写真实账本；演示只用去标识化合成场景。不得改变万行后端独立性能验收范围。
- [ ] 6.5 在 Compose 演示模式以 Web 代表视口交付受影响流程，取得用户演示确认后才勾选 UI 完成；执行 OpenSpec strict、链接和 `git diff --check`。

## 7. 发布

- [x] 7.1 验证与审查后修订 README／业务说明／接口示例／功能地图和旧变更取代关系，原位清理过时表述，检查链接；无需修改的文档记录理由。
- [ ] 7.2 记录交付范围、依赖版本、回退和未解决风险；相称检查后按已授权仓库／分支小步提交推送，PR／合并／部署另需授权。不得通过回退恢复已关闭接口。

## 8. 反思

- [ ] 8.1 记录可复用防复发测试与必要规则，保持文档只表述当前结论；完成后再按实际依赖同步 delta，未实现内容不提前归档。

### 当前审查与验证

2026-10-25，验证对象为 `average-snail` 的当前合并树：提交前 `HEAD=bb66b38`，合入 `origin/main=adcb081`，共同基线 `5b41517`。保留用户认可的视觉基线 `8aa0b40` 和本分支恢复后的信息卡、业务分组及交互蓝，接入主分支索引滚动、刷新、管理导航和 M3 容器测量；不恢复旧详情／任意关系写入，不升级依赖。完整 change 尚未取得用户演示确认，不归档。

Cross-platform Impact Check：共享列表、详情／编辑宿主、创建表单、索引读取和导航影响 Web、Android、iOS。Web 用 Chromium 在 `http://127.0.0.1:5308` 验证当前 HTTP 合同夹具和受影响主分支流程，代表视口 1440×900，直接相关响应式补验 390×844、768×1024、1440×1000；演示使用 `5309` 的合成内存数据。Android 编译、主机单测及设备测试源码编译；iOS 仅共享编译，本轮平台单测、应用包及设备 QA 按用户决定不执行，不记通过。

| 验证 | 当前结果与证据 |
|---|---|
| 测试先行 | 同版本删除非根来源后的月份缓存双库回归先失败 2/2；opaque 来源组成项映射先出现 JSON 解码失败；账户任务主操作顺序先出现浏览器几何断言失败。永久回归位于 `test_cash_projection_lifecycle_reads.py`、`FinanceModelMapperTest.kt` 及 `compose-cash-import.e2e.ts`。未放宽读取／金额／请求断言 |
| 共享回归及构建 | Wasm 401／Android 主机 396 项，零失败、错误或 skip；最终账户表单修复后 Web、Android Debug 包、Android 设备测试源码和 iOS Simulator Arm64 共享编译通过，1m13。命令见下文，日志 `/tmp/ft-main-merge-account-matrix.log`；演示构建采用相同任务并设置 `FT_COMPOSE_DEMO_MODE=true FT_API_ORIGIN=''` |
| 当前 HTTP 合同 Web 冒烟 | entry／import／manual／relations 21/21，2.0m，日志 `/tmp/ft-main-merge-contract-final.log`。覆盖统一核对／摘要结果、实际影响确认／取消保输入、普通及转账模式、防重／退出、当前关系成员及安全重读。最终账户局部布局另以三窗口回归 3/3，12.4s，日志 `/tmp/ft-main-merge-account-api.log`；源码／产物指纹一致，不重跑未受影响流程。使用当前 HTTP 合同夹具，不称为真实后端端到端验收 |
| 主分支受影响 Web 冒烟 | 当前详情、管理导航／旧分类链接、FAB／吸顶／行内缩、索引跳转、重载／错误及下拉共 12/12，1.6m；下拉触控额外连续三轮 3/3，1.0m。日志 `/tmp/ft-main-merge-extra-final-reviewed.log`、`/tmp/ft-main-merge-refresh-final.log`。原鼠标后切 CDP 触控序列不稳定，当前用全触控完成选择／取消／下拉并保留原请求、非顶部与多选断言；该测试序列局限不认定为上游缺陷，不增加产品兼容路径 |
| 后端回归 | SQLite／显式专用 PostgreSQL 受影响全集 294/294，392.00s，日志 `/tmp/ft-main-merge-backend-reviewed.log`；最后冷页单次聚合调整再执行全部查询及生命周期读取 96/96，118.96s，日志 `/tmp/ft-main-merge-cold-read-reviewed.log`，包含新服务中页、空筛选、越界空页及同版本软删除。全仓 4770 项的无关扩展未完成，不宣称全仓回归通过 |
| 读取性能 | 固定万行同服务热读预算及索引查询计划 4/4，121.54s；SQLite 浅／中／深索引页 p95 32.0／61.6／84.0ms，PostgreSQL 61.2／69.9／80.6ms；原 100ms 门槛未放宽。日志 `/tmp/ft-main-merge-read-performance-direct.log`。冷请求聚合以双库功能合同验证，不宣称 HTTP 冷请求延迟已通过同一性能预算；未扩入独立导入写性能待办 |
| 当前演示 | 最终源码离线构建及产物指纹一致；原流程与新增账户三窗口共 10/10，45.8s，日志 `/tmp/ft-main-merge-demo-reviewed.log`。真实走通导入准备→核对→结果、详情编辑保存、普通／转账创建及账户表单；截图 `/tmp/ft-cash-ui-restored/*.png`，确认图位于 `/tmp/ft-cash-current-contract-results/`。只使用合成内存数据，主流程零 API 请求和 JS 异常，不写真实账本，不替代后端合同；用户尚未确认 |
| 工具与文档 | Node 3/3，日志 `/tmp/ft-main-merge-node.log`；OpenSpec strict 35/35、doctor healthy，当前非归档 Markdown 链接／锚点通过。当前范围术语与可见文字自检无新增实现术语／教学说明；最终 diff 检查在提交前执行 |
| 独立审查 | 当前协议／工程、冷请求分支及 delta 回查无 blocking／major。来源 ID 自动合并退化、旧平铺及任意关系操作、账户操作区遗漏均已修复并重审关闭。当前实际普通／转账、详情／编辑、导入、确认及账户三窗口截图的 Hallmark audit／独立设计复核为 0 critical／0 major／0 minor。保留字段分组、配色与唯一主要操作；不将规划自查算实际审查 |
| 限制与回退 | Compose `1.12.1`／Material3 `1.12.0-alpha03` 不升级。已登记弹层后无障碍树停更、禁用语义及输入镜像缺陷仍保留，画布交互通过不代表屏幕阅读器通过。回退只撤销本次集成，不恢复退役写入口；未合入 main、未部署、未归档 |

当前范围 finding 与处置：

| 严重度／位置 | 处置与回写 | 状态 |
|---|---|---|
| major，索引读取与月份元数据 | 保留 `sort_at`、正式／草稿和统计资格；完整连续月份段与精确删除来源签名进入有界聚合缓存。携版本冷页一次 SQL 聚合数量，空页不伪装不可用；双库及性能证据见上表 | 已修复并独立重审关闭 |
| major，来源 DTO 映射 | 组成项／来源流水标识恢复字符串直传，保留 main 组成项与双端字段；opaque 标识回归先失败再通过 | 已修复并独立重审关闭 |
| major，独立账户侧板操作区 | 主操作在 Regular／Wide 左侧，Compact 保留取消／保存，移除额外水平内缩；浏览器先失败，三窗口及实际截图重审通过 | 已修复并独立重审关闭 |
| P2，UI delta 取代关系 | 用完整 `MODIFIED` 保留 main 有效测量，移除旧平铺详情、成员编辑、独立关系创建及冲突的忙碌／退出断言 | 独立回查无阻断同步项 |
| 测试局限，混合输入序列 | 鼠标后切 CDP 触控未确认可稳定执行；全触控及独立鼠标流程分别验证，不把工具序列问题当上游缺陷或增加产品兼容路径 | 保留准确限制 |

实际编译及单测命令：

```sh
FT_COMPOSE_DEMO_MODE=false FT_API_ORIGIN=http://127.0.0.1:58792 ./compose/gradlew -p compose :shared:wasmJsTest :shared:testAndroidHostTest :androidApp:assembleDebug :shared:compileKotlinIosSimulatorArm64 :shared:compileAndroidDeviceTest :webApp:wasmJsBrowserDevelopmentWebpack --no-configuration-cache
```

后端受影响全集使用人工准备的 `_test` 数据库，测试进程顺序运行，不自动探测／启动数据库：

```sh
FT_TEST_POSTGRES_URL='postgresql+psycopg://ft_entry_test@127.0.0.1:55439/ft_cash_entry_test' FT_REQUIRE_POSTGRES=1 PYTHONPATH=tests:tests/contract uv run pytest tests/test_application_web_queries.py tests/test_cash_ledger_management.py tests/contract/test_web_api.py tests/test_alembic_migration.py tests/test_runtime_docs.py tests/test_ci_workflow_contracts.py tests/contract/test_cash_entry_editing.py tests/contract/test_cash_current_projection_api.py tests/contract/test_cash_projection_lifecycle_reads.py tests/contract/test_cash_projection_graph_reads.py tests/contract/test_cash_projection_parity.py -q --disable-warnings --tb=short
```

Web 实际命令使用本机 QA 配置及合成凭据，缓存和截图均不进入 Git：

```sh
npx --prefix compose/qa/web playwright test --config /tmp/ft-cash-current-contract.config.cjs
npx --prefix compose/qa/web playwright test --config /tmp/ft-cash-current-contract.config.cjs --grep 'import account task'
npx --prefix compose/qa/web playwright test --config /tmp/ft-main-merge-extra.config.cjs --grep 'F-04 当前详情|管理共用目录|旧分类链接|F-03 (滚动条拖动后定位到未加载流水|首次和完整重载|下拉刷新|FAB 覆盖列表)'
npx --prefix compose/qa/web playwright test --config /tmp/ft-main-merge-extra.config.cjs --grep '下拉刷新只响应' --repeat-each 3
npx --prefix compose/qa/web playwright test --config /tmp/ft-cash-ui-restored.config.cjs
```

最后冷请求分支重验的实际后端命令为同一数据库配置下的 `uv run pytest tests/test_application_web_queries.py tests/contract/test_cash_projection_lifecycle_reads.py -q --disable-warnings --tb=short`。性能专项为同一配置下的 `uv run pytest tests/test_cash_projection_performance.py::test_fixed_10k_cash_read_paths_meet_100ms_budget tests/test_cash_projection_performance.py::test_fixed_10k_cash_page_lookup_plans_use_pagination_indexes -q -s --disable-warnings --tb=short`。

文档收尾范围：当前列表协议／UI delta、三个 change、功能地图、UI 规范、AGENTS 和 apply 技能已对齐；主分支归档保留历史事实，不把历史截图当当前完成证据。现金账本说明补索引、未知月份和统计资格；README 保持现金流程主入口及主分支投资架构入口，修复失效链接。不新增领域概念、依赖或迁移，无需另改金额、记账与权限合同。完整 UI 的 6.5／7.2／8.1 保持未完成，用户确认后再收尾。
