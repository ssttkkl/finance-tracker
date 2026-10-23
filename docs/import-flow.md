# 导入、关系与同步流程

本文提供流程概览。现金导入后端已完成重构，Compose Web、Android、iOS 的统一导入页和主页维护入口尚未接入；客户端状态见 [功能地图](feature-map.md)。业务规则见 [现金导入业务规则](cash-import-redesign.md)，字段、请求和响应见 [接口与数据契约](cash-import-redesign-contracts.md)，实施及验证证据见 [OpenSpec 变更](../openspec/changes/cash-import-lifecycle-redesign/tasks.md)。

## 现金账单导入后端

从 [现金账单导入流程](cash-import-flow.md) 开始阅读：它贯穿业务流程、后端执行、接口和数据衔接、一致性及失败恢复。详细规则、API／表结构和运行配置按该文末的参考导航查阅。

现金导入采用“解析会话 → 不可变计划 → 一次确认 → 持久收据／后续维护”。原文件和密码不留存，独立账户命令立即生效；草稿和未确认关系可延后处理。后端已实现，统一客户端入口尚待接入。

旧 `scan/preview/commit` 批次路径、`import_token`、原文件会话和账户随导入创建的合同已退役；现有旧页面代码不代表新流程已联通。

## 投资账单导入

投资账单使用独立的 [`import/investment-statement-import`](../openspec/specs/import/investment-statement-import/spec.md) 能力和证券事件模型。它与现金账单的解析结果、账户映射和提交语义分开处理，不进入现金账单批次。

## 连接器同步

连接器同步由受权限控制的后端操作触发。交易所和 Polymarket 凭据从后端受控配置读取，不交给客户端。同步将数据映射为正式投资事件；事件写入与增量游标更新保持同一事务语义。provider 范围和失败处理见 [`import/investment-connector-sync`](../openspec/specs/import/investment-connector-sync/spec.md)。

## 来源内容与运行配置

`source_payload` 保存单条业务行的原始字段和值，不以整份账单或来源路径代替；金额、数量与成本在账务边界使用精确十进制。现金账单、证券账单和连接器同步各自遵循所属领域合同，不将一类来源的兼容行为推及其他来源。

临时存储、TTL、清扫、权限和发布限制见 [现金导入临时存储配置](cash-import-r2-staging.md)。
