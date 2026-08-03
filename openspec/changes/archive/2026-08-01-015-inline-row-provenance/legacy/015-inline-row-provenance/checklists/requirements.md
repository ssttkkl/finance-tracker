# Specification Quality Checklist: 正式事实结构清理（015）

**Purpose**: Validate specification completeness and quality before proceeding to planning  
**Created**: 2026-07-24  
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Validation 2026-07-24: initial pass (inline provenance).
- Validation 2026-07-24 (Living): offset 七列；再扩结构清理。
- Validation 2026-07-24 (Living, 用户拍板)：
  - **做**：删 `locked`、`transfer_account`、`source`+`bill_source`；删 `fact_deletion_events`；删 `record_revisions`；删 `relation_check_runs`；import/raw 内联。
  - **不做/纠正**：不删 `record_id`——幂等权威为 **`record_id` × `source_type`**（行键 × 导入渠道名）；不用平行 `source_identity`；不按裸 `record_id` 跨渠道去重。
  - **保留**：`ledger_snapshots` 缓存；wealth 表族不动。
  - **投资**：删除 `price`（US7 / FR-025～027）；单价由 legs 派生，窄化 014 升列。
  - **本机库**：实现后一次性升级 `~/.ft` 下 SQLite（默认 `finance-tracker.db`）——US8 / FR-028～031 / SC-012；先备份再迁。
- Target structure: `database-schema.md`；基线 `docs/database-schema.md` 至 implement 前仍为运行时描述。
