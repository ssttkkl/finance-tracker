# Polymarket settlement reconciliation hardening

Use when maintaining `ft stock sync polymarket`, investigating negative Polymarket positions, or reconciling ledger positions with the public Positions API.

## Core invariant

A resolved outcome token must be closed **once**. A real `SELL` Activity fill and a synthetic `polymarket settlement` row are alternative explanations for the same reduction in token shares; they must never both close the same shares.

## Safe synchronization order

1. Fetch and normalize Activity rows. Preserve `size`, `price`, and `usdcSize` as normalized Decimal text in CSV.
2. Apply row-level deduplication to identify the Activity rows that are actually new.
3. Replay the current ledger, then apply those candidate new rows in memory to obtain **projected positions**.
4. Query a dedicated resolution-metadata source. A settlement requires explicit resolved/closed status plus the outcome payout; do **not** infer resolution solely because an estimate/quote equals `0` or `1`.
5. Generate synthetic settlement rows only for positive projected remainder. For a partial real sell, settle only the remaining shares.
6. Deduplicate synthetic settlements with a stable business identity such as `condition/token id + resolution version/timestamp`, not current date plus a generic note.
7. Write all rows atomically, rebuild snapshot, run `ft verify`, and compare with official Positions API.

## Dedupe rule: hash is not a fill identity

A single Polymarket transaction hash may contain multiple distinct fills. Never treat an existing bare `transactionHash` as proof that all future rows for that transaction were imported.

Use an API fill/activity ID when available. Otherwise use a normalized business identity including at least:

```text
transaction hash + token/ticker + side + size + USDC size + timestamp
```

A hash can be an index for efficient lookup, but exact/business-row identity decides whether to skip a row.

## Negative-position incident pattern

If a ticker has a matching real SELL and a later synthetic settlement for the same quantity, the settlement is duplicate. Verify by tracing every CSV leg for the ticker before removing anything:

```text
sum(to_amount where to_ticker=ticker)
- sum(from_amount where from_ticker=ticker)
```

Removing a duplicate settlement must also remove its artificial cash credit; do not add a balancing cash entry.

## Positions API precision

The Activity ledger can preserve more decimal places than the Positions API exposes. Compare both sides as `Decimal` at the API's demonstrated precision and report a precision match separately from a raw exact match.

Do not truncate CSV ledger quantities just to match a lower-precision API response. For example, a ledger `394.999522` and an API `394.9995` can match at 4 decimal places while remaining different raw values.

## Required regression tests

- Existing snapshot position + same-sync real SELL fully closes it: no synthetic settlement.
- Existing snapshot position + partial real SELL: settlement only closes the projected remainder.
- Quote at `0` or `1` without explicit resolved metadata: no settlement.
- Both winning and losing resolved outcomes settle with exact Decimal payout.
- Same transaction hash with a newly discovered distinct fill: import the new fill, skip only the exact old fill.
- Synthetic settlement rerun on a later date after a failed snapshot rebuild: no duplicate settlement.
- High-precision token size survives Activity normalization and ledger replay; API-precision reconciliation reports it as a precision match.
