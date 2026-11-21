## 1. 思考

- [x] 定位本地导入、全量同步、冻结、锁内验证和逐项回执合同。A 类，因跨端协议、恢复与锁内复核耦合。基线和用户确认范围见 design.md。

## 2. 计划

- [x] 定义双读发布、协商压缩、来源覆盖表、完全重复项证明和两层字节验收。Web、Android、iOS 共用命令与恢复合同，各自适配压缩；无 UI 变更。

## 3. 任务拆分

- [x] 按传输、首次导入、重导、恢复组织场景。完整命令作为紧凑表示的等价性对照。

## 4. 构建

- [x] 协商与正文边界：写集路由增加已鉴权 `OPTIONS` 能力声明；服务端安装 JSON gzip 响应压缩；写集请求按 `Content-Encoding` 解压后限长。未知编码 415、损坏流 400、展开超限 413，均发生在业务写入前。`CashImportProtocol.jsonInput` 只对解码后的 UTF-8 正文执行 64 MiB 上限；`expandCashWriteSetTransport` 对逻辑展开再加 64 MiB 预算。
- [x] 首次导入：`business-domain` 新增 `CashWriteSetTransport.kt`。`command_version: 2` 的 `coverage_table` 按首次出现顺序保存唯一 coverage 值，绑定用从零开始的 `coverage_ref` 引用；只合并对象键顺序无关的完全相等值，数组顺序与数字词形保留。服务端展开后仍走原 DTO 校验、来源绑定校验和写入服务；未修改原有 v1 解码与摘要。
- [x] 重导：`replay_groups` 以原逻辑数组位置、已有 ID、期望修订和 SHA-256 证明表示完全重复的流水/账目。客户端只在完整逻辑片段与 facts 重建片段逐字段摘要相等、且没有被来源绑定/关系/问题/拆分/人工编辑引用时生成；服务端在原工作区锁内读取 facts，校验 ID/修订/归属/摘要，按原位置重建后交给既有写入与回执统计。证明不符返回 `write_set_replay_mismatch` 且 `not_committed`。
- [x] 恢复：`StoredCashImportSubmission` 增加可选 `transport`；首次发送前探测能力并用 `recordTransport` 原子持久化紧凑 JSON，之后重放同一冻结字节；命中回执后丢弃 transport。能力探测失败、旧服务端、持久化失败均回退完整 v1 命令；旧版存储缺省该字段仍可读。Web worker 用 `CompressionStream` 压缩请求并在不可用时回退 identity；Android 用 `GZIPOutputStream`，iOS 用 `NSData` zlib 流重新封装 gzip 帧。
- [x] 测试先行：新增 `CashImportTransportHttpTest`、`CashWriteSetTransportTest`、`CashImportRequestCompressionTest`，并扩展月账单 E2E、`CashWriteSetApiTest`、`LocalCashImportSubmissionTest`、`SqlLedgerImportFilesTest`。覆盖能力探测、gzip 重放、损坏/未知编码/超限、覆盖表精确数字、重导证明、伪造/过期证明、transport 持久化与回执清理。

## 5. 审查

- [x] 5a 独立代码复核（只读）：确认回执优先、锁内重建、摘要词形、位置合并和原子写入主干成立；发现并处置：
  - CORS 未允许 `OPTIONS`/`Content-Encoding`，跨源 Web 探测与 gzip 会被预检拒绝。已在 `FinanceModule` 补齐并加入带 `Origin` 的预检测试。
  - `coverage_table` 展开按引用计费不足，可能在校验前放大内存。已改为逐引用预算，超 64 MiB 立即 413，并补业务层与 HTTP 放大用例。
  - 首次基线拒绝使用探测前的旧提交记录，transport 已落库时会误报本地存储失败。已改用实际发送的 `prepared`，并让幂等比较忽略派生 transport。
  - iOS `NSData` zlib 实际输出裸 DEFLATE，原实现会静默回退或破坏数据。已改为自组 gzip 帧，并在 iOS Simulator 用 Foundation 解压 + CRC/尺寸校验。
  - 混合版本服务端拒绝 gzip/紧凑命令时无降级。已实现先核实原键、再改发 identity/完整 v1 的一次性回退；证明不符则退役冻结尝试并回到核对。
  - 首次紧凑命令未走 HTTP、回执逐项未比较。月账单用例已直接提交首次 compact、gzip 重导，并比较 `write_set_result`/`commit_outcome`。
