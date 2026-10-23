# 导入、关系与同步流程

本文提供流程概览。现金导入后端已完成重构，Compose Web、Android、iOS 的统一导入页和主页维护入口尚未接入；客户端状态见 [功能地图](feature-map.md)。业务规则见 [现金导入业务规则](cash-import-redesign.md)，字段、请求和响应见 [接口与数据契约](cash-import-redesign-contracts.md)，实施及验证证据见 [OpenSpec 变更](../openspec/changes/cash-import-lifecycle-redesign/tasks.md)。

## 现金账单导入后端

当前流程为 **解析会话 → 统一预览计划 → 一次确认 → 持久结果／后续维护**，以下路径均以 `/api/v1` 为前缀：

1. `POST /cash-import/sessions`：以 multipart 上传文件和元数据，逐文件识别、解析，仅暂存解析内容。需要密码时，通过 `POST /cash-import/sessions/{session_id}/files` 重传该文件及当次密码；原文件和密码不留作后续处理。
2. 从当前来源分组选择已生效账户。创建、修改账户使用独立账户 API，立即生效，不随导入确认提交或取消。缺少账户、币种或分摊的可辨认流水可保存为草稿，不阻塞其他流水。
3. `POST /cash-import/sessions/{session_id}/plans`：提交当前修订及完整的账户、流水和配对决定，生成预计流水列表、差异和按需详情。预览不写业务流水；选择变化后重新预览，旧计划失效。
4. `POST /cash-import/plans/{plan_id}/commit`：携带期望修订和 `Idempotency-Key`，一次提交计划中的全部流水与配对决定，不只提交当前分页。流水逐项隔离；必须一起生效的关系按完整提交组隔离。成功业务写入、余额、受影响投影和收据同事务提交，请求级失败仍完整回滚。
5. 从 `/cash-mutations/receipts/{receipt_id}` 及其 `rows/relations` 分页读取真实结果；结果未知时用 `/cash-mutations/resolve` 核实原请求。临时内容有效的失败项可通过 `/cash-import/receipts/{receipt_id}/retry-sessions` 派生新会话，不重跑已成功项。

完全重复、来源更新、草稿和跳过分别报告。正式流水固定金额、币种、时间、资金账户及组成项；可更新字段统一受业务约束，并保护人工备注、分类和明确清空值。来源对应按来源范围及可靠编号或已验证的出现次数处理，不以内容散列代替业务身份；微信默认使用一个稳定来源，多账号才显式区分。

旧 `scan/preview/commit` 批次路径、`import_token`、原文件会话和账户随导入创建的合同已退役。现有 Compose 仍有旧调用代码，不能按旧页面存在认定新流程已联通；新客户端是后续独立任务。

## 草稿与关联维护

已保存草稿及未确认关联从当前流水详情维护，无需原文件。`GET /cash-records/{transaction_id}` 读取当前字段、问题和可执行动作；`PATCH /cash-records/{transaction_id}/draft` 保存补充内容，仍保持草稿。正式更新、明确转正式、来源问题及完整关联决定通过 `/cash-mutations/plans` 预览后确认。

草稿不参与余额、收入、支出或月度金额汇总。未确认关系不合并正式流水；确认后才按既定镜像、退款、转账规则合并，原始金额不变，全额退款净额为 `0` 的结果仍可见。类型更新保留合法关系、解除失效关系，只维护受影响旧、新关系闭包。关系是流水详情内的维护内容，不恢复独立关系审查产品或已退休的直接写接口。

## 投资账单导入

投资账单使用独立的 [`import/investment-statement-import`](../openspec/specs/import/investment-statement-import/spec.md) 能力和证券事件模型。它与现金账单的解析结果、账户映射和提交语义分开处理，不进入现金账单批次。

## 连接器同步

连接器同步由受权限控制的后端操作触发。交易所和 Polymarket 凭据从后端受控配置读取，不交给客户端。同步将数据映射为正式投资事件；事件写入与增量游标更新保持同一事务语义。provider 范围和失败处理见 [`import/investment-connector-sync`](../openspec/specs/import/investment-connector-sync/spec.md)。

## 来源内容与运行配置

`source_payload` 保存单条业务行的原始字段和值，不以整份账单或来源路径代替；金额、数量与成本在账务边界使用精确十进制。现金账单、证券账单和连接器同步各自遵循所属领域合同，不将一类来源的兼容行为推及其他来源。

临时存储、TTL、清扫、权限和发布限制见 [现金导入临时存储配置](cash-import-r2-staging.md)。
