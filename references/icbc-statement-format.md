# 工行银行卡 PDF 解析

工行信用卡和借记卡使用不同的 PDF 解析方式，不能用一套行格式描述两者。

## 信用卡

`_read_icbc_raw` 调用 `extract_pdf_text(path, password=..., backend="pdfplumber", word_stream=True)`，解析 word-stream 文本。信用卡类型从账单文本识别；解析器从金额附近的上下文提取日期、币种、交易方向、商户和支付来源。具体字段变化以 `_parse_icbc_lines` 及对应解析测试为准。

## 借记卡

`_read_icbc_debit_raw` 使用 `pdfplumber` 打开 PDF，并从页面表格读取交易。解析器会过滤较大的水印字符，再从表头和数据行建立原始字段映射。

当前行解析使用下列字段位置：

| 列位置 | 字段 |
|---:|---|
| 0 | 日期与时间 |
| 4 | 币种 |
| 6 | 摘要 |
| 8 | 带符号金额 |
| 10 | 对方户名 |

日期、币种、摘要、金额及对方信息的字段布局由 `_parse_icbc_debit_row` 处理。日期缺失、列数不足或金额无法解析时，当前实现会报错；其它 provider 的解析行为不由此推断。

## 账户识别与安全

借记卡 PDF 的卡号识别结果作为来源账户身份，进入现金导入映射流程，由工作区映射关联到目标账户。末四位是匹配证据之一，不应单独替代银行和卡类型。

账单密码只用于解析请求；不要在参考文档、日志或测试夹具中保存密码、完整卡号、真实文件名或原始账户信息。解析和测试见 [`convert.py`](../src/ft/convert.py)、[`test_convert.py`](../tests/test_convert.py) 与 [`test_icbc_refund_pairing.py`](../tests/test_icbc_refund_pairing.py)。
