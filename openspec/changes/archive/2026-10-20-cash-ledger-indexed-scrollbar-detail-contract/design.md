## Context

See `proposal.md` for the motivation and `specs/cash-ledger/cash-ledger-browser/spec.md` for the observable contract.

The shared Compose ledger currently uses `LazyColumn`, Paging Compose, and a `CashProjectionPagingSource` keyed by forward-only cursor strings. The response has no exact total count or absolute start index. The server already applies a stable `(occurred_at DESC, projection_id DESC)` order and binds cursor reads to workspace, filters, and projection version. The ledger groups visible rows by month, so visual list-item offsets do not equal transaction ordinals. The scrollbar bubble displays only the target month; it must not wait for that month's row page to load.

The cash-record detail, candidate, and mutation responses serialize record, component, cash-transaction, and relation-level component identifiers as JSON numbers; the relation endpoint `component_id` remains a JSON string for compatibility. The projection-evidence response has a separate established contract: root, member, and relation-endpoint record IDs are decimal JSON strings, while component IDs and cash-transaction IDs are JSON numbers. Some Compose DTOs declare numeric IDs as `String`, which makes Kotlin serialization fail. The backend also returns a category ID without the category path and includes component-level relation fields that the detail DTO does not model.

## Goals / Non-Goals

**Goals:**

- Let users drag to any ordinal in the full current filtered result on shared Compose Web, Android, and iOS presentation at Compact, Regular, and Wide window sizes.
- Keep the existing stable order, filter semantics, monthly summaries, list details, and automatic loading behavior.
- Make indexed reads snapshot-consistent and equivalent on SQLite and PostgreSQL.
- Decode complete detail and related-record payloads without losing numeric IDs, category paths, component identifiers, or exact relation amounts.
- Keep the rail discoverable on touch devices while preventing it from obscuring transaction content.

**Non-Goals:**

- Changing transaction sort order, filters, month summaries, record grouping, or financial calculations.
- Seeking by estimated pixel height or changing the row layout to make every item equal height.
- Adding a database table, persistent ordinal index, schema migration, or a backend dependency.
- Redesigning the detail surface beyond displaying the category data it already promises and preserving the existing related-record presentation.

## Decisions

### 1. Use an additive positional-read mode alongside cursor pagination

Extend the existing cash-projection page endpoint with an indexed request mode. A request with `start_index` and `limit` returns the page, `start_index`, exact `total_count`, `items_before`, `items_after`, and `projection_version`. Its initial versionless response also returns `month_positions`: one entry per month in the requested timezone, each with a zero-based inclusive `start_index` and exclusive `end_index` in the same stable filtered ordering. The ranges are contiguous, non-overlapping, and cover `[0, total_count)`; an empty result returns no ranges. Subsequent indexed reads include the version from the first response and do not repeat the full-result monthly aggregation. `start_index` cannot be combined with `cursor`; existing cursor requests retain their current behavior and response semantics for old clients.

All filters, workspace scoping, visibility rules, and projection-version checks are applied before counting or selecting rows. Positional reads use the existing stable descending order `(occurred_at, projection_id)`. The adapter performs a count query and a bounded `OFFSET/LIMIT` page query over the same predicate. During the existing initial monthly-summary scan, it also counts filtered records by their local month and derives each month's ordinal range in descending month order; no additional full-row scan is required. Month ranges and summaries are tied to the same workspace, filters, timezone, and projection version as the indexed page. No database state is mutated. If the offset query misses the existing 10K performance budget, optimize the relational query before adding persistent indexes or a materialized ordinal structure.

**Alternative considered:** sequentially fetch cursors until reaching the target. This would make drag latency proportional to the target position and cannot satisfy arbitrary fast jumps.

### 2. Let Paging Compose represent unloaded transaction positions

Replace the Compose ledger's cursor-only page key with a positional page key. The `PagingSource` maps the server's `start_index` and `total_count` to `itemsBefore` and `itemsAfter`, enables placeholders, and requests rows around the viewport's ordinal. The first read establishes the projection version; later reads in that PagingSource carry it. Refresh keys preserve the current anchor within the same version. A stale-version response invalidates the source and reloads the active filter from a clean snapshot.

The paging page size remains within the backend limit and uses `PagingConfig` load sizes explicitly. Indexed reads load both preceding and following pages as the user seeks; accessing the trailing placeholder continues to load adjacent pages without a separate “load more” control. Duplicate-ID global filtering is removed from the position-based source because absolute page ranges, stable ordering, and snapshot version provide the no-duplicate contract; tests cover overlapping or stale responses.

### 3. Map scroll progress to transaction ordinals, not LazyColumn pixels

Use `fastscroller-compose-multiplatform`'s Material 3 fast-scroll interaction and indicator slot after verifying it against this repository's Kotlin, Compose Multiplatform, Android, iOS, JS, and Wasm targets. Supply a custom adapter that maps the scrollbar fraction to `round(progress * (totalCount - 1))`; the library's generic pixel/row adapter is not authoritative for this variable-height, month-grouped Paging list. Keep the visual thumb thin and its pointer/touch target wide. Month labels, monthly summary content, headers, and variable line wrapping do not contribute to the denominator. Resolve the drag target ordinal against cached `month_positions` ranges with binary search and show only that month in the indicator, without waiting for the target row page. When a target placeholder is reached, Paging loads its page and the list scrolls to that transaction.

