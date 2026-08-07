## Why

当前代码把 `Asia/Shanghai` 同时用于浏览器展示、Web 日期筛选、关系匹配、财富日桶和手工写入。这样同一条带时区时间在不同客户端会出现不同的日期语义，且后端的 naive datetime 会把部署环境时区隐式带入财务计算。现在需要把时间边界明确分层：导入器继续按来源合同解释原始账单，后端只保存和传递 UTC 或明确带时区的时间，浏览器展示和日期范围按用户本地时区完成。

## What Changes

- 移除非导入器代码中的固定 `Asia/Shanghai` 时区依赖。
- 将后端解析、手工写入、查询边界、关系匹配、财富日桶和汇率业务日期统一为 UTC 或保留输入时区的 aware datetime；SQLite 和 PostgreSQL 返回同一 UTC 语义。
- Web API 接收前端浏览器的 IANA 时区，用它把日期筛选转换为带时区的 UTC 查询边界，并将该时区绑定到 cursor 和月度汇总合同。
- 前端发生时间和月份分割行使用浏览器本地时区，不再向 `Intl.DateTimeFormat` 传入固定 `timeZone`。
- 保留导入器对来源无时区日期的既有解释，不重写导入器来源语义。
- **BREAKING**：Web 查询 cursor 和筛选响应会绑定浏览器时区；缺少或非法时区的非默认调用方需要改为传入有效 IANA 时区。

## Capabilities

### New Capabilities

- `time-boundary-contract`：定义导入、后端时间数据和浏览器本地展示之间的边界合同。

### Modified Capabilities

- `020-cash-ledger-browser-web`：发生时间、月份分割行、日期筛选和月度汇总改为浏览器本地时区。
- `002-dual-database-runtime`：两个后端统一返回 UTC aware 时间，并保持带时区输入的等价行为。
- `003-wealth-attribution-core`：财富规范日桶改为 UTC 业务边界，不再固定绑定上海时区。
- `006-transaction-relations`：关系匹配对带时区时间统一按 UTC 比较和分桶。
- `cash-investment-funding-relations`：资金调拨候选窗口按 UTC 日历计算。

## Impact

- 受影响代码覆盖 `src/ft` 的时间解析、查询、关系、财富和手工现金写入边界，以及 `web/src` 的格式化、月份分组、筛选请求和类型。
- 受影响测试包括 Web API/组件、关系匹配、财富日投影、资金调拨、SQLite/PostgreSQL 契约测试；不修改导入器实现。
- 不新增数据库列、不迁移历史金额或事实；数据库已有 aware 时间继续以 UTC 往返。
- 回滚可恢复旧的时区边界实现；由于不改变已保存 UTC 瞬时值，不需要数据回滚。
