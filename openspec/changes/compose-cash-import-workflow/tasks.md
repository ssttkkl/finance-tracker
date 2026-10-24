## 1. 思考

- [x] 1.1 通过 `grill-me → grilling` 确认功能范围和四项 UI 方向；对照实际入口、已有任务／账户 VM、后端合同和 active changes 盘点缺口。

## 2. 计划

- [x] 2.1 完成 proposal、delta 与 design，明确完整任务页、统一列表、账户独立保存、结果恢复、非目标及共同文件归属。
- [x] 2.2 完成独立规划复核、三 change 一致性与 OpenSpec strict，记录证据；只声明规划就绪。

## 3. 任务拆分与一致性

- [x] 3.1 在维护模块公共基础合入后，核对公共字段／关系选择及宿主合同；先补真实响应 DTO 解码失败测试，覆盖 `entry`、权限、公共冲突与明确清空。
- [ ] 3.2 先补状态及页面失败测试：部分文件继续、未映射草稿、完整组、计划失效、结果摘要与提交异常统一返回、原命令未知与账户退出；与 requirement 逐项对应。

## 4. 构建

- [ ] 4.1 扩展生命周期 DTO／domain／mapper，不重写已有恢复基础；按测试补齐任务／账户 VM 及最小恢复接线。
- [ ] 4.2 实现主内容区准备页面、逐文件重传／设置及独立账户表单，保存生效后选择；不保存账户草稿。
- [ ] 4.3 实现统一预计收支列表、状态筛选、分页及当前项详情，差异／公共值／关联选择写入当前计划输入，一次确认。
- [ ] 4.4 实现实际结果摘要、提交新增异常统一错误／安全原因／返回、提交未知核实和安全退出／补偿；覆盖权威未提交、已持久化但无成功写入的 failed、仅草稿、正常无新增及成功部分写入。
- [ ] 4.5 统一完成 App／Graph 接线和受影响依赖注入，确认三个模块真实入口；移除无人调用的旧导入 API／状态／步骤并更新合成演示场景。

## 5. 审查

- [ ] 5.1 在独立上下文复核产品范围、工程、API 可发现性、恢复与隐私边界，记录 finding、处置和回写；阻断项修复后重审。
- [ ] 5.2 对实际 Compose 页面执行 Hallmark audit、中文术语与可见文案必要性自检；独立设计复核清零 critical／major，不能用规划自查代替。

## 6. 测试与 QA

- [ ] 6.1 在 `compose/` 执行 `./gradlew :shared:wasmJsTest --no-configuration-cache` 及受影响 Android／iOS 单元测试；构建 `:webApp:wasmJsBrowserDistribution`、`:androidApp:assembleDebug`、`:shared:linkDebugFrameworkIosSimulatorArm64`，记录实际 HEAD、命令与结果。
- [ ] 6.2 在 `compose/qa/web/` 执行 `npm run test:node` 和受影响 Playwright，在 Web 代表视口冒烟受影响主流程、关键交互及直接相关复现分支，记录实际 API、步骤、必要截图及 console／network；不默认穷举全部状态、主题或三类窗口。
- [ ] 6.3 按当前默认策略核对 Android、iOS 相称编译和单元测试证据；设备／模拟器交互、截图和端到端 QA 不在默认验收范围，不以未执行阻断，也不记为通过。
- [ ] 6.4 使用专用 SQLite 及显式 `_test` PostgreSQL 服务验证受影响真实请求与业务结果，不自动探测数据库、不写真实账本；演示只用去标识化合成场景。不得改变万行后端独立性能验收范围。
- [ ] 6.5 在 Compose 演示模式以 Web 代表视口交付受影响流程，取得用户演示确认后才勾选 UI 完成；执行 OpenSpec strict、链接和 `git diff --check`。

## 7. 发布

- [ ] 7.1 验证与审查后修订 README／业务说明／接口示例／功能地图和旧变更取代关系，原位清理过时表述，检查链接；无需修改的文档记录理由。
- [ ] 7.2 记录交付范围、依赖版本、回退和未解决风险；相称检查后按已授权仓库／分支小步提交推送，PR／合并／部署另需授权。不得通过回退恢复已关闭接口。

## 8. 反思

- [ ] 8.1 记录可复用防复发测试与必要规则，保持文档只表述当前结论；完成后再按实际依赖同步 delta，未实现内容不提前归档。

