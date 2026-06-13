# Multi-Currency Account Model Design

## Context

`finance-tracker` currently stores transaction rows with both `account_name` and
`currency`, and `accounts.yaml` allows the same account name to appear in
multiple currencies. The code does not consistently treat `(account_name,
currency)` as the account identity. Several paths use only `account_name` as a
dict key, which makes same-name multi-currency accounts overwrite each other in
append routing, snapshot balances, and reports.

This blocks reliable support for credit cards with multiple currencies and for
future transfer recognition across cash, credit card, security, and FX flows.

## Goal

Support one logical account name with multiple currencies by making
`(account_name, currency)` the identity for cash, loan, and lend accounts.

## Non-Goals

- Do not rename accounts to include currency suffixes.
- Do not introduce stable account ids.
- Do not change security account storage in this step.
- Do not implement transfer recognition in this step.

## Data Model

CSV records keep the existing columns:

- `account_name`
- `currency`

`accounts.yaml` keeps the existing list format and continues allowing duplicate
names when the currency differs.

Snapshot storage for `cash`, `loan`, and `lend` changes from flat balances:

```yaml
accounts:
  loan:
    工行信用卡(1200): -124592.80
```

to nested balances:

```yaml
accounts:
  loan:
    工行信用卡(1200):
      CNY: -124592.80
      USD: -10.00
```

Security accounts keep their existing structure:

```yaml
accounts:
  security:
    IBKR:
      currency: USD
      cash: 100.00
      positions: {}
```

## Behavior

### Account Lookup

All account lookup paths that route or mutate cash/loan/lend records must use
`(name, currency)`.

If a row references an account name that exists only in another currency, append
must fail with a clear missing account message for the specific `(name,
currency)` pair.

### Append

`ft append` loads accounts into a `(name, currency) -> account` map. A row is
routed to `records/<type>/YYYY-MM-DD.csv` using the account type for the row's
exact name and currency.

The CSV row itself remains unchanged.

### Snapshot Rebuild

`rebuild_snapshot_from_records` groups cash/loan/lend rows by
`(account_name, currency)` and writes nested snapshot balances. Checkin rows
reset only the matching account and currency.

Legacy flat snapshot balances are not guessed or migrated as authoritative
state. The reliable migration path is to run `ft verify --fix`, which rebuilds
snapshot balances from CSV records.

### Balance Helpers

Balance helpers must accept currency when the caller needs a unique account.
Legacy helper behavior may remain as a compatibility shim only when the account
name is unambiguous.

### Reports

Net worth reporting expands nested cash/loan/lend balances by currency. Same-name
accounts in multiple currencies display as separate entries, for example:

```text
工行信用卡(1200) [CNY]  -124592.80
工行信用卡(1200) [USD]      -10.00
```

`acct list` also shows each `(name, currency)` row with its own balance.

`ft list --account` continues filtering by account name only for convenience.
Adding `--currency` is optional and out of scope for the first implementation.

## Compatibility

Read paths should tolerate legacy flat snapshot dictionaries to avoid breaking
existing commands before the first rebuild. Write paths should emit only the new
nested cash/loan/lend structure.

## Testing

Cover these cases:

- Same account name with CNY and USD in `accounts.yaml` routes rows using
  `(name, currency)`, not name alone.
- `rebuild_snapshot_from_records` keeps CNY and USD balances separate for the
  same account name.
- Checkin affects only the matching account and currency.
- `report_networth` lists same-name multi-currency balances under the correct
  currency groups.
- `acct list` shows separate balances for same-name different-currency accounts.
- Legacy flat snapshot reading remains tolerated where existing commands depend
  on it.

## Rollout

1. Implement code and tests for the new account identity model.
2. Run the full test suite.
3. Run `ft verify --fix` on `~/.ft` to rewrite snapshot from records.
4. Run `ft report` and inspect same-name multi-currency accounts.
5. Commit data changes separately from code changes if both are needed.
