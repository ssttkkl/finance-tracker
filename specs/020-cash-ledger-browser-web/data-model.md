# 数据模型：收支投影

## 展示层边界（021 合并）

本 feature 的 React 展示层只消费既有账户、收支投影分页和证据 DTO；不新增持久化实体、API DTO 或数据库 schema。列表以累计收支记录、下一个 cursor、首批加载、追加加载和追加错误管理浏览状态；金额继续作为十进制字符串展示，前端不得重算财务结果。窄屏卡片保留原生表格列头关联，并通过真实文本显示分类与导入渠道。

现金流水和交易关系继续作为唯一事实源。以下表只保存可删除、可重建的派生读模型，全部受
`workspace_id` 约束。

## 领域输入

### 有效现金流水

来自 `cash_transactions`，要求 `deleted_at IS NULL`。投影使用 `id`、`account_id`、`occurred_at`、
`amount`、`currency`、`counterparty`、`category`、`note`、`source_type`、`record_id` 和
`source_payload`。

### 生效关系

来自 `transaction_relations`，仅使用 `status = 'accepted'`、两个端点均存在且类型均为 `cash` 的双边
关系。`primary_fact_id` 表达主记录方向，`secondary_fact_id` 指向它。

## 持久化实体

### `cash_projection_states`

每个工作区一行，作为并发锁、活动指针和诊断摘要。

| 字段 | 类型与约束 | 说明 |
|------|------------|------|
| `workspace_id` | `String(64)`，主键，外键 | 工作区。 |
| `active_dataset_id` | `String(64)`，可空 | 当前活动数据集；为空表示尚无有效投影。 |
| `projection_version` | `BigInteger`，非空，初始为 0 | 每次成功增量提交或全量发布后单调增加。 |
| `source_revision` | `BigInteger`，非空，初始为 0 | 受投影约束的事实源变更序号。 |
| `rules_version` | `String(64)`，非空 | 当前活动投影使用的规则版本。 |
| `availability` | `String(16)`，非空 | `uninitialized` 或 `ready`。 |
| `last_build_status` | `String(16)`，非空 | `never`、`running`、`succeeded` 或 `failed`。 |
| `last_build_id` | `String(64)`，可空 | 最近一次全量构建标识。 |
| `last_error_code` | `String(64)`，可空 | 稳定、脱敏的失败码。 |
| `last_error_summary` | `Text`，可空 | 不含原始账务数据、SQL、路径或凭据的失败摘要。 |
| `projection_count` | `BigInteger`，非空 | 活动数据集投影条目数。 |
| `member_count` | `BigInteger`，非空 | 活动数据集投影成员数。 |
| `build_started_at` / `build_finished_at` / `updated_at` | 带时区时间 | 构建和状态更新时间。 |

约束：`availability = 'ready'` 时 `active_dataset_id` 必须非空；失败构建不得清空已有活动指针。状态行可由
首次重建惰性创建，但创建前必须先锁定对应 `workspaces` 行。

### `cash_projection_datasets`

描述一次可发布的数据集。日常增量更新活动数据集；全量重建创建暂存数据集。

| 字段 | 类型与约束 | 说明 |
|------|------------|------|
| `id` | `String(64)`，主键 | 数据集 UUID。 |
| `workspace_id` | `String(64)`，外键，非空 | 工作区。 |
| `state` | `String(16)`，非空 | `staging`、`active` 或 `retired`。 |
| `source_revision` | `BigInteger`，非空 | 构建读取的来源版本。 |
| `source_digest` | `String(64)`，非空 | 全部投影与证据输入的规范序列 SHA-256，包含有效现金流水的身份、金额、币种、时间、账户、展示字段和已确认关系的全部构建字段。 |
| `rules_version` | `String(64)`，非空 | 投影规则版本。 |
| `created_at` / `published_at` | 带时区时间 | 创建与发布时间。 |

约束：一个工作区最多有一个 `active` 数据集；暂存数据集不能被 Web 查询。

### `cash_projections`

每行是一个投影条目。`id` 是数据库代理键，`projection_id` 是稳定业务标识，格式为
`cash:<root_cash_transaction_id>`。

