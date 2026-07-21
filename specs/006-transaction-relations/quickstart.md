# Quickstart: Transaction Relations

Validation guide for local proof of 006 behaviors. Implementation details live in tasks; this is the runnable acceptance sketch.

## Prerequisites

- Python 3.11+, `uv`
- Workspace with cash accounts used by mapping (e.g. 支付宝, 微信, 建行储蓄, 信用卡)
- `FT_DATABASE_URL` set explicitly to SQLite **or** PostgreSQL (never implicit)

```bash
# SQLite example
export FT_DATABASE_URL="sqlite:////tmp/ft-006.sqlite3"

# PostgreSQL example (real matrix)
export FT_DATABASE_URL="postgresql+psycopg://..."
export FT_TEST_POSTGRES_URL="$FT_DATABASE_URL"   # for pytest matrix if used
```

Apply migrations before tests/commands (project standard Alembic path).

## 1. Import two views of one expense (payment_mirror)

1. Import Alipay expense and bank debit for the same external purchase (same amount/currency, close time, card tail/text cross).
2. Confirm two **active** formal facts exist.
3. Confirm a `payment_mirror` relation is `accepted` or `pending_review`.
4. Confirm expense report counts once when accepted; balances show both legs.

## 2. Internal transfer (transfer_pair)

1. Import/create opposite-sign same-currency legs on different accounts with transfer signals and Δt ≤10s unique match.
2. Confirm `transfer_pair` accepted (or pending if weak).
3. Balances move both sides; external income/expense excludes the pair.

## 3. Refund offset

1. Import expense −100 and refund +30 (strong merchant/order signals, within windows).
2. Confirm both facts keep original amounts.
3. Confirm accepted `refund_offset` yields net −70 in projection; pending does not.

## 4. Review inbox

### 4a. Open-leg pending (multi-candidate refund/transfer)

1. Create one refund and **N≥2** same-merchant expenses in window (or multi transfer candidates).
2. Run relation check.
3. Expect **exactly one** `refund_offset`/`transfer_pair` pending with empty other leg; evidence lists candidate ids (≤20) and `candidate_count`.
4. `ft relations accept <id>` **without** `--other` must fail.
5. `ft relations accept <id> --other <expense_id>` succeeds → bilateral accepted; net/transfer projection updates.
6. Reject path: open-leg reject → re-check does not create another open pending for same anchor.

## 4b. Review inbox (bilateral)

1. Force a weak match (same day only / multi-candidate) → pending.
2. `accept` → report updates; audit has actor/time.
3. Another pending → `reject` → re-check does not recreate same pending key.
4. `later` → still pending, still listed, no report impact.

## 5. Logical delete + re-import

1. Create two substantive duplicate active facts (fixture).
2. User logical-delete one with reason.
3. Projection excludes deleted instance; its relations superseded.
4. Re-import same source identity (without active occupant) → **new active fact** published; old remains deleted.
5. Active identity still blocks a second concurrent active publish.

## 6. Cross-batch

1. Import bank leg in batch A; later import platform leg in batch B.
2. After B’s check, cross-batch `payment_mirror` exists; A facts unchanged.

## 7. Dual backend

Re-run the same pytest contract matrix on SQLite and real PostgreSQL:

```bash
uv run pytest tests/test_transaction_relations*.py -q
# plus project dual-backend markers once tasks define them
```

If PostgreSQL URL is unset, record missing evidence explicitly; do not claim dual-backend complete.

## Expected invariants

- Multi-candidate refund/transfer → one open-leg pending, not N bilateral rows
- Open-leg never changes reports until accept+other
- payment_mirror never open-leg


- No fact amount rewrites for pairing/refunds
- No physical deletes for pairing
- No `duplicate_of`
- Exact Decimal; no amount tolerance
- Import success survives relation-check failure
