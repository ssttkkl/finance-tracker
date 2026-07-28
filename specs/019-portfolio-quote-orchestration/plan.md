# Implementation Plan: 持仓行情报价编排

**Branch**: `019-portfolio-quote-orchestration` | **Date**: 2026-07-28 | **Spec**: [spec.md](./spec.md)

**Input**: 在不改变现有估值合同的前提下，使默认 4 秒持仓报价预算能覆盖更多独立标的。

## Summary

将 `PortfolioQueryService` 从“逐仓位发起并等待报价”改为两阶段查询：先收集并去重报价请求，再按数据源使用批量读取或受控并发执行。所有外部工作共享 4 秒总预算；证券、加密资产与预测市场的失败相互隔离。实时行情仍不入库，既有 Decimal、`QuoteStatus`、计价币种与展示币种折算合同不变。

## Technical Context

**Language/Version**: Python 3.11+

**Primary Dependencies**: SQLAlchemy 2、yfinance、urllib、现有 `ValuationService`/Quote Provider port

**Storage**: 无新增读写；运行时仍显式使用 PostgreSQL 或文件型 SQLite

**Testing**: pytest；可控批量源、阻塞源和调用计数假源；SQLite 与真实 PostgreSQL 集成测试

**Target Platform**: Python CLI/library

**Project Type**: 双后端个人财务 CLI/application

**Performance Goals**: 在可控的 30 个非零仓位/3 类数据源组合中，报价阶段不超过 4 秒；重复标的每次查询最多一次外部取价

**Constraints**: 精确十进制；全局 4 秒预算；有界外部并发；无账本写入；不得更改现有 ticker 映射、FX 或状态语义

**Scale/Scope**: `PortfolioQueryService`、`ValuationService`、市场数据适配器、运行时装配与相关单元/双后端集成测试

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 方案前 | 设计后 |
|------|--------|--------|
| I. 财务正确性与可审计性 | PASS：仅复用同一行情结果，保留 Decimal 与状态 | PASS：不引入成本价回退、猜测价格或账本写入 |
| II. Spec Kit 规格驱动 | PASS：019 spec、research、data model、contracts、quickstart | PASS：任务必须先测试后实现并回写状态 |
| III. 测试先行与验证证据 | PASS：任务安排失败测试、受影响测试、全量测试 | PASS：批量、去重、超时隔离、双后端均有可控证据 |
| IV. 显式数据库选择与行为等价 | PASS：不访问数据库，不新增方言逻辑 | PASS：同一 Application Service 与假源在两后端验证 |
| V. 清晰边界与最小复杂度 | PASS：扩展现有报价 port，不建缓存或新服务 | PASS：执行器只位于 Application 编排边界，供应商细节留在 adapter |

**持久化矩阵**：无 schema、事务、查询或错误合同变更。SQLite 与 PostgreSQL 仅在加载同一快照的既有路径不同；报价去重、批量、并发、超时和状态全部位于共享 Application Service，用户可见结果必须一致。无自动回退、双写或隐式跨后端迁移。

**Status**: PASS

## Project Structure

### 文档

```text
specs/019-portfolio-quote-orchestration/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── contracts/quote-batch-port.md
├── quickstart.md
└── tasks.md
```

### 代码范围

```text
src/ft/
├── domain/valuation.py                  # 报价批量 port 与结果合同
├── application/valuation.py             # 单项/批量结果组装与状态语义
├── application/investment.py            # 持仓请求收集、去重、全局预算编排
├── adapters/market_data.py              # 证券/加密批量适配、预测市场受限并发
└── adapters/relational/runtime.py       # 现有依赖装配（仅在需要注入编排配置时变更）

tests/
├── unit/application/test_valuation_service.py
├── unit/application/test_portfolio_valuation.py
├── test_market_data.py
├── integration/test_portfolio_query_sqlite.py
└── integration/test_portfolio_query_postgres.py
```

**结构决策**：持仓层只负责工作收集、去重和结果映射；估值服务保留状态计算；各数据源的批量协议和网络超时只在市场数据适配器中实现。不得让 CLI 或 repository 承担报价并发。

## 实施阶段

1. 完成可控测试，证明当前串行行为无法满足去重、数据源隔离与预算要求。
2. 在报价 port 中定义逐项批量结果合同，并以单项实现作为兼容默认。
3. 为 yfinance 和 CoinGecko 增加源内批量读取；预测市场使用固定上限的并发工作，不新增未知 API。适配器测试追加到既有 `tests/test_market_data.py`，避免与同名测试模块产生 pytest 导入冲突。
4. 将持仓查询改成“收集 → 去重 → 按源调度 → 映射回仓位 → FX 折算”。
5. 在 SQLite 与真实 PostgreSQL 跑同一持仓报价契约矩阵；无网络的假源为确定性证据。
