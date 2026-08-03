# Research: uSmart HK Import

## Decision log

### D1 — CLI source string
- **Decision**: Primary `--source usmart-hk`; accept alias `usmart_hk` (normalize to same branch).
- **Rationale**: Matches user/spec; hyphen consistent with some CLI sources (`ccb-debit`).
- **Alternatives**: `usmart` only (too vague).

### D2 — Fee contract (equity order groups)
- **Decision**: **Gross + commission** (IBKR-like), **side-aware**:
  - `gross = |交易金额|`, `net = 变动金额合计` (signed), `abs_net = |net|`
  - `commission = |gross - abs_net|` (≥ 0)
    - BUY (`net < 0`): `commission = abs_net - gross` (cash out = gross + fee)
    - SELL (`net > 0`): `commission = gross - abs_net` (cash in = gross − fee)
  - cash leg always `gross`; BUY swap cash→ticker; SELL swap ticker→cash
  - Fail if commission would be negative beyond 0.02 tolerance
- **Rationale**: Buy 1990.40 + 3.92 = 1994.32; **sell DELL** 3699.41 − 4.00 = 3695.41. Naive unsigned `abs_net − gross` yields **−4** on sells (illegal commission).
- **Alternatives**: Net-only commission=0; peel only 佣金; unsigned `abs_net−gross` only (broken on sells).

### D3 — Order group vs per-fill
- **Decision**: One SWAP per order group (fills merged qty + notional).
- **Rationale**: One 变动金额合计 / fee block per group.
- **Alternatives**: Per-fill (fee allocation undefined).

### D4 — 换汇
- **Decision**: Pair opposite-sign 换汇 rows (different ccy) within statement; one cash↔cash `swap`; unpaired fail-closed.
- **Pairing**: Prefer unique mutual match; allow ±1 calendar day; do not rewrite amounts; note rate = |hkd|/|usd| for audit only.
- **Sample**: HKD -3161.18 (2026-06-16) + USD +402.32 (2026-06-15) → swap hkd 3161.18 ↔ usd 402.32.
- **Alternatives**: Per-leg deposit/withdraw (user rejected for FX); invent rate (forbidden).

### D5 — 转账 / 日内融
- **Decision**: `withdraw` if amount < 0 (or out), `deposit` if amount > 0; note keeps flag. **No `transfer` action**, no pocket ticker.
- **Rationale**: User rollback from transfer; keep 009 action set.
- **Sample**: 转入到日内融账户 USD -1781.03 → withdraw 1781.03 usd.

### D6 — 资金出入 trade mirrors
- **Decision**: Ignore flags matching 买/卖股票、买入/卖出股票、*手续费* trade mirrors (normalize CJK).
- **Rationale**: Already in 交易明细 groups.
- **Count** ignored rows for audit in details if easy; not required for MVP success.

### D7 — CHECKIN
- **Decision**:
  - Cash CHECKIN per market with numeric 期末账户结余; date = last day of 结单日期 `YYYY-MM`.
  - Holdings CHECKIN shares only; price/cost = 0 or omit inventing cost (projection: cost = shares * price with price 0 → cost 0; **document** flow cost unreliable until later history).
- **Rationale**: No 成本价 on statement; SC-002 anchors cash + shares.
- **Alternatives**: Opening 期初 CHECKIN (optional later); skip holdings (user wants share align).

### D8 — PDF path
- **Decision**: Prefer `pdf_tools.decrypt_pdf` + `extract_pdf_text`; accept `.txt`/`.text` fixtures without tools (like dfzq).
- **Rationale**: Password not in argv; consistent security.
- **Note**: Current dfzq path still uses subprocess password=; usmart_hk should use pdf_tools (improvement, in scope for new code).

### D9 — source_type / identity
- **Decision**:
  - `source_type = usmart_hk_pdf`
  - trade: `usmart_hk:trade:{date}:{ticker}:{side}:{qty}:{gross}:{net}:{ccy}`
  - cash: `usmart_hk:cash:{date}:{flag_norm}:{ccy}:{amount}`
  - fx swap: `usmart_hk:fx:{d1}:{ccy1}:{a1}:{d2}:{ccy2}:{a2}`
  - checkin cash: `usmart_hk:checkin:cash:{period}:{ccy}:{amount}`
  - checkin pos: `usmart_hk:checkin:pos:{period}:{ticker}:{shares}`
