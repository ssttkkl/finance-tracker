## 1. 思考

- [x] 1.1 通过 `grill-me → grilling` 确认功能范围和四项 UI 方向；对照实际入口、已有任务／账户 VM、后端合同和 active changes 盘点缺口。

## 2. 计划

- [x] 2.1 完成 proposal、delta 与 design，明确完整任务页、统一列表、账户独立保存、结果恢复、非目标及共同文件归属。
- [x] 2.2 完成独立规划复核、三 change 一致性与 OpenSpec strict，记录证据；只声明规划就绪。

## 3. 任务拆分与一致性

- [ ] 3.1 在维护模块公共基础合入后，核对公共字段／关系选择及宿主合同；先补真实响应 DTO 解码失败测试，覆盖 `entry`、权限、公共冲突与明确清空。
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
