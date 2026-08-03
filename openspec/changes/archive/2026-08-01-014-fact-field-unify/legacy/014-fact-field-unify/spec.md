# Feature Specification: Fact Field Unification

**Feature Branch**: `014-fact-field-unify`

**Created**: 2026-07-24

**Status**: Complete

**Input**: User description: "统一消费账户记录（cash_transactions）与投资账户记录（investment_events）的建模风格与字段名；业务含义相同的字段使用相同名称。对齐表结构（正式列 + 可选 payload），保留两张事实表，不合并为单 ledger 表。背景：消费侧为宽表；投资侧为窄表 + JSON payload，且 `kind` 列实际存 action，与历史文档漂移。"

**Context**: Flow-Forward feature. Extends persistence and export contracts established by `001`–`005` (accounts / cash facts), `009`–`013` (investment import and cash-like investment actions). Does **not** supersede wealth attribution semantics (`003`), relation kinds (`006`–`008`), or uSmart/cost rules (`011`–`013`) beyond requiring field identity and storage shape consistency for shared concepts.

**Product stage**: Early development. Delivery is a **one-shot schema and contract cutover** with **no long-term compatibility layer** (no dual columns, dual-write, dual-read, retired-name public aliases, or shim code left in tree after the feature completes).

## Clarifications

### Session 2026-07-24

- Q: 现金侧 memo 正式字段名如何落地？ → A: 两侧正式名均为 **`note`**；**表结构必须改**：`cash_transactions.description` 物理重命名为 `note`；投资侧正式列为 `note`（由 payload 提升）。读/写、formal 契约与 **public 导出**均使用 `note`。
- Q: 投资侧 `kind` 列如何变成 `action`？ → A: **物理 rename `kind` → `action`**；完成后无双列、无 `kind` 残留列。语义固定为投资事件 action；资产类别仅来自 `accounts.type`。
- Q: 投资核心字段升列后 payload 如何处理？ → A: 升列后从 payload **删除**已提升的核心键；**不双写**。完成后 payload 仅可含非核心扩展键（可无扩展则为空对象）；读路径只信正式列。
- Q: Public/导出层字段名策略？ → A: **一并改** public 列表/CSV 表头为 Shared Catalog 名；测试与文档同步；**不**保留长期旧表头别名。
- Q: 迁移时列与 payload 对 action 等核心字段不一致怎么办？ → A: **失败关闭**（迁移/升级中止），报告冲突事实标识；禁止静默选边或自动“修好”账务语义。
- Q: 产品阶段与兼容策略？ → A: **前期开发 + 一次性完成迁移**；完成后 **不留兼容代码**（无 shim、无双路径读旧列名/旧 JSON 核心键、无 deprecated 导出头）。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Shared fact vocabulary is explicit and stable (Priority: P1)

As a maintainer or integrator, I can look at cash and investment formal facts and see the **same names** for the same business concepts (workspace isolation, account ownership, occurrence time, currency, revision, provenance link, free-text note). Investment-only legs (from/to tickers and amounts, commission, price) are first-class formal fields. Cash-only fields (category, counterparty, offset metadata, soft-delete) stay cash-only.

**Why this priority**: Synonym sprawl (`date` vs `occurred_at`, `description` vs `note`, `kind` vs `action`, core data only in JSON) is the problem this feature exists to end.

**Independent Test**: Write one cash fact and one investment `swap` plus one cash-like investment event (`deposit` or `fee`). Inspect storage and catalog: shared fields share names; investment core legs are columns; cash has no `description` column; investment has `action` not `kind`.

**Acceptance Scenarios**:

1. **Given** the Shared Fact Field catalog, **When** cash and investment facts are written via normal product paths, **Then** every applicable Shared field is stored and exposed under the same canonical name on both kinds.
2. **Given** investment `swap` and cash-like investment actions, **When** persisted, **Then** `action`, `currency`, legs, price/commission as applicable, and `note` are formal columns—not recoverable only by scraping JSON.
3. **Given** a cash fact, **When** persisted, **Then** cash-only attributes remain; formal memo column is **`note`**; **`description` does not exist** after cutover.
4. **Given** cutover complete, **When** inspecting investment schema and writers, **Then** the event action column is only **`action`**; no `kind` column remains; asset class is not re-encoded on the event row.

---

### User Story 2 - One-shot upgrade preserves accounting outcomes (Priority: P1)

As a ledger owner on an early-stage book, after the **single** upgrade, projected cash balances and investment positions/costs match the pre-upgrade baseline for the same inputs. Provenance and idempotency hold. I do not keep running old and new code paths.

**Why this priority**: Constitution I — no silent re-accounting; early stage still requires exact money outcomes, just without multi-version compatibility machinery.

**Independent Test**: Golden fixture (cash + investment actions including `swap`, funding, dividend, and `fee`/`ipo` when in fixtures). Snapshot projections → one-shot migrate → re-project → exact equality; conflict rows fail the migration; dual backend match.

**Acceptance Scenarios**:

