# Contract: CLI — uSmart HK import

## Command

```bash
ft import <file.pdf|file.txt> --source usmart-hk --account <name> \
  [--password-file <path>] [--currency <CCY>]
```

### Arguments
| Arg | Required | Rules |
|-----|----------|--------|
| file | yes | PDF (encrypted OK) or redacted `.txt` fixture |
| --source | yes | `usmart-hk` (alias `usmart_hk` accepted) |
| --account | yes | existing account type security or crypto |
| --password-file | if PDF encrypted | password not preferred on argv |
| --currency | no | optional default only; events use native ccy |

### Success
- Exit 0
- Message includes imported novel event count (or 0 if all identities known)
- details may include batch_id, ignored_mirror_count

### Errors (non-zero, no partial facts)
| Condition | User-visible intent |
|-----------|---------------------|
| account missing / wrong type | clear account error |
| bad password / decrypt fail | decrypt failed; check password-file |
| qpdf/mutool missing | install hint |
| unknown 业务标志 | list flag + snippet |
| fee imbalance | group identity + amounts |
| unpaired 换汇 | list unpaired legs |
| unsupported source typo | list allowed sources |

### Idempotency (010)
- Re-import same identities: novel count 0, no snapshot drift
- Overlapping files: only new identities apply

### Out of scope CLI
- `ft stock transfer` new command
- Web import UI
