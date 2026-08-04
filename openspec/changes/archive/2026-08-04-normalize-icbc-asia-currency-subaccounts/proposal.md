## Why

当前工银亚洲逻辑错误地删除完整币种子账号的最后一位，导致规范账号和映射尾号少一位，例如 `...74240` 被错误路由为 `7424`。币种区分位应标准化为 `0`，而不是移除。

## What Changes

- 将工银亚洲完整币种子账号规范化为同长度、末位为 `0` 的规范账号。
- 使用规范账号的最后四位进行账户路由和 `payment_method` 展示。
- 工银亚洲内部转账匹配以规范账号的前缀识别不同币种子账号及扩展账号，同时拒绝缺少币种位的截断账号。
- **BREAKING**：工银亚洲账户映射来源键从错误的 `icbc_asia_current_account_<去末位尾号>` 改为 `icbc_asia_current_account_<规范账号尾号>`；现有配置必须从 `7424` 改回 `4240`。

## Capabilities

### New Capabilities

- 无。

### Modified Capabilities

- `icbc-asia-current-account-import`：工银亚洲账户路由改为使用同长度的末位 `0` 规范账号。
- `counterparty-account-transfer-matching`：工银亚洲转出流水按规范账号家族筛选既有转账候选。

## Impact

- 受影响模块：工银亚洲 CSV 解析器、转账候选匹配、导入映射、SQLite/PostgreSQL 契约测试及用户本地映射配置。
- 不修改 `source_payload`、数据库 schema、完整子账号业务行键或其他来源的账号匹配规则。
- 回滚时恢复先前代码和 `~/.ft/mapping.yaml` 备份；本次不自动改写历史流水。
