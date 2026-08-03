# Research: Wealth Attribution Core

## Decision 1: Use typed wealth ports and a pure calculation kernel

**Decision**: Add wealth-specific immutable DTOs and read/build protocols. Keep formulas, coverage/status propagation, aggregation, identities and canonicalization free of SQLAlchemy and vendor market clients.

**Rationale**: Existing cash/investment repositories return loose command dictionaries and omit stable account/fact/raw/revision identities. `FinanceQueryService` demonstrates application-level provider injection, while existing Decimal and investment replay helpers provide useful low-level semantics.

**Alternatives considered**: Expanding legacy dict protocols was rejected because it would weaken audit contracts and couple commands to analytical reads. Putting formulas in SQL was rejected because dialect precision/JSON differences would endanger parity.

## Decision 2: Add formal valuation observations

**Decision**: Persist revisioned cash/position check-ins, quotes and FX observations with stable identity, as-of/observed time, exact value, source and provenance.

**Rationale**: Current cash check-ins put exact balance only in mutable snapshot state and a rounded human description; current market lookup supplies unversioned present prices. Historical attribution cannot be deterministic or reconstructible from either.

**Alternatives considered**: Parsing check-in descriptions and using current prices were rejected as lossy and unauditable. Treating snapshots as facts was rejected by the constitution and existing architecture.

## Decision 3: Store immutable canonical result payloads and manifests

**Decision**: Content-address daily results, components, evidence and coverage; store canonical UTF-8 text plus digest and relational indexes/manifests. Keep prior revisions append-only.

**Rationale**: Canonical strings avoid PostgreSQL/SQLite JSON/NUMERIC serialization differences and preserve more than 18 fractional digits produced by division. Immutable component/evidence identities support historical links and deterministic retries.

**Alternatives considered**: Mutable daily rows were rejected because they silently rewrite history. Database-native aggregation/payload comparison was rejected due to backend representation drift.

## Decision 4: Build from a frozen watermark and publish with fencing

**Decision**: Capture a source watermark before calculation, bind the entire generation to it, and publish with full-index validation plus monotonic compare-and-swap under a short backend-appropriate writer lock.

**Rationale**: An atomic pointer alone cannot prevent a generation from mixing facts that arrive during calculation or an older concurrent builder from overwriting a newer generation.

**Alternatives considered**: One long write transaction was rejected because it would unnecessarily block SQLite and retain PostgreSQL locks. An unconditional pointer update was rejected because it permits regression.

## Decision 5: Use total evidence ordering and result-scoped contribution folding

**Decision**: Order by occurred_at, source identity, evidence kind and evidence identity; bind cursor to immutable result and ordering version. Fold repeated source contributions deterministically within each result scope, retaining non-monetary gap evidence.

**Rationale**: The first three keys are not unique; pagination without a total order can duplicate or omit rows. Aggregate evidence must reconcile to component values without losing repeated-day context or gaps.

**Alternatives considered**: Offset pagination was rejected because result size and concurrent history make it fragile. Global source deduplication was rejected because one fact may legitimately contribute in different result contexts.

## Decision 6: Keep the core transport-neutral

**Decision**: Return immutable evidence references and stable application errors, not URLs, HTTP status codes or OpenAPI shapes.

**Rationale**: HTTP and Web are explicit non-goals and belong to the next feature. Transport-neutral contracts are directly testable across database adapters and reusable by CLI/API/worker compositions.

**Alternatives considered**: Copying `/experimental/wealth` URLs from the prior design was rejected as premature A2 coupling.

## Decision 7: Extend the existing dual-backend test topology

**Decision**: Reuse the parameterized file-SQLite/real-PostgreSQL fixture and required `_test` database gate. Add a wealth-specific canonical comparator that preserves business IDs, fixed-seed property matrices and a deterministic benchmark harness.

**Rationale**: The repository already proves migration and contract parity this way. The generic existing normalizer removes every `id` key and therefore cannot validate component/evidence identities.

**Alternatives considered**: Mocked PostgreSQL was rejected because the constitution requires real backend evidence. Adding a benchmark dependency is unnecessary for the fixed local p95 protocol.

## Decision 8: Use an additive migration and reversible wealth scope

**Decision**: Add a linear Alembic revision after `20260717_01`; downgrade removes wealth read-model/valuation structures but never rewrites existing cash/investment/import facts.

**Rationale**: This makes the feature independently reviewable and reversible while preserving the completed dual-database baseline.

**Alternatives considered**: Rewriting the initial migration was rejected because the baseline feature is already complete and current history is a useful stable boundary.

## Decision 9: Record account lifecycle and source manifests explicitly

**Decision**: Append account opened/closed/reactivated events and persist a content-addressed source manifest with every build.

**Rationale**: Current `active` and `updated_at` values describe only the latest account state, so historical not-applicable coverage cannot be reconstructed. A digest alone also cannot prove which fact/valuation revisions a build read. Explicit lifecycle events and source-manifest items make coverage and build inputs auditable.

**Alternatives considered**: Inferring closure from `active=False`/`updated_at` was rejected because reactivation overwrites history. Storing only an aggregate watermark hash was rejected because it cannot enumerate or replay participating inputs.

## Decision 10: Reuse source-manifest items as direct evidence

**Decision**: A direct cash/investment/valuation/lifecycle contribution is paged from its immutable source-manifest item; component manifests store the frozen source-manifest identity and canonical selection/folding contract. Only evidence without a direct source row is materialized separately.

**Rationale**: Profiling the required 100,000-fact fixture showed that eagerly duplicating all source facts into evidence rows and manifest-link rows adds roughly three seconds to every cold build after the same inputs are already captured immutably. The source manifest already supplies the historical identity/revision/digest fence. Reusing it preserves the evidence contract without redundant physical copies.

**Alternatives considered**: Lazy creation after publication was rejected because it could observe a different source revision. Eager duplicate evidence/link rows were rejected because they miss the cold-query budget with no audit benefit. Omitting evidence was rejected by the constitution and spec.

## Decision 11: Make account ownership part of every owned coverage identity

**Decision**: Key cash/position coverage by `(workspace_id, owner_account_id, identity_kind, identity)`. Require explicit same-workspace ownership on account-owned valuation observations; derive position ownership only from immutable investment-event `account_id` plus canonical ticker/position fields or an explicit owned valuation. Shared quote and FX observations remain ownerless support inputs. Start position expectation at its earliest formal owning input, never guess a close, and fail closed with stable evidence on missing/conflicting ownership.

**Rationale**: A position identity alone cannot be evaluated against account lifecycle, and the same ticker may exist in multiple accounts. Explicit ownership makes lifecycle applicability, workspace isolation, coverage fingerprints and source manifests deterministic on both databases.

**Alternatives considered**: Joining by account display name or parsing an identity prefix was rejected as mutable and unauditable. Treating every position as globally owned was rejected because it collides across accounts. Backfilling guessed close/owner intervals from mutable snapshots or current `active` was rejected because it rewrites historical meaning. A separate speculative holding master was rejected for this feature because the existing formal investment facts already carry the authoritative account relationship; a future feature may introduce one with its own migration semantics.
