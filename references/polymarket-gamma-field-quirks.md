# Polymarket Gamma 字段解析

`PredictionMarketQuoteProvider` 从 Gamma 响应的 `outcomes` 和 `outcomePrices` 字段读取预测市场结果价格。provider 可能返回原生 JSON 数组，也可能返回编码为 JSON 文本的数组；当前实现会对字符串值执行 JSON 解码，再根据结果名称匹配 Yes/No 价格。

解析实现见 [`market_data.py`](../src/ft/adapters/market_data.py)。HTTP JSON helper 设置 `User-Agent` 请求头。

测试应同时覆盖数组和 JSON 文本两种字段形态，并检查 Yes、No 代码映射到对应价格。当前回归用例见 [`test_market_data.py`](../tests/test_market_data.py)。
