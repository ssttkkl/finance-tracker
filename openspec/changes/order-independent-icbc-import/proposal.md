## Why

工行借记卡 PDF 的表格同时包含本方账号和交易渠道，当前转换结果把渠道误用为来源账户身份，导致同一张卡被拆成多个映射组。关系规划还会在不同导入顺序下选择不同的退款镜像，可能生成非法收支投影并使导入失败。

## What Changes

- 使用工行借记卡 PDF 每行的本方账号作为稳定的来源账户身份，并以脱敏尾号作为映射证据；交易渠道继续只作为流水字段。
- 关系扫描在已有退款关系与同批平台退款关系存在时，先按退款对的两端对齐跨来源付款镜像，再执行普通镜像匹配。
- 保持来源行快照完整、业务行幂等、精确 `Decimal` 金额、退款/镜像关系审计和既有失败关闭语义。
- 增加 SQLite 隔离库、真实账单双顺序重放以及适用 PostgreSQL 合同矩阵的回归证据。

## Capabilities

### New Capabilities

### Modified Capabilities

- `statement-import`: 工行借记卡的来源账户身份必须来自本方账号，不得由交易渠道替代。
- `transaction-relations`: 相同账本记录、人工决定和规则版本在不同导入顺序下必须生成等价的关系与合法收支投影。

## Impact

- 影响 `src/ft/convert.py` 的工行借记卡行转换、`src/ft/application/statement_account_mapping.py` 的来源账户映射证据，以及 `src/ft/domain/relations` 和 `src/ft/application/relations.py` 的关系规划顺序。
- 增加解析、来源账户分组、关系规划和现金导入集成测试；不新增依赖、不新增迁移，不改变公开 API 字段。
- 回滚只需恢复转换和关系规划代码；不会修改既有来源行快照、账本记录或关系历史。
