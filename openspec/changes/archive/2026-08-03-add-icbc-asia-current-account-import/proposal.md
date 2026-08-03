## Why

工银亚洲活期账户导出的 `currentaccounthistory*.csv` 目前无法进入统一账本，使用者只能手工录入，既容易遗漏对方账号，也失去原始账单行的审计证据。样本已经提供交易时间、收支金额、币种、对方账号和对方户名，适合以受控解析器直接导入。

## What Changes

- 新增工银亚洲活期账户明细 CSV 的现金导入渠道和文件名推断。
- 将每笔交易的全部原始表头和单元格值保存为 `source_payload`，包括无标题的时间列；文件级账户和日期信息不进入该快照。
- 将原始 `對方賬號`、`對方戶名` 提升为正式 `counterparty_account`、`counterparty`，并以账户映射规则路由账本账户。
- 对 UTF-16、表头、币种、金额方向、账户标识和无法安全区分的重复业务行失败关闭。

## Capabilities

### New Capabilities

- `icbc-asia-current-account-import`: 导入工银亚洲活期账户明细 CSV，并保留完整的行级来源证据。

### Modified Capabilities

- `015-inline-row-provenance`: 明确允许以空字符串作为无标题来源列的 JSON 键，以完整保留该银行账单的原始业务行。

## Impact

- 影响现金账单转换、导入来源识别、账户映射、银行渠道关系路由、CLI 文档和导入测试。
- 不新增数据库表或列，不修改既有正式事实，不自动导入 `Downloads` 或 `~/.ft/bills` 中的文件。
- 需要 SQLite 与本地 PostgreSQL 的等价导入验证；发布前可通过既有映射文件为目标账户配置规则，失败时不写入任何事实。
