---
name: statement-source-onboarding
description: >
  Add or upgrade a statement/bill source for import + relation scanning in finance-tracker.
  Use when onboarding a new bank/platform export, fixing source mapping/account semantics,
  or extending payment_mirror / refund_offset / transfer_pair for a new bill type.
  Encodes the 007 real-bill workflow, pitfalls, and rule architecture (Spec Kit + dual DB).
---

# Statement Source Onboarding (Import + Scan)

Project-level skill for **finance-tracker**: how to add (or materially change) a bill source
so rows land correctly as formal facts **and** participate correctly in `relations check`.

This skill is the distilled playbook from feature `007-closed-trade-refund-import` and the
real `~/.ft/bills` calibration loops (mirror, refund, transfer).

## When to use

- New export type (e.g. another bank, another wallet, broker cash ledger)
- Existing source mis-routes accounts (提现记到卡、理财记到余额等)
- Scan misses/false pairs for a source (mirror weak flood, wrong transfer, orphan refunds)
- Extending `source_group` / signal tokens / Phase A hard-key matchers

## Non-goals of this skill

- Does not replace Spec Kit (`specify` → plan → tasks → implementer agent)
- Does not invent financial semantics without real bill samples
- Does not implement production code in the main session when project rules require
  `speckit_implementer` (follow CLAUDE.md / constitution)

---

## Architecture you must respect

```text
Import (facts only)
  parse → raw_records.payload (source fields) → formal cash facts
  NO refund_offset / payment_mirror / transfer_pair at import

relations check (one shot, ordered)
  Phase A  platform hard-key refund_offset   (source-specific adapters)
  Phase B  payment_mirror                     (platform × bank)
  Phase C  transfer_pair / credit_repayment  (taxonomy gate → fine match)
  Phase D  bank/weak refund + diamond        (open-leg last)
```

Dual backend (SQLite + PostgreSQL), Decimal money, formal facts immutable, workspace isolation.

---

## Golden workflow (do not skip)

### 0. Spec Kit feature

1. New or living feature under `specs/00N-…`
2. Spec must state: acceptance counts, raw payload contract, scan phase ownership,
   non-goals (no silent skip, no amount netting, no import-time relations unless
   product explicitly revisits that decision)
3. Plan + tasks + analyze before implement

### 1. Inventory real bills (taxonomy first)

For **every** new source, build a **native classification table** before writing matchers.

| Source style | Taxonomy axes (examples) |
|---|---|
| Platform (Alipay-like) | `status × direction × amount bucket` |
| Wallet (WeChat-like) | `direction × status × type` |
| Bank XLS/PDF | `summary/location × sign` (+ card) |
| Card credit | counterparty / raw description × sign |

For each cell answer:

1. **Import?** yes / whitelist-skip (comment+counter) / fail-closed
2. **Account role?** which book account (never confuse destination evidence with book account)
3. **Scan seed role?** expense / refund / transfer-out / transfer-in / mirror-leg / ignore
4. **Hard key?** order id, merchant order, none

**Pitfall (lived):** Do not invent “import-time pairing” because the source is “simple”.
Hard keys → Phase A; fuzzy → Phase B/C/D.

### 2. Account semantics (highest-risk mistakes)

Before any scan tuning, fix **which account a row books to**.

| Transaction | Correct book | Wrong book (seen in production) |
|---|---|---|
| Wallet withdraw to bank | **Wallet −** (out) | Bank **+** (double-count with bank credit) |
| Platform cash withdraw | **Platform balance −** | Bank **+** |
| Money-market out to bank (余额宝/余利宝) | **Product account −** | Destination card **+** or platform balance **+** |
| Card purchase via platform | Often **card account** (if mapping uses pay method) | Random platform cash |

Rules of thumb:

- **Evidence vs book:** destination card in counterparty/payload is OK; **mapping key must be the book account**.
- **Import must not write relations** to “fix” bad booking; fix convert/mapping first.
- User `~/.ft/mapping.yaml` + `accounts.yaml` may need new accounts (e.g. 余额宝, 余利宝); document in feature notes (often not in git).

