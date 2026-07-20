# Data Model: Multi-Currency Accounts

## Account

| Field | Notes |
|---|---|
| `id` | Stable UUID identity |
| `workspace_id` | Isolation |
| `name` | **Unique within workspace** |
| `type` | cash / loan / lend / security / crypto |
| `active` | Account-level lifecycle |
| ~~`currency`~~ | **Removed** as identity/attribute |

**Constraints**:
- `UNIQUE (workspace_id, name)` replaces `UNIQUE (workspace_id, name, currency)`
- Investment display name uniqueness among security/crypto remains application-level (existing rule)

## Currency Balance Pocket (projection)

Not a separate durable identity table.

- Cash/loan/lend snapshot: `accounts[type][name][currency] = amount`
- Written only via formal facts + deterministic projection rules
- Empty account may have zero pockets until first write or optional create seed

## Cash / Loan / Lend Fact

Unchanged columns conceptually:
- `account_id` → multi-currency account
- `currency` → pocket key (3-letter upper)
- `amount` → exact decimal

## Transfer Pair

- Two facts (or investment legs) each with own `account_id` + `currency` + amount
- Cross-currency: amounts may differ (`to-amount` required)

## Import Mapping Target

- Mapping resolves **account name only**
- Row carries independent `currency`
- Batch multi-account model from 004 retained (`target_account_id` nullable)

## Valuation Observation (cash)

| Before | After |
|---|---|
| `identity_kind=cash_account`, `identity=account_id`, owner=`account_id` | `identity_kind=cash_account`, `identity="{account_id}:{currency}"` (or equivalent), owner=`account_id`, `currency` column holds pocket currency |

Constraint change:
- Drop/replace `ck_valuation_cash_owner_identity` requiring `identity = owner_account_id`
- Keep owner required for cash_account kind

## Account Lifecycle

- Events hang on `account_id` only (account-level open/close/rename/activate)
- Lifecycle writer resolves by **name**, not name+currency

## Wealth AccountFact

- Remove or null out account-level currency field usage as identity
- Digest of account source items must not treat single currency as account identity; use type + metadata (+ multi-currency pocket set if needed for rebuild)

## Legacy merge (migration only)

For each workspace group by `name`:
1. If multiple types among rows → **fail** with conflict list
2. Else choose survivor (`created_at`, then `id`)
3. Reassign all FKs from losers → survivor:
   - cash_transactions.account_id
   - investment_events.account_id
   - account_lifecycle_events.account_id
   - valuation_observations.owner_account_id (+ rewrite cash identities)
   - import_batches.target_account_id when set
4. Delete loser account rows
5. Drop `accounts.currency` column / unique constraint; add name unique
6. Rebuild ledger snapshot from facts (or merge currency maps under survivor name)

## Dual-database schema parity

| Object | PostgreSQL | SQLite |
|---|---|---|
| `uq_accounts_workspace_name` | yes | yes |
| no account.currency column | yes | yes |
| cash valuation identity per account+currency | yes | yes |
| exact decimal amounts | NUMERIC(38,18) | text exact decimal adapter |
| fail-closed type conflict merge | yes | yes |
