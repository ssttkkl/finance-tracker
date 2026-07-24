# 功能规格：整数代理主键（Bigint Surrogate IDs）

**功能分支**：`016-bigint-surrogate-ids`

**创建日期**：2026-07-24

**状态**：Complete（PR #13 已合入 `refactor/web`；015 后重评范围已落实）

**输入**：采用 D2——**内部代理主键用整数**，**对外/导入保留稳定业务键**；产品初期 **一次性切换、不留兼容逻辑**。

**上下文**：Flow-Forward。**前置：`015-inline-row-provenance` 已落地**（Alembic head 基线 `20260724_08`）。015 已删除导入作业/raw 表与多项作业壳表，并把行级幂等固定为事实行上的 **`record_id` × `source_type`**。本 feature **只改「内部代理键类型与分层」**，不重做 014 字段语义、不重做 015 溯源/幂等形态，也不恢复任何 015 已删表。

## 澄清记录

### 会话 2026-07-24

- 问：采用哪一档？ → 答：**D2** 完整整数代理主键 + 对外业务键。
- 问：是否保留兼容？ → 答：**不留** dual-read/dual-write、UUID 主键回退或长期 uuid↔bigint 映射表。
- 问：规格语言？ → 答：**spec 用中文**。
- 问：015 完成后如何重评 016？ → 答：范围从表清单中**剔除 015 已删对象**；幂等/对外身份以 015 的 `record_id`×`source_type` 为准，**不再**引入平行 `public_id` 列，也**不再**依赖 `raw_records`。

### 会话 2026-07-25

- 问：016 遇到开放单腿关系的空有序端点时如何迁移？ → 答：将 015 的 `NULL` 或空字符串 sentinel 规范化为 `NULL`；只有其他非空值无法映射到对应正式事实时才失败关闭。
- 问：PostgreSQL 从空库升级到 015 基线时，财富 owner FK 与 UUID 账户键不兼容怎么办？ → 答：修复历史迁移的建表类型，使 015 基线在 PG 与 SQLite 都保持 owner FK 类型与当时 `accounts.id` 一致；016 再统一改为整数。

## 关系（Context）

| 关系 | Feature | 说明 |
|---|---|---|
| **Depends on / After** | `015-inline-row-provenance` | 基线 schema：无 import/raw/revision/check_run/deletion 表；事实内联溯源。 |
| Extends | `014-fact-field-unify` | note/action/legs 等列名与形态保持；本 feature 只改 id 类型。 |
| Orthogonal | 财富公式 / 关系匹配算法 | 仅机械改写端点 id 类型。 |

## 015 之后的基线事实（重评前提）

### 已不存在（016 不得再纳入范围）

下列对象在 015 中已删除，**不得**作为 016 的迁入目标或兼容目标：

- 表：`import_batches`、`raw_files`、`raw_records`、`record_revisions`、`fact_deletion_events`、`relation_check_runs`
- 列：事实 `raw_record_id`、`revision`；现金 `source`/`bill_source`/`transfer_account`/`locked`/`offset_*`/`proposed_action`；投资 `price`

### 仍使用 UUID 字符串代理主键（016 默认纳入）

| 表 | 当前 PK | 说明 |
|---|---|---|
| `accounts` | `id` String(36) | 账户 |
| `cash_transactions` | `id` String(36) | 现金正式事实 |
| `investment_events` | `id` String(36) | 投资正式事实 |
| `transaction_relations` | `id` String(36) | 关系边；端点 fact id 字符串 |
| `account_aliases` | `id` String(36) | 账户别名 |

### 默认不纳入（字符串业务/确定性身份）

| 表 / 键 | 原因 |
|---|---|
| `workspaces.id` | 工作区 slug（`FT_WORKSPACE_ID`） |
| `ledger_snapshots.workspace_id` | 每 workspace 一行缓存 PK |
| `account_lifecycle_events.event_id` | 确定性字符串身份 |
| `valuation_observations.observation_id` 及 wealth 读模型 digest/manifest 字符串 PK | 确定性身份；**仅**把指向 `accounts.id` 的 UUID 形 FK 改为整数（若有） |
| 事实 **`record_id` + `source_type`** | **业务幂等键**（015）；不是代理主键，**不得**改成整数 |

### 对外与幂等（015 已定，016 必须遵守）

