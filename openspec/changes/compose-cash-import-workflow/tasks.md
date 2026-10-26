## 1. 思考

- [x] 1.1 通过 `grill-me → grilling` 确认功能范围和四项 UI 方向；对照实际入口、已有任务／账户 VM、后端合同和 active changes 盘点缺口。

## 2. 计划

- [x] 2.1 完成 proposal、delta 与 design，明确完整任务页、统一列表、账户独立保存、结果恢复、非目标及共同文件归属。
- [x] 2.2 完成独立规划复核、三 change 一致性与 OpenSpec strict，记录证据；只声明规划就绪。

## 3. 任务拆分与一致性

- [x] 3.1 在维护模块公共基础合入后，核对公共字段／关系选择及宿主合同；先补真实响应 DTO 解码失败测试，覆盖 `entry`、权限、公共冲突与明确清空。
- [x] 3.2 先补状态及页面失败测试：部分文件继续、未映射草稿、完整组、计划失效、结果摘要与提交异常统一返回、原命令未知与账户退出；与 requirement 逐项对应。

## 4. 构建

- [x] 4.1 扩展生命周期 DTO／domain／mapper，不重写已有恢复基础；按测试补齐任务／账户 VM 及最小恢复接线。
- [x] 4.2 实现主内容区准备页面、逐文件重传／设置及独立账户表单，保存生效后选择；不保存账户草稿。
- [x] 4.3 实现统一预计收支列表、状态筛选、分页及当前项详情，差异／公共值／关联选择写入当前计划输入，一次确认。
- [x] 4.4 实现实际结果摘要、提交新增异常统一错误／安全原因／返回、提交未知核实和安全退出／补偿；覆盖权威未提交、已持久化但无成功写入的 failed、仅草稿、正常无新增及成功部分写入。
- [x] 4.5 统一完成 App／Graph 接线和受影响依赖注入，确认三个模块真实入口；移除无人调用的旧导入 API／状态／步骤并更新合成演示场景。

- [x] 4.6 按修改前视觉基线恢复内容层级、业务字段分组及主要操作位置，保持现有新协议和用户已确认流程。

### 4.7 导入行可用动作收紧

- [x] 4.7.1 思考：用户确认新流水不显示“保留当前信息”、同笔仅一项来源决定；沿用现有详情容器、Material 3 `TextButton` 和个人记账刷新重试原则。
- [x] 4.7.2 计划：更新 delta、design 与项目工程约束；删除无对象可保留的冗余操作，仅为异常状态补必要提示。官方 `Buttons: Overview`／`Buttons: Specs` 正文已核对。
- [x] 4.7.3 测试先行：新流水旧响应仍宣称可保留时客户端拒绝；已有流水保留合法，同笔决定不重复提交。
- [x] 4.7.4 构建：共享状态与详情动作按真实已有流水资格收紧，导入表单在当前页阻止资金矛盾；后端公开可用动作与 UI 一致。
- [x] 4.7.5 审查：范围、设计、无障碍、响应式、共享三端及最终 diff；Hallmark audit 无 critical／major。
- [x] 4.7.6 验证：共享单测、Web 构建和真实浏览器代表视口冒烟；Android／iOS 相称编译与单测，设备 QA 按默认范围不运行。
- [x] 4.7.7 发布：核对功能地图及导入说明，记录证据和当前未解决的演示确认，按已有同分支授权提交推送。
- [x] 4.7.8 反思：无效动作在服务端和客户端均有回归，不为多人协作引入自动合并 UI。

本节当前证据：2026-10-26，验证工作树基于 `HEAD=83bf24b94cc8babd2d20e92d3a339ab063ecb985`，比较基线同该提交。Cross-platform Impact Check：`commonMain` 的动作资格、表单检查及错误文案影响 Web、Android、iOS，产品语义共用；本轮只对 Web 执行交互冒烟，Native 未运行设备／应用包 QA。