1. **Given** a mixed workspace, **When** one-shot upgrade to the unified model completes, **Then** projected cash pockets and investment positions/costs match pre-upgrade baselines exactly.
2. **Given** investment cores previously only in payload, **When** upgraded, **Then** cores exist as columns, cores are **absent** from residual payload, and projection matches baseline.
3. **Given** `raw_record_id` links, **When** upgraded, **Then** provenance identities unchanged; re-import does not duplicate formal facts.
4. **Given** soft-deleted cash facts, **When** upgraded, **Then** default exclusion and include-deleted behavior unchanged.
5. **Given** a row whose legacy column value disagrees with payload on a promoted core field, **When** migration runs, **Then** upgrade **fails closed** with identifying error; no partial workspace cutover and no silent winner.
6. **Given** PostgreSQL and SQLite, **When** the same fixture is upgraded, **Then** financial outcomes and fact counts match across backends.
7. **Given** feature complete, **When** reviewing the codebase and schema, **Then** there is no remaining compatibility path that reads `description`, investment `kind`, or core investment fields from payload for normal operation.

---

### User Story 3 - Single public vocabulary (Priority: P1)

As a CLI/report consumer, list/export of cash and investment facts uses **catalog field names only** (including `note`, `occurred_at` as formal time). Domain-specific names remain domain-specific (`category` vs `action` + legs). No synonym table is required.

**Why this priority**: Storage-only rename with old public headers would reintroduce the same confusion.

**Independent Test**: Export/list cash and investment after cutover; headers/keys match catalog; no `description` / investment `kind` / payload-core leakage in public rows.

**Acceptance Scenarios**:

1. **Given** post-cutover list/export, **When** reading occurrence time and currency, **Then** both fact kinds use catalog names (`occurred_at`, `currency`).
2. **Given** free-text memos on cash and investment, **When** exported, **Then** both use **`note`**.
3. **Given** historical callers expecting `description` or payload-shaped investment rows, **When** this feature completes, **Then** those callers/tests **must be updated** in-repo; the product does not ship a long-term alias layer.
4. **Given** relations and wealth loaders, **When** cutover completes, **Then** matching and attribution outcomes for the same book remain equivalent; only mechanical field renames/adapters are in scope.

---

### Edge Cases

- Non-core payload keys: retained in residual payload only; promoted cores **stripped**.
- Sparse legs (deposit/`fee`/`ipo` one-sided): empty/null formal legs preserve prior projection semantics.
- Column vs payload disagreement on any promoted core: **fail closed** (no automatic winner).
- Empty string vs null for optional text: normalize per plan rules so both backends agree.
- No fake symmetry: investment does not grow cash offset/soft-delete columns.
- Upgrade is atomic per workspace (or whole migration unit): no half-promoted completed state.
- Dialect differences limited to existing type representations (e.g. exact decimal storage), not names or nullability.
- After cutover, production code paths MUST NOT branch on “old shape vs new shape.”

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST keep **two** formal fact kinds (cash and investment) and MUST NOT merge into one physical ledger table.
- **FR-002**: System MUST publish a Shared Fact Field catalog (canonical name, meaning, nullability, applicability).
- **FR-003**: Shared fields MUST include at least: `id`, `workspace_id`, `account_id`, `raw_record_id`, `occurred_at`, `currency` (when applicable), `note`, `revision`, `created_at`.
- **FR-004**: Investment formal facts MUST store as columns: `action` (including living set `swap`, `deposit`, `withdraw`, `dividend`, `checkin`, `fee`, `ipo`, …), `from_ticker`, `from_amount`, `to_ticker`, `to_amount`, `price`, `commission`, `commission_asset`, `note`.
- **FR-005**: Cash formal facts MUST retain cash-domain columns required by accepted behavior, with memo stored only as **`note`** (physical rename from `description`): `amount`, `category`, `counterparty`, `note`, `record_id`, `source`, `bill_source`, `transfer_account`, `locked`, offset_*, `proposed_action`, soft-delete markers.
- **FR-006**: Investment `payload` MAY remain for **non-core extension keys only**. After cutover it MUST NOT contain promoted core keys (action, currency, legs, price, commission, commission_asset, note, date/time mirrors, or duplicates of shared formal fields). Empty object when no extensions. Readers of core data MUST use columns only.
- **FR-007**: System MUST **physically rename** investment `kind` → **`action`**; completed schema has no `kind` column. Asset class comes from `accounts.type` only.
- **FR-008**: System MUST one-shot migrate existing rows: populate investment formal columns from prior payload/legacy columns; strip promoted keys from payload; rename cash `description` → `note`; preserve projected financial outcomes when source data is consistent.
- **FR-009**: System MUST update formal contracts **and public list/CSV field names** to catalog names end-to-end. Retired names are removed from product surfaces; in-repo tests/fixtures update in this feature.
- **FR-010**: Writers MUST validate investment actions/legs per living `009`/`013` rules; invalid writes fail closed.
- **FR-011**: Import idempotency and provenance uniqueness constraints MUST remain.
- **FR-012**: Schema and behavior MUST be proven on **PostgreSQL and SQLite**.
- **FR-013**: Relations, wealth, and importers keep equivalent outcomes aside from mechanical field renames.
- **FR-014**: Artifacts MUST include old→new field mapping and non-goals.
- **FR-015**: Cash column `description` MUST be physically renamed to `note` on both backends; completed state has no `description` column.
- **FR-016**: On promoted-core conflicts (legacy column vs payload), migration MUST **fail closed** and report fact identity; no silent resolution.
- **FR-017**: After feature completion, the codebase MUST NOT retain compatibility shims for pre-unification shapes (no dual-read of old column names, no dual-write, no public alias headers for retired names, no “if payload has action else column” core readers).

