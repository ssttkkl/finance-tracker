---
name: statement-source-onboarding
description: >
  Add or upgrade a statement/bill source for import + relation scanning in finance-tracker.
  Use when onboarding a new bank/platform export, fixing source mapping/account semantics,
  or extending payment_mirror / refund_offset / transfer_pair for a new bill type.
  Core method: real bills → native taxonomy buckets (e.g. Alipay status×direction) →
  per-bucket feature inventory → bucket-scoped scan rules (not global keyword soup).
  Encodes the 007 real-bill workflow, pitfalls, and rule architecture (OpenSpec + dual DB).
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
- Building or revising a source **taxonomy bucket table** (status×direction, summary×sign, …)

## Non-goals of this skill

- Does not replace OpenSpec (`$openspec-propose` → `$openspec-apply-change` → `$openspec-archive-change`)
- Does not invent financial semantics without real bill samples
- Does not implement production code in the main session when project rules require
  `$openspec-apply-change` (follow CLAUDE.md / constitution)

---

## Architecture you must respect

```text
Import (facts only; post-015)
  parse → formal cash_transactions with inline source_payload
  idempotent on source_type × record_id
  NO refund_offset / payment_mirror / transfer_pair written at import time
  (no raw_records / import_batches tables)

relations check (one shot, ordered)
  Phase A  platform hard-key refund_offset   (source-specific adapters)
  Phase B  payment_mirror                     (platform × bank)
  Phase C  transfer_pair / credit_repayment  (taxonomy gate → fine match)
  Phase D  bank/weak refund + diamond        (open-leg last)
```

Dual backend (SQLite + PostgreSQL), Decimal money, formal facts immutable, workspace isolation.

---

## Golden workflow (do not skip)

### 0. OpenSpec change

1. New or living feature under `openspec/changes/<name>`
2. Spec must state: acceptance counts, raw payload contract, scan phase ownership,
   non-goals (no silent skip, no amount netting, no import-time relations unless
   product explicitly revisits that decision)
3. Proposal/specs/design/tasks + `openspec validate` before implementation

### 1. Real bills → taxonomy buckets → per-bucket features → bucket-scoped rules

**This is the house method.** Do not start from global keywords (“凡含转账/退款就怎样”).
Start from **source-native axes**, fill **buckets**, then write **rules that only fire inside buckets**.

```text
真实账单全集
    ↓
选划分键（导出原生字段，可组合）
    ↓
桶表：每个 (键₁ × 键₂ × …) 一格 + 条数
    ↓
逐桶：样本特征 / 账户语义 / 是否 import / 扫描角色 / 有无硬键
    ↓
按桶制定 import 变换 + 扫描规则（Phase A/B/C/D 归属）
    ↓
全量 import + check 校准；只在目标桶上收紧/放宽
```

#### 1.1 Choose bucket keys (partition fields)

Prefer fields **exported by the source**, stable across files:

| Source style | Primary axes (worked examples) |
|---|---|
| Alipay | **`交易状态 × 收/支`**（再加金额=0/≠0 若需要） |
| WeChat | **`收/支 × 当前状态 × 交易类型`** |
| CCB debit XLS | **`摘要 summary × 正负号`**（+ 卡号） |
| ICBC debit/credit PDF | 支付方式/摘要 × 正负；退货标记 |
| New source | Ask: which columns make **mutually exclusive, high-coverage cells**? |

Good keys: few values, high coverage, product-meaningful.  
Bad keys alone: free-text title, noisy merchant (use later as **features inside a bucket**).

#### 1.2 Build the bucket table (mandatory artifact)

For each cell count rows on real multi-year samples. Put the table in `spec.md` or
`attachments/*-taxonomy.md` (see 007 Alipay 19-bucket map, WeChat status×type, transfer attachment).

Per cell fill:

| Field | Question |
|---|---|
| **n** | How many rows? |
| **Import** | must import / whitelist skip+counter / fail-closed |
| **Book account** | which account; mapping key vs evidence-only fields |
| **Sign/category** | expense/income/neutral transform |
| **Scan role** | refund origin / refund leg / mirror platform / mirror bank / transfer out/in / credit repay / none |
| **Hard key?** | txn equality/prefix, mer=txn, dual-row, **none → fuzzy phase** |
| **In-bucket features** | e.g. 提现-实时提现, 余额宝-转出到银行卡, 消费退货, pay method empty |

