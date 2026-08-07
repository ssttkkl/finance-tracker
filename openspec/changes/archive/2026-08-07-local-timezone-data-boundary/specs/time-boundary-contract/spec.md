## Purpose

为账本、关系和财富计算定义稳定的时间边界，使来源时间、后端瞬时值和浏览器本地展示各自承担明确职责，避免部署环境或固定地区时区改变财务结果。

## ADDED Requirements

### Requirement: 后端时间数据必须明确且可比较

系统 MUST 在非导入器边界将时间解析为 UTC 或保留原始 offset 的 timezone-aware datetime；不得以运行环境默认时区解释无 offset 的后端输入，持久化和 API 输出的时间必须携带明确 offset。

#### Scenario: 无 offset 的手工时间按 UTC 处理

- **WHEN** 非导入器调用方提交不带 offset 的时间字符串
- **THEN** 系统 MUST 按 UTC 解释或拒绝该输入，不得按服务器本地时区或固定地区时区解释

#### Scenario: SQLite 与 PostgreSQL 往返时间一致

- **WHEN** 同一带 offset 的时间写入 SQLite 和 PostgreSQL 后再读取
- **THEN** 两个后端 MUST 返回代表同一瞬时的 UTC aware 时间，且 JSON 序列化结果必须携带 offset

### Requirement: 前端展示必须使用浏览器本地时区

系统 MUST 在用户可见的发生时间和月份归属展示中使用浏览器运行环境的本地时区，不得固定使用 `Asia/Shanghai` 或其他地区时区。

#### Scenario: 不同浏览器时区展示同一瞬时

- **WHEN** 两个浏览器以不同本地时区打开包含同一 UTC 时间的账本
- **THEN** 每个浏览器 MUST 按自身本地时区展示日期、时间和月份，且不得改变后端保存的瞬时值

### Requirement: 日期筛选必须绑定浏览器时区

系统 MUST 接收前端传递的有效 IANA 时区，将日期输入解释为该时区的自然日，再转换为 UTC aware 的半开区间；筛选条件、月度汇总和 cursor MUST 绑定该时区。

#### Scenario: 本地日跨越 UTC 日期边界

- **WHEN** 使用者在 UTC+09:00 浏览器筛选某一自然日，且该日的开始或结束跨越 UTC 日期
- **THEN** 后端 MUST 按该浏览器时区的 `[00:00, 次日 00:00)` 转换后的 UTC 边界筛选，并返回与该时区月份一致的汇总

#### Scenario: 非法浏览器时区被拒绝

- **WHEN** Web 请求携带不存在或格式非法的 IANA 时区
- **THEN** API MUST 返回稳定的 `invalid_filter`，且不得使用服务器时区回退执行查询

### Requirement: 导入器来源时间解释保持隔离

系统 MUST 保留导入器按各来源合同解释无 offset 原始账单时间的行为；导入完成后形成的正式时间 MUST 进入后端明确时区的数据边界，其他模块不得复制导入器的固定来源时区常量。

#### Scenario: 导入来源日期不改变来源语义

- **WHEN** 导入器处理无 offset 的来源账单日期
- **THEN** 导入器 MUST 继续按该来源既有合同生成正式时间，非导入器模块只消费其带时区的结果
