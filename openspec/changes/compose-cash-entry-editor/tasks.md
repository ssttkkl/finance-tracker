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
- [ ] 4.2 接通普通手动新建／编辑及单入口转账模式，校验真实账户、同币种联动金额、换汇双金额的精确字符串和合法类型；不发送半笔转账或来源身份。
- [ ] 4.3 接通手动转账完整双端编辑，按真实成员与允许补丁共同预览／提交，覆盖失败全保留和受限只公共编辑。
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

规划证据见 [共同规划验证](../compose-cash-ledger-maintenance/tasks.md#当前审查与验证)。当前实施基线 `HEAD=0e523a1`，先接入当前详情中的整笔编辑；普通新建／转账和旧写入口退役仍未完成。

### 当前审查与验证

独立工程复核范围为字段权限、补丁序列化、真实修订和详情宿主的编辑生命周期。取消编辑残留旧影响计划的 major 已关闭：维护任务原位清空未发送计划，忙碌／未知状态拒绝放弃，确认回调再次检查；三项失败回归修复转绿，独立重审无新增 blocking／major。其余已审查的权限、修订和共享字段控件无 major。

Cross-platform Impact Check：共享表单、取消与保存语义影响 Web、Android 和 iOS；Web 真实冒烟，Android 编译和主机单测，iOS 共享编译，本轮 iOS 平台单测及设备 QA 按用户决定不执行。仅接入公共及普通手动编辑表单，双端转账、记账模式、旧入口删除及整个 change 演示确认尚未完成。

| 验证 | 当前结果与证据 |
|---|---|
| 基线／时间 | `HEAD=0e523a1`，比较基线同 HEAD；2026-10-24，工作树编辑小步，不含后端、存储或依赖变更 |
| 测试先行 | 输入权限五项、四项业务失败后转绿；取消生命周期三项失败后转绿。日志 `/tmp/ft-cash-entry-input-red.log`、`/tmp/ft-cash-entry-cancel-red.log`、`/tmp/ft-cash-entry-cancel-green.log`，最终定向 31 项通过 |
| 共享回归及构建 | `FT_API_ORIGIN=http://127.0.0.1:58792 ./gradlew :webApp:wasmJsBrowserDevelopmentWebpack :shared:wasmJsTest :shared:testAndroidHostTest :androidApp:assembleDebug :shared:compileKotlinIosSimulatorArm64 --no-configuration-cache`：成功，1m24；Wasm 344、Android 主机 339 项，均无失败／跳过；日志 `/tmp/ft-cash-entry-final-matrix.log` |
| 真实 Web 冒烟 | Chromium，`http://127.0.0.1:5308`，1440×900，开发产物指纹一致；外部专用合成 SQLite API `58792`。主页→详情→编辑→改备注→一次保存→来源原文；真实稳定修订、仅备注补丁、一次预览／提交、来源字段及分摊不变。1/1 通过，10.5s；日志 `/tmp/ft-cash-entry-real-web-smoke.log`；截图 `/tmp/ft-compose-cash-evidence/entry-{form,saved}-1440.png`；无新增控制台／请求／HTTP 错误 |
| 入口回归 | `compose-cash-entry.e2e.ts`：1/1 通过，6.5s；核对只展示可编辑项、真实对象修订、一次保存与当前详情刷新。日志 `/tmp/ft-cash-entry-fixture-web-smoke.log` |
| 工具与规格 | `npm run test:node`：3/3；`openspec validate --all --strict --json`：37/37；日志 `/tmp/ft-cash-entry-node.log`、`/tmp/ft-cash-entry-spec-validation.json`。5 份改动 Markdown 的本地链接／锚点及最终 diff 检查通过 |
| Hallmark audit | 按仓库 audit 及 anti-patterns 对照实际上述截图与 Compose，沿用 FinanceTheme／M3，固定摘要→字段→保存，未增加说明区或装饰；当前自查 0 critical／0 major／0 minor。中文字段与文字必要性已复核；独立工程／产品／设计复核同意该小步提交，0 critical／0 major／0 minor；实际页面验收仅覆盖导入公共编辑，手动财务表单本轮只有权限／序列化单测 |
| 范围及限制 | 无后端存储行为变更，不重复后端双库矩阵；真实冒烟写入仅合成库。Compose 1.12.1 关闭弹层树停更及禁用状态无障碍缺陷按用户决定保留；本小步未改变相关依赖。完整关系、转账、统一导入和用户演示确认不算完成 |

文档收尾核对：更新现金流水操作说明、功能地图及 OpenSpec apply 接线参考；复用既有术语，无新领域概念，不改词表；根 README、部署及接口示例无新接口／配置，保持不变。回退为撤回本前端小步，不能恢复旧 PUT 写入。防复发测试明确约束取消必须同时撤销未发送确认计划，不能仅以表单关闭推断命令已放弃。
