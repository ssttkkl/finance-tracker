# Implementation Plan: Wealth Attribution Core

**Branch**: `codex/wealth-attribution-core` | **Date**: 2026-07-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/003-wealth-attribution-core/spec.md`

## Summary

Implement a transport-neutral `WealthChangeService` that calculates CNY/Asia-Shanghai daily wealth points from typed, revisioned formal facts and valuation observations, derives natural-month breakdown and day/week/month series from those points, and publishes a content-addressed append-only read model through an atomically fenced active generation. Pure domain calculations remain database-independent; PostgreSQL and SQLite share one Application Service, schema lineage and canonical contract matrix. Existing mutable snapshots and current-price lookups are explicitly excluded as wealth facts.

## Technical Context

**Language/Version**: Python 3.11+

**Primary Dependencies**: Python standard library (`dataclasses`, `decimal`, `datetime`, `hashlib`, `json`, `zoneinfo`), SQLAlchemy 2.x, Alembic 1.16+, psycopg 3; existing Finance Tracker application/repository patterns

**Storage**: PostgreSQL and file-backed SQLite, selected explicitly by `FT_DATABASE_URL`; shared Alembic lineage with dialect-specific types/locking isolated in relational adapters

**Testing**: pytest; deterministic fixed-seed property matrices, golden JSON fixtures, shared SQLite/real-PostgreSQL contract tests, failure/concurrency injection, and a purpose-built p95 benchmark harness using `time.perf_counter_ns`

**Target Platform**: Local Python runtime on macOS/Linux; no HTTP or browser runtime in this feature

**Project Type**: Single Python package with domain, application, repository-port, relational-adapter, migration and test layers

**Performance Goals**: For each backend on the documented fixed benchmark, cold build/query p95 <5s and validated active-cache query p95 <300ms for 10 accounts, 50 positions, 100,000 facts and 366 days

**Constraints**: Exact Decimal semantics; CNY and Asia/Shanghai fixed; maximum 366-day query; deterministic canonical bytes; append-only historical result/evidence; source-watermark-consistent builds; monotonic fenced publication; no fallback/dual write/implicit migration; no raw sensitive payload logging

**Scale/Scope**: One workspace per service binding; formal facts plus valuation observations; daily retention sufficient for 366-day queries; five mandatory golden families plus multi-account/multi-currency/concurrency/performance fixtures

## Constitution Check

*GATE: Passed before research and re-checked after design.*

| Principle / gate | Design evidence | Status |
|---|---|---|
| I. 财务正确性与可审计性 | Pure Decimal calculations, formal revisioned valuation facts, canonical identities, append-only evidence/results, closed identities and explicit gaps | PASS |
| II. Spec Kit 规格驱动 | `spec.md`, this plan, design artifacts, tasks, analyze, delegated implement and converge are the sole workflow | PASS |
| III. 测试先行与验证证据 | Every behavior/data/interface task is preceded by a failing test; full SQLite and required live PostgreSQL matrices are completion gates | PASS |
| IV. 显式数据库选择与行为等价 | Shared Application Service/schema, explicit `FT_DATABASE_URL`, no fallback/dual write/migration, real dual-backend canonical parity matrix | PASS |
| V. 清晰边界与最小复杂度 | Domain/application depend only on typed ports; SQLAlchemy/dialects stay in adapter; HTTP/Web/Worker/MCP remain non-goals | PASS |
| Amount/currency/time semantics | CNY, Asia/Shanghai, ExactDecimal input bounds, high-precision local contexts, boundary intervals and canonical display rounding are explicit | PASS |
| Persistence parity matrix | Schema, transaction, concurrency, query, error and permitted-difference matrix is defined below and exercised by one test suite | PASS |
| Rollback | New Alembic revision can downgrade wealth read models and valuation inputs without modifying existing formal facts | PASS |

### Post-Design Re-check

The data model uses workspace-qualified identities/FKs, formal valuation observations and immutable result payloads; contracts are transport-neutral and all persistence differences are adapter-local. No constitution violation or complexity exception is required.

## Architecture Decisions

### Chosen Approach: Typed Pure Kernel + Versioned Facts + Fenced Read Model

Three approaches were assessed during the required product/architecture challenge:

| Approach | Completeness | Effort | Risk | Decision |
|---|---:|---:|---:|---|
| Minimal on-demand calculation from current snapshots/current market adapter | 3/10 | M | Critical | Rejected: cannot reconstruct historical boundaries, revision evidence, freshness or deterministic rebuilds |
| Typed pure calculation kernel plus formal valuation facts and a fenced append-only read model | 10/10 | XL | Medium | **Selected**: directly satisfies audit, aggregation, parity, rebuild and performance requirements while preserving boundaries |
| External analytical engine/event-sourcing platform | 9/10 | XL+ | High | Rejected: adds infrastructure and operational boundaries outside the current local product without improving specified outcomes |

The scope remains the approved A1 wealth core. API/Web/auth/connectors/AI are not added. Product challenge findings that materially affect correctness—formal historical valuation inputs, source fencing, evidence total ordering, reproducible dual-backend performance—are incorporated here and in the spec.

### Layering and Dependency Direction

```text
formal cash/investment facts + valuation observations
                         │
                         ▼
