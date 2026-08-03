# Research: Base-currency cost

### D1 — Where to put the feature
- **Decision**: New Flow-Forward `012-investment-base-currency-cost` (not Living 011).
- **Rationale**: Cross-cutting domain rule for all investment sources; 011 is usmart-specific and already Complete for import.

### D2 — Authoritative base set
- **Decision**: `account.metadata.base_currencies` (list of uppercase codes). Projection receives lowercase ticker set.
- **Empty**: fallback `DEFAULT_BASE_TICKERS` = fiat ISO set ∪ {usdt, usdc}.

### D3 — Base leg semantics
- **Decision**: After any op on base ticker: `shares` updated; `total_cost = shares`; `cost_currency = TICKER.upper()`.
- **Rationale**: No cost basis; face identity; avoids conflict; schema unchanged.
- **Swap both base**: each leg face only; no released-cost cross-booking.

### D4 — Non-base
- Unchanged 009: release proportion; target cost = released (+fee rules); cost_currency = event currency.

### D5 — Retire product reliance on KNOWN_FIAT alone
- Keep constant only as part of DEFAULT fallback or delete usages for product path once base passed.
