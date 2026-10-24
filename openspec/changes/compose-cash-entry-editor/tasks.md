## 1. 思考

- [x] 1.1 通过 `grill-me → grilling` 确认只列可编辑字段、固定摘要和记账内切换转账；核对创建方式、双端权限与新建／维护的真实接口差别。

## 2. 计划

- [x] 2.1 完成 proposal、delta 与 design，明确导入公共编辑、普通手动、转账、非目标、宿主和文件归属。
- [x] 2.2 完成独立规划复核和 OpenSpec strict，确认各创建方式 UI 权限与后端一致；只声明规划就绪。

## 3. 任务拆分与一致性

- [x] 3.1 基于维护模块已合入的公共基础冻结编辑器输入／回调与命令边界，不并行修改集中 DTO／宿主。
- [ ] 3.2 先补权限与序列化失败测试：导入五字段、组内只读、null／清空、手动财务、双端转账、孤立／投资单端受限及并发过期。

## 4. 构建

- [x] 4.1 新建独立编辑状态及表单，展示固定摘要和当前可编辑项，公共编辑使用真实对象修订并保护人工清空。
- [x] 4.2 接通普通手动新建／编辑及单入口转账模式，校验真实账户、同币种联动金额、换汇双金额的精确字符串和合法类型；不发送半笔转账或来源身份。
- [x] 4.3 接通手动转账完整双端编辑，按真实成员与允许补丁共同预览／提交，覆盖失败全保留和受限只公共编辑。
- [ ] 4.4 接通保存／返回／放弃、必要影响确认和维护命令核实；直接新建未知不自动重发，新建转账确定成功只刷新账本，不伪造稳定定位／收据或额外去重机制。
- [ ] 4.5 由维护宿主挂载真实编辑／记账入口，按实际结果刷新定位；更新各模式演示，移除旧全字段 PUT 构造与逐条来源编辑。

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

规划证据见 [共同规划验证](../compose-cash-ledger-maintenance/tasks.md#当前审查与验证)。公共整笔编辑已提交 `2b9833a`，普通记账／转账创建及响应修复已提交 `fd0cb5c`。当前小步完成完整转账双端编辑；旧路径删除、完整演示及用户确认仍未完成。

### 当前审查与验证

Cross-platform Impact Check：双端输入、完整组读取、保存及演示状态由 Web、Android、iOS 共享；默认 Web 冒烟、Android 编译及主机单测、iOS 共享编译。本轮 iOS 平台单测及设备 QA 按用户决定不执行，不算通过或默认阻断。没有后端代码、依赖、数据库或持久化语义变化；双后端转账 HTTP 证据保留在 `fd0cb5c`，当前仅通过既有维护合同提交。

独立工程／产品复核：实际成员身份、同对象及修订、方向识别、保守权限、同币种精确联动、换汇独立金额及公共-only 补丁无 blocking／major。演示按非空请求补丁增加修订会遗漏另一端派生类型变化的 major，已用失败回归复现并改为按实际成员差异增修订，重审关闭。取消、忙碌与未知仍复用当前维护任务，不新增自动协调、单端财务写入或跨版本重放。

| 验证 | 当前结果与证据 |
|---|---|
| 基线／时间 | `HEAD=fd0cb5c`，比较基线同 HEAD；2026-10-24，完整转账双端编辑工作树，无依赖变化 |
| 测试先行 | 双端构造 6 项中 5 项失败后转绿；原金额字符串保留新增回归 7 项中 1 项失败；完整成员读取 29 项中 2 项失败；演示完整双端 3 项中 2 项失败；派生类型及双端修订 4 项中 1 项失败。编译错误不计失败回归。日志 `/tmp/ft-cash-transfer-entry-{red,untouched-red}.log`、`/tmp/ft-cash-transfer-member-read-red.log`、`/tmp/ft-demo-transfer-{entry-red,derived-revision-red}.log` |
| 共享回归及构建 | `FT_API_ORIGIN=http://127.0.0.1:58792 ./gradlew :webApp:wasmJsBrowserDevelopmentWebpack :shared:wasmJsTest :shared:testAndroidHostTest :androidApp:assembleDebug :shared:compileKotlinIosSimulatorArm64 --no-configuration-cache`：成功，1m30；Wasm 371、Android 主机 366 项，均无失败／跳过。日志 `/tmp/ft-cash-transfer-entry-final-matrix.log` |
| 真实 Web 双端编辑 | Chromium，`http://127.0.0.1:5308`，1440×900，专用合成 SQLite API `58792`。当前完整转账→修改联动金额→一次预览／提交→两端实际更新；重新打开→只改备注→无财务成员补丁；合成换汇→修改转入金额→完整双端补丁，仅实际一端字段改变。核对稳定对象、手动转账身份及实际金额。1/1 passed，26.6s，无新增控制台／请求／HTTP 错误；日志 `/tmp/ft-cash-transfer-edit-web-final.log`，截图 `/tmp/ft-compose-cash-evidence/transfer-edit-{linked,fx}-1440.png`。已修正冒烟脚本重复刷新取消资源及未等待动画的假失败，不把它们当产品问题；独立任务重新进入页面应对保留的 Compose 弹层树更新缺陷 |
| 公共编辑回归 | 相同最终产物、Chromium／1440×900，仓库 `compose-cash-entry.e2e.ts`：1/1 passed，4.2s；导入公共-only、一次预览提交、来源财务字段不可改。日志 `/tmp/ft-cash-transfer-edit-public-fixture.log` |
| 工具与规格 | Node 3/3、OpenSpec strict 37/37、doctor healthy、修改的 Markdown 本地链接／锚点及 `git diff --check` 通过。日志 `/tmp/ft-cash-transfer-edit-{node.log,specs.json,doctor.json}` |
| Hallmark／独立设计 | 对上述实际双端编辑截图执行 Hallmark audit、中文术语及可见文字必要性检查，独立设计复核 0 critical／0 major／0 minor。同一详情容器、转出／转入层级、联动与换汇金额、标准 Material 3、无重复说明；完整用户演示确认仍由 6.5 承载 |
| 范围及限制 | 只写合成账本。无新增性能或安全边界，不重复已通过且未受影响的后端全回归。Compose 1.12.1 关闭弹层树停更及禁用状态无障碍缺陷按用户决定保留，不升级。完整关系验收、旧路径清理、统一导入及用户演示确认不算完成 |

文档收尾：现金流水说明、功能地图和 OpenSpec apply 接线参考与当前真实入口对齐；复用现有术语，无新领域概念，不改词表。README、接口示例、部署及配置无变化，无需改写。前端小步可撤回接线，不恢复已关闭 PUT；无迁移。防复发由方向／完整组、未改金额字符串、公共-only 不回写财务字段、演示非法任一端保持整笔及派生修订回归保护。
