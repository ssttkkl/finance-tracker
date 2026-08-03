# Data Model: PostgreSQL-Only Runtime Storage

## Workspace

- `id`: string PK
- `name`: string
- `created_at`: timestamptz

所有 runtime repository 在构造时绑定一个 workspace。调用参数不得覆盖它。

## Account

- `id`: UUID/string PK，稳定标识
- `workspace_id`: FK -> Workspace
- `name`, `type`, `currency`, `active`
- `metadata_json`
- `created_at`, `updated_at`
- unique `(workspace_id, name, currency)`

账户重命名只改变 `name`；正式事实通过 `account_id` 关联，不级联改写历史事实。
有现金/投资事实引用时，`account_id` 外键使用 `ON DELETE RESTRICT`；空账户才能硬删除。

## CashTransaction

- `id`: UUID PK
- `workspace_id`: FK -> Workspace
- `account_id`: FK -> Account
- `raw_record_id`: nullable FK -> RawRecord；statement-derived fact 必填，manual fact 为空
- `external_record_id`: provider 记录标识，可空
- `occurred_at`: timestamptz
- `amount`: numeric(38,18)
- `currency`, `counterparty`, `description`, `category`
- transfer/offset metadata
- `revision`, `created_at`
- provider 标识存在时 unique `(workspace_id, source_kind, external_record_id)`

金额在持久化和投影计算中保持原始有限 Decimal，不做量化或展示舍入；超过 18 位小数直接拒绝，
不允许依赖数据库隐式舍入。

`occurred_at` 保存 UTC timestamptz。provider 自带 offset 时保留其瞬时时间；无 offset 的现有中国账单
按 workspace `Asia/Shanghai` 解释，查询按相同时区形成日/月边界。

## InvestmentEvent

- `id`: UUID PK
- `workspace_id`: FK -> Workspace
- `account_id`: FK -> Account
- `raw_record_id`: nullable FK -> RawRecord；statement-derived fact 必填，manual fact 为空
- `occurred_at`: timestamptz
- `kind`, `currency`, typed JSON payload
- `revision`, `created_at`

payload 中 Decimal 写为十进制字符串，禁止 float。

## LedgerProjection

- `workspace_id`: PK/FK
- `payload`: JSON projection
- `version`, `updated_at`

payload 的账户 bucket 以稳定 account ID 为 key，账户名只在查询时 join。它是从正式 facts 派生的缓存，
不是 source of truth。任何修复只能从 PostgreSQL facts 重建。

## ImportBatch

- `id`: UUID PK
- `workspace_id`: FK
- `target_account_id`: FK -> Account；本次导入显式选择的稳定账户标识
- `source_kind`: provider/parser kind
- `source_digest`: SHA-256
- `source_ref`: 去敏后的文件名或用户可识别标签，不保存不必要的绝对路径
- `status`: pending/completed；pending 只在当前事务内，失败事务不留下 batch
- `created_at`, `completed_at`
- unique `(workspace_id, source_kind, source_digest)`

重复 completed batch 返回原结果，不重复发布 facts。
批次目标账户直接保存在 batch 上，不从 raw record 或 formal fact 反推；因此全重叠批次仍可拒绝将同一
source digest 重新指向另一个账户。

## RawFile

- `id`: UUID PK
- `workspace_id`, `batch_id`
- `content_digest`, `size_bytes`, `media_type`, `source_ref`
- `created_at`
- unique `(workspace_id, batch_id, content_digest)`

RawFile 归属一个 batch；RawRecord 通过 `(workspace_id, batch_id, raw_file_id)` 组合外键保证不会
引用其他 batch 的原始文件。

原始内容本身不复制进仓库或日志；模型保存标识和解析证据。

## RawRecord

- `id`: UUID PK
- `workspace_id`, `batch_id`, `raw_file_id`
- `source_type`, `source_identity`, `source_line`
- `payload`: immutable JSON
- `created_at`
- unique `(workspace_id, source_type, source_identity)`

## RecordRevision

- `id`: UUID PK
- `workspace_id`
- `cash_transaction_id`: nullable FK
- `investment_event_id`: nullable FK
- `before`, `after`: Decimal-safe JSON
- `actor_type`, `reason`, `created_at`

check constraint 要求两个目标 FK 恰好一个非空；组合 workspace FK 保证 revision 与 fact 同 workspace。

raw records 不可修改；正式 facts 的后续修订只追加 revision。

## Transaction invariants

1. batch/raw/formal fact/link/revision/projection/completion 一次 commit。
2. exception 或 validation failure 回滚全部变更。
3. 任何跨 workspace 查找都返回 not found，不允许 fallback。
4. 金额入库前必须转换为有限 Decimal；币种显式大写三字符。
5. 旧 `~/.ft` 文件永远不进入上述模型。
6. 数据库与投影计算不舍入、不使用 float；展示层舍入不回写 facts。
7. 时间解析失败或无法确定 provider 时区时，事务在写入前失败。
8. 现货投资事件不得使持仓为负；超额 sell/swap 直接拒绝，不推断空头成本。
