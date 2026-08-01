# Implementation Plan: 工行退款摘要关系配对

**Branch**: `023-icbc-refund-pairing` | **Date**: 2026-08-01 | **Spec**: [spec.md](./spec.md)

## Summary

修复工行信用卡与借记卡账单的来源摘要解析、正式导入渠道和退款关系门禁。解析器将 `退货` 从对手方位置中分离出来，消费与退货共用同一套对手方规范化；导入服务保存 `bill_source`、`summary` 和精确退款信号，并以工行解析结果的 `bill_source` 作为正式 `source_type`。其他来源的预路由行继续使用其既有命令来源路径。关系层改为对工行读取结构化来源行快照，其他来源继续使用其既有规则。

## Technical Context

**Language/Version**: Python 3.11+

**Primary Dependencies**: `Decimal`、SQLAlchemy、Alembic、pytest、现有 PDF 文本解析器

**Storage**: SQLite 与 PostgreSQL；不新增表、不新增迁移

**Testing**: pytest；SQLite 集成测试和真实 PostgreSQL 契约测试

**Target Platform**: 本机 CLI、关系扫描服务、收支投影服务

**Project Type**: Python 单体应用（CLI + Application Service + Domain + Relational Adapter）

**Performance Goals**: 不增加关系扫描的候选数量级；结构化信号在现有候选索引中完成门禁

**Constraints**: 金额继续使用精确十进制；导入不写关系；不增加历史兼容回退；不泄露原始账单到日志或仓库

**Scale/Scope**: 影响 `src/ft/convert.py`、账单导入服务、退款关系领域层及对应测试

## Constitution Check（研究前）

- [x] A 类完整 Feature：涉及关系匹配、持久化来源字段和 SQLite/PostgreSQL 行为，走完整 Spec Kit 流程。
- [x] 财务正确性：不改写金额，不在导入阶段写关系，保存来源字段并使用 `Decimal`。
- [x] 测试先行：先增加真实解析链路、结构化信号和双后端契约的失败测试。
- [x] 数据库显式选择：不新增自动回退、双写或隐式迁移。
- [x] 无兼容方案：旧的 `source_type=icbc` 记录不由代码猜测修复；用户重导。

## Research Summary

1. 当前导入服务用命令参数 `icbc` 覆盖解析器已经产生的 `bill_source=icbc_credit`，导致关系层无法进入工行结构化门禁。
2. 当前信用卡解析器把退款行扫描到的第一个非空字段 `退货` 当作对手方，再把完整商户写入描述；消费行则先去除 `美团支付-` 和 `美团App` 前缀，形成两个不同的规范化路径。
3. 当前退款候选索引和 `evaluate_refund_offset` 只读取拼接文本；仅在单元测试手工填入 `bill_source=icbc_credit` 时才可能识别结构化信号，真实导入后的 `source_type=icbc` 会失败。
4. 来源行快照已经随现金流水持久化，无需 schema 变更；需要扩展解析到导入的字段白名单，并在领域 gate 中严格校验来源和值。

## Design

### Data flow

```text
ICBC PDF
  -> convert parser: summary / raw counterparty / normalized counterparty / refund_signal
  -> _build_output_row: bill_source + source_payload
  -> StatementImportService: source_type = row.bill_source
  -> repository: cash fact + source_payload
  -> RelationService: FactView(raw_payload, bill_source)
  -> structured ICBC refund gate
  -> refund_offset proposal / persisted relation
```

### Formal field contract

| 字段 | 工行信用卡 | 工行借记卡 | 语义 |
|---|---|---|---|
| `bill_source` | `icbc_credit` | `icbc_debit` | 正式导入渠道 |
| `summary` | PDF 摘要原值 | PDF 摘要原值 | 独立于对手方的来源字段 |
| `refund_signal` | `icbc_credit_return`（仅 `退货`） | `icbc_debit_return`（仅 `退货`） | 关系层唯一认可的工行退款信号 |
| `counterparty` | 同一规范化函数 | 同一规范化函数 | 查询与配对字段 |
| 原始对手方 | 来源行快照字段 | 来源行快照字段 | 审计核对，不直接作为正式匹配字段 |

### Relation gate

`has_refund_signal_for_fact(fact)` 先验证现金收入，再按渠道分支：工行信用卡和借记卡要求 `raw_payload` 为字典、`raw_payload.bill_source == fact.bill_source` 且 `raw_payload.refund_signal` 等于对应精确值；其他渠道调用原有文本规则。候选索引、种子识别、普通退款匹配和银行退款链路统一调用该 fact-level gate。

### Counterparty parsing

信用卡金额后的扫描遇到 `消费` 或 `退货` 时先写入 `summary` 并跳过；之后遇到完整商户文本才写入 `counterparty`。两种行都调用 `_normalize_counterparty`，因此目标三条记录均得到 `山葵村烤肉`；完整 `美团支付-美团App山葵村烤肉` 保留在来源行快照。

### PostgreSQL / SQLite parity matrix

| 维度 | SQLite | PostgreSQL | 必须等价的结果 | 允许差异 |
|---|---|---|---|---|
| Schema | 现有 `cash_transactions.source_type/source_payload` | 同一 schema | 无新增列；字段可读写 | SQL 方言 |
| 事务 | 现有 UoW 单事务写入现金流水和来源快照 | 同一 Application Service 单事务 | 导入失败不落半行；成功后可扫描 | 锁粒度与并发实现 |
| 幂等 | `source_type × record_id` 唯一语义 | 同一唯一语义 | 重复导入新增数均为 0 | 约束错误文本格式 |
| 查询 | repository 返回相同 `source_type/source_payload` | repository 返回相同字段 | FactView 得到相同正式信号 | 执行计划 |
| 关系 | 结构化信号产生同一 accepted refund_offset | 同一 | 关系类型、方向、金额和置信度相同 | ID 生成形式如实现既有约定 |
| 错误 | 缺失字段按普通收入，非异常回退 | 同一 | 不凭文本误识别工行退款 | 驱动器异常包装 |
| 运行差异 | 内存或临时文件测试可用 | 需要 `FT_TEST_POSTGRES_URL` | 测试明确标记跳过原因 | 环境启动方式 |

## Constitution Check（设计后）

- [x] 未新增表、迁移、依赖或兼容回退。
- [x] 解析、导入、持久化、关系扫描和投影字段链路均有测试覆盖计划。
- [x] 工行只认可精确结构化字段，其他来源规则不被改变。
- [x] SQLite 与真实 PostgreSQL 契约矩阵已定义。

## Complexity Tracking

无 Constitution 例外。

## Source files

- `src/ft/convert.py`
- `src/ft/adapters/statement_import.py`
- `src/ft/application/statement_import.py`
- `src/ft/domain/relations/core/types.py`
- `src/ft/domain/relations/pipeline.py`
- `src/ft/domain/relations/refund/diamond.py`
- `src/ft/domain/relations/refund/match.py`
- `src/ft/domain/relations/refund/signals.py`
- `tests/test_convert.py`
- `tests/test_relations_index_injection.py`
- `tests/test_transaction_relations_refund.py`
- `tests/test_import_scan_refund_boundary.py`
- `tests/contract/`
