## ADDED Requirements

### Requirement: 工行信用卡摘要的标准化存储
系统 MUST 从工行信用卡 PDF 的账单摘要列提取来源原文，并将其同时保留在标准导入行的 `summary` 和入库事实的 `source_payload.summary`。`summary` MUST 独立于交易场所形成的 `note`；例如摘要为“转账”、交易场所为“手机银行”时，两者 MUST 分别保存。工行信用卡/借记卡摘要为“转账”或“转帐”时，系统 MUST 按来源方向分类为 `transfer_in` 或 `transfer_out`，而不得以金额正负单独推断为普通收入或消费。

#### Scenario: 信用卡转账摘要驱动标准记录类型
- **WHEN** 工行信用卡 PDF 行的摘要为“转账”、交易场所为“手机银行”且金额为正
- **THEN** 标准导入行与 `source_payload` MUST 保留 `summary="转账"` 和 `note="手机银行"`，且 `record_type` MUST 为 `transfer_in`

### Requirement: 标准记录类型历史更正通过来源账单重建
系统 MUST 对因解析缺陷丢失来源摘要的历史现金事实使用独立数据库从原始账单重建、验证和受控替换来更正 `record_type` 与来源快照。系统 MUST NOT 通过直接更新当前正式事实、伪造 `source_payload` 或仅重建投影来更正该类语义错误。

#### Scenario: 解析修复后的历史事实重建
- **WHEN** 修复后的解析器从原始账单在独立 SQLite 数据库重建现金事实
- **THEN** 系统 MUST 在替换前验证事实数量、活动 identity、来源快照、记录类型、关系和投影，且失败时 MUST 保留原库和新库

### Requirement: 个人购汇的双向安全识别
系统 MUST 仅将同一工作区中负额 `fx_out` 与正额 `fx_in` 识别为个人购汇候选。双方 MUST 币种不同、来源键非空且无冲突并一致；候选查询 MUST 按来源键和上海业务日有界。自动确认 MUST 同时满足双方均有可靠钟点、时间差不超过 60 秒、正反两侧合法候选数均为 1、两端均未参与另一条已确认 `transfer_pair`。任一条件不成立时系统 MUST NOT 自动确认；候选歧义 MUST 以 `fx_out` 为锚点创建一条开放端 `pending_review`，并保存正反候选数、稳定候选业务行标识、来源状态、时间精度与阻断原因。

#### Scenario: 双向唯一的个人购汇自动确认
- **WHEN** 同一来源键下的 `CNY -47952.10 fx_out` 与 `USD +7000 fx_in` 均有可靠钟点且相差不超过 60 秒，并且双方各自仅有此合法候选
- **THEN** 系统 MUST 创建一条双边 `accepted transfer_pair(currency_exchange)`，并保存来源、时间精度、正反候选数和端点证据

#### Scenario: 反向不唯一不自动确认
- **WHEN** 一条 `fx_in` 同时是两条或更多合法 `fx_out` 的候选，即使每条 `fx_out` 各自只有该一个入账候选
- **THEN** 系统 MUST NOT 按扫描顺序确认任一关系，并 MUST 为被触发的 `fx_out` 建立可审计待配对关系

#### Scenario: 日期精度与空来源不自动确认
- **WHEN** 任一购汇端点只有日期精度，或任一端的来源键为空、冲突或不一致
- **THEN** 系统 MUST NOT 自动确认；日期精度的同业务日合法候选其 `time_delta_seconds` MUST 为 `null`，不得伪造为 `0`

### Requirement: 入账驱动的有界关系重评
系统 MUST 支持新导入的合法 `fx_in` 反向激活有界的既有 `fx_out` 锚点重评，但关系提议 MUST 始终由 `fx_out` 发出。增量检查和全量检查对相同活跃事实 MUST 得到等价的活跃关系集合，并保持幂等。

#### Scenario: 后到购汇入账补配历史转出
- **WHEN** `fx_out` 已存在且新导入唯一合法 `fx_in`
- **THEN** 系统 MUST 重评该 `fx_out` 并在满足自动确认条件时建立一条关系，而不得要求全量手工重跑
