# Implementation Plan: 导入时生成现金流水标准记录类型

**Branch**: `024-normalized-cash-record-type` | **Date**: 2026-08-01 | **Spec**: [spec.md](spec.md)

## Summary

在现金账单导入的纯转换阶段统一生成 `record_type`，把它作为 `cash_transactions` 的正式非空字段保存；分类函数只依赖来源原生字段和解析出的正式信号，不修改关系扫描。完成后创建新 SQLite，导入 `.ft/bills` 的全部现金账单，校验分布后备份并替换当前库。

## Technical Context

**Language/Version**: Python 3.12+

**Primary Dependencies**: Python、SQLAlchemy、Alembic、pytest、openpyxl、xlrd、pdfplumber

**Storage**: SQLite 与 PostgreSQL，共享 SQLAlchemy 模型和导入服务

**Testing**: pytest；SQLite 集成测试；真实 PostgreSQL 契约测试（若 `FT_TEST_POSTGRES_URL` 可用）

**Target Platform**: 本地 CLI/Web 运行时

**Project Type**: Python CLI + 本地 Web 服务

**Performance Goals**: 分类为单行 O(1) 纯函数；全量账单导入不增加关系扫描工作

**Constraints**: Decimal 金额保持现有精度；不写入导入关系；未知类型显式为 `other`；不兼容旧数据库记录

**Scale/Scope**: 约 11,394 条当前现金账单记录，5 个现金来源

## Constitution Check

- 规格先行：通过本 Feature 的 spec、research、plan、data-model、tasks 和一致性检查后实施。
- 财务语义：`category` 与 `record_type` 分离，`repayment` 不并入转账，P2P 退回使用 `transfer_reversal` 而非消费退款或一般撤销；金额不做重算或净额替换。
- 持久化：字段在 SQLite/PostgreSQL 共享模型中定义，导入使用同一服务和事务。
- 不兼容策略：不从旧记录推断或回填类型；新数据库由完整账单重建。
- 关系边界：本 Feature 不修改 `payment_mirror`、`refund_offset`、`transfer_pair` 或 `credit_repayment` 扫描逻辑。

## Data Flow

```text
真实账单
  → 来源解析器（保留原生字段）
  → classify_record_type(source, row)
  → _build_output_row(record_type=...)
  → StatementImportService(source_payload 含原生字段)
  → cash_transactions.record_type
```

## Components

- `src/ft/domain/record_type.py`：定义稳定类型集合和纯分类函数。
- `src/ft/convert.py`：把解析行传入分类函数，并将 `record_type` 放入输出行；不改变现有金额、账户和关系跟踪。
- `src/ft/adapters/statement_import.py`：继续把完整输出行写入 `source_payload`，不另写关系。
- `src/ft/adapters/relational/models.py`：为 `CashTransactionModel` 增加非空 `record_type`。
- `src/ft/adapters/relational/repositories.py`：持久化和读取 `record_type`。
- `alembic/versions/*`：增加 schema 迁移；重建库时由最新 schema 创建。

## SQLite/PostgreSQL Parity Matrix

| 维度 | SQLite | PostgreSQL | 必须等价的行为 | 允许差异 |
|---|---|---|---|---|
| Schema | `VARCHAR` 非空字段 | `VARCHAR` 非空字段 | 字段名、枚举值、非空约束一致 | 类型方言实现 |
| 事务 | 导入事务内写入现金行和快照 | 同一事务 | 任一分类/账户错误整体回滚 | SQLite 锁粒度 |
| 查询 | `record_type` 可直接筛选 | `record_type` 可直接筛选 | 返回值和空值语义一致 | 查询计划 |
| 精度 | 现有 `ExactDecimal` | `NUMERIC(38,18)` | 分类不改变金额；金额读取一致 | 存储类型 |
| 错误 | 未知类型落 `other`；空类型禁止入库 | 同上 | 不静默丢行，不允许空类型 | 错误包装文本 |
| 并发 | 复用现有 SQLite UoW | 复用现有 PostgreSQL UoW | 导入幂等和原子性一致 | 并发能力 |
| 迁移 | 最新 Alembic revision | 最新 Alembic revision | 新库 schema 完整可用 | 迁移执行方式 |

## Rollback / Replacement

先复制 `/Users/huangwenlong/.ft/finance-tracker.db` 为带时间戳的备份，再在同目录临时路径建立新库并全量导入。只有 schema、行数、类型分布和 `other=0` 校验均通过后，才将新库移动为当前路径；失败时保留旧库和临时新库，不覆盖旧库。

本次本地账单集中缺少东方证券 PDF 密码；该文件不能完成正式重解析。为保证全量重建不丢失已有投资事实，执行阶段将当前库中该账单已经正式导入的投资事实作为一次性重建输入写入临时库。该处理不进入生产代码，不构成旧库读取兼容或历史现金分类回填。
