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

规划证据见 [共同规划验证](../compose-cash-ledger-maintenance/tasks.md#当前审查与验证)。公共整笔编辑小步已提交 `2b9833a`；当前实施基线同 HEAD，完成普通记账、转账直接创建及普通金额编辑，完整双端编辑和旧路径删除仍未完成。

### 当前审查与验证

Cross-platform Impact Check：共享表单、模式输入、取消与保存语义影响 Web、Android 和 iOS；默认 Web 冒烟、Android 编译及主机单测、iOS 共享编译。本轮 iOS 平台单测及设备 QA 按用户决定不执行，不算通过或默认阻断。直接转账响应的通用 JSON 列表递归缺陷纳入本 change；不改变金额、数据库或写入事务。

独立工程／产品复核：普通及转账输入、账户币种、本地时间换算、精确字符串、单次提交与未知停止重发无 major。演示经济类型错误的 major 已修复为 `internal_transfer` 与正式统计资格，并经筛选／精确换汇金额回归和独立重审关闭。真实 HTTP 非空转账结果序列化错误已用双后端先红复现，一行列表递归修复后重审无 blocking／major；保留真实双端及内部确认关系的断言。公共编辑取消残留旧计划的 major 已在 `2b9833a` 关闭，未引入新的取消路径。

| 验证 | 当前结果与证据 |
|---|---|
| 基线／时间 | `HEAD=2b9833a`，比较基线同 HEAD；2026-10-24，普通记账／转账创建及响应修复工作树，无依赖或数据库结构变化 |
| 测试先行 | 创建输入 4 项失败后转绿；模式／未知／忙碌状态 4 项失败后转绿；演示分组及真实经济类型回归先失败后修复；转账类型文案新增失败回归；演示普通创建账号属性及显式公共保护新增回归先失败后转绿。日志 `/tmp/ft-cash-creation-{input-red,state-red,demo-red,demo-contract-red,green}.log`、`/tmp/ft-cash-transfer-label-red.log`、`/tmp/ft-cash-creation-demo-public-red.log` |
| 响应根因及双后端 | `FT_TEST_POSTGRES_URL` 显式指向人工准备的 `ft_cash_entry_test`，`FT_REQUIRE_TEST_POSTGRES=1 PYTHONPATH=tests:tests/contract uv run pytest tests/test_web_serialization.py tests/contract/test_cash_transfer_http_response.py -q --disable-warnings --tb=short`：5 项原先均报 Decimal JSON TypeError；最终 5 passed、无 skip（9.97s）。覆盖同币种／换汇一次写入双端及一条活动确认内部关系；`/tmp/ft-cash-transfer-response-{red,final-dual}.log` |
| 受影响 HTTP | 双后端执行上述两个文件及 `test_cli_replacement_api.py test_cash_manual_create_boundary.py`：28 passed；`test_web_api.py test_investment_web_api.py`：42 passed；均无 skip。日志 `/tmp/ft-cash-transfer-response-{green,http-regression}.log` |
| 完整后端回归 | `uv run python /tmp/ft-transfer-response-full-regression.py`：contract 三分片及独立非 contract，清除 PostgreSQL 环境，`-q -m 'not performance' --disable-warnings --tb=short --maxfail=5`；仅排除已登记的两项旧 Expo 文档断言。3392 passed、1297 skipped、73 deselected，四进程均 exit 0；skip 不算双后端通过。完整参数／日志 `/tmp/ft-transfer-response-full-{0,1,2,3}.{json,log}`，最长 751.57s |
| 共享回归及构建 | `FT_API_ORIGIN=http://127.0.0.1:58792 ./gradlew :webApp:wasmJsBrowserDevelopmentWebpack :shared:wasmJsTest :shared:testAndroidHostTest :androidApp:assembleDebug :shared:compileKotlinIosSimulatorArm64 --no-configuration-cache`：成功，1m22；Wasm 358、Android 主机 353 项，均无失败／跳过；包括最终演示公共值保护及类型文案回归。日志 `/tmp/ft-cash-creation-final-matrix.log` |
| 真实 Web 创建及编辑 | Chromium，`http://127.0.0.1:5308`，1440×900，专用合成 SQLite API `58792`。主页→普通记账填值→转账填值→切回收入保存→当前详情金额编辑→新建同币种转账→确定 201／当前完整组；模式不串输入，仅提交当前模式，金额字符串精确。后端响应修复后 1/1 passed（41.1s），无新增控制台／请求／HTTP 错误；`/tmp/ft-cash-creation-real-web-smoke.log`；截图 `/tmp/ft-compose-cash-evidence/create-{ordinary,transfer,manual-edit,saved}-1440.png`。任务间刷新用于已登记的弹层无障碍缺陷，不记作修复 |
| 列表类型冒烟 | 最终产物指纹一致；同一 Chromium／1440×900／真实合成 API，确认类型显示“转账”、点击转账快捷筛选后只展示完整转账，未发送任何 POST；1/1 passed，日志 `/tmp/ft-cash-transfer-label-web-final.log`，截图 `/tmp/ft-compose-cash-evidence/create-transfer-filter-1440.png` |
| 打包／类型 | `uv build --wheel --out-dir /tmp/ft-cash-creation-dist` 成功，wheel 中 `ft/web/serialization.py` 与当前源码逐字节相同；`uv run python -m compileall -q src/ft/web/serialization.py` 通过；不重复已登记范围外的 sdist 问题 |
| 工具与规格 | Node 3/3、OpenSpec strict 37/37、doctor healthy、7 份 Markdown 本地链接／锚点及 diff 检查通过；日志 `/tmp/ft-cash-creation-{node.log,spec-validation.json,doctor.json}` |
| Hallmark／独立复核 | 对上述实际普通记账、转账、金额编辑及转账筛选截图执行 Hallmark audit、中文术语及文字必要性检查，独立设计复核 0 critical／0 major／0 minor。工程／产品／最终 diff 无剩余阻断；演示公共属性新增失败回归和当前全绿矩阵关闭最后待决项。完整演示及用户确认仍由 4.5／6.5 承载，不以本小步审查代替 |
| 范围及限制 | 只写合成账本。无存储行为变化，新增双库 HTTP 验证现有写入与响应；无新增性能／安全依赖边界。Compose 1.12.1 关闭弹层树停更及禁用状态无障碍缺陷按用户决定保留，不升级。完整双端编辑、关系验收、旧路径清理、统一导入及用户演示确认不算完成 |

文档收尾：现金流水说明、功能地图和 OpenSpec apply 接线参考与当前真实入口对齐；复用现有术语，无新领域概念，不改词表；根 README、部署及配置无变化。修复结果按精确字符串 JSON 返回，不增加自动重发或收据保证。回退可撤回当前前端接线；响应修复无数据迁移，不重启旧 PUT。防复发由模式独立状态、未知禁重发、非空真实双端 HTTP 响应和演示真实经济类型回归保护。
