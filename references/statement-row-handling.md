# 账单来源行的解析、报错与跳过

本文只说明现金账单的读取和行归一边界；投资账单格式另见[证券账单参考](dfzq-field-offsets.md)。文件格式拒绝、行结构未导入、字段问题和业务跳过是不同结果，不能统一称为“解析失败”或“跳过”。

## 当前行为示例

| 来源 | 当前代码可确认的行为 |
|---|---|
| 全部现金渠道 | 自动探测无唯一匹配返回 `import_channel_unrecognized`；唯一匹配后，损坏格式、无效表头及预算错误属于该文件，不回退其他解析器 |
| 支付宝 CSV | 订单号或来源行结构不可靠时拒绝文件；可证明未付款关闭、失败还款按白名单跳过并计数；金额、时间或方向问题保留对应问题，0 元事实不因金额为零丢弃 |
| 微信 CSV／XLSX | 金额或方向无法识别时保留字段问题；无法读取的行结构记录未导入原因，不能将未知中性类型概括为静默跳过 |
| 建行 XLS／工银亚洲 TSV | 坏行结构与字段问题分开；金额、时间及币种问题保留，工银亚洲符合规则的零金额开户行按明确原因跳过 |
| 工行信用卡／借记卡 PDF | 完整表头及表格结构校验与行归一分开；金额、时间、币种或方向无法识别保留问题，不把所有字段缺失都当作整文件失败 |

可保留的候选行继续交给统一资金及正式校验，形成正式流水、草稿或明确未导入结果；格式成功不等于全部正式入账。最终以预览和持久收据核对全部数量，不从文件成功状态推断财务结果。

## 变更检查

- 为预期跳过定义可识别原因和可验证的计数；不要把无条件 `continue` 描述为安全白名单。
- 错误消息提供足够的来源和字段上下文，但不回显密码、完整卡号或不必要的个人数据。
- 验证导入预览中的事实数、跳过数和失败状态，并检查来源行 `source_payload` 是否完整。
- 行归一见 [CashStatementRowNormalization.kt](../compose/backend/src/main/kotlin/com/finance/tracker/backend/CashStatementRowNormalization.kt)，金额／方向回归见[平台](../compose/backend/src/test/kotlin/com/finance/tracker/backend/PlatformRowNormalizationGoldenTest.kt)、[银行](../compose/backend/src/test/kotlin/com/finance/tracker/backend/BankRowNormalizationGoldenTest.kt)和[工行 PDF](../compose/backend/src/test/kotlin/com/finance/tracker/backend/IcbcPdfRowNormalizationGoldenTest.kt)；来源身份与绑定另见 [CashSourceRowsGoldenTest.kt](../compose/backend/src/test/kotlin/com/finance/tracker/backend/CashSourceRowsGoldenTest.kt)。读取及几何边界回归由各格式参考链接提供。
