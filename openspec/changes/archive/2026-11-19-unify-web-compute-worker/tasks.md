## 1. 思考

- [x] 复核计算 Worker 共用机制、所有调用端、公开规格和 SQLite 独立生命周期。基线 `a45cbc445e5404838b61f6c17a3ee92b2849f396`。

## 2. 计划

- [x] B 类，限定为已批准的现有 Web 计算调用统一，按现有产品合同判定验收。

## 3. 任务拆分

- [x] 先写消息关联失败回归，再实现调度、调用封装和相称验证。

## 4. 构建

- [x] Node 先因旧平铺消息缺少任务和请求编号失败。实施十个具名任务、回包关联和统一挂起桥接后通过。覆盖迟到回包、重复就绪、取消、无对应请求回包及两类实例故障隔离。

## 5. 审查

- [x] 独立工程复核了十个任务、旧任务迟到回包、取消、接口类型、内容指纹及会话退出边界。实际 Wasm 入口探针通过合成 PDF 字节转移、文件限额、内部错误形状和未知任务拒绝。回包类型校验由具名调用声明。无公开业务合同、持久化或 UI 变化，专项契约和 UI 复用审查不适用。

## 6. 测试与 QA

- [x] Node、Web Wasm 生产构建和真实浏览器受影响场景。
- 2026-11-19，`./gradlew :shared:compileKotlinWasmJs --offline --console=plain` 通过，`./gradlew :webApp:wasmJsBrowserDistribution --max-workers=1 --offline --console=plain` 通过。核对本地预览实际服务带请求关联的脚本。
- `compose/qa/web/` 执行 `npm run test:node`，36 项通过。另覆盖旧流水连接关闭后的迟到回包、新连接及正在执行的导入各自独立。
- Android、iOS 与 Kotlin/JS 无受影响实现。本次 Kotlin 改动位于 `wasmJsMain`，SQLite 连接协议和服务端均不涉及。
- Chrome 157.0.8103.79 使用 `http://127.0.0.1:5191` 的新生产包，`npx playwright test --config /tmp/ft-worker-playwright.mGdBFL/playwright.config.ts --workers=2 compose-local-import.e2e.ts compose-local-first-cash.e2e.ts compose-cash-month-summary.e2e.ts` 共 12 项通过。覆盖 390／768／1440 视口、真实断网重建、首次流水启动失败和保留结果后本地重试、同批单文件重试、保存前失败、冲突后重新核对、保存后归档失败及原收据恢复。追踪见 `/tmp/ft-unify-worker-traces`，Safari 和 Firefox 未实测。

## 7. 发布

- [x] 已核对 Compose README、应用技能、规格、词表及功能地图。用户要求复用现有计算调用，属于本项目当前实现边界，无需补充通用规则。没有产品流程、功能地图、接口示例或部署配置变化，可用上一版 Web 静态产物回滚。
