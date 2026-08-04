## ADDED Requirements

### Requirement: 工银亚洲规范账号内部调拨关系

系统 MUST 对来源均为 `icbc_asia_current_account` 的转账出账和转账入账识别同一规范账号内的资金移动，即使两条正式事实已路由到同一 `account_id`。转出侧来源直接提供的、未掩码完整对方账号 MUST 通过当前工作区唯一登记的工银亚洲规范账号归属转入账户；用户仅登记 `card_tail` 时，系统 MUST 只将来源账号末四位的末位标准化为 `0` 后，与唯一已登记尾号比较。没有该归属、目标归属不唯一、方向或记录类型不合法时，系统 MUST 不创建关系。同币种时金额绝对值 MUST 精确相等，关系 subtype MUST 为空；异币种时关系 subtype MUST 为 `currency_exchange`，不得以金额换算或容差推断汇率。两侧时间差 MUST 不超过 5 分钟；只有正反两侧在该窗口内均唯一时系统才可以创建双边 `accepted transfer_pair`。存在多个合法候选时，系统 MUST 以转出为锚点创建一条 `pending_review` 待配对关系，并仅保存稳定的 `candidate_fact_ids`。

#### Scenario: 同一正式账户内的跨币种调拨
- **WHEN** 工银亚洲 CNY 转账出账的完整对方账号唯一归属到其自身规范账号，且 5 分钟内存在唯一 HKD 转账入账
- **THEN** 系统 MUST 创建双边 `accepted transfer_pair(currency_exchange)`，两条现金流水仍保留且不计入外部收支

#### Scenario: 工银亚洲同币种调拨金额不等
- **WHEN** 工银亚洲同一规范账号的同币种转账出账与入账金额存在任意非零 Decimal 差额
- **THEN** 系统 MUST NOT 自动确认 `transfer_pair`

#### Scenario: 工银亚洲内部调拨存在歧义
- **WHEN** 一笔工银亚洲规范账号转账出账在 5 分钟内有多个合法转账入账
- **THEN** 系统 MUST 仅创建一条以该出账为锚点的 `pending_review` 待配对关系，且不得自动排除任一现金流水

### Requirement: 工行跨境汇款至工银亚洲关系

系统 MUST 将来源为 `icbc_debit`、正式记录类型为负额 `transfer_out` 且来源语义明确为跨境汇款的流水作为工银亚洲跨境转账出账种子。候选必须是来源为 `icbc_asia_current_account` 的正额 `transfer_in`，其账本账户必须由转出侧未掩码完整对方账号唯一归属到同一工银亚洲规范账号，且币种一致、绝对金额严格相等、时间差不超过 36 小时。若在 10 秒强窗口中正反两侧唯一，或在 36 小时内正反两侧唯一，系统 MUST 创建双边 `accepted transfer_pair`；多个合法候选时系统 MUST 以跨境汇款出账为锚点创建一条 `pending_review` 待配对关系。该规则 MUST NOT 接受购汇类 `fx_out`、普通工行转账或未归属到工银亚洲规范账号的候选。

#### Scenario: 工行跨境汇款同币种短时到账
- **WHEN** 工行借记卡的 CNY 跨境汇款转出后 10 秒内，唯一工银亚洲规范账号出现同币种等额转账入账
- **THEN** 系统 MUST 创建双边 `accepted transfer_pair`

#### Scenario: 工行跨境汇款次日到账
- **WHEN** 工行借记卡的 USD 跨境汇款转出后 36 小时内，唯一工银亚洲规范账号出现同币种等额转账入账
- **THEN** 系统 MUST 创建双边 `accepted transfer_pair`

#### Scenario: 非跨境汇款不得走工银亚洲桥接
- **WHEN** 工行借记卡的购汇类 `fx_out` 或不具有明确跨境汇款来源语义的 `transfer_out` 出账出现
- **THEN** 系统 MUST NOT 因本规则为其创建 `transfer_pair`
