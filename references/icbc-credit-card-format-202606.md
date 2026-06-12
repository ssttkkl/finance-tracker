# 工行信用卡 PDF 格式（2026-06 实测）

## 文件信息

- 文件: `202606091857118880563874-20260609.pdf` (1.2MB)
- 密码: 账单查询密码
- 提取工具: `qpdf --decrypt` → `mutool draw -F text`
- 原始文本行数: 11958 行
- 转换后交易: 843 条

## PDF 文本布局

每笔交易约 12-15 行，包含：

```
入账日期
交易卡号
收/支
交易币种
交易金额
入账币种
入账金额
账户余额
对方户名
对方账号
摘要
交易场所  ← 支付源信息在此
```

## 交易场所（支付源）格式

```
美团支付-美团App霸王茶姬（鼎成中心店）
财付通-瑞幸咖啡
支付宝-北京嘀嘀无限科技发展有限公司
京东支付-京东商城业务
网银在线-海天京东自营旗舰店
Apple.com/bill MQZF72ZG2Fa0S
程支付-上海携程国际旅行社有限公司
拼多多支付-橙予进口专营店
（特约）抖音支付平台交易
财付通(银联云闪付)
```

前缀即支付源。对应 PAYMENT_SOURCE_RULES。

## 已修复的问题

1. **`do_convert` 硬编码** `bill_type = "icbc_debit"` → 改为从 PDF 文本检测"信用卡"关键字（`_read_icbc_raw` 返回 `(rows, bill_type)` 元组）。

2. **32 条交易商家名为空** → `_extract_merchant` 的 `nearby` 窗口原是 `lines[i-8:i+1]`，只覆盖金额行之前的行。但信用卡账单格式中交易场所（`美团支付-美团App霸王茶姬`）在金额行**之后**。改为 `lines[i-8:i+8]` 后所有商家名都能提取到。

3. **`import re` 位置错误** → `_extract_merchant` 函数内 `import re as _re` 在首次调用 `_re.match()` 之后，导致 NameError。将 import 移到函数首行。

## 关键格式要点

交易场所（商家名）在金额行之后约 3-6 行。这意味着：
- `_read_icbc_raw` 中 `_extract_merchant` 的 nearby 窗口必须向后延伸（使用 `min(i+8, len(lines))` 而非 `i+1`）
- `ctx` 上下文也同样需要向后延伸用于 `is_charge` 判断

```python
# 必须向后看，不能只看金额行之前
description = _extract_merchant(ctx, lines[max(0, i-8):min(len(lines), i+8)])
```