### 3. Raw payload contract

Every formal fact must retain **pairing fields** in `raw_records.payload` (JSON):

Minimum by family:

| Family | Must recover |
|---|---|
| Platform | status, full txn/order ids, merchant order if any, direction, pay method, amount, time |
| Wallet | status, type, direction, pay method, amount, time, txn/mer ids |
| Bank | amount, business date, summary/location/raw cp, refund signal text |

**Pitfall (lived):** Scan used `occurred_at` UTC midnight → `…16:00:00` and false “platform_after_bank”.
**Pairing must use business time from raw** (`payload.date`) in workspace timezone (`Asia/Shanghai` today).

Date-only bank days: compare **calendar day only**; do not invent second-resolution lag.

### 4. Implement import path

Checklist:

- [ ] Parser: every source transaction line → structured row
- [ ] No silent `continue` (whitelist skips: comment + counter)
- [ ] Mapping miss → **fail closed** (not skip)
- [ ] Amount sign/category from **source semantics**, not from “pay method looks like a bank”
- [ ] Payload filled; `source_identity` stable for idempotency
- [ ] Dual-backend acceptance counters equivalent
- [ ] Unit tests: sample rows → account + sign + payload keys

### 5. Wire into scan (only after import is honest)

#### Phase A — hard-key refunds (if source has them)

- Pure matchers in domain (e.g. `platform_refund.py` pattern)
- Trigger from `RelationService` Phase A; rule ids `scan.<source>.*`
- Multi-candidate → open-leg/pending, never silent pick
- **Do not** re-run weak merchant refund as “补漏” for hard-key sources

#### Phase B — payment_mirror

Requires `source_group ∈ {platform, bank}` (see token sets in `relations.py`).

**When adding a source:**

1. Extend `PAYMENT_PLATFORM_SOURCES` or `BANK_CHANNEL_SOURCES` / `source_group()` blobs
2. Prefer structural rules over merchant names:
   - same `account_id` + exact amount + same business day (main auto path)
   - refund dual-source: both credits + refundish text
   - cross-account only with text/card + short window
3. Multi-candidate on same-account tiers: **nearest time / best score**, still 1–1 greedy globally
4. Do not treat bank×bank as payment_mirror

**Pitfall (lived):** Requiring text cross blocked true 1-second mirrors when bank summary is only「消费」.

#### Phase C — transfer_pair

Taxonomy gate **then** fine match:

1. Label legs from native cells (withdraw, card bridge, credit repay, brokerage…)
2. Exclude P2P/QR/redpacket from transfer auto
3. Transfer = **different** `account_id` (except documented dual-source legacy)
4. Credit repayment: cash→loan + strong repay text; never merchant「还款」alone

**Pitfall (lived):** Treating wallet P2P “转账” as internal transfer; flood of false pairs.

#### Phase D — bank/weak refund + diamond

1. Prefer **diamond** when chain exists:
   `bank_ref –mirror– plat_ref –refund– plat_pay –mirror– bank_pay`
2. Only accepted platform refund + solid mirrors as diamond corners
3. Else merchant/open-leg; multi-candidate → open-leg with candidate list
4. **Do not** expect diamond to fix rows with **no platform mirror** (data gap, not algorithm gap)

### 6. Calibration loop (mandatory on real bills)

Use a **fresh SQLite DB** per iteration (or dual PG matrix when available):

```bash
export FT_DATABASE_URL=sqlite+pysqlite:////tmp/ft-source-calibrate.db
export FT_WORKSPACE_ID=default
# migrate + seed accounts from ~/.ft/accounts.yaml (name-unique)
# import all files for the source (+ counterpart banks/platforms needed for mirror/transfer)
ft relations check
```

Measure:

| Metric | Watch |
|---|---|
| pending total / by rule | Should drop for the target class only |
| open-leg count | Bank refunds without platform chain |
| rule_id histogram | Unexpected rule capturing traffic |
| Account sample | Withdraw/product rows on correct accounts |

