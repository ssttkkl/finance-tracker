# Feature Specification: Multi-Currency Accounts

**Feature Branch**: `multi-currency-accounts`

**Created**: 2026-07-20

**Status**: Complete

**Input**: User description: "目前的账户体系规定一个账户只能持有一个币种；物理世界一张卡有多币种会被建模成多个账户。修改建模为一个账户可持有多个币种，为后续换汇、跨币种转账做支持。不保留兼容逻辑和数据迁移代码，实现完成后一次性迁移现有数据。"

## Clarifications

### Session 2026-07-20

- Q: 一次性迁移同名同 type 多行时 survivor 如何选？ → A: 最早 `created_at`，再比最低 `id`。
- Q: 同账户不同币种 transfer 是否允许？ → A: 允许，作为通用转账路径；不定义 FX 产品语义。
- Q: 财富现金估值 identity 如何区分多币种？ → A: `cash_account` identity 绑定 account+currency（owner 仍为 account_id）。
- Q: 投资账户去掉 currency 标识后报价币种从哪来？ → A: snapshot/事件/metadata.base_currencies；不重做投资产品。
- Q: 用户要求一口气推进且不交互确认 → A: 上述默认写入 artifacts，跳过交互式 clarify 轮询。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 一账户持有多币种余额 (Priority: P1)

作为多币种记账用户，我把“工行借记卡”建成**一个账户**，可以同时持有 CNY、JPY 等币种余额；列表与报告按该账户展示各币种余额，而不是要求我维护“工行 CNY”“工行 JPY”两个账户行。

**Why this priority**: 这是本 feature 的核心建模变更；没有它，后续换汇/跨币种转账仍会卡在“分册账户”标识上。

**Independent Test**: 仅创建账户 `工行`（cash），分别写入 CNY 与 JPY 事实/校准后，账户列表对该账户展示两个币种余额；workspace 内账户名唯一。PostgreSQL 与 SQLite 结果等价。

**Acceptance Scenarios**:

1. **Given** workspace 中尚无 `工行` 账户，**When** 创建 cash 账户 `工行`（可不绑定唯一币种属性），**Then** 账户创建成功且 workspace 内名称唯一。
2. **Given** 已存在 cash 账户 `工行`，**When** 分别对 `工行` 做 CNY 与 JPY 的手工记账或余额校准，**Then** 两个币种余额均挂在同一账户下，互不覆盖。
3. **Given** 账户 `工行` 已有 CNY/JPY 余额，**When** 查看账户列表或财务报告，**Then** 能看到该账户下各币种余额集合（或等价的一账户多行展示），而不是两个同名账户实体。
4. **Given** 已存在账户 `工行`，**When** 再次创建同名账户，**Then** 创建失败并给出可操作错误，不产生第二个同名账户。

---

### User Story 2 - 手工记账与校准按账户+操作币种 (Priority: P1)

作为用户，我对多币种账户做 `add` / `checkin` 时，**必须显式指定操作币种**（无账户主币种默认）；系统把金额写入该账户对应币种余额，不再用“账户自带唯一币种”消歧。

**Why this priority**: 去主币种后，操作币种成为分币种余额键；缺省猜测会导致错账。

**Independent Test**: 对同一账户执行不同币种的 add/checkin；缺 `--currency` 失败；非法币种失败；双后端一致。

**Acceptance Scenarios**:

1. **Given** 账户 `工行` 存在，**When** 执行带显式币种 CNY 的支出/收入，**Then** 事实币种为 CNY，CNY 余额变化，其它币种余额不变。
2. **Given** 账户 `工行` 存在，**When** 执行带显式币种 JPY 的余额校准，**Then** 仅 JPY 余额被设为校准值，并产生可审计的校准事实/观测。
3. **Given** 多币种账户操作路径，**When** 未提供操作币种，**Then** 失败关闭，提示必须指定币种，不写入。
4. **Given** 非法币种码（非 3 位字母），**When** 记账或校准，**Then** 校验失败，不写入。

---

### User Story 3 - 账单导入按账户名路由、按行币种入账 (Priority: P1)

作为导入用户，mapping 仍把账单行解析到**账户名**；行币种直接写入该账户对应分币种余额。**不再**要求预先存在“同名 + 该币种”的分册账户，也**不再**因“行币种 ≠ 账户唯一币种”拒绝现金/贷款账户导入。

