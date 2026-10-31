## 1. 思考

- [x] 1.1 阅读 UI 规范、token 用法、词表、主规格与共享月份表头；需求由用户明确，无未决产品决定。B 类局部布局，不涉及 A 类升级条件。

## 2. 计划

- [x] 2.1 记录 compact 月份独占首行、每币种横排右对齐及共享范围，保持其他尺寸和财务规则。

## 3. 任务拆分

- [x] 3.1 浏览器几何回归先失败，再实现共享表头；安排跨端编译、真实浏览器与演示确认。

## 4. 构建

- [x] 4.1 几何回归先在旧演示失败：币种与收入中心相差 18.5px（预期 <3px），再修改共享表头；每币种横排复用同一内容，compact 月份独占首行。

## 5. 审查

- [x] 5.1 独立范围／UI 复用及最终设计复核通过；账本与导入 390 的初始、无合计和吸顶截图均无裁切、逐字换行或横向溢出，0 critical、0 major。Hallmark 运行时不可用，按 audit 参考完成人工替代：延续既有主题、表头分组和金额层级，无新增装饰或重复标签，0 新增 finding。图审未覆盖原生设备；768／1440 以实际几何回归覆盖。

## 6. 测试与 QA

- [x] 6.1 共享单测与 Android／iOS／JS 编译通过。真实 Chromium 的 390／768／1440 月份几何回归 3 passed，compact 账本／导入、全部／待确认、无合计及吸顶冒烟通过；控制台运行错误和失败请求为空。
- [x] 6.2 最新离线演示已全量重建（1m51s、49 tasks）并启动于 `http://127.0.0.1:5198/w/demo-workspace/`，用户已确认本轮截图及布局效果。

## 7. 发布

- [x] 7.1 功能地图、完整 delta 与主规格、词表及文档已核对，发布准备完成；无迁移或新依赖，回滚为还原本次布局提交。用户已确认布局，按既有授权归档并提交推送到 `fix/cash-ui-audit-findings`，交付入口为 [PR #107](https://github.com/ssttkkl/finance-tracker/pull/107)，目标 `main`。

当前证据：2026-10-31，基线／执行 HEAD `84cd819abf50f9ea97196f6bf51938531a9642c4`，分支 `fix/cash-ui-audit-findings`。独立 5b 按 `$codebase-degradation-review → ui-reuse-review` 复核共享表头、调用点、fixture 与几何测试，确认三尺寸复用同一汇总内容、主题 token 和金额格式；无新增 finding。契约专项不适用。未覆盖的实际视觉和无障碍交最终图审。

`cd compose && ./gradlew :shared:testAndroidHostTest :androidApp:compileDebugKotlin :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinJs --offline`：BUILD SUCCESSFUL（23s，49 tasks）；共享 Android Host 448 tests、0 failed、0 skipped。原生设备交互不在本轮默认验证范围。`openspec validate compact-cash-month-summary-rows --strict` 与 `git diff --check` 通过。后端、双数据库、性能和财务计算未变化，不重复运行对应回归。

浏览器回归命令：`cd compose/qa/web && FT_COMPOSE_SUMMARY_DEMO=1 node node_modules/playwright/cli.js test -c playwright-report/summary-local.config.ts`，针对与最新构建指纹一致的离线演示执行 3 个实际窗口，3 passed（4.9s）。标准 CI 测试使用同一测试文件及多币种 API fixture；本轮未为测试数据重复构建正式 API 模式。必要截图保存在 Git 忽略目录 `compose/qa/web/playwright-report/compact-month-summary/`，覆盖账本初始／吸顶和导入无合计／三币种／吸顶。无截图或运行缓存写入 `/tmp`。

文档收尾：功能地图 F-03／F-05 同组描述 compact 月份布局；词表及 README 无新概念或运行方式变化，无需修改。主规格已按完整 delta 同步并逐块核对一致；归档保留完整记录。最终 `openspec validate --all --strict` 归档前为 26 passed、归档后为 25 passed；`openspec doctor` 正常，`git diff --check` 通过。用户确认后源码与依赖未变化，已有测试证据仍适用；收尾只同步文档、规格与归档，不重复编译。
