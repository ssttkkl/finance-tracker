## ADDED Requirements

### Requirement: 双后端统一 UTC 时间往返

PostgreSQL 和 SQLite MUST 对非导入器时间输入执行相同的 aware/UTC 解析、保存、查询比较和序列化；数据库驱动差异不得引入 naive datetime 或服务器本地时区语义。

#### Scenario: 带 offset 的事实在两个后端等价

- **WHEN** 同一现金流水带 `+09:00` 的发生时间分别写入 PostgreSQL 和 SQLite
- **THEN** 两个后端 MUST 以同一 UTC 瞬时排序、筛选并输出带 offset 的时间字符串
