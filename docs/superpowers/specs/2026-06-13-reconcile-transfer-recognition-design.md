# Reconcile Transfer Recognition Design

> Date: 2026-06-13
> Status: Approved for implementation planning
> Related: `src/ft/reconcile.py`, `src/ft/models.py`, `src/ft/report.py`,
> `src/ft/snapshot.py`, `src/ft/stock.py`

## Context

`ft reconcile` currently runs after import and only removes duplicate records.
Imported account transfers still appear as normal `income` and `expense` rows,
so reports count them as real income or spending.

The ledger now supports `(account_name, currency)` account identity. That makes
it possible to connect two transfer-side records even when the account names
are the same but currencies differ, such as a multi-currency credit card.

Real data inspection showed several distinct transfer shapes:

- Cash account transfers are usually equal and opposite same-currency rows.
- Credit-card repayment is a cash outflow paired with a loan inflow.
- Foreign-currency credit-card repayment is a CNY cash outflow paired with a
  USD/HKD/JPY loan inflow; amounts are not equal.
- Security transfers use the stock CSV schema. Cash-side rows contain text like
  `银转证` or `证转银`; security-side rows are `DEPOSIT` or `WITHDRAW`.
- Some bank rows store `00:00:00` in `date` while the real time appears in
  `counterparty` or `description`, for example `09:35:35`.

## Goals

1. Add a `transfer_account` column to normal cash/loan/lend CSV records.
2. Replace the generic normal-record `transfer` category with directional
   categories:
   - `transfer_out`
   - `transfer_in`
3. Extend `ft reconcile` so it recognizes high-confidence transfer pairs after
   duplicate removal.
4. Exclude recognized transfers from income and expense reports.
5. Support cash transfers, credit-card repayment, security cash movement, and
   foreign-currency repayment/exchange cases.
6. Keep audit output for both automatic matches and ambiguous candidates.

## Non-Goals

- Do not introduce stable transaction ids.
- Do not add an exchange-rate column in this step.
- Do not rewrite stock `BUY` or `SELL` records as transfers.
- Do not automatically match low-confidence transfer-like records.
- Do not infer a missing security-side row when only the cash-side bank row
  exists.

## Data Model

Normal records gain one column:

```text
transfer_account
```

`models.CSV_FIELDS` changes from:

```python
["date", "amount", "currency", "counterparty", "description", "category",
 "account_name", "source", "bill_source"]
```

to:

```python
["date", "amount", "currency", "counterparty", "description", "category",
 "account_name", "source", "bill_source", "transfer_account"]
```

The field stores the opposite account name. The opposite record's currency
continues to live in that record's own `currency` column.

Directional transfer categories:

- Negative side: `transfer_out`
- Positive side: `transfer_in`

Legacy `category=transfer` remains readable and excluded from income/expense,
but new writes should use `transfer_out` and `transfer_in`.

Security records keep their existing stock schema:

```text
date,action,ticker,shares,price,amount,commission,currency,account_name,note
```

Security transfer recognition only reads `DEPOSIT` and `WITHDRAW` rows and does
not add `transfer_account` to stock CSV files in this step.

## Candidate Normalization

`reconcile` builds an internal transfer-candidate model from records in scope.

Normal cash/loan/lend records participate when:

- `category` is `income` or `expense`
- amount is non-zero
- account exists

Security records participate when:

- `action` is `DEPOSIT` or `WITHDRAW`
- amount is non-zero

The candidate model includes:

- record type: `cash`, `loan`, `lend`, or `security`
- account name
- currency
- signed amount
- source file path
- original row dict
- effective datetime
- combined searchable text from counterparty, description, source, bill source,
  action, note, and account name

## Effective Datetime

Matching uses `effective_datetime`, not always the raw `date`.

Rules:

1. Parse `date` as `YYYY-MM-DD HH:MM:SS`.
2. If parsed time is not `00:00:00`, use it.
3. If parsed time is `00:00:00`, scan row text for `HH:MM:SS`.
4. If a valid time is found, combine it with the row date.
5. Otherwise keep the parsed midnight timestamp.

This is required because bank debit records often use midnight in `date` and
put the real transaction time in `description`.

## Matching Rules

`reconcile` runs transfer recognition after duplicate removal. Already matched
or already directional-transfer records do not participate again.

### 1. Same-Currency Exact Transfer

Automatically match when all conditions hold:

- one negative candidate and one positive candidate
- same currency
- absolute amounts differ by at most `0.01`
- accounts differ by `(account_name, currency)`
- effective datetimes differ by at most 10 seconds
- at least one side contains a strong transfer signal

Strong signals include:

- `转账支取`
- `转账存入`
- `银联入账`
- `手机银行`
- `转帐`
- `还款`
- `花呗`
- `月付`

