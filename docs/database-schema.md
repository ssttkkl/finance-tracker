# Finance Tracker 数据库表结构文档（018 落地态）

> **文档性质**：**`015` 内联溯源 + `016` 整数代理主键 + `018` `sync_cursors`** 后的 schema 速查。  
> **运行时权威**：`src/ft/adapters/relational/models.py` + Alembic head **`20260804_20`**（`SCHEMA_REVISION` 同值）。
> **双后端**：PostgreSQL 与文件型 SQLite；逻辑 schema 共享。  
> **主键**：账本代理主键/外键为 **整数**（PG `BIGINT` / SQLite `INTEGER`，`SurrogatePK`）。  
> **不恢复**：015 已删的导入作业/raw/修订/检查 run 等表。

可执行行为与持久化合同以 constitution 与各 feature `spec.md` 为准；本文是结构速查。

### 相对旧基线的变更摘要

| 动作 | 对象 |
|---|---|
| **删除表（015）** | `import_batches`、`raw_files`、`raw_records`、`fact_deletion_events`、`record_revisions`、`relation_check_runs` |
| **保留** | `ledger_snapshots`；wealth 读模型族 |
| **新增表（018）** | `sync_cursors` |
| **正式幂等列** | **`record_id` × `source_type`**；`source_payload` 内联溯源 |
| **主键（016）** | 账本整数代理 PK/FK |

---

## 1. 总体架构

```
workspaces  (租户隔离根)
    │
    ├─ accounts
    │     ├─ account_aliases
    │     └─ account_lifecycle_events
    │
    ├─ cash_transactions     source_type / record_id / source_payload
    ├─ investment_events     source_type / record_id / source_payload + residual payload
    │
    ├─ transaction_relations
    ├─ sync_cursors              (018 连接器增量游标)
    ├─ ledger_snapshots          (派生缓存，非 SoT)
    │
    └─ wealth_* 读模型族
          valuation_observations
          wealth_source_manifests / items
          wealth_generations / generation_days / active_manifests
          wealth_daily_results
          wealth_coverage_dispositions
          wealth_components
          wealth_evidence_manifests / items / manifest_items
```

### 设计原则（摘要）

| 原则 | 说明 |
|---|---|
| Workspace 隔离 | 几乎所有业务表带 `workspace_id`；跨表 FK 多为复合 `(workspace_id, id)`，禁止跨 workspace 关联 |
| 正式事实 SoT | `cash_transactions` / `investment_events` 为账本事实；CSV/PDF **仅瞬时输入**，不落库为作业/文件表 |
| 行级幂等 | 账单行是否已入账只看活跃事实上的 **`record_id` × `source_type`**（`source_type`=导入渠道名） |
| 行级原始快照 | `source_payload` 与正式列同在事实行；供 relations hard-key / 排障；**不是**文件档案；渠道正式列为 `source_type` |
| 金额精度 | `ExactDecimal`：PG `NUMERIC(38,18)`；SQLite 无损文本；禁止 float |
| 时间 | `UTCDateTime`：一律 timezone-aware UTC |
| 逻辑删除 | 现金事实行上 `deleted_at`/`deleted_by`/`delete_reason`（无独立删除事件表）；删后再导同一 **`record_id` × `source_type`** 可新建活跃实例 |
| 修订审计 | **无** `record_revisions`；不持久化改账版本链；导入快照看 `source_payload` |

---

## 2. 公共类型与约定

### 2.1 自定义列类型

| 类型 | PostgreSQL | SQLite | 规则 |
|---|---|---|---|
| `ExactDecimal` | `NUMERIC(38,18)` | `String(96)` 十进制文本 | 有限 Decimal；PG 读后 strip 尾零 padding |
| `UTCDateTime` | `timestamptz` | aware `DateTime` | bind 必须带 tz；统一存 UTC |

### 2.2 主键形态（当前，016）

