# 东方证券对账单转换 + stock append 设计

> 日期: 2026-06-13
> 状态: 设计完成
> 关联: stock.py, cli.py, importers/dfzq.py

## 背景

已有 `ft stock buy/sell/deposit/withdraw/checkin` 等手动交易命令，但缺少**对账单批量导入**能力。东方证券（东方财富证券）PDF 对账单含两年资金流水，需自动解析并按 `stock CSV 10列格式` 落库。

## 目标

1. `ft stock convert <file> -s dfzq --password <pwd> -o <output.csv>` — 东方证券 PDF → stock CSV
2. `ft stock append <file>` — stock CSV 写入 records/security/ + 重建快照
3. 支持 `-s` 扩展其他券商（中金、华泰等）

## 命令签名

```bash
# 转换
ft stock convert 电子对账单.pdf -s dfzq --password 099215 -o dfzq_stock.csv

# 导入（支持所有券商 CSV）
ft stock append dfzq_stock.csv
```

CLI 需要：
- `ft stock` 下新增 `convert` 和 `append` 两个子命令
- `convert` 子命令：`-s/--source` 必选，`--password` 可选，`-o/--output` 必选，可选 `--currency` 和 `--account`
- `append` 子命令：接收一个 file 参数

## 1. PDF 解析器 — importers/dfzq.py

### 解密与文本提取

```
qpdf --decrypt → mutool draw -F text → 文本行
```

### 文本结构

PDF 包含三块：
1. **头部** — 客户信息、查询区间、资产汇总（证券市值、资金余额、总资产）
2. **汇总股票资料** — 当前持仓（代码、名称、股数、市价、成本价）
3. **资金流水明细** — 交易列表（发生日期、买卖类别、证券代码、名称、数量、价格…），**按时间倒序**

### 字段映射

| PDF 字段 | Stock CSV | 处理 |
|----------|-----------|------|
| 发生日期 YYYYMMDD | `date` | 格式化为 `YYYY-MM-DD 00:00:00` |
| 买卖类别 | `action` | 见下方 action 映射表 |
| 证券代码 | `ticker` | 加上市场后缀 |
| 成交数量 | `shares` | int |
| 成交价格 | `price` | float |
| 总发生金额 + 手续费 | `amount` | `amount = 总发生金额 + 手续费`（还原不含佣金的净额） |
| 手续费 | `commission` | 直接填入 |
| 印花税/过户费 | `note` | 合并记录（如 `"印花税0.50 过户费0.02"`） |
| — | `currency` | 固定 CNY（由账户决定） |
| — | `account_name` | 固定 `东方证券`（或由 `--account` 覆盖） |

### Action 映射表

| PDF 买卖类别 | action | 说明 |
|-------------|--------|------|
| 证券买入 | BUY | 金额为负 |
| 证券卖出 | SELL | 金额为正 |
| 银行转证券 | DEPOSIT | shares=0, price=0, amount=正 |
| OTC资金划出 | WITHDRAW | shares=0, price=0, amount=负 |
| 融券回购 | BUY | 逆回购卖出（借钱出去），ticker=204001 |
| 融券购回 | SELL | 逆回购到期（收回本息），ticker=204001 |

### Ticker 后缀规则

| 匹配条件 | 后缀 | 示例 |
|---------|------|------|
| 深市（0/1/2 开头） | `.sz` | 159740.sz, 000001.sz |
| 沪市（5/6 开头） | `.sh` | 600050.sh, 601398.sh |
| OTC 产品（851890, 007011 等） | `.otc` | 851890.otc |
| 逆回购（204001） | 无后缀 | 204001 |

### CHECKIN 行

从资金流水明细中取**最后一笔交易**的 资金余额 值。在 CSV 末尾追加一行：

```csv
date,action,ticker,shares,price,amount,commission,currency,account_name,note
2026-06-12 00:00:00,CHECKIN,,0,0.00,23049.72,0.00,CNY,东方证券,资金余额初始化
```

注意这里 `amount=23049.72`（现金余额为正），`shares=0`, `price=0`。

### 输出排序

PDF 行序是时间**倒序**。转换器处理时需：
1. 解析所有行
2. 按日期**升序**排序
3. CHECKIN 行放在末尾（日期同最后一条交易或略后）

## 2. `ft stock append` — CSV 批量导入

### 校验

- 检查是否有 10 列：date, action, ticker, shares, price, amount, commission, currency, account_name, note
- action 必须是 BUY/SELL/DEPOSIT/WITHDRAW/DIVIDEND/CHECKIN/INIT
- account_name 必须存在于 accounts.yaml 中（type=security）
- 数字字段可解析为 float

### 写入逻辑

1. 按 `date` 升序排序
2. 每行写入 `records/security/{date[:10]}.csv`（追加到已有文件）
3. 写入后自动调用 `repair_security()` 全量重建快照
4. git commit

### 错误处理

- 账户不存在 → 打印错误并提示 `ft acct add 东方证券 --type security --currency CNY`
- CSV 格式错误 → 打印具体行号+错误信息
- 写入不覆盖已有 CSV 行，采用按日合并（与 `record_trade` 一致）

## 3. 与现有代码的关系

- `importers/dfzq.py` — 新文件，纯解析函数
- `stock.py` — 新增 `do_convert()` 和 `do_append()` 函数
- `cli.py` — 在 `stock` 子命令下注册 convert 和 append
- 复用 `_replay_security_csv` 和 `repair_security`（已有函数）
- **不改动**任何现有命令、格式或快照结构

## 4. 测试要点

- **PDF 解析**：用真实解密文本做单元测试，覆盖所有 action 类型
- **ticker 后缀**：测试深市/沪市/OTC/逆回购四条规则
- **CHECKIN 行**：验证取最后一笔的资金余额
- **排序**：验证倒序输入→升序输出
- **append 校验**：测试缺字段、账户不存在、未知 action 三种失败场景
- **往返验证**：convert→append 后 `ft stock list` 持仓和现金与 PDF 汇总页一致

## 5. 审查要点

转换后 CSV 审查 checklist：
- [ ] 总记录数与 PDF 资金流水明细一致
- [ ] BUY/SELL 金额 = 成交数量 × 成交价格
- [ ] amount + commission = 总发生金额
- [ ] 银行转证券 / OTC资金划出 正确识别为 DEPOSIT/WITHDRAW
- [ ] 逆回购正确标记（ticker=204001）
- [ ] 代码后缀映射正确
- [ ] 日期排序正确
- [ ] 末尾 CHECKIN 金额 = 最后一笔资金余额
