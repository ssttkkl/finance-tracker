## ADDED Requirements

### Requirement: 转账关系的本人账户标识证据

系统 MUST 在转账、提现和还款的转出方具有来源直接提供的对方账号时，以当前工作区用户显式登记的本人账户标识收敛或排除既有候选。账号命中只能筛选已满足金额、币种、时间、方向和记录类型规则的候选，且不得单独使关系自动确认。命中结果仅在本次匹配中使用，不得写入关系、日志、CLI 输出或任何 JSON。

#### Scenario: 账号证据不放宽转账门槛

- **WHEN** 对方账号命中一个候选账户但该候选不满足既有金额、币种、时间、方向或记录类型门槛
- **THEN** 系统 MUST 不创建或自动确认该转账关系

#### Scenario: 账号证据收敛强候选

- **WHEN** 多个既有强转账候选中只有一个目标账户命中来源直接提供的对方账号
- **THEN** 系统 MUST 仅对该命中候选按既有规则创建关系

### Requirement: 平台支付方式验证同账户支付镜像

系统 MUST 仅在既有 `payment_mirror` 的平台账单与银行账单属于同一 `account_id` 时，使用平台侧原始 `payment_method` 中的账户标识验证该账户。未掩码完整数字标识 MUST 与唯一 `account_identifier` 精确匹配；仅有四位尾号时，系统 MUST 同时使用显式 `card_tail` 和完整 `account_identifier` 推导的尾号，并且仅当合并结果唯一映射到该账户时作为匹配条件。该条件 MUST NOT 创建跨账户支付镜像、放宽金额或币种条件，或将完整账号、账号尾号或命中过程写入关系。

#### Scenario: 完整账号验证支付镜像

- **WHEN** 平台账单 `payment_method` 含有未掩码完整账号，且该账号唯一匹配同账户的 `account_identifier`
- **THEN** 系统 MUST 将其作为支付镜像匹配条件

#### Scenario: 由完整账号推导的唯一尾号验证支付镜像

- **WHEN** 平台账单 `payment_method` 仅包含四位尾号，用户没有对应 `card_tail` 但有同账户、尾号唯一的完整 `account_identifier`
- **THEN** 系统 MUST 将其作为支付镜像匹配条件

#### Scenario: 支付方式尾号冲突

- **WHEN** 显式 `card_tail` 与完整 `account_identifier` 推导后有多个账户共享平台支付方式尾号
- **THEN** 系统 MUST NOT 将该尾号作为支付镜像匹配条件或提高自动确认条件

## MODIFIED Requirements

### Requirement: 关系持久化仅保存可执行关系与人工决定

系统 MUST 在 `transaction_relations` 持久化工作区、关系种类、子类型、两侧端点或锚点、状态、`rule_id`、创建者/时间与人工决定。系统 MUST NOT 持久化金额或时间差、置信度、信号、账号命中、`evidence_json` 或“稍后处理”标记。待配对关系 MUST 以空的对侧端点表达，并以 `candidate_fact_ids` 持久化按规则排序、去重且最多 20 个的候选账本记录 ID，供人工选择对侧；该列不得包含任何其他证据。自动匹配的退款额度只在本次计算中使用。

#### Scenario: 已创建关系不含过程证据

- **WHEN** 系统创建、确认、驳回或取代一条关系
- **THEN** 关系表 MUST 不含 `evidence_json`、`confidence` 或 `later_marker`，且读取关系不依赖这些列

#### Scenario: 待配对关系保存可选择候选

- **WHEN** 系统创建一条具有多个合法候选的待配对关系
- **THEN** 系统 MUST 以空的对侧端点和锚点识别该关系，并仅在 `candidate_fact_ids` 写入有序候选账本记录 ID

#### Scenario: 人工确认只能选择已保存候选

- **WHEN** 用户确认一条待配对关系并指定对侧流水
- **THEN** 系统 MUST 拒绝不在 `candidate_fact_ids` 中、已删除或不在当前工作区的流水；成功确认、驳回或替换后 MUST 清空 `candidate_fact_ids`
