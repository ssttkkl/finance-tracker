# Attachment: Transfer Source Taxonomy (Phase C)

**Feature**: 007-closed-trade-refund-import  
**Purpose**: Stage-1 classification gate for `transfer_pair` / credit repayment — *which native bill buckets may enter the transfer candidate pool*. Fine matching (amount/time/account) is Stage-2.

Data basis: `~/.ft/bills` real exports (Alipay ~3y, WeChat ~3y, CCB XLS, ICBC PDF).

---

## Global pipeline

```text
Stage 1  Taxonomy gate  → tag TRANSFER_* / CREDIT_REPAY_* / NOT_TRANSFER
Stage 2  Fine match within allowed pools only
Phase order: A refund → B mirror → **C transfer** → D bank refund / weak
```

---

## Alipay — `platform_status × direction` (+ text family)

### Enter transfer / credit-repay pool

| status | direction | Text family (Stage-1 filter) | Tag | n (approx) | Stage-2 peer |
|---|---|---|---|---:|---|
| 交易成功 | 支出 | `提现` / `提现-实时提现` | `TRANSFER_OUT_WITHDRAW` | 6 | Bank credit same amount, Δt≤60s |
| 交易成功 | 支出 | `转账到银行卡` | `TRANSFER_OUT_TO_BANK` | 1 | Bank credit |
| 转出成功 | 不计收支→记账金额常+ | `余利宝-转出到银行卡` | `TRANSFER_OUT_YULIBAO` | 5 | 网商/银行卡入账 |
| 交易成功 | 支出/收入 | `余额宝` 转入/转出到余额或银行卡（非收益） | `TRANSFER_YUEBAO_INTERNAL` | few | Same ecosystem account |
| 还款成功 | 收入/支出 | `花呗` + `还款` | `CREDIT_REPAY_HUABEI` | ~24 | Funding leg (card/balance) |
| 交易成功 | 支出 | `月付】主动还款` / 先享后付到期 | `CREDIT_REPAY_OUT` | few | Loan/credit account |
| 还款成功 | 支出 | 先享后付等到期 | `CREDIT_REPAY_OUT` | few | Loan/credit |

### NOT transfer (same status grid, excluded by text/type)

| status | direction | family | Why |
|---|---|---|---|
| 交易成功 | 收入 | `转账` / `转账红包`（微信等） | P2P income |
| 交易成功 | 支出 | 商户消费、充值缴费（电费/交通卡等） | Spend |
| 退款成功 | * | * | Phase A refund |
| 交易关闭 | 非支出 | 未支付关闭 | Whitelist skip |

**Note**: `交易成功×支出` is a **wide** cell — Stage-1 MUST apply text family; must not treat all successful expenses as transfer.

---

## WeChat — `direction × status × type`

### Enter pool

| direction | status | type | Tag | n | Stage-2 peer |
|---|---|---|---|---:|---|
| 收入 | **提现已到账** | **零钱提现** | `TRANSFER_WITHDRAW_RECEIPT` | 4 | CCB `银联入账` / `支付机构提现` same amount, same calendar day (date-only OK) |
| 支出 | **支付成功** | **信用卡还款** | `CREDIT_REPAY_OUT` | 1 | Credit card inflow |
| 支出 | 支付成功 | 购买理财通 | `TRANSFER_INVEST_OPTIONAL` | 2 | Only if investment account modeled |

### NOT transfer

| direction | status | type | n | Why |
|---|---|---|---:|---|
| 支出 | 已转账 | 扫二维码付款 | 87 | QR spend |
| 收入 | 已收钱 | 二维码收款 | 9 | QR receive |
| 支出 | 对方已收钱 | 转账 | 100 | P2P out |
| 收入 | 已存入零钱 | 转账 | ~175 | P2P in |
| * | * | 微信红包* | 200+ | P2P / redpacket |
| 支出 | 对方已退还 | 转账 | 1 | **Refund origin** (Phase A) |
| 收入 | 已全额退款 | 转账-退款 | 2 | **Refund** (Phase A) |

---

## CCB debit — `summary` × sign

### Enter pool

| summary | sign | Tag | n | Stage-2 peer |
|---|---|---|---:|---|
| 转账支取 | − | `TRANSFER_OUT_BANK` | 20 | Other card 银联入账 |
| 无卡自助交易 | − | `TRANSFER_OUT_BANK` | 40 | Often cloud-pay bridge |
| 银联入账 | + | `TRANSFER_IN_BANK` | 49 | Peer out-leg or platform withdraw |
| 支付机构提现 | + | `TRANSFER_IN_BANK` | 2 | WeChat withdraw |
| 转账存入 | + | `TRANSFER_IN_BANK` | 1 | |
| 电子汇入 | + | `TRANSFER_IN_BANK` | 3 | |
| 银转证 | − | `TRANSFER_BROKERAGE_OUT` | 22 | Securities (often missing) |
| 证转银 | + | `TRANSFER_BROKERAGE_IN` | 11 | Securities |
| 还款 | − | `CREDIT_REPAY_OUT` | 12 | Credit account if present |

