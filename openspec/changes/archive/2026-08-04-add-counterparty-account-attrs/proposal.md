## Why

现金流水目前只保存 `counterparty_account`，导入期会把支付宝、建行和工行账单中的掩码账号清空，关系匹配又根据字符长度和掩码符号临时猜测完整账号或尾号。真实账单审计证明该行为既损失可审计证据，也让匹配语义依赖隐式字符串规则。

## What Changes

- 为现金流水增加非空 JSON 数组列 `counterparty_account_attrs`，以 `full`、`tail`、`masked` 和 `reconstructed` 明确描述对方账号表示及严格验证后的重建来源。
- 调整支付宝、微信、建行、工行借记卡、工行信用卡和工银亚洲导入：来源直接提供且可归属到业务行的对方账号均写入正式字段，掩码与非数字账户标识不再因无法直接匹配而被清空。
- 让所有使用 `counterparty_account` 的转账匹配入口同时消费 `counterparty_account_attrs`；完整、尾号、掩码和重建值采用各自的失败关闭规则，缺失、未知或矛盾属性不得生成账号命中。
- 增加 SQLite/PostgreSQL 等价迁移，对已有正式值和可确定的完整来源行进行保守回填；无法证明表示类型的历史值保留账号原文但使用空属性，因此不参与账号匹配。
- 更新现金流水查询与 CSV 合同，使账号值与属性始终成对输出。
- **BREAKING**：新导入的非空 `counterparty_account` 必须同时具有合法属性；关系匹配不再从账号长度、掩码字符、导入渠道或来源快照推断其表示类型。

不改变支付宝未支付关闭与失败还款的既有白名单跳过规则，不把付款账户、映射账户或对方名称当作对方账号，也不新增非数字本人账户别名类型。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `015-inline-row-provenance`：现金流水正式字段增加对方账号属性，并要求所有受支持来源保留可识别的完整、尾号、掩码或非数字对方账号表示。
- `counterparty-account-transfer-matching`：本人账户标识匹配改为显式消费对方账号属性，不再根据账号字符串自行判断表示类型。
- `006-transaction-relations`：标准字段转账扫描合同增加 `counterparty_account_attrs`，并定义掩码账号的唯一、失败关闭匹配边界。

## Impact

- 影响现金账单解析、转换、导入校验、现金流水仓库、公开列表与 CSV 字段、关系 `FactView`、转账匹配、SQLAlchemy 模型、Alembic migration 和双后端契约测试。
- 不新增依赖；JSON 数组由现有 SQLAlchemy JSON 类型在 SQLite 与 PostgreSQL 中持久化。
- 对方账号及其属性仍属于账户隐私数据；关系、CLI、异常和日志不得回显账号原文或匹配过程。
- 部署前必须备份数据库。升级迁移只回填来源行能够证明的值和属性；回滚优先恢复备份，downgrade 仅删除属性列，不主动删除升级时补回的对方账号证据。
