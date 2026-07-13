# Corporate Actions in ft

## 当前支持状态（2026-07-13 更新）

### 支持的5种 action

| action | 用途 |
|--------|------|
| swap | 资产A→资产B（买卖） |
| deposit | 外部入金 |
| withdraw | 出金 |
| dividend | 现金分红 **+ 送股/转增** |
| checkin | 校准持仓到指定值 |

### `dividend` action（已修复，支持送股）

`_replay_security_rows` 中 dividend 的逻辑现在区分现金分红和股票分红：

```python
h["shares"] += to_amount
# 现金分红（to_ticker 是货币如 cny/usd）：total_cost 增加
# 送股/转增（to_ticker 是股票如 002594.sz）：total_cost 不变
if "." not in to_ticker and to_ticker == to_ticker.lower():
    h["total_cost"] += to_amount
```

判断依据：`to_ticker` 无 `.` 且全小写 → 货币（现金分红）；有 `.` 或大写 → 股票（送股）。

**CSV 写法：**
```
# 现金分红：to_ticker = 货币
2025-08-01,dividend,,,0,500,1,0,,CNY,东方证券,现金分红

# 送股/转增：to_ticker = 股票代码，to_amount = 送股数量
2025-08-01,dividend,002594.sz,002594.sz,0,400,0,0,,CNY,东方证券,1拆3 送股
```

### 送股/拆股的正确处理方式

**用户明确偏好：不修改历史交易记录，应加一行独立记录。**

1. **送股/转增**（如每10股送3股）：用 `dividend` action
   - 计算送股数：原持仓 × 送股比例（如200股×2=400股）
   - CSV：`dividend,002594.sz,002594.sz,0,400,0,0,,CNY,东方证券,送股`
   - 效果：shares +400，total_cost 不变，avg_cost 自动下降

2. **拆股**（如1拆3）：也用 `dividend` action
   - 拆股 = 送股的特例（1拆3 = 每1股送2股）
   - 计算送股数：原持仓 × (拆股倍数-1)（如200股×2=400股）
   - CSV：`dividend,002594.sz,002594.sz,0,400,0,0,,CNY,东方证券,1拆3`
   - 效果：shares 200→600，avg_cost ¥352→¥117

3. **配股**（股东按比例认购）：用 `swap` action（用钱买配股）
   - CSV：`swap,cny,002594.sz,配股价×配股数,配股数,配股价,手续费,,CNY,东方证券,配股`

### ⚠️ `ft stock checkin` 历史日期的快照陷阱

`ft stock checkin` 命令会**同时**创建 CSV 记录和更新 snapshot。当 checkin 日期是历史日期而后续还有交易时，snapshot 会偏高/偏低。

**修复方法：** 运行 `ft stock checkin` 后，手动编辑 `~/.ft/snapshot.yaml` 修正，不要用第二个 checkin。

### dfzq 转换器支持的公司行为

`ft stock convert -s dfzq` 现在自动转出以下记录：

| PDF 原文 | 转换结果 | 说明 |
|----------|----------|------|
| 红利入账 | `dividend` (to_ticker=CNY) | 现金分红 |
| 红股入账 | `dividend` (to_ticker=股票) | 送股/转增 |
| 银行转证券 | `deposit` | 银证转入 |
| 证券转银行 | `withdraw` | 银证转出 |
| OTC资金划入 | `deposit` | OTC 入金 |
| OTC资金划出 | `withdraw` | OTC 出金 |
| 股息红利差异扣税 | `withdraw` | 分红税 |
| 利息归本 | `deposit` | 利息入账 |

**⚠️ 红利入账 vs 红股入账的区分（2026-07-13 修复）：**

PDF 中「红利入账」和「红股入账」都映射到 `DIVIDEND` action，但语义完全不同：
- **红利入账**：现金分红。PDF 中的 `shares` 是参与分红的股数（如 300 股 × ¥2.26 = ¥678），不是额外获得的股份。转换后 `to_ticker=CNY`，`to_amount=分红金额`。
- **红股入账**：送股/转增。PDF 中的 `shares` 是实际获得的股数。转换后 `to_ticker=股票代码`，`to_amount=送股数`。

**历史 bug**：`_make_txn` 用 `shares > 0` 判断是否是送股，导致红利入账（shares=300/800）被错误当成送股，凭空多出仓位。**修复**：改为 `action_raw == "红股入账"` 判断。

### 比亚迪 2025年拆股实例

- 拆股日期：2025年8月1日（7/22和8/22之间）
- 比例：1拆3（每1股送2股）
- 拆股前：200股 @ ¥351.99 avg_cost
- 拆股后：600股 @ ¥117.33 avg_cost
- 处理：`dividend,002594.sz,002594.sz,0,400,0,0,,CNY,东方证券,1拆3`
- 历史记录（2/7~7/22的BUY/SELL）完全不动

### 中国A股常见公司行为

| 行为 | 英文 | ft action | 说明 |
|------|------|-----------|------|
| 拆股 | stock split | dividend | shares ×N, total_cost 不变 |
| 送股 | bonus shares | dividend | shares +送股数, total_cost 不变 |
| 转增 | capital reserve transfer | dividend | 同送股 |
| 配股 | rights issue | swap | 用钱买配股，total_cost +配股成本 |
| 增发 | additional offering | swap | 同配股 |
| 现金分红 | cash dividend | dividend | cash +金额 |
