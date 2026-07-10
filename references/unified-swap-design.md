# Unified Swap Accounting — Design Reference

**Spec**: `docs/superpowers/specs/2026-07-09-unified-swap-design.md`
**Status**: Approved, pending implementation
**Date**: 2026-07-09

## 核心变化

旧模型：cash（现金）和 positions（持仓）是两个概念，BUY/SELL 操作 cash，SWAP 操作两个 positions，多币种现金需要层层补丁。

新模型：所有资产（USD/USDT/BTC/NVDA/...）统一为 position `{shares, avg_cost, cost_currency}`。所有交易统一为 swap。cash 字段彻底删除。

## Snapshot 新结构

```yaml
accounts:
  security:
    IBKR:
      base_currencies: [USD, HKD, CNY]
      positions:
        USD:  {shares: 8158.9, avg_cost: 1.0, cost_currency: USD}
        NVDA: {shares: 25, avg_cost: 205.9, cost_currency: USD}
    Kraken:
      base_currencies: [USD, USDT]
      positions:
        USDT: {shares: 1316.5, avg_cost: 1.0, cost_currency: USDT}
        BTC:  {shares: 0.02049, avg_cost: 63710, cost_currency: USD}
```

- `base_currencies`: 本位币列表（fiat/stablecoin），从 accounts.yaml 配置
- 本位币 position: avg_cost 固定 1.0，cost_currency = 自身
- 非本位币 position: avg_cost + cost_currency 追踪 acquisition cost

## CSV 新格式（12 列）

```
date, action, from_ticker, to_ticker, from_amount, to_amount, price, commission, commission_asset, currency, account_name, note
```

Actions: `swap`（统一交易）, `deposit`, `withdraw`, `dividend`, `checkin`

手续费内嵌在 swap 的 commission + commission_asset 字段，无独立 FEE action。

## 已删除的旧概念

| 旧概念 | 替代 |
|--------|------|
| `cash` 字段 | base currency positions |
| `cash_map` | deleted |
| `cash_legacy` | deleted |
| `FIAT` set | `base_currencies` from account config |
| `pending_swaps` dict | inline cost transfer in swap |
| `BUY` / `SELL` action | `swap` |
| `FEE` action | commission field in swap |
| `_extract_quote_currency` | deleted |
| `quote:XXX` note hack | deleted |
| `CASH_QUOTES` in exchange_sync | `base_currencies` from account |

## accounts.yaml 新字段

每个 security/crypto 账户新增 `base_currencies`:

```yaml
- name: IBKR
  type: security
  currency: USD
  base_currencies: [USD, HKD, CNY]
- name: Kraken
  type: crypto
  currency: USD
  base_currencies: [USD, USDT]
```

## 影响范围

- `stock.py`: CSV_FIELDS、replay/verify/repair 全部重写
- `exchange_sync.py`: 删 CASH_QUOTES、trade_to_rows 统一 swap
- `snapshot.py`: 新增 base_currencies、删 cash 字段
- `models.py`: 更新 CSV_FIELDS
- `report.py`: 适配新 snapshot 结构
- `polymarket_sync.py`: 适配新 CSV 格式
- `append.py`: 适配新 CSV 格式
- 测试: test_stock.py、test_exchange_sync.py 重写

## 注意事项

- **不兼容旧格式**：用户明确要求不保留向后兼容
- 所有现有 CSV 需要从当前 snapshot + 交易历史重新生成
- 银行卡/微信/支付宝（accounts.cash 类型）暂不改动
