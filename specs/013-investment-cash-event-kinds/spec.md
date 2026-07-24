# Feature Specification: Investment cash event kinds (fee + dividend mapping)

**Feature Branch**: `013-investment-cash-event-kinds` (delivered with 011 branch)

**Created**: 2026-07-24

**Status**: Complete

**Input**: P0 cleanup — fee-class cash movements must not be generic withdraw; dividends must use dividend action.

## Decision (converged types)

| Bucket | Action | Includes (uSmart flags / notes) |
|--------|--------|----------------------------------|
| Customer funding | `deposit` / `withdraw` | 入金, EDDA入金, 出金, 提取 |
| Income from holdings | `dividend` | 红利入账 |
| All cost-of-carry / tax / brokerage charges on cash | **`fee`** | 融资利息, 融券罚息转出, 融券利息, 美股股息税, 股息代收费, 红利税费, 股息税, 罚息转出, 资金存(税退? keep deposit if positive refund — see map) |
| Broker promo | `deposit` (note) for now | 优惠券 — optional later `rebate`; P0 keeps deposit |
| IPO / tax refund | `deposit` | IPO认购退款, 资金存/Refund tax positive |
| FX / equity trades | `swap` | unchanged |
| Alignment | `checkin` | unchanged |
| Internal transfers | deposit/withdraw + note | P1, not this feature |

## Projection

- `fee`: same cash effect as `withdraw` (reduce base cash by from_amount). Unsigned amounts.
- `dividend`: existing path (increase cash; optional from_ticker when known).

## Non-goals

- New transfer action
- Lot accounting
- Changing commission-on-trade (still on swap.commission)
