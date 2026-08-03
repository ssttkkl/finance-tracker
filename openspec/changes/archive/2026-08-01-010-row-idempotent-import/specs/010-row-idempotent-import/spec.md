## Purpose
User description: "009 就改成「仅业务行幂等 + 重叠文件可增量」。这个开个新spec做吧，把消费账本和投资账本都改掉" — change cash and investment statement import idempotency from file-level source_digest short-circuit to **row-level source_identity only**, so overlapping files apply only new business rows (incremental). Supersedes digest-as-primary-idempotency in 007 and 009. Keep import batches / raw files as job/audit metadata, not ledger truth. Dual-backend required. 本能力的行为契约由迁移后的需求与场景持续维护。

## ADDED Requirements

### Requirement: Re-import same file: no double facts
系统 MUST As a user, I re-import the **exact same** statement file after a successful import. The system must not create duplicate cash transactions or investment events. It may report that no **new** rows were applied (count=0) and leave existing balances/positions unchanged.。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: Overlapping export: only new rows apply
系统 MUST As a user, I import file A (e.g. Jan–Mar), then file B that **overlaps** A (e.g. Mar–Jun) or a superset re-export. The system must **apply only rows whose business identity is not already present**, and leave existing facts for overlapping identities untouched.。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: Job metadata is not ledger truth
系统 MUST As a maintainer, I need import job records (batch, optional file metadata) for ops/debug without them defining “what is already booked.” Ledger truth remains formal facts (cash transactions / investment events) keyed by provenance to raw records’ business identity.。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 功能需求基线
系统 MUST 保持以下迁移前功能需求；后续行为变更必须通过 OpenSpec change 明确修改。

- - **FR-001**: System MUST use **business row identity** (`source_type` + `source_identity` within workspace) as the **sole** rule for whether a statement row creates a new formal cash or investment fact.
- - **FR-002**: System MUST NOT skip parsing or skip applying **new** rows solely because an `import_batches.source_digest` (or equivalent file hash) already has a completed batch. Digest MAY be stored for audit and MAY be reused to attach job metadata, but MUST NOT be primary idempotency for “no work.”
- - **FR-003**: On import of a statement (cash or investment), system MUST: parse all supported rows → resolve identities → for each identity already linked to a formal fact, **skip** formalization → for each novel identity, create raw record linkage and formal fact in one atomic transaction for that import job (no partial formal facts on failure).
- - **FR-004**: Re-import of a file whose **entire** row set is already known MUST succeed with **new formal fact count = 0** and leave ledger balances/positions unchanged.
- - **FR-005**: Import of a file that is a **superset or overlap** of prior imports MUST formalize **only** novel identities; reported count MUST equal the number of newly formalized facts (cash and investment respectively).
- - **FR-006**: Formal facts MUST continue to link to raw records for audit (`raw_record_id`); raw record payloads remain the parsed row snapshot. File path and batch are optional job context, not required for balance correctness.
- - **FR-007**: Behavior MUST apply uniformly to **cash statement import** and **investment statement import** entry points used by CLI `ft import` for those paths.
- - **FR-008**: Dual-backend (PostgreSQL and SQLite): same statement sequence MUST produce the same set of formal facts by business identity, same amounts, and same skip/new counts for overlapping imports. Schema/transaction differences MUST be documented; results MUST be equivalent for user-visible ledger state.
- - **FR-009**: Existing uniqueness constraints on raw identity and “one formal fact per raw record” MUST continue to prevent double booking under concurrency (fail closed or serialize; no silent double facts).
- - **FR-010**: User-visible outcome of an import MUST distinguish: success with new rows; success with zero new rows (full overlap); failure (parse error, account missing, identity bound to wrong account, validation failure) with no partial formal facts.

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：对该能力进行修改时，验证结果 MUST 覆盖迁移后的功能需求清单。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 可度量验收结果
系统 MUST 继续满足以下可度量结果；它们是迁移后的验收回归基线。

- - **SC-001**: After importing the same cash fixture twice, formal cash fact count increases only on the first import; second import adds **0** facts in 100% of automated runs.
- - **SC-002**: After importing investment fixture A then overlapping fixture B (known shared + novel identities), final investment event count equals **|union of identities|**, and B’s reported new count equals **|novel only|**, for both SQLite and PostgreSQL.
- - **SC-003**: Users can import a later monthly export that includes prior month’s rows without manual file splitting; only new activity appears once in the ledger.
- - **SC-004**: Dual-backend matrix for cash and investment overlapping-import scenarios: 100% agreement on new/skip counts and final balances/positions for identical fixture sequences.
- - **SC-005**: No production path remains that returns “already imported” **only** because file digest matches a prior completed batch while unread novel rows could exist in that file (regression suite must fail if digest short-circuit returns before row-level diff).

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：运行该能力的验收矩阵时，结果 MUST 满足迁移后的成功标准。
- THEN 系统满足该条件，并保留可复核的验证证据。
