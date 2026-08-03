# Data Model: uSmart HK Import

## No new persistence tables

Reuses 009/010 entities:

| Entity | Role for 011 |
|--------|----------------|
| ImportBatch | Job metadata; source_kind includes usmart-hk |
| RawFile | PDF or text fixture bytes + digest |
| RawRecord | source_type=`usmart_hk_pdf`, source_identity recipes |
| InvestmentEvent | actions: swap, deposit, withdraw, dividend, checkin only |
| LedgerSnapshot | security account positions incl. cash tickers hkd/usd/cny |
| Account | type security |

## Parse-side structures (in-memory)

### UsmartHkStatement
- period: `YYYY-MM`
- account_no: optional redacted
- markets: list[{ccy, ending_cash?, ending_mv?, ...}]
- trade_groups: list[UsmartHkTradeGroup]
- holdings: list[{ticker, ccy, shares, ...}]
- cash_movements: list[UsmartHkCashMovement]
- fx_swaps: list (after pairing)

### UsmartHkTradeGroup
- trade_date, settle_date, market, side (buy|sell), ticker, ccy
- qty, gross (交易金额), net (变动金额合计 signed), commission
- fills: optional audit list
- fees_detail: dict optional

### UsmartHkCashMovement
- date, flag, ccy, amount (signed), note
- ignored: bool (trade mirror)

## Event field mapping (unified row)

| Source | action | from_ticker | to_ticker | from_amount | to_amount | commission | currency |
|--------|--------|-------------|-----------|-------------|-----------|------------|----------|
| BUY group | swap | {ccy} | {ticker} | gross | qty | net_fees | CCY |
| SELL group | swap | {ticker} | {ccy} | qty | gross | net_fees | CCY |
| FX pair | swap | ccy_out | ccy_in | abs_out | abs_in | 0 | OUT or research lock |
| 出金/利息/负转账 | withdraw | {ccy} | | abs | 0 | 0 | CCY |
| 入金/IPO/正转账 | deposit | | {ccy} | 0 | abs | 0 | CCY |
| 期末结余 | checkin | | {ccy} | 0 | balance | 0 | CCY |
| 持仓 | checkin | {ticker} or to | {ticker} | 0 | shares | 0 | pos ccy; price 0 |

## Validation rules
- Unknown cash flag → abort
- Fee commission < -tolerance → abort
- Unpaired 换汇 → abort
- Account type not security/crypto → abort (existing)
- Snapshot finite validation after apply (existing)

## Dual-backend
Identical event payloads and projection; no dialect branches in importer.

## Projection: multi-currency cash (011)

Cash positions use tickers `usd`/`hkd`/`cny`/… with **`cost_currency` = that ticker’s ISO upper**.

Cross-currency `swap` (FX) MUST NOT force both legs to share event `currency` for `_position` cost tagging. Equity legs still use event `currency` as cost currency (009).

Cash↔cash swap: each leg face-value cost in own currency (`target_cost = to_amount` when to-ticker is cash).
