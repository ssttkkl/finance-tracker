# Feature Specification: Row-Level Idempotent Import (Incremental)

**Feature Branch**: `010-row-idempotent-import`

**Created**: 2026-07-23

**Status**: Complete

**Input**: User description: "009 就改成「仅业务行幂等 + 重叠文件可增量」。这个开个新spec做吧，把消费账本和投资账本都改掉" — change cash and investment statement import idempotency from file-level source_digest short-circuit to **row-level source_identity only**, so overlapping files apply only new business rows (incremental). Supersedes digest-as-primary-idempotency in 007 and 009. Keep import batches / raw files as job/audit metadata, not ledger truth. Dual-backend required.

**Context**: Today both **cash** (`StatementImportService`) and **investment** (`InvestmentImportService`) treat a completed batch with the same `source_digest` as “already imported” and return count=0 **without** applying any new rows. That makes the **file** the unit of truth. Users re-export overlapping windows (monthly CSV that includes last month’s rows); only **business-row identity** should decide whether a fact is new. This feature redefines import idempotency for **both ledgers** and documents provenance as **event/fact → raw_record**, not “file path forever.”

**Supersedes / extends**:
- Idempotency semantics in `007-closed-trade-refund-import` (cash import) and `009-investment-account-import` (investment import) where file digest was treated as whole-batch skip.
- Does **not** replace 009 parsers or event models; only import orchestration and acceptance of duplicates/increments.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Re-import same file: no double facts (Priority: P1)

As a user, I re-import the **exact same** statement file after a successful import. The system must not create duplicate cash transactions or investment events. It may report that no **new** rows were applied (count=0) and leave existing balances/positions unchanged.

**Why this priority**: Safety baseline — row idempotency must not weaken “no double booking.”

**Independent Test**: Import fixture once (cash or investment); import identical file again; assert formal fact counts unchanged; assert no new `investment_events` / cash facts for those identities.

**Acceptance Scenarios**:

1. **Given** a completed cash import of file A, **When** the user imports A again unchanged, **Then** no new cash facts are created; reported new-row count is 0; balances unchanged.
2. **Given** a completed investment import of file A (e.g. IBKR/Schwab/DFZQ), **When** the user imports A again unchanged, **Then** no new investment events; new-row count is 0; positions/cash CHECKIN outcome unchanged.
3. **Given** either ledger, **When** the second import completes, **Then** the system may create a **new** import batch job record for audit, but must not treat batch/digest uniqueness as the reason to skip parsing if that would block story 2 (see FR-002: digest must not short-circuit before row diff).

---

### User Story 2 - Overlapping export: only new rows apply (Priority: P1)

As a user, I import file A (e.g. Jan–Mar), then file B that **overlaps** A (e.g. Mar–Jun) or a superset re-export. The system must **apply only rows whose business identity is not already present**, and leave existing facts for overlapping identities untouched.

**Why this priority**: This is the product reason to abandon file-level skip — monthly re-exports and rolling statements.

**Independent Test**: Construct two fixtures sharing some `source_identity` values and differing on others; import A then B; assert only B’s novel identities become formal facts; shared identities remain single; snapshot/balances reflect A then +B-new only.

**Acceptance Scenarios**:

1. **Given** cash statements A and B where B includes some of A’s business rows plus new rows, **When** the user imports A then B, **Then** cash facts equal union of identities; count on B equals number of new identities only.
2. **Given** investment statements A and B with the same overlap pattern, **When** the user imports A then B, **Then** investment events equal union of identities; positions equal applying A then only new rows from B.
3. **Given** B is imported first then A (order reverse), **When** both complete, **Then** final formal facts still equal the same identity set (order of discovery must not create duplicates).

---

### User Story 3 - Job metadata is not ledger truth (Priority: P2)

As a maintainer, I need import job records (batch, optional file metadata) for ops/debug without them defining “what is already booked.” Ledger truth remains formal facts (cash transactions / investment events) keyed by provenance to raw records’ business identity.

**Why this priority**: Clarifies architecture so implementers do not reintroduce digest-as-idempotency.

**Independent Test**: After two overlapping imports, inspect that multiple batches may exist for related digests/paths while formal fact counts follow identity union only.

**Acceptance Scenarios**:

1. **Given** two overlapping imports, **When** listing import jobs, **Then** more than one completed job may exist; formal fact count is still identity-unique.
2. **Given** a successful import, **When** tracing a formal fact, **Then** it links to a raw record with stable `source_identity` (and optional batch/file for “this job”), not only a file path.

---

### Edge Cases

- **Empty after skip**: File fully overlaps existing identities → success, new count=0, message indicates no new rows (not an error).
- **Partial overlap**: Mixed new/old rows → only new rows formalized; already-present identities skipped without rewriting events.
- **Same identity, different account**: Existing 007 behavior — if identity already bound to another account, import **fails closed** with clear error (do not silent redirect).
- **Identity quality**: Rows without a stable broker id must still get a deterministic identity (existing composite/hash rules); collisions that would merge distinct business events are a **parser/source** defect, not fixed by file digest.
- **CHECKIN / snapshot rows** (investment): Identities that encode a checkpoint (e.g. cash CHECKIN amount) remain unique per identity string; re-import of same CHECKIN identity does not re-apply; a later statement with a **new** CHECKIN identity may apply a new checkin (product: checkpoints are rows like any other).
- **PostgreSQL vs SQLite**: Same inputs → same which identities are new/skipped, same formal fact counts and amounts (Constitution IV). Allowed: different batch UUIDs, timestamps.
- **Prohibited**: Auto-fallback between DBs; dual-write; using file path as identity; skipping parse solely because digest was seen when B could contain new rows.
- **Relations (cash)**: New facts from incremental import participate in existing relation pipelines; already-imported identities do not re-fire duplicate formal facts (relation side effects only for newly formalized facts — same as first import of those rows).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST use **business row identity** (`source_type` + `source_identity` within workspace) as the **sole** rule for whether a statement row creates a new formal cash or investment fact.

