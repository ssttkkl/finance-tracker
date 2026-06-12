# 退款追踪 CSV 设计

> 2026-06-09 | convert 阶段退款配对生成追踪 CSV

## 动机

`_pair_refunds`（支付宝/微信/ICBC信用卡）和 `_pair_ccb_refunds`（建行储蓄卡）目前静默核销退款——配上的全额删除、部分调净额，不留审计痕迹。

参考 merge 去重阶段的 `removed.csv`，为退款配对生成 `_refunds.csv`，实现完整的可追溯核销链路。

## 影响范围

| 转换器 | 退款函数 | 改动类型 |
|---|---|---|
| 支付宝 | `_pair_refunds` (共用) | 签名 + 追踪输出 |
| 微信 | `_pair_refunds` (共用) | 同上 |
| ICBC 信用卡 | `_pair_refunds` (共用) | 同上 |
| 建行储蓄卡 | `_pair_ccb_refunds` | 签名 + 追踪输出 |

ICBC 储蓄卡无退款配对逻辑，不影响。

## 核心改动

### 1. 退款函数签名变更

`_pair_refunds` 和 `_pair_ccb_refunds` 的返回值从 `list[dict]` 改为元组：

```python
(cleaned_records: list[dict], tracking_pairs: list[dict])
```

`tracking_pairs` 列表，每项描述一对退款配对的完整信息：

```python
{
    "expense": rec,      # 原消费 rec dict（含原始金额，调整前快照）
    "refund": rec,        # 退款 rec dict
    "match_type": "full" | "partial",
}
```

### 2. 各 raw reader 返回值变更

| 函数 | 旧返回值 | 新返回值 |
|---|---|---|
| `_read_alipay_raw` | `list[dict]` | `(list[dict], list[dict])` — (records, tracking_pairs) |
| `_read_wechat_raw` | `list[dict]` | `(list[dict], list[dict])` |
| `_read_icbc_raw` | `(list[dict], str)` | `(list[dict], str, list[dict])` — (records, bill_type, tracking_pairs) |
| `read_ccb_debit` | `list[dict]` | `(list[dict], list[dict])` |

### 3. 追踪 CSV 格式

文件名：主 CSV 同名 + `_refunds` 后缀。`alipay_202606.csv` → `alipay_202606_refunds.csv`。

列：标准 10 列 + `refund_status`

每对两行相邻：消费行在前，退款行在后。

`refund_status` 枚举：

| 值 | 行类型 | 说明 |
|---|---|---|
| `已全额退款` | 消费 | 该笔被完全核销，两条记录都从主 CSV 消失 |
| `已部分退款(净额-70.00)` | 消费 | 主 CSV 中保留，净额已是调整后值。这里显示原始金额 |
| `退款核销` | 退款 | 配上的退款 |

### 示例

```
date,amount,...,refund_status
2026-03-14 12:00:00,-200.00,...,已全额退款
2026-03-14 18:00:00,200.00,...,退款核销
2026-03-21 14:00:00,-100.00,...,已部分退款(净额-70.00)
2026-03-21 15:00:00,30.00,...,退款核销
```

### 4. do_convert 改动

- 收集所有 raw reader 返回的 `tracking_pairs`
- 写完主 CSV 后，如果 `tracking_pairs` 非空，写 `_refunds.csv`
- 写追踪 CSV 时使用与主 CSV 相同的 mapping 路由（account_name/source 由 do_convert 层注入，非 raw reader 负责）

### 5. _pair_refunds 改动细节

在配对决策后、返回前，记录每对：

```python
pair = {
    "expense": {**exp, "amount": -(exp_original_amount)},
    "refund": {**ref, "amount": ref["amount"]},
    "match_type": "full" if exact_amt else "partial",
}
tracking_pairs.append(pair)
```

`exp_original_amount` 是记录在被消耗/调整前的原始值。

### 6. _pair_ccb_refunds 改动细节

仅全额配对（已移除部分退款逻辑），每对全额匹配记录为 `match_type="full"`。

## 非目标

- 不改变退款配对算法本身
- 不修改 mapping 规则
- 不修改 merge 去重的 removed.csv 格式
- 不涉及 ICBC 储蓄卡（无退款配对）
