# 功能规格：正式事实结构清理（内联溯源 + 去掉冗余表/列）

**功能分支**：`015-inline-row-provenance`

**创建日期**：2026-07-24

**状态**：Complete

**输入**：015 扩成 **「事实表 + 作业壳全清理」**。去掉导入作业/文件表；账单行溯源内联到正式事实；删除现金 legacy 非权威列与其它作业壳/过度设计表列；**不考虑长期兼容**。幂等权威为 **`record_id` × `source_type`**：`record_id` = 平台流水/展示号或确定性业务行键，`source_type` = **导入渠道名**（如 alipay / wechat / 券商解析 kind）；二者复合，不是只按 `record_id` 全局唯一，也不是另造平行身份列。`ledger_snapshots` **保留**为派生缓存。spec 用中文。目标 schema 见本目录 `database-schema.md`。

**上下文**：Flow-Forward。接续 `010` 行级幂等产品语义与 `006` 关系权威；在 `014` 字段统一之后一次性收紧 schema。与 `016-bigint-surrogate-ids` 正交（PK 类型）。产品初期干净切换，无 dual-read/dual-write/shim。

## 澄清记录

### 会话 2026-07-24

- 问：导入溯源链在文件 digest 不再作去重后还有何用？ → 答：行级业务身份 + 原始 payload 仍被业务使用；希望与行数据放在一起。
- 问：是否仍持久化「导入过什么文件」？ → 答：**不希望**在数据库存储文件作业信息。
- 问：规格语言？ → 答：spec 用中文。
- 问：`offset_*` / `proposed_action` 是否删除？ → 答：**删除**（7 列）。
- 问：015 是否扩成事实表 + 作业壳全清理？ → 答：**是**。
- 问：候选清理项取舍？ → 答：
  1. **`locked`：删除**
  2. **`transfer_account`：删除**
  3. **`source` + `bill_source`：删除正式双列**；渠道身份归入 `source_type`，其余进 `source_payload`/`note`
  4. **`record_id`：保留**；此前「删 record_id、另用 source_identity」是**逻辑错误**——幂等键为 **`record_id` × `source_type`**（`record_id`=平台流水/展示号或确定性行键，`source_type`=导入渠道名）
  5. **软删双写：做**——删除 `fact_deletion_events`，审计留在事实行（`deleted_at` / `deleted_by` / `delete_reason`）
  6. **`record_revisions`：删除**——当前不涉及改账历史版本追溯，属过度设计
  7. **`ledger_snapshots`：保留**——必要派生缓存
  8. **`relation_check_runs`：删除**——检查作业壳，非关系 SoT
- 问：wealth 读模型 11 表？ → 答：本 feature **不动**（非目标）。
- 问：`investment_events.price` 是否保留？ → 答：**删除**。成交价可由 leg 数据推导（例如数量与现金腿金额），不必再占正式列；导入/投影不得再依赖持久化 `price` 列。
- 问：实现后是否迁移开发者本机 `~/.ft` 下的数据库？ → 答：**要**。代码与 Alembic 落地后，MUST **一次性**升级本机 **`~/.ft` 目录下的 SQLite 账本库**（当前为 `~/.ft/finance-tracker.db`，以该路径下实际被 `FT_DATABASE_URL` 使用的 `.db` 为准）；迁移前备份；失败关闭且可从备份恢复；不要求在本 feature 内迁移任意 PostgreSQL 生产实例（除非同机也用且用户显式指向）。

## 关系（Context）

| 关系 | Feature | 说明 |
|---|---|---|
| **Supersedes（部分）** | `010-row-idempotent-import` | 保留：业务行身份 formalize 门禁、重叠只入新行、禁止 digest 短路。**废止**：batch/file 作业表；formal 必须 `raw_record_id`→raw。 |
| Extends | `006-transaction-relations` | 关系权威在 `transaction_relations`；删除行内 offset 空壳与 `relation_check_runs` 作业壳。 |
| Extends | `007` / `009` 等导入 | 解析配方可继续产出 record_id；编排直写 fact。 |
| **Narrows（列）** | `014-fact-field-unify` | 014 曾把 `price` 升为投资正式列；本 feature **删除** `price`，以 legs 为权威可推导量。 |
| Orthogonal | `016-bigint-surrogate-ids` | 不强制同波。 |
| Reference | `docs/database-schema.md` | 变更前基线；目标见本目录 `database-schema.md`。 |

