# 实施方案：工行卡退货退款信号

**分支**：`fix/icbc-credit-refund-signal` | **日期**：2026-08-01 | **规格**：[spec.md](spec.md)

**输入**：`specs/023-icbc-credit-refund-signal/spec.md`

## 摘要

工行信用卡解析器已经能根据原生“摘要=退货”识别退款，但正式账本记录的来源行快照没有保留该事实。工行借记卡需要采用相同规则。实现将把两类工行账单的原生账单摘要和来源专用的正式退款信号（`icbc_credit_return` 或 `icbc_debit_return`）一同写入重新导入记录的 `source_payload`，并让第 D 阶段退款冲销匹配读取一个来源受限的事实级退款信号。

本功能不兼容历史记录、不迁移也不回填。受影响账单由使用者重新导入；金额、显示文本、关系阈值和关系持久化流程都不改变。

## 技术上下文

| 项目 | 决定 |
|------|------|
| 语言/版本 | Python 3.11+ |
| 主要依赖 | 标准库、SQLAlchemy；不新增依赖 |
| 存储 | 既有 SQLite / PostgreSQL；复用 JSON `source_payload`，无 schema 或迁移变更 |
| 测试 | `pytest`，去标识化单元、应用服务集成和双后端契约测试 |
| 目标平台 | 本地 CLI、Web 及 Worker 共享的 Python 运行时 |
| 项目类型 | 单体 Python 应用，领域关系规则包 + 关系应用服务 |
| 性能目标 | 每个 `FactView` 常数时间读取来源行快照；保持候选索引的按日桶查找，避免全量扫描 |
| 约束 | 仅工行信用卡或借记卡、正数、对应来源的明确信号可作为退款种子；失败关闭；不记录真实账单或密码 |
| 范围 | 解析输出、导入快照、退款信号门控、候选索引和第 D 阶段匹配 |

## 宪法检查

### 研究前

| 原则 | 结果 | 落实方式 |
|------|------|----------|
| 财务正确性与可审计性 | 通过 | 金额仍使用 `Decimal`；只增加可追溯来源行快照字段，不改金额或净额化。 |
| Spec Kit 规格驱动 | 通过 | 以独立的 023 feature、方案、任务和测试证据实施。 |
| 测试先行 | 通过 | 先新增失败测试，覆盖新信号、非退款正数、幂等和两后端。 |
| SQLite/PostgreSQL 等价 | 通过 | 不改 schema；同一来源行快照和关系检查用同一应用服务契约测试。 |
| 清晰边界与最小复杂度 | 通过 | 来源判定集中于退款信号包；核心候选索引只依赖注入的事实级门控。 |

### 设计后复核

通过。没有引入新金额计算、数据库表、迁移、双写、自动后端回退、外部依赖或未获授权的写操作。

### SQLite / PostgreSQL 等价矩阵

| 维度 | SQLite | PostgreSQL | 契约 |
|------|--------|------------|------|
| Schema | 现有 JSON `source_payload` 列 | 同一模型的 JSON `source_payload` 列 | 无 DDL 变更。 |
| 写入事务 | 既有账单导入事务 | 同一 Unit of Work | 新键随现金事实原子保存。 |
| 查询 | 仓储还原为 `raw_payload` | 相同仓储映射 | 关系服务只读同一 `FactView.raw_payload`。 |
| 并发 | 既有 SQLite 事务限制 | 既有 PostgreSQL 行为 | 本功能不新增锁、重试或事务边界。 |
| 错误 | 缺失/畸形来源快照不识别退款 | 同样失败关闭 | 不自动迁移、不双写、不跨后端回退。 |
| 幂等 | 重复导入/关系检查沿用既有唯一性 | 相同 | 不重复创建事实或 `refund_offset`。 |

## 架构与数据流

```text
工行 PDF 原生摘要“退货”
  → convert._parse_icbc_lines：结构化退款信号
  → convert._build_output_row：summary / refund_signal
  → StatementParser：保留到正式导入行
  → StatementImportService：JSON source_payload
  → FactView.raw_payload
  → refund.signals：仅工行信用卡或借记卡的事实级退款信号判定
  → FactCandidateIndex + evaluate_refund_offset（第 D 阶段）
  → 既有 RelationService 持久化与幂等保护
```

导入解析器本身不创建关系。本功能只让既有的、按当前配置触发的关系检查能够消费退款信号；不改变是否在导入后运行关系检查的既有应用服务行为。

### 关键设计

1. **重新导入记录保存结构化证据。** `convert.py` 从工行信用卡和借记卡来源行提取原生 `summary`，并只在“摘要=退货”的正数行分别输出 `refund_signal="icbc_credit_return"` 或 `refund_signal="icbc_debit_return"`。`_build_output_row` 和 `StatementParser` 显式保留这两个字段，从而使 `StatementImportService` 的现有整行快照保存它们。保留现有 `_refund_signal` 仅作转换过程的内部跟踪，不把内部字段当作正式协议。
2. **候选索引和所有关系入口共用同一门控。** 将 `RefundTextGates.has_refund_signal` 从文本入参提升为 `FactView` 入参，仍由退款包实现，避免核心类型包依赖退款实现。`FactCandidateIndex`、`evaluate_refund_offset`、银行退款种子扫描和 Diamond 兜底都调用该事实级判定，杜绝“索引找不到、最终规则能识别”或相反的分叉。
3. **文本规则保持局部。** `has_refund_signal(text)`、P2P 家族排除、标题与商户匹配仍只看文本。工行信用卡和借记卡的结构化信号不能把普通收入、其他银行或 P2P 转账变为退款；它仅取代“退款种子必须在可见文本中带关键字”的条件。
4. **沿用第 D 阶段政策。** 时间窗口、同币种、同账户精确金额弱匹配、商户强匹配、人工待审核和幂等写入全部保持不变。该来源信号只决定一笔正数行是否有资格成为退款种子。

