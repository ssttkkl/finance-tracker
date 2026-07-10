# Reconcile transfer leakage audit: batch review → TDD rules → dry-run

Use this reference when improving `ft reconcile` after discovering existing `income/expense` rows that should be internal transfers.

## Workflow

1. **Review existing data in 6-month batches**
   - Split `records/cash/` and `records/loan/` by date into half-year ranges.
   - Delegate each range to a read-only reviewer/subagent.
   - Review only rows still in `category in (income, expense)` and `locked != 1` when looking for missed marks; separately sample existing `transfer_in/out` for over-marking.
   - Do **not** modify records during review.
   - Output format should be terse: classify each finding as `无需标注`, `未标注`, or `误标注`, with `high/medium/low` confidence. Do not list every `无需标注` row; only give counts/confidence distribution. Only list `未标注`/`误标注` details as `置信度 | 判断 | 日期 | 文件 | 金额 | 账户 | counterparty | description | 当前category`.

2. **Integrate cases before coding**
   - Group findings into rule families.
   - Only code high-confidence, repeatable patterns.
   - Keep medium/ambiguous findings as a confirmation list, not production rules.

3. **TDD is mandatory**
   - Write RED tests from real leaked examples before changing production code.
   - Verify failures are because rules/pairing are missing, not fixture errors.
   - Then implement minimal GREEN changes.
   - Run targeted tests, then the full suite.

4. **Dry-run on a copy before touching real ledger**
   - Copy `~/.ft/accounts.yaml`, `records/cash`, and `records/loan` to a temp directory.
   - Monkeypatch `ft.models.FT_DIR`, `RECORDS_DIR`, `ACCOUNTS_PATH`, and `ft.snapshot.SNAPSHOT_PATH` to the copy.
   - Run `do_reconcile()` on the copy.
   - Count new `income/expense → transfer_in/transfer_out` marks and audit `match_rule` distribution.
   - Run `do_reconcile()` a second time and compare file hashes; second run must change zero files.
   - Only after reporting the dry-run result should the real ledger be modified, and only with user confirmation.

## High-confidence patterns captured in tests/code

- `same_day_unionpay_cash_transfer`
  - Cash↔cash, same date, same currency, same amount, different accounts.
  - In leg has `银联入账` or `电子汇入`.
  - Out leg has `无卡付` or `转账支取`.
  - Solves bank records where one leg is timestamped `00:00:00`, so the older ±10s rule misses it.
  - Must require unique match.

- `same_currency_cash_loan_repayment`
  - Cash negative leg has `还款` / `自动还款` / `主动还款`.
  - Loan positive leg has `转帐` / `转账` / `银行卡中心` / `手机银行`.
  - Same currency, same amount, within about 10 minutes, unique match.

- `security_transfer`
  - Single-leg `银转证` / `银行转证券` → `transfer_out`.
  - Single-leg `证转银` / `证券转银行` → `transfer_in`.

- `self_fund`
  - Description explicitly says `基金购买` or `基金赎回`; direction follows amount sign.
  - **Do not bind this rule to the user's real name**. Real ledger examples may show the user's name as counterparty, but production rules must identify the transaction semantics, not PII.
  - Keep `收益发放` excluded as real income.

- `fx_cash_leg`
  - Description is exactly a currency label such as `美元`, `港币`, `日元`, or `欧元`.
  - Treat as an FX cash-leg transfer; direction follows amount sign.
  - Must not match broader real-consumption text such as `美元消费` / `港币消费`.
  - If the ledger needs an account carrier, add same-name multi-currency cash accounts (e.g. `工行借记卡` in USD/HKD/JPY) rather than hiding the transfer as income/expense.

- `wallet_transfer`
  - `微信零钱提现` + `支付机构提现` or `银联入账` → wallet/bank transfer.
  - `零钱提现` with bank counterparty such as `建设银行` → wallet/bank transfer.
  - `提现-实时提现` with bank counterparty such as `中国工商银行` / `中国建设银行` → Alipay/bank transfer.
  - `网商银行` + `转出到网商银行` → Alipay/MyBank transfer.
  - `微信零钱充值账户` + description exactly `充值`, or `互联互通` + description exactly `钱包充值` → wallet funding transfer.
  - Exclude `收益发放` / `账户结息` as real income.
  - Do **not** broaden generic `充值`: plain `微信` + `充值` remains a real/ambiguous expense unless the counterparty/description proves wallet funding.

- `consumer_loan_repayment`
  - Once the loan product is modeled (`花呗`, `美团月付`, `京东白条`), repayments are internal cash↔loan transfers, not real consumption.
  - Add missing loan accounts before/with rules when needed (e.g. `美团月付`, `京东白条`).
  - Safe signatures: `花呗`+`还款`; `美团金融/美团金融服务/美团月付` + repayment wording or description exactly `消费` when counterparty itself is `美团月付还款`; `京东` with description exactly `还款`; `京东白条`+`还款`; bank-side repayment rows such as `还款`+`消费`, `网银在线/钱袋宝`+`还款`; loan-side card repayment rows such as `转帐`+`手机银行` or `转帐收入`+`财付通`.
  - Keep 京东 purchases/商城业务 and generic platform consumption as real expense; do not broaden 京东 matching beyond explicit repayment text.

## Patterns that require special care

- ATM 存取款 unless cash account modeling is explicit.
- Generic `充值`; may be wallet funding, transit card stored value, or real consumption.
- Any rule that appears to require a real person name: rewrite it around stable transaction semantics, account type, description, or counterparty class instead of hard-coding PII.

## Verification checklist

- Targeted RED tests failed first.
- Targeted tests pass.
- Full test suite passes.
- Dry-run on copied ledger reports:
  - number of new transfer marks,
  - match-rule distribution,
  - representative examples,
  - second reconcile run changes zero files.
- Real ledger has not been modified until user explicitly approves application.
