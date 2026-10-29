# 现金账单解析会话与统一确认 API

本参考对应当前后端入口，前缀均为 `/api/v1`。Compose 迁移完成情况以 [功能地图](../../../../docs/feature-map.md) 为准，旧客户端结构不是新接口合同。实施与验收记录见 [归档变更](../../../../openspec/changes/archive/2026-10-28-cash-import-lifecycle-redesign/design.md)；同步后的主规格位于 `import/cash-import-session` 与 `import/statement-import`。

实现依据：

- `src/ft/web/cash_import_routes.py`、`cash_import_protocol.py`：上传、文件修订、资源限制及安全会话响应。
- `src/ft/web/cash_plan_routes.py`、`cash_plan_protocol.py`：计划、一次确认、分页结果和核实。
- `src/ft/application/cash_import_context.py`、`cash_import_plans.py`、`cash_import_commit.py`：解析内容、不可变计划、原子提交及原请求核实。
- `src/ft/web/account_routes.py`：独立账户命令。

## 通用约束

认证使用 `Authorization: Bearer <token>`，工作区取自登录上下文；不得通过正文指定工作区。新写命令的 `Idempotency-Key` 必须为 1–255 个 `[A-Za-z0-9_:.~-]` 字符。一个逻辑请求固定一键，网络重试不得换键或改正文；不同账户命令与最终导入各有自己的键。

对象 ID 均视为不透明字符串，原样使用响应值；修订为正整数。金额用精确十进制字符串，未知值为 `null`。请求不接受未知字段；成功响应只读取公开字段，不提交来源内部操作图、原摘要、关系边或消费证明。

## 1. 上传、恢复与文件修改

| 方法和路径 | 输入 | 结果 |
|---|---|---|
| `POST /cash-import/sessions` | multipart | `201 SessionView` |
| `GET /cash-import/sessions/{session_id}` | 无 | 当前 `SessionView` |
| `POST /cash-import/sessions/{session_id}/files` | multipart，含当前修订；重传时含 `file_ref` | 更新会话，不重读其他文件 |
| `DELETE /cash-import/sessions/{session_id}/files/{file_ref}` | `If-Match: <session_revision>` | 移除文件解析内容，旧计划失效 |
| `DELETE /cash-import/sessions/{session_id}` | 无 | 取消未提交任务；不撤销已保存账户或导入 |

multipart 有且只有一个文本部分 `metadata`，值为 JSON；每份文件对应一个名为 `file:<client_file_ref>` 的文件部分。`client_file_ref` 为客户端本次指定的 1–128 个字母、数字、下划线或连字符，不同文件不能重复。最多 20 份，每份 100 MiB，元数据最多 64 KiB，总上传上限为 20 × 100 MiB 加 1 MiB；文件字段数量必须与元数据一致。

首次上传的元数据示例（另附 `file:f1` 的原始文件字节）：

```json
{"files": [{"client_file_ref": "f1"}]}
```

每个文件元数据还可含当次 `password` 和可选已知 `source_hint`；普通导入省略 `source_hint` 并自动识别来源。没有设置微信来源名称的字段或文件更新命令。微信每条交易必须有非空交易单号，并使用固定范围跨账号匹配。密码最多 1,024 字符，只用于当次解析，不放入请求头、URL 或持久缓存。无密码时省略。用户提供密码后重传单份文件：

```json
{
  "expected_session_revision": 2,
  "file_ref": "f_server_file_ref",
  "files": [{"client_file_ref": "f1", "password": "synthetic-example-only"}]
}
```

重传附且仅附 `file:f1`，使用原文件字节。添加文件则省略 `file_ref`，可附多份新文件。成功后从响应取得最新修订。文件名明确以“密码”“password”或“pwd”等标记提供的值，可作为该文件当次上传的 `password`；不得从其他文件名信息推测密码，也不得从文件名猜来源身份。密码仅用于本次请求，不记录或持久化。

`SessionView` 含 `session_id`、`session_revision`、`expires_at`、`state`（`open/committed/cancelled`）、`files[]`、`source_accounts[]`、`last_preview_input`、`last_plan_id`、`can_preview`、`receipt_id`。会话只保存限时解析内容，不提供原文件下载。

- 文件项含 `file_ref`、`client_file_ref`、安全展示名、`source_type`、`status`（`ready/partial/password_required/failed`）、`recognized_row_count`、安全 `issues[]`、`can_retry`。
- 来源账户项含 `source_account_ref`、渠道、展示名、`currencies`、`row_count`、`suggestion` 和 `selection`；微信和支付宝账号组含只在本次会话使用的 `session_group_ref`；其 `session_label` 显示微信昵称、脱敏支付宝账号或文件名。身份不可用时按文件分组，这些字段不作为长期身份。建议含 `account_id`、`expected_account_revision`、`basis`（`confirmed_mapping/legacy_mapping/account_alias/none`）；微信建议固定为空并使用 `none`。不使用已移除的 `groups`、`masked_evidence` 或 `mapping_revision` 字段。
- 单份文件错误不阻塞其他文件；使用 `can_preview` 判断下一步，不能要求所有文件就绪。已消费会话按持久结果恢复，不因临时清理丢失已知成功。

