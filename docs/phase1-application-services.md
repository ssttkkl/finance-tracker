# Phase 1 Application Services（历史记录）

> Superseded。本文只记录 Phase 1 曾建立 application/domain/adapter 边界；当前命令与存储合同以
> [README](../README.md) 和 [001-postgres-only-storage](../specs/001-postgres-only-storage/spec.md) 为准。

Phase 1 把账户、现金、转账、查询和投资行为从 CLI 函数中抽到 application services，并建立可注入的
repository/UoW ports。这些边界仍被当前 PostgreSQL composition root 复用。

Phase 1 同时存在的文件 repository、Git change set、converted-file import、文件 reconcile 和 Connector
编排已被后续 PostgreSQL-only feature 删除，不是可恢复或兼容的产品能力。

当前保留资产：

- `AccountService`、`CashflowService`、`TransferService`；
- `InvestmentService`、`PortfolioQueryService`；
- `FinanceQueryService`；
- workspace-bound PostgreSQL UoW/repositories；
- storage-independent statement parsers 和投资投影规则。

历史实现细节通过 Git history 追溯，不在本文件维护旧命令矩阵。
