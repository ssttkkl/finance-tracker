# CSV 预览与交换格式

CSV 只是用户选择的预览/交换格式，不是运行时存储。Web 与 Expo 的账单导入流程把解析预览直接展示在导入会话中；预览不会注册为账本、snapshot 或恢复来源，也不会自动上传。

## 现金账单预览

现金账单使用 `/api/v1/cash-import/scan` 和 `/api/v1/cash-import/preview`。响应中的常见字段：

| 字段 | 含义 |
|---|---|
| `record_id` | 业务行标识（导入幂等键的一半） |
| `date` | 原始业务时间 |
| `amount` | 精确十进制文本 |
| `currency` | 三字符币种 |
| `counterparty` / `description` / `note` | 对方与说明 |
| `category` | income / expense / transfer 等解析分类 |
| `account_name` | 账单字段和 workspace mapping 得到的目标账户 |

现金账户由账单字段和映射路由，客户端不能通过预览请求强行覆盖账户。

## 投资账单预览

投资账单同样先走预览，再在确认阶段选择目标投资账户。统一事件字段包括：

| 字段 | 含义 |
|---|---|
| `date` | 业务时间 |
| `record_type` / `record_subtype` | 规范事件语义，如 `funding` / `external`、`trade` / `security` |
| `from_ticker` / `to_ticker` | 付出资产 / 换入资产 |
| `from_amount` / `to_amount` | 精确数量文本 |
| `price` | 解析侧价格证据 |
| `commission` / `commission_asset` | 手续费 |
| `currency` | 成本/结算币种 |
| `account_name` | 确认时选择的目标投资账户 |
| `note` | 说明 |

## 精度与安全

- 金额、数量、成本和汇率用十进制字符串，不使用二进制浮点作为权威值。
- 超过存储精度、`NaN`、`Infinity` 在导入确认前失败。
- 账单密码通过 `X-FT-Statement-Password` 传递，不写入日志、TokenStore、缓存或数据库。
- 审计权威在数据库正式事实行上的 `source_type`、`record_id` 和 `source_payload`。
- 预览过期或关系上下文变化时必须重新扫描/预览，不以旧结果提交。
