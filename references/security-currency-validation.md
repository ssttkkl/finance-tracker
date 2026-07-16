# Security Allowed Cash/Settlement Currency Validation

Manual stock commands that write `records/security/*.csv` use account-scoped currency validation:

- Applies to `ft stock buy`, `sell`, `swap`, `deposit`, `withdraw`, `dividend`, and `checkin`.
- The target account must exist in `accounts.yaml` and must be `security` or `crypto`.
- `--currency` is case-insensitive and must be listed in the account's allowed cash/settlement currencies (`base_currencies`).
- Security and crypto accounts with `base_currencies` have no primary/default/reporting currency; direct manual commands require explicit `--currency`.
- Older accounts without `base_currencies` are the only fallback: they allow and infer their legacy `currency`.
- Legacy account `currency` is not required to appear in `base_currencies`.
- Direct manual writes normalize row `currency` and currency-like `commission_asset` to uppercase; cash tickers are stored as lowercase position keys.
- If a non-currency ticker already has a non-zero position, new writes cannot change its `cost_currency`. The command fails before changing snapshot or records.
- Once a position is fully closed and cleaned up, the same ticker may be opened later with another configured base currency.
- Existing-stock cash dividends must be recorded in the stock position's `cost_currency`; conversion to another currency is a separate swap.
