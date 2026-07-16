# Unified swap 手续费契约与历史迁移

## 规范 CSV 语义

对新的 `swap` 行：

- `from_amount` / `to_amount` 是**成交毛额**，不含手续费。
- `commission` 是单独收取的手续费。
- `commission_asset` 是实际扣费资产，必须填写；现金买卖通常为账户本位币（如 `CNY`、`USD`）。
- replay 先按 swap legs 处理成交，再从 `commission_asset` 扣费。
- 若手续费从 `from_ticker` 扣除（典型现金买入），该手续费计入接收标的的总成本；典型现金卖出则降低收到现金。

示例：买入 10 股 @100、佣金 1 CNY：

```csv
action,from_ticker,to_ticker,from_amount,to_amount,price,commission,commission_asset
swap,cny,159330.sz,1000,10,100,1,CNY
```

现金减少 1001；持仓总成本为 1001。

## 旧格式兼容规则

历史行若 `commission_asset` 为空，视为**现金腿已经是净额**的旧格式。replay 不得再根据 `commission` 扣费，否则会双扣。

因此不能只改 replay；必须先辨别历史 CSV 采用的是“净额 + 空 asset”还是“毛额 + 空 asset”。

## 券商导入器

券商 PDF 解析器若将 `from_amount/to_amount` 输出为成交毛额，必须同时写出：

```python
commission_asset = currency
```

东方证券的买卖成交即适用此规则。不要仅保存 `commission` 而留空 `commission_asset`，否则手续费会成为仅审计、非账务字段。

## 修复已导入历史的安全流程

1. **不要直接改真实 `~/.ft`。** 在 Git 仓库外创建 `records/security/` 临时副本。
2. 精确筛选目标账户、`action=swap`、`commission > 0` 且 `commission_asset` 为空的行；不要全账本盲改。
3. 在副本将候选行标为该账户本位币的 `commission_asset`，全量 replay。
4. 将重放后的现金、持仓和券商对账单/已确认余额闭合；同时确认非目标账户及非候选行未变化。
5. 向用户展示候选行数、重放差额和预期影响，取得确认后才原子写回真实 CSV。
6. 写回后运行 `ft verify --fix && ft verify`，并复查 `ft stock list`。不要把临时备份放进 `~/.ft`，避免被自动暂存。

## 回归测试最低要求

修复前先写 RED 测试，覆盖：

- 入金 → 毛额买入（现金减少 `成交额 + fee`，持仓成本含 fee）→ 毛额卖出（现金增加 `成交额 - fee`）；
- 旧格式（空 `commission_asset`、净现金腿）不会双扣；
- 由该券商导入器生成的新行带有正确的 `commission_asset`。
