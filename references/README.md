# References

本目录保留 **当前运行时**仍有用的 parser、账单格式与只读行情适配资料。  
文件账本、reconcile session、旧 `stock sync` 流程等已删除；考古用 Git history。

## 原始账单解析

- [建行借记卡 XLS 格式](ccb-debit-xls-format.md)
- [工行信用卡格式（2026-06）](icbc-credit-card-format-202606.md)
- [工行账单格式](icbc-statement-format.md)
- [东方证券字段偏移](dfzq-field-offsets.md)
- [支付账单方向审计](payment-statement-direction-audit.md)
- [微信中性交易](wechat-neutral-txns.md)
- [转换器不得静默丢行](convert-no-silent-drop.md)

## 报告与行情

- [Yahoo Finance ticker 格式](yfinance-ticker-format.md)
- [Yahoo Finance 市场分组](yfinance-market-grouping.md)
- [Yahoo Finance 港股价格](yfinance-hk-price-fetch.md)
- [Polymarket gamma 字段](polymarket-gamma-field-quirks.md)（估值 017 / 同步 018 相关）

实现行为以 feature artifacts、代码和测试为准；reference 只补充供应商格式细节。  
操作总览见根 [README.md](../README.md) 与 [docs/import-flow.md](../docs/import-flow.md)。
