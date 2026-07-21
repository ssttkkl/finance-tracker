# Contract: Import Acceptance Counts

## Success
```
source_transaction_lines
  = newly_published_formal_facts
  + idempotent_hits
  + skipped_unpaid_closed
  + skipped_failed_repay
```

## Fail closed
- Parse error, illegal amount, mapping miss → batch fails or row-addressable failure; MUST NOT report success with silent drops.

## Skip reasons (whitelist only)
| code | when |
|---|---|
| unpaid_closed | FR-008a |
| failed_repay | FR-008c |

WeChat corpus: both counters may be 0.