- **账本代理键**（`accounts` / `cash_transactions` / `investment_events` / `transaction_relations` / `account_aliases` 等）：`SurrogatePK` = PG `BIGINT` / SQLite `INTEGER`，`autoincrement`
- **事实/关系上的账户与事实引用 FK**：同为整数代理键（复合 FK 仍带 `workspace_id`）
- 部分 wealth 读模型：调用方提供的确定性字符串 ID（`String(128/160)`）
- `workspaces.id`：`String(64)` 业务键（由配置 `FT_WORKSPACE_ID` 绑定）
- `ledger_snapshots` / `wealth_active_manifests`：以 `workspace_id` 为 PK（每 workspace 一行）
- **对外/导入幂等**仍用事实行 **`record_id` × `source_type`**，不把整数代理 id 当作导入标识

### 2.3 领域枚举（应用层约束，部分有 DB Check）

| 概念 | 取值 |
|---|---|
| 账户类型 `accounts.type` | `cash` / `loan` / `lend` / `security` / `crypto` |
| 现金分类（应用） | `income` / `expense` / `transfer` / `transfer_in` / `transfer_out` / `checkin` |
| 关系 kind | `payment_mirror` / `transfer_pair` / `refund_offset`（DB Check） |
| 关系 status | `pending_review` / `accepted` / `rejected` / `superseded`（DB Check） |
| 关系检查 run status | `pending` / `running` / `completed` / `failed`（DB Check） |
| 估值 identity_kind | `cash_account` / `position` / `instrument_quote` / `currency_pair` / `fx` |
| 币种 | 开放 3 字母码；展示常用 `CNY` / `USD` / `HKD` |

---

## 3. 核心租户与账户

### 3.1 `workspaces`

工作区（隔离边界）。

| 列 | 类型 | 空 | 说明 |
|---|---|---|---|
| `id` | String(64) **PK** | N | 工作区键 |
| `name` | String(255) | N | 显示名 |
| `created_at` | UTCDateTime | N | 创建时间 |

删除 workspace → 级联清理其下属行（视各 FK `ondelete`）。

### 3.2 `accounts`

账户主数据。多币种后：**账户名在 workspace 内唯一**；币种落在事实行上，账户本身无 `currency` 列。

| 列 | 类型 | 空 | 说明 |
|---|---|---|---|
| `id` | **SurrogatePK**（BIGINT/INTEGER）**PK** | N | 自增代理主键（016） |
| `workspace_id` | String(64) FK→workspaces | N | `ON DELETE CASCADE` |
| `name` | String(255) | N | 显示名 |
| `type` | String(32) | N | 账户类型 |
| `active` | Boolean | N | 默认 true |
| `metadata_json` | JSON | N | 扩展元数据，默认 `{}` |
| `created_at` | UTCDateTime | N | |
| `updated_at` | UTCDateTime | N | onupdate |

**约束 / 索引**

- `uq_accounts_workspace_id`：`(workspace_id, id)` — 复合 FK 目标
- `uq_accounts_workspace_name`：`(workspace_id, name)`
- `ix_accounts_workspace`：`(workspace_id)`

有事实引用时账户 FK 多为 `RESTRICT`，空账户才可硬删。

### 3.3 `account_aliases`

账户别名（导入匹配、展示映射）。

| 列 | 类型 | 空 | 说明 |
|---|---|---|---|
| `id` | **SurrogatePK** **PK** | N | 自增代理主键（016） |
| `workspace_id` | String(64) FK→workspaces | N | CASCADE |
| `alias_type` | String(32) | N | 别名类型 |
| `alias_value` | String(255) | N | 别名值 |
| `account_id` | SurrogatePK | N | 复合 FK→accounts |
| `created_at` / `updated_at` | UTCDateTime | N | |

**约束 / 索引**

- `uq_account_aliases_workspace_id`：`(workspace_id, id)`
- `uq_account_aliases_value_account`：`(workspace_id, alias_type, alias_value, account_id)`
- `fk_account_aliases_workspace_account`：`(workspace_id, account_id)` → accounts，`CASCADE`
- `ix_account_aliases_workspace_value`：`(workspace_id, alias_type, alias_value)`

用于转账关系的本人账户标识仅接受两种别名类型：`card_tail` 为恰好四位 ASCII 数字；`account_identifier` 为可去除空白、连字符和括号的完整数字账号。它们必须由用户显式登记，匹配时只参与运行时筛选，不将命中种类或别名原文写入关系记录。工银亚洲账号的币种位在导入期已标准化为末位 `0`；关系层只以该规范值精确匹配完整账号或唯一尾号，不按来源或账号前缀扩展候选。

