# Quickstart: Multi-Currency Accounts

```bash
export FT_DATABASE_URL='postgresql+psycopg://…/finance_tracker'   # or sqlite URL
export FT_WORKSPACE_ID='default'
uv run alembic upgrade head   # includes one-time account merge

# Create one account (optional seed currency for zero pocket)
uv run ft acct add 工行 --type cash
# or: uv run ft acct add 工行 --type cash --currency CNY

# Multi-currency cash writes (currency required)
uv run ft add --amount -12.5 --counterparty Coffee --account 工行 --currency CNY
uv run ft checkin 工行 --balance 10000 --currency CNY --date 2026-07-20
uv run ft checkin 工行 --balance 5000 --currency JPY --date 2026-07-20

# List shows multi-pocket balances under one account
uv run ft acct list

# Cross-currency transfer between accounts
uv run ft transfer --from 工行 --from-currency CNY --to 钱包 --to-currency USD \
  --amount 100 --to-amount 14

# Import: mapping → account name; row currency → pocket (no 工行 JPY booklet account)
uv run ft import ~/.ft/bills/icbc-debit.pdf --source icbc-debit --password-file /tmp/pw.txt
```

## Notes

- No account home currency; cash writes always pass `--currency`.
- Rename/delete/activate are name-scoped (no currency flag for disambiguation).
- Migration is one-shot; same-name different-type accounts must be fixed before upgrade.
