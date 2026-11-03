# 导入页加载失败阻断

## 1. 思考

- [x] 阅读导入入口、账户恢复、呈现主规格、UI 规范与现有测试。根因是辅助数据加载中和失败共用 `!auxiliaryReady`，账户恢复失败没有统一阻断。
- [x] 用户授权将既有 B 类 proposal 一起推进并提交同一 main PR；目标、边界与验收沿用 proposal。不改变领域、金额、持久化、权限、路由、接口或依赖。

## 2. 计划

- [x] proposal 与 `shared/cross-platform-presentation` delta 定义加载中、失败、正常及操作错误边界。
- [x] 使用既有 Material 3 错误文字和标题关闭入口；失败时替代步骤内容，不增加恢复控件或本地降级。

## 3. 任务拆分

- [x] 判定单测覆盖辅助失败、账户未就绪、请求在途、就绪后操作错误。
- [x] Web 操作覆盖失败阻断、关闭返回、重新进入成功，以及加载中没有失败提示。

## 4. 构建

- [x] `CashImportTaskScreen.kt` 明确辅助失败，删除裸重试入口；统一阻断判定。
- [x] 添加 `CashImportBlockingFailureTest` 和辅助失败 Web 回归。旧 HEAD 构建的同一 Web 回归为 1 failed（仍显示重试和步骤区），当前构建通过。

## 5. 审查

- [x] 5a 根因、范围和恢复路径独立复核。
- [x] 5b `$codebase-degradation-review` 调度 `$ui-reuse-review`；契约模型无变化，不触发契约专项。
- [x] Hallmark audit 与实际页面独立设计复核，记录发现及处置。

## 6. 测试与 QA

- [x] 当前单测、Android 相称编译、iOS 编译、Web 构建通过。
- [x] 真实 Chrome 冒烟及截图，验证错误、加载和重新进入。
- [x] OpenSpec strict 28 passed、doctor、当前 diff 检查通过。
- Cross-platform Impact Check：共享 `commonMain` 影响 Web、Android、iOS，三端使用相同失败文案及阻断判定。默认原生验证仅编译和单测；不执行设备交互。
- 无数据或存储行为变化，不需本 change 单独运行双后端矩阵；并发追加 change 独立验证。

## 7. 发布

- [x] 核对功能地图、流程文档、词表和参考入口。
- [x] 记录 HEAD、基线、时间、命令与结果。回滚恢复原失败显示及重试入口，无迁移。
- [x] 按用户授权与原文件名、并发追加一起提交推送并创建 PR 到 main（本次交付）；演示确认与归档待办，等待 PR 检查。


## 验证证据与审查结论

- HEAD/比较基线 `6c436265904da59a75ece6722f3f48a95e345f4e`，分支 `fix-import-original-filename-display`，2026-11-02。本次验证针对该基线上的工作树 diff。
- 旧 HEAD 独立 worktree 的 production 构建通过；同一 `F-03 导入页辅助数据加载失败时阻塞流程且不提供重试` Chrome 回归因仍出现重试/步骤区而失败，见 `/tmp/ft-cash-import-qa-20261102/blocking-baseline-red.log`。不是编译失败或环境失败。
- 当前 `:shared:testAndroidHostTest :shared:compileKotlinIosSimulatorArm64` 完整共享 471 passed，Android/iOS 编译通过；production Wasm 构建通过。
- Chrome、390×844、QA URL `http://127.0.0.1:5187`：辅助失败、accounts/categories失败后关闭重进、加载中、已恢复任务关闭不取消通过；固定截图 `/tmp/ft-cash-import-qa-20261102/review-final/`。无非预期控制台/网络错误。
- 独立工程和 UI 复用复核无剩余 finding；关闭原来可能依赖失败取消请求的问题已修复并有浏览器回归。Hallmark 与独立设计复核：0 critical、0 major、0 minor（本变更范围）。标题关闭固定，错误短句唯一，无裸重试或步骤操作。
- 文档已核对导入流程、协议、功能地图和词表；没有新概念、配置、迁移或命令，README/其他 references 无需改动。未运行原生设备交互、额外视口/主题，按默认验证范围不宣称覆盖。