### 3.4 `account_lifecycle_events`

账户生命周期事件（开户/销户等，驱动 wealth 覆盖「不适用」）。

| 列 | 类型 | 空 | 说明 |
|---|---|---|---|
| `event_id` | String(128) **PK** | N | 确定性事件标识 |
| `workspace_id` | String(64) FK→workspaces | N | CASCADE |
| `account_id` | SurrogatePK | N | 复合 FK→accounts，`RESTRICT` |
| `event_kind` | String(16) | N | 事件种类 |
| `effective_at` | UTCDateTime | N | 生效时刻 |
| `source_identity` | String(255) | N | 来源标识 |
| `source_revision` | String(128) | N | 来源修订 |
| `reason` | Text | N | 默认 `""` |
| `created_at` | UTCDateTime | N | |

**约束 / 索引**

- `uq_lifecycle_workspace_event`：`(workspace_id, event_id)`
- `ix_lifecycle_workspace_account_effective`：`(workspace_id, account_id, effective_at)`

---

## 4. 导入与文件（015：不持久化）

**015 删除**基线中的整条导入溯源链：

| 已删除表 | 原职责 | 替代 |
|---|---|---|
| `import_batches` | 导入作业壳、source_digest、status | **无**；文件仅为进程内瞬时输入 |
| `raw_files` | 文件路径/大小/media_type/digest | **无** |
| `raw_records` | 行 identity + payload + 幂等唯一 | **迁入** `cash_transactions` / `investment_events` 的 `source_*` 列 |

### 4.1 运行时导入语义（非表）

```
读文件（内存/临时文件）
  → parse
  → 每行 source_type + record_id + source_payload
  → 查活跃正式事实是否已占用 identity
  → novel → INSERT 正式事实（含 source_*）
  → 更新 snapshot
  → relations.check(seed_fact_ids=新建 id)
```

- **不**写入文件 digest / 路径 / batch 状态到数据库。  
- 幂等门禁：**仅**活跃事实的 **`record_id` × `source_type`**（导入渠道名 × 行键）。  
- CLI 可报告 `new_rows` / 成功消息；**无**持久化 `batch_id`。


---

## 5. 正式账本事实

### 5.1 `cash_transactions`

现金正式事实。账单派生行在同行携带 `source_type` / **`record_id`** / `source_payload`；**无** `raw_record_id`。

| 列 | 类型 | 空 | 说明 |
|---|---|---|---|
| `id` | **SurrogatePK** **PK** | N | 自增代理主键（016） |
| `workspace_id` | String(64) FK→workspaces | N | CASCADE |
| `account_id` | SurrogatePK | N | 复合 FK→accounts `RESTRICT` |
| `source_type` | String(64) | Y | **导入渠道名**（幂等复合键的一半；如 alipay/wechat/券商 kind）；手工可空 |
| `record_id` | String(512) | Y* | **业务行键**（平台流水/展示号或确定性内容键）；与 `source_type` 组成幂等；账单派生应非空；手工可空。空串视为「无标识」（见约束） |
| `source_payload` | JSON | Y | 账单派生行的完整原始业务行快照：保留全部来源列和值（含空值），不得混入解析、映射或关系字段；手工可空 |
| `occurred_at` | UTCDateTime | N | 发生时刻（UTC） |
| `amount` | ExactDecimal | N | 有符号金额 |
| `currency` | String(3) | N | 币种 |
| `counterparty` | String(512) | N | 对手方 |
| `counterparty_account` | String(512) | N | 来源直接提供的对方账号、卡号或账户标识的导入期规范值；可识别的掩码与非数字标识不得因当前无法匹配而清空 |
| `counterparty_account_attrs` | JSON | N | 对方账号属性数组；规范组合为 `[]`、`["full"]`、`["tail"]`、`["masked"]` 或 `["masked", "reconstructed"]` |
| `note` | Text | N | 备注 |
| `category` | String(64) | N | 分类（含 transfer / transfer_in / transfer_out 等） |
| `record_type` | String(32) | N | 标准记录类型 |
| `record_subtype` | String(32) | N | 标准记录子类型；与 `record_type` 受组合约束 |
| `created_at` | UTCDateTime | N | |
| `deleted_at` | UTCDateTime | Y | 逻辑删除时间 |
| `deleted_by` | String(128) | N | 删除操作者（行内审计；无独立删除事件表） |
| `delete_reason` | Text | N | 删除原因 |

