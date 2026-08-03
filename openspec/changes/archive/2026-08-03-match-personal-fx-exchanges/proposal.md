## Why

工行借记卡与信用卡 PDF 的账单摘要“转账”在解析时被丢弃，导致对应流水无法标准化为 `transfer_out` / `transfer_in`。这违背了 `record_type` 以来源原生摘要表达业务语义的设计，也使跨账单转账无法由既有普通转账规则正确配对。

工行个人购汇的 `fx_out` / `fx_in` 同样缺少安全的关系识别，导致两端被投影为消费和收入。

## What Changes

- 修复工行借记卡与信用卡 PDF 摘要提取，将“转账”等原生摘要保存到标准输出和 `source_payload`，并按方向生成正确的 `record_type`。
- 通过来源账单重建新数据库更正已丢失摘要的历史事实；不直接更新当前正式事实或伪造来源快照。
- 为个人购汇建立受来源、可靠时间、双向唯一候选和端点占用约束的 `transfer_pair(currency_exchange)`；不明确的情形保持待审核或未配对。
- 支持新 `fx_in` 有界反向激活历史 `fx_out` 锚点，确保增量与全量检查等价。
- 在证据详情中安全展示关系决策依据，不暴露原始来源快照或隐私数据。

## Capabilities

### New Capabilities

- 无。

### Modified Capabilities

- `024-normalized-cash-record-type`：修复工行借记卡与信用卡来源摘要的提取、持久化和转账类型标准化，并明确历史更正采用来源账单重建。
- `006-transaction-relations`：为个人购汇增加双向唯一、安全增量触发和人工确认合同。
- `020-cash-ledger-browser-web`：扩展已采用关系的安全证据详情合同。

## Impact

- 解析与标准化：`src/ft/convert.py`、`src/ft/domain/record_type.py`。
- 关系与投影：个人购汇候选索引、Phase C、关系接受和证据读取。
- 历史数据：通过独立 SQLite 重建、关系检查和投影重建验证后才可替换业务库；正式事实不原地更新。
- 不新增依赖、路由或外部服务；不增加工行或其他银行的关系层特例桥接。