---

## 用户场景与测试 *(必填)*

### 用户故事 1 - 账单行溯源与正式事实同在一行 (优先级: P1)

作为账本所有者，账单派生正式事实在**同一行**保存渠道类型、**业务行键 `record_id`** 与匹配/排障所需的 **`source_payload`**；不再挂独立 raw 表。

**优先级理由**：核心数据模型。

**独立测试**：导入现金与投资后，事实行有 `source_type`、非空业务 `record_id`（账单行）、`source_payload`；无 `raw_records`。

**验收场景**：

1. **给定** 成功现金导入，**当** 查看事实，**则** 有 `source_type`、稳定 **`record_id`**，且 `source_payload` 含 hard-key 等所需原始字段。
2. **给定** 成功投资导入，**当** 查看事实，**则** 有等价的 `source_type`、稳定业务行键（正式列名 **`record_id`**，与现金目录一致）与 `source_payload`（与 residual `payload` 分工清晰）。
3. **给定** 手工事实，**当** 持久化，**则** `source_type` / `record_id` / `source_payload` 可空，不强制伪造身份。

---

### 用户故事 2 - 以 record_id × source_type 行级幂等，无文件作业表 (优先级: P1)

作为用户，重复/重叠导入时仅按 **`record_id` × `source_type`**（再加 workspace；活跃、未删）决定是否新建正式事实：

- **`record_id`**：平台流水号、展示号或确定性业务行键；
- **`source_type`**：**导入渠道名**（账单/券商解析渠道，如 `alipay`、`wechat`、对应券商 kind），不是支付方式文案、也不是文件路径。

同一 `record_id` 在**不同** `source_type` 下是不同身份（可并存两条活跃事实）；同一 `(source_type, record_id)` 不得双记。不因文件 digest/批次跳过 novel 行；库中无导入批次/文件清单。

**优先级理由**：010 产品价值 + 正确身份模型（渠道内流水号才稳定）。

**独立测试**：同夹具导两次 → 新事实 0；A 再 B → 身份并集；跨渠道同号不互相吞掉；无 batch/file 表数据。

**验收场景**：

1. **给定** 已导入 A，**当** 再导未改 A（同渠道），**则** 新事实 0；余额/持仓不变；无批次/文件持久化。
2. **给定** B 与 A 部分 **`(source_type, record_id)`** 重叠，**当** 先 A 后 B，**则** 事实集合 = 该复合身份并集；new = novel 复合身份数。
3. **给定** 任意成功导入，**当** 查库，**则** 无文件路径/digest/batch 状态账本数据。
4. **给定** 平台提供稳定流水号，**当** formalize，**则** 流水号写入 **`record_id`**、导入渠道名写入 **`source_type`**，二者共同参与幂等；**不得**再维护平行 `source_identity` 列，也**不得**只按裸 `record_id` 跨渠道去重。
5. **给定** 渠道甲与渠道乙出现相同字面 `record_id`，**当** 分别导入，**则** 可各有一条活跃事实（身份不同）；不得因「流水号字符串相同」而跳过另一渠道。

---

### 用户故事 3 - 关系匹配读事实行快照 (优先级: P1)

作为对账用户，hard-key 与日期几何从事实 `source_payload`（或已提升列）读取；种子仅为 **`seed_fact_ids`**。

**验收场景**：

1. hard-key 夹具在无 raw 表时仍能提出等价候选。
2. 关系检查不依赖 `seed_batch_id` / `relation_check_runs`。
3. 逻辑删除后再导同一 **`(source_type, record_id)`** → 允许新活跃实例；种子为新 fact id。

---

### 用户故事 4 - 一次切换，无垫片 (优先级: P2)

升级后只认目标模型；删除旧表/旧列；无 dual-write。

