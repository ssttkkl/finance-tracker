# 证券对账单转换结果审查提示词

## 适用场景

`ft stock convert` 将券商 PDF 转换为 stock CSV 后，逐条审查转换质量。

## 审查提示词

```
逐条审查 {csv_path}（N条记录）。

## 检查清单

### 1. Action 映射
每条记录的 action 字段必须在 {BUY, SELL, DEPOSIT, WITHDRAW, DIVIDEND, CHECKIN} 中。
检查是否有未映射的原始中文action（如"红利入账""红股入账"等应已被正确映射）。

### 2. Ticker 后缀
- 深市（0/1/2 开头，含 159 ETFs）→ .sz
- 沪市（5/6 开头）→ .sh
- OTC（851890, 007011 等）→ .otc
- 逆回购 204001 → 无后缀

### 3. Amount 公式正确性
- BUY: amount = -shares × price   （金额为负）
- SELL: amount = shares × price   （金额为正）
- amount + commission = 总发生金额（从PDF原始值校验）

### 4. DIVIDEND（红利入账）
- shares=0, price=0
- amount = 现金分红金额（正数）

### 5. CHECKIN（红股入账/送股）
- ticker=股票代码, shares=送股数量, price=0
- amount=0

### 6. DEPOSIT（银行转证券/银证转账入金）
- shares=0, price=0
- amount=入金金额（正数）

### 7. WITHDRAW（OTC资金划出/股息红利差异扣税）
- shares=0, price=0
- amount=出金金额（负数，含股息扣税）

### 8. CHECKIN（资金余额初始化）
- 只有一条无 ticker 的 CHECKIN，位于 CSV 末尾
- amount=当前资金余额（正数）
- shares=0, price=0, ticker=空

### 9. 日期格式
- 格式: YYYY-MM-DD HH:MM:SS
- 按日期升序排列

### 10. note 字段
- 印花税>0 时写"印花税X.XX"
- 过户费>0 时写"过户费X.XX"
- 多个字段空格分隔

### 11. 总条数核对
- BUY + SELL + DIVIDEND + DEPOSIT + WITHDRAW + CHECKIN(有ticker) + 1(CHECKIN无ticker) = 总条数
- 与 PDF 对账单中的资金流水明细交易数量一致

### 12. 持仓校验（可选：导入后运行）
- `ft stock list` 查看当前持仓和现金
- `ft verify` 检查 CSV ↔ 快照一致性
- 与 PDF 汇总页中的证券市值、持仓数量、资金余额对比

## 输出格式

只输出有问题的记录，每条格式：
- 行N: {问题描述}

无误则输出：✅ 全部通过（N条）
```