**Why this priority**: 004 已开放任意币种与 mapping 多账户导入，但解析仍按 `(account_name, currency)` 找分册；本 feature 必须把导入接到“一账户多币种”。

**Independent Test**: 仅存在账户 `工行`；mapping 指向 `工行`；导入含 CNY 与 JPY 行的银行账单后，两行事实均挂同一账户且币种正确；重复导入幂等；双后端一致。

**Acceptance Scenarios**:

1. **Given** 仅存在 cash 账户 `工行`，mapping 将支付方式/卡号解析到 `工行`，**When** 导入含 CNY 与 JPY 行的账单，**Then** 各行写入 `工行` 对应币种事实与投影，无需 `工行(JPY)` 分册。
2. **Given** 行币种与账户曾用于其它币种操作的历史不同，**When** 导入，**Then** 不因“币种不匹配账户唯一币种”失败。
3. **Given** mapping 目标账户名在 workspace 中不存在，**When** 导入，**Then** 整批失败回滚，错误含账户名，无部分写入。
4. **Given** 同一文件 content digest 已成功导入，**When** 再次导入，**Then** 不重复发布正式事实（幂等成功/already imported）。
5. **Given** `ft convert` 与 `ft import` 使用同一 mapping 与文件，**When** 对比账户归属，**Then** 账户名路由一致；行币种仍按行保留。

---

### User Story 4 - 跨账户跨币种转账仍可用 (Priority: P2)

作为用户，我可以在两个账户之间转账；当转出币种与转入币种不同时，必须提供转入金额；系统用**账户名 + 操作侧币种**指定双方对应币种余额，不再依赖“账户行上的唯一币种”消歧账户标识。

**Why this priority**: 为后续换汇铺路；本 feature 复用/修正现有跨币种转账路径，不引入正式 FX 产品事件。

**Independent Test**: 从 `工行` CNY 转到 `钱包` USD；跨币种缺 to-amount 失败；双后端一致。

**Acceptance Scenarios**:

1. **Given** 账户 A、B 存在，**When** 从 A 的 CNY 余额转到 B 的 CNY 余额并指定金额，**Then** 双方同币种余额按金额增减。
2. **Given** 账户 A、B 存在，**When** 从 A 的 CNY 转到 B 的 JPY 且提供 to-amount，**Then** A 的 CNY 减少、B 的 JPY 增加，事实币种各自正确。
3. **Given** 跨币种转账，**When** 未提供 to-amount，**Then** 失败关闭，不写入任一侧。
4. **Given** 转账任一侧账户名不存在，**When** 执行，**Then** 失败且无部分写入。

---

### User Story 5 - 一次性合并历史分册账户 (Priority: P1)

作为已有数据的用户/运维，实现完成后通过**一次性迁移**把历史“同名不同币种”账户行合并为单一账户；所有现金事实、投资事件、生命周期、估值观测与导入关联挂到保留的账户标识；合并后系统**不再**提供旧分册语义或兼容查找。

**Why this priority**: 用户明确不要长期兼容层；无迁移则无法在真实数据上使用新模型。

**Independent Test**: 准备同名 CNY/JPY 两行账户及各自事实；跑一次性迁移后仅剩一个账户实体，事实与分币种余额完整；type 冲突样本迁移失败并报出。双后端迁移结果等价。

**Acceptance Scenarios**:

1. **Given** 同 workspace 下存在 `工行`+CNY 与 `工行`+JPY 且 type 相同，**When** 执行一次性迁移，**Then** 合并为一个账户，两币种历史事实均挂到该账户，分币种余额可重建。
2. **Given** 同名账户行 type 不同（如 cash 与 loan 同名），**When** 迁移，**Then** 失败关闭并明确报出冲突账户名与类型，不产生静默错误合并。
3. **Given** 迁移成功完成，**When** 使用账户列表/查找/导入，**Then** 仅有 name 唯一的账户语义；不存在“按 name+currency 找分册账户”的兼容路径。
4. **Given** 迁移涉及估值观测中的现金账户标识，**When** 合并完成，**Then** 现金估值标识可区分同一账户下不同币种，互不覆盖。

---

### Edge Cases

