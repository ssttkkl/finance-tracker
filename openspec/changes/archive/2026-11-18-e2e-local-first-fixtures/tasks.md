## 1. 思考

- [x] 1.1 盘点 14 个 E2E 文件的投影路由、行字段与选择器依赖
- [x] 1.2 对照 `/ledger/snapshot` DTO 与本地派生，列出字段映射和不可一一对应的断言
- [x] 1.3 记录分级依据：仅测试基础设施，B 类

影响范围：`compose/qa/web/tests` 与 1 处登录错误映射；产品行为仅修正认证失败的错误码透传，不改变账本派生、接口或数据模型。

## 2. 计划

- [x] 2.1 设计 `snapshotFixture` 输入结构与默认快照
- [x] 2.2 列出每个文件的迁移清单与需要更新的断言

旧文件中断言远端 query 参数、证据重试计数、错误／空态 mock 的部分在新架构下没有对应行为，按等价本地优先用例替换并删除，见 `design.md` 第 5 条。

## 3. 任务拆分

- [x] 3.1 先写一条失败用例：旧断言在新快照下确实失败
- [x] 3.2 实现夹具构造器并让默认单条支出用例通过
- [x] 3.3 逐文件迁移并运行

## 4. 构建

- [x] 4.1 实现 `buildLedgerSnapshot` 与 `installComposeApiFixtures` 的 `snapshot` 选项
- [x] 4.2 迁移现金列表与待处理筛选：新增 `compose-local-first-cash.e2e.ts`，删除仅验证远端投影的旧文件
- [x] 4.3 迁移月汇总：`compose-cash-month-summary.e2e.ts` 改为三币种快照
- [x] 4.4 迁移分类管理：空分类快照、去掉远端加载／错误断言
- [x] 4.5 迁移离线与导入：允许会话探测请求、冷启动等待版本接口、网络恢复后重载
- [x] 4.6 迁移登录错误：`AccessViewModel` 在记录诊断前把 `ApiFailure` 归一为 `DomainFailure`
- [x] 4.7 删除 13 个已退役的远端投影／证据测试文件（`compose-cash-*`、`compose-pages`、`compose-local-ledger`）

关键实现约束：快照事务／条目 ID 必须是数字串；派生对象 ID 为 `cash-entry:<entry_id>`；Compose Web 点击需走坐标 helper。

## 5. 审查

- [x] 5a 范围复核：无测试专用产品分支；产品代码仅 2 处登录错误映射
- [x] 5b 代码库防劣化：纯测试基础设施与错误码归一，不涉及契约或 UI 复用新增
- [x] 5.2 夹具可复用性：`buildLedgerSnapshot` 支持多条目、多币种、issue；默认快照保持既有非数据用例可用

## 6. 测试与 QA

- [x] 6.1 `npm --prefix compose/qa/web run test:node`：通过
- [x] 6.2 全量 `npx playwright test`：**50 passed**（本地 Kotlin 后端 + 预览代理，1440/768/390 视口，2026-11-18）
- [x] 6.3 `:shared:wasmJsTest`：通过（1,032 tests，1 skipped）
- [x] 6.4 `openspec validate --all --strict`：通过

验证环境：`FT_DATABASE_URL=sqlite+pysqlite:////tmp/web-e2e_test.db`、`FT_KOTLIN_PORT=19261`、`FT_COMPOSE_PREVIEW_PORT=5199`、`FT_API_PROXY_ORIGIN=http://127.0.0.1:19261`。HEAD `7eab81ca`。

## 7. 发布

- [x] 7.1 功能地图／文档：本 change 不改变用户可见流程与文案；登录错误码修正使既有文案按预期显示，无需更新功能地图
- [x] 7.2 归档与 PR：待用户授权推送分支后创建 PR；本 change 不修改主规格（`skip_specs: true`）

## 遗留风险

- 删除的 13 个文件覆盖的关系、草稿、导入细节场景不再有等价浏览器断言；这些行为由共享层单测与保留的导入／离线 E2E 覆盖，后续可补关系与草稿的本地优先用例。
- 本轮未执行 Android／iOS 设备 QA，按默认前端验证范围不宣称通过。