\* 应用层：空 `record_id` 或空 `source_type` 表示无完整业务标识（手工）；**二者皆非空**才参与幂等。

**约束 / 索引**

- `uq_cash_transactions_workspace_id`：`(workspace_id, id)`
- **部分唯一** `uq_cash_transactions_active_source_record`（PG/SQLite partial）：  
  `(workspace_id, source_type, record_id)`  
  **WHERE** `source_type IS NOT NULL AND source_type <> '' AND record_id IS NOT NULL AND record_id <> '' AND deleted_at IS NULL`  
  → 活跃账单行按 **`record_id` × `source_type`** 不双记；逻辑删除后同一复合标识可再入账为新行；跨渠道相同 `record_id` 字面不冲突
- `ix_cash_transactions_workspace_date`：`(workspace_id, occurred_at)`
- `ix_cash_transactions_workspace_account`：`(workspace_id, account_id)`
- `ix_cash_transactions_workspace_source_record`：`(workspace_id, source_type, record_id)`（查询/幂等查找）

**规则**

- 账单派生：`source_type`（导入渠道名）、`record_id`、`source_payload` 均应非空（应用层强制）。
- 手工事实：标识相关字段可空；不占用 partial unique。
- 幂等比较单位永远是 **`(source_type, record_id)`**，不是裸 `record_id`。
- 账单派生的 `source_payload` 必须保存完整原始业务行；关系读取只能消费该快照中的原始字段或已提升的正式列，不得要求派生键。
- **015 删除**列：`raw_record_id`、`offset_*`、`proposed_action`、`locked`、`transfer_account`、`source`、`bill_source`、以及无改账语义时的 `revision`。核销/转账权威在 `transaction_relations` + 正式 `category`。

### 5.2 `investment_events`

投资事件（证券、加密资产等）的资产组成字段已提升为正式列（014）；`payload` 保留非核心扩展。

| 列 | 类型 | 空 | 说明 |
|---|---|---|---|
| `id` | **SurrogatePK** **PK** | N | 自增代理主键（016） |
| `workspace_id` | String(64) FK→workspaces | N | CASCADE |
| `account_id` | SurrogatePK | N | → accounts `RESTRICT` |
| `source_type` | String(64) | Y | **导入渠道名**（幂等复合键一半；券商/解析 kind）；手工可空 |
| `record_id` | String(512) | Y | **业务行键**（与 `source_type` 组成幂等；与现金目录同名；不用平行 source_identity） |
| `source_payload` | JSON | Y | 完整来源行快照；与 residual `payload` 分工见下 |
| `occurred_at` | UTCDateTime | N | |
| `record_type` | String(64) | N | 规范记录类型：`funding`、`trade`、`income`、`expense`、`reversal`、`subscription`、`adjustment` 或 `snapshot` |
| `record_subtype` | String(32) | N | 记录子类型；与 `record_type` 受组合约束，例如 `funding(external)`、`expense(tax)`、`reversal(expense_tax)` |
| `currency` | String(3) | N | 默认 `""` |
| `note` | Text | N | |
| `from_ticker` | String(64) | N | 付出资产标的 |
| `from_amount` | ExactDecimal | Y | 支出数量/金额 |
| `to_ticker` | String(64) | N | 换入资产标的 |
| `to_amount` | ExactDecimal | Y | 收入数量/金额 |
| `commission` | ExactDecimal | Y | 佣金/费用 |
| `commission_asset` | String(64) | N | 费用币种/资产 |
| `payload` | JSON | N | **业务 residual**（014 后非 core 扩展）；非文件溯源 |
| `created_at` | UTCDateTime | N | |

**约束 / 索引**

- `uq_investment_events_workspace_id`：`(workspace_id, id)`
- **唯一（投资当前无逻辑删除）** `uq_investment_events_source_record`：  
  `(workspace_id, source_type, record_id)` **WHERE** `source_type IS NOT NULL AND source_type <> '' AND record_id IS NOT NULL AND record_id <> ''`  
  → **`record_id` × `source_type`**；跨渠道同号不冲突  
  （若未来支持删除语义，改为与现金相同的 partial + 删除标记）