| 字段 | 类型与约束 | 说明 |
|------|------------|------|
| `id` | PostgreSQL `BigInteger` / SQLite `Integer`，主键 | 代理键。 |
| `workspace_id` | `String(64)`，非空 | 工作区。 |
| `dataset_id` | `String(64)`，外键，非空 | 所属数据集。 |
| `projection_id` | `String(96)`，非空 | 稳定投影标识；在工作区和数据集内唯一。 |
| `root_cash_transaction_id` | 现金流水代理键，外键，非空 | 主记录。 |
| `economic_type` | `String(24)`，非空 | `expense`、`income` 或 `internal_transfer`。 |
| `transfer_subtype` | `String(32)`，可空 | `ordinary_transfer`、`credit_repayment`、`currency_exchange`、`bank_security_transfer`、`balance_adjustment` 或关系提供的受控 subtype。 |
| `net_amount` | 精确十进制，非空 | 投影净额；消费保持负号，收入保持正号。 |
| `currency` | `String(3)`，非空 | 主记录币种；退款必须同币种。 |
| `occurred_at` | 带时区时间，非空 | 主记录发生时间。 |
| `account_id` | 账户代理键，外键，非空 | 主记录账户。 |
| `counterparty` / `category` / `note` | 与现金流水一致 | 主记录展示字段。 |
| `source_type` / `record_id` | 与现金流水一致 | 主记录来源身份。 |
| `visible` | `Boolean`，非空 | 是否进入收支账本列表。 |
| `hidden_reason` | `String(32)`，可空 | `full_refund`、`internal_transfer` 或 `balance_adjustment`。 |
| `has_payment_mirror` / `has_refund_offset` / `has_transfer_pair` | `Boolean`，非空 | 组成方式筛选和摘要。 |
| `member_count` / `accepted_relation_count` | `Integer`，非空 | 证据数量摘要。 |
| `built_projection_version` | `BigInteger`，非空 | 最近一次写入该条目的投影版本。 |
| `created_at` / `updated_at` | 带时区时间 | 派生行生命周期。 |

索引：

- 唯一键：`(workspace_id, dataset_id, projection_id)`。
- 列表键：`(workspace_id, dataset_id, visible, occurred_at DESC, projection_id DESC)`。
- 筛选键：账户、币种、经济类型、分类分别与 `workspace_id`、`dataset_id` 组合。
- 根记录键：`(workspace_id, dataset_id, root_cash_transaction_id)`。

### `cash_projection_members`

记录有效现金流水的唯一投影归属。

| 字段 | 类型与约束 | 说明 |
|------|------------|------|
| `id` | PostgreSQL `BigInteger` / SQLite `Integer`，主键 | 代理键。 |
| `workspace_id` / `dataset_id` | 非空 | 工作区和数据集。 |
| `projection_row_id` | 外键，非空 | 对应 `cash_projections.id`。 |
| `cash_transaction_id` | 现金流水代理键，外键，非空 | 投影成员。 |
| `roles_json` | JSON，非空 | 确定性排序的 `root`、`mirror`、`refund`、`transfer` 角色集合。 |
| `ordinal` | `Integer`，非空 | 证据详情中的确定性顺序。 |

关键唯一键：`(workspace_id, dataset_id, cash_transaction_id)`。它从数据库层保证同一数据集中每条有效
现金流水最多属于一个投影；全量发布前还必须验证成员数等于有效现金流水数。`dataset_id` 建立单列索引，
用于数据集级删除和成员计数。

### `cash_projection_relations`

记录投影实际采用的已确认关系。

| 字段 | 类型与约束 | 说明 |
|------|------------|------|
| `id` | PostgreSQL `BigInteger` / SQLite `Integer`，主键 | 代理键。 |
| `workspace_id` / `dataset_id` | 非空 | 工作区和数据集。 |
| `projection_row_id` | 外键，非空 | 对应投影条目。 |
| `transaction_relation_id` | 交易关系代理键，外键，非空 | 生效关系。 |
| `kind` / `subtype` | `String`，非空 | 构建时采用的关系种类及 subtype。 |
| `ordinal` | `Integer`，非空 | 确定性证据顺序。 |

唯一键：`(workspace_id, dataset_id, transaction_relation_id)`。未生效关系不写入此表，证据详情按当前
投影成员实时批量读取并明确标记为未生效提示。`dataset_id` 建立单列索引，用于数据集级删除和关系依据计数。

## 领域输出

### 投影条目 DTO

