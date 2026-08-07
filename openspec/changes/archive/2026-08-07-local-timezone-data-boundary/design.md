## Context

见 `proposal.md`。当前数据库模型已经通过 `UTCDateTime` 在两个后端把带时区时间保存为 UTC，但若干应用层、关系层、财富层和查询适配器仍把 naive 输入补成 `Asia/Shanghai`，并把带时区结果重新格式化为上海时间。Web 查询还把日期输入直接转换为上海自然日，前端的时间和月份展示也固定使用上海时区。

## Goals / Non-Goals

**Goals：**

- 建立单一的非导入器时间边界：后端内部使用 UTC aware datetime，API 输出保留明确 offset。
- 让 Web 日期筛选、月度汇总、发生时间和月份分割行共享浏览器本地 IANA 时区。
- 让 SQLite 与 PostgreSQL 在时间解析、比较、排序、分页 cursor 和结果序列化上保持等价。
- 用测试覆盖 UTC 月边界、非零 offset、DST 时区、无 offset 输入和非法时区。

**Non-Goals：**

- 不改变导入器对来源无时区日期的既有解释，不把来源账单日期改成浏览器本地时间。
- 不修改数据库 schema、历史事实的瞬时值、金额语义、关系种类或投影结构。
- 不让后端猜测用户时区，也不增加用户账户级时区配置。

## Decisions

### 1. 后端 canonical 时间为 UTC aware

所有非导入器 `datetime` 解析在进入应用服务或 persistence adapter 时执行以下规则：已有 aware 值转换为 `timezone.utc`；无 offset 的后端输入按 UTC 解释，或在边界要求明确 offset 时失败。序列化统一使用 `datetime.isoformat()`，保留 `+00:00`，不再使用上海时间格式化字符串。

涉及手工现金/投资写入的 date-only + time 输入也按 UTC 组成 aware 值。这样服务器 `TZ`、SQLite 的 naive 返回值和 PostgreSQL 的 `TIMESTAMP WITH TIME ZONE` 不会改变结果。

备选方案是继续用 `Asia/Shanghai` 作为后端默认时区，但它仍会让部署地区和用户展示产生不一致，且无法满足移除固定时区的目标，因此不采用。

### 2. Web 以请求时区计算日期边界

浏览器在每次投影列表请求中发送 `timezone=Intl.DateTimeFormat().resolvedOptions().timeZone`。后端把该字段纳入 `ProjectionFilters` 和 cursor 签名：

```text
date_from=2026-07-01, timezone=Asia/Tokyo
      ↓
2026-07-01T00:00:00+09:00 ≤ occurred_at < 2026-07-02T00:00:00+09:00
      ↓
查询 UTC aware 边界
```

后端用 `ZoneInfo` 校验 IANA 名称，并把本地自然日的开始/结束转换为 UTC。没有提供时区的直接服务调用保留 UTC 默认，便于已有 CLI/服务调用明确采用后端 canonical 语义；Web 入口总是显式发送浏览器时区。非法时区返回 `invalid_filter`，不得回退到服务器时区。

月度汇总使用同一个请求时区生成月份键，cursor 同时绑定完整筛选条件和时区，避免先在一个时区取第一页、再在另一个时区续读。

备选方案是由前端把 UTC 边界作为两个隐藏参数发送。该方案能表达瞬时但不能让后端验证本地自然日合同，也会把 DST 规则分散在客户端，因此不采用。

### 3. 前端格式化不指定 `timeZone`

`formatOccurredAt` 和月份键 formatter 均使用 `Intl.DateTimeFormat` 的浏览器默认时区。formatter 仍使用固定 locale/字段，月份键只读取命名的 `year`、`month` 字段，不依赖格式化字符串布局。组件测试通过 `TZ` 或显式测试环境验证跨 UTC 月份边界，避免恢复固定地区时区。

### 4. 业务日期计算统一按 UTC

关系候选、现金—投资资金调拨、财富 daily read model、汇率业务日期、月份查询和手工写入的 backend calendar 均以 UTC 日期或带时区值归一化后的 UTC 日期计算。原始来源 payload 的 date-only 字段仍优先作为来源业务日期使用；该行为属于导入器来源合同，不能在通用层重新附加固定地区时区。

这会改变历史代码中将完整时间解释为上海日的边界行为，但不会改变带 offset 时间代表的瞬时值。相关关系、财富和 Web 契约测试必须显式使用 UTC 或带 offset 样本，禁止通过系统默认时区让测试偶然通过。

### 5. 持久化与回滚

不新增迁移。`UTCDateTime` 继续作为 PostgreSQL/SQLite 共享 adapter；补齐所有绕过该 adapter 的解析和返回路径。若实现失败，回滚代码即可恢复旧的业务日期解释；数据库不需要回写，因为本变更不转换已保存瞬时值。

## Risks / Trade-offs

- [旧调用方未发送 `timezone`] → Web route 为直接服务调用使用 UTC 默认，前端始终发送浏览器时区，并为 cursor/响应增加回归测试。
- [DST 导致一天不是固定 24 小时] → 用 `ZoneInfo` 生成相邻本地午夜后再转换 UTC，不通过固定秒数计算结束边界。
- [历史测试依赖上海日桶] → 将测试输入改为 UTC 或显式 offset，并新增跨 offset 断言；若测试反映真实产品需求而非固定实现，先回写对应主规格再调整。
- [SQLite 驱动返回 naive datetime] → 统一由 `UTCDateTime.process_result_value` 补 UTC，并在 Web API 和双后端契约中断言 `+00:00`。
- [运行环境缺少 IANA 时区数据] → 浏览器展示使用平台 Intl 数据；后端非法/缺失 ZoneInfo 失败关闭，不隐式回退到本地时区。

## Migration Plan

1. 先添加会因旧固定时区行为失败的前后端与双后端契约测试。
2. 按任务替换非导入器固定时区、补齐 Web `timezone` 参数和 UTC 序列化。
3. 运行 SQLite/PostgreSQL 同一时间合同矩阵、Web Vitest、Python 回归和构建。
4. 发布前执行 `openspec validate --all --strict`、`openspec doctor`、`git diff --check`，并复核 `src/ft`（排除导入器）不再包含固定 `Asia/Shanghai`。

回滚时恢复代码和 Web 合同版本即可；不执行数据库数据回滚。
