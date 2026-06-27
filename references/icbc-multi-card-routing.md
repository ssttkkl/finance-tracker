# ICBC 信用卡 PDF 多卡路由

## 背景

工行信用卡历史明细 PDF（-s icbc）可能在一份 PDF 中包含多张卡（主卡 + 附属卡）的交易记录。转换器 `_parse_icbc_lines`（convert.py:584）已提取每笔交易的卡号后四位存入 `card_number` 字段。

## 路由逻辑

在 `do_convert`（convert.py:1133-1138）中：

```python
# 优先按卡号路由（信用卡账单）
card_num = rec.get("card_number", "")
if card_num:
    match = match_payment_method(rules, f"{bill_type}_{card_num}", "*")
else:
    match = None
if not match:
    match = match_payment_method(rules, bill_type, rec.get("payment_method", ""))
```

1. 如果有 card_number，先尝试 `icbc_credit_{last4}`（如 `icbc_credit_1200`）
2. 未匹配则走通用 `icbc_credit` + payment_method 规则

## mapping.yaml 配置示例

```yaml
  - source: icbc_credit_1200
    match: "*"
    account: "工行信用卡(1200)"
    currency: CNY
  - source: icbc_credit_0851
    match: "*"
    account: "工行信用卡(0851)"
    currency: CNY
```

**注意：** 通用 fallback `source: icbc_credit` 仍要保留，兼容 card_number 为空的行。

## accounts.yaml 多币种

每张卡需要配置所有涉及的币种：

```yaml
  - currency: CNY
    name: 工行信用卡(0851)
    type: loan
  - currency: HKD
    name: 工行信用卡(0851)
    type: loan
  - currency: JPY
    name: 工行信用卡(0851)
    type: loan
  - currency: USD
    name: 工行信用卡(0851)
    type: loan
```

## 检测 PDF 中的卡号

```bash
pdftotext "202606130112316491068950-20260613_001密码349448.pdf" -upw 349448 - \
  | grep -oE '\b\d{16}\b' | sort -u
```

## 2026-06-13 会话记录

- CAD 9661 → 工行信用卡(1200)（附属卡，已通过 wechat mapping 覆盖）
- 0851 → 独立卡 `工行信用卡(0851)`（新建账户，非 1200 子卡）
- 多币种（CNY/HKD/JPY/USD）→ 每卡每种货币一条 account 条目
- PDF 密码 349448，001（2265 条）+ 002（318 条）两部分