- `ix_investment_events_workspace_date` / `ix_investment_events_workspace_account`
- `ix_investment_events_workspace_source_record`：`(workspace_id, source_type, record_id)`

**015 删除**正式列 `price`：成交单价由资产组成字段（数量和现金金额）及既有 commission 规则派生，不落库，也不回灌 residual `payload`。

**`source_payload` vs `payload`**

| 字段 | 用途 |
|---|---|
| `source_payload` | 导入时解析行快照；幂等/排障/若匹配需原始券商字段 |
| `payload` | 正式事件 residual（非 core 业务扩展），014 语义不变 |

### 5.3 ~~`record_revisions`~~

> **015 已删除**：不持久化改账版本链。

### 5.4 ~~`fact_deletion_events`~~

> **015 已删除**：逻辑删除审计仅在事实行 `deleted_at` / `deleted_by` / `delete_reason`。

### 5.5 `ledger_snapshots`

从正式事实派生的账本投影缓存（**非事实源**）。

| 列 | 类型 | 空 | 说明 |
|---|---|---|---|
| `workspace_id` | String(64) **PK** FK→workspaces | N | CASCADE；每 workspace 一行 |
| `payload` | JSON | N | 以 account_id 为 key 的 bucket |
| `version` | Integer | N | 默认 1 |
| `updated_at` | UTCDateTime | N | |

---

## 6. 交易关系（对账 / 镜像 / 退款 / 转账）

### 6.1 `transaction_relations`

| 列 | 类型 | 空 | 说明 |
|---|---|---|---|
| `id` | **SurrogatePK** **PK** | N | 自增代理主键（016） |
| `workspace_id` | String(64) FK→workspaces | N | CASCADE |
| `kind` | String(32) | N | `payment_mirror` / `transfer_pair` / `refund_offset` |
| `subtype` | String(64) | N | 如 credit_repayment；可空串 |
| `primary_fact_id` | SurrogatePK | N | 按关系角色记录的流水主键 |
| `secondary_fact_id` | SurrogatePK | Y | 对侧流水；待配对关系（`open_leg`）可空 |
| `primary_fact_type` | String(32) | N | 默认 `cash` |
| `secondary_fact_type` | String(32) | Y | |
| `ordered_fact_a` | SurrogatePK | Y | 排序端点 A；待配对关系可为 NULL（016） |
| `ordered_fact_b` | SurrogatePK | Y | 排序端点 B；待配对关系可为 NULL（016 规范化空串→NULL） |
| `active_slot` | String(36) | N | 活跃占位；默认 `active`；superseded 用 id 字符串释放键 |
| `status` | String(32) | N | pending_review / accepted / rejected / superseded |
| `rule_id` | String(128) | N | 匹配规则 |
| `candidate_fact_ids` | JSON | N | 仅待配对关系保存的有序候选账本记录 ID，默认 `[]`；确认、驳回或替换后清空 |
| `created_by` | String(128) | N | 默认 `system` |
| `created_at` | UTCDateTime | N | |
| `decided_by` | String(128) | N | |
| `decided_at` | UTCDateTime | Y | |
| `decision_reason` | Text | N | |
| `superseded_by_id` | SurrogatePK | Y | 被谁替代 |
| `anchor_fact_id` | SurrogatePK | N | 待配对关系或关系角色的锚点流水 |

**关键约束**

- `uq_transaction_relations_workspace_id`
- `uq_transaction_relations_active_business_key`：  
  `(workspace_id, kind, ordered_fact_a, ordered_fact_b, subtype, active_slot)`
- 部分唯一索引 `uq_transaction_relations_open_leg_active`（PG/SQLite partial）：  
  有效待配对关系占位 `(workspace_id, kind, subtype, anchor_fact_id)` where `secondary_fact_id IS NULL AND active_slot='active'`
- Checks：
  - kind / status 枚举
  - accepted 必须 bilateral（`secondary_fact_id NOT NULL`）
  - payment_mirror 必须 bilateral
  - 待配对关系仅允许 refund_offset/transfer_pair，且 status 不能为 accepted