### Key Entities

- **Shared Fact Field Catalog**: Canonical names and applicability.
- **Cash Formal Fact**: `cash_transactions` (post-cutover columns per catalog).
- **Investment Formal Fact**: `investment_events` with formal action/legs + optional non-core payload.
- **Account**, **Raw Record / Import Batch**, **Projection Snapshot**: unchanged ownership/provenance roles; projection baselines used for SC-002.

### Non-Goals

- Single physical ledger table or universal polymorphic fact API.
- Redesign of relation kinds, review UX, or wealth formulas.
- Changing investment action **business meaning** beyond storage/naming (`013` semantics stay).
- Long-term compatibility / dual-stack / deprecated public field aliases.
- Fake column symmetry (cash columns on investment “for consistency”).
- Cross-backend data replication product.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of Shared catalog fields use the same canonical name on both fact kinds in storage and public/formal read contracts.
- **SC-002**: Golden fixture projections after one-shot upgrade match pre-upgrade baselines with zero money/share delta (when source data is consistent).
- **SC-003**: 100% of golden investment rows have formal `action` (and required `currency`) as columns; promoted cores absent from payload.
- **SC-004**: PostgreSQL and SQLite matrices match on financial totals and fact counts for the same fixture.
- **SC-005**: Re-import of an already-imported source does not increase formal fact counts.
- **SC-006**: Mapping review: every retired synonym is gone from storage and public contracts (not merely aliased).
- **SC-007**: Static/repo check (or equivalent review evidence): no remaining production readers/writers for cash `description`, investment `kind`, or core investment fields exclusively from payload.
- **SC-008**: Injected conflict fixture causes migration failure with actionable identity; no partial successful cutover for that unit.

## Assumptions

- Two tables retained; columns + optional residual payload (investment only unless plan finds a cash need—which is out of default scope).
- Formal and public time field name is **`occurred_at`** (not a long-lived public `date` synonym). Display formatting of timestamps is allowed; the field name stays `occurred_at`.
- Formal and public memo field name is **`note`** on both kinds.
- Investment action column is **`action`** via rename from `kind`.
- Cash stays amount+category shaped; investment stays from/to leg shaped.
- Early-stage books may be rebuilt from imports if needed operationally, but the feature still requires **exact projection parity** for migratable consistent data and fail-closed on conflicts—not “wipe and ignore.”
- No multi-release compatibility window: one feature lands the final shape.

## Field Catalog (normative draft)

### Shared (same name, both fact kinds)

| Canonical name  | Meaning                                            |
|-----------------|----------------------------------------------------|
| `id`            | Formal fact identity                               |
| `workspace_id`  | Workspace isolation                                |
| `account_id`    | Owning account                                     |
| `raw_record_id` | Provenance (null if manual)                        |
| `occurred_at`   | Event/transaction time (UTC-aware)                 |
| `currency`      | Settlement/reporting currency when applicable      |
| `note`          | Free-text memo                                     |
| `revision`      | Revision counter                                   |
| `created_at`    | System insert time                                 |

### Cash-only

`amount`, `category`, `counterparty`, `record_id`, `source`, `bill_source`, `transfer_account`, `locked`, `offset_group`, `offset_role`, `offset_strength`, `offset_source`, `offset_rule_hint`, `offset_match_type`, `proposed_action`, `deleted_at`, `deleted_by`, `delete_reason`

### Investment-only

`action`, `from_ticker`, `from_amount`, `to_ticker`, `to_amount`, `price`, `commission`, `commission_asset`, optional residual non-core `payload`

### Explicit renames / retirement (no long-term alias)

| Legacy | End state |
|--------|-----------|
| Cash column/export `description` | Column + public field **`note`** |
| Cash/public `date` as primary time key | **`occurred_at`** |
| Investment column `kind` | **`action`** (rename) |
| Investment payload cores (`action`, legs, price, commission*, `note`, `date`, …) | Formal columns; **removed from payload** |
| Investment payload `account_name` | Export/join via account; not ownership source of truth; not required in residual payload |

## Dependencies

- Dual-database runtime and shared ORM models.
- Investment projection/validation from `009`–`013`.
- Cash import, soft-delete, relations consumers.
- Wealth fact loaders: mechanical switch to formal investment columns without formula changes.