**验收场景**：

1. 自 raw 回填 `source_type`/`record_id`/`source_payload` 后删 raw 链。
2. 不存在 `import_batches`/`raw_files`/`raw_records`。
3. 估值等处无 `raw_record_id` 依赖。

---

### 用户故事 5 - 删除现金 legacy 非权威列 (优先级: P1)

删除 `cash_transactions` 上 006 后非权威的 offset/proposed 列，以及无读者/非权威的 **`locked`、`transfer_account`**，以及与 `source_type`+payload 重复的 **`source`、`bill_source`**。

**删除列清单（现金）**：

| 列 | 理由 |
|---|---|
| `offset_group` | 非权威 |
| `offset_role` | 非权威 |
| `offset_strength` | 非权威 |
| `offset_source` | 非权威 |
| `offset_rule_hint` | 非权威 |
| `offset_match_type` | 非权威 |
| `proposed_action` | 非权威 |
| `locked` | 几乎无读路径，假布尔遗留 |
| `transfer_account` | 非关系权威；对端展示用 note/关系 |
| `source` | 与渠道/payload 重复 |
| `bill_source` | 渠道归 `source_type` |

**保留**：`record_id`、`category`、`note`、`counterparty`、金额时间账户、软删行字段、`ledger_snapshots` 表等（见非目标/保留清单）。

**验收场景**：

1. 双后端 schema 无上表删除列。
2. 导入/convert/手工写入不要求、不落库这些列。
3. 关系净消费/排除仍只靠活跃事实 + `transaction_relations` + 正式 `category`。
4. 财富 transfer 分类不读已删列；以 `category` ∈ {`transfer`,`transfer_in`,`transfer_out`} 和/或 accepted `transfer_pair` 为准；同输入用户可见结果一致。
5. 无 dual-write/空列 shim。

---

### 用户故事 6 - 去掉作业壳与过度设计表 (优先级: P1)

作为维护者，schema 不再包含：

| 删除表 | 理由 |
|---|---|
| `import_batches` | 导入作业壳 |
| `raw_files` | 文件档案 |
| `raw_records` | 已内联 |
| `fact_deletion_events` | 与行上 `deleted_*` 双写 |
| `record_revisions` | 尚无改账版本追溯需求 |
| `relation_check_runs` | 关系检查作业壳；SoT 是 `transaction_relations` |

**保留表（本 story 明确）**：`ledger_snapshots`（派生缓存，**必须保留**）；wealth 读模型族不动。

**验收场景**：

1. 升级后上述删除表不存在。
2. `ft fact-delete`（或等价）仍可逻辑删除现金事实，审计字段写在**事实行**上；无强制写入 `fact_deletion_events`。
3. 关系检查可运行并产生/更新 `transaction_relations`，**不**依赖 `relation_check_runs` 持久化。
4. 查询路径仍可 `load`/`save` **`ledger_snapshots`**；行为不因本 feature 删除该缓存。
5. 不存在写入 `record_revisions` 的运行时路径；无「改账前后 JSON」持久化要求。

---

### 用户故事 7 - 投资事件不持久化可推导的 price (优先级: P2)

作为维护者，`investment_events` **不再**持久化 `price` 列。成交单价可由 **leg 数量与金额**（及既有 `commission` 规则，若适用）推导，不应与 legs 双写。

**优先级理由**：减少冗余列与双源不一致。相对 014「price 升列」的窄化。

**独立测试**：schema 无 `price`；buy/sell/swap 等夹具仅靠 legs（及 commission）投影，用户可见持仓/成本与「legs 可完整表达」的输入一致。

**验收场景**：

1. **给定** 升级完成，**当** 检查 `investment_events`，**则** 不存在 `price` 列。
2. **给定** 导入或手工投资事实，**当** 写入，**则** 不要求、不落库 `price`；读回契约无 `price` 正式字段。
3. **给定** 典型 buy/sell（数量在一腿、现金金额在另一腿），**当** 投影持仓与成本，**则** 不读取持久化 `price`；结果与确定性 leg 规则一致。
4. **给定** 功能完成，**当** 审查实现，**则** 无 `price` 列 dual-write，也不得把 `price` 塞进 residual `payload` 充当正式核心字段。

