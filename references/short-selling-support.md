# 做空支持开发记录

## 改动日期

2026-06-26

## 背景

系统之前不支持做空（负股数）。`do_sell` 遇到无持仓时直接 return 报错（"⚠️ No position for X"），`repair_security` 用 `if p["shares"] <= 0: continue` 丢弃所有非正股数。

用户导入 GOOG 历史交易时包含一笔沽空→平仓对子，被系统错误处理：沽空跳过、平仓被当成普通买入，导致持仓多出4股。

## 改动文件

`src/ft/stock.py`

## 改动函数

| 函数 | 改动 |
|---|---|
| `do_sell` | 无持仓时自动创建负股数；支持已有空头继续加空；支持多头翻空 |
| `do_buy` | `pos["shares"] > 0` → `!= 0`，负股数（平仓）也能计算 avg_cost |
| `_replay_security_csv` | SELL 支负股数（不再跳过 shares=0）；BUY 支平仓（old_s < 0 时累加成本） |
| `repair_security` | `p["shares"] <= 0: continue` → `== 0: continue` |
| `verify_security` | CSV→Snapshot 校验支负股数 |

## 双四舍五入修复

`_replay_security_csv` 的 BUY 路径原代码：

```python
avg = round((old_c + s * p) / new_s, 2)
h["total_cost"] = round(avg * new_s, 2)
```

→ 每次 BUY 约 drift +$0.06，12 笔后累计 +$989。改为：

```python
h["total_cost"] = round(old_c + s * p, 2)
```

## 测试验证

```
做空: ft stock sell --ticker aapl.us --shares 5 --price 220
  → shares=-5, avg_cost=$220
平仓: ft stock buy --ticker aapl.us --shares 2 --price 215
  → shares=-3, avg_cost≈$223.17
verify --fix 全量重建 + 校验通过
```

## 后续发现的隐藏问题

做空修复后 `repair_security` 不再丢弃负股数，暴露了一个之前被吃掉的 **BYD 002594.sz -200股"假空头"**（因 CSV 中一条多余的 SELL 记录导致）。手动删除该条 SELL 后修复。