- 导入幂等：**仅**活跃事实 `(workspace_id, source_type, record_id)`。
- 对外/跨库对齐：优先用 **`source_type` + `record_id`**（账单派生）与账户 **name**（工作区内唯一）；整数 PK 仅内部。
- **不**新增 `public_id` 列：015 后 `record_id` 已承担「稳定行业务键」职责；手工行 `record_id` 可空（不参与幂等），需要对外引用时可在 plan 中定义「手工行可选生成不透明 `record_id`」，但仍是业务键语义而非第二套 PK。

---

## 用户场景与测试 *(必填)*

### 用户故事 1 - 内部关联使用紧凑整数主键 (优先级: P1)

作为维护者，015 之后仍用 UUID 字符串代理主键的正式账本表，改为 **整数代理主键** 作为 PK/FK。

**优先级理由**：D2 核心交付。

**独立测试**：升级后 schema 范围内表为整数 PK；FK 为整数；无 UUID 主键列。

**验收场景**：

1. **给定** 已迁移的工作区，**当** 检查 `accounts`、`cash_transactions`、`investment_events`、`transaction_relations`、`account_aliases`，**则** 主键为整数类型（PostgreSQL BIGINT / SQLite INTEGER 亲和），引用方 FK 同为整数。
2. **给定** 切换完成，**当** 查看产品写入路径，**则** 不再为上述表的主键分配 UUID；由数据库（或方言等价自增/IDENTITY）分配代理键。
3. **给定** 功能完成，**当** 检索代码路径，**则** 不存在仍接受旧 UUID 主键作为范围内表 PK 的运行时分支。
4. **给定** 015 已删表名，**当** 检查 016 范围与迁移，**则** 不出现对这些表的重建或「为 bigint 迁回」逻辑。

---

### 用户故事 2 - 导入与对外身份仍靠 015 业务键 (优先级: P1)

作为账本所有者，重复导入同一结单仍然 **幂等**；整数 id 可变，**`source_type`×`record_id`** 不变。

**优先级理由**：代理键不得成为幂等或跨库对齐的唯一依据。

**独立测试**：同一夹具导入两次 → 正式事实行数不变；`source_type`/`record_id` 不变；不同空库整数 id 可不同。

**验收场景**：

1. **给定** 一次成功的结单导入，**当** 以相同来源再次导入，**则** 不产生重复正式事实（partial unique / 015 规则继续生效）。
2. **给定** 正式现金/投资事实，**当** 对外列出，**则** 账单派生行暴露 `source_type` 与 `record_id`；整数 PK 默认不必出现在公共现金 CSV。
3. **给定** 按业务键对齐到新空库，**当** 比较投影，**则** 余额/持仓一致；整数 PK 不必与旧库相同。

---

### 用户故事 3 - 关系引用保持完整 (优先级: P1)

作为 payment_mirror / transfer_pair / refund_offset 的使用者，主键改写后关系边仍指向正确事实。

**优先级理由**：`transaction_relations` 端点目前是字符串 fact id。

**独立测试**：先造事实与关系 → 迁移 → 端点仍对应同一业务事实；关系感知投影不变。

**验收场景**：

1. **给定** 升级前的双边与开放单腿关系，**当** 升级完成，**则** primary/secondary/anchor 仍标识同一逻辑事实（整数 FK）。
2. **给定** 现金与投资分表分序列，**当** 关系存储端点时，**则** 仍有显式 `primary_fact_type` / `secondary_fact_type`（或今日等价），禁止静默跨表撞号。
3. **给定** 软删现金事实（行上 `deleted_*`，无独立删除事件表），**当** 升级后，**则** 删除标记与默认列表排除行为正确。
4. **给定** `refund_offset`、`transfer_pair` 或其他开放单腿关系的 `ordered_fact_a` 或 `ordered_fact_b` 为 `NULL`，**当** 升级完成，**则** 该端点仍为 `NULL`；非空端点仍映射到同一业务事实。

---

### 用户故事 4 - 运维一次升级、无垫片 (优先级: P2)

作为初期账本运维者，在 **015 head** 上备份后一次升级到 016 head。

**独立测试**：从 `20260724_08`（或其后 015 等价 head）升到 016 head；`SCHEMA_REVISION` 一致；失败关闭。

**验收场景**：

1. **给定** 015 完成后的数据库，**当** `alembic upgrade head` 成功，**则** schema revision 为新 head，抽样读写可用。
2. **给定** 升级中途失败，**当** 观察库状态，**则** 不得提交「部分表 UUID、部分表 bigint」的完成态。
3. **给定** 切换完成，**当** 检索范围内模型，**则** 已清除主键 `default=_uuid`（或等价）。
4. **给定** 本机 `~/.ft` SQLite（若用户继续使用），**当** 交付，**则** 提供与 015 类似的备份→升级→验证步骤（可选交付门禁，plan/tasks 写明）。