---

### 用户故事 8 - 一次性升级 ~/.ft 下的本机 SQLite 账本 (优先级: P1)

作为本机账本所有者/开发者，在 015 代码与 schema 迁移就绪后，**一次性**把 **`~/.ft` 下正在使用的 SQLite 数据库文件**（默认/当前：`~/.ft/finance-tracker.db`）升级到本 feature 目标结构，使日常 CLI 在指向该库时无需手工拼装迁移步骤即可继续记账。

**优先级理由**：真实数据在 `~/.ft`（约含大量现金/投资事实与 raw 链）；仅绿测试库不等于可用。

**独立测试**：对**备份副本**或受控副本跑完整升级后，schema 符合 SC-005/007/010/011；事实计数与关键投影（余额/持仓抽样）与升级前基线一致（允许丢弃非权威列值）；原文件在成功前有可恢复备份。

**验收场景**：

1. **给定** 升级前存在 `~/.ft/finance-tracker.db`（或 `~/.ft` 下用户通过 `FT_DATABASE_URL` 指向的同一目录 SQLite 文件）且 alembic 版本为升级前 head，**当** 执行本 feature 规定的一次性升级流程，**则** 库升级到 015 目标 schema（无 import/raw/fact_deletion_events/record_revisions/relation_check_runs；现金无 FR-015 列；投资无 `price`；有 `source_type`/`record_id`/`source_payload` 与约定唯一约束）。
2. **给定** 升级开始，**当** 写入目标结构前，**则** 已生成可恢复备份（例如同目录时间戳副本或文档规定的备份路径）；升级失败时原库可从备份恢复，不得留下「半升级且无备份」的唯一副本。
3. **给定** 升级成功，**当** 用同一 `FT_WORKSPACE_ID` 与指向该文件的 `FT_DATABASE_URL` 打开 CLI，**则** 列表/余额/持仓等主路径可用；账单派生事实具备可幂等的 `source_type`×`record_id`（自 raw/旧列回填规则见 FR-012/FR-028）。
4. **给定** 升级过程中数据无法安全映射（例如必填回填冲突），**当** 迁移执行，**则** **失败关闭**、报告可操作错误，并保持可回退到备份；禁止静默丢正式金额/legs。
5. **给定** 功能交付说明，**当** 阅读 quickstart/操作步骤，**则** 写明：目标路径（`~/.ft` 下 db）、备份命令、升级命令（如 alembic upgrade / 项目封装入口）、验证命令与回滚（用备份覆盖）步骤。

---

### 边界与失败场景

- 全重叠：成功，新事实 0。
- 身份已绑其他账户：失败关闭。
- 导入失败：整次不留部分事实。
- 无稳定平台 id：用既有确定性内容键写入 **`record_id`**，并仍带正确 **`source_type`（导入渠道名）**（规则在 plan/importer 固定）；冲突 fail-closed。
- 并发同 **`(source_type, record_id)`**：至多一条活跃事实。
- PG/SQLite：用户可见事实集合与余额/持仓等价。
- **`~/.ft` 库升级**：仅覆盖用户本机 `~/.ft` 下 SQLite 账本文件；不自动扫描全盘；不修改 `mapping.yaml`/账单文件本身。
- 禁止：digest 门禁；文件路径作身份；恢复 batch/raw/check_run/revision 表；把已删 offset 塞进 payload 当关系权威；删除 `ledger_snapshots`；在无备份情况下就地破坏性改写 `~/.ft` 唯一 db。

---

## 需求 *(必填)*

### 功能需求

#### 溯源与幂等

- **FR-001**：系统 MUST 仅以工作区内 **`record_id` × `source_type`** 判断账单行是否创建新的活跃正式事实（现金；投资等价）。复合身份定义为：
  - **`record_id`**：正式业务行键（平台流水/展示号或确定性内容键）；
  - **`source_type`**：**导入渠道名**（importer/账单渠道标识，稳定短名；不是支付方式自由文案、不是文件路径）。
  MUST NOT 只按裸 `record_id` 跨渠道去重；MUST NOT 再引入与之平行的 `source_identity` 列。
