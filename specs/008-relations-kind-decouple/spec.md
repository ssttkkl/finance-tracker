# Feature Specification: Relations Kind Decouple

**Feature Branch**: `008-relations-kind-decouple`

**Created**: 2026-07-22

**Status**: Complete

**Input**: User description: "关系识别 Kind 竖切解耦：将 payment_mirror / transfer_pair / refund_offset 拆为独立 RulePack，共享最薄 core；合法跨 kind 依赖仅通过 pipeline 的 MatchContext；Phase A→D 固定；Diamond 作为 refund 子能力只读 accepted 边。目标：三 kind 行为可独立演进；Step A 零业务语义变更；词表清理（强/软排除等）后续 feature。非目标：通用规则引擎、改审查 API 契约、改 006/007 验收语义。"

**Context**: Extends `specs/006-transaction-relations` and `specs/007-closed-trade-refund-import` without superseding their acceptance semantics. Structural refactor of how relation recognition is organized and composed.

## Clarifications

### Session 2026-07-22

- Q: 三种关系是否允许语义/结果上的依赖？ → A: **允许数据依赖，禁止实现串味**。后阶段可读前阶段已接受的关系边与占用集合；不得共享各 kind 私有信号词表或互相调用对方匹配内部逻辑。
- Q: Diamond（银行退货链）归属？ → A: **作为 refund 子能力**（非第四种关系 kind）；只消费已接受的 mirror 边与平台 refund 边，不重新执行 mirror 匹配。
- Q: 本 feature 是否改配对业务规则（如闲鱼强排除、微信软处理）？ → A: **否**。本 feature 为结构解耦与行为对齐基线；词表/策略清理另开 feature 或后续 Living 变更。
- Q: 审查与持久化对外契约？ → A: **不变**。关系 kind、状态机、CLI/审查入口、幂等键语义与 006/007 一致；用户可见关系结果在迁移后应与迁移前基线对齐（允许可解释的 superseded 审计，但不得改变 accepted/pending 业务结论集合的可核对语义）。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 结构迁移后关系结果与基线一致 (Priority: P1)

作为记账用户，我在完成「关系识别模块按 kind 拆分」的发布后，对同一账本执行与迁移前相同的全量关系检查，得到的用户可见关系结论（各 kind 的 accepted / pending_review / 开放单腿 pending 的业务含义与成员事实）与迁移前基线一致，无需重新理解新的关系类型或审查流程。

**Why this priority**: 解耦若改变配对待遇，会破坏已建立的财务可信与审查工作；零语义回归是本 feature 的首要交付。

**Independent Test**: 固定同一 workspace 快照（或可复现导入集），记录迁移前一次 full recompute 的关系摘要（按 kind×status 计数 + 关键业务键样本）；迁移后同触发再跑；对比摘要与样本业务键集合一致（或仅差可文档化的 superseded 审计，不含新增/丢失用户可见 pending/accepted 业务结论）。

**Acceptance Scenarios**:

1. **Given** 迁移前已记录基线关系检查结果，**When** 完成 kind 竖切结构迁移并执行同等范围的关系检查，**Then** 各 kind 的 accepted 与 pending_review（含开放单腿）业务结论与基线一致，用户无需学习新 kind。
2. **Given** 基线中存在 diamond 推导出的银行侧退款关系，**When** 迁移后重跑，**Then** 同等输入下仍能建立等价结论，且不要求用户手工重做 mirror。
3. **Given** 用户使用既有审查命令 accept/reject/绑定 open-leg，**When** 迁移后，**Then** 命令语义与状态机与 006/007 一致，无新强制参数。

---

### User Story 2 - 维护者可独立演进单一关系 kind (Priority: P1)

作为关系规则维护者，我在只变更「转账识别」的信号或门控策略时，不需要阅读或修改「退款」或「支付镜像」的规则定义；反之亦然。跨 kind 的唯一协作点是固定阶段顺序与已接受关系边的只读上下文。

**Why this priority**: 本 feature 的产品价值是降低误改与认知负担，使强/软排除等后续策略可安全落地。

**Independent Test**: 做一次「仅 transfer 信号」的文档化变更演练（或评审清单）：diff 范围不得包含 refund/mirror 的规则定义；做一次「仅 refund 商户弱匹配」演练：diff 不得包含 transfer 信号定义。跨边 diamond 变更只允许出现在 refund 子能力与阶段编排说明中。

**Acceptance Scenarios**:

1. **Given** 需要调整 transfer 的排除/信号策略，**When** 维护者定位变更点，**Then** 变更集中在 transfer 规则边界内，不强制修改 refund 或 mirror 的规则正文。
2. **Given** 需要调整 refund 的 P2P 家族或商户退款门控，**When** 维护者定位变更点，**Then** 变更不强制修改 transfer 的排除表或 mirror 的配对窗。
3. **Given** diamond 依赖 mirror 与平台 refund 的已接受边，**When** 维护者阅读依赖说明，**Then** 文档明确：依赖的是「已接受边」，不是「重新跑 mirror 匹配」；阶段顺序保证 diamond 在 mirror（及平台硬键 refund）之后。

---

### User Story 3 - 编排顺序与跨 kind 兼容对审查者可解释 (Priority: P2)

作为审查关系的用户或维护者，我能从规格/运行说明中知道：关系检查按固定阶段顺序执行；后阶段可消费前阶段已接受关系；transfer 与 mirror 等兼容矩阵仍按 006 生效；我不会看到「同一检查里随机顺序导致不同 pending」的不可解释行为。

**Why this priority**: 解耦后若顺序漂移，会出现难复现的 inbox 差异。

**Independent Test**: 同一事实集连续两次 full recompute，用户可见关系业务结论一致；文档列出阶段顺序 A→D 及每阶段产出 kind。

**Acceptance Scenarios**:

1. **Given** 完整关系检查，**When** 执行完成，**Then** 阶段顺序固定为：平台硬键退款 → 支付镜像 → 转账/信用还款 → 银行/弱退款与 diamond 等剩余 refund 路径（与 007 编排意图一致）。
2. **Given** 前阶段未产生某条 accepted mirror，**When** 依赖该边的 diamond 路径运行，**Then** 不得伪造 mirror；仅在边存在时推导。
3. **Given** 006 规定的跨 kind 兼容矩阵，**When** 迁移后检查，**Then** 兼容与投影顺序语义保持不变。

---

### Edge Cases

- 仅有退款文案、无任何 mirror 边时：merchant/open-leg refund 仍可按 006/007 工作；diamond 不产出。
- 仅有 mirror、无平台 refund 边时：diamond 不得臆造平台退款。
- 全量 recompute 与「导入后种子检查」：编排顺序相同；种子范围规则仍遵循 006（种子 + 跨批次候选）。
- 并发两次关系检查：同一 workspace 结果可串行化到等价用户可见结论（006 已有要求，本 feature 不削弱）。
- PostgreSQL 与 SQLite：用户可见关系结论与审查语义等价；允许实现层存储/锁差异，禁止静默双写或跨后端漂移业务结论。
- 迁移中的临时兼容门面：若存在，不得成为长期第二套规则源；完成后单一编排入口。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 将 `payment_mirror`、`transfer_pair`、`refund_offset` 的**识别规则**划分为三个独立规则边界（RulePack 语义），使每个 kind 的信号、门控与配对策略可在不修改另外两个 kind 规则正文的前提下演进。
- **FR-002**: 系统 MUST 提供最薄共享核心，仅包含跨 kind 必需的公共概念：正式事实视图、关系提案/证据壳、业务幂等键与开放单腿键、跨 kind 兼容矩阵、无 kind 语义的时间/金额等几何判定、以及关系投影顺序语义。MUST NOT 把任一 kind 的私有信号词表放入共享核心。
- **FR-003**: 系统 MUST 通过唯一关系检查编排入口按固定阶段顺序执行识别：**A** 平台硬键类 `refund_offset` → **B** `payment_mirror` → **C** `transfer_pair`（含信用还款子类型）→ **D** 其余 `refund_offset` 路径（含商户/弱匹配/开放单腿与 diamond）。顺序 MUST 与 007 的统一扫描意图一致。
- **FR-004**: 合法跨 kind 依赖 MUST 仅通过检查上下文中的**只读已接受关系边**与**事实占用集合**（及 006/007 已有的 remaining 等退款余额上下文）表达。规则边界 MUST NOT 为完成匹配而调用另一 kind 的匹配过程或读取其私有信号定义。
- **FR-005**: Diamond（银行退货链）MUST 作为 **refund 规则边界内的子能力** 存在，产物 kind 仍为 `refund_offset`。其输入 MUST 为正式事实加上上下文中的 accepted `payment_mirror` 边与已接受的平台侧 `refund_offset` 边；MUST NOT 在 diamond 路径内重新执行 mirror 识别。
- **FR-006**: 本 feature MUST NOT 改变 006/007 已规定的用户可见关系语义：关系 kind 集合、状态（pending_review / accepted / rejected / superseded）、开放单腿 pending 规则、审查 accept/reject/绑定对侧、幂等键占用、禁止单腿 accepted、导入不写关系、以及跨 kind 兼容与投影顺序。
- **FR-007**: 结构迁移完成后，对同一活跃正式事实集的全量关系检查 MUST 与迁移前基线在用户可见业务结论上对齐（按 kind 与状态可核对；样本业务键一致）。允许保留 superseded 审计链，但 MUST NOT 无故新增或丢失 pending/accepted 业务结论。
- **FR-008**: 系统 MUST 保持 transfer 与 refund 的信号语义**概念分离**：即使文案短语相同，也不得强制共享同一信号定义源。后续「强排除 / 软 P2P / 真 transfer 信号」分层 MUST 能只在 transfer 边界内完成而不修改 refund 边界（本 feature 不要求实现该分层，但 MUST 不阻断之）。
- **FR-009**: 关系检查的应用服务（触发、持久化、审查 API）MUST 继续作为编排与存储边界；规则边界只负责从事实与上下文产生关系提案。MUST NOT 将持久化细节泄漏为第三套规则源。
- **FR-010**: 双后端（PostgreSQL 与 SQLite）下，本 feature 引入的结构变更 MUST 保持用户可见关系与审查行为等价；禁止依赖单一后端的隐式行为作为正确性条件。