- [x] 5b `codebase-degradation-review` 调度 `contract-design-review`（无 UI，`ui-reuse-review` 不适用）：确认紧凑表示未进入业务 DTO/收据/表，归属正确；发现并处置：
  - transport 与完整 request 无绑定。新增 `request_digest`，冻结/读回/重放前展开比对；OpenAPI 与契约描述同步。
  - `write_set_replay_mismatch` 无客户端恢复路径。已退役冻结尝试、保留文件与核对，用户重新确认生成新尝试。
  - OpenAPI 未表达 `coverage`/`coverage_ref` 恰一、v2 条件、`Content-Encoding` 与 400/413/415。已补 `oneOf`、请求头、响应和字段说明。
  - 未采纳：`replay_groups`/`transport` 更名会扩大公开契约面；`response_encodings` 保留为探测声明；`ReplayItem`/`ReplayEntry` 结构相同但语义位置不同，暂不合并。
- [x] 残余风险：未做真实代理链路、PostgreSQL 混合版本滚动发布和原生设备压缩验证；旧版客户端读取含 `transport` 的新记录会严格失败，作为“禁止降级读取未完成 transport”的保守行为记录。

## 6. 测试与 QA

- [x] SQLite 与真实 PostgreSQL 双后端全量 `:backend:test` 通过：本地 Docker `postgres:18` 专用 `finance_test` 库，`FT_TEST_POSTGRES_URL=postgresql://postgres:test@127.0.0.1:55432/finance_test`，实际执行 294 个 `[postgres]` 参数化用例；容器已移除。`CashImportTransportHttpTest` 与月账单用例含首次 compact、gzip 请求、gzip 回执/快照、重导 replay、伪造摘要 422、放大 413、损坏/未知编码/超限、CORS 预检。
- [x] 月账单（SQLite，11 文件、759 行）实测：首次完整 1,998,455 B → 紧凑 1,572,037 B → gzip 177,881 B；重导 790,681 B → 118,108 B → 35,278 B；覆盖表 1,299 绑定 → 3 值；重导 364 个可证明重复项。首次 gzip 相对完整减少 91.1%，重导减少 95.5%；解压正文分别减少 21.3%/85.1%。未测 CPU、峰值内存与锁内时长。
- [x] 完整 `:backend:test`（SQLite）、`:business-domain:jvmTest`、`:shared:testAndroidHostTest`（仅既有 `FT_KOTLIN_CLIENT_CONTRACT_DIR` 集成用例因环境变量缺失失败）、`:shared:iosSimulatorArm64Test` 的 gzip 用例、Android/iOS/Wasm/Web 编译均通过；`qa/web` worker 客户端 Node 契约测试通过。
- [x] `openspec validate reduce-cash-import-payloads --strict` 通过。
- 证据基线：`HEAD 4d991d79`（design），实现位于工作树；2026-11-21 03:39 CST。命令：`:backend:test`（SQLite 无 `FT_TEST_POSTGRES_URL`；PostgreSQL 使用本地 Docker `postgres:18` + `finance_test`）、`:business-domain:jvmTest`、`:shared:testAndroidHostTest`（受影响类）、`:shared:iosSimulatorArm64Test --tests '*CashImportCompressionIOSTest*'`、`:shared:compileKotlinWasmJs :shared:compileKotlinJs :webApp:compileKotlinWasmJs`、`node --test compose/qa/web/tests/cash-import-worker-client.node-test.mjs`。
- [x] 真实浏览器传输冒烟：Chromium 1280×900，Web `http://127.0.0.1:5186`，API `http://127.0.0.1:18240`，跨源；注册并创建工作区后，带 Bearer 的 `OPTIONS` 能力探测 200，浏览器 `CompressionStream('gzip')` 生成的写集请求 200 并返回真实收据；控制台无 CORS/编码错误（仅既有 Kotlin/Wasm memory 弃用提示）。未走完整 UI 确认/恢复主流程（本变更无 UI 改动），发布前仍建议在真实跨源部署复跑。

## 7. 发布

- [x] 已更新 `openapi-contract.json`（v2 字段、`request_digest`、`coverage`/`coverage_ref` 恰一、`Content-Encoding`、400/413/415）、[导入流程](../../../docs/import/cash-import-flow.md)、[导入功能地图](../../../docs/import/feature-map.md)、design/tasks。
- [x] 已归档为 `2026-11-21-reduce-cash-import-payloads`，主规格 `import/local-import-pipeline` 已同步；发布仍需用户明确授权。
- [x] 用户决策反思：本次只采纳“先不做投资账单／增量同步／解析结果复用”的范围取舍，属于既有 change 的边界，无需新增通用规范；compact 是传输细节、完整逻辑命令仍是事实源，已在 design 记录，不升级为跨模块规则。