---

### 边界与失败

- 空表/全新安装：直接以 head 建出整数 PK。
- 手工事实：`record_id`/`source_type` 可空；仍得整数 PK。
- 关系边异构事实引用：必须保留类型标签。
- PostgreSQL 与 SQLite 自增表示可不同；**账务结果不得分叉**。
- 财富确定性字符串 PK 默认不改；仅 account 等 UUID FK 改为整数。
- `workspaces.id` 默认仍为 slug 字符串。
- 开放单腿关系允许 `ordered_fact_a` 和/或 `ordered_fact_b` 为空；015 历史库可用 `NULL` 或空字符串 sentinel 表示，迁移必须规范化为 `NULL`，并与非空旧 UUID 无法映射的断链区分。
- **禁止**为配合 bigint 恢复 015 已删的 import/raw/revision 表。

---

## 需求 *(必填)*

### 功能需求

- **FR-001**：系统 MUST 对所有 **范围内表** 使用 **整数代理主键**。
- **FR-002**：范围内表之间的外键 MUST 引用上述整数代理键，不得再引用 UUID 字符串主键。
- **FR-003**：功能完成后，系统 MUST NOT 保留对范围内表 UUID 主键的 dual-read、dual-write 或回退路径。
- **FR-004**：导入幂等 MUST 继续以 **015 业务身份** `(workspace_id, source_type, record_id)` 为准（活跃现金 partial unique 等既有规则），MUST NOT 以整数代理键作为幂等键，MUST NOT 依赖已删除的 `raw_records`。
- **FR-005**：正式事实 MUST 继续具备 015 的 `source_type` 与 `record_id` 列语义；MUST NOT 为「对外身份」再增加平行 `public_id`（或等价）列，除非 Living Spec 明确推翻本条。
- **FR-006**：事实的公共 list/CSV 契约 MUST 在账单派生场景暴露 `source_type`/`record_id`（及既有正式字段）；整数 PK 默认 **不必** 出现在公共现金 CSV。
- **FR-007**：`transaction_relations` 端点字段 MUST 在整型化后仍能通过显式 fact_type 避免现金/投资 id 空间碰撞。
- **FR-008**：今日指向 UUID 账户/事实的 FK（含 `account_aliases.account_id`、关系端点、lifecycle/valuation/wealth 上的 `account_id`/`owner_account_id` 等）MUST 改写为整数代理键；**不得**改写不存在的 `record_revisions` / `fact_deletion_events` / `relation_check_runs`。
- **FR-009**：一次性 Alembic 迁移 MUST 将既有 UUID 行改写为整数，并原子改写范围内全部 FK（SQLite 必要时同事务重建表）。
- **FR-010**：迁移遇到无法解析的 FK 目标或歧义映射时 MUST **失败关闭**，禁止静默悬空关系。
- **FR-011**：PostgreSQL 与 SQLite 双后端矩阵 MUST 证明同一夹具下财务与关系结果等价；代理整数值允许不同。
- **FR-012**：运行时 schema 校验 MUST 仅接受切换后的 revision。
- **FR-013**：本 feature 产物 MUST 记录旧 UUID 角色 → 新代理键的映射与非目标；并 cross-link 015 已删表清单。
- **FR-014**：范围外表若继续使用字符串 PK，MUST 保持完全不动或明确列入后续 feature，禁止半迁。
- **FR-015**：迁移与实现 MUST 以 **015 目标 schema**（无 import/raw 作业壳）为起点；MUST NOT 假设 `raw_record_id` 或 import 批次表仍存在。
- **FR-016**：`transaction_relations` 的 `ordered_fact_a` 与 `ordered_fact_b` MUST 保持原有可空合同；015 中的 `NULL` 或空字符串 sentinel MUST 规范化为 `NULL`，仅在其他非空旧值缺少对应 cash/investment 映射时失败关闭。
- **FR-017**：从空 PostgreSQL 升级至 `20260724_08` 时，财富/生命周期中引用 `accounts.id` 的 FK 列 MUST 使用与该历史 accounts PK 相同的 UUID 字符串类型；不得在 016 切换前提前使用 BIGINT。

### 范围 — 默认纳入（In-Scope）

须将 UUID/字符串 **代理主键**（及指向它们的 UUID 形 FK）改为整数代理：

- `accounts`
- `cash_transactions`
- `investment_events`
- `transaction_relations`
- `account_aliases`