规划起点 `HEAD=a05d593`；仅完成 artifacts，未修改应用代码或运行 UI QA。独立复核、finding 处置、37/37 strict、doctor、链接与 diff 证据见 [共同规划验证](../compose-cash-ledger-maintenance/tasks.md#当前审查与验证)；本变更审查无剩余阻断，实施证据在各阶段原位填写。

### 实施细化

2026-10-24 用户逐轮确认：复用主页列表／详情；待确认优先、零待确认自动全部；文件上账户下；摘要下警示卡片按需展开；通常一次保存；结果仅摘要，提交新增异常统一错误／原因／返回。已知业务问题在预览展示。实施基线 `664d0ce`；现有用户 `AGENTS.md` 改动不纳入实现提交。

### 当前实施与验证

核对列表筛选基础已完成，页面尚未接线。默认待确认读取独立分页，零待确认自动全部，用户主动筛选保留至重新预览；初始页失败禁止确认。测试先红：`/tmp/ft-cash-import-pending-red.log` 55 项中 3 失败；首分页失败反例 `/tmp/ft-cash-import-pending-first-page-red.log` 56 项中 1 失败。2026-10-24 基线 `5966cd4`，`./gradlew :shared:wasmJsTest :shared:testAndroidHostTest :androidApp:assembleDebug :shared:compileKotlinIosSimulatorArm64 --no-configuration-cache` 最终成功（59 秒，`/tmp/ft-cash-import-pending-final-matrix.log`）；Wasm 377／Android 372 项，无失败或跳过。iOS 平台单测及设备 QA 依本轮用户决定未运行，不记为通过。

独立工程复核覆盖筛选、游标、迟到响应、恢复及确认资格；首分页失败仍可确认的 major 已修复并回查关闭，其余无 blocking／major。本小步没有新用户入口或渲染变化，浏览器冒烟留至统一导入页面接线后；现有线上页面仍为旧导入，不以状态机测试宣称可用。共用层三端受影响，未改持久化、金额或原生命令。八阶段中思考／计划沿用本 change，当前小步测试先行、构建及独立审查完成；交付保留旧入口事实，不提前归档；防复发由专属分页与首次读取失败测试保证。

预计整笔公共编辑协议与客户端已补齐，生产页面仍待接入。`entry.edit_ref` 与 `entry_choices` 独立于来源选择；目标变化需用户明确重新核对，重新读取的服务器行选择保留，旧整笔／关联决定清除，未成功预览的本地输入不迁移。测试先红覆盖缺少 `edit_ref`、客户端丢字段和确定未提交标志。2026-10-24 比较基线 `fc21c41`：显式专用 `_test` PostgreSQL 与 SQLite 最终定向合同 89 passed，无 skip（`/tmp/ft-cash-import-entry-confirmed-dual.log`）；此前大矩阵 390 passed／2 个旧输入形状断言失败，断言按新增空数组修正后已包含在最终 89 项中；跨工作区分类夹具的 PostgreSQL 前置工作区缺失也已修正，不把前次失败计通过。

共享矩阵 `./gradlew :shared:wasmJsTest :shared:testAndroidHostTest :androidApp:assembleDebug :shared:compileKotlinIosSimulatorArm64 --no-configuration-cache` 成功，Wasm 380／Android 375 项，无失败或跳过，日志 `/tmp/ft-cash-import-entry-ready-matrix.log`；测试、编译范围按默认策略，iOS 平台单测本轮未运行。独立工程／DX／安全复核的分类预检、关系依赖失败回滚、失效引用循环三项 major 均已修复并回查关闭；无新增 blocking／major。该小步不改页面，Web 冒烟随真实入口接线执行；已开始完整后端回归 `/tmp/ft-cash-import-entry-full-regression.log`，尚未完成，不以定向合同代替整体完成。后端普通预览性能与最终收尾仍需检查。账本表格复用、金额显示和通用输入取值的下一步改动不包含在此协议小步或本次已验证矩阵中。编辑目标索引按局部成员构建，全局版本、旧边及组只遍历一次，并保留原次序；千独立目标成本回归通过。排序及补丁定位索引的最终双后端增量 29 passed，无 skip（`/tmp/ft-cash-import-entry-indexed-final-dual.log`），不重复未变化的其余合同。

当前详情已补 `relation_members` 的后端及共享模型，候选实际字段来自同一冻结计划并保持只读；不扩展正式来源编辑权限。builder缺字段回归及客户端未知字段被丢弃的回归先失败。比较基线 `00c7a1e`，显式 SQLite／PostgreSQL 定向矩阵 205 passed、无 skip（`/tmp/ft-cash-import-members-dual.log`）；重复读取、当前项之外候选、完整引用及空权限均覆盖。共享矩阵 `/tmp/ft-cash-import-members-and-table-matrix.log` 成功，包含 Wasm／Android 单测、Android 构建和 iOS 共享编译；后续新增详情／关系编辑／准备页面尚未包含在该证据中。独立工程／安全复核无 blocking／major，冻结数据归属、只读权限及字段容器复制边界通过。协议小步无当前用户入口变化，Web 冒烟及演示确认仍未完成，整个 change 不标完成。