During a drag, keep the thumb and month indicator responsive while conflating/throttling intermediate target requests. On release, submit the final ordinal without throttling and settle the list on that record. The scrollbar reports an accessible progress value and supports keyboard adjustment as well as pointer/touch dragging. Use `FinanceTheme` / `MaterialTheme` color roles for track and thumb; do not add local palette values or text that duplicates the current filter or result count. In Compact, render the scrollbar over the list without reserving a trailing content lane. It starts hidden, becomes visible while the list scrolls or the thumb is dragged, and fades out after both interactions have been idle for 2 seconds; once hidden, it must not intercept list input. Regular and Wide keep the persistent scrollbar and reserve its trailing lane so it does not cover record text or actions. Clear cached ranges when filters change; replace them when a new initial response establishes another projection version.

**Hallmark direction and self-check:** quiet, utility-first Material 3 treatment integrated with the ledger surface; one narrow rail, a clearly distinguishable thumb, and no new card, count banner, or floating controls. In Compact the rail overlays the list only while it is in use and fades after 2 seconds of inactivity; the hidden state cannot steal list input. Regular/Wide retain a persistent rail. Review the transition, thumb contrast, drag target, keyboard semantics, and content overlap in all three window classes. The existing list remains the primary surface; the rail acts only as a navigation affordance.

### 3.1 Vendor the FastScroller source required by the ledger

The pinned `fastscroller-compose-multiplatform` 0.3.2 Material 3 artifact compiles, but its separate Kotlin/Wasm module cannot access the `MaterialTheme` backing field at runtime in the current Compose toolchain. Vendor the upstream common drag, slider, scrollbar, indicator, and Material 3 style source into `compose/shared/src/commonMain` so these calls compile in the same module as `FinanceTheme`. Keep the upstream package names and copyright headers, copy the full Apache 2.0 `LICENSE` to `compose/shared/third_party/fastscroller/LICENSE`, and document provenance and local changes in `NOTICE.md`.

Only `ScrollbarAdapter` is narrowed to a regular common interface because the ledger supplies its own ordinal adapter and does not use upstream platform adapters. The copied Material 3 style resolves theme roles in `shared` and passes `Color` values directly; no `toArgb` conversion or independent theme implementation is added. Remove the external core/indicator dependencies after the local source compiles on Android, iOS, JS, and Wasm.

### 4. Align response DTOs at the network boundary

Preserve each endpoint's existing identifier contract. Cash-record detail, candidate, create/update, and relation-operation DTOs use numeric JSON IDs for records, components, cash transactions, and relation component fields; their relation endpoint `component_id` remains a string. Evidence DTOs keep root/member/relation-endpoint record IDs as decimal JSON strings and use numeric JSON IDs for components and cash transactions. Compose DTOs reflect those wire types and map IDs losslessly to the domain's string identifiers. Add component-level `component_id`, `primary_component_id`, and `secondary_component_id` to the shared record/relation DTOs and domain objects, with `CashRelationDto` using numeric values for the two relation fields. Keep `applied_amount` as a decimal string end-to-end; never route it through floating point.

Add the complete nullable category object (ID, name, and ordered path) to record-detail and related-record wire responses using the workspace's category records. Continue returning `category_id` for compatibility. The category lookup joins only requested leaf categories to their ancestors using the stored `category_path`, avoiding a workspace-wide category scan on each detail or write response. The existing detail renderer then displays the already-intended category path. Shared Compose serialization tests cover detail, relation candidates, and every read/write endpoint that returns the same DTO; backend response tests assert JSON types and values.

**Alternative considered:** change backend IDs to JSON strings. Rejected because existing backend contracts and tests already expose numeric IDs; aligning Compose at the boundary preserves the established server contract and domain API.

### 5. Keep API rollback additive

No schema migration is needed. Existing cursor-only clients continue to use the legacy mode. Deploy the backend's additive indexed mode and response fields before a Compose client that requires them. Rolling back the Compose client returns to cursor pagination; rolling back the backend requires rolling the new client back as well.

## Risks / Trade-offs

- **Exact count and deep offsets can be costly** → run the existing 10K performance matrix and add 100K-scale query checks where the fixtures permit; retain stable indexed ordering, count only matching visible rows, and optimize the query before adding persistent ordinal state.
- **Records can change while a user drags** → bind every seek to the same projection version and filter; treat the server's stale-version response as an invalidation and reload, never merge pages from two snapshots.
- **Rapid drag can create excess requests** → keep only the latest in-flight target, throttle intermediate seeks, cancel superseded reads where supported, and always issue one exact release seek. The month bubble is resolved locally from the first response's cached ordinal ranges, independently of row-page loading.
- **Touch rail can steal list gestures or obscure content** → QA its edge lane and target in Compact/Regular/Wide, including row actions, text, screen reader semantics, and keyboard operation.
- **DTO expansion can break serializers or write response handling** → add request/response JSON contract tests before changing mappers; exercise all endpoints sharing the DTO, including successful writes whose response is parsed after commit.
- **Backend query differences can drift** → execute the same contract matrix on SQLite and a real dedicated PostgreSQL test database.