typed wealth fact/query ports ──────── relational adapters (workspace scoped)
                         │
                         ▼
                WealthChangeService
                         │
                         ▼
     pure daily calculation / aggregation / identities
                         │
                         ▼
 immutable results + components + evidence manifests
                         │
                         ▼
          fenced active-generation publication
```

- `ft.domain.wealth` owns immutable DTOs, enums, canonical values and stable application errors.
- `ft.domain.wealth_calculation` owns pure daily formulas, coverage/status propagation, aggregation, Dietz returns and canonical identity/hash functions.
- `ft.repositories.wealth` owns typed read/build ports; existing command repositories remain unchanged.
- `ft.application.wealth` owns validation, consistent-source capture, rebuild/query orchestration and evidence paging.
- `ft.adapters.relational.wealth_facts` reads only stable workspace/account/fact/valuation/revision identities; mutable `LedgerSnapshotModel` and current `MarketDataProvider` are not sources.
- Account-owned coverage keys are `(workspace_id, owner_account_id, identity_kind, identity)`. The adapter derives position ownership only from formal investment-event account IDs plus canonical ticker/position fields or explicit owned valuations; it never joins by account name or guesses from identity text. Shared quote/FX observations remain ownerless support inputs.
- `ft.adapters.relational.wealth_read_model` stores immutable generations/results/components/evidence and publishes one active manifest through a short fenced transaction.

### Input Truth and Valuation

The current cash check-in writes only a zero-amount cash fact and stores the exact balance in mutable snapshot state; the current market adapter returns unversioned present prices. Neither is reconstructible. The feature therefore adds `valuation_observations` as formal, revisioned inputs for cash/position check-ins, security/crypto quotes and FX rates. Account-owned cash/position observations carry an explicit same-workspace `owner_account_id`; shared instrument quotes and FX observations carry none. Cash check-in writes its exact balance observation and owner in the same command transaction; it never parses the human description. Position ownership comes only from the immutable `account_id` on a formal investment fact plus canonical ticker/position fields or an explicit owned valuation. It also adds append-only account lifecycle events because the current `active` flag cannot reconstruct opened/closed/reactivated intervals. The migration creates only deterministic `opened` events for existing accounts from `created_at`; it never guesses a historical close, position close or owner from current `active`, mutable snapshots, names or prefixes. Ownerless/conflicting account-owned inputs fail closed as unsupported with evidence. Tests and runtime wealth calculations consume only these formal inputs.

Valuation choice is deterministic: trusted boundary check-in first, then complete replay using facts plus usable observations, otherwise explicit partial/unsupported. Source revision is a canonical hash of the exact participating fact IDs/revisions/content digests, valuation identities/revisions and policy versions.

### Read Model and Publication State Machine

```text
capture immutable source watermark
              │
              ▼
       STAGING generation
        │             │
        │ failure     │ full retention index + validation
        ▼             ▼
  remains inactive   READY
                        │ fenced CAS / short transaction
                        ▼
                     ACTIVE
                        │ next valid generation
                        ▼
                    SUPERSEDED