#### 1.3 Features inside the bucket (second pass)

Only after the table exists, open each **non-empty important bucket** and list subtypes:

- Alipay `交易成功×支出`: 成功消费 vs 提现 vs 转账到银行卡 — **same cell, different features**
- WeChat `收入×提现已到账×零钱提现`: withdraw receipt (book wallet − after fix)
- CCB `银联入账×+`: channel in; pair as transfer/mirror not generic income

Features become **predicates** (`description contains …`, status set, pay method set),
not a second global soup.

#### 1.4 Rules are bucket-scoped

| Hard key in bucket | Scan placement |
|---|---|
| Order/dual-row unique | **Phase A** source adapter |
| Same physical payment, dual export | **Phase B** mirror (often same mapped account) |
| Self-account move / repay | **Phase C** transfer (taxonomy gate = bucket labels) |
| Bank return, no hard key | **Phase D** (+ diamond if chain exists) |

**Gate then fine-match:** Phase C taxonomy gate **is** the bucket label set; fine match
only runs on gated seeds/candidates.

**Pitfall (lived):** Global “转账/退款” tokens without buckets → P2P flood, false credit_repay,
weak mirror noise.  
**Pitfall (lived):** Writing scan rules before account semantics → optimized the wrong fact.

#### 1.5 Worked references in-repo

- Alipay status×direction map: `openspec/specs/007-closed-trade-refund-import/spec.md` appendix
- WeChat dual-row / status×type: same spec appendix  
- Transfer buckets: `openspec/changes/archive/2026-08-01-007-closed-trade-refund-import/legacy/007-closed-trade-refund-import/attachments/transfer-source-taxonomy.md`
- Calibration: fresh DB, full import, `relations check`, pending by rule_id  

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

### 3. Inline source_payload contract (post-015)

Every formal fact must retain **pairing fields** in `source_payload` (JSON on the
fact row; there is no separate `raw_records` table):

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
- **Bucket keys:** (e.g. 状态×收/支 / status×type / summary×sign)
- **Bucket table:** path to full map; n per cell; import + scan role per cell
- **In-bucket features:** (subtype predicates used for rules)
- Whitelist skips: (reason codes, which cells)
- Book account rules: (withdraw, repay, product, purchase) per cell
- Raw payload keys: (must include every bucket key + pairing fields)
- source_group: platform | bank | other (tokens to add)
- Phase A: which **cells** + hard keys (none | order | dual-row | …)
- Phase B: which **cells** are mirror legs; same-account mapping?
- Phase C: which **cells** are transfer/repay seeds; exclude cells
- Phase D: which **cells** are bank refund; diamond feasibility
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
| Global keyword rules without buckets | P2P/false repay flood | Taxonomy table first; bucket-scoped predicates |
| Features before partition | Missing rare cells | Full bucket census with counts first |

---

## Implementation map (repo)

| Concern | Typical location |
|---|---|
| Parse source | `src/ft/convert.py`, `src/ft/importers/*` |
| Mapping | `src/ft/mapping.py`, user `~/.ft/mapping.yaml` |
| Import orchestration | `src/ft/application/statement_import.py` |
| Phase A hard keys | `src/ft/domain/platform_refund.py` + relations Phase A |
| Phase B/C/D pure rules | `src/ft/domain/relations/`（竖切 transfer/refund/mirror） |
| Check orchestration | `src/ft/application/relations.py` |
| Payload for scan | fact `source_payload` / detailed list views |
| Specs | `openspec/changes/<name>/`；导入语义见 `docs/import-flow.md` |

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

## Collaboration with OpenSpec / gstack

1. explore/propose/validate first
2. Use `$openspec-apply-change` for product code when required
3. Real-bill calibration is **in-scope product validation**, not optional QA  
4. gstack review after code; update the OpenSpec change if calibration forces product changes

When the user asks only for exploration (“would this source fit?”), run steps 1–2 and report
gaps without implementing.
