## Purpose
- 问：采用哪一档？ → 答：**D2** 完整整数代理主键 + 对外业务键。 本能力的行为契约由迁移后的需求与场景持续维护。

## ADDED Requirements

### Requirement: 内部关联使用紧凑整数主键
系统 MUST 作为维护者，015 之后仍用 UUID 字符串代理主键的正式账本表，改为 **整数代理主键** 作为 PK/FK。 **优先级理由**：D2 核心交付。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 导入与对外标识仍靠 015 业务键
系统 MUST 作为账本所有者，重复导入同一结单仍然 **幂等**；整数 id 可变，**`source_type`×`record_id`** 不变。 **优先级理由**：代理键不得成为幂等或跨库对齐的唯一依据。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 关系引用保持完整
系统 MUST 作为 payment_mirror / transfer_pair / refund_offset 的使用者，主键改写后关系边仍指向正确事实。 **优先级理由**：`transaction_relations` 端点目前是字符串 fact id。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 运维一次升级、无垫片
系统 MUST 作为初期账本运维者，在 **015 head** 上备份后一次升级到 016 head。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 功能需求基线
系统 MUST 保持以下迁移前功能需求；后续行为变更必须通过 OpenSpec change 明确修改。

- - **FR-001**：系统 MUST 对所有 **范围内表** 使用 **整数代理主键**。
- - **FR-002**：范围内表之间的外键 MUST 引用上述整数代理键，不得再引用 UUID 字符串主键。
- - **FR-003**：功能完成后，系统 MUST NOT 保留对范围内表 UUID 主键的 dual-read、dual-write 或回退路径。
- - **FR-004**：导入幂等 MUST 继续以 **015 业务标识** `(workspace_id, source_type, record_id)` 为准（活跃现金 partial unique 等既有规则），MUST NOT 以整数代理键作为幂等键，MUST NOT 依赖已删除的 `raw_records`。
- - **FR-005**：正式事实 MUST 继续具备 015 的 `source_type` 与 `record_id` 列语义；MUST NOT 为「对外标识」再增加平行 `public_id`（或等价）列，除非 Living Spec 明确推翻本条。
- - **FR-006**：事实的公共 list/CSV 契约 MUST 在账单派生场景暴露 `source_type`/`record_id`（及既有正式字段）；整数 PK 默认 **不必** 出现在公共现金 CSV。
- - **FR-007**：`transaction_relations` 端点字段 MUST 在整型化后仍能通过显式 fact_type 避免现金/投资 id 空间碰撞。
- - **FR-008**：今日指向 UUID 账户/事实的 FK（含 `account_aliases.account_id`、关系端点、lifecycle/valuation/wealth 上的 `account_id`/`owner_account_id` 等）MUST 改写为整数代理键；**不得**改写不存在的 `record_revisions` / `fact_deletion_events` / `relation_check_runs`。
- - **FR-009**：一次性 Alembic 迁移 MUST 将既有 UUID 行改写为整数，并原子改写范围内全部 FK（SQLite 必要时同事务重建表）。
- - **FR-010**：迁移遇到无法解析的 FK 目标或歧义映射时 MUST **失败关闭**，禁止静默悬空关系。
- - **FR-011**：PostgreSQL 与 SQLite 双后端矩阵 MUST 证明同一夹具下财务与关系结果等价；代理整数值允许不同。
- - **FR-012**：运行时 schema 校验 MUST 仅接受切换后的 revision。
- - **FR-013**：本 feature 产物 MUST 记录旧 UUID 角色 → 新代理键的映射与非目标；并 cross-link 015 已删表清单。
- - **FR-014**：范围外表若继续使用字符串 PK，MUST 保持完全不动或明确列入后续 feature，禁止半迁。
- - **FR-015**：迁移与实现 MUST 以 **015 目标 schema**（无 import/raw 作业壳）为起点；MUST NOT 假设 `raw_record_id` 或 import 批次表仍存在。
- - **FR-016**：`transaction_relations` 的 `ordered_fact_a` 与 `ordered_fact_b` MUST 保持原有可空合同；015 中的 `NULL` 或空字符串 sentinel MUST 规范化为 `NULL`，仅在其他非空旧值缺少对应 cash/investment 映射时失败关闭。
- - **FR-017**：从空 PostgreSQL 升级至 `20260724_08` 时，财富/生命周期中引用 `accounts.id` 的 FK 列 MUST 使用与该历史 accounts PK 相同的 UUID 字符串类型；不得在 016 切换前提前使用 BIGINT。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：对该能力进行修改时，验证结果 MUST 覆盖迁移后的功能需求清单。
- THEN 系统满足该条件，并保留可复核的验证证据。
