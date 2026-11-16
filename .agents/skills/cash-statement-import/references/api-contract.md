# 现金账单导入执行边界

当前入口是 Compose 客户端的本地生命周期，不存在供 Agent 上传整批 CSV／XLSX 并取得服务器计划的 HTTP 工作流。使用当前应用完成准备与核对，再由应用冻结和提交完整写集；没有可操作的客户端时报告执行环境缺失，不能照旧会话 API、逐行写入或手造写集替代。

| 阶段 | 当前能力 | 使用边界 |
|---|---|---|
| CSV／XLSX | 客户端本地读取、归一与计算 | 不上传，不用服务端回退；公式等输入限制沿当前客户端 |
| PDF | `POST /api/v1/cash-import/pdf/parse` | 单文件无状态解析，需联网，返回归一化文档 |
| 旧 XLS | `POST /api/v1/cash-import/xls/parse` | 单文件无状态解析，返回原始文档供客户端继续归一 |
| 统一核对 | 原加密批次、完整快照及共享计算 | 账户、行、关联和整笔编辑沿原有效选择，查看分页不缩小确认范围 |
| 在线确认 | `POST /api/v1/cash-import/write-set?workspace_id=<原工作区>` | `Idempotency-Key` 与完整 `CashWriteSetRequest` 由当前客户端生命周期生成和固定 |
| 原请求核实 | `GET /api/v1/cash-mutations/receipts/by-key?key=<原键>&workspace_id=<原工作区>` | 原凭据和原范围；未找到不等于未提交 |
| 已知收据 | `/cash-mutations/receipts/{receipt_id}` 及 `/rows`、`/relations` | 保持原归属；实际结果分页到完整，不补造缺失的历史数据 |

`CashWriteSetRequest` 包含 `base_ledger_seq`、`rules_version` 和 `changes`。服务端只校验并原子保存，不重新识别或配对；实际 ID、修订和服务端元数据由真实事务产生。新收据为 `operation_kind=cash_write_set`、`plan_id=null`，以 `write_set_result` 和实际版本表达结果。精确字段、身份、预算及错误见[接口与数据参考](../../../../docs/import/cash-import-redesign-contracts.md#3-前后端接口契约)和运行 OpenAPI。

旧 `/cash-import/sessions` 及 `/cash-import/plans` 的文件、预览、分页和确认方法全部退役。`POST /cash-mutations/resolve` 保留给维护 `mp_` 计划；旧导入 `ip_` 返回 `404 plan_not_found`，不能用于核实新写集。

独立账户命令及逐项币种补齐继续即时生效，沿各自幂等键、修订及真实资格，不属于导入事务。字段和补偿约束见[独立账户接口](../../../../docs/import/cash-import-redesign-contracts.md#36-独立账户接口与退出补偿)。断网时这些写入与导入确认都不允许执行。

结果未知保留原请求与键。仅 POST 明确基线拒绝且证明未提交才进入原批次刷新／重算；结论变化停止重核对，结论一致沿既有有界重派。成功后收尾失败保留成功统计并恢复原批次，不重新发送已成功写集。错误与恢复的完整规则见[导入流程](../../../../docs/import/cash-import-flow.md#recovery)。
