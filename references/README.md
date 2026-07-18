# References

本目录只保留仍被当前 PostgreSQL-only runtime 使用的 parser、账单格式和只读行情适配资料。历史文件
账本、reconcile session、Connector sync、迁移和 snapshot 修复 runbook 已删除；需要考古时使用 Git
history，不把旧流程继续作为操作说明维护。

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

实现行为仍以当前 feature artifacts、代码和测试为准；reference 只补充供应商格式细节。