This covers cash-to-cash transfer and same-currency credit-card repayment.

### 2. Foreign-Currency Loan Repayment / Exchange

Automatically match when all conditions hold:

- negative side is a normal cash account
- positive side is a normal loan account
- currencies differ
- positive side contains `手机银行` or `转帐`
- effective datetimes differ by at most 10 seconds
- no same-currency exact match is available for either side

Amounts are not required to match. Each side keeps its own amount and currency.
This covers CNY repayment of USD/HKD/JPY credit-card balances and is also the
shape needed for future purchase/settlement exchange handling.

If multiple negative cash rows and multiple positive loan rows appear in the
same 10-second cluster, use nearest-time matching with one-to-one consumption.
Ties remain unmatched and are written to candidate audit only.

### 3. Cash / Security Transfer

Automatically match when all conditions hold:

- one side is a normal cash account
- the other side is a security `DEPOSIT` or `WITHDRAW` row
- currencies match
- cash-side text contains one of:
  - `银转证`
  - `证转银`
  - `银行转证券`
  - `证券转银行`
- direction is consistent:
  - `银转证` / `银行转证券`: cash negative + security `DEPOSIT`
  - `证转银` / `证券转银行`: cash positive + security `WITHDRAW`
- absolute amounts differ by at most `0.01`
- effective datetimes are on the same date

Security rows are not rewritten in this step. The normal cash row receives the
appropriate transfer category and `transfer_account=<security account>`.
The security row appears in audit output as the linked counterpart.

## Conflict Handling

Each candidate can be consumed by at most one transfer match.

If a candidate has more than one equally valid match, do not modify any of
those rows automatically. Write the alternatives to audit with status
`transfer_candidate`.

Automatic matches use the following priority:

1. Same-currency exact transfer
2. Foreign-currency loan repayment / exchange
3. Cash / security transfer

## Row Mutation

For matched normal records:

- amount < 0:
  - `category = transfer_out`
  - `transfer_account = <counterpart account name>`
- amount > 0:
  - `category = transfer_in`
  - `transfer_account = <counterpart account name>`

For matched security records:

- Do not mutate the stock CSV.
- Include the security row in the transfer audit.

When rewriting normal CSV files, always write the expanded `models.CSV_FIELDS`
header including `transfer_account`. Missing input values are treated as empty.

## Reports and Snapshot

Income and expense reports count only:

- `category=income`
- `category=expense`

They must exclude:

- `transfer`
- `transfer_in`
- `transfer_out`
- `checkin`

Snapshot rebuild for cash/loan/lend also skips all transfer categories:

- `transfer`
- `transfer_in`
- `transfer_out`

`report_flow` should read directional transfer records and summarize them using
`account_name`, `transfer_account`, currency, and amount.

## Audit Output

The reconcile audit should include both dedup and transfer decisions.

For automatic transfer matches, write rows with:

- `reconcile_status=transfer_matched`
- `transfer_side=out|in|security`
- `match_rule`
- `match_confidence=auto`
- `record_file`
- `counterpart_file`
- `counterpart_account`
- `counterpart_currency`
- `counterpart_amount`

For ambiguous candidates, write rows with:

- `reconcile_status=transfer_candidate`
- `match_confidence=manual_review`
- same counterpart metadata where available

Existing dedup audit rows remain available. Field names may be expanded, but
existing dedup information must not be lost.

## Migration

Historical normal CSV files without `transfer_account` remain readable.

The first `ft reconcile` run that touches a normal day file rewrites that file
with the new header. Untouched files do not need eager migration.

`ft append`, `ft add`, `ft checkin`, and manual `ft transfer` writes should emit
the new column immediately after implementation.

## Testing

Cover these cases:

- Existing normal CSV without `transfer_account` is read and rewritten with the
  new column when touched.
- Same-currency cash transfer becomes `transfer_out` / `transfer_in`.
- Same-currency credit-card repayment becomes `transfer_out` / `transfer_in`.
- Foreign-currency credit-card repayment matches by effective time, not amount.
- Bank rows with raw midnight date match using `HH:MM:SS` from description.
- Cash/security transfer links cash row to `DEPOSIT` or `WITHDRAW` audit
  counterpart without rewriting security CSV.
- Ambiguous same-time candidates are not mutated and are written to audit.
- `report_expense`, `report_income`, snapshot rebuild, and `report_flow` handle
  the new categories correctly.

## Rollout

1. Implement field and category constants.
2. Update all normal-record writers to emit `transfer_account`.
3. Add transfer-recognition helpers in `reconcile`.
4. Extend report and snapshot skip logic.
5. Add tests for same-currency, FX repayment, cash/security, ambiguity, and
   reporting.
6. Run the full test suite.
7. Run `ft reconcile` on a narrow month and inspect the audit before applying
   broadly.