## 失败模式与处理

| 风险 | 保护措施 | 自动化证据 |
|------|----------|------------|
| `退货` 信号误扩展到其他来源 | 精确校验 `bill_source` 与对应的 `refund_signal` 配对。 | 非工行、普通正数、畸形快照均不产生候选。 |
| 新导入信号未进入 JSON 快照 | 测试完整的解析输出与导入后 `source_payload`。 | 断言 `summary` 与 `refund_signal`。 |
| 退款信号未抵达候选索引 | 索引和最终匹配共享 `FactView` 门控。 | 断言由退款和支出两种种子方向均能取候选。 |
| 缺失或畸形来源行快照被误判 | 只接受精确来源和正式 `refund_signal`。 | 缺失、非字符串和其他来源快照测试。 |
| 结构化信号破坏 P2P 排除 | P2P 判定仍使用文本。 | 工行退款不与 P2P 支出形成商户退款关系。 |
| 关系检查重复运行 | 不调整已有唯一约束和状态过滤。 | 同一输入两次运行只保留一条关系。 |

## 测试策略

先写会失败的测试，再实施最小代码，按以下层次验证：

1. 解析/映射：去标识化工行信用卡和借记卡“退货”行生成 `summary` 和对应的 `refund_signal`，且不改变对手方、备注、金额和类别。
2. 规则/索引：`FactView.raw_payload` 中的新字段可以成为退款种子；普通正数、其他来源和错误 payload 不可以。
3. 关系：对同卡、同币种、时间窗口内的原消费生成唯一 `refund_offset`；重复检查幂等；仍保留原有 P2P 排除。
4. 存储：以同一关系应用服务在 SQLite 和真实 PostgreSQL 运行去标识化输入，比较关系候选、状态和来源快照。

测试路径：

```text
解析器测试
  → 导入映射/来源快照集成测试
    → FactCandidateIndex 测试
      → 退款第 D 阶段关系测试
        → SQLite 与 PostgreSQL 契约矩阵
```

## 不在范围内

- 不把建设银行、支付宝或微信的退款分类改为此信号协议。
- 不兼容、不回填或修改历史记录；受影响账单由使用者重新导入。
- 不改变关系自动确认阈值、排序、人工审核 UI 或关系写入模型。
- 不改变导入后是否自动运行关系检查的既有产品配置。

## 已有能力

- 工行信用卡解析阶段已经识别 `icbc_credit_return`；工行借记卡解析阶段已经识别内部 `icbc_debit_refund`，本次将仅对 `summary=退货` 暴露正式 `icbc_debit_return`。
- `StatementImportService` 已保存导入行的完整 JSON 来源行快照，并在仓储读取时映射为 `FactView.raw_payload`。
- `FactCandidateIndex` 已将显式退款文本入桶；第 D 阶段已有受限的商户/P2P/待审核匹配和关系幂等性。

## 项目结构

### 功能文档

```text
specs/023-icbc-credit-refund-signal/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── icbc-credit-refund-signal.md
└── tasks.md
```

### 实现与测试

```text
src/ft/
├── convert.py                                  # 工行原始行与正式输出行
├── adapters/statement_import.py                # 正式解析行的元数据保留
└── domain/relations/
    ├── core/types.py                           # FactCandidateIndex / 门控协议
    ├── refund/signals.py                       # 来源受限退款信号判定
    ├── refund/match.py                         # 第 D 阶段退款匹配
    └── pipeline.py                             # 钻石阶段种子（共用信号）

tests/
├── test_convert.py
├── test_statement_import_mapping.py
├── test_relations_index_injection.py
├── test_transaction_relations_refund.py
└── <现有关系服务 SQLite/PostgreSQL 契约测试>
```

**结构决策**：在既有转换、关系领域包和测试模块内实施，不新增子项目、服务、表或运行时依赖。

## 复杂度跟踪

无需记录：没有宪法例外或额外抽象；事实级门控是使索引与最终规则保持一致的最小接口调整。

## GSTACK REVIEW REPORT

| 评审 | 执行方式 | 已识别问题 | 状态 | 处理 |
|------|----------|-----------:|------|------|
| 工程方案评审 | `plan-eng-review` | 1 | 已纳入方案 | 候选索引与最终匹配过去各自只看文本，方案已规定共用同一个事实级门控，防止候选阶段和判定阶段语义漂移。 |

**结论**：方案通过工程审查。范围、数据流、失败关闭、跨后端契约、测试路径和非目标明确；没有待用户决策或未解决的高风险问题。
