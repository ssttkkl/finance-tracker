---
name: investment-statement-importer-onboarding
description: >
  Use when adding or fixing a securities/crypto/broker investment statement importer
  (PDF/CSV/API → investment_events), onboarding a new broker (e.g. Futu, IBKR, Huatai),
  or calibrating DFZQ-style fee/cost/checkin semantics against real statements.
  Keywords: 证券对账单, broker import, investment_events, SWAP, commission, cost basis,
  CHECKIN, qpdf, mutool, source_identity, ledger snapshot, 总发生金额, 成本价.
---

# Investment Statement Importer Onboarding

Project skill for **finance-tracker**: add (or fix) a **securities / crypto / broker**
importer so rows become `investment_events` with correct cash legs, positions, and
cost basis — calibrated on **real statements**, not invented schemas.

Companion to `statement-source-onboarding` (cash bills + relations). This skill is
**investment-only**: no payment_mirror / transfer_pair / refund_offset.

Distilled from feature `009-investment-account-import` and live DFZQ calibration
(`~/.ft/bills` PDF → SQLite import loops).

## When to use

- New broker / exchange statement format (PDF, CSV, XLS, API dump)
- Existing importer wrong cash, shares, **cost price**, or fees
- Wiring `ft import --source <name>` for investment accounts
- Dual-backend (SQLite + PostgreSQL) parity for investment import
- Extending beyond DFZQ (Futu, IBKR, Huatai, another CN broker)

## When NOT to use

- Cash/bank/wallet bills → use **`statement-source-onboarding`**
- Connector API sync / credentials → feature **`018-investment-connector-sync`**
  (`ft sync`, not this skill’s file-import path)
- Live quotes / portfolio valuation → feature **`017-asset-valuation-quote`**
- Investment **relations** (trade lots, FIFO) — not in Phase 1 baseline
- Implementing product code in main session when CLAUDE.md requires
  `$openspec-apply-change` — follow the OpenSpec workflow; this skill is the playbook

## Non-goals

- Does not replace OpenSpec (`$openspec-propose` → `$openspec-apply-change` → `$openspec-archive-change`)
- Does not invent fee semantics without real samples
- Does not restore CSV/Git file ledgers (removed by `001`)
- Does not add independent `FEE` / `BUY` / `SELL` actions if product chose
  **single-row SWAP + commission field** (009 decision)

---

## Architecture you must respect

```text
CLI: ft import <file> --source <src> --account <security|crypto> [--password-file]
        ↓
InvestmentImportService (application)   # post-015: no import_batches / raw_* tables
  1. parse (decrypt/extract if PDF) → list[source txn]
  2. map → unified investment event rows
  3. ONE transaction:
       for each row: idempotent key = source_type × record_id
         skip if exists; else insert investment_events
         (source_payload inline; no raw_record_id FK)
       + apply_investment_event → ledger_snapshots
       + validate_investment_snapshot
  4. NO cash_transactions, NO relations writes on this path
```

| Layer | Location |
|---|---|
| Parser + map | `src/ft/importers/<source>.py` |
| PDF helpers | `src/ft/importers/pdf_tools.py`, qpdf/mutool |
| Import orchestration | `src/ft/application/investment_import.py` |
| Projection | `src/ft/domain/investment_projection.py` |
| Snapshot validation | `src/ft/domain/investment_validation.py` |
| CLI wire | `src/ft/cli.py` (`--source` choices + investment branch) |
| Spec | `openspec/changes/<name>` |

**Event actions (009 baseline):** `swap` | `deposit` | `withdraw` | `dividend` | `checkin`  
Buy/sell = **SWAP** (cash↔ticker). Fees: prefer **inside cash leg** or explicit
`commission` + `commission_asset` — never both for the same fee.

Dual backend, Decimal money, workspace isolation, fail-closed parse.

---

## Golden workflow (do not skip)

### 0. OpenSpec change

1. Flow-forward: `openspec/changes/<name>` (or living update of active investment feature)
2. Spec must state: sources in scope, event action set, fee/cost rules, checkin policy,
   dual-backend, non-goals (no connector platform, no valuation, no relations)
3. Clarify: **fee double-count**, SWAP vs BUY/SELL, opening cost when history incomplete
4. design → tasks → validate → apply-change

### 1. Real statement → native field census → fee/cost contract → map → calibrate

```text
真实对账单（加密 PDF/CSV）
    ↓
解密/抽文本，保存 exports/ 样本（gitignore 个人数据）
    ↓
原生字段表：每列含义 + 符号约定（资金流入为正？）
    ↓
费用合同：总发生金额 vs 手续费/印花税/过户费 谁含谁
    ↓
持仓汇总页：股数、成本价、市值、资金余额（CHECKIN 来源）
    ↓
map → unified events；离线 apply_investment_event 回放
    ↓
ft import 真库；对照页眉数字；有 diff 只改 map/projection，再清库重导
```

**Pitfall (lived):** Coding map before fee census → commission double-count,
cash only correct via accidental CHECKIN.

### 2. Fee / cash-leg contract (highest-risk mistakes)

