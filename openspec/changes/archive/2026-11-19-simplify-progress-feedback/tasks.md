## 1. 思考

- [x] 排查正式产品 Web、Android、iOS 的共享 Compose 页面、弹层及平台呈现。范围包括账户导入、流水保存、关联、普通记账及转账、工作区、分类和投资。梳理直接进度反馈、未知写入、安全重放和必须由用户决定的业务取舍。
- [x] 按 A 类执行。跨模块变更引入公开命令、收据事务和兼容发布顺序。UI 使用现有布局、表单、Material 3 进度及按钮，无新信息架构。

## 2. 计划

- [x] 已对齐进度只显示一处、原业务按钮接续、返回账户匹配刷新、不能完成依赖未决账户的导入等边界。采用当前设计规则和现有 token，参考 Compose 界面；Pen 文件是历史参考。
- [x] 设计固定用户、工作区、账户 ID、操作键与内容。现有旧 API 保留，回执和领域写入同一事务。删除和分类在原范围主动查询，不从列表猜测提交结果。

## 3. 任务拆分

- [x] 进度与文案；账户创建和补币种；导入、流水及关联沿用原按钮；记账和转账新增命令及双后端测试；删除及批量分类主动刷新；最后完成跨端验证。

## 4. 构建

- [x] 先用目标测试复现账户 Save 未重发及新命令路由缺失，再接通。覆盖相同键和原数据、重复点击、响应异常、身份变化、账户改名、无效输入及版本变化。

## 5. 审查

- [x] 独立产品、工程、契约和 UI 复用审查。修复关闭后仍应可在同一会话接续、恢复后读到历史账户、刷新失败损坏旧缓存、权限变化、同名账户错绑及业务拒绝锁死。
- [x] 按 Hallmark audit 与独立设计复核检查按钮位置、用户用语、运行与失败状态、遮罩层及滚动。分类、流水和工作区的删除状态由当前可见层承接；账户、记账和分类编辑的进度条固定。实际截图覆盖合成数据，无完整读屏或原生设备交互。

## 6. 测试与 QA

- [x] 基线与当前工作树比较点 `f58a670f`，2026-11-19。`openspec validate --all --strict`、`openspec doctor` 与 `git diff --check`。
- [x] 真实 SQLite、专用 PostgreSQL `feedback_recovery_test`。运行 `:backend:test --tests '*CashCreationCommandHttpTest' --tests '*OpenApiContractTest' --tests '*CashImportCurrencyRequirementsHttpTest'`，21 项通过，无跳过。
- [x] JS、Wasm、Android Debug 与 iOS Simulator framework 构建通过。Android Host 自包含测试 1086 项，iOS Simulator 997 项，均通过。完整 Android 入口需另建历史 HTTP 合成夹具及投资 live 宿主，本次自包含运行通过外部 Gradle init 排除相关历史测试。受影响的新 HTTP 合同已单独跑通双库。
- [x] 用户确认「保存遇到问题，请重试。」及原保存、返回交互。
- [x] 最终 Wasm 产物在本地预览 `127.0.0.1:5230` 通过 5 项 Chrome 冒烟。覆盖窗口宽度 390、768 和 960 px，账户保存进度、失败重发、返回后刷新及重开，手工记账重发，账单读取与退出提示，工作区访问错误及分类编辑。截图记录在 `/tmp/compose-progress-account-960.png`、`/tmp/compose-account-network-retry.png` 和 `/tmp/compose-manual-save-retry.png`。

## 7. 发布

- [x] 更新设计规则、功能地图和新接口 OpenAPI。业务规则沉淀为进度和接续原则，后端上线顺序及回滚写入 design。无独立迁移或配置切换。
- [x] 已确认内容符合交付范围。同步主规格并随此次提交归档，使用当前任务分支。原反馈 PR #119 已合并。