## 2. 独立账户操作

| 方法和路径 | 输入 | 语义 |
|---|---|---|
| `GET /accounts?view=cash` | 无 | 现金类账户列表，包含停用账户 |
| `GET /accounts/by-id/{account_id}` | 无 | 当前账户、`account_revision` 和能力 |
| `POST /accounts` | `name`、`type`、`currencies`；独立幂等键 | 立即创建，返回稳定 `account_id` 及修订 |
| `PATCH /accounts/by-id/{account_id}` | `expected_revision`、`patch`；独立幂等键 | 修改名称、启用状态或支持币种 |
| `DELETE /accounts/by-id/{account_id}` | `expected_revision`；独立幂等键 | 仅能删除已停用、未被使用的账户 |

列表返回 `id/name/type/active/currencies`，不含 `account_id/account_revision`；只将 `id` 转成不透明字符串，再从 by-id 响应取得 `account_id`、当前修订及配置，不编造修订或默认所有列表项可选。现金导入使用 `cash/loan/lend` 的适用账户，由当前后端资格检查决定；投资类型不能凭名称作为资金账户。已生效账户才可进入预览选择。创建或修改不会因导入取消自动回滚。

只在用户已授权时创建或扩充币种。普通账户更新字段为 `name`、`active`、`currencies`，账户类型不能通过 patch 修改。扩充币种提交旧集合与新增值的并集。需要撤销时，使用本任务内存中的原值和最新成功修订按逆序补偿；冲突、引用或原值丢失时不能强行还原。`account_result_unknown` 先用相同请求和幂等键恢复已知结果。

## 3. 统一预览

```http
POST /api/v1/cash-import/sessions/{session_id}/plans
```

```json
{
  "expected_session_revision": 1,
  "previous_plan_id": null,
  "account_selections": [
    {"source_account_ref": "source-account-1", "account_id": "101", "expected_account_revision": 1}
  ],
  "row_choices": [],
  "relation_choices": []
}
```

上述五个基础字段均必填；整笔编辑另传 `entry_choices`。选择数组是完整替换输入，最多各 100,000 项。恢复任务先保留当前 `selection/last_preview_input` 的有效决定，只给尚未选择项补建议；空数组不是增量“保持不变”。文件或来源变化后按服务端失效结果重核，不能将旧行位置的选择移到新文件。账户未选择可省略该项，或传 `account_id: null` 且修订为 `null`；不要求全部账户完成。引用真实账户时必须带其当前修订。

行选择为 `{"row_ref": "f1:r1", "action": "apply", "patch": {...}}`，动作只允许 `apply/keep_existing/defer`。仅按服务端详情的 `editable_fields` 和 `allowed_actions` 填写；未知或非法行字段使本次预览请求被拒绝，修正输入后重试。组成项金额必须精确等于总额；导入正式金额、币种、发生时间、资金账户、组成项、类型／子类型和来源身份固定，不能借导入修改。用户只编辑收支公共属性：交易对方、对方账号和属性、备注、分类；来源行只由来源更新刷新。公共人工值和明确清空受保护。来源成员只读，计划详情 `entry.fields/editable_fields/edit_ref` 提供预计公共值及明确编辑目标。整笔公共补丁放入完整替换数组 `entry_choices`，格式为 `{"edit_ref":"ie_current","patch":{"note":"","category_id":null}}`；未编辑时可省略或传空数组。不挑首个来源行、不改变其来源决定。文件变化清除计划时清除旧整笔选择，目标失效须重新核对，不能迁移补丁。实际人工冲突按服务端 `common_field_conflicts` 保留待确认，只有用户明确选择后才提交 `common_fields`，不能替用户猜值。

关联选择为 `{"suggestion_id": "sg_example", "action": "defer", "candidate_id": null}`，动作只允许 `accept/defer/ignore`。首次可留空使用后端默认；调整时从上一计划的单项详情取得完整建议和候选，禁止自造关系或拆开联合决定组。`defer` 是待确认，`ignore` 是明确忽略，不能混用。选项变化后带最新会话修订及 `previous_plan_id` 重预览，成功得到新计划和递增修订，旧计划不可执行。

响应 `ImportPlanView` 含 `plan_id`、会话及最新修订、到期时间、`summary`、`item_counts`、`can_commit`、第一页 `items`、`file_results`、`pages` 和 `invalidated_choices`。

每个预计流水项含 `preview_item_id`、`confirmation_status`（`confirmed/pending`）、`anticipated_state`（`posted/draft`）、`display`、`statistics_eligible`、`member_refs`、`attention_reasons` 和 `detail_available`。展示字段为时间、精确金额、币种、对方、备注和账户列表，未知值可为 `null`。已确认关系才合并，待确认正式流水分别统计；草稿不参与统计。全额退款净额 0 继续展示。

