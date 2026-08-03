# Tasks

## 1. 迁移后的历史任务清单

- [x] T001 Create/switch feature branch `008-relations-kind-decouple` from a commit that already includes 007 Phase A→D scan behavior; leave working tree clean of unrelated WIP
- [x] T002 [P] Create package dirs `src/ft/domain/relations/{core,mirror,transfer,refund}/` with empty `__init__.py` files per `openspec/specs/008-relations-kind-decouple/plan.md`
- [x] T003 [P] Record baseline relation export procedure in `openspec/specs/008-relations-kind-decouple/quickstart.md` (confirm paths); if local DB available, save pre-move full-check summary under `outputs/008-baseline/` (kind×status counts + business keys sample)
- [x] T004 [P] Add placeholder test modules `tests/test_relations_pack_boundaries.py` and `tests/test_relations_pipeline_order.py` with `pytest.skip("not implemented")` so paths exist for TDD
- [x] T005 Write failing characterization note: run `uv run pytest tests/test_transaction_relations_payment_mirror.py tests/test_transaction_relations_transfer.py tests/test_transaction_relations_refund.py tests/test_transfer_phase_c.py tests/test_transaction_relations_open_leg.py tests/test_platform_refund_matchers.py -q` and keep log as green baseline before moves
- [x] T006 Extract enums + `FactView` + `RelationEvidence` + `RelationProposal` + related constants into `src/ft/domain/relations/core/types.py` (move from `src/ft/domain/relations.py` without logic change)
- [x] T007 [P] Extract `ordered_fact_pair`, `business_key`, `open_leg_business_key`, open-leg helpers into `src/ft/domain/relations/core/keys.py`
- [x] T008 [P] Extract pure time/amount/day/card-tail/`_text_blob` geometry helpers into `src/ft/domain/relations/core/geometry.py`
- [x] T009 [P] Extract `cross_kind_compatible` into `src/ft/domain/relations/core/compatibility.py`
- [x] T010 [P] Extract `project_balances_and_pnl` and projection helpers into `src/ft/domain/relations/core/projection.py`
- [x] T011 Convert `src/ft/domain/relations.py` into temporary facade re-exporting core + remaining monolith symbols so `from ft.domain.relations import …` still works
- [x] T012 Update `src/ft/domain/relations/__init__.py` to re-export public API used by `src/ft/application/relations.py` and tests
- [x] T013 Re-run relation test suite from T005; MUST stay green (behavior-identical core extract)
- [x] T014 [P] [US1] Extend `tests/test_relations_pipeline_order.py` with a failing test that `pipeline.run_relation_phases` (or equivalent) is the single recognition entry (import/attribute exists) — expect fail until **US3 / T030–T031** wiring
- [x] T015 [US1] Keep running full kind suite after each pack move (manual gate in T016–T019)
- [x] T016 [US1] Move payment mirror match functions into `src/ft/domain/relations/mirror/match.py`; facade re-exports; suite green
- [x] T017 [US1] Move transfer signals + taxonomy + match into `src/ft/domain/relations/transfer/{signals,taxonomy,match}.py`; no imports from refund/mirror; facade re-exports; suite green
- [x] T018 [US1] Move refund signals + evaluate/merchant/open-leg into `src/ft/domain/relations/refund/{signals,match}.py`; hard_key helper module `src/ft/domain/relations/refund/hard_key.py` wrapping Phase A calls to `platform_refund`; facade re-exports; suite green
- [x] T019 [US1] Move `match_diamond_bank_refunds` into `src/ft/domain/relations/refund/diamond.py` with signature accepting accepted mirror/refund edges (not calling mirror.match); facade re-exports; suite green
- [x] T020 [US1] Ensure `src/ft/application/relations.py` still produces same phase outcomes via facade (no semantic edits); run suite + optional baseline export diff; document any superseded-only diffs in `openspec/specs/008-relations-kind-decouple/quickstart.md`
- [x] T021 [P] [US2] Implement failing tests in `tests/test_relations_pack_boundaries.py`: AST or module walk asserting `mirror/`, `transfer/`, `refund/` do not import each other’s `signals` or `match` modules (per `contracts/rule-pack.md`)
- [x] T022 [P] [US2] Add test that transfer and refund each define their own signal constants modules (files exist; no `relations/signals.py` god module)
- [x] T023 [US2] Fix any cross-pack imports discovered by T021 (duplicate tokens if needed per research R4) under `src/ft/domain/relations/**`
- [x] T024 [US2] Make T021–T022 pass; add brief note in `src/ft/domain/relations/__init__.py` or package docstring pointing to `contracts/rule-pack.md`
- [x] T025 [US2] Maintainer drill: no-op comment-only change proof in docs — append checklist item result to `openspec/specs/008-relations-kind-decouple/quickstart.md` §5
- [x] T026 [P] [US3] Expand `tests/test_relations_pipeline_order.py`: without accepted mirror edges, diamond path yields zero proposals for a fixture that only has bank refund text
- [x] T027 [P] [US3] Expand same file: with synthetic accepted mirror + platform refund edges, diamond can produce a proposal (minimal fixture)
- [x] T028 [US3] Failing test that `RelationService` recognition path invokes `pipeline` entry (mock/spy or source contract) until wired
- [x] T029 [US3] Implement `MatchContext` + edge types in `src/ft/domain/relations/core/types.py` (or `core/context.py`) per `contracts/match-context.md` and `data-model.md`
- [x] T030 [US3] Implement `src/ft/domain/relations/pipeline.py` `run_relation_phases` enforcing A→B→C→D per `contracts/pipeline-phases.md`, updating context between phases; **seed MatchContext with workspace preloaded accepted payment_mirror + platform refund_offset edges plus this-run accepts** (007 full-recompute parity)
- [x] T031 [US3] Wire `src/ft/application/relations.py` to call `pipeline.run_relation_phases` for recognition; remove duplicate phase ordering logic from application (persist/review remain)
- [x] T032 [US3] Make T026–T028 pass; re-run full relation suite; re-check baseline parity (SC-001)
- [x] T033 Remove temporary monolith body from `src/ft/domain/relations.py` if still a file: either delete and rely on package, or keep thin re-export only—ensure single source of rule code under package dirs
- [x] T034 [P] Update any stale imports in tests/application to preferred package paths (`ft.domain.relations…`) without behavior change
- [x] T035 [P] Run dual-backend relation tests available in repo (SQLite mandatory; PostgreSQL when env present); record result in quickstart or task notes
- [x] T036 Verify Non-Goals: no lexicon policy change (闲鱼/微信 soft) landed; grep diff for accidental signal policy edits
- [x] T037 [P] SC-004 walkthrough: from `openspec/specs/008-relations-kind-decouple/spec.md` FR-003/FR-005 and `contracts/pipeline-phases.md` + `contracts/match-context.md`, confirm a maintainer can locate Phase A→D order and diamond edge-only rule within 15 minutes; note pass in `quickstart.md`
- [x] T038 Final suite: relation tests + new boundary/order tests green; update `openspec/specs/008-relations-kind-decouple/tasks.md` checkboxes

## 2. 迁移确认

- [x] 2.1 保留原始任务、验证证据和未解决风险。
- [x] 2.2 将行为需求投影到 OpenSpec 主规格。