### Key Entities

- **Rule boundary (per kind)**: 某一关系 kind 的识别职责范围：私有信号与门控、配对与开放单腿策略、产出该 kind 的关系提案。
- **Shared core concepts**: 跨 kind 公共模型与键、兼容矩阵、几何判定、投影语义；不含 kind 私有信号。
- **Match context**: 单次关系检查中的只读协作数据：已接受边（按 kind）、事实占用集合、退款剩余等；由编排填充，供后阶段消费。
- **Pipeline / orchestration**: 唯一阶段顺序与上下文组装职责；是跨 kind 数据依赖的唯一合法交汇点。
- **Diamond path**: refund 子能力：基于 mirror 边与平台 refund 边推导银行侧退款关系提案。
- **Relation proposal / persisted relation**: 与 006 一致的关系对象（kind、状态、证据、业务键）；本 feature 不新增用户可见 kind。

### Assumptions

- 006 的跨 kind 兼容矩阵与投影顺序、007 的 Phase A–D 意图仍为正确基线。
- 「行为对齐基线」以同一数据快照上的 full recompute 对比为准；人工已 reject 占用键的行为与 006 一致。
- 平台硬键匹配逻辑可保留在 refund 边界内（或作为其协作能力），但不因此把 mirror/transfer 词表并入 refund。
- 词表策略纠偏（闲鱼强排除、微信/支付宝转账软处理等）明确排除在本 feature 范围外。

### Non-Goals

- 不引入通用可插拔规则引擎或配置化 DSL。
- 不改变 CLI/审查 API 的用户契约与关系 kind 枚举。
- 不修改 006/007 的金额严格相等、禁止单腿 accepted、导入不写关系等财务原则。
- 不在本 feature 内完成 transfer 强/软排除分层或批量清理历史 pending。
- 不将 diamond 提升为第四种用户可见关系 kind。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 迁移前后对同一账本快照的 full 关系检查，用户可见的 accepted 与 pending_review 业务结论按 kind 计数一致，且抽检的业务键集合一致（100% 可核对对齐，或仅差已文档化的 superseded 审计差集）。
- **SC-002**: 维护者完成「仅变更 transfer 信号策略」的评审时，变更 diff 不包含 refund 与 mirror 规则边界正文（抽检 1 次演练通过）。
- **SC-003**: 维护者完成「仅变更 refund 商户/P2P 门控」的评审时，变更 diff 不包含 transfer 信号定义（抽检 1 次演练通过）。
- **SC-004**: 文档化阶段顺序 A→D 与 diamond「只读已接受边」约束可被新维护者在 15 分钟内对照规格定位（走查通过）。
- **SC-005**: 既有关系相关自动化验收（006/007 行为套件）在迁移后全部通过；无新增「用户必须改审查习惯」的失败项。
- **SC-006**: PostgreSQL 与 SQLite 契约矩阵中与关系检查相关的用例保持双后端通过，无单后端专用正确性分支作为唯一依据。

## Dependencies

- `specs/006-transaction-relations`：关系模型、状态机、兼容矩阵、投影、审查、open-leg。
- `specs/007-closed-trade-refund-import`：导入不写关系、统一扫描阶段顺序、平台硬键退款与银行 Phase D 分工。

## Out of Scope Notes for Planning

- 物理模块路径、类名、lint 禁令等属 plan/tasks，不在本 spec 强制；但 FR/SC 要求的**边界与依赖方向**必须在 plan 中可追溯到可执行任务。
