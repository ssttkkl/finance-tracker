## 1. 思考

- [x] 1.1 按项目 grilling 明确范围；用户确认同时修复 26 个浏览器失败与 Python 测试收集，以 183/11 全部通过验收，不改变财务语义或重做页面。
- [x] 1.2 阅读项目上下文、词表、编码/UI 规范、主规格与现行代码；独立事实调查覆盖原 26 项，按 A 类完成 explore。

## 2. 计划

- [x] 2.1 完成 proposal、完整 delta 与 design，记录平台语义边界、现行合同与旧测试差异、失败/回滚和跨端策略。
- [x] 2.2 完成 Material 3 正文核对与 Hallmark 范围化预检，保留既有信息架构、组件、视觉和文案，不做页面重构。

## 3. 任务拆分

- [x] 3.1 对齐八组原始失败、Python 收集缺口、实施文件、独立审查、183 项验收、演示和文档收尾。

## 4. 构建

- [x] 4.1 在隔离 worktree 基线复现代表浏览器失败及 Python 6/11 收集差异，先记录红结果。
- [x] 4.2 修复 Python 入口，两种入口全部 11 项通过。
- [x] 4.3 修复导航、筛选、日期、布局断言与分类/成员模拟数据一致性；保留原用户行为和请求合同。
- [x] 4.4 补齐四个受影响 Web 控件的真实语义、月份头标识及搜索后的键盘焦点；快速提交关闭读取实时任务保护，不改财务规则。
- [x] 4.5 第一版提供草稿状态及筛选弹窗演示；用户确认符合保持现有页面设计的要求，继续回归与审查。

## 5. 审查

- [x] 5.1 独立工程、范围、安全及 diff 审查，按产品编码规范核对生命周期、模拟合同、状态边界与测试强度，finding 清零。
- [x] 5.2 独立 codebase-degradation-review 调度 UI 复用审查，finding 清零；完整契约专项不触发，未修改生产 DTO、领域、存储或后端接口。
- [x] 5.3 最终 Hallmark audit 与独立设计复核覆盖语义、焦点及关闭保护增量，无 critical、major 或 minor finding。

5a/5b 发现的断开 ShadowRoot 命中已修复：仅缓存连接目标，缺失时查找开放 Shadow DOM，组合销毁时停止观察并还原属性。真实 Chromium 生命周期回归先红后绿，覆盖晚 attachShadow、替换宿主、状态转换及重注册。分类 POST/PATCH 目录修订和路径一致，生产校验未放松。UI 未增加重复组件、布局、token 或业务模式。

快速提交保护经独立复核：整体关闭复用原 `canLeaveTask`，取消编辑仍拒绝所有 busy/unresolved 状态，事件时读取同一实时任务快照。手工编辑和登录锁定测试使用实际点击及键盘，不强制聚焦透明镜像；登录响应显式挂起，邮箱、密码、单次请求及错误后输入保留均验证。成员处理在网络/字体/布局稳定后取坐标，来源证据版本和确认状态与当前投影一致。日期滚动先确认位置稳定，耗尽则报错，不继续使用旧坐标；取消恢复和 Apply 参数断言完整保留。刷新骨架用例仅在 Regular 表格就绪后稳定单击，有界检查三阶段请求及支出参数，原加载/失败/重试断言完整保留，重复专项 3/3 通过。以上增量分别独立复核，无剩余 finding。

设计复核直接查看三宽草稿、筛选、日期、投资、月份几何及最终关闭保护截图。备注恢复/保留为空在刷新前的原尺寸画面已核对；刷新重开只额外验证读回及恢复旧镜像，不替代即时画面。提交挂起时侧板保留且无放弃弹窗，释放后同页显示新备注；导入关闭后实际返回账本。真实读屏体验及全页性能未覆盖，不宣称通过。

## 6. 测试与 QA

- [x] 6.1 最终固定快照完整 183 项 Web E2E 通过；Python 两入口各 11 项、Node 工具测试 5 项已通过，不删测或跳过失败案例。
- [x] 6.2 共享 Wasm 514 项及受影响 Web/Android/iOS/JS 编译通过；浏览器代表宽度 390、768、1440，覆盖关键交互、状态及控制台/网络错误。
- [x] 6.3 最终严格 OpenSpec、diff、文档链接/命令、术语及实现快照检查完成。

## 7. 发布

- [x] 7.1 核对 README、功能地图、词表、技能和当前规格，回写实际行为、验证、限制与用户决策反思。
- [x] 7.2 主规格已同步，本 change 已归档；明确已授权的同仓库目标分支、PR、发布边界与回滚方式。

## 当前验证证据