Before writing map code, fill this table on **≥5 sample trades** (buy, sell, repo, dividend).
**Universal constraint**: each fen of fee appears in **either** the cash leg **or**
`commission`, never both. Projection cash for a trade must still match the statement
net cash movement.

#### DFZQ (net statement + peel 手续费) — product choice for 009

Statement「总发生金额」is **net** cash (after 手续费/印花税/过户费). Preferred map peels
**手续费** into `commission` and adjusts the cash leg so total impact stays `|净额|`:

| Side | Cash leg | commission | commission_asset | Projection cash |
|---|---|---|---|---|
| BUY | `\|净额\| - 手续费` | `手续费` | cny | from + commission = \|净额\| out |
| SELL | `\|净额\| + 手续费` | `手续费` | cny | to − commission = \|净额\| in |
| fee missing / cannot peel (BUY fee ≥ net) | `\|净额\|` | `0` | — | cash = \|净额\| |

| Field (source native) | Example (DFZQ) | In cash leg? | In commission field? | Note only? |
|---|---|---|---|---|
| 总发生金额 / net cash | `-1343.01` buy | **Partial** after peel: net−fee | No | — |
| 手续费 | `5.00` | No once peeled | **Yes** when peeled | Only if peel fails |
| 印花税 | sell | Inside cash leg (not peeled by default) | No | Yes |
| 过户费 | buy | Inside cash leg (not peeled by default) | No | Yes |
| 成交数量×价格 | notional | Not equal to cash leg | — | Sanity only |

#### IBKR / gross+fee statements

| Field | Example | In cash leg? | In commission field? |
|---|---|---|---|
| 总额 / gross | `-5478.28` buy | **Yes** `abs(gross)` | No |
| 佣金 | `-1.00` | No | **Yes** `abs(comm)` |
| 净额 | `gross+comm` | Derived only | — |

**Rules:**

1. **One place for each fen of fee.** Never `cash_leg = |net|` **and** `commission = fee`
   when net already includes that fee (double drain).
2. If source gives **gross + separate fee** (IBKR equity): cash leg = gross, commission = fee.
3. If source gives **net only** and you want commission audit (DFZQ preferred): peel fee
   out of net into commission and shrink/grow the cash leg so projection still equals net.
4. If peel is unsafe: keep cash leg = net and **commission = 0** (fees in note).
5. Never `amount = total_amount + fee` in the **parser** when `total_amount` is already net,
   then also set commission=fee (classic DFZQ double-count bug).
6. Repo / reverse-repo (e.g. `204001`): cash leg is **principal**, not `shares×price`.

**Pitfall (lived):** DFZQ `_make_txn` did `amount = total_amount + fee` then map set
`commission = fee` → double drain; final cash looked OK only after cash CHECKIN.

**Pitfall (docs):** Treating “net statement ⇒ always commission=0” as the only legal model
discards commission audit; peel model is preferred when fee is cleanly separable.

| Checkpoint | Source of truth | Event |
|---|---|---|
| End shares | Statement holdings table | Flow replay **or** CHECKIN |
| End **cost price** | Statement “成本价” | **CHECKIN** with `price=cost`, `to_amount=shares` |
| End cash | 资金余额 / cash balance | CHECKIN cash ticker |
| Flow-only cost | Incomplete history | Unreliable — do not claim P&L |

**CHECKIN policy (recommended):**

1. Always emit **cash CHECKIN** from header balance after flows.
2. Emit **per-ticker CHECKIN** from “汇总持仓 / positions” when available
   (shares + broker cost price).
3. Projection `checkin` **replaces** position (not delta) — use for alignment, not
   incremental buys.

**Pitfall (lived):** Flow replay shares matched 95200 but avg cost 0.610 vs broker 0.718
until holdings CHECKIN.

**Pitfall (lived):** Allowing sell > held shares unblocks monthly PDFs but corrupts cost;
prefer explicit opening CHECKIN or documented soft-start, then **summary CHECKIN**.

### 4. Parser implementation checklist

- [ ] External tools declared (qpdf/mutool versions; fail with install hint)
- [ ] Password via `--password-file` (not argv) for PDFs
- [ ] Extract text with explicit encoding (`utf-8` + replace); never `open(pdf)` as text
- [ ] Every trade line → structured txn; **no silent `continue`** without counter
- [ ] Unknown action type → **fail closed** (abort import)
- [ ] `source_identity` stable: date + ticker + action + net amount + balance (or broker id)
- [ ] Map to unified event: unsigned magnitudes, `account_name`, `currency`
- [ ] BUY/SELL → `action=swap` with from/to tickers; deposit/withdraw/dividend/checkin as named
- [ ] Account type must be `security` or `crypto` at CLI/service
- [ ] Unit tests: fixture text → txn counts, fee fields, map shapes
- [ ] Offline full-statement replay script before touching user DB

### 5. Wire import path

1. `importers/<src>.py`: `parse_*`, `map_*_to_investment_event`, `construct_source_identity`,
   optional `_parse_holdings_summary` / `_parse_cash_balance`
