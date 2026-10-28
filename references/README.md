# 解析与行情参考

本目录记录当前解析器和行情适配器中仍有用的来源格式与行为。项目规格、实现和测试是行为事实源；本文档只补充特定 provider 的输入布局与适配边界。

## 账单解析与方向

- [建行借记卡 XLS 格式](ccb-debit-xls-format.md)
- [工行信用卡 PDF 格式](icbc-credit-card-format.md)
- [工行银行卡 PDF 解析](icbc-statement-format.md)
- [东方证券 PDF 资金流水布局](dfzq-field-offsets.md)
- [支付账单方向与类别语义](payment-statement-direction-audit.md)
- [微信中性交易](wechat-neutral-txns.md)
- [来源行解析与跳过行为](statement-row-handling.md)

## 行情适配

- [证券代码到行情 provider 的映射](yfinance-ticker-format.md)
- [证券行情批量读取与回退](yfinance-market-grouping.md)
- [Polymarket Gamma 字段解析](polymarket-gamma-field-quirks.md)

操作总览见根目录 [README.md](../README.md) 和 [导入流程](../docs/import-flow.md)；现金导入字段及协议见 [接口与数据契约](../docs/cash-import-redesign-contracts.md)。
