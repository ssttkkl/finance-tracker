# 现金账单多文件 API 合同

本文根据当前 Compose 客户端与后端实现整理。接口细节变更时，复核下列实现和主规格：

- `src/ft/web/routes.py`：HTTP 路由和请求解码。
- `src/ft/application/cash_import_session.py`：临时会话、预览绑定和确认幂等。
- `src/ft/application/cash_ledger.py`、`src/ft/application/statement_account_mapping.py`：识别、账户组、映射、预览和写入行为。
- `compose/shared/src/commonMain/kotlin/com/finance/tracker/data/CashImportApi.kt`、`FinanceApiClient.kt`、`CashImportDtos.kt`：Compose API 请求和字段。
- `compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportViewModel.kt`：Compose 阶段转换、映射和关系决策。
- `openspec/specs/import/statement-import/spec.md`、`openspec/specs/import/cash-import-session/spec.md`：账单导入及临时会话合同。

## 鉴权与文件限制

- 所有请求使用当前 Finance Tracker API origin 和 `Authorization: Bearer <token>`。工作区由令牌对应的活动会话确定，路径不含 workspace ID；写请求要求 `admin` 或 `editor` 角色。
- 批量扫描接受 1–20 个文件，每个文件最多 100 MiB；扩展名为 `.csv`、`.xls`、`.xlsx` 或 `.pdf`。服务端会为每个文件自动识别唯一现金账单渠道，批次可以混合渠道。
- 上传原始文件字节的 Base64 编码和原文件名。不要设置 `source` 覆盖自动识别结果。
- `X-FT-Statement-Passwords` 的值为 JSON 对象，键为从 `0` 开始的文件序号字符串，值为本次请求的文件密码。只在扫描/预览/确认阶段需要时传递；不可放入 URL、正文、日志或持久化存储。

## 1. 创建多文件会话并扫描

```http
POST /api/v1/cash-import/scan
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{
  "files": [
    {"filename": "bank.csv", "content_base64": "<原始字节的 Base64>"},
    {"filename": "wallet.pdf", "content_base64": "<原始字节的 Base64>"}
  ]
}
```

扫描响应包含：

- `import_token`：短期会话令牌，后续阶段必须沿用。
- `ready`：只有所有文件均成功识别和解析时为 `true`。
- `files[]`：按输入序号返回文件名、摘要、`channel`、`row_count`、`status` 和可能的 `error_code`。状态包括 `ready`、`password_required`、`error`。
- `groups[]`：来源账户组，含 `group_id`、`source_type`、`display_name`、`masked_evidence`、`currencies`、`row_count` 和 `suggestion`。
- `accounts[]`：当前工作区可选账户。

所有文件都必须 `ready`。密码待补时复用会话，且只对未解锁文件提供密码：

```http
POST /api/v1/cash-import/scan
X-FT-Statement-Passwords: {"1":"<用户提供的密码>"}
Content-Type: application/json
```

```json
{"import_token": "<scan.import_token>", "batch": true}
```

不要删除失败文件后偷偷提交剩余文件。若用户决定修改文件集合，应从新文件集合重新扫描，以保证整个批次的摘要一致。

## 2. 账户映射与预览

每个来源组在 `mapping` 中恰好出现一次。复用已有账户时提交其 ID 和扫描返回的映射修订号：

```json
{
  "import_token": "<scan.import_token>",
  "batch": true,
  "mapping": [
    {"group_id": "<scan.groups[0].group_id>", "account_id": 123, "mapping_revision": 4}
  ]
}
```

需要创建账户时，该组使用 `new_account`，币种取扫描结果：

```json
{
  "group_id": "<scan.groups[0].group_id>",
  "mapping_revision": null,
  "new_account": {
    "draft_id": "<同一目标账户可共享的草稿 ID>",
    "name": "账户名称",
    "type": "cash",
    "currencies": ["CNY"]
  }
}
```

预览请求：

```http
POST /api/v1/cash-import/preview
Content-Type: application/json
```