以及上述表之间、与财富/生命周期表之间 **指向这些 UUID 的 FK 列**（例如 `owner_account_id`、`account_id`、关系 fact 端点）。

### 范围 — 默认不纳入（Out-of-Scope）

- `workspaces.id`（工作区 slug）
- `ledger_snapshots`（workspace_id PK 缓存行）
- 财富确定性身份表的字符串 PK（`observation_id`、digest/manifest 等）
- **已由 015 删除的表**（见上「已不存在」清单）——禁止迁回
- 事实业务键 `source_type` / `record_id` 的语义变更
- 合并现金/投资为单物理表
- 修改关系 kind 业务规则或财富公式（除机械改 id 外）
- 切换成功后长期保留 uuid↔bigint 映射产品表
- 新增 `public_id` 列

### 关键实体

- **代理主键（Surrogate Id）**：库分配的整数主键，仅内部使用。
- **业务行身份**：015 的 `(source_type, record_id)`；决定是否已入账。
- **账户 / 现金正式事实 / 投资正式事实 / 交易关系 / 账户别名**：角色同 015 后，键类型重接。
- **迁移期 Id 映射**：仅存在于 Alembic 修订过程内；成功后 **不是** 运行时产品表。

### 非目标

- UUID 主键兼容垫片
- 恢复 015 已删作业/raw/修订表
- 用 bigint 充当安全或租户边界
- 跨后端复制/双写
- 面向用户的「漂亮连续发票号」产品（可另开序列 feature）
- 另起 `public_id` 与 `record_id` 双业务键

---

## 成功标准 *(必填)*

### 可度量结果

- **SC-001**：迁移后范围内表 100% 使用整数 PK；这些表上 UUID 主键列消失。
- **SC-002**：多源金样夹具：迁移前后投影现金余额与投资持仓/成本一致（金额与股数零 diff）。
- **SC-003**：金样夹具上关系 kind×status 的业务成员集合一致（按 `source_type`×`record_id` 或稳定业务对齐端点）。
- **SC-004**：已导入来源再次导入不增加正式事实行数（015 幂等）。
- **SC-005**：PG 与 SQLite 矩阵财务合计与事实行数一致（id 数值可不同）。
- **SC-006**：运行时连接旧 revision 失败关闭；新 revision 成功。
- **SC-007**：代码门禁：范围内模型不再出现主键 `default=_uuid`（或等价物）。
- **SC-008**：schema 中仍不存在 015 已删作业/raw 表。

---

## 假设

- 初期允许备份后 **一次性破坏性切换**；不做多版本双栈。
- **每表全局整数序列**（A3）：单列整数 PK 自增/IDENTITY；`workspace_id` 继续做隔离。
- 现金与投资 **分表分序列**（B1）；关系保留 fact_type 消歧。
- 历史行迁移时，旧 UUID 字符串 **不必** 写入新列；业务对齐靠 `source_type`/`record_id` 与账户名。若运维需要「旧 UUID 可查」，可在迁移日志/一次性旁路中记录，**不是**产品表。
- 财富 digest 类字符串 PK 默认保留。
- 实施顺序：**必须**在 015 之后；当前活跃 feature 指针在进入 016 实施前改为本目录。

---

## 依赖

- **`015-inline-row-provenance`（Complete）** 的目标 schema 与幂等语义
- `014-fact-field-unify` 字段模型（note/action/legs 等）
- 双库运行时与 Alembic 链（head 自 `20260724_08` 起）
- 关系与财富消费者中的账户/事实 id

---

## 键角色对照（015 后 → 016 后）

| 概念 | 015 完成后（当前） | 016 完成后 |
|---|---|---|
| 现金行 PK | UUID 字符串 `id` | 整数 `id` |
| 投资行 PK | UUID 字符串 `id` | 整数 `id` |
| 业务幂等键 | `source_type` × `record_id` | **不变** |
| 账户 PK | UUID 字符串 | 整数 `id` |
| 关系 PK | UUID 字符串 | 整数 `id` |
| 关系端点 | UUID 事实 id | 整数事实 id + fact_type |
| 账户别名 PK / account FK | UUID | 整数 |
| 导入批次 / raw / 修订 / 删除事件 / check_run | **已删除** | **仍不存在** |
| 工作区 id | 字符串 slug | 默认不变 |

---

## 规格语言

本 feature 的 `spec.md` 以 **中文** 为正文语言（专有名词、表名、列名、枚举值可保留英文代码标识）。见 `.specify/memory/constitution.md` 工程约束。
