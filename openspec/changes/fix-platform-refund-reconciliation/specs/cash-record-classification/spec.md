## ADDED Requirements

### Requirement: 转账退回分类与退款冲销关系可以并存
导入器 MUST 继续把个人转账、红包或群收款退回保存为 `transfer_reversal`，关系扫描 MUST NOT 将其改写为 `refund`；当来源同时提供唯一原交易标识、账户、币种、精确金额和有效时间顺序时，关系层可以用 `refund_offset` 的 `p2p_return` 子类型抵扣其对应出账。

#### Scenario: 微信红包退回被冲销
- **WHEN** 微信红包退回和对应红包出账均为 `transfer_reversal`，并且两条流水的来源交易标识、账户、币种、金额和时间顺序一致
- **THEN** 两条流水 MUST 保持 `transfer_reversal`，同时可以建立 `refund_offset`（`subtype=p2p_return`），不得把退回显示为消费退款记录类型

#### Scenario: 缺少原交易标识的转账退回
- **WHEN** 转账退回只有自由文本或金额方向证据，缺少唯一原交易标识
- **THEN** 系统 MUST 保持 `transfer_reversal` 且不得自动建立 `p2p_return`