`projection_id`、`projection_version`、主记录展示字段、`economic_type`、`transfer_subtype`、
`amount`、`currency`、`member_count`、组成方式、生效关系摘要和证据入口。金额始终为十进制字符串。

### 投影证据 DTO

包含投影结果、主记录、全部成员现金流水、生效关系、未生效关系提示和退款时间线。成员按角色、
发生时间和 ID 确定性排序；来源行快照继续使用既有白名单脱敏规则。

## 构建不变量

1. 活动数据集中的有效现金流水归属完整率和唯一率均为 100%。
2. 投影主记录必须是其成员，并且关系方向只能得到一个根；多根、无根或有向环均失败。
3. 同笔支付成员金额只计主记录一次。
4. 退款主端必须是负金额消费，退款端必须是正金额且币种一致；累计退款绝对值不得超过消费绝对值。仅两端金额均为零且币种一致时例外：该组形成净额为零的隐藏全额退款投影；单侧零金额退款无效。
5. 内部转账、全额退款和余额校准投影必须存在，但 `visible = false`。
6. `pending_review`、`rejected` 和 `superseded` 关系不得出现在 `cash_projection_relations` 中。
7. 相同事实、已确认关系和规则版本必须生成相同 `projection_id`、成员、净额、类型和显示状态。
8. `payment_mirror` 的两端金额、币种和经济方向必须一致；所有 `transfer_pair` 的两端必须为非零且金额异号，且仅币种相同时金额绝对值相等，币种不同时不比较金额绝对值；`currency_exchange` 还必须使用不同币种。
9. 同一个已确认关系连通组不得同时包含 `transfer_pair` 与 `refund_offset`；违反种类不变量的关系不得进入任何投影数据集。

## 状态转换

```text
无状态 ──首次重建开始──> uninitialized/running
  │                           │
  │                           ├─成功──> ready/succeeded + active dataset
  │                           └─失败──> uninitialized/failed
  └─合法事实源写入──> 无状态或 uninitialized（不生成活动数据集）

uninitialized ──显式首次重建成功──> ready
ready ──合法源变更 + 增量维护成功──> ready（版本递增）
ready ──增量维护失败──> 源变更与派生变更一并回滚
```
  │
ready ──增量变更成功──> ready + 同一 dataset + version + 1
  │
  └─全量重建开始──> ready/running + staging dataset
                         ├─成功──> 新 dataset active，旧 dataset retired，version + 1
                         └─失败──> 旧 dataset 保持 active，ready/failed
```

失败构建的业务事务先回滚，再以独立短事务写 `last_build_status` 和脱敏错误；该事务只有在活动指针和来源
版本仍与失败前一致时才能更新状态，避免迟到诊断覆盖更新后的健康状态。

## PostgreSQL 与 SQLite 差异

| 主题 | PostgreSQL | SQLite | 等价性要求 |
|------|------------|--------|------------|
| 代理键 | `BIGINT` 自增 | `INTEGER` 自增 | API 不暴露代理键。 |
| 工作区写锁 | 状态行 `SELECT ... FOR UPDATE` | Unit of Work 的 `BEGIN IMMEDIATE` | 并发写入不得发布过期投影。 |
| 批量替换 | `DELETE` / 批量 `INSERT`，可使用服务端批量能力 | 同事务参数化 `DELETE` / `INSERT` | 投影结果和版本相同。 |
| 全量暂存 | 新 `dataset_id` 行集 | 新 `dataset_id` 行集 | 校验前均不可查询。 |
| Web 读取 | 服务端只读连接 | 既有只读快照连接 | 同一请求只读取活动数据集和一个投影版本。 |
| JSON | 原生 JSON | SQLAlchemy JSON 序列化 | 角色顺序和响应内容相同。 |

## 迁移与回滚

- Alembic `20260729_11` 新增上述 5 张派生表、约束和初始索引；`20260731_12` 仅为投影成员和投影关系依据表新增 `dataset_id` 索引，不更新现金流水、关系或快照。
- 升级后显式运行 `ft projections rebuild`；未构建时 Web 返回 `projection.unavailable`。
- 应用回滚可继续使用旧代码忽略派生表；必要时可降级删除投影表，事实源不需要反向转换。
- 重建命令可重复执行；失败的暂存数据集可安全删除，不影响活动数据集。
