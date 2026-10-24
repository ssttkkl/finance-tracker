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

### 当前实施与验证

2026-10-24 用户逐轮确认的方向已写入 design 和 delta：复用主页表格／详情；待确认优先、零待确认自动全部；文件上账户下；摘要下警示卡片；通常一次保存；结果仅摘要，提交新增异常统一错误／原因／返回。当前入口为共享 `CashImportTaskScreen`，由 App／Graph 承载，旧四步页面、scan／preview／commit 客户端及专属模型、状态和演示实现已删除。

Cross-platform Impact Check：App 导航／退出、当前导入页面、共享账本行渲染和恢复存储影响 Web、Android、iOS。三端共用当前业务合同；默认 Web 代表视口冒烟，Android 编译与主机单测，iOS 共享编译。本轮 iOS 平台单测、设备和应用包 QA 按用户决定不运行，不记通过。依赖保持 Compose `1.12.1`；弹层关闭后无障碍树停更及禁用状态语义缺陷按用户决定保留，不据物理点击通过宣称辅助技术通过。

| 范围 | 当前证据与结论 |
|---|---|
| 基线／时间 | 生产 UI 修复验证基线 `c7b04ec`、交付 `dc4b39e`；最终演示与测试迁移基线 `62e6240`、比较基线同 HEAD，2026-10-25；最终离线 Demo 源码及产物指纹一致 |
| 测试先行 | 默认待确认分页、首屏失败禁止确认、明确公共目标与候选成员、退出策略、结果统计均有失败反例。连续同成员 APPLY 丢补丁的 20 项中 1 项失败后修复；多成员切换浏览器反例后改为一次只展开一个成员，未保存确认。演示普通草稿保存／明确转正式及来源只读 5 项中 3 项先失败后修复，保持同一流水身份及原文；缺金额／时间／币种或账户不支持币种保持草稿不统计，证券账户不能转正式 |
| 共享回归和构建 | `FT_API_ORIGIN=http://127.0.0.1:58792 ./gradlew :shared:wasmJsTest :shared:testAndroidHostTest :androidApp:assembleDebug :shared:compileKotlinIosSimulatorArm64 :webApp:wasmJsBrowserDevelopmentWebpack --no-configuration-cache` 生产入口矩阵成功，1m59；Wasm 386／Android 主机 381 项，无失败或 skip（`/tmp/ft-cash-import-empty-final-matrix.log`）。随后仅演示组成标签修复，`FT_COMPOSE_DEMO_MODE=true FT_API_ORIGIN=''` 使用同一任务矩阵成功，1m59；Wasm 387／Android 主机 382，无失败或 skip（`/tmp/ft-cash-demo-single-final-matrix.log`）。旧测试减少仅对应已退役协议／四步页面，当前会话／状态／账户／恢复回归保留 |
| Web 当前合同回归 | Chromium，1440×900，`http://127.0.0.1:5308`；仓库 `compose-cash-import.e2e.ts` 10/10 passed，47.0s：待确认独立分页／切全部／实际摘要，未知提交按原键核实且不重发，多成员放弃确认后保留原成员实际补丁，完成导入经分类页回账本刷新，主内容完整任务／唯一标题及返回；提交新增异常或明确未提交均只给错误、原因和返回，不提供行重试或自动重发。额外覆盖准备页账户／密码宽度上限、结构化原文双值和空组成数组显式显示。日志 `/tmp/ft-cash-import-empty-final-browser.log`。跨页刷新先复现失败再修复；成员保留检查实际请求，绕过已登记的弹窗后语义树停更，不把它计无障碍通过 |
| 真实 API 冒烟 | 专用合成 SQLite API `58792`，同一开发包及 Chromium／1440×900：实际文件选择上传、未映射草稿、统一核对、实际结果；公共备注使用 `entry_choices` 且来源原值不变，恢复后提交；独立账户立即创建，退出先取消会话再撤销账户。最终日志 `/tmp/ft-cash-import-retired-real-browser.log`，截图 `/tmp/ft-compose-cash-evidence/import-*.png`；3/3 passed，22.1s，无新增控制台／请求／HTTP 错误。随后改动仅限演示数据，生产流程未变化 |
| 后端合同 | 明确整笔公共目标最终双后端 89 passed，索引增量 29 passed；只读候选成员 205 passed。当前分页缓存／暂存／API／重试／消费矩阵 SQLite 与显式专用 `_test` PostgreSQL 329 passed、无 skip，暂存单测 153 passed。日志 `/tmp/ft-cash-{import-entry-confirmed-dual,import-entry-indexed-final-dual,import-members-dual,plan-cache-final-dual,plan-cache-final-unit}.log`；具体后端范围与收尾见父 change，不把缺 PostgreSQL 的 skip 计通过 |
| 性能边界 | 既有千行 HTTP 性能及 publication 双后端 54 passed：SQLite／PostgreSQL 预览 2.46／2.61s、提交 4.68／10.65s、预热后同页热读 p95 约 14.3ms。冷读仍整对象解码，不宣称任意冷页低于 100ms；万行独立待办不变。日志 `/tmp/ft-cash-import-final-performance.log` |
| 独立复核 | 当前公开字段输入、成员切换、演示必需值／账户完整性 major 均已修复重审；既有七张实际截图独立 Hallmark／设计为 0 critical／0 major／0 minor。准备页宽度顺序使 720 px 上限失效，已用真实浏览器先失败再修复并独立几何复核关闭。结构化原文曾显示标量占位，已复用只读 renderer，真实重复导入和只读页面反例验证；空组成数组先失败，再显式显示“未提供”，最终 10/10 与共享矩阵通过，独立回查 major 关闭。无新增视觉系统或组件包装，完整用户演示确认仍待完成 |
| 离线演示 | `http://127.0.0.1:5309/w/demo-workspace/`，Chromium／1440×900。实际演示导入→核对→摘要→返回、当前详情编辑保存、普通／转账模式及同币种创建，3/3 passed，18.7s，零 API 请求和 JS 异常，日志 `/tmp/ft-cash-offline-final-verified.log`。普通创建稳态取证重跑 1/1、9.3s（`/tmp/ft-cash-offline-booking-stable.log`）；八张实际截图独立 Hallmark／M3 复核 0 critical／0 major／0 minor。演示只保留当前 Graph 合成数据，不模拟完整关系引擎；复杂来源／身份／关系操作使用前述实际 API QA 证据，不据演示断言全部复杂分支或无障碍通过。用户确认尚未取得 |
| 工具和文档 | Node 3/3（`/tmp/ft-cash-ui-final-node.log`）；功能地图、业务说明、design 与旧路径归属原位修订。OpenSpec strict 37/37、doctor healthy、本地 Markdown 链接／锚点及 `git diff --check` 通过；日志 `/tmp/ft-cash-ui-final-{specs.json,doctor.json}`。README 的演示启动命令仍适用，接口示例、配置、部署及词表无新增概念或变更，无需重复修改 |
| 代表分支与验收边界 | 真实上传消费／退款双流水→映射实际账户→完整只读候选→延后→实际未合并提交，1/1 passed，11.2s，日志 `/tmp/ft-cash-import-refund-mapped-browser.log`。已验证归属的重复账单→原文差异→采用→恢复→一次提交，同一流水更新原文，1/1 passed，11.6s，日志 `/tmp/ft-cash-import-source-update-rendered-browser.log`。来源／身份维护最终 2/2、14.8s 及独立复核见维护模块。密码重传、复杂分摊、来源设置及账户未知分支以共享合同测试覆盖，本轮未逐项 Web 端到端穷举；完整演示及用户确认仍待完成 |
| 发布与回退 | 当前只写专用合成账本，不操作真实库或部署；同仓库／`average-snail` 提交推送已有授权。回退可撤回当前前端接线，不恢复已关闭的旧公开协议。无新依赖、schema 或迁移 |

防复发：每次导入只允许一个来源成员编辑；同一成员的连续 APPLY 保留明确 null 与已保存补丁，DEFER／KEEP 清除。结果统计只计账单行，不把公共对象更新再算一笔；导入结束后的刷新标记直到首次返回账本才消费。最小恢复只存标识与原幂等键，不存文件、密码或账户旧值；来源只读展示不混入公共人工值。
