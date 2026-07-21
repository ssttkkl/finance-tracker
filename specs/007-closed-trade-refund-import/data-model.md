# Data Model: Import No-Skip & Closed-Trade Anchors

## Entities

### SourceTransactionLine (ephemeral parse output)

| Field | Notes |
|---|---|
| source_type | alipay / wechat / icbc_* / ccb_debit / dfzq |
| occurred_at | required |
| amount | Decimal |
| currency | required at formalize |
| direction / category | expense/income/… |
| counterparty, description | text |
| platform_status | e.g. 交易关闭, 退款成功, 交易成功 |
| txn_id | platform order id |
| origin_order_id | optional; for refunds base of txn_id |
| funding_status | `funding` \| `non_funding` |
| source_identity components | feed RawRecord identity |

### RawRecord (existing)

Unchanged identity model; **every** accepted source transaction line produces one RawRecord in a successful batch.

### CashTransaction (formal fact, extended)

| Field | Change |
|---|---|
| existing money/account/time fields | unchanged |
| platform_status | **add** (string, default "") |
| funding_status | **add** (`funding` default for success paths; `non_funding` for closed/failed) |
| origin_order_id | **add** (string, default ""; refunds set when parseable) |
| txn_id / record_id | ensure platform order id retained in payload/columns already used |

### ImportBatch acceptance counters (result DTO + optional persisted summary)

| Field | Meaning |
|---|---|
| source_lines | count of source transaction lines |
| published | new formal facts |
| idempotent_hits | lines already present |
| failed | if partial-fail model used; else batch aborts |

**Invariant (success)**: `source_lines == published + idempotent_hits`.

## Validation rules

- `funding_status=non_funding` ⇒ default balance delta 0.
- `funding_status=funding` ⇒ existing balance rules.
- Refund lines keep positive amount semantics as today; set `origin_order_id` when pattern matches.
- Closed origin keeps negative expense amount for audit but non_funding.

## State / transitions

No new lifecycle beyond publish. Logical delete remains 006.

## Dual-backend

New columns nullable or defaulted identically on PG and SQLite; no dialect-specific business branching.