基线及受测 HEAD：`805eca60c5cafe8c1a80b9793dddb77574192b86` 加当前工作树改动。比较基线相同；隔离分支 `fix-web-regression-gates`，路径 `/Users/huangwenlong/.paseo/worktrees/1zalea72/web-regression-gates`。执行日期 2026-11-05（Asia/Shanghai）。仓库外证据目录为 `/Users/huangwenlong/Library/Caches/finance-tracker/web-regression-gates-qa`，`implementation-snapshot.json` 固定最终运行的源码、测试和配置散列；浏览器状态及匿名 SQLite 测试库均在仓库外。

| 验证 | 实际命令及当前结果 | 证据 |
| --- | --- | --- |
| 失败基线 | 代表 8 项中 6 失败、2 通过；Python 直接入口 6 项、discovery 11 项 | `baseline-web.log`、Python 基线日志 |
| Python 全量收集 | `python3 compose/tests/test_build_wasm_web.py`；`python3 -m unittest discover -s compose/tests -p 'test_build_wasm_web.py'`，各 11 项通过 | `python-direct-green.log`、`python-discovery-green.log` |
| Node 工具及真实 DOM | `npm --prefix compose/qa/web run test:node`，5/5 通过 | `node-green.log` |
| 共享及跨端构建 | `./compose/gradlew -p compose :shared:wasmJsTest :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64 :shared:compileKotlinJs :androidApp:compileDebugKotlin --console=plain`，514 项零失败/零跳过，所有目标编译通过 | `shared-validation-final.log`、共享 JUnit XML |
| 增量真实浏览器 | 快速关闭/导入 4/4、登录锁定 1/1、成员处理三宽 3/3、日期及相邻筛选 3/3、共用输入 29/29、账户测量三宽 3/3 通过；文件密码显式掩码 1/1、焦点完整 trace 诊断 5/5、刷新骨架重复专项 3/3 | `guard-final-synced.log`、`login-busy-verified.log`、`member-issues-final.log`、`date-scroll-final.log`、`input-helper-stable.log`、`account-measure-stable.log`、`password-display-final.log`、`focus-diagnostic.log`、`resize-skeleton.log` |
| 最终完整 Web | `FT_COMPOSE_PREVIEW_PORT=5187 FT_API_PROXY_ORIGIN=http://127.0.0.1:19261 npm --prefix compose/qa/web run test:e2e -- --output /Users/huangwenlong/Library/Caches/finance-tracker/web-regression-gates-qa/web-final-results`，183/183 通过，用时 15.8 分钟，零失败/零跳过 | `web-final.log`、`web-final-results/` |
| 规格及工程检查 | `openspec validate --all --strict` 归档前 27/27；归档后 26/26，工作流 YAML 解析、当前文档链接及 `git diff --check` 通过 | `openspec-all-validation.log`、`openspec-all-validation-final.log`、`final-doc-links.json` |

最终完整运行于 2026-11-05 完成；183 项均实际执行通过，含同源代理到本机 Kotlin 后端的真实注册流程。运行前后的 1,958 个源码、测试和配置文件散列一致。旧失败及中断快照仅保留为诊断证据，不混入最终结果；曾发生 ENOSPC 后只清理本任务的可再生输出，失败 trace、截图、日志、XML 和匿名库均保留。

跨端影响：Web 接入有限语义导出与焦点桥；共享页面仅绑定同一真实状态、实时关闭保护及稳定月份标识；Android/iOS 保持原生 Material 语义，以共享单测和编译覆盖，不新增设备/模拟器交互 QA。无存储、金额、币种或关系规则变化，不触发新增双后端/专用性能/安全矩阵，也不据此宣称历史 1,219 项业务证据已重跑。

验证边界：完整 Android Host 入口实际执行 544 项，其中 19 因缺少外部 HTTP 夹具环境失败、17 原有条件跳过，未记为通过。当前共享回归采用 CI 同一 Wasm 入口；Host HTTP 集成合同补跑须先配置合成响应目录 `FT_KOTLIN_CLIENT_CONTRACT_DIR`、`FT_KOTLIN_INVESTMENT_READ_CONTRACT_DIR`，启动本机投资服务并提供 `FT_KOTLIN_PORTFOLIO_HOST_FILE`。并发预览构建曾冲突生成资源，最终上述 Gradle 组合顺序执行通过，没有修改产品代码规避构建。Compose CMP-10623 的全局弹层镜像恢复缺口仍保留；本次仅修有限控件，不宣称整体无障碍 QA 通过。

文档收尾范围为 Compose README、Web QA/CI 命令、共享及账本功能地图、词表、OpenSpec 和 apply 技能；没有新领域概念，无需改词表。用户选择同时恢复 183 项浏览器验收和 Python 全量收集，并保持现有页面设计，已有范围控制与测试真实性原则足够，无需新增或修改通用规范；实现细节不沉淀为用户偏好。

交付沿用用户已授权的 `ssttkkl/finance-tracker`、`origin/continue-kotlin-backend-refactor` 及指向 `main` 的 PR #112。隔离验证分支不直接推送；没有合并或 Render 部署授权。回滚本 change 的代码提交即可，不涉及数据回滚。