- **Idempotency**: 010 row identity only.

### D10 — Currency resolution
- **Decision**: Each event carries its own `currency` (trade ccy / cash line ccy). CLI `--currency` optional default for checkin fallback only; multi-ccy positions via ticker hkd/usd.
- **Rationale**: Header is multi-market; single default insufficient.

### D11 — Ticker normalize
- **Decision** (all importers via `ft.importers.ticker_normalize`):
  - **US equity**: `CODE.us` (e.g. `mrvl.us`)
  - **HK equity**: `CODE.hk` (e.g. `00700.hk`)
  - **CN A-share**: `CODE.sh` / `CODE.sz` (DFZQ / A股通)
  - Cash: bare ISO (`usd`/`hkd`); FX pairs not equity
- **Rationale**: Cross-broker position identity; never bare `MRVL` vs `00700` collision risk.
- **Shared module**: `src/ft/importers/ticker_normalize.py` used by usmart_hk, ibkr, schwab; dfzq already suffix-aware.

### D12 — CJK compatibility
- **Decision**: Normalize text: map common compatibility ideographs used by mutool/PDF (⾦→金, ⼾→户, 买⼊→买入, etc.) before section matching.
- **Rationale**: Real extract uses radical variants.

## Open items for implementer (not NEEDS CLARIFICATION)
- Exact tolerance constant 0.02 for fee balance
- Whether holdings CHECKIN sets price=0 or omits price field (match dfzq holdings path if any)

## Investment source template: usmart-hk

- Export: encrypted monthly PDF (M21 style)
- Tools: qpdf + mutool
- Fee table: see D2
- Holdings: shares, no cost
- Cash balance: 期末账户结余 per market
- Actions: swap/deposit/withdraw/checkin (+ dividend reserved)
- Special: FX pair, ignore trade mirrors in 资金出入
- Account: security
- CHECKIN: cash multi + holdings shares
- Non-goals: transfer action, connector, valuation, lots

### D13 — Multi-currency cash swap projection (cost_currency)

> **Superseded by 012**: product rule is account `base_currencies` face-only; fiat hardcode is fallback only.
- **Problem**: `apply_investment_event` passes event-level `currency` into `_position` for **both** swap legs. Cash ticker `hkd` created by HKD deposit has `cost_currency=HKD`; FX swap with `currency=USD` then touches `hkd` with USD → `cost currency conflict for usd/hkd`.
- **Decision**: For investment **swap** (and consistently deposit/withdraw/checkin when the leg ticker is a **cash ticker**), each position leg uses:
  - if `ticker` ∈ known fiat cash set (`usd`,`hkd`,`cny`,`eur`,`gbp`,`jpy`, … documented in code constant): `cost_currency = ticker.upper()`
  - else (equity/crypto asset): `cost_currency = event.currency` (unchanged 009 stock behavior)
- **FX map**: still one `swap` event; `currency` field MAY be from-side or base (research lock: **from-leg ISO upper**); does not force both legs’ cost currency.
- **Cash target cost for FX**: when both legs are cash tickers, `target_cost = to_amount` (face value in to-currency), not released-from-source conversion into wrong unit — i.e. treat cash↔cash like deposit/withdraw face amounts on each pocket.
- **Rationale**: Required by usmart multi-market cash; IBKR avoided this by costing all positions in base USD only.
- **Alternatives rejected**: Represent FX as withdraw+deposit only (user requires swap); invent transfer action; skip FX and rely on CHECKIN only (loses flow audit).
- **Files**: `src/ft/domain/investment_projection.py` (+ unit tests). In scope for 011.

### D14 — Soft-start oversell (pre-existing 009)
- **Decision**: Projection allows sell/swap that drives equity shares negative (soft-start for incomplete history); CHECKIN realigns. Test `test_investment_projection_soft_start_oversell_and_numeric_overflow` documents this; does **not** reintroduce hard insufficient-position abort.
- **Rationale**: Monthly imports without full history; 009 comment in investment_projection.

### D15 — Flat position CHECKIN (absent from 持仓明细)
- **Decision**: After open holdings CHECKINs, for every equity ticker in 交易明细 but not in 持仓明细, emit checkin shares=0 (period-end cleared).
- **Rationale**: Holdings table is EOP only; missing name means flat, not unknown. Clears soft-start negatives after sells without prior history.
- **Identity**: usmart_hk:checkin:pos:{period}:{ticker}:0
