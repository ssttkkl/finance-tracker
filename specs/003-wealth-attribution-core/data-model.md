# Data Model: Wealth Attribution Core

## Domain Values

### Wealth Status

`complete | stale | partial | unsupported`, ordered from least to most severe. Unexplained residual is a warning, not a status.

### Coverage Disposition

`supported | missing | unsupported | unvalued | not_applicable`. Fingerprint input is the sorted stable owned identity/disposition set selected for one workspace/source revision; the request date/range itself is not hashed. Account lifecycle can select `not_applicable`, while an unchanged selected identity/disposition set has the same fingerprint across adjacent dates.

### Component Kind

Fixed order: `external_cashflow`, `investment_return`, `fx_impact`, `liability_revaluation`, `explained_other_adjustment`, `unexplained_adjustment`.

## Formal Input Entities

### ValuationObservation

Revisioned formal input for cash balance, position quantity/value, market quote or FX rate.

| Field | Rule |
|---|---|
| workspace_id | Required partition key |
| observation_id | Stable content/source identity, workspace-qualified |
| identity_kind / identity | `cash_account`, account-owned `position`, shared `instrument_quote` or `currency_pair` |
| owner_account_id | Required same-workspace account FK for `cash_account`/`position`; null for shared quote/FX. For cash it equals `identity` |
| observation_kind | boundary_checkin, quantity_checkin, quote, fx |
| value / currency / unit | Canonical exact decimal string and explicit units |
| as_of | Aware instant for which value applies |
| observed_at | Aware instant source observed/published value |
| source_identity / source_revision | Stable provenance and revision |
| raw_record_id | Optional workspace-qualified raw provenance |
| trust | trusted_checkin or trusted_provider |
| created_at | UTC audit time, excluded from financial identity |

Unique identities include workspace and revision. Corrections append a new revision; they do not update old observations in place. An ownerless/conflicting account-owned observation is not eligible for complete attribution and must be surfaced as unsupported evidence; adapters never infer the owner from display names, identity prefixes, snapshots or current account state.

### OwnedCoverageIdentity

Canonical key `(workspace_id, owner_account_id, identity_kind, identity)` for account-owned cash and positions. Cash ownership is the account itself. Position ownership is established by an explicit position valuation or by a formal investment fact's immutable `account_id` plus canonical `from_ticker`/`to_ticker` or explicit position identity. The identity enters the expected universe at the earliest owning fact/observation while its owner account is lifecycle-applicable. No position close is guessed: a completely replayed zero position remains supported at zero; absent replay/valuation is missing, and only the owning account lifecycle makes it `not_applicable`.

If two accounts claim the same unscoped position identity, they remain distinct owned keys. If one valuation claims an owner inconsistent with its formal facts, or an owner is absent/cross-workspace, the affected owned identity is `unsupported` with deterministic `OWNERSHIP_CONFLICT` or `OWNERSHIP_MISSING` evidence. Shared instrument quotes and FX pairs have no owner and only support valuation of owned identities; they are not independently counted in coverage.

### WealthAccountFact

Typed projection of existing account facts for calculation: stable account ID, type, currency, metadata and lifecycle events. Account name is display-only. Applicability comes from append-only opened/closed/reactivated facts; the current `active` flag is not a historical source.

### AccountLifecycleEvent

Workspace/account-qualified append-only `opened | closed | reactivated` fact with effective time, source identity/revision, reason and audit time. Ordered events form non-overlapping applicable intervals; invalid transitions fail closed.

Migration backfill creates a deterministic `opened` event at existing `AccountModel.created_at`. It does not synthesize `closed` from the current `active` flag or `updated_at`; without an explicit close event the historical identity remains applicable and missing data is surfaced honestly.

### CashflowFact / InvestmentFact

Typed projections of existing formal facts including workspace/account/fact identity, occurred_at, exact values/payload, raw source identity and record revision digest. They are read-only to the wealth service.

## Calculation Entities

### DailyWealthPoint

