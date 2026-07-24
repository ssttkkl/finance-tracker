# Feature Specification: Investment cash event kinds (fee + dividend)

**Status**: Complete  
**Branch**: delivered on `011-usmart-hk-import`

## Converged actions

| Action | Cash effect | uSmart examples |
|--------|-------------|-----------------|
| `deposit` / `withdraw` | + / − funding | 入金, EDDA入金, 出金, 户内调拨(P1) |
| `dividend` | + | 红利入账 |
| **`fee`** | **− charge or + refund** | 融资利息, 融券罚息, 股息税, 代收费; **税退/费用退回** (same action, opposite legs) |
| `swap` / `checkin` | as today | trades, FX, alignment |

## Fee refund rule

- Tax/fee **charge**: `action=fee`, `from_amount=|amt|` (cash out)
- Tax/fee **refund** (e.g. 资金存 + Refund tax, or positive fee-like note): `action=fee`, `to_amount=|amt|` (cash in)
- **IPO 认购退款**: still `deposit` (subscription funding return, not a tax/fee ledger line)

Projection: `fee` with `to_amount>0` and `from_amount=0` increases base cash; otherwise decreases via `from_amount`.
