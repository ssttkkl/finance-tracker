## Context

见 [提案](proposal.md) 与 [delta 规格](specs/wealth/wealth-attribution/spec.md)。当前没有财富归因客户端入口；应用服务通过 `ServiceBundle` 暴露，财富来源记录则由账户、现金校准和导入事务写入。迁移 20260719、20260917 和 20260920 的历史执行代码依赖旧财富表模型与修订触发器。

## Goals / Non-Goals

**Goals:**

- 删除应用运行时中的财富归因计算、来源捕获、读模型与写入钩子。
- 通过一个前向迁移清除财富专属数据库表和已存派生数据。
- 让旧迁移从空库仍可回放到当前版本，并从应用 metadata 与运行时依赖中隔离。
- 保留被现金投影使用的规范序列化语义。

**Non-Goals:**

- 设计或实现新的财富报告。
- 修改现金账本、账户、投资组合估值、投资周期盈亏、现金投影或支付宝余额宝流水解析。
- 删除历史 OpenSpec 归档或重写历史迁移语义。

## Decisions

### 移除整个运行时能力，并保留独立财务能力

从 `ServiceBundle`、关系型组合根、应用服务、领域模型、仓储协议和专属适配器移除财富归因。删除账户开户/停用、余额校准和现金导入中的专属来源写入，但保留这些业务事务自身的账户、余额、流水、账户映射和现金投影写入。保留 `ValuationService`、投资周期盈亏实现和解析器中表示余额宝等金融产品的语义。

这样可以删除未被页面使用的后端合同，同时避免把“投资估值”或账单数据语义误判为“财富归因”。只移除查询/计算而保留捕获钩子会继续积累无人使用的数据，因此不采用局部停用。

### 数据库用单个前向迁移清理

新增 `20261018_37` 迁移，在删除修订触发器后按外键依赖顺序删除以下表：`wealth_generation_days`、`wealth_active_manifests`、`wealth_components`、`wealth_evidence_manifest_items`、`wealth_coverage_dispositions`、`wealth_evidence_manifests`、`wealth_evidence_items`、`wealth_generations`、`wealth_daily_results`、`wealth_source_manifest_items`、`wealth_source_manifests`、`wealth_source_revisions`、`account_lifecycle_events`、`valuation_observations`。

这些表仅承载归因来源快照、缓存结果与证据；用户已授权删除其中数据。迁移不操作 `accounts`、现金流水、投资事件、报价服务数据或现金投影。由于已删除派生数据不可由 downgrade 恢复，回滚数据库迁移不支持；部署前的数据库备份是唯一数据恢复点。

### 历史迁移支持与应用模型分离

将旧财富表的冻结 schema 定义和修订触发器函数移至 `migrations/` 下的迁移专用支持模块。历史 revision 35 和 36 改为导入迁移支持模块；revision 2 使用冻结定义创建历史表。应用 ORM metadata、UOW、`create_schema()` 和运行时不再包含这些表或触发器。历史迁移文件保留在原位，既有数据库可连续升级到 revision 37。

相比保留旧 ORM 类，本方案不会让 `Base.metadata.create_all()` 或工作区创建重新创建财富表/行；相比删除历史迁移代码，仍能升级空数据库及从 revision 34 之前开始的数据库。

### 迁出通用规范序列化

把现金投影使用的 `canonical_bytes` 从财富领域模块移到通用领域序列化模块，并更新现金投影引用。对现金投影当前合法输入保持相同 UTF-8 JSON、键顺序、十进制文本和 SHA-256 结果；不保留 `WealthError` 或财富相关 DTO。

## Risks / Trade-offs

- [前向迁移会删除已存财富派生数据] → 用户已明确选择删除；迁移说明标为不可逆，其他账本表不触碰。
- [历史迁移依赖应用代码会在删除后失效] → 在修改旧 revision 的导入边界时保留等价冻结 schema 与触发器支持，并保留迁移历史和旧 revision 测试。
- [抽取规范序列化可能改变现金投影内容摘要] → 迁移有效输入的实现语义，并在任务清单保留字节与摘要兼容检查项。
- [清理服务装配时遗漏隐式来源写入] → 搜索 `wealth_facts`、财富模型、来源修订触发器和 `ServiceBundle.wealth` 全仓引用并逐项处置。

## Migration Plan

1. 新迁移通过 `20261018_37` 删除财富专属表和修订触发器，runtime schema revision 更新到该版本。
2. 清理运行时模型与代码，并迁移旧迁移所需的冻结 schema/helper 到迁移专用目录。
3. 迁移后的新数据库不创建财富表；已在 revision 36 的数据库升级后删除相关表。主账本、投资事件与账户数据不变。
4. 回滚应用版本前必须先恢复迁移前数据库备份；仅 downgrade 无法恢复已删除的派生数据，故该迁移显式不可逆。

## Open Questions

无。
