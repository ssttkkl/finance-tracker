# Fact Field Unification

## Purpose
User description: "统一消费账户记录（cash_transactions）与投资账户记录（investment_events）的建模风格与字段名；业务含义相同的字段使用相同名称。对齐表结构（正式列 + 可选 payload），保留两张事实表，不合并为单 ledger 表。背景：消费侧为宽表；投资侧为窄表 + JSON payload，且 `kind` 列实际存 action，与历史文档漂移。 本能力的行为契约由迁移后的需求与场景持续维护。

## Requirements

### Requirement: Shared fact vocabulary is explicit and stable
系统 MUST As a maintainer or integrator, I can look at cash and investment formal facts and see the **same names** for the same business concepts (workspace isolation, account ownership, occurrence time, currency, revision, provenance link, free-text note). Investment-only legs (from/to tickers and amounts, commission, price) are first-class formal fields. Cash-only fields (category, counterparty, offset metadata, soft-delete) stay cash-only.。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: One-shot upgrade preserves accounting outcomes
系统 MUST As a ledger owner on an early-stage book, after the **single** upgrade, projected cash balances and investment positions/costs match the pre-upgrade baseline for the same inputs. Provenance and idempotency hold. I do not keep running old and new code paths.。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: Single public vocabulary
系统 MUST As a CLI/report consumer, list/export of cash and investment facts uses **catalog field names only** (including `note`, `occurred_at` as formal time). Domain-specific names remain domain-specific (`category` vs `action` + legs). No synonym table is required.。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 功能需求基线
系统 MUST 保持以下迁移前功能需求；后续行为变更必须通过 OpenSpec change 明确修改。

- - **FR-001**: System MUST keep **two** formal fact kinds (cash and investment) and MUST NOT merge into one physical ledger table.
- - **FR-002**: System MUST publish a Shared Fact Field catalog (canonical name, meaning, nullability, applicability).
- - **FR-003**: Shared fields MUST include at least: `id`, `workspace_id`, `account_id`, `raw_record_id`, `occurred_at`, `currency` (when applicable), `note`, `revision`, `created_at`.
- - **FR-004**: Investment formal facts MUST store as columns: `action` (including living set `swap`, `deposit`, `withdraw`, `dividend`, `checkin`, `fee`, `ipo`, …), `from_ticker`, `from_amount`, `to_ticker`, `to_amount`, `price`, `commission`, `commission_asset`, `note`.
- - **FR-005**: Cash formal facts MUST retain cash-domain columns required by accepted behavior, with memo stored only as **`note`** (physical rename from `description`): `amount`, `category`, `counterparty`, `note`, `record_id`, `source`, `bill_source`, `transfer_account`, `locked`, offset_*, `proposed_action`, soft-delete markers.
- - **FR-006**: Investment `payload` MAY remain for **non-core extension keys only**. After cutover it MUST NOT contain promoted core keys (action, currency, legs, price, commission, commission_asset, note, date/time mirrors, or duplicates of shared formal fields). Empty object when no extensions. Readers of core data MUST use columns only.
- - **FR-007**: System MUST **physically rename** investment `kind` → **`action`**; completed schema has no `kind` column. Asset class comes from `accounts.type` only.
- - **FR-008**: System MUST one-shot migrate existing rows: populate investment formal columns from prior payload/legacy columns; strip promoted keys from payload; rename cash `description` → `note`; preserve projected financial outcomes when source data is consistent.
- - **FR-009**: System MUST update formal contracts **and public list/CSV field names** to catalog names end-to-end. Retired names are removed from product surfaces; in-repo tests/fixtures update in this feature.
- - **FR-010**: Writers MUST validate investment actions/legs per living `009`/`013` rules; invalid writes fail closed.
- - **FR-011**: Import idempotency and provenance uniqueness constraints MUST remain.
- - **FR-012**: Schema and behavior MUST be proven on **PostgreSQL and SQLite**.
- - **FR-013**: Relations, wealth, and importers keep equivalent outcomes aside from mechanical field renames.
- - **FR-014**: Artifacts MUST include old→new field mapping and non-goals.
- - **FR-015**: Cash column `description` MUST be physically renamed to `note` on both backends; completed state has no `description` column.
- - **FR-016**: On promoted-core conflicts (legacy column vs payload), migration MUST **fail closed** and report fact identity; no silent resolution.
- - **FR-017**: After feature completion, the codebase MUST NOT retain compatibility shims for pre-unification shapes (no dual-read of old column names, no dual-write, no public alias headers for retired names, no “if payload has action else column” core readers).

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：对该能力进行修改时，验证结果 MUST 覆盖迁移后的功能需求清单。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 可度量验收结果
系统 MUST 继续满足以下可度量结果；它们是迁移后的验收回归基线。

- - **SC-001**: 100% of Shared catalog fields use the same canonical name on both fact kinds in storage and public/formal read contracts.
- - **SC-002**: Golden fixture projections after one-shot upgrade match pre-upgrade baselines with zero money/share delta (when source data is consistent).
- - **SC-003**: 100% of golden investment rows have formal `action` (and required `currency`) as columns; promoted cores absent from payload.
- - **SC-004**: PostgreSQL and SQLite matrices match on financial totals and fact counts for the same fixture.
- - **SC-005**: Re-import of an already-imported source does not increase formal fact counts.
- - **SC-006**: Mapping review: every retired synonym is gone from storage and public contracts (not merely aliased).
- - **SC-007**: Static/repo check (or equivalent review evidence): no remaining production readers/writers for cash `description`, investment `kind`, or core investment fields exclusively from payload.
- - **SC-008**: Injected conflict fixture causes migration failure with actionable identity; no partial successful cutover for that unit.

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：运行该能力的验收矩阵时，结果 MUST 满足迁移后的成功标准。
- THEN 系统满足该条件，并保留可复核的验证证据。

## Source
完整迁移来源与原始验证证据：[014-fact-field-unify/spec.md](../../changes/archive/2026-08-01-014-fact-field-unify/legacy/014-fact-field-unify/spec.md)。
本文件是 OpenSpec 的行为导向投影；实现细节、研究记录和历史任务保留在对应 change 的 `legacy/` 目录。