Identified by the content key `(workspace_id, local_date, calculation_version, valuation_policy_version, source_revision)` and immutable result digest. Contains unrounded canonical financial values, nullable complete values, known values, status/freshness/warnings, coverage fingerprint, components and build-independent result revision. `build_revision` belongs to the generation index rather than changing identical point content.

### AttributionComponent

- `component_key = hash(workspace + period_start + period_end + granularity + kind + grouping_key)`
- `result_revision = hash(calculation_version + valuation_policy_version + source_revision + canonical result content)`
- `component_id = hash(component_key + result_revision)`

The top-level result has exactly six kinds when complete. Known components use an explicit known grouping scope.

### EvidenceItem

Immutable result-scoped record with evidence identity, source identity/revision, kind, occurred_at, optional exact contribution, safe metadata and provenance reference. Ordering is total. Gap evidence has null contribution. Result-scoped folding retains one canonical row per fold identity and sums monetary contributions.

### CoverageDisposition

One identity/date/source-revision row with lifecycle applicability and disposition. The sorted set hashes to coverage fingerprint.

## Persistence Entities

### WealthSourceManifest / WealthSourceManifestItem

Content-addressed immutable build input. Items enumerate every participating or expected cash/investment fact, valuation observation and account lifecycle fact with identity, revision, content digest and the canonical evidence projection required for stable paging (`occurred_at`, evidence kind, optional contribution and safe grouping metadata). The sorted canonical item set hashes to `source_watermark/source_revision`. A build may read only items in its manifest. Direct evidence reuses these rows rather than copying them.

### WealthGeneration

| Field | Rule |
|---|---|
| workspace_id + build_revision | Primary immutable identity |
| source_watermark / source_revision | Captured before calculation |
| calculation/valuation versions | Required cache validity inputs |
| date_from/date_to | Full indexed retention interval |
| expected_active_revision | CAS fence captured at start |
| state | staging, ready, active, superseded; immutable history except validated state transitions |
| canonical manifest digest | Proves index completeness/content |
| created/completed time | Audit metadata |

### WealthGenerationDay

Maps every expected local date in one generation to an immutable DailyWealthPoint result digest or an explicit missing-day marker. Unique on workspace/build/local date. Full coverage is required before publication.

### WealthDailyResult

Content-addressed canonical point payload and digest. Shared by generations when unchanged. Workspace-qualified and append-only.

### WealthComponentResult

Content-addressed canonical component payload, amount/status and evidence manifest identity. Append-only.

### WealthEvidenceManifest / WealthEvidenceItem / ManifestItem

Append-only evidence structures. A manifest binds an immutable source-manifest identity plus canonical period/kind/fold predicates. `WealthEvidenceItem`/`ManifestItem` store only derived, aggregate-only, conflict, residual or gap evidence without a direct source-manifest row. Paging merges selected source-manifest items and derived items under one total order; aggregate reconciliation is checked against their folded contributions.

### WealthCoverageDisposition

Content-addressed per-result coverage universe rows, indexed by workspace/date/source revision and identity.

### WealthActiveManifest

One mutable row per workspace: active build revision, monotonic manifest revision and updated time. Publication condition requires current manifest revision/build to match the builder's expected fence and the candidate generation to be complete and not older than the active source watermark.

## Relationships and Invariants

- Every owned key, unique constraint, FK and query includes `workspace_id`; account-owned valuation/coverage keys also include `owner_account_id` and reference the same-workspace account.
- Active manifest points to one complete generation; generation points to a full ordered date index; date index points to immutable daily results.
- Daily results reference immutable components; components reference immutable evidence manifests; old references remain valid after rebuild.
- Complete identity and known identity are independently checked at full internal precision.
- Series envelope source revision hashes ordered daily source revisions; it is not a daily source revision.
- Canonical payload text and digest are checked on write/read; database-native JSON ordering is never authoritative.

## State Transitions

```text
valuation/fact revision appended -> new source watermark
new build -> staging -> ready -> active -> superseded
                         └ failure/stale -> inactive (never active)
```

Only the publish transaction changes `WealthActiveManifest`. A stale CAS or incomplete index fails closed. Old source manifests, results and evidence are never mutated or automatically garbage-collected in this feature.
