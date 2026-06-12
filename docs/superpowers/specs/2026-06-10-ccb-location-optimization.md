# 建行新版交易地点优化设计

> 2026-06-10 | 基于新版建行对账单（交易地点列完整展示），优化转换器

## 动机

新版建行对账单的「交易地点/附言」列（旧版始终 `***`）现在展示完整信息：
`财付通-微信支付-瑞幸咖啡` / `支付宝-淘宝-于震` / `PAYPAL_PIXIVFANBOX` 等。

这解决了旧版两个核心痛点：
1. **脱敏 counterparty**（`***咖啡`）→ 现在可从交易地点提取真实商户名
2. **模糊退款配对**（末位字符 hack）→ 现在可精确 counterparty 匹配

## 改动范围

- `src/ft/importers/ccb_debit.py` — `read_ccb_debit` 重写，`_pair_ccb_refunds` 删除
- `tests/test_ccb_debit.py` — 测试更新

## 设计

### 1. counterparty 提取

对齐工行的 `_strip_payment_prefix` 模式：

```python
def _extract_ccb_counterparty(location: str) -> tuple[str, str]:
    """从建行交易地点提取 (counterparty, payment_source)"""
    # 第一层：支付源前缀
    # 第二层：子渠道前缀（微信支付-、淘宝-、支付宝外部商户-等）
    
    # 支付源映射
    for prefix, source in [
        ("财付通-", "微信支付"),
        ("支付宝-", "支付宝"),
        ("美团支付-", "美团支付"),
    ]:
        if location.startswith(prefix):
            rest = location[len(prefix):]
            # 剥离子渠道层
            for sub in ["微信支付-", "淘宝-", "支付宝外部商户-", "支付宝-转账-"]:
                if rest.startswith(sub):
                    rest = rest[len(sub):]
                    break
            return rest, source
    
    # PAYPAL / 一卡通等无前缀 → 直接作为 counterparty
    return location, "建行储蓄卡"
```

### 2. payment_source

从交易地点前缀推断（见上），对齐工行的 `_infer_payment_source`。

`convert.py` 中 `_infer_payment_source` 对 `ccb_debit` 类型返回 `"建行储蓄卡"`（统一 fallback），但 rec dict 的 `payment_method` 字段使用上述推断结果用于 mapping 路由。

### 3. 退款配对

**删除 `_pair_ccb_refunds`**，改用共用的 `_pair_refunds`（与支付宝/微信/工行信用卡完全一致）。

条件：
- 金额精确匹配
- counterparty 匹配（现可从交易地点精确提取）
- 日期：退款 ≥ 消费日
- 全额：删除两条；部分：调整净额；孤退款：收入

22.23 `美团特约商户` 无消费 counterparty 匹配 → 孤退款，保留为 income。

### 4. description

保留 摘要 列（消费/充值/消费退货/无卡自助交易…），与旧版一致。

### 5. 向后兼容

旧版建行 XLS（交易地点=`***`）仍在 `_extract_ccb_counterparty` 中兜底：
- 交易地点为 `***` 或空 → 回退到对方户名 `/` 后部分（旧逻辑）

## 非目标

- 不修改 `_pair_refunds` 算法本身
- 不修改其他转换器
- 不修改 mapping 规则