**Compare** against previous DB snapshot (pending Δ, accepted Δ per rule).

Stop auto-tuning when:

- Further recall needs broader tokens that pull P2P/consume into transfer
- Open-legs lack platform mirrors (need data/mapping, not looser refund)
- Multi-candidate cases need human product choice, not silent merge

### 7. Dead code / subsumption hygiene

After rule changes:

1. Count real hits per `rule_id` on calibration DB
2. If rule A ⊂ rule B on match conditions, **merge branches** (keep optional rule_id label for audit)
3. Delete unused helpers/imports/import-time relation writers
4. Update `research.md` Decision log (why removed/merged)

---

## Source onboarding template (copy into feature spec)

```markdown
### Source: <name>
- Export format / sample path:
- Taxonomy table: (status×dir / summary×sign / …)
- Whitelist skips: (reason codes)
- Book account rules: (withdraw, repay, product, purchase)
- Raw payload keys:
- source_group: platform | bank | other (tokens to add)
- Phase A hard keys: (none | order | dual-row | …)
- Phase B expectations: same-account mapping? refund dual?
- Phase C cells: withdraw / card bridge / credit repay / exclude
- Phase D: bank refund wording; diamond feasibility
- Calibration DB path + pending baseline → target
- Non-goals:
```

---

## Pitfall checklist (007 lived experience)

| Pitfall | Symptom | Fix |
|---|---|---|
| Import writes relations | Split brain vs bank path | Facts-only import; scan owns relations |
| Pay-method mapping to destination | Withdraw books as bank + | Book source account; destination as evidence |
| Netting amounts in convert | Balances wrong; scan confused | Relations only; never rewrite amount |
| Pair on `occurred_at` UTC | Weak mirrors, platform_after_bank | Raw business day Asia/Shanghai |
| Require text for same-account mirror | 1s true pairs pending | same_account + biz day auto |
| Multi-candidate → all pending | 62 “obvious” pairs stuck | Nearest-time accept + global 1–1 |
| Diamond without platform mirror | False hope on open-leg | Diagnose chain; else open-leg/human |
| Credit repay on merchant「还款」 | Nonsense pairs | Strong repay text + cash/loan |
| Silent skip rows | Missing facts | no-skip + documented counters |
| Tuning without full reimport | Stale semantics | Fresh DB after convert/mapping change |

---

## Implementation map (repo)

| Concern | Typical location |
|---|---|
| Parse source | `src/ft/convert.py`, `src/ft/importers/*` |
| Mapping | `src/ft/mapping.py`, user `~/.ft/mapping.yaml` |
| Import orchestration | `src/ft/application/statement_import.py` |
| Phase A hard keys | `src/ft/domain/platform_refund.py` + `RelationService._phase_a_*` |
| Phase B/C/D pure rules | `src/ft/domain/relations.py` |
| Check orchestration | `src/ft/application/relations.py` |
| Raw join for scan | `list_detailed` + `FactView.raw_payload` |
| Specs | `specs/00N-*/` (contracts for payload + scan phases) |

---

## Definition of done (new source)

- [ ] Taxonomy table in spec with import + scan roles
- [ ] Account semantics verified on real samples (esp. withdraw/product/repay)
- [ ] Payload contract tests green
- [ ] `source_group` (or equivalent) classifies the source
- [ ] Phase ownership explicit (A/B/C/D); no import-time relations
- [ ] Fresh full import + `relations check`; pending/open-leg explained
- [ ] Dual-backend matrix if schema/persistence touched
- [ ] Research/decisions note pitfalls and any rule merges
- [ ] No silent skips; no amount netting

---

## Collaboration with Spec Kit / gstack

1. Spec/clarify/plan/tasks/analyze first  
2. Implementer agent for product code when required  
3. Real-bill calibration is **in-scope product validation**, not optional QA  
4. gstack review after code; re-open spec if calibration forces product changes  

When the user asks only for exploration (“would this source fit?”), run steps 1–2 and report
gaps without implementing.