2. `InvestmentImportService._parse_statement`: branch on `source`; PDF decrypt+extract
3. CLI: add `--source` choice; pass `password` from `_read_password_file`
4. `OperationResult` extras go in **`details`** (`batch_id`, `duplicate`) — not free kwargs
5. Projection: quantize divisions to ≤18 dp (`NUMERIC(38,18)`); validate snapshot after apply

### 6. Calibration loop (mandatory on real statements)

```bash
export FT_DATABASE_URL=sqlite+pysqlite:////tmp/ft-invest-calibrate.db   # or user DB after backup
export FT_WORKSPACE_ID=default
# migrate; create security account
ft acct add <券商名> --type security --currency CNY
echo '<pdf-password>' > /tmp/broker_pw.txt
ft import /path/to/statement.pdf --source <src> --account <券商名> --password-file /tmp/broker_pw.txt
# re-run → must print already imported
```

**Measure against statement header (not “feels right”):**

| Metric | Pass |
|---|---|
| Event count | = flow rows + checkins |
| pairwise parse↔DB fields | 0 mismatches |
| Cash | = 资金余额 |
| Each holding shares | = 持仓数量 |
| Each holding avg cost | = 成本价 (after checkin) |
| Cash + 证券市值 | ≈ 总资产 (rounding ≤ 0.02) |
| Swaps with wrong double fee | 0 |
| Idempotent re-import | same batch_id, count 0 |

**Clean re-import (post-015):** delete matching `investment_events` rows for the
account/`source_type` (and related soft-delete state if any) → reset that account’s
slice in `ledger_snapshots` → import again. There are **no** `raw_records` /
`import_batches` tables to wipe.

Stop tuning when:

- Remaining gaps need missing history (open with CHECKIN / document)
- “Fix” requires inventing amounts not on the statement
- Product wants lot accounting → new feature, not silent map hacks

### 7. Dual backend & tests

- Unit: parser fixtures (checked-in **redacted** text, never real PDF in git)
- Integration: import flow + idempotency (parametrize sqlite/postgres when fixtures exist)
- Contract: CLI errors (missing account, wrong type, missing tools)
- `.gitignore`: `tests/fixtures/**/*.pdf`, `exports/`, `*.db`

---

## Source onboarding template (copy into feature spec)

```markdown
### Investment source: <name>
- Export format / sample path (local only):
- Decrypt/extract tools:
- **Native columns:** (date, side, code, qty, price, net cash, fee, tax, balance, …)
- **Fee contract table:** (which fields enter cash leg / commission / note)
- **Holdings summary fields:** (shares, cost price, market price, market value)
- **Cash balance field:**
- Action map: source label → swap|deposit|withdraw|dividend|checkin
- Special instruments: (repo, OTC, HKD/USD legs)
- source_identity recipe:
- Account type: security | crypto
- CHECKIN: cash only | cash + holdings cost
- Calibration: cash / shares / cost / assets recon actuals
- Non-goals: (connector, valuation, relations, …)
```

---

## Pitfall checklist (009 lived)

| Pitfall | Symptom | Fix |
|---|---|---|
| Read PDF as UTF-8 text | `codec can't decode byte` | qpdf decrypt + mutool text |
| `amount = total + fee` + `commission=fee` | Cash low before checkin; fees twice | Net once; commission 0 if embedded |
| Flow cost without holdings checkin | Shares OK, cost 0.61 vs 0.718 | Parse 汇总股票资料 → CHECKIN |
| Sell blocked / soft-sell without checkin | Import abort or wrong cost | Opening/summary CHECKIN policy |
| Division unbounded decimals | `exceeds NUMERIC(38,18)` | quantize release cost to 18 dp |
| `OperationResult(batch_id=…)` | TypeError on success path | use `details={...}` |
| Password on CLI argv | leaks in shell history | `--password-file` |
| Commit real PDF / exports | privacy | gitignore `exports/`, fixture PDFs |
| Tune without clean re-import | stale events/snapshot | FK-safe wipe + reimport |
| Treat repo as stock notional | absurd avg price | principal cash leg |
| Import writes relations | N/A for invest v1 | never |

---

## Definition of done (new investment source)

- [ ] Spec fee/cost/checkin contract filled from real samples
- [ ] Parser + map unit tests on redacted fixtures
- [ ] Offline full replay matches header cash/shares/cost
- [ ] Live `ft import` on real file; idempotent second run
- [ ] Assets recon (cash + MV ≈ total) documented
- [ ] Dual-backend tests if persistence touched
- [ ] CLI source choice + password-file + account type gate
- [ ] No silent skips; no fee double-count; no personal data in git
- [ ] Research/decision log notes fee model and checkin policy

---

## Collaboration with OpenSpec / cash skill

1. explore/propose/validate first
2. Use this skill for **investment** calibration; `statement-source-onboarding` for cash  
3. Use `$openspec-apply-change` for product code when the project context requires it
4. Real-statement calibration is **in-scope** product validation  
5. If calibration changes product rules, Flow-Back: update spec then code  

When user only asks “can we support broker X?”, run census + fee table + gap list;
do not implement until the OpenSpec change exists.