| 读取路径 | 用途 |
|---|---|
| `GET /cash-import/plans/{plan_id}` | 当前计划及第一页 |
| `GET /cash-import/plans/{plan_id}/items` | `limit`（默认 50，最多 200）、`cursor`、`confirmation_status=all/confirmed/pending` |
| `GET /cash-import/plans/{plan_id}/items/{preview_item_id}` | 按需查看当前成员、只读 `relation_members`、`field_diffs`、事项、关联选择及既有关系变化 |
| `GET /cash-import/plans/{plan_id}/processing-details` | 按需查看重复、跳过和配对失败原因；`limit/cursor` 分页 |

分页响应使用 `next_cursor`；游标绑定计划、类型和筛选，不跨计划复用。单项失败不要求逐条人工确认才继续；以 `can_commit` 判断最终动作是否可用。预览摘要计数描述预计处理，不代替实际收据。

## 4. 一次确认与未知结果恢复

```http
POST /api/v1/cash-import/plans/{plan_id}/commit
Idempotency-Key: synthetic-confirm-example
```

```json
{"expected_session_revision": 2}
```

整笔公共编辑的目标依赖失败或最终完整成员不符时整次命令拒绝并回滚，不发布成功收据；任一提交新增异常都使整次业务写入回滚。已权威排除原消费且提交尚未开始的拒绝返回 `commit_state=not_committed`；未取得该证明不推断未提交。

`200` 返回持久收据，预览已知跳过或没有新增仍是确定结果；提交新增异常不返回成功收据。历史部分完成收据保持可读。收据含 `receipt_id`、`plan_id`、`operation_kind`、`committed: true`、时间、`status`（`completed/partial/no_changes/failed`）、`summary`、待确认计数。内部上下文 ID、请求摘要及完整结果数组不公开。

当前导入摘要包括 `created_posted`、`updated_posted`、`saved_draft`、`finalized_draft`、可选的 `updated_entries`、`duplicate`、`skipped`，以及 `relations_confirmed/deferred/ignored/failed`。新增／更新按实际保存操作计数，重复和跳过按来源出现计数，不能把全部数字相加当作新增流水。`needs_attention_count` 按有当前问题的唯一流水计数；`pending_item_count` 按最终可见的待确认列表项计数，均不混作失败数量。

| 结果读取 | 说明 |
|---|---|
| `GET /cash-mutations/receipts/{receipt_id}` | 原操作者的安全摘要 |
| `GET /cash-mutations/receipts/by-key?key=...` | 原键精确查询，必须正确 URL 编码；路径式 `by-key/{key}` 仍可用 |
| `GET /cash-mutations/receipts/{receipt_id}/rows` | 行结果；`after` 从 0 开始，`limit` 默认 50、最多 200 |
| `GET /cash-mutations/receipts/{receipt_id}/relations` | 关联结果，同上 |

结果分页使用 `next_after`；返回当前 `projection_refs` 和 `projection_version` 便于定位，永久结果不保证旧展示 ID 永远有效。结果 `code/message` 是安全原因；预览已知的未确认关系不阻止合法流水保存；执行异常使本次全部回滚。文件级原因从本次会话及计划的 `file_results` 核对，不调用不存在的收据 `/files` 接口；只剩永久摘要时不猜测文件名或逐文件结果。

超时或 `commit_state=unknown` 时保留原键、计划和正文。调用：

```http
POST /api/v1/cash-mutations/resolve
```

```json
{"plan_id": "ip_from_latest_response", "idempotency_key": "synthetic-confirm-example"}
```

- `committed`：响应含原收据，按其报告。
- `pending`：原操作仍可能在进行，继续核实。
- `retry_original`：只重试原计划、原正文和原键。
- `not_committed`：服务端在同步边界确认未提交，按当前会话重新准备。

收据 404、会话过期和暂存丢失都不能单独判定未提交。未知期间不换键重做、不取消任务、不补偿账户。明确 `commit_state=not_committed` 与未知不同；同键重放始终返回首次处理结果，不重新执行失败项。

旧导入结果以 `operation_kind=legacy_import` 保存真实旧摘要及可选 `legacy_by_account`，不推断缺失状态、正式／草稿数或逐行结果。旧键读取兼容不放宽新键规则；历史收据仅供原归属读取，不开放失败子集派生。

## 5. 错误与重新处理

失败子集派生接口及 `retry_context_*` 能力已移除；确定未保存时重新核对原会话或重选账单，结果未知时仍核实原命令。普通失败文件补密码／重传不受影响。

安全错误结构为 `error.code/message/field_errors/retryable`。常见处理：401／403 停止未授权写入；422 修正请求；409 的 `session_changed/plan_changed` 刷新重预览，`session_committed` 读取已有结果；410 按具体原因重新提供文件或打开流水；503 按 `commit_state` 区分明确未保存与结果未知。确认前可定位的文件／来源业务问题可单项展示；非法输入、内部程序错误和提交新增异常必须拒绝，不伪装成普通跳过。密码或解析问题优先采用文件级安全 `issues`。

旧 `cash-import/detect/scan/preview/commit`、原 `import_token`、Base64 JSON 上传、密码请求头、账户 `draft_id/new_account`、客户端关系边及摘要确认字段均已退出合同。
