# Contract: CLI Multi-Currency Accounts

## Account lifecycle

### `ft acct add NAME --type TYPE [--currency CODE]`

- Creates workspace-unique account by **name** only.
- `--currency` optional: if present, may seed zero balance pocket for that currency; **must not** become permanent account identity.
- Duplicate name → non-zero error, no write.

### `ft acct list` / finance report account section

- One account entity may show **multiple currency balance lines** (or equivalent multi-pocket display).
- Not one account row per currency identity.

### `ft acct rename OLD NEW`

- Name-scoped; **no** `--currency` required for disambiguation.
- Target name conflict → fail.

### `ft acct delete|activate|deactivate NAME`

- Name-scoped; **no** `--currency` disambiguation.
- Delete fails if account still has formal facts/dependencies.

## Cash writes

### `ft add --account NAME --currency CODE --amount ...`

- `--currency` **required**.
- Writes fact currency=CODE; updates only that pocket.

### `ft checkin NAME --balance X --currency CODE`

- `--currency` **required**.
- Sets only that pocket; wealth cash observation identity is account+currency.

### Missing/invalid currency

- Fail closed; no write.

## Transfer

### `ft transfer --from A --from-currency C1 --to B --to-currency C2 --amount X [--to-amount Y]`

- Resolve accounts by **name only**.
- Pocket selection uses from/to currencies.
- If C1 != C2: `--to-amount` required; else may ignore redundant to-amount with warning.
- Same name A=B with C1!=C2 allowed as generic multi-pocket transfer (not FX product).

## Import / convert (004 + 005)

### `ft import FILE --source SOURCE ...`

- Still **no** `--account`.
- Mapping → account **name**.
- Row currency → fact/pocket on that account.
- Must **not** fail for “row currency != account currency”.
- Account name missing → full batch rollback.
- Digest idempotent unchanged.

### `ft convert`

- Same account-name routing as import; row currencies preserved.

## Explicit non-contracts

- No home/default account currency for cash writes.
- No `find(name, currency)` ledger-book API after migration.
- No formal FX event CLI in this feature.