```

- Build input reads first persist a content-addressed source manifest containing every participating or expected fact, valuation and lifecycle identity/revision/digest. Facts arriving mid-build are excluded and schedule the next build.
- Immutable rows may be written in short transactions using deterministic IDs; identical concurrent inputs converge on the same content.
- Final publish validates the full retention index and expected source/build versions, then uses PostgreSQL row locking or SQLite writer reservation plus compare-and-swap semantics.
- A builder whose expected active revision is stale receives `wealth.build_stale`; it cannot regress the pointer.
- A failure before publication leaves the previous active generation unchanged; a committed pointer switch is authoritative and immutable old results remain readable.

### Canonical Serialization and Precision

- Domain calculations use `Decimal` with a local precision sufficient for all multiplication/division chains; inputs still obey existing finite NUMERIC(38,18) bounds.
- Read-model calculation values are stored as canonical decimal strings/payload text plus SHA-256 digest so SQLite text and PostgreSQL JSON/NUMERIC behavior cannot round or reorder results.
- Canonical JSON is UTF-8, sorted keys, compact separators, no floats; amounts are non-exponent strings, timestamps are RFC 3339 with offset, and ordered collections follow contract-defined total order.
- Display rounding happens after the unrounded identity check. Visible rounding reconciliation is an `explained_other_adjustment` evidence contribution with reason `rounding`.

### Evidence Paging and Reconciliation

Evidence ordering is the total tuple `(occurred_at, source_identity, evidence_kind, evidence_identity)`. A cursor includes ordering version, immutable `component_id`, `result_revision` and last tuple; using it for a different result fails with `wealth.evidence_cursor_invalid`. Result-scoped evidence identity includes the immutable source identity plus contribution context. Repeated daily source evidence in a weekly/monthly result is folded deterministically, with monetary contributions summed exactly once per result scope; non-monetary gap evidence is retained with a null contribution. The folded monetary contributions reconcile exactly to the component amount or the explicit residual/coverage adjustment contract.

Direct fact contributions MUST reuse the immutable `WealthSourceManifestItem` row as their evidence source; rebuild MUST NOT duplicate every participating formal fact into a second evidence row and a third manifest-link row. A component evidence manifest binds its immutable source manifest plus canonical period/kind/fold predicates and stores only derived, aggregate-only, conflict, residual or gap evidence that has no direct source-manifest item. Paging resolves the frozen source items through those predicates and merges separately stored derived evidence under the same total order. This preserves historical evidence and exact reconciliation while keeping cold rebuild work proportional to one immutable input capture plus derived outputs.

### Performance Protocol

The benchmark fixture, seed, commands and query mix are documented in `quickstart.md`. Each backend runs in isolation. A cold sample resets/removes wealth generations and rebuilds then queries the fixed 366-day day series; a hot sample proves a valid active generation/revision hit before timing the same canonical query. Run 3 warmups and 20 measured samples, compute nearest-rank p95, record Python/OS/CPU/RAM/backend/database versions and fixture digest. Both backends must meet the same query budgets; backend operational differences are reported but cannot waive canonical parity or completion.

## PostgreSQL / SQLite Parity Matrix

| Area | Shared required behavior | PostgreSQL implementation | SQLite implementation | Permitted difference / proof |
|---|---|---|---|---|
| Schema | Same logical tables, workspace-qualified keys/FKs/checks, one Alembic head | Native constraints/indexes, JSON/text as selected | Equivalent constraints/indexes, canonical text payloads | Physical type/DDL spelling only; migration inspection tests |
| Decimal/time | Exact input bounds, canonical string results, UTC storage and Shanghai boundaries | NUMERIC inputs, timezone-aware timestamp | canonical text inputs, UTC-normalized timestamp | Driver representation only; canonical byte parity |
| Build snapshot | One immutable source watermark and consistent input set | Repeatable transaction/exported watermark or revision-bounded reads | Revision-bounded reads captured before build | Lock/snapshot mechanism may differ; mid-build-arrival test results match |
| Immutable writes | Deterministic content IDs, idempotent retries, no partial active view | Short transactions, uniqueness conflict normalization | Short writer transactions, deterministic uniqueness | Concurrency throughput differs; identical application result |
| Publish | Full-index validation, monotonic CAS, stale builder rejection | Row lock + conditional update | `BEGIN IMMEDIATE` + conditional update | Lock primitive/busy behavior differs; same stable error and visibility |
| Queries | Same breakdown/series/evidence ordering, nullable/known semantics and workspace isolation | SQL range/index queries | Equivalent SQL range/index queries | Query plans may differ; canonical DTO/evidence parity |
| Errors | Stable `wealth.*`/`storage.*` categories without raw credentials/path/payload | Normalize unique/deadlock/serialization/connection errors | Normalize unique/busy/readonly/connection errors | Native text/retryability differs and is adapter-local |
| Rebuild rollback | Failure never changes active manifest; old generations/evidence remain | Transaction rollback + inactive staging rows | Transaction rollback + inactive staging rows | Staging cleanup timing may differ; active contract identical |
| Performance | Both run fixed benchmark and meet cold/hot budgets | Record server/version/connection metadata | Record SQLite/version/file metadata | Operational explanation allowed; no skipped completion gate |

Automatic fallback, dual writes, shadow compare and implicit cross-backend migration remain forbidden.

## Error Contract and Observability

Stable application errors include `wealth.invalid_month`, `wealth.invalid_date_range`, `wealth.range_too_large`, `wealth.invalid_granularity`, `wealth.report_not_constructible`, `wealth.component_not_found`, `wealth.evidence_cursor_invalid`, `wealth.source_changed`, `wealth.build_stale`, `wealth.build_incomplete`, plus normalized existing storage categories. Domain/application errors contain safe codes and structured identity/revision metadata only.

Structured operational records cover build started/completed/failed/stale, captured source/build revisions, affected date range, counts, duration and cache hit/miss. They never contain database URL credentials, full SQLite path, account labels, raw fact payloads or evidence contents. Because this is a local core without a server/deployment topology, no uptime dashboard or remote alerting is introduced; tests assert safe structured fields and failures remain visible to callers.

## Project Structure

### Documentation (this feature)

```text
specs/003-wealth-attribution-core/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── wealth-query.md
│   ├── evidence.md
│   └── persistence.md
├── checklists/requirements.md
└── tasks.md
```

### Source Code (repository root)

```text
src/ft/
├── domain/
│   ├── wealth.py
│   └── wealth_calculation.py
├── application/
│   └── wealth.py
├── repositories/
│   └── wealth.py
├── adapters/relational/
│   ├── models.py
│   ├── wealth_facts.py
│   ├── wealth_read_model.py
│   └── runtime.py
└── runtime.py

migrations/versions/
└── 20260719_02_wealth_attribution.py

tests/
├── fixtures/wealth/
├── test_wealth_calculation.py
├── test_application_wealth.py
├── test_relational_wealth_contract.py
├── test_wealth_rebuild_concurrency.py
└── test_wealth_performance.py
```

**Structure Decision**: Extend the existing single Python package and neutral relational adapter. Typed wealth ports isolate the new read/query concern from legacy dict-based command repositories. One additive migration preserves the completed dual-database baseline and supports a clean wealth-only downgrade.

## Implementation Phases

1. Establish canonical domain DTOs/errors/serialization and failing golden/property tests.
2. Implement pure daily formulas, status/coverage semantics, Dietz return and daily-to-period aggregation.
3. Add typed ports, formal valuation/lifecycle facts and immutable source-manifest reads.
4. Add immutable read-model tables, build orchestration and fenced publication with failure/concurrency tests.
5. Compose the service, prove breakdown/series/evidence and cross-view invariants.
6. Run the shared SQLite/real PostgreSQL matrix, fixed performance protocol, converge, gstack review and full repository validation.

## Complexity Tracking

No constitution violations. The additional valuation and generation tables are required by current reconstruction, audit, atomic publication and performance requirements; they are not speculative infrastructure.
