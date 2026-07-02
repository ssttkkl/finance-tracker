# Security/Polymarket Sync Hardening Checklist

Use this when adding or reviewing `ft stock ...` import/sync commands, especially external broker/API imports such as Polymarket.

## Account targeting
- Validate the target account before any network call or file write.
- Match accounts by `(account_name, currency)` and require `type == "security"`; name-only checks are unsafe because the same account name can exist in multiple currencies/types.
- Custom `--account` sync paths must be tested positively, not only the default account.

## Dedupe semantics
- Scope external transaction-hash dedupe to the target `account_name`; two Polymarket accounts may legitimately contain the same hash.
- Within a fresh fetched batch, do not drop distinct rows solely because they share one `transactionHash`; one chain tx can contain multiple fills/markets. Collapse only exact duplicate row identities.
- Keep the tx hash in `note` for auditability.

## Mixed security CSV schemas
- `records/security/*.csv` may contain stock rows and transfer/checkin audit rows on the same day.
- Any writer touching a security CSV must preserve the union of existing columns plus stock `CSV_FIELDS`; do not rewrite with a narrow schema that drops transfer columns or crashes on extra fields.
- CLI helpers (`add`, `checkin`, `stock append`), transfer writers, generic append writers, and direct stock operations all need this path.

## Atomicity and rollback
- Snapshot and security CSV writes should be temp-file + atomic replace.
- Direct stock operations must roll back both snapshot and same-day CSV if either write fails.
- Multi-day `stock append` must restore all touched day files and snapshot if a later read/write or `repair_security()` fails.
- Validate non-finite values at input, derived-value, cumulative replay, and proposed-snapshot levels before persisting.

## Tests to add before implementation
- Red tests for non-security account rejection and same-name/different-currency account selection.
- Red tests for account-scoped transaction-hash dedupe and custom account dry-run.
- Tests that same-day mixed stock/transfer rows preserve all columns after each writer path.
- Rollback tests for direct stock audit write failure, snapshot save failure, multi-day append failure, and cumulative overflow.
- CLI tests for non-zero exit when `stock append` returns false and when `sync polymarket` validation fails.

## Review prompt notes
- Ask Codex/reviewer to focus on account scoping, idempotence/dedupe semantics, mixed CSV schema preservation, and transactional file writes.
- Provide real verification output: targeted pytest, `ft verify`, and a dry-run sync. If full pytest has unrelated legacy failures, list the failing test names explicitly and do not claim full pass.
