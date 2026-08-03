# Feature Specification: Investment cash event kinds

**Status**: Complete (living with 011 branch)

## Actions

| Action | Use |
|--------|-----|
| `deposit` / `withdraw` | True customer funding; internal transfers (P1) |
| `dividend` | 红利入账 |
| `fee` | Charges **and refunds** of tax/interest/handling (signed legs) |
| `ipo` | IPO **认购扣款** (cash out) and **认购退款** (cash in); not equity swap |
| `swap` | Equity trades, FX only (not IPO claim lifecycle) |
| `checkin` | Alignment |

## IPO (`action=ipo`)

Cash-only lifecycle. No synthetic IPO asset / claim ticker required.

| Step | Event |
|------|--------|
| 认购扣款 | `ipo` cash out: `from_ticker=hkd|usd`, `from_amount=…` |
| 认购手续费 | `fee` cash out |
| 认购退款 | `ipo` cash in: `to_ticker=…`, `to_amount=…` |
| 中签 (future) | separate equity `swap` when allotment appears on statement |

Stock code (e.g. 02553) may appear in App notes but is often **absent** from PDF 资金出入; keep in `note` when present, do not invent claim positions.

## Fee refunds

Same `fee` action: charge uses `from_amount`, refund uses `to_amount`.

## Projection

`fee` and `ipo` share signed cash semantics:
- `from_amount > 0` → reduce cash
- `to_amount > 0` and `from_amount == 0` → increase cash