- 账户名在 workspace 内必须唯一；创建/重命名到已占用名称失败。
- 同名历史分册 type 不一致：一次性迁移失败并列出冲突，不得自动猜测。
- 无主币种：任何写入现金/贷款/借出余额的操作都必须显式操作币种；禁止用隐式默认主币种入账。
- 币种规则沿用开放三字母码（大小写归一）；非法码失败。
- 删除账户：账户下任一币种仍有事实/依赖时不得删除（或按现有“有事实不可删”语义扩展到账户级）。
- 停用/启用、重命名：作用在账户级，影响该账户全部分币种余额的展示与可用性，不按币种分册生命周期。
- security/crypto 账户：展示名在投资账户范围内仍唯一；账户级唯一 currency 标识移除后，投资事件与持仓继续自带 currency/ticker 信息；本 feature 不重做投资产品语义。
- 导入目标账户不存在、mapping 未匹配、digest 幂等、整批事务：保持 004 的失败关闭与幂等合同，仅账户解析键从 name+currency 改为 name。
- 同账户内换汇（CNY 余额 ↔ JPY 余额的专用 FX 事件）：**非目标**；若 transfer 指向同一账户名的不同币种，仅作为通用转账路径的自然结果，不定义汇率产品语义。
- PostgreSQL 与 SQLite：账户唯一约束、合并迁移、多币种余额读写的用户可见结果必须等价；禁止自动回退、双写、隐式跨后端迁移。
- 允许的运行差异：锁实现、并发吞吐、错误底层驱动信息；不得造成账务结果或账户标识分叉。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST 将账户标识定义为 workspace 内 `name` 唯一；账户实体 MUST NOT 再以单一 `currency` 作为标识组成部分。
- **FR-002**: System MUST 允许同一账户持有零个或多个分币种余额项；分币种余额键为操作或账本记录中的币种。
- **FR-003**: System MUST NOT 引入账户主币种、默认币种或“代表币种”作为记账缺省来源。
- **FR-004**: 创建账户 MUST 不要求绑定唯一账户币种；若创建时提供可选初始币种，仅可用于初始化该币种 0 余额展示项，不得写成账户永久属性。
- **FR-005**: 对手工现金类记账（add）与现金余额校准（checkin），System MUST 要求显式操作币种；写入事实的 currency 为该操作币种，并只更新对应币种余额。
- **FR-006**: 账户列表与财务报告 MUST 能展示每个账户下的多币种余额集合（一账户多余额行或等价聚合展示），用户可区分币种。
- **FR-007**: 重命名、删除、启用/停用 MUST 以账户名为作用域（账户级），不得再要求用 currency 消歧账户行。
- **FR-008**: 删除账户时，若该账户仍存在不可丢弃的正式事实或依赖，System MUST 拒绝删除并说明原因。
- **FR-009**: 账单导入 MUST 仅按 mapping 解析出的账户名定位账户；行币种 MUST 写入该账户对应事实与分币种余额。
- **FR-010**: 导入 MUST NOT 因“行币种与账户唯一币种不一致”拒绝 cash/loan/lend 账户；账户不存在或 mapping 失败时 MUST 整批失败回滚。
- **FR-011**: 导入幂等（workspace + source_kind + content digest）与 convert/import 账户名归属一致性 MUST 保持；仅账户解析键变更。
- **FR-012**: 跨账户转账 MUST 用账户名定位双方；用 from/to 操作币种指定余额项；跨币种 MUST 要求 to-amount；同币种可忽略多余 to-amount。
- **FR-013**: System MUST 提供一次性数据迁移：将历史同名同 type 的多币种账户行合并为单一账户，并重挂事实、快照可重建状态、生命周期与相关估值观测。
- **FR-014**: 一次性迁移遇到同名不同 type MUST 失败并报告冲突；不得静默合并或丢弃任一侧数据。
- **FR-015**: 迁移完成后，System MUST NOT 保留 name+currency 分册账户兼容查找、双写、影子账户或长期双模型 API。
- **FR-016**: 财富侧现金估值/校准标识 MUST 能区分同一账户下的不同币种，避免多币种 checkin 互相覆盖。
- **FR-017**: 金额 MUST 使用精确十进制语义；币种为 3 位字母并归一大写；非法币种失败关闭。
- **FR-018**: PostgreSQL 与 SQLite MUST 对上述用户可见行为、幂等、失败合同与迁移结果提供等价证据；禁止自动回退、双写、隐式跨后端迁移。
- **FR-019**: 文档与 CLI 帮助 MUST 反映：一账户多币种、操作需显式币种、导入按账户名、无主币种、一次性迁移且无兼容层。