**索引**：status、kind、primary、secondary、anchor

> 注意：`transaction_relations` 的 fact 引用是**逻辑引用**（字符串 fact id），模型层**没有**到 `cash_transactions` / `investment_events` 的外键；匹配引擎在应用层保证 active 语义。

### 6.2 ~~`relation_check_runs`~~

> **015 已删除**：关系检查作业壳不落库；SoT 为 `transaction_relations`。

## 7. Wealth 归因读模型

> 标识多为**确定性内容键**（非随机 UUID），便于幂等重建。金额用 `ExactDecimal` 或 canonical 文本。

### 7.1 `valuation_observations`

估值观测（现金余额、持仓、行情、FX）。

| 列 | 类型 | 空 | 说明 |
|---|---|---|---|
| `observation_id` | String(128) **PK** | N | |
| `workspace_id` | String(64) FK→workspaces | N | CASCADE |
| `identity_kind` | String(32) | N | cash_account / position / instrument_quote / currency_pair / fx |
| `identity` | String(255) | N | 稳定标识；现金为 `{account_id}:{currency}` |
| `owner_account_id` | SurrogatePK | Y | 账户持有类必填；报价/FX 为空。复合 FK→accounts `RESTRICT` |
| `observation_kind` | String(32) | N | boundary_checkin / quantity_checkin / quote / fx 等 |
| `value` | ExactDecimal | N | |
| `currency` | String(3) | N | |
| `unit` | String(32) | N | |
| `as_of` | UTCDateTime | N | 适用时刻 |
| `observed_at` | UTCDateTime | N | 观测/发布时间 |
| `source_identity` | String(255) | N | 观测来源标识（非导入文件表） |
| `source_revision` | String(128) | N | 修正追加新 revision，不原地改 |
| `trust` | String(32) | N | trusted_checkin / trusted_provider 等 |
| `created_at` | UTCDateTime | N | 不参与财务标识 |

**约束**

- `uq_valuation_revision`：`(workspace_id, observation_id, source_revision)`
- `ck_valuation_owner_kind`：owner 与 identity_kind 一致性
- `ck_valuation_cash_owner_identity`：现金 identity 必须以 `owner_account_id:` 为前缀
- `ix_valuation_workspace_identity_asof`

015：**删除**可选列 `raw_record_id`（原弱链到已移除的 `raw_records`）；wealth 主路径不依赖导入 raw 表。

### 7.2 Source Manifest 族

#### `wealth_source_manifests`

| 列 | 类型 | 空 | 说明 |
|---|---|---|---|
| `manifest_id` | String(128) **PK** | N | |
| `workspace_id` | String(64) FK→workspaces | N | CASCADE |
| `source_watermark` | String(128) | N | |
| `canonical_digest` | String(128) | N | |
| `created_at` | UTCDateTime | N | |

- `uq_source_manifest_workspace_id`：`(workspace_id, manifest_id)`

#### `wealth_source_manifest_items`

| 列 | 类型 | 空 | 说明 |
|---|---|---|---|
| `id` | String(128) **PK** | N | |
| `workspace_id` | String(64) | N | |
| `manifest_id` | String(128) | N | FK→manifests（单列 + 复合） |
| `item_kind` | String(32) | N | |
| `item_identity` | String(255) | N | |
| `revision` | String(128) | N | |
| `content_digest` | String(128) | N | |
| `evidence_occurred_at` | UTCDateTime | Y | |
| `evidence_kind` | String(64) | Y | |
| `evidence_contribution` | ExactDecimal | Y | |
| `evidence_scope_fold_identity` | String(255) | Y | |
| `evidence_safe_metadata` | Text | N | 默认 `"{}"` |

- `uq_manifest_item`：`(workspace_id, manifest_id, item_identity, revision)`

### 7.3 Generation / 日结果

#### `wealth_generations`

