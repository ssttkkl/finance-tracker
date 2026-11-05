# 工行银行卡 PDF 解析

工行信用卡和借记卡都由 Kotlin 后端通过隔离 JVM 中的 PDFBox 读取表格，按各自表头映射原始字段。自动识别共用一次有界首页探测，唯一匹配后才读取全部页面；缺少或错误密码保留对应文件错误，不尝试其他渠道。

## 表头与字段

| 来源 | 表头及主要取值 |
|---|---|
| 信用卡 | 12 列：入账日期、交易卡号、收支、交易币种、交易金额、入账币种、入账金额、账户余额、对方户名、对方账号、摘要、交易场所。流水使用交易币种和交易金额，不用入账金额替代 |
| 借记卡 | 13 列：交易日期、账号、储种、序号、币种、钞汇、摘要、地区、收入/支出金额、余额、对方户名、对方账号、渠道。金额保留来源符号 |

表格按真实边线与文字位置重建，不依靠金额附近的任意文本窗口猜列。文字和边线统一使用页面显示坐标，处理配套页面／内容旋转和 CropBox 原点；这不表示支持任意混合竖排。读取保留页面、表格和行位置，完整校验表头、页数、行数、来源声明及既有预算。

## 账户识别与行问题

信用卡逐行交易卡号与借记卡文档账户按来源合同提供身份。脱敏尾号、显示名称和商户渠道不能单独替代可信来源身份或使用者选择的资金账户。来源原文进入 `source_payload`，账户对应、关系建议及最终保存仍由统一导入流程处理。

格式或表头错误与行内缺失字段分开处理。金额、时间、币种或方向无法识别时保留对应问题；行结构无法读取时记录未导入原因。草稿、业务跳过和最终可保存性以[来源行处理](statement-row-handling.md)及[导入流程](../docs/import/cash-import-flow.md)为准，不能把字段缺失统称为整份文件解析失败。

实现见 [PDF 读取器](../compose/backend/src/main/kotlin/com/finance/tracker/backend/IcbcPdfDocument.kt)和[行归一](../compose/backend/src/main/kotlin/com/finance/tracker/backend/CashStatementRowNormalization.kt)；回归见[文档 golden](../compose/backend/src/test/kotlin/com/finance/tracker/backend/IcbcPdfDocumentGoldenTest.kt)、[坐标回归](../compose/backend/src/test/kotlin/com/finance/tracker/backend/IcbcPdfCoordinatesTest.kt)与[进程安全边界](../compose/backend/src/test/kotlin/com/finance/tracker/backend/PdfProcessTest.kt)。密码、完整卡号、真实文件名及交易明细不进入仓库或日志。
