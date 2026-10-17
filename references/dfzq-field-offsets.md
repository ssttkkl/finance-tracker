# 东方证券 PDF 资金流水布局

东方证券 PDF 由 `qpdf`（需要解密时）和 `mutool draw -F text` 提取文本，`parse_dfzq_text` 按“资金流水明细”区域及每笔交易的日期边界解析字段。当前字段布局处理见 [`dfzq.py`](../src/ft/importers/dfzq.py)。

## 标准交易块

标准块的字段顺序为：

`发生日期 | 买卖类别 | 证券代码 | 证券名称 | 成交数量 | 成交价格 | 总发生金额 | 手续费 | [印花税] | [过户费] | 资金余额`

来源文本中的空字段会使单笔交易块缺少字段。解析器用“证券名称位置是否为数字”和后续单元数量区分含名称、无名称、OTC 资金变动和银行转账布局：

- 标准交易包含证券名称、数量、价格、发生金额和费用等字段。
- OTC 资金块可能缺少证券名称字段，但仍包含数量和价格字段。
- 银行转证券、证券转银行等资金块可能同时缺少名称和成交数量字段。
- “红股入账”使用专门的短块解析分支。

各分支对可选印花税、过户费和余额字段的读取条件不同。新导出格式应先增加去标识化 fixture 与解析测试，再调整偏移逻辑。

## 当前测试

解析与事件映射测试位于 [`test_dfzq_parser.py`](../tests/unit/importers/test_dfzq_parser.py) 和 [`test_dfzq_event_mapping.py`](../tests/unit/importers/test_dfzq_event_mapping.py)。现有 PDF 文本 fixture 位于 [`tests/fixtures/dfzq/`](../tests/fixtures/dfzq/)；使用前应检查其是否含个人或账户信息。
