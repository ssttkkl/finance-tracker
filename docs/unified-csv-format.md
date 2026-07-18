# 显式 CSV 导出格式

CSV 只是一种用户选择的导出格式，不是 Finance Tracker 的运行时存储。导出文件不会注册为账本、
snapshot、pending session 或恢复来源，也没有 `append` 命令把它提交为正式事实。

## 现金账单导出

字段来自 `ft.domain.imports.CASHFLOW_EXPORT_FIELDS`：

| 字段 | 含义 |
|---|---|
| `record_id` | provider 记录身份 |
| `date` | 原始业务时间 |
| `amount` | 精确十进制文本 |
| `currency` | 三字符币种 |
| `counterparty` / `description` | 交易对方与说明 |
| `category` | income / expense / transfer 等 |
| `account_name` | 用户显式指定的目标账户 |
| `source` / `bill_source` | 支付来源与 provider |
| `offset_*` | parser 产生的退款/冲正关系证据 |
| `proposed_action` | 解析建议，仅供检查 |

命令：

```bash
ft convert statement.csv --source alipay --account Wallet --output preview.csv
```

## 投资账单导出

字段来自 `ft.schema.CSV_FIELDS`，采用统一事件语义：

| 字段 | 含义 |
|---|---|
| `date` | 业务时间 |
| `action` | swap / deposit / withdraw / dividend / checkin |
| `from_ticker` / `to_ticker` | 资产流出/流入腿 |
| `from_amount` / `to_amount` | 精确数量文本 |
| `price` | provider 价格证据 |
| `commission` / `commission_asset` | 手续费数量与资产 |
| `currency` | 成本/结算币种 |
| `account_name` | 目标投资账户 |
| `note` | 来源说明 |

命令：

```bash
ft stock convert statement.pdf --source dfzq --account 东方证券 --output preview.csv
```

## 精度与安全边界

- 导出金额和数量使用十进制字符串，不写二进制浮点结果。
- 超过 18 位小数、NaN 和 Infinity 在导出前失败。
- 导出可能含敏感财务描述，用户负责选择受控路径；应用不会自动提交或上传输出文件。
- 数据库中的 raw record、formal fact 和 revision 才构成正式审计链。