- **FR-002**：系统 MUST NOT 因文件 digest、路径或批次状态跳过 novel 行 formalize；MUST NOT 持久化文件路径/digest/导入批次。
- **FR-003**：账单派生正式事实 MUST 同行保存：`source_type`（导入渠道名）、`record_id`、`source_payload`（JSON 快照）。手工事实三者可空。
- **FR-004**：系统 MUST 移除 `import_batches`、`raw_files`、`raw_records` 及一切 `raw_record_id` 列/FK。
- **FR-005**：现金：当 `record_id` 与 `source_type` 均非空且 `deleted_at` 为空时，`(workspace_id, source_type, record_id)` 至多一条活跃事实。删后再导同一复合身份允许新活跃行。不同 `source_type` 的相同 `record_id` 字面值 MUST 视为不同身份。
- **FR-006**：投资：对非空 `(source_type, record_id)` 提供等价不双记保证（无软删则全量唯一；有软删则 partial 对齐现金）。
- **FR-007**：导入编排：解析 → 得 **`source_type`（导入渠道名）与 `record_id`** → 该复合身份已存在活跃则跳过 → 否则写入事实并更新投影；失败原子回滚。
- **FR-008**：导入后关系检查 MUST 仅 `seed_fact_ids`；MUST NOT 依赖 batch id 或 `relation_check_runs`。
- **FR-009**：关系匹配 MUST 能从 `source_payload`（或已提升列）获得原 raw payload 所需字段，能力不回退。
- **FR-010**：现金与投资导入入口一致遵守 FR-001～FR-009。
- **FR-011**：PostgreSQL 与 SQLite 用户可见等价；允许代理键/时间戳字面差异。
- **FR-012**：迁移一次性回填 source 字段并删除旧表/列；无长期 dual-write。
- **FR-013**：估值等实体清除 `raw_record_id` 依赖，不阻断 wealth 主路径。
- **FR-014**：提供本目录目标 `database-schema.md`。

#### 现金列清理

- **FR-015**：MUST 物理删除现金列：`offset_group`、`offset_role`、`offset_strength`、`offset_source`、`offset_rule_hint`、`offset_match_type`、`proposed_action`、`locked`、`transfer_account`、`source`、`bill_source`。
- **FR-016**：MUST NOT 向正式事实写入 FR-015 字段；MUST NOT 以空串保留列。
- **FR-017**：关系与净额 MUST 仅依赖活跃事实 + `transaction_relations` + 正式 `category` 等；不得把 FR-015 键写入 `source_payload` 充当关系权威。
- **FR-018**：财富/事件分类 MUST NOT 读 FR-015 字段；transfer 判定基于 `category`（`transfer`/`transfer_in`/`transfer_out`）和/或 accepted `transfer_pair`；同正式输入下用户可见结果与删列意图一致（不静默改账）。
- **FR-019**：MUST 保留正式列 **`record_id`** 与 **`source_type`** 作为幂等复合键：`record_id` × `source_type`（导入渠道名）。`source_type` 承接原导入/bill 渠道语义（稳定渠道名），**不是**支付方式文案列；不得保留并行的 `bill_source`/`source` 正式列名。

#### 表清理与软删

- **FR-020**：MUST 删除表 `fact_deletion_events`。逻辑删除审计 MUST 仅使用事实行 `deleted_at`、`deleted_by`、`delete_reason`（现金）。
- **FR-021**：MUST 删除表 `record_revisions`。系统 MUST NOT 持久化「改账前后快照」修订链。若事实行上 `revision` 仅服务未实现的改账版本语义，MUST 删除该列；wealth 源水印 MUST 改为不依赖递增 revision（例如事实 id + 内容摘要，plan 定）。
- **FR-022**：MUST 删除表 `relation_check_runs`；关系检查运行时状态可不持久化或仅日志/CLI 输出。
- **FR-023**：MUST **保留**表 `ledger_snapshots` 及其 load/save 缓存职责（派生、非 SoT，但是产品需要的缓存）。
- **FR-024**：本 feature MUST NOT 删除或重做 wealth 读模型表族（`wealth_*`、`valuation_observations` 结构以不动为默认，仅去掉 `raw_record_id` 依赖）。

