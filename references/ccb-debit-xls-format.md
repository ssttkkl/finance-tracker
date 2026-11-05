# 建行借记卡 XLS 格式

建行个人活期账户明细由 Kotlin 后端的[银行账单读取器](../compose/backend/src/main/kotlin/com/finance/tracker/backend/BankStatementDocument.kt)读取，使用 Apache POI 处理 XLS。快速探测检查受控表头区中的建设银行交易明细标题，以及序号、交易日期、交易金额和账户余额字段；唯一匹配后完整读取。

## 当前字段读取

解析器保留原始表头和值作为单条来源流水的 `source_payload`，按字段名读取。下表描述常见导出布局，不把固定列号作为身份或取值规则：

| 位置 | 用途 |
|---:|---|
| 第 0 列 | 序号 |
| 第 1 列 | 摘要 |
| 第 2 列 | 币别 |
| 第 4 列 | 交易日期，通常为 `YYYYMMDD` |
| 第 5 列 | 已带正负号的交易金额 |
| 第 6 列 | 账户余额 |
| 第 7 列 | 交易地点或附言 |
| 第 8 列 | 对方账号与户名 |

列 3 的含义和每份导出表的表头以实际 `headers` 为准。解析器不会要求交易地点必须是占位符；支付源和交易对手会结合交易地点、摘要及对方信息推导。

## 账户与退款

工作簿的“卡号/账号”声明按来源身份合同读取，展示尾号不能单独替代可信来源身份。导入仍由使用者确认对应工作区资金账户，不凭卡尾号或同名银行猜测目标账户。

正金额记录可根据摘要识别退款信号。退款候选由共享关系匹配逻辑进一步约束，包括来源账户、剩余可抵销金额、日期范围及候选证据；不应假设系统只接受全额退款或仅按固定天数、金额相等配对。无法唯一确认的候选保留给关系审查。

## 解析边界

- 表头缺失、重复或不完整会报错，因为无法构造可靠的原始来源行。
- 不可解析的金额保留 `amount_unreadable`，不能概括为静默跳过；坏行结构与无法识别的字段分开处理，见[来源行处理](statement-row-handling.md)。
- 行归一由[现金来源行归一](../compose/backend/src/main/kotlin/com/finance/tracker/backend/CashStatementRowNormalization.kt)定义，文档回归见[银行账单 golden](../compose/backend/src/test/kotlin/com/finance/tracker/backend/BankStatementDocumentGoldenTest.kt)；关系仍遵守当前统一导入规格。