请求正文是包含 `import_token`、`batch: true` 和完整 `mapping` 的 JSON 对象；密码仍通过 `X-FT-Statement-Passwords` 传递。

预览响应包括 `batch_digest`、`channel`（混合渠道时为 `mixed`）、`files[]`、`items[]`、`summary`、规范化后的 `mapping[]`、`relations[]` 和 `relation_digest`。重点核对：

- `summary.total`、`new`、`existing`、`unsupported`、`unresolved`、`requires_allocation`。
- `items[].status`：`new`、`existing`、`unresolved`、`requires_allocation` 或 `unsupported`。
- `items[].components` 和 `component_allocation`：组成账户与金额状态。
- `relations[]`：`id`、`kind`、`subtype`、`rule_id`、`automatic`、`primary`、`secondary` 和 `candidates`。

需要分配金额时，在 `mapping` 的一个决定中追加 `component_allocations`，键为预览流水的 `relation_ref`（若无则用 `record_id`），值为十进制金额字符串数组，然后重新请求预览。例如：

```json
{
  "component_allocations": {
    "alipay:record-123": [{"amount": "10.25"}, {"amount": "20.00"}]
  }
}
```

预览后映射变化时必须再次预览。`commit` 的映射必须与保存预览时的映射逐字段一致。

## 3. 提交关系与最终确认

Compose 对 `automatic: true` 且有 `secondary` 的建议默认提交为 `accepted`。端点引用优先使用后端返回的 `fact_id`，否则用 `relation_ref`，最后才用 `record_id`：

```json
{
  "proposal_key": "<relation.id>",
  "kind": "<relation.kind>",
  "subtype": "<relation.subtype>",
  "rule_id": "<relation.rule_id>",
  "primary_record_ref": "<relation.primary.relation_ref>",
  "secondary_record_ref": "<relation.secondary.relation_ref>",
  "status": "accepted"
}
```

字段名应与端点标识匹配：`primary_fact_id` / `secondary_fact_id`、`primary_record_ref` / `secondary_record_ref` 或 `primary_record_id` / `secondary_record_id`。非自动且仍待处理的建议不提交关系决定；用户或 agent 有唯一证据接受的候选提交为 `accepted`。

```http
POST /api/v1/cash-import/commit
Authorization: Bearer <token>
Idempotency-Key: <本批唯一幂等键>
Content-Type: application/json
```

```json
{
  "import_token": "<scan.import_token>",
  "batch": true,
  "mapping": ["<与最后一次 preview 完全相同的映射>"],
  "preview_digest": "<preview.batch_digest>",
  "preview_relation_digest": "<preview.relation_digest>",
  "preview_channel": "<preview.channel>",
  "relations": ["<自动或唯一确认的关系决定>"]
}
```

同一会话的网络超时重试必须复用同一个 `Idempotency-Key` 和相同正文。确认成功后，服务端在一个数据库事务中写入新账户/币种、来源账户映射、有效新流水和关系，并清理临时会话文件；重复请求返回首次成功结果，不重复记账。

## 错误处理要点

响应错误码位于 `error.code`；如响应同时有 `import_token`，按密码错误或会话状态继续同一批次。

- `import_password_required` / `import_password_invalid`：请用户提供或更正密码，再用同一个会话重扫。
- `import_files_not_ready`、文件级 `error`：整批不可预览或提交。
- `import_mapping_incomplete` / `import_mapping_stale`：重新完成映射并预览。
- `import_component_allocation_incomplete` / `import_composite_payment_unresolved`：返回分配阶段；不能猜金额或账户。
- `import_preview_stale`、`import_relation_preview_stale`、`import_relation_reconfirmation_required`、`import_relation_candidate_invalid`：当前预览或关系图已变化，重新预览和核对，不得沿用旧决定。
- `import_session_expired`、`import_session_source_changed`、`import_session_storage_*`：停止并说明原因；不得将文件落到本地或改用其他导入路径。
- 401 / 403：认证、工作区或写入权限无效，停止操作。