| 列 | 类型 | 空 | 说明 |
|---|---|---|---|
| `build_revision` | String(128) **PK** | N | |
| `workspace_id` | String(64) FK→workspaces | N | CASCADE |
| `source_watermark` | String(128) | N | |
| `source_manifest_id` | String(128) | N | 复合 FK→source manifests `RESTRICT` |
| `calculation_version` | String(64) | N | |
| `valuation_policy_version` | String(64) | N | |
| `date_from` / `date_to` | String(10) | N | 本地日期 `YYYY-MM-DD` |
| `expected_active_revision` | String(128) | Y | |
| `state` | String(16) | N | 构建状态 |
| `canonical_manifest_digest` | String(128) | N | |
| `created_at` | UTCDateTime | N | |
| `completed_at` | UTCDateTime | Y | |

- `uq_generation_workspace_build`：`(workspace_id, build_revision)`

#### `wealth_daily_results`

| 列 | 类型 | 空 | 说明 |
|---|---|---|---|
| `result_digest` | String(128) **PK** | N | 内容寻址 |
| `workspace_id` | String(64) FK→workspaces | N | CASCADE |
| `local_date` | String(10) | N | |
| `calculation_version` | String(64) | N | |
| `valuation_policy_version` | String(64) | N | |
| `source_revision` | String(128) | N | |
| `result_revision` | String(128) | N | |
| `canonical_payload` | Text | N | 规范化结果正文 |
| `created_at` | UTCDateTime | N | |

- `uq_daily_result_workspace_digest`：`(workspace_id, result_digest)`

#### `wealth_generation_days`

| 列 | 类型 | 空 | 说明 |
|---|---|---|---|
| `id` | String(160) **PK** | N | |
| `workspace_id` | String(64) | N | |
| `build_revision` | String(128) | N | → generations `CASCADE` |
| `local_date` | String(10) | N | |
| `result_digest` | String(128) | Y | → daily_results `RESTRICT` |
| `missing_reason` | String(64) | Y | 缺日原因 |

- `uq_generation_day`：`(workspace_id, build_revision, local_date)`

#### `wealth_active_manifests`

当前激活的 generation（每 workspace 一行）。

| 列 | 类型 | 空 | 说明 |
|---|---|---|---|
| `workspace_id` | String(64) **PK** FK→workspaces | N | CASCADE |
| `build_revision` | String(128) | N | 复合 FK→generations `RESTRICT` |
| `manifest_revision` | Integer | N | |
| `updated_at` | UTCDateTime | N | |

### 7.4 Evidence / Component / Coverage

#### `wealth_evidence_items`

| 列 | 类型 | 空 | 说明 |
|---|---|---|---|
| `evidence_identity` | String(128) **PK** | N | |
| `workspace_id` | String(64) FK→workspaces | N | CASCADE |
| `source_identity` | String(255) | N | |
| `source_revision` | String(128) | N | |
| `occurred_at` | UTCDateTime | N | |
| `evidence_kind` | String(64) | N | |
| `contribution` | ExactDecimal | Y | |
| `safe_metadata` | Text | N | 默认 `"{}"` |

- `uq_evidence_item_workspace_id`：`(workspace_id, evidence_identity)`

#### `wealth_evidence_manifests`

| 列 | 类型 | 空 | 说明 |
|---|---|---|---|
| `manifest_id` | String(128) **PK** | N | |
| `workspace_id` | String(64) FK→workspaces | N | CASCADE |
| `result_revision` | String(128) | N | |
| `ordering_version` | String(32) | N | |
| `canonical_digest` | String(128) | N | |
| `source_manifest_id` | String(128) | Y | → source manifests `RESTRICT` |
| `selection_payload` | Text | N | 默认 `"{}"` |

#### `wealth_evidence_manifest_items`

| 列 | 类型 | 空 | 说明 |
|---|---|---|---|
| `id` | String(160) **PK** | N | |
| `workspace_id` | String(64) | N | |
| `manifest_id` | String(128) | N | → evidence manifests `CASCADE` |
| `evidence_identity` | String(128) | N | → evidence items `RESTRICT` |
| `scope_fold_identity` | String(255) | N | |
| `contribution` | ExactDecimal | Y | |

- `uq_evidence_fold`：`(workspace_id, manifest_id, scope_fold_identity)`

#### `wealth_components`

归因分量（external_cashflow / investment_return / fx_impact …）。

