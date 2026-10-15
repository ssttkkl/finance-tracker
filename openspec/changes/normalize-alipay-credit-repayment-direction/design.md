## Context

See `proposal.md` for the problem and scope. `src/ft/convert.py` currently interprets selected Alipay `不计收支` rows as outflows but does not include successful `信用借还`. The current real-bill bucket has 12 successful non-expense repayment rows with positive exported amounts; four use multiple funding accounts. The existing bank statements confirm negative debits for four component amounts. See `attachments/alipay-credit-repayment-taxonomy.md` for the bucket counts.

The active SQLite database also contains 12 positive Alipay `信用借还 × 不计收支` repayment facts from an earlier import. All are atomic, have no accepted relation, and retain the original source payload.

## Goals / Non-Goals

**Goals:**

- Normalize the source-account cash direction using Alipay's structured repayment type and non-expense direction.
- Preserve exact decimal magnitude, raw source evidence, confirmed account mappings, and allocation proportions.
- Correct the already-imported affected facts through the application's existing audited update path before importing the remaining Alipay rows.
- Keep unresolved component amounts out of the committed batch.

**Non-Goals:**

- Change the meaning or matching rules of `credit_repayment` relations.
- Reclassify other Alipay `不计收支` transaction types.
- Re-import or mutate unrelated source rows, investment records, or existing human relation decisions.
- Change the API contract, database schema, or UI.

## Decisions

1. **Scope the direction rule to the source-native repayment bucket.** Normalize only Alipay `信用借还` rows whose direction is `不计收支` and which survive the existing failed-repayment filter. Apply `-abs(amount)` with `Decimal`; leave zero at zero. This follows the funding-account cash direction and avoids extending the rule to unrelated transaction types.
2. **Preserve record classification and source snapshot.** Keep `repayment` / `credit_repayment`; change only the normalized cash direction. The raw source amount and direction remain untouched in `source_payload`. The alternative of rewriting raw payload fields would destroy source evidence.
3. **Correct the active database through the ledger application service.** Update only facts whose source snapshot identifies `信用借还` and `不计收支`, whose current amount is positive, and whose record type is `repayment`. Keep the existing fact ID, mapped account, source payload, and exact magnitude; set the corrected amount and any atomic component amount to the negative value. The application service stores the correction as a manual override and refreshes snapshots/projections. A direct SQLite update was rejected because it would bypass ledger audit and projection maintenance.
4. **Require signed conservation for composite rows.** Confirmed component magnitudes remain as chosen; the corrected negative parent direction is propagated to every component. The exact `Decimal` sum must equal the parent. Do not infer the unconfirmed component split from a positive parent amount or from a partial bank match.
5. **Do not alter relation matching.** Import continues to store facts only. `credit_repayment` pairing remains in the existing relation scan phase; this change only supplies the correct source-account direction for later planning.

## Risks / Trade-offs

- [Risk] A wrong source classification could reverse a non-repayment transfer. → Gate on `txn_type=信用借还` together with `direction=不计收支`; leave every other Alipay bucket unchanged.
- [Risk] Historical corrections could affect balances or projections. → Back up the active SQLite database, restrict updates to the 12 positively signed matching facts, and verify counts, magnitudes, accounts, source snapshots, and projection availability after the operation.
- [Risk] An incomplete composite allocation could create an unbalanced ledger fact. → Keep the row out of the final import until exact component values are confirmed; require negative components to sum exactly to the parent before confirmation.

## Migration Plan

1. Save a mode-`0600` SQLite backup outside the repository.
2. Implement the parser rule and inspect sanitized output for the real source bucket.
3. Use the existing ledger command service to correct the 12 historical facts, verifying that each update retains source evidence and allocation/account identity.
4. Re-scan and preview the filtered Alipay file; confirm all repayment amounts/components are negative, component sums are exact, and the one unresolved composite row remains excluded.
5. Commit the remaining supported Alipay rows once. Audit resulting source counts and component rows.
6. If any check fails, stop the backend and restore the backup before attempting another correction.

## Open Questions

None.
