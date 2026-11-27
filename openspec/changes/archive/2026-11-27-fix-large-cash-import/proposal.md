## Why

多份现金账单经过整批核对后，确认可能在写集达到容量上限时被拒绝，客户端却持续显示结果待核实。CLI 的确认还会再次计算完整计划。按来源拆批改变了关联的候选集合和入账边界。

## What Changes

- 在核对阶段识别确定超限的完整写集。收到服务端明确拒写后，先核实原收据，再恢复编辑。网络和提交结果未知时保留原尝试。
- 同一工作区、事实版本、规则和核对修订匹配时，复用该批实际核对的请求和结果上下文。事实变化时沿用现有重验规则。
- 改善 CLI 大批核对的耗时、内存及阶段反馈。保持完整关系计算、一次原子写入和原收据。
- 用合成多来源账单验证实际安装包、SQLite、PostgreSQL 和受影响 Compose 路径。另以隔离探针对比 Kotlin/JS、Kotlin/Native 和 Node 宿主的 wasmJs。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `import/local-import-pipeline`：写集预算、明确拒写与原收据恢复。
- `shared/ledger-cli`：整批核对的确认复用及阶段反馈。

## Impact

A 类。涉及共同业务层、HTTP 提交协议、加密本地恢复、CLI 和 Compose 以及双库原子写入。保持现有容量、权限、关联规则和事务边界。平台迁移和线上数据操作不在范围内。
