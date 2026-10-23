## 1. 思考

- [x] 1.1 通过 `grill-me → grilling` 确认保留现有外观、原详情容器和在流水内处理关系；对照当前列表、详情与维护协议核实缺口。

## 2. 计划

- [x] 2.1 完成 proposal、delta 与 design，明确列表／详情／补处理边界、公共基础、文件所有权及三模块依赖。
- [x] 2.2 完成独立规划复核及 OpenSpec strict，确认旧 UI delta 不与新责任重复；只声明规划就绪。

## 3. 任务拆分与一致性

- [x] 3.1 先补当前读取、`entry`／来源权限、维护所有意图、公共冲突／继承、草稿保存、计划／收据／核实的协议失败测试；验证 null、清空、修订、字符串金额和工作区隔离。
- [ ] 3.2 完成公共基础与宿主：
  - [x] 3.2.1 当前 DTO／domain／Repository、读取及维护 API、公共冲突和定位引用通过测试与构建。
  - [ ] 3.2.2 接入编辑宿主和稳定定位行为，冻结集中声明的文件归属。
- [ ] 3.3 提交公共基础小步，让导入和编辑模块基于同一版本开发；合成合同夹具、语义 ID／文案与最终 App 集成接口分别登记所有者。

## 4. 构建

- [ ] 4.1 以失败测试接列表状态／未知值／统计／待确认快捷筛选和快照翻页；保留原筛选、月汇总及批量选择行为。
- [ ] 4.2 以失败测试切换当前详情，整笔公共值总览、待处理区域和折叠只读来源，移除来源成员编辑入口。
- [ ] 4.3 以失败测试实现草稿普通保存、明确转正式、来源采用／保留及实际身份问题处理；不自动转正式或换目标。
- [ ] 4.4 以失败测试实现已有完整关系组确认／延后／拒绝／解除、公共冲突选择及解除继承；移除任意关联搜索和直接关系写入。
- [ ] 4.5 接入编辑器插槽、当前分类／删除影响及保存后的稳定定位，集成合成演示；删除无人使用的旧详情和维护适配，未实现动作保持准确受限状态。

## 5. 审查

- [ ] 5.1 在独立上下文复核产品范围、工程、API 可发现性、恢复与隐私边界，记录 finding、处置和回写；阻断项修复后重审。
- [ ] 5.2 对实际 Compose 页面执行 Hallmark audit、中文术语与可见文案必要性自检；独立设计复核清零 critical／major，不能用规划自查代替。

## 6. 测试与 QA

- [ ] 6.1 在 `compose/` 执行 `./gradlew :shared:wasmJsTest --no-configuration-cache` 及受影响 Android／iOS 测试；构建 `:webApp:wasmJsBrowserDistribution`、`:androidApp:assembleDebug`、`:shared:linkDebugFrameworkIosSimulatorArm64`，记录实际 HEAD、命令与结果。
- [ ] 6.2 在 `compose/qa/web/` 执行 `npm run test:node` 和受影响 Playwright，用生产预览走真实 API 主流程及正常／错误／空／loading／disabled／未知状态；记录浏览器、URL、390×844／768×1024／1440×900、步骤、截图及 console／network 结果。
- [ ] 6.3 验证 Android 与 iOS 对应 Compact／Regular／Wide 的真实入口、关键操作、系统返回和业务结果；缺设备／签名时记录未完成，不把共享代码或 Web 截图计为设备通过。
- [ ] 6.4 使用专用 SQLite 及显式 `_test` PostgreSQL 服务验证受影响真实请求与业务结果，不自动探测数据库、不写真实账本；演示只用去标识化合成场景。不得改变万行后端独立性能验收范围。
- [ ] 6.5 在 Compose 演示模式交付三类窗口及关键状态，取得用户演示确认后才勾选 UI 完成；执行 OpenSpec strict、链接和 `git diff --check`。

## 7. 发布

- [ ] 7.1 验证与审查后修订 README／业务说明／接口示例／功能地图和旧变更取代关系，原位清理过时表述，检查链接；无需修改的文档记录理由。
- [ ] 7.2 记录交付范围、依赖版本、回退和未解决风险；相称检查后按已授权仓库／分支小步提交推送，PR／合并／部署另需授权。不得通过回退恢复已关闭接口。

## 8. 反思

- [ ] 8.1 记录可复用防复发测试与必要规则，保持文档只表述当前结论；完成后再按实际依赖同步 delta，未实现内容不提前归档。

### 当前审查与验证

2026-10-24，公共协议小步代码基线 `HEAD=3f10e40`，比较基线 `8aa0b40`。仅新增维护协议客户端与模型、补齐导入公共对象／冲突选择；运行入口和 UI 尚未切换。Web、Android、iOS 消费共享协议，真实页面覆盖未提升。

| 范围／命令 | 当前结论 |
|---|---|
| 产品与合同独立复核 | 用户逐轮确认复用主页表格／详情、待确认优先、零待确认自动全部、文件上账户下、警示卡片、一次保存、摘要结果及统一提交异常返回。旧结果明细／失败派生任务和原命令重放措辞已修正并独立重审关闭 |
| 工程独立复核 | 当前详情形状、origin／权限、稳定公共对象、草稿收据、上下文三字段、精确金额／ID、公共冲突及字段清空均通过。阻断项：可选公共决定缺省 null 被后端拒绝；HTTP 回归先失败（274 项中 1 失败）再以定向省略修复，保留明确清空及 false。after_import_action 和公共展示字段已完整映射 |
| 失败测试 | `/tmp/ft-compose-maintenance-red.log`：缺维护接口；`/tmp/ft-compose-public-fields-red.log`：缺公共对象／冲突字段；`/tmp/ft-compose-choice-null-red.log`：可选 null 的实际出站请求失败 |
| `./gradlew :shared:wasmJsTest :shared:testAndroidHostTest :webApp:wasmJsBrowserDistribution --no-configuration-cache` | 通过，Wasm 274／Android 269 项，0 失败、0 skip；Web production 构建通过。报告 `/tmp/ft-compose-foundation-verified.log` |
| `npm run test:node` | 3 项通过，`/tmp/ft-compose-cash-node-tests.log` |
| OpenSpec／静态检查 | `openspec validate --all --strict --json` 37/37；`openspec doctor --json` healthy；`git diff --check` 通过 |
| 文档与词表 | 已核对当前接口、README 和功能地图；协议文件和字段引用正确，页面入口与使用方式尚未改变，README 无需改动。功能地图仅登记协议基础完成，页面与双端 TODO 保留。沿用 `DOMAIN_GLOSSARY.md`，无新概念，按中文规范复核 |
| 专项门禁 | 本协议小步不改后端、数据库或 UI，存储矩阵与视觉 QA 不适用；页面接入后必须继续真实 API／双后端联调、390／768／1440 浏览器与设备验证、Hallmark audit 和用户演示。iOS 编译待本轮共享基础后补跑；不能据共享代码认为设备通过 |
| 回退与观察 | 当前为 additive 接口，原页面未切换；回退该小步不改变存量数据。后续集成时不恢复已关闭写入口；关注明确清空和原键恢复 |