- **FR-002**: System MUST NOT skip parsing or skip applying **new** rows solely because an `import_batches.source_digest` (or equivalent file hash) already has a completed batch. Digest MAY be stored for audit and MAY be reused to attach job metadata, but MUST NOT be primary idempotency for “no work.”

- **FR-003**: On import of a statement (cash or investment), system MUST: parse all supported rows → resolve identities → for each identity already linked to a formal fact, **skip** formalization → for each novel identity, create raw record linkage and formal fact in one atomic transaction for that import job (no partial formal facts on failure).

- **FR-004**: Re-import of a file whose **entire** row set is already known MUST succeed with **new formal fact count = 0** and leave ledger balances/positions unchanged.

- **FR-005**: Import of a file that is a **superset or overlap** of prior imports MUST formalize **only** novel identities; reported count MUST equal the number of newly formalized facts (cash and investment respectively).

- **FR-006**: Formal facts MUST continue to link to raw records for audit (`raw_record_id`); raw record payloads remain the parsed row snapshot. File path and batch are optional job context, not required for balance correctness.

- **FR-007**: Behavior MUST apply uniformly to **cash statement import** and **investment statement import** entry points used by CLI `ft import` for those paths.

- **FR-008**: Dual-backend (PostgreSQL and SQLite): same statement sequence MUST produce the same set of formal facts by business identity, same amounts, and same skip/new counts for overlapping imports. Schema/transaction differences MUST be documented; results MUST be equivalent for user-visible ledger state.

- **FR-009**: Existing uniqueness constraints on raw identity and “one formal fact per raw record” MUST continue to prevent double booking under concurrency (fail closed or serialize; no silent double facts).

- **FR-010**: User-visible outcome of an import MUST distinguish: success with new rows; success with zero new rows (full overlap); failure (parse error, account missing, identity bound to wrong account, validation failure) with no partial formal facts.

### Key Entities

- **Business row identity**: Stable key for a statement line within a source type (broker/bank id or documented composite). Determines “already booked.”
- **Formal fact**: Cash transaction or investment event that affects balances/positions.
- **Raw record**: Parsed row snapshot + identity; audit and linkage target.
- **Import job (batch)**: Optional/ops record of an import attempt; may store digest and ref for debugging; not ledger authority.
- **Source file metadata**: Optional capture of size/digest/media type for that job.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After importing the same cash fixture twice, formal cash fact count increases only on the first import; second import adds **0** facts in 100% of automated runs.
- **SC-002**: After importing investment fixture A then overlapping fixture B (known shared + novel identities), final investment event count equals **|union of identities|**, and B’s reported new count equals **|novel only|**, for both SQLite and PostgreSQL.
- **SC-003**: Users can import a later monthly export that includes prior month’s rows without manual file splitting; only new activity appears once in the ledger.
- **SC-004**: Dual-backend matrix for cash and investment overlapping-import scenarios: 100% agreement on new/skip counts and final balances/positions for identical fixture sequences.
- **SC-005**: No production path remains that returns “already imported” **only** because file digest matches a prior completed batch while unread novel rows could exist in that file (regression suite must fail if digest short-circuit returns before row-level diff).

## Dependencies

- **007** cash import pipeline and raw/formal linkage.
- **009** investment import pipelines (DFZQ, IBKR, Schwab) and `source_identity` recipes.
- **002** dual-database runtime and test Postgres conventions.
- Constitution I (idempotent, no silent double book) and IV (backend equivalence).

## Out of Scope

- Changing broker/bank **parser field maps** or fee contracts (remain 007/009).
- Connector auto-sync cursors (011) — though this feature makes overlapping file imports safe; API cursors are separate.
- Asset valuation (**011-asset-valuation-quote**); Connector sync (**012**); Web browser (**013**).
- Historical rewrite of already double-booked data (if any); no automatic merge tool required.
- Deleting `import_batches` / `raw_files` tables; only semantics of digest as skip gate change.
- Soft-delete / user undo of imports.

## Assumptions

- Roadmap numbering (2026-07-23): after inserting this feature as **010**, subsequent planned ids shift by one:
  valuation → **011**, connector sync → **012**, transaction browser web → **013**.
- Cash path already skips formalization when raw id already has a fact in some cases; investment path currently short-circuits on digest — both must end on **row-only** rule.
- CHECKIN and similar synthetic rows are identities like any other; re-export with a new ending balance produces a **new** identity if the identity recipe includes that amount/date.
- “No partial facts” remains: a failed import job does not leave a subset of **new** facts committed.
- Concurrent identical imports: at most one formal fact per identity (DB constraints / transaction rules).