#### 投资列清理

- **FR-025**：系统 MUST 从 `investment_events` **物理删除** `price` 列（schema、模型、仓库读写、正式/导出契约、目标 schema 文档同步）。
- **FR-026**：系统 MUST NOT 向正式投资事实写入 `price`；MUST NOT 以空值/零值默认保留该列；MUST NOT 将 `price` 作为 residual `payload` 的核心键长期存放。
- **FR-027**：投资投影、成本与持仓计算 MUST 以 **`from_ticker`/`from_amount`/`to_ticker`/`to_amount`**（及 `commission`/`commission_asset` 等仍保留的正式列）为权威输入；需要单价展示或中间量时 MUST **派生**，不得依赖已删列。在相同 leg 输入下，用户可见持仓/成本结果 MUST 与删列意图一致（不静默改账）。

#### 本机 ~/.ft 账本一次性升级

- **FR-028**：本 feature 交付 MUST 包含对开发者/用户本机 **`~/.ft` 目录下 SQLite 账本库**的**一次性升级**任务与可执行步骤。默认目标文件为 **`~/.ft/finance-tracker.db`**；若用户日常 `FT_DATABASE_URL` 指向 `~/.ft` 下其它 `.db` 文件，升级步骤 MUST 允许显式指定该路径。升级内容即本 spec 的 schema/数据迁移（FR-012 及列/表删除），不是第二套脚本语义。
- **FR-029**：执行 `~/.ft` 库升级前 MUST 创建可恢复备份；升级失败 MUST 失败关闭且不得在无备份时留下不可恢复的唯一损坏副本。
- **FR-030**：`~/.ft` 库升级成功后，使用该文件的 CLI 主路径（账户/事实列表、导入幂等、投影抽样）MUST 可用；活跃事实计数与升级前相比 MUST 不因迁移丢失正式事实行（非权威列值可丢弃；`price` 可按 FR-025 丢弃列而保留 legs）。
- **FR-031**：操作文档（本 feature `quickstart.md` 或等价交付说明）MUST 写明备份、升级、验证、从备份回滚的准确命令；MUST NOT 假定调用方记得未写入文档的对话步骤。

---

### 关键实体

| 实体 | 定义 |
|---|---|
| **业务行身份** | **`record_id` × `source_type`**（导入渠道名 × 行键）；决定是否已入账。 |
| **正式事实** | `cash_transactions` / `investment_events`；SoT。 |
| **行级原始快照** | `source_payload`；匹配/排障；非文件档案。 |
| **分类** | 现金 `category`；投资 `action`。 |
| **关系** | `transaction_relations`；核销/转账权威。 |
| **账本快照缓存** | `ledger_snapshots`；派生缓存，**保留**。 |
| **已删除概念** | 导入批次/文件/独立 raw；行内 offset/locked/transfer_account/source/bill_source；删除事件表；修订表；关系检查 run 表；投资 `price` 列。 |
| **本机 SQLite 账本** | 默认 `~/.ft/finance-tracker.db`（`~/.ft` 下由 `FT_DATABASE_URL` 指向的文件型库）；本 feature 结束时须一次性迁到目标 schema。 |

---

## 成功标准 *(必填)*