- 在 `compose/` 运行 `./gradlew :shared:wasmJsTest :shared:testAndroidHostTest :shared:iosSimulatorArm64Test :shared:linkDebugFrameworkIosSimulatorArm64 --no-configuration-cache --quiet`，退出 0；XML 汇总 Web 405、Android Host 399、iOS Simulator 400 项，均无失败／错误／跳过，iOS 共享 framework 链接成功。日志 `/tmp/ft-cash-input-final-platform.log`。
- Chromium，`http://127.0.0.1:5195`，1440×900；`FT_COMPOSE_PREVIEW_PORT=5195 npm run test:e2e -- tests/compose-cash-import.e2e.ts -g 'cash import reviews pending|new import rows never|import source differences|invalid review choices|inconsistent import allocations'` → 5 passed。实际操作覆盖上传与默认待确认、切全部与结果摘要、已有流水保留、新流水隐藏保留、预览输入拒绝后重试、矛盾金额留在表单且没有第二次预览请求。首次主流程诊断无 console error／failed request；构建 freshness gate 验证当前 bundle。截图在 Git 忽略的 `compose/qa/web/test-results/` 中，分别为 `new-row-actions.png` 与 `allocation-validation.png`，没有提交截图。日志 `/tmp/ft-cash-input-latest-web.log`；`npm run test:node` 3 passed。
- 已打开并阅读官方 `Buttons: Overview`、`Buttons: Specs`、`Applying M3 Expressive`、`Assistive technology` 与 `States` 正文；采用短动作标签、现有原生 Material 3 状态／焦点语义及明确错误提示，不更改组件默认测量或项目 token，不引入新 API／依赖。Hallmark audit 按最终 Compose、1440×900 截图及本节行为范围执行，主代理未发现新增 critical／major；独立上下文对相同两张截图复核，0 critical／0 major／0 minor，未发现新增布局或主题问题；截图复核不代替未执行的键盘／读屏及其他视口验证。可见文案仅增加拒绝预览后的重核对提示和当前表单资金错误提示，范围化术语及文字必要性检查通过。
- 本小步不改变既有详情容器、准备页和响应式结构，不增加新任务或逐行恢复入口；整体 workflow 的演示用户确认仍待完成，不据此勾选 6.5 或归档。Compose 1.12.1 已登记的关闭弹层无障碍缺陷按用户决定保留，本小步未修改依赖。


共享导入 API／DTO／Repository 的失败子集派生能力已退役，原请求幂等与未知结果核实保留。当前八阶段实施、双库回滚、Web 及共享编译证据由 [lifecycle 4.28](../cash-import-lifecycle-redesign/tasks.md#428-人工输入与原子提交收紧) 集中记录，不据此关闭整体 UI 演示确认。

## 5. 审查

- [x] 5.1 在独立上下文复核产品范围、工程、API 可发现性、恢复与隐私边界，记录 finding、处置和回写；阻断项修复后重审。
- [x] 5.2 对实际 Compose 页面执行 Hallmark audit、中文术语与可见文案必要性自检；独立设计复核清零 critical／major，不能用规划自查代替。

## 6. 测试与 QA

- [x] 6.1 在 `compose/` 执行 Wasm 与 Android 主机单测、Web 开发／QA 构建、Android 包和 iOS 共享编译，记录实际 HEAD、命令与结果。本轮 iOS 平台单测、设备与应用包 QA 按用户决定不执行。
- [x] 6.2 在 `compose/qa/web/` 执行 `npm run test:node` 和受影响 Playwright，在 Web 代表视口冒烟受影响主流程、关键交互及直接相关复现分支，记录实际 API、步骤、必要截图及 console／network；不默认穷举全部状态、主题或三类窗口。
- [x] 6.3 按当前默认策略核对 Android、iOS 相称编译和单元测试证据；设备／模拟器交互、截图和端到端 QA 不在默认验收范围，不以未执行阻断，也不记为通过。
- [x] 6.4 使用专用 SQLite 及显式 `_test` PostgreSQL 服务验证受影响真实请求与业务结果，不自动探测数据库、不写真实账本；演示只用去标识化合成场景。不得改变万行后端独立性能验收范围。
- [ ] 6.5 在 Compose 演示模式以 Web 代表视口交付受影响流程，取得用户演示确认后才勾选 UI 完成；执行 OpenSpec strict、链接和 `git diff --check`。

## 7. 发布

- [x] 7.1 验证与审查后修订 README／业务说明／接口示例／功能地图和旧变更取代关系，原位清理过时表述，检查链接；无需修改的文档记录理由。
- [ ] 7.2 记录交付范围、依赖版本、回退和未解决风险；相称检查后按已授权仓库／分支小步提交推送，PR／合并／部署另需授权。不得通过回退恢复已关闭接口。

## 8. 反思

- [ ] 8.1 记录可复用防复发测试与必要规则，保持文档只表述当前结论；完成后再按实际依赖同步 delta，未实现内容不提前归档。

### 当前审查与验证

2026-10-25，当前合并树以 `bb66b38` 为第一父提交、`origin/main=adcb081` 为第二父提交。保留完整导入任务、待确认默认筛选、文件上账户下、统一核对、摘要结果和通用提交错误；返回账本后使用主分支管理导航和索引列表。当前依赖未升级，退役协议未恢复。

整体前端交付的命令、Web 合同与演示、双库回归、性能、独立审查、文档收尾和风险见 [共同当前审查与验证](../compose-cash-ledger-maintenance/tasks.md#当前审查与验证)；本次动作收紧的当前编译／单测及 Web 冒烟见 [4.7](#47-导入行可用动作收紧)。共享层影响 Web、Android、iOS；默认 Web 冒烟，Android／iOS 编译和单元测试，未执行应用包／设备 QA。用户演示确认仍未取得。

Compose 1.12.1 已登记无障碍缺陷保留；演示 `http://127.0.0.1:5309/w/demo-workspace/` 仅使用合成内存数据，完整用户确认尚未取得。6.5／7.2／8.1 保持未完成，不归档，不声称已合入 main 或部署。
