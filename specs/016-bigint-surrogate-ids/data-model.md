# Data Model: 016 Bigint Surrogate IDs

## Surrogate PK (in-scope)

| Table | PK | Type after 016 |
|---|---|---|
| accounts | id | integer autoincrement |
| cash_transactions | id | integer autoincrement |
| investment_events | id | integer autoincrement |
| transaction_relations | id | integer autoincrement |
| account_aliases | id | integer autoincrement |

## FKs rewritten to integer

- cash_transactions.account_id → accounts.id  
- investment_events.account_id → accounts.id  
- account_aliases.account_id → accounts.id  
- transaction_relations.primary_fact_id / secondary_fact_id / ordered_* / anchor_fact_id → int (typed by fact_type)  
- account_lifecycle_events.account_id → accounts.id  
- valuation_observations.owner_account_id → accounts.id (nullable)  
- wealth_coverage_dispositions.owner_account_id → accounts.id  
- any other account UUID FK found in models  

## Unchanged business keys

- workspaces.id (slug)  
- cash/investment source_type, record_id, source_payload  
- wealth string PKs  

## Constraints

- Keep uq_accounts_workspace_name, uq_*_workspace_id composite uniqueness patterns (workspace_id, id) with int id  
- Keep partial unique on cash (workspace, source_type, record_id) active  
- Keep investment unique on (workspace, source_type, record_id) non-empty  
- `transaction_relations.ordered_fact_a` 与 `ordered_fact_b`：可空整数；旧值为 `NULL` 时迁移后仍为 `NULL`。旧值非空但无法映射到对应事实时失败关闭。
