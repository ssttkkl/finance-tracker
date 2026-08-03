# Contract: apply_investment_* base set

```text
apply_investment_event(snapshot, row, *, default_currency, base_tickers: AbstractSet[str] | None = None)
apply_investment_command(snapshot, command, *, account_type, default_currency, base_tickers: AbstractSet[str] | None = None)
```

`base_tickers`: lowercase. `None` or empty → DEFAULT_BASE_TICKERS.

Base leg: face accounting. Non-base: cost basis.