- **SC-001**：同现金夹具连导两次 → 第二次新事实 0；无 batch/file 表。
- **SC-002**：投资 A 再重叠 B → 事件数 = \|`(source_type, record_id)` 并集\|；双后端一致。
- **SC-003**：用户可重叠导出只入新活动（010 目标，无文件作业表）。
- **SC-004**：双后端：身份集合与余额/持仓 100% 用户可见一致。
- **SC-005**：schema：无 import/raw 三表；事实无 `raw_record_id`；有 `source_type`/`record_id`/`source_payload` 及约定唯一约束。
- **SC-006**：无 digest 整单短路；无写文件 digest/路径入账本库。
- **SC-007**：现金无 FR-015 所列列；导入读回无这些键。
- **SC-008**：同 category+关系状态下财富分类与金额用户可见一致；不引用已删列。
- **SC-009**：目标 schema 与运行时一致：已删表/列清单正确；**`ledger_snapshots` 仍在**；**`record_id` 仍在**。
- **SC-010**：无 `fact_deletion_events`、`record_revisions`、`relation_check_runs`；逻辑删除与关系检查主路径仍可用。
- **SC-011**：升级后 `investment_events` 在双后端均无 `price` 列；buy/sell 类夹具仅依赖 legs（及 commission）投影，用户可见持仓/成本与「可从 legs 完整表达的输入」一致。
- **SC-012**：对 `~/.ft` 目标 SQLite 文件完成一次性升级后：schema 满足 SC-005/007/010/011；存在升级前备份；用该 URL 打开后主路径可用；正式事实行数不因迁移丢失（相对升级前计数）。

---

## 假设

- 现金 convert 今日的 `record_id`（平台 txn 等）与内容哈希回退规则，加上导入时的渠道名作为 `source_type`，迁移为 formal 复合幂等键 **`record_id` × `source_type`**；投资 importer 的业务行键**写入同名正式列 `record_id`**，渠道写入 `source_type`（不再叫 `source_identity`）。
- `source_payload` 覆盖 relations 所需键；可瘦身不得导致匹配回退。
- 投资 residual `payload`（014）与 `source_payload`：后者=导入快照，前者=非 core 业务扩展。
- 016 可后做；默认 UUID PK 上先落地 015。
- 历史 offset/locked/transfer_account/source/bill_source 列值可丢弃。
- 无改账产品：不需要 revision 链；wealth 水印改造在 plan/tasks 中安排测试。
- **`price` 可丢弃**：历史或导入中的单价若能由 legs 还原则迁移时不保留列；importer 在 formalize 前把数量与现金金额写入 legs。若某源只有单价+数量而缺现金腿，应用层在写入前算出现金腿，**仍不**落 `price` 列。
- **本机库**：当前开发者环境已存在 `~/.ft/finance-tracker.db`（含 cash/investment/raw/batch 等）；升级前 alembic 版本为既有 head（如 `20260724_07`）。`~/.ft` 内 mapping/bills/yaml **不是**本迁移对象。PostgreSQL 测试库（如 docker 契约库）仍按测试矩阵升级；**交付门禁额外要求**完成 `~/.ft` SQLite 实库升级或提供已执行证据。

---

## 非目标

- 不改金额精度、币种规则、多币种账户模型。
- 不重做 relations 匹配算法（只改数据来源、种子、去掉作业壳）。
- 不提供按历史文件整批回滚。
- 不做文件归档/对象存储。
- 不完成 016 bigint（除非显式合并）。
- 不重做 wealth 归因公式；不删 wealth 读模型表族。
- **不删除 `ledger_snapshots`**。
- **不删除 `record_id`**，不另建平行 `source_identity`。
- 不自动修复历史双记。
- 不借删 `price` 之机重做成本会计政策（平均成本等规则不变，仅输入改为 legs）。
- 不自动迁移用户家目录以外的任意 SQLite/PostgreSQL 实例；不迁移 `~/.ft` 下非数据库文件（账单、mapping、凭证等）。

---

## 依赖

- Constitution：幂等、不静默双记、双后端等价、Decimal。
- `010` 幂等产品语义（存储形态 supersede）。
- `006` 关系权威。
- `002` 双后端。
- 基线 `docs/database-schema.md`；目标本目录 `database-schema.md`。

---

## 规格产物

| 文件 | 用途 |
|---|---|
| `spec.md` | 本文件 |
| `database-schema.md` | 完成后完整目标结构 |
| `checklists/requirements.md` | 质量清单 |

（`plan.md` / `tasks.md` 由后续 `$speckit-plan` / `$speckit-tasks` 产生。）
