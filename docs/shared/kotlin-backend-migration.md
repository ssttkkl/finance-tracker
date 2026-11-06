# Kotlin 后端迁移证据

[返回产品总览](../feature-map.md) · [后端运行与测试](../../compose/backend/README.md) · [当前行为规格](../../openspec/specs/README.md)

Kotlin/Ktor 是仓库唯一后端实现，已接入 86 个业务操作。Python 后端、Alembic 历史迁移、原后端测试和契约导出器已退役；Python 仅用于 UI token、Web 构建和其他独立工具。schema 由 Kotlin 在空库初始化到 `20261030_53`，已有库只验证当前完整表集合和版本。运行、配置与部署入口统一见 [后端说明](../../compose/backend/README.md)，不使用迁移期的预览命令。

原 Python 测试定义的盘点结果为 2,352 migrated、374 retained、0 pending；这些数量记录迁移对应关系。逐项源定义、保留理由和当时验证命令位于已归档的正式 artifact：

| 需要核对 | 事实源 |
|---|---|
| 能力与源测试范围 | [迁移盘点](../../openspec/changes/archive/2026-11-04-kotlin-backend-shared-core/inventory.md) |
| 各切片验证、原断言适配和独立复核 | [迁移任务](../../openspec/changes/archive/2026-11-04-kotlin-backend-shared-core/tasks.md) |
| 当前启动、schema、删除边界和验证 | [后端退役任务](../../openspec/changes/archive/2026-11-05-retire-python-backend/tasks.md) |

默认 `:backend:test` 与 CI 执行全部自包含测试；读取外部历史夹具的类或方法使用 `HistoricalMigrationEvidence` category，通过 `:backend:migrationEvidenceTest` 显式运行。混合类中的自包含方法继续默认执行。两类测试都编译，历史夹具缺失不会被视为执行通过；仓库不重新生成已退役导出器的输出。PostgreSQL schema 验证仍在默认任务中，通过显式 `_test` 数据库配置运行。

迁移保留的实际限制仍适用：Kraken 分页截断、OKX 资金类型和历史窗口、Binance 默认现货同步的限制见 [后端行为规格](../../openspec/specs/shared/kotlin-backend-parity/spec.md)。PDFBox 使用独立 JVM 和资源预算隔离，文件与网络的操作系统沙箱尚未实现。当前 Web／Native 页面入口、覆盖和待办由 [功能地图](../feature-map.md) 维护；后端迁移完成不代表所有原生设备 QA 或 Render 发布已经完成。
