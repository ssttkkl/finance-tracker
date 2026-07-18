# CLI Contract

## Retained PostgreSQL commands

- `ft acct add|list|rename|delete|activate|deactivate`
- `ft report`, `ft list`
- `ft add`, `ft checkin`, `ft transfer`
- `ft stock buy|sell|swap|deposit|withdraw|dividend|checkin|list`
- direct statement import for the providers supported at feature start

All retained runtime commands require PostgreSQL configuration and the configured workspace.

Direct import syntax:

```text
ft import FILE --source {alipay,wechat,icbc,icbc-debit,ccb-debit,dfzq}
               --account NAME [--password-file FILE] [--currency CURRENCY]
```

The command parses and commits the original source in one database transaction. `--currency` accepts the currently
supported explicit currencies and never converts numeric input through float.
Encrypted PDF passwords are read from the first line of a user-supplied file. Inline `--password` values are not
accepted, and the password is never forwarded in a child-process argument.

Cash write commands select accounts explicitly: `ft add` and `ft checkin` require `--currency`; `ft transfer`
requires `--from-currency` and `--to-currency`. Transfer amounts must be positive.

`ft acct delete NAME --currency CURRENCY` succeeds only for a deactivated account with no formal facts. Active
accounts and referenced accounts fail with a message directing the user to `ft acct deactivate`.

## Pure file boundary

Statement conversion may write a user-selected output file for inspection. The output is not registered as
a ledger, snapshot, transaction log, pending session or fallback store.

## Removed commands

- `ft commit`, `ft status`, `ft reset`
- `ft migrate inspect|import|verify|export`
- CSV/snapshot `ft verify --fix`
- converted-ledger `ft append` and `ft stock append`
- file-backed `ft reconcile`
- local-backed `ft stock sync`

Removed commands do not retain aliases or compatibility messages beyond argparse's normal unknown-command error.

## Failure behavior

- Missing database/schema/workspace: non-zero exit with a direct message.
- Invalid original input: non-zero exit identifying source/provider and record location where available.
- Any application-service rejection of a write, including a missing/non-investment stock account: non-zero exit.
- Failed transaction: no partial formal facts or completed batch.
- Existing `~/.ft`: ignored and untouched.
