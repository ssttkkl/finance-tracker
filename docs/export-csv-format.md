# 显式 CSV 导出格式

CSV 只是用户选择的**预览/交换**格式，不是运行时存储。导出文件不会注册为账本、snapshot 或恢复来源；**没有** `append` 命令把它提交为正式事实。正式写入使用 `ft import`、`ft sync` 或手动 `add` / `stock *`。

## 现金账单导出

字段来自 `ft.domain.imports.CASHFLOW_EXPORT_FIELDS`（以代码为准）。常见列：

| 字段 | 含义 |
|---|---|
| `record_id` | 业务行标识（导入幂等键的一半） |
| `date` | 原始业务时间 |
| `amount` | 精确十进制文本 |
| `currency` | 三字符币种 |
| `counterparty` / `description` 或 `note` | 对方与说明 |
| `category` | income / expense / transfer 等 |
| `account_name` | 解析/路由得到的目标账户名 |

命令：

```bash
ft convert statement.csv --source alipay --output preview.csv
```

现金 `convert` **不**接受 `--account`（与 `import` 相同：账户由账单 + mapping 决定）。

> 历史列名如 `offset_*` / `proposed_action` / 双列 `source`+`bill_source` 在 **015** 后已非正式事实权威；若导出仍出现，仅作解析检查，不入账。

## 投资账单导出

字段来自 `ft.schema.CSV_FIELDS`，统一事件语义：

| 字段 | 含义 |
|---|---|
| `date` | 业务时间 |
| `record_type` / `record_subtype` | 规范事件语义，如 `funding` / `external`、`trade` / `security`、`expense` / `tax` |
| `from_ticker` / `to_ticker` | 付出资产/换入资产 |
| `from_amount` / `to_amount` | 精确数量文本 |
| `price` | 解析侧价格证据（落库投资事件可能不单列 price） |
| `commission` / `commission_asset` | 手续费 |
| `currency` | 成本/结算币种 |
| `account_name` | 目标投资账户 |
| `note` | 说明 |

命令：

```bash
ft stock convert statement.pdf --source dfzq --output preview.csv
```

## 精度与安全

- 金额与数量用十进制字符串，不用二进制浮点展示为权威。
- 超过存储精度、NaN、Infinity 在导出或导入前失败。
- 导出可能含敏感描述；用户自选路径。应用不自动上传。
- 审计权威在数据库正式事实行上的 `source_type` / `record_id` / `source_payload`。
