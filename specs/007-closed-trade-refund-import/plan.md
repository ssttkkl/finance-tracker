# Implementation Plan: Import No-Skip & Closed-Trade Anchors

**Branch**: `007-import-no-skip` | **Date**: 2026-07-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/007-closed-trade-refund-import/spec.md`

## Summary

Deliver **import no-skip** for all wired bill sources, **publish closed/failed Alipay/WeChat rows** as normal formal facts (no `funding_status` field), and **create alipay `refund_offset` at import** when order keys uniquely match (`==` / `prefix_` / `prefix*`). Relation scan does **not** backfill alipay same-platform order refunds; it keeps cross-source mirror and transfers. Balance net-zero for closed+full refund comes from **amount cancel** (-A +A), not a funding enum.

## Technical Context

**Language/Version**: Python 3.11+  
**Primary Dependencies**: existing `ft` CLI, Application Services, SQLAlchemy 2.x, Alembic, uv  
**Storage**: PostgreSQL + SQLite via `FT_DATABASE_URL` (shared logical schema)  
**Testing**: pytest; dual-backend matrix when `FT_TEST_POSTGRES_URL` set  
**Target Platform**: local CLI / developer machine  
**Project Type**: CLI + domain/application services + relational adapter  
**Performance Goals**: full real bill files import without silent loss; relation check budget remains 006 concern  
**Constraints**: Decimal money; no silent skip; fail closed on mapping/parse errors; dual-backend equivalence  
**Scale/Scope**: all wired sources (alipay, wechat, icbc credit/debit, ccb debit, dfzq); convert + statement_import + cash fact funding flag + balance snapshot path

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|---|---|---|
| I. Financial correctness & auditability | PASS | No silent discard; closed anchors + raw chain; Decimal |
| II. Spec Kit driven | PASS | Spec/plan/tasks only source of truth for this change |
| III. Test-first | PASS | Tasks require failing tests for each former skip path |
| IV. Explicit dual DB | PASS | Schema + import acceptance + balance exclusion equivalent on PG/SQLite |
| V. Clear boundaries | PASS | Convert/import publish facts; 006 owns relations; no time-window hack |

**Post-design re-check**: still PASS — funding/settlement flag is domain field on formal cash facts; parsers stay free of relation engine.

## Project Structure

### Documentation (this feature)

```text
specs/007-closed-trade-refund-import/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── import-acceptance.md
│   └── closed-trade-anchor.md
├── checklists/requirements.md
└── spec.md
```

### Source Code (expected touch set)

```text
src/ft/convert.py                    # remove status continues; emit settlement flags + order prefix
src/ft/importers/*.py                # wechat/alipay/etc. no silent status drops
src/ft/application/statement_import.py  # acceptance counters; fail-closed mapping; balance skip non-funding
src/ft/adapters/relational/models.py    # funding_status / settlement fields if needed
migrations/versions/                    # nullable-safe columns for settlement + origin_order_id
src/ft/domain/relations.py              # consume origin_order_id / closed facts as refund counterparty (minimal)
tests/test_import_no_skip_*.py
tests/test_closed_trade_import_*.py
```

**Structure Decision**: extend existing convert → statement_import pipeline; no new runtime backend.

## Complexity Tracking

No constitution violations requiring justification.

## Implementation Phases (design)

### Phase A — Acceptance contract

- Define source-transaction-line vs layout noise.
- Import result counters: `source_lines`, `published`, `idempotent_hits`, `failed`.
- Success invariant: `published + idempotent_hits == source_lines`.

### Phase B — Remove silent skips

- Alipay: stop skipping 交易关闭/已关闭/还款失败; emit non-funding facts.
- WeChat: stop skipping 交易失败/已关闭/已撤销 and non-whitelist income silence; emit non-funding or fail-closed.
- Mapping: remove silent `default: skip` path → error.
- Parse errors: raise with file+line; no continue-success.

### Phase C — Closed-trade + order prefix

- Persist platform status + `origin_order_id` / order base for refunds.
- Balance snapshot updates **only** for funding-occupying facts.
- Minimal 006 hook: refund matching may use origin_order_id exact before title.

### Phase C2 — Import emits refund_offset; scan does not 补漏 alipay orders

- On unique order-key match, insert `transaction_relations` refund_offset at import.
- Relation check skips facts that already have active refund_offset for that pair/refund.
- **No alipay refund 补漏** path in check for order-key cases.

### Phase D — Verification

- Dual-backend tests.
- Real `~/.ft/bills` closed→refund→reorder fixtures (anonymized copies in tests where needed).