### Key Entities

- **Account**: workspace 内名称唯一的账户实体；含 type、active 等账户级属性；**不含**唯一币种标识字段。
- **分币种余额**（旧称 `Currency Balance Pocket`）：账户在某一币种上的余额视图，可由账本记录确定性投影或从快照重建，不是第二套账户标识。
- **Cash/Loan Fact**: 挂在 account 上的正式资金事实，自身携带 currency 与 amount。
- **Transfer Pair**: 两侧事实各自携带账户与币种；跨币种时两侧金额可不同（to-amount）。
- **Import Mapping Target**: 解析结果为账户名；行级 currency 独立。
- **Valuation Observation (cash)**: 标识需绑定账户与币种（或等价），以支持多币种边界校准。
- **Legacy Account Row (migration only)**: 历史 name+currency 分册行；仅在一次性迁移中出现，迁移后不保留为运行时模型。

### Non-Goals

- 正式换汇产品：汇率源、FX 专用事件模型、汇兑损益产品化流水。
- 账户主币种 / 默认币种 / 代表币种。
- 长期兼容：`find(name, currency)` 分册语义、双写、旧 unique 并行、可重复复杂迁移框架。
- mapping.yaml 规则语言大改（仅账户解析从 name+currency 分册改为 name）。
- Web UI、MCP、Connector sync。
- 自动合并同名但不同 type 的账户。
- 投资组合交易语义大重构（buy/sell/swap 产品行为以现有为准；仅消除账户唯一币种标识带来的冲突）。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 用户可在单一账户下同时持有至少 2 种币种余额，并在列表/报告中正确读出。
- **SC-002**: 对同一账户的不同币种 add/checkin 互不覆盖；缺币种参数的操作 100% 被拒绝且无写入。
- **SC-003**: 仅存在一个目标账户名时，含 ≥2 种币种行的账单可一次导入成功；无需预建分册账户。
- **SC-004**: 重复导入后正式事实总数不变（幂等）。
- **SC-005**: 一次性迁移后，历史同名分册样本合并为 1 个账户实体，事实 0 丢失、0 串户；type 冲突样本 100% 失败可诊断。
- **SC-006**: 迁移后任意账户查找/导入路径不再依赖“同名多账户行 + 币种消歧”也能得到唯一账户。
- **SC-007**: 同一验收矩阵在 SQLite 与 PostgreSQL 上结论一致。
- **SC-008**: 跨币种转账在提供 to-amount 时可完成；缺 to-amount 时 100% 失败且无部分写入。

## Assumptions

- 用户已确认方案 A：账户不绑定币种，可记录多种币种余额，也不设置主币种。
- 用户已确认：不做兼容层；实现后一次性迁移现有数据即可；开发数据可丢弃但迁移必须可审计、失败关闭。
- 004 mapping import + open currency 行为保留，本 feature 只改账户标识与分币种余额挂载方式。
- 快照/投影仍可按 `账户类型 → 账户名 → 币种 → 金额` 表达多币种余额（产品层只需保证可读与可重建，不规定存储引擎细节）。
- security/crypto：账户展示名唯一约束保留；账户级唯一 currency 标识移除后，投资事件与持仓继续自带 currency/ticker 信息；本 feature 不重做投资产品语义。
- 同账户内正式“换汇”产品流留给后续 feature；本 feature 只保证多币种余额与跨账户跨币种转账底座。
- CLI 仍是主要用户界面；help 文案与 README 示例需同步，但不引入 Web。

## Dual-Database Behavior

| 行为 | PostgreSQL | SQLite | 等价要求 |
|---|---|---|---|
| 账户 name 唯一 | 强制唯一 | 同左 | 同名第二账户均失败 |
| 多币种余额读写 | 精确小数 | 同左 | 金额/币种/账户归属一致 |
| 导入按账户名 + 行币种 | 单事务整批 | 同左 | 条数、归属、幂等一致 |
| 一次性分册合并迁移 | 失败关闭/成功可审计 | 同左 | 合并结果与冲突报告一致 |
| 现金多币种估值标识 | 按账户+币种可区分 | 同左 | checkin 不互相覆盖 |
| 自动回退/双写/跨后端隐式迁移 | 禁止 | 禁止 | 禁止 |
