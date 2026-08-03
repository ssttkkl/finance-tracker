# Data Model

No new tables. Uses:

- `accounts.metadata_json.base_currencies`: `string[]` uppercase
- Snapshot position: `{shares, total_cost, cost_currency}` — base legs invariant `total_cost == shares` after apply
