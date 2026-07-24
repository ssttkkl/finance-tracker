# Implementation Plan: uSmart HK Monthly Statement Import

**Branch**: `011-usmart-hk-import` | **Date**: 2026-07-23 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/011-usmart-hk-import/spec.md`

## Summary

Add **uSmart Securities Limited (盈立证券香港)** encrypted monthly PDF as a first-class investment import source (`--source usmart-hk`), reusing 009 `InvestmentImportService` and 010 row-level `source_identity` idempotency. Map trade **order groups** to equity `swap` with **gross + commission**; non-trade cash via `deposit`/`withdraw`; pair 换汇 into cash↔cash `swap`; multi-currency cash CHECKIN + holdings shares CHECKIN (no invented cost). **No new event actions**.

## Technical Context

**Language/Version**: Python 3.11+

**Primary Dependencies**: SQLAlchemy 2, existing investment projection/import; external **qpdf**, **mutool** via `ft.importers.pdf_tools`

**Storage**: PostgreSQL + SQLite via `FT_DATABASE_URL` — no new tables; no new action enum values

**Testing**: pytest unit (parser/map/fee/FX), integration import + idempotency, dual-backend when PG fixture available

**Target Platform**: macOS / Linux CLI

**Project Type**: CLI + hexagonal library

**Performance Goals**: Monthly PDF (~12 pages, <100 events): import <15s including extract

**Constraints**: Decimal money; fail-closed; password-file; no PII in git; 010 row idempotency (no digest short-circuit for novel rows)

**Scale/Scope**: One broker; multi-currency cash tickers `hkd`/`usd`/`cny` on one security account

## Constitution Check

*GATE: pass before research; re-check after design.*

### I 财务正确性 ✅
Gross+commission once; FX unpaired fail-closed; CHECKIN aligns header cash; no fake cost.

### II Spec Kit ✅
spec → plan → research → data-model → contracts → tasks → implementer.

### III 测试先行 ✅
Red tests first for parser/map; SC-002 anchors; dual-backend when available.

### IV 双后端等价 ✅
Shared application path; domain-only fee math.

### V 最小复杂度 ✅
One importer module + wire; no transfer action; no connector/valuation.

**Post-design**: ✅ No unjustified violations.

## Project Structure

```text
specs/011-usmart-hk-import/
├── spec.md, plan.md, research.md, data-model.md, quickstart.md
├── contracts/cli.md
├── checklists/requirements.md
└── tasks.md

src/ft/importers/usmart_hk.py          # NEW
src/ft/domain/investment_projection.py  # EXTEND multi-ccy cash swap cost_currency
src/ft/application/investment_import.py # WIRE
src/ft/cli.py                          # WIRE usmart-hk
tests/fixtures/usmart_hk/*.txt
tests/unit/importers/test_usmart_hk.py
```

**Structure Decision**: Same pattern as dfzq/ibkr/schwab importers.

## Complexity Tracking

| Item | Why | Rejected alternative |
|------|-----|----------------------|
| Order-group aggregation | Fees billed once per group | Per-fill invents fee splits |
| FX pairing | User requires swap | Independent deposit/withdraw per leg without pair |
| Ignore cash-section trade mirrors | Dual listing | Double cash |
