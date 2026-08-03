## Why

当前 `source_payload` 保存的是解析后行，而不是原始账单业务行。支付宝的 `对方账号` 等字段会在解析阶段丢失，且快照混入映射和关系处理字段，无法可靠审计或支持后续转账识别。

## What Changes

- 将 `source_payload` 的合同收紧为原始账单单行的完整列名和值，禁止缺列、派生字段和兼容空字段。
- 为现金流水增加正式的可查询 `counterparty_account`（对方账号）列；保留原始值，不以规范化结果覆盖来源行快照。
- 更新支付宝、微信、建行和工行现金账单解析器，使其从原始列捕获对方账号，并只把原始行写入 `source_payload`。
- **BREAKING**：详情/内部读取方不得再依赖 `source_payload` 中的 `account_name`、`record_type`、`source_type`、映射结果或关系处理字段。
- 为既有数据库执行可审计迁移：从现存快照可确定的值回填 `counterparty_account`；不能从已丢失来源恢复的历史字段保持空值，不伪造原始行。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `015-inline-row-provenance`：收紧来源行快照的完整性边界，并增加现金流水对方账号的正式持久化合同。

## Impact

- 影响现金账单解析、导入编排、关系候选读取、SQLAlchemy 模型、Alembic 迁移、SQLite/PostgreSQL 契约测试和数据库结构文档。
- 不保存来源文件路径、文件内容或文件级元数据；仅保存每个业务行的原始列值。
- 对方账号属于账户隐私数据，延续现有本地受控账本的保护边界；日志、测试夹具和错误信息不得输出真实账号。
- 回滚通过数据库备份或 Alembic downgrade 删除正式列；无法恢复的历史原始字段不尝试反向构造。
