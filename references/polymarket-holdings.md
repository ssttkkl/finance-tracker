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
- Account and report valuation should use current market price, not cost basis.
- If a live quote cannot be fetched, fall back to cost so read-only reporting keeps working.
- Polymarket Gamma API requests are more reliable with a browser-like `User-Agent`; plain urllib-style probes may 403.

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
