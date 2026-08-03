# Tasks: Investment Base-Currency Cost Semantics

**Tests mandatory first.**

## Phase 1: Domain

- [x] T001 [P] Unit tests base face vs equity cost + FX both-base no conflict in `tests/unit/domain/test_investment_base_currency_cost.py`
- [x] T002 Implement `base_tickers` param + face base legs + DEFAULT fallback in `src/ft/domain/investment_projection.py`
- [x] T003 Green T001; keep soft-start oversell tests green

## Phase 2: Wire

- [x] T004 Pass account base_currencies into `apply_investment_command` in `src/ft/adapters/relational/investments.py`
- [x] T005 Pass account base_currencies into `apply_investment_event` in `src/ft/application/investment_import.py`
- [x] T006 [P] Helper to normalize metadata list → lowercase set (shared small fn ok)

## Phase 3: Polish

- [x] T007 Run usmart/ibkr/dfzq related suites; fix account fixture bases if needed
- [x] T008 Mark tasks complete
