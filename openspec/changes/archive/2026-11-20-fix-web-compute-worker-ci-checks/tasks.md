## 1. 思考

- [x] 1.1 读取 PR #123 的失败检查、相关主规格、Worker 测试入口及 Karma 代理配置，确认三组失败分别来自测试环境未提供 Worker 产物、启动诊断日志级别与管理页字体加载时序。
- [x] 1.2 按 C 类局部修复处理：根因明确，改动限于测试构建、测试时序与已处理分支的 Console 级别；生产 Worker 计算、主规格和财务语义不变。

## 2. 计划

- [x] 2.1 浏览器测试复用 `:webApp:stageCashImportWorker` 的版本化产物，通过既有 Karma 代理提供 `/cash-import-worker/`，并声明任务依赖与输入。
- [x] 2.2 不支持 Wasm 的启动分支改用普通 Console 记录，保留本地诊断与 Sentry 上报；管理页用例在读取边界前等待字体并确认边界稳定。
- [x] 2.3 更新导入测试替身以支持 Worker 分页归档；依赖真实 Worker 的 ViewModel 用例等待浏览器事件循环。

## 3. 任务拆分

- [x] 3.1 先复现 `:shared:wasmJsTest` 的 88 项失败及 E2E 的 4 项失败，再按失败类别拆分修复与验证。

## 4. 构建

- [x] 4.1 新增 `compose/shared/karma.config.d/cash-import-worker.js`，并在 `shared/build.gradle.kts` 绑定 staging 任务与测试输入。
- [x] 4.2 调整 `startup.js` 启动诊断级别、`compose-fixtures.ts` 布局等待及三个受影响测试的断言/等待方式；按平台显式断言预览计划标识分配。
- [x] 4.3 只恢复既有行为，不修改 Worker 协议、计算逻辑、主线程预算或数据格式。

## 5. 审查

- [x] 5a 范围化复核：测试构建依赖、Karma 代理路径、诊断字段与日志级别、测试时序及最终 diff；未发现阻断项。
- [x] 5a 独立复核处置：Worker 路径改由 Gradle 传入并在缺失时失败；计划标识按平台显式断言且成功预览必须取得新标识；ViewModel 等待上限提高到 30 秒；布局读取改为三次稳定采样。
- [x] 5b 涉及契约或 UI 的代码变更专项不适用：生产契约与 UI 布局未变，改动仅为测试等待与已处理分支日志级别。

## 6. 测试与 QA

- [x] 6.1 `:shared:wasmJsTest` 通过 1037 项、0 失败；同命令在 `--configuration-cache --configuration-cache-problems=fail` 下首次保存、再次复用配置缓存。
- [x] 6.2 `compose/qa/web` Node 检查 43 项通过，含新增的不支持 Wasm 启动分支回归；管理页布局用例在字体等待版本重复 6 轮、稳定边界版本重复 2 轮均通过。
- [x] 6.3 最终完整 Chrome E2E 套件 54 项通过、8 项跳过、0 失败，覆盖 4 个原始失败场景及导入/离线/诊断主流程；此前同一套件在修复前复现 4 项失败。
- [x] 6.4 `FT_COMPOSE_DEMO_MODE=true ./gradlew :webApp:wasmJsBrowserDistribution --no-configuration-cache` 通过且生成 `COMPOSE_DEMO_MODE: Boolean = true`；`python3 compose/tests/test_build_wasm_web.py` 11 项通过。
- [x] 6.5 `openspec validate --all --strict` 通过；`git diff --check` 通过。
- [x] 6.6 `:shared:testAndroidHostTest` 与 `:shared:iosSimulatorArm64Test` 的 `LedgerSessionImportsTest` 定向运行通过，覆盖 commonTest 改动的双平台编译与平台化断言。
- [x] 6.7 远端 PR #123 的 `Compose client`、`Compose shared and native checks`、`Compose Web E2E` 与 `Backend (Kotlin)` 全部通过（提交 `9261eccb`）。`Compose shared and native checks` 首次运行有 2 项 Worker 用例瞬时失败，同一提交的 `Compose client` 与重跑通过；`Backend (Kotlin)` 的既有 `CcxtSdkLifecycleTest` 时序失败也在重跑通过。保留为 CI 资源竞争下的残余风险。

## 7. 发布

- [x] 7.1 本次没有用户可见文案、业务术语或数据格式变化；用户决策限定为修复 PR 检查失败，没有值得沉淀的新通用规则。
- [x] 7.2 已归档本 change 为 `2026-11-20-fix-web-compute-worker-ci-checks`；本次没有需要同步的主规格 delta。
- [x] 7.3 已提交为 `9261eccb` 并推送 `dazzling-husky`，PR #123 必需检查全部通过。

## 验证基线

分支 `dazzling-husky`，代码基线 `690f3df335799ed6c94417d28d5569c9a4240cdb`，比较基线 `origin/main`=`315d710429244fe6a6d5b19486a738464f93f8f2`。2026-11-20 UTC 在 macOS 本机完成上述本地验证：`:shared:wasmJsTest` 覆盖 1037 项；Node 检查 43 项；完整 Playwright Chromium 151 套件运行于 `http://127.0.0.1:5187/`，54 项通过、8 项跳过、0 失败；管理页布局用例在字体等待与稳定边界两版分别重复 6 轮和 2 轮通过。远端 PR 检查于 2026-11-20 UTC 全部通过；详见 6.7。
