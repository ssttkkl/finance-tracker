# 现金流水分类

## Purpose

定义现金流水在导入边界生成标准记录类型和标准记录子类型时使用的正式语义、来源证据和失败边界，避免关系扫描重新分类或根据自由文本猜测业务含义，并让历史更正保持可审计。

## Requirements

### Requirement: 每条导入现金流水都有标准记录类型

每条成功导入的现金流水 MUST 在入库前获得非空 `record_type`，并按需获得规范 `record_subtype`。流水类型必须表达来源业务语义，不能把零金额、金额方向或缺少关键词本身当作业务类型。导入边界不得把来源分类、旧 `income` / `expense` / `transfer*` / `checkin` 值或 `record_type` 写入用户收支分类；新导入流水必须保持空 `category_id`，重复导入或来源更新不得覆盖既有用户分类。

#### Scenario: 导入普通消费
- **WHEN** 来源结构化字段明确表示一笔消费支出
- **THEN** 现金流水 MUST 保存对应消费记录类型、保留用于分类的来源字段，并保持无收支分类

#### Scenario: 导入零金额业务行
- **WHEN** 一个受支持来源允许零金额业务记录
- **THEN** 系统 MUST 按来源业务语义生成流水类型，不得生成专用“零金额”类型或默认收支分类

#### Scenario: 再次导入已有用户分类的流水
- **WHEN** 相同导入渠道和业务行标识的现金流水已经由使用者设置收支分类后再次导入
- **THEN** 导入合并 MUST 保留其 `category_id`，不得以来源字段、金额方向或流水类型覆盖

### Requirement: 分类只使用来源原生结构化证据
导入器 MUST 优先使用来源提供的交易类型、方向、结构化摘要和行结构生成 `record_type` 与 `record_subtype`。系统 MUST NOT 在关系扫描阶段通过自由备注、交易对方关键词或金额正负重新定义一级记录类型。

#### Scenario: 普通收入包含退款关键词
- **WHEN** 一条普通收入仅在自由文本中包含“退款”字样，但来源类型未表示消费退款
- **THEN** 系统 MUST NOT 将其分类为 `refund`

#### Scenario: 工行退货来源摘要
- **WHEN** 工行信用卡或借记卡来源摘要精确为 `退货` 且金额方向符合来源合同
- **THEN** 导入器 MUST 将该流水分类为可进入消费退款匹配的 `refund`

### Requirement: 资金移动使用标准记录子类型
转账、提现、还款、换汇和内部账户调拨 MUST 使用标准记录类型与标准记录子类型表达来源明确的资金移动语义。跨境汇款不得仅因两端币种不同而改写为购汇；个人转账、红包或群收款退回必须使用 `transfer_reversal`，不得进入消费退款分类。

#### Scenario: 跨境汇款没有换汇语义
- **WHEN** 来源明确表示跨境汇款但未表示购汇或汇兑
- **THEN** 流水 MUST 保留转账方向和 `cross_border_remittance` 子类型，不得分类为 `fx_out` 或 `fx_in`

#### Scenario: 转账被退回
- **WHEN** 支付平台来源明确表示个人转账、红包或群收款退回
- **THEN** 流水 MUST 使用 `transfer_reversal`，不得分类为消费退款或普通转账

### Requirement: 对方账号及其属性在导入边界生成
来源直接提供的完整、尾号、掩码或经严格验证重建的对方账号 MUST 在导入时生成规范 `counterparty_account` 和有序 `counterparty_account_attrs`。系统不得根据字符串长度、导入渠道或关系候选结果补猜账号属性。

#### Scenario: 来源只提供四位尾号
- **WHEN** 来源字段明确只提供独立四位账号尾号
- **THEN** 现金流水 MUST 保存该值并标记 `tail`，不得把它标记为完整账号

#### Scenario: 非空账号无法生成合法属性
- **WHEN** 来源提供非空账号但其表示与允许的账号属性组合矛盾
- **THEN** 系统 MUST 拒绝整批导入，不得清空账号后继续

### Requirement: 历史分类更正必须可审计
当来源分类规则发生纠正时，系统 MUST 通过明确的 OpenSpec 变更、数据重建或受控迁移更新记录，且不得由关系扫描静默覆盖既有正式类型。

#### Scenario: 分类规则升级
- **WHEN** 新版本纠正一个来源动作到标准记录类型的映射
- **THEN** 更正流程 MUST 能说明受影响来源、重建或迁移方式以及验证结果

### Requirement: 转账退回分类与退款冲销关系可以并存
导入器 MUST 继续把个人转账、红包或群收款退回保存为 `transfer_reversal`，关系扫描 MUST NOT 将其改写为 `refund`；当来源同时提供唯一原交易标识、账户、币种、精确金额和有效时间顺序时，关系层可以用 `refund_offset` 的 `p2p_return` 子类型抵扣其对应出账。

#### Scenario: 微信红包退回被冲销
- **WHEN** 微信红包退回和对应红包出账均为 `transfer_reversal`，并且两条流水的来源交易标识、账户、币种、金额和时间顺序一致
- **THEN** 两条流水 MUST 保持 `transfer_reversal`，同时可以建立 `refund_offset`（`subtype=p2p_return`），不得把退回显示为消费退款记录类型

#### Scenario: 缺少原交易标识的转账退回
- **WHEN** 转账退回只有自由文本或金额方向证据，缺少唯一原交易标识
- **THEN** 系统 MUST 保持 `transfer_reversal` 且不得自动建立 `p2p_return`