| 列 | 类型 | 空 | 说明 |
|---|---|---|---|
| `component_id` | String(128) **PK** | N | |
| `workspace_id` | String(64) FK→workspaces | N | CASCADE |
| `component_key` | String(128) | N | |
| `result_revision` | String(128) | N | |
| `kind` | String(64) | N | 分量种类 |
| `status` | String(16) | N | complete/stale/partial/unsupported 等 |
| `amount` | ExactDecimal | Y | |
| `evidence_manifest_id` | String(128) | N | → evidence manifests `RESTRICT` |
| `canonical_payload` | Text | N | |

#### `wealth_coverage_dispositions`

某日结果下各 owned identity 的覆盖处置。

| 列 | 类型 | 空 | 说明 |
|---|---|---|---|
| `id` | String(160) **PK** | N | |
| `workspace_id` | String(64) | N | |
| `result_digest` | String(128) | N | → daily_results `CASCADE` |
| `local_date` | String(10) | N | |
| `source_revision` | String(128) | N | |
| `owner_account_id` | SurrogatePK | N | → accounts `RESTRICT` |
| `identity_kind` | String(32) | N | |
| `identity` | String(255) | N | |
| `disposition` | String(32) | N | supported/missing/unsupported/unvalued/not_applicable |

- `uq_coverage_result_owned_identity`：  
  `(workspace_id, result_digest, owner_account_id, identity_kind, identity)`

---

## 8. 表清单速查（ORM 当前）

| 表名 | 职责 |
|---|---|
| `workspaces` | 租户根 |
| `accounts` | 账户 |
| `account_aliases` | 账户别名 |
| `account_lifecycle_events` | 账户生命周期 |
| `cash_transactions` | 现金事实 + 内联溯源 |
| `investment_events` | 投资事实 + 内联溯源 |
| `ledger_snapshots` | 派生快照缓存 |
| `transaction_relations` | 交易关系 |
| `sync_cursors` | 连接器同步游标（018） |
| `valuation_observations` | 估值观测 |
| `wealth_*` | 财富归因读模型族（manifest / generation / daily / component / evidence / coverage） |

**015 已删**：`import_batches`、`raw_files`、`raw_records`、`fact_deletion_events`、`record_revisions`、`relation_check_runs`。  
权威集合以 `models.py` 的 `__tablename__` 为准。

---

## 9. 关键关系（逻辑）

```
workspaces 1──* accounts 1──* account_aliases
                 │         └──* account_lifecycle_events
                 ├──* cash_transactions / investment_events
                 ├──* transaction_relations
                 ├──* sync_cursors
                 ├──1 ledger_snapshots
                 └── wealth 读模型
```

---

## 10. 迁移历史（Alembic，摘要）

| Revision | 内容 |
|---|---|
| `20260717_01` … `20260724_07` | 初始 → 多币种账户 → relations → fact 字段统一 |
| `20260724_08` | 015 内联溯源 + 删 import/raw 作业壳 |
| `20260724_09` | 016 整数代理主键 |
| **`20260726_10`** | **018 `sync_cursors`（当前 head）** |

```bash
export FT_DATABASE_URL='postgresql+psycopg://...'   # 或 sqlite+pysqlite:////path
uv run alembic upgrade head
```

---

## 11. 运行时与方言差异

| 项 | PostgreSQL | SQLite |
|---|---|---|
| 金额 | `NUMERIC(38,18)` | 文本 Decimal |
| 时间 | timestamptz | aware DateTime，读回补 UTC |
| JSON | 原生 JSON | JSON affinity |
| 引擎 | `pool_pre_ping` | WAL、FK、busy_timeout |
| 选择 | `FT_DATABASE_URL` 显式；无回退/双写 | 同左 |
| 建表 | 需 `alembic upgrade head` | 同左 |

---

## 12. 已落地 schema 相关 feature

| Feature | 要点 |
|---|---|
| 015 | 删 import/raw 表；行内溯源与幂等键 |
| 016 | 账本 bigint/integer 代理 PK |
| 017 | 估值服务（应用层；观测表既有） |
| 018 | **`sync_cursors`**；head `20260726_10` |

---

## 13. 索引

| 资源 | 路径 |
|---|---|
| ORM | `src/ft/adapters/relational/models.py` |
| 导入语义 | `docs/import-flow.md` |
| README | `README.md` |
| 015/016/018 specs | `openspec/specs/015-…`、`016-…`、`018-…` |