### NOT transfer

| summary | Why |
|---|---|
| 消费 | Spend / mirror |
| 消费退货 | Phase D refund |
| 利息存入 | Interest |
| 充值（生活缴费类） | External bill pay unless proven internal |

### Fine routes (Stage-2)

| Out | In | Notes |
|---|---|---|
| 转账支取 / 无卡自助 | 银联入账 on **other** account_id | Same day / ≤10s; real multi-card pairs exist |
| 微信 提现已到账 | 支付机构提现 / 银联入账+微信零钱 | Date-only amount match |
| 银转证 / 证转银 | Counter-leg or open-leg | Do not pair to 消费 |

---

## ICBC

### Debit

| Gate | Tag | Stage-2 |
|---|---|---|
| amount>0, payment_method 快捷/网银, counterparty self-name (e.g. 黄文龙), near alipay 提现 | `TRANSFER_IN_BANK` | Pair alipay withdraw |
| amount<0, 支付宝/财付通 channel | **NOT_TRANSFER** | Phase B mirror |
| 公积金/工资 large credit | NOT_TRANSFER | Income |

### Credit

| Gate | Tag |
|---|---|
| 转帐收入 + 财付通/还款语义 | `CREDIT_REPAY_IN` |
| 转帐刷卡金入账 | NOT_TRANSFER (promo) |
| 退货 | Phase D refund |

---

## Stage-2 priority (unique → accepted)

1. `scan.transfer.withdraw_to_bank.v1` — platform withdraw ↔ bank credit  
2. `scan.transfer.unionpay_card_bridge.v1` — CCB out ↔ CCB in other card  
3. `scan.transfer.credit_repayment.v1`  
4. `scan.transfer.brokerage.v1` — open-leg if single  
5. Generic weak transfer **only** among taxonomy-gated legs + signals including **提现**

---

## Explicit exclusions (never auto transfer)

- WeChat P2P: 对方已收钱×转账, 已存入零钱×转账, 红包  
- QR pay/receive  
- Refund / 消费退货 / 退款成功  
- Merchant consume / 快捷支付 channel spend  
- Interest, payroll, housing fund  

---

## Import payload fields required for gates

| Source | Fields |
|---|---|
| Alipay | platform_status, direction, description, payment_method, amount, time |
| WeChat | status, type/txn_type, direction, pay method, amount, time |
| CCB | summary, location, amount, date, card |
| ICBC | payment_method, counterparty, description, amount, time |


## Audit tightening (2026-07-22 full import)

### WeChat withdraw (fixed gap)
- Out/receipt: status `提现已到账`, type `零钱提现` (amount often **positive**).
- In: CCB/ICBC `银联入账` or `支付机构提现` or description contains `微信零钱提现`.
- Match: **exact amount + same calendar day** (bank may lack time).
- Rule: `transfer_pair.withdraw_to_bank.v1`.

### Credit repayment gate (noise reduction)
**Out-leg ALLOW text** (any): `信用卡还款`, `购汇还款`, `自动还款`, `花呗`+`还款`, `月付】主动还款`, `主动还款`+credit product.
**Out-leg DENY**: CCB `summary=还款` alone with merchant-like counterparty (京东, 消费); pure `消费`.
**In-leg ALLOW**: `account_type in {loan, credit}` OR (bank credit bill + (`还款` or `转帐收入`/`转账收入`) and not refund).
**In-leg DENY**: `退款`, `退货`, `消费退货`, `刷卡金`, merchant refund incomes.

### Stop criteria
Do not further broaden transfer signals into P2P/QR/consume; do not auto credit_repayment without loan/credit in-leg.


## WeChat withdraw: same-account dual-source (audit)

When mapping routes 零钱提现 → 建行储蓄卡, the WeChat row is a **bank credit fact** with `bill_source=wechat`.
CCB XLS may add a second credit `银联入账`/`支付机构提现` on the **same account_id**.

| Case | Relation |
|---|---|
| Same account_id, both +amount | **payment_mirror** (platform×bank views), NOT transfer_pair |
| Different account_id (零钱 vs 建行) | **transfer_pair.withdraw_to_bank** |
| Only one fact | no pair |

Date-only bank rows may sit at 16:00 UTC previous calendar day — mirror window must allow adjacent day / 36h for this family only.
