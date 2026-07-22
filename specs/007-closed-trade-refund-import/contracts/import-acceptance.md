# Contract: Import Acceptance

- source_lines = published + idempotent + skipped_unpaid_closed + skipped_failed_repay
- mapping miss / parse error → fail closed
- import does **not** create transaction_relations
