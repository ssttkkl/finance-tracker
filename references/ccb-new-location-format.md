# 建行新版交易地点优化

> 2026-06-10

## 背景

旧版建行对账单的"交易地点/附言"列始终为 `***`，导致：
- counterparty 只能用脱敏的对方户名（`***咖啡`、`***ro`）
- 退款配对只能用末位字符 hack（如「于震」↔`*震`）
- 22.23 美团特约商户孤退款无法配对

## 新版（2026-06-10 申请）

新版对账单的"交易地点/附言"列展示完整支付信息：

```
财付通-微信支付-瑞幸咖啡
支付宝-淘宝-于震
美团支付-美团特约商户
PAYPAL_PIXIVFANBOX
北京市政交通一卡通有限公司
```

## _extract_ccb_counterparty 逻辑

```python
def _extract_ccb_counterparty(location: str) -> str | None:
    if not location or location == "***":
        return None
    
    PAYMENT_PREFIXES = [
        ("财付通-", ["微信支付-", "微信转账"]),
        ("支付宝-", ["淘宝-", "支付宝外部商户-", "支付宝-转账-"]),
        ("美团支付-", []),
    ]
    
    for prefix, subs in PAYMENT_PREFIXES:
        if location.startswith(prefix):
            rest = location[len(prefix):]
            for sub in subs:
                if rest.startswith(sub):
                    rest = rest[len(sub):]
                    break
            return rest
    
    return location  # 无前缀：直接作为商户名
```

## 退款配对改进

- 旧版：`_pair_ccb_refunds`（末位字符 hack）→ 已删除
- 新版：`_pair_refunds`（counterparty 精确匹配，与支付宝/微信/工行一致）

## 效果

| 旧版 | 新版 |
|---|---|
| counterparty=`***咖啡` | counterparty=`瑞幸咖啡` |
| 4 对退款匹配 | 5 对退款匹配 |
| 22.23 = 孤退款 | 22.23 = 美团特约商户部分退款(净额-27.77) |
