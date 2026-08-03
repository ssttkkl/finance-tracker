# Relations Kind Decouple

## Purpose
User description: "关系识别 Kind 竖切解耦：将 payment_mirror / transfer_pair / refund_offset 拆为独立 RulePack，共享最薄 core；合法跨 kind 依赖仅通过 pipeline 的 MatchContext；Phase A→D 固定；Diamond 作为 refund 子能力只读 accepted 边。目标：三 kind 行为可独立演进；Step A 零业务语义变更；词表清理（强/软排除等）后续 feature。非目标：通用规则引擎、改审查 API 契约、改 006/007 验收语义。 本能力的行为契约由迁移后的需求与场景持续维护。

## Requirements

### Requirement: 结构迁移后关系结果与基线一致
系统 MUST 作为记账用户，我在完成「关系识别模块按 kind 拆分」的发布后，对同一账本执行与迁移前相同的全量关系检查，得到的用户可见关系结论（各 kind 的 accepted / pending_review / 待配对关系的业务含义与成员事实）与迁移前基线一致，无需重新理解新的关系类型或审查流程。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 维护者可独立演进单一关系 kind
系统 MUST 作为关系规则维护者，我在只变更「转账识别」的信号或门控策略时，不需要阅读或修改「退款」或「支付镜像」的规则定义；反之亦然。跨 kind 的唯一协作点是固定阶段顺序与已接受关系边的只读上下文。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 编排顺序与跨 kind 兼容对审查者可解释
系统 MUST 作为审查关系的用户或维护者，我能从规格/运行说明中知道：关系检查按固定阶段顺序执行；后阶段可消费前阶段已接受关系；transfer 与 mirror 等兼容矩阵仍按 006 生效；我不会看到「同一检查里随机顺序导致不同 pending」的不可解释行为。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 功能需求基线
系统 MUST 保持以下迁移前功能需求；后续行为变更必须通过 OpenSpec change 明确修改。

- - **FR-001**: 系统 MUST 将 `payment_mirror`、`transfer_pair`、`refund_offset` 的**识别规则**划分为三个独立规则边界（RulePack 语义），使每个 kind 的信号、门控与配对策略可在不修改另外两个 kind 规则正文的前提下演进。
- - **FR-002**: 系统 MUST 提供最薄共享核心，仅包含跨 kind 必需的公共概念：正式事实视图、关系提案/证据壳、业务幂等键与待配对关系键、跨 kind 兼容矩阵、无 kind 语义的时间/金额等几何判定、以及关系投影顺序语义。MUST NOT 把任一 kind 的私有信号词表放入共享核心。
- - **FR-003**: 系统 MUST 通过唯一关系检查编排入口按固定阶段顺序执行识别：**A** 平台硬键类 `refund_offset` → **B** `payment_mirror` → **C** `transfer_pair`（含信用还款子类型）→ **D** 其余 `refund_offset` 路径（含商户/弱匹配/待配对关系与 diamond）。顺序 MUST 与 007 的统一扫描意图一致。
- - **FR-004**: 合法跨 kind 依赖 MUST 仅通过检查上下文中的**只读已接受关系边**与**事实占用集合**（及 006/007 已有的 remaining 等退款余额上下文）表达。规则边界 MUST NOT 为完成匹配而调用另一 kind 的匹配过程或读取其私有信号定义。
- - **FR-005**: Diamond（银行退货链）MUST 作为 **refund 规则边界内的子能力** 存在，产物 kind 仍为 `refund_offset`。其输入 MUST 为正式事实加上上下文中的 accepted `payment_mirror` 边与已接受的平台侧 `refund_offset` 边；MUST NOT 在 diamond 路径内重新执行 mirror 识别。
- - **FR-006**: 本 feature MUST NOT 改变 006/007 已规定的用户可见关系语义：关系 kind 集合、状态（pending_review / accepted / rejected / superseded）、待配对规则、审查 accept/reject/绑定对侧、幂等键占用、禁止缺少对侧流水的关系进入 accepted 状态、导入不写关系、以及跨 kind 兼容与投影顺序。
- - **FR-007**: 结构迁移完成后，对同一活跃正式事实集的全量关系检查 MUST 与迁移前基线在用户可见业务结论上对齐（按 kind 与状态可核对；样本业务键一致）。允许保留 superseded 审计链，但 MUST NOT 无故新增或丢失 pending/accepted 业务结论。
- - **FR-008**: 系统 MUST 保持 transfer 与 refund 的信号语义**概念分离**：即使文案短语相同，也不得强制共享同一信号定义源。后续「强排除 / 软 P2P / 真 transfer 信号」分层 MUST 能只在 transfer 边界内完成而不修改 refund 边界（本 feature 不要求实现该分层，但 MUST 不阻断之）。
- - **FR-009**: 关系检查的应用服务（触发、持久化、审查 API）MUST 继续作为编排与存储边界；规则边界只负责从事实与上下文产生关系提案。MUST NOT 将持久化细节泄漏为第三套规则源。
- - **FR-010**: 双后端（PostgreSQL 与 SQLite）下，本 feature 引入的结构变更 MUST 保持用户可见关系与审查行为等价；禁止依赖单一后端的隐式行为作为正确性条件。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：对该能力进行修改时，验证结果 MUST 覆盖迁移后的功能需求清单。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 可度量验收结果
系统 MUST 继续满足以下可度量结果；它们是迁移后的验收回归基线。

- - **SC-001**: 迁移前后对同一账本快照的 full 关系检查，用户可见的 accepted 与 pending_review 业务结论按 kind 计数一致，且抽检的业务键集合一致（100% 可核对对齐，或仅差已文档化的 superseded 审计差集）。
- - **SC-002**: 维护者完成「仅变更 transfer 信号策略」的评审时，变更 diff 不包含 refund 与 mirror 规则边界正文（抽检 1 次演练通过）。
- - **SC-003**: 维护者完成「仅变更 refund 商户/P2P 门控」的评审时，变更 diff 不包含 transfer 信号定义（抽检 1 次演练通过）。
- - **SC-004**: 文档化阶段顺序 A→D 与 diamond「只读已接受边」约束可被新维护者在 15 分钟内对照规格定位（走查通过）。
- - **SC-005**: 既有关系相关自动化验收（006/007 行为套件）在迁移后全部通过；无新增「用户必须改审查习惯」的失败项。
- - **SC-006**: PostgreSQL 与 SQLite 契约矩阵中与关系检查相关的用例保持双后端通过，无单后端专用正确性分支作为唯一依据。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：运行该能力的验收矩阵时，结果 MUST 满足迁移后的成功标准。
- THEN 系统满足该条件，并保留可复核的验证证据。

## Source
完整迁移来源与原始验证证据：[008-relations-kind-decouple/spec.md](../../changes/archive/2026-08-01-008-relations-kind-decouple/legacy/008-relations-kind-decouple/spec.md)。
本文件是 OpenSpec 的行为导向投影；实现细节、研究记录和历史任务保留在对应 change 的 `legacy/` 目录。
