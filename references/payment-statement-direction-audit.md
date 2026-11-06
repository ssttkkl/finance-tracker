# 支付账单方向与类别语义

本文记录支付宝、微信和银行卡转换器当前采用的方向语义。具体解析规则以实现和对应测试为准；不同 provider 的跳过、报错和金额处理策略并不完全相同。

## 支付宝

- 非零金额通常按账单方向与交易类型转换为支出或收入。
- 账户提现、信用借还、部分理财转出等从支付账户视角表示资金流出。
- 0 元记录会保留为来源事实；它不代表资金变动。
- 符合未支付关闭或失败还款白名单的记录按明确规则跳过并计数；“交易关闭”状态并不单独构成跳过条件。
- 成功退款作为正向来源事实保留；抵销关系单独记录，不改写原消费金额。

## 微信

- “收入”和“支出”按 provider 方向处理。
- `零钱提现`、`充值`、`零钱充值`、`零钱通存取`、`理财通`、`购买理财通` 和 `信用卡还款` 等中性类型按交易类型及说明判断资金方向。
- 零钱提现记录表示微信零钱出账；付款方式中的银行卡作为到账证据，不代表该行由银行卡账户出账。
- 未知中性交易类型或无法解析金额时保留方向／金额问题；坏行结构和业务跳过另有原因，见[来源行处理](statement-row-handling.md)。

## 银行卡

- 工行信用卡的借贷标记决定正负方向；还款、冲正和退款应结合来源类型处理。
- 借记卡解析保留账单金额符号，并使用账单字段作为来源账户和对手方证据。
- 支付账单的账户映射由来源账户身份和工作区映射决定；不要用商户支付渠道替代账单账户身份。

## 维护与验证

增加方向分支时，为金额符号、类别、来源身份、原始来源行及关系候选分别建立测试。Kotlin 金额断言使用项目精确金额类型，不经过二进制浮点。格式错误、字段问题及业务跳过分别验证，不概括为一律静默跳过或整份拒绝。

实现见[现金来源行归一](../compose/backend/src/main/kotlin/com/finance/tracker/backend/CashStatementRowNormalization.kt)，金额及方向验证见[平台](../compose/backend/src/test/kotlin/com/finance/tracker/backend/PlatformRowNormalizationGoldenTest.kt)、[银行](../compose/backend/src/test/kotlin/com/finance/tracker/backend/BankRowNormalizationGoldenTest.kt)和[工行 PDF](../compose/backend/src/test/kotlin/com/finance/tracker/backend/IcbcPdfRowNormalizationGoldenTest.kt)；关系由[当前导入规格](../openspec/specs/import/statement-import/spec.md)单独约束。
