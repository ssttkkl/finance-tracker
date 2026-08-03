# Tasks

## 1. 迁移后的历史任务清单

- [x] T001 [P] Create `tests/fixtures/usmart_hk/` and add redacted monthly statement text fixture `tests/fixtures/usmart_hk/monthly_sample.txt` (from exports redaction; no real name/address/account)
- [x] T002 [P] Confirm `.gitignore` covers `exports/`, `tests/fixtures/**/*.pdf`; do not commit real PDF
- [x] T003 [P] Verify `qpdf`/`mutool` available in dev env (document versions in research if needed)
- [x] T004 Create `src/ft/importers/usmart_hk.py` with module docstring, CJK normalize helper, public API stubs: `parse_usmart_hk_text`, `map_usmart_hk_to_investment_event`, `construct_source_identity`, `check_external_tools` (reuse or wrap pdf_tools)
- [x] T005 Wire `InvestmentImportService._parse_statement` for sources `usmart-hk` / `usmart_hk`: `.txt` path and PDF via `pdf_tools.decrypt_pdf` + `extract_pdf_text` in `src/ft/application/investment_import.py`
- [x] T006 Wire `_import_transactions` branch: `source_type=usmart_hk_pdf`, map/identity imports from `usmart_hk` in `src/ft/application/investment_import.py`
- [x] T007 Wire CLI: add `usmart-hk` (and alias `usmart_hk`) to import `--source` choices and `investment_sources` set; require `--account`; pass password-file in `src/ft/cli.py`
- [x] T008 Currency resolution for usmart-hk: prefer per-row currency; CLI `--currency` optional default only in `src/ft/application/investment_import.py`
- [x] T008a [P] Unit tests multi-currency cash↔cash swap + sequential HKD deposit then FX then USD flow in `tests/unit/domain/test_investment_event_replay.py` (or new `test_investment_multiccy_cash.py`) — expect no cost currency conflict
- [x] T008b Implement cash-ticker-aware cost_currency for swap/deposit/withdraw/checkin legs in `src/ft/domain/investment_projection.py` per research D13 (known fiat cash set; equity unchanged); cash↔cash target cost face value
- [x] T008c Green T008a; ensure existing IBKR/DFZQ projection unit tests still pass
- [x] T009 [P] [US1] Unit tests fee contract + order-group merge in `tests/unit/importers/test_usmart_hk.py` (buy/sell gross, buy commission=abs_net-gross; sell commission=gross-abs_net, multi-fill merge)
- [x] T010 [P] [US1] Unit tests `construct_source_identity` stability for trade/checkin rows in `tests/unit/importers/test_usmart_hk.py`
- [x] T011 [P] [US1] Unit tests map BUY/SELL → swap legs + commission_asset=ccy in `tests/unit/importers/test_usmart_hk.py`
- [x] T012 [US1] Integration test import fixture → event counts, ending cash CHECKIN, holdings shares, idempotent second import in `tests/integration/test_usmart_hk_import.py` (sqlite; mark postgres if matrix exists)
- [x] T013 [US1] Implement text section split + header period/ending cash parse in `src/ft/importers/usmart_hk.py`
- [x] T014 [US1] Implement 交易明细 order-group parser (fills + fee block + 变动金额合计) fail-closed on imbalance in `src/ft/importers/usmart_hk.py`
- [x] T015 [US1] Implement 持仓明细 parser (ticker, shares, ccy) in `src/ft/importers/usmart_hk.py`
- [x] T016 [US1] Emit cash CHECKIN (per numeric ending balance, date=period month-end) and holdings CHECKIN (shares, no invented cost) after flows in `src/ft/importers/usmart_hk.py`
- [x] T017 [US1] Implement `map_usmart_hk_to_investment_event` + `construct_source_identity` for trade/checkin per research.md in `src/ft/importers/usmart_hk.py`
- [x] T018 [US1] Make T009–T012 pass; fix only usmart_hk + wire (no 009 source regressions)
- [x] T019 [P] [US2] Unit tests empty market columns and empty 证券提存 in `tests/unit/importers/test_usmart_hk.py`
- [x] T020 [US2] Harden header parser for `--` / missing markets; skip non-numeric cash CHECKIN in `src/ft/importers/usmart_hk.py`
- [x] T021 [US2] Accept empty 证券提存 without events in `src/ft/importers/usmart_hk.py`
- [x] T022 [US2] Green T019
- [x] T023 [P] [US3] Unit tests cash flag map + ignore trade mirrors in `tests/unit/importers/test_usmart_hk.py`
- [x] T024 [P] [US3] Unit tests 换汇 pairing success + unpaired fail-closed in `tests/unit/importers/test_usmart_hk.py`
- [x] T025 [P] [US3] Unit tests 转入到日内融 → withdraw with note in `tests/unit/importers/test_usmart_hk.py`
- [x] T026 [US3] Parse 资金出入; classify ignore vs book in `src/ft/importers/usmart_hk.py`
- [x] T027 [US3] Implement FX pairing → single cash↔cash swap; fail unpaired in `src/ft/importers/usmart_hk.py`
- [x] T028 [US3] Map 出金/IPO/利息/转账 → withdraw|deposit; identities per research in `src/ft/importers/usmart_hk.py`
- [x] T029 [US3] Unknown flag fail-closed with snippet in `src/ft/importers/usmart_hk.py`
- [x] T030 [US3] Green T023–T025; extend integration if needed in `tests/integration/test_usmart_hk_import.py`
- [x] T031 [P] Dual-backend parity test or skip-with-reason if no PG: same fixture event count/cash/shares on sqlite+postgres in `tests/integration/test_usmart_hk_import.py`
- [x] T032 [P] CLI contract smoke: wrong account type / missing tools message paths (minimal) in `tests/unit/cli/` or integration
- [X] T033 Run full related pytest suite; fix regressions in dfzq/ibkr/schwab only if accidental
- [X] T034 Update tasks.md checkboxes; note calibration evidence vs SC-002 in quickstart or research
- [X] T035 [P] Optional: `openspec validate --all --strict` after implementer completes
- [x] T036 Record and return the ignored trade-mirror row count per FR-008 / SC-006 (partial)

## 2. 迁移确认

- [x] 2.1 保留原始任务、验证证据和未解决风险。
- [x] 2.2 将行为需求投影到 OpenSpec 主规格。
