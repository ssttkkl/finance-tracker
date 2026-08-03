# Tasks

## 1. 迁移后的历史任务清单

- [x] T001 [P] Unit tests base face vs equity cost + FX both-base no conflict in `tests/unit/domain/test_investment_base_currency_cost.py`
- [x] T002 Implement `base_tickers` param + face base legs + DEFAULT fallback in `src/ft/domain/investment_projection.py`
- [x] T003 Green T001; keep soft-start oversell tests green
- [x] T004 Pass account base_currencies into `apply_investment_command` in `src/ft/adapters/relational/investments.py`
- [x] T005 Pass account base_currencies into `apply_investment_event` in `src/ft/application/investment_import.py`
- [x] T006 [P] Helper to normalize metadata list → lowercase set (shared small fn ok)
- [x] T007 Run usmart/ibkr/dfzq related suites; fix account fixture bases if needed
- [x] T008 Mark tasks complete

## 2. 迁移确认

- [x] 2.1 保留原始任务、验证证据和未解决风险。
- [x] 2.2 将行为需求投影到 OpenSpec 主规格。
