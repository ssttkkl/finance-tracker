# Polymarket holdings in `ft`

Use Polymarket outcome tokens as pseudo securities so they can live inside the existing `security` module.

## Ticker format

- `pm:<market-slug>:yes`
- `pm:<market-slug>:no`

Rules:
- Keep the slug lowercase and exactly as stored in the tracker.
- Treat `yes` / `no` as the outcome token, not as a market filter.
- Do not normalize away the `pm:` prefix.

## Pricing / valuation

- `ft stock list` should fetch live Polymarket quotes for `pm:` tickers.
- Account and report valuation should use current market value, not only cost basis.
- Keep fractional shares visible; Polymarket fills are often non-integer.
- For resolved markets, use the held outcome's settlement price from Gamma `outcomePrices` directly. Example: `outcomes=["Yes","No"]`, `outcomePrices=["0","1"]` means `:yes` is priced at `0` and `:no` is priced at `1`; never fall back to `lastTradePrice=0` for the `:no` token.
- If `/markets?slug=<child-slug>` returns empty for a nested/stale sub-market, fall back to Gamma `public-search`, inspect returned parent `events[].markets[]`, and match the exact child `slug` before pricing.

## Resolved-market sync / settlement

`ft stock sync polymarket` should not only import Activity API trades. It must also scan current `Polymarket` snapshot positions for `pm:` tickers and auto-close any position whose live/settled outcome price is exactly `0` or `1`:

```text
SELL <shares> @ <0-or-1>
commission = 0
note = polymarket settlement
```

This preserves the audit trail and removes resolved positions without deleting historical trade rows. A winning No position should therefore add cash at `shares × 1`, not disappear at zero value.

TDD coverage to keep when touching this path:

- Direct Gamma resolved market: `outcomes=["Yes","No"]`, `outcomePrices=["0","1"]` returns `:yes=0`, `:no=1`.
- Stale child slug fallback: direct `/markets?slug=` empty, `public-search` parent event contains matching child market with settlement prices.
- Sync settlement: with an open `pm:<slug>:no` snapshot position and price `1`, dry-run returns one `SELL` settlement row for the full share count.

## Cash balance interpretation

A negative `Polymarket` cash balance in `ft stock list` usually means the security ledger is missing one or more cash-leg records (deposit, withdrawal, redemption/settlement, or a cash checkin). It is **not** proof that the platform itself has negative cash.

Important distinction:

- `ft verify` passing means `records/security/*.csv` and `snapshot.yaml` are internally consistent.
- It does **not** prove Polymarket platform cash equals the ft cash balance.

When cash is negative after Activity sync:

1. Do not invent or auto-adjust cash.
2. Explain that trades were replayed but cash legs may be incomplete.
3. Ask for the current platform available USDC/cash if the user wants a quick `stock checkin --cash` alignment.
4. For audit-quality repair, import or reconstruct deposits, withdrawals, redemptions, and settlement cash flows instead of using a blind balancing entry.

## Import snapshot

Current imported positions used to validate the integration:

- `pm:will-russia-capture-kostyantynivka-by-june-30-382-954-769:no` — `323.5`
- `pm:strait-of-hormuz-traffic-returns-to-normal-by-end-of-june:no` — `300`
- `pm:strait-of-hormuz-traffic-returns-to-normal-by-july-7-20260625174256255:no` — `16.7`

These are fractional-share positions, so import and display paths must preserve decimal precision.

## Practical gotchas

- Polymarket positions are easiest to reason about when they are shown alongside normal securities, not in a separate subsystem.
- Keep display output explicit: market title + yes/no outcome + current quote.
- Verify support with both a price lookup test and a balance/report test.

## Reference implementation notes

- Price fetching is routed through the Polymarket Gamma API.
- Existing stock logic should remain the default path for non-`pm:` tickers.
- A good regression pair is:
  - one test for `pm:` price extraction
  - one test for balance computation using live market price
