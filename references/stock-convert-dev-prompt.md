# 股票对账单转换器开发提示词

## 背景

为 `ft`（finance-tracker）新增一个券商对账单转换器，实现 PDF → stock CSV → 批量导入 records。

## 架构概览

```
用户流程:
  ① ft stock convert -s <broker> -o <csv> <pdf>     # PDF → stock CSV
  ② AI 审查 (references/stock-convert-review.md)      # 审查转换质量
  ③ ft stock append <csv>                             # CSV → records + 快照
```

### 现有代码结构

- `src/ft/importers/dfzq.py` — 东方证券解析器（参考模板）
- `src/ft/stock.py` — `do_convert()` / `do_append()` / `repair_security()`
- `src/ft/cli.py` — CLI 参数注册
- `tests/test_dfzq.py` — 解析器单元测试（参考）
- `tests/test_stock_convert.py` — 集成测试（参考）

## 规范

### stock CSV 10 列格式（CSV_FIELDS）

```
date,action,ticker,shares,price,amount,commission,currency,account_name,note
```

- **date**: `YYYY-MM-DD HH:MM:SS`
- **action**: BUY / SELL / DEPOSIT / WITHDRAW / DIVIDEND / CHECKIN
- **ticker**: 代码 + 后缀（.sz / .sh / .otc / 逆回购无后缀）
- **shares/price/amount/commission**: float（CSV 中存字符串）
- **currency**: CNY / USD / HKD
- **account_name**: 账户名（需提前 `ft acct add`）
- **note**: 备注（印花税/过户费等）

### Action 映射规则

| 中文名    | Action    | 说明                           |
|-----------|-----------|--------------------------------|
| 证券买入  | BUY       | amount = -shares × price       |
| 证券卖出  | SELL      | amount = shares × price        |
| 银行转证券 | DEPOSIT   | shares=0, price=0              |
| 红利入账  | DIVIDEND  | shares=0, price=0, amount=现金分红 |
| 红股入账  | CHECKIN   | price=0, amount=0, ticker=股票代码 |
| 股息扣税  | WITHDRAW  | shares=0, price=0, amount=负数 |
| OTC资金划出| WITHDRAW  | shares=0, price=0              |
| 融券回购  | BUY       | ticker=204001（无后缀）         |
| 融券购回  | SELL      | ticker=204001（无后缀）         |

⚠️ **为保护 PII，此类 PR 说明中的 PDF 密码须脱敏，e.g. `[REDACTED]`。**

### Ticker 后缀规则（复用 `_ticker_suffix` 模式）

- 0/1/2 开头（含 159 ETFs）→ `.sz`
- 5/6 开头 → `.sh`
- 85 开头或 007 开头 → `.otc`
- 204001 逆回购 → 无后缀

### Amount 计算公式

- `amount = total_amount + fee`（从总发生金额还原不含手续费净额）
- BUY: amount 为负数（支出）
- SELL: amount 为正数（收入）
- DEPOSIT/WITHDRAW/DIVIDEND: amount = total_amount（fee=0）

### CHECKIN 末行

转换器应在所有交易记录末尾追加一条 CHECKIN 行（无 ticker），取最后一笔交易的资金余额作为 amount，用于初始化现金余额。

### ETF 分红价差

东方证券分红明细格式：`红利入账(ETF分红或价差)`。若存在有 ticker 无金额的明细，映射方式为 `DIVIDEND` 时 `amount` 为 0，应从 `资金余额` 的一笔差异中推断。

## 实现步骤

### 第一步：解析 PDF 文本

PDF 解密 + 文本提取统一用 `do_convert()` 中的通用流程（qpdf + mutool），解析器只需处理纯文本。

每个券商 PDF 文本结构不同，需要：
1. 定位交易流水段起点（如特征行/表头）
2. 按固定字段顺序解析每条交易
3. 处理多页翻页符、页码标记、汇总段跳过

**解析器函数签名**（参考 `parse_dfzq_text`）：

```python
def parse_{broker}_text(lines: list[str]) -> list[dict[str, Any]]:
```

返回的 dict 字段：

```python
{
    "date": "YYYY-MM-DD HH:MM:SS",
    "action": "BUY",           # 映射后的英文 action
    "ticker": "000001.sz",     # 含后缀
    "name": "平安银行",         # 证券名称（可选）
    "shares": 1000.0,
    "price": 11.5,
    "amount": -11500.0,        # 不含手续费的净额
    "fee": 5.0,
    "stamp_tax": 1.15,
    "transfer_fee": 0.5,
    "balance": 50000.0,        # 交易后资金余额
    "note": "印花税1.15 过户费0.50",
}
```

### 第二步：创建解析器文件

路径：`src/ft/importers/{broker}.py`

内容同 dfzq.py 结构：
- 模块级 `ACTION_MAP`
- `_ticker_suffix()`（若券商后缀规则不同）
- `parse_{broker}_text()`

### 第三步：扩展 do_convert

在 `src/ft/stock.py` 的 `do_convert()` 中添加 `elif source == "{broker_code}"` 分支，导入并调用新的解析器。

### 第四步：注册 CLI

`src/ft/cli.py` 中 `stock convert` 已经通用化（`-s` 参数），如果券商需要额外选项（如不同的加密格式），添加对应参数。

### 第五步：测试

**单元测试**（`tests/test_{broker}.py`）：
- Ticker 后缀映射各类型（深市/沪市/OTC/逆回购）
- 买入/卖出解析 + amount 公式验证
- DEPOSIT/WITHDRAW/DIVIDEND/CHECKIN 映射
- 交货排序（PDF 倒序 → 升序输出）
- CHECKIN 末行取最后余额
- 多页/页码跳过
- 空输入/无表头

**集成测试**（`tests/test_stock_convert.py` 追加）：
- 有效 CSV → 写入 + 快照重建
- 未知账户 → 报错不写入
- 无效 action → 报错
- 缺少字段 → 报错

## 运行验证

```bash
# 激活环境
cd ~/.hermes/skills/finance/finance-tracker
source .venv/bin/activate

# 跑全部测试
pytest -x -q

# 仅跑新解析器测试
pytest tests/test_{broker}.py -x -v

# 端到端验证
ft stock convert -s {broker_code} -o /tmp/{broker}_stock.csv <对账单PDF>
# AI 审查 CSV（见 stock-convert-review.md）
ft stock append /tmp/{broker}_stock.csv
ft stock list
ft verify
```

## 快照结构

所有 security 账户统一写 `accounts.security.{account_name}`（`_ensure_account` 保证），顶层同名旧副本在 `repair_security()` 时自动清理。`do_list` 同时读两层。
