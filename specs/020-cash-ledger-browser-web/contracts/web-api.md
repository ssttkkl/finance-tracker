# 收支账本 Web API 合同

基础路径为 `/api/v1`。所有读取绑定 `FT_WORKSPACE_ID`，金额为十进制字符串，时间为带时区的 ISO 8601
字符串。成功响应不得包含原始现金流水字段。

## 端点

| 方法与路径 | 用途 |
|------------|------|
| `GET /accounts?view=cash` | 返回可用于收支投影筛选的非投资账户。 |
| `GET /cash-projections` | 返回当前活动版本中应展示的消费和收入投影。 |
| `GET /evidence/cash-projections/{projection_id}` | 返回一个投影条目的完整证据详情。 |

020 不提供原始现金流水读取端点或兼容别名。

## `GET /cash-projections`

### 查询参数

| 参数 | 约束 |
|------|------|
| `date_from` / `date_to` | 可选，`YYYY-MM-DD`；按主记录在 `Asia/Shanghai` 的自然日。 |
| `account_id` | 可选，正整数。 |
| `counterparty` | 可选，去除首尾空白后按项目既有包含匹配规则查询。 |
| `category` | 可选，主记录分类的精确值。 |
| `currency` | 可选，3 位币种码，统一为大写。 |
| `amount_min` / `amount_max` | 可选，精确十进制字符串，按投影净额筛选。 |
| `economic_type` | 可选：`expense` 或 `income`；省略表示两者。 |
| `composition` | 可选：`single`、`payment_mirror`、`refund_offset` 或 `combined`。 |
| `cursor` | 可选，只能继续相同工作区、完整筛选条件和投影版本。 |
| `limit` | 可选，默认 50，范围 1 至 50。 |

`amount_min` 大于 `amount_max`、日期倒置、未知枚举或非法数值均返回 `invalid_filter`。列表始终排除
`visible = false` 的内部转账、全额退款和余额校准投影。

### 成功响应

```json
{
  "projection_version": 42,
  "items": [
    {
      "projection_id": "cash:1001",
      "occurred_at": "2026-01-08T12:30:00+08:00",
      "account": {"id": 7, "name": "日常账户", "type": "cash", "active": true},
      "counterparty": "示例商户",
      "category": "餐饮",
      "note": "午餐",
      "amount": "-70.00",
      "currency": "CNY",
      "economic_type": "expense",
      "transfer_subtype": null,
      "composition": ["payment_mirror", "refund_offset"],
      "member_count": 3,
      "accepted_relation_summary": [
        {"kind": "payment_mirror", "subtype": "", "count": 1},
        {"kind": "refund_offset", "subtype": "", "count": 1}
      ],
      "source_type": "alipay",
      "record_id": "example-001"
    }
  ],
  "next_cursor": null,
  "page_size": 50,
  "filters": {
    "date_from": null,
    "date_to": null,
    "account_id": null,
    "counterparty": null,
    "category": null,
    "currency": null,
    "amount_min": null,
    "amount_max": null,
    "economic_type": null,
    "composition": null
  }
}
```

排序固定为 `occurred_at DESC, projection_id DESC`。无匹配项返回 `200`、当前
`projection_version` 和空 `items`。

## `GET /evidence/cash-projections/{projection_id}`

成功响应包含：

- `projection`：与列表一致的投影结果，并包含 `visible` 和 `hidden_reason`。
- `root_record`：主记录的规范化字段、导入渠道、业务行标识和白名单脱敏来源行快照。
- `members`：全部投影成员及其角色，按 `ordinal` 排序。
- `accepted_relations`：构建实际采用的关系、subtype、规则 ID、置信度和受控证据。
- `inactive_relation_hints`：成员当前关联的 `pending_review`、`rejected` 或 `superseded` 关系提示；只说明状态和端点，不改变结果。
- `refund_timeline`：退款成员的实际发生时间、金额、币种和来源；列表时间仍取主记录。
- `projection_version`：本次证据读取对应的活动版本。

投影版本、投影、成员、已采用关系和未生效关系提示必须来自同一数据库快照；重建并发发生时，响应只能完整
代表旧版本或新版本之一，绝不混合两个版本的数据。

来源证据沿用白名单脱敏合同，不返回姓名、账号、完整订单号、凭据、完整本地路径、SQL 或驱动文本。

证据详情允许按已知 `projection_id` 读取隐藏投影，但收支账本本身不提供内部转账或全额退款入口。

## 游标合同

游标负载包含版本号、工作区、完整筛选摘要、最后一项 `occurred_at` 和 `projection_id`；Base64 解码后的 JSON 顶层必须是对象。`v` 与 `version` 必须是非布尔整数，`workspace` 与 `projection_id` 必须是字符串，`occurred_at` 必须是带时区的 ISO 8601 字符串，`filters` 必须是与当前筛选摘要完全相同的对象。以下情况返回：

- 游标损坏、JSON 顶层非对象、缺少字段、字段类型非法、跨工作区或筛选不同：HTTP `400`，`invalid_cursor`。
- 游标格式正确但 `projection_version` 已不是活动版本：HTTP `409`，`projection.updated`。

`projection.updated` 的前端文案为“账本已更新，请刷新列表”，并保留当前筛选条件后重新读取第一页。

## 投影可用性

`active_dataset_id` 为空或状态不是 `ready` 时，列表和证据返回 HTTP `503`、
`projection.unavailable`。不得查询原始现金流水作为回退。已有活动数据集但最近一次全量重建失败时，
继续返回活动版本；失败只通过维护命令和安全日志诊断。

## 共同错误对象

```json
{
  "error": {
    "code": "projection.updated",
    "message": "账本已更新，请刷新列表。"
  }
}
```

允许的稳定错误码：

- `invalid_filter`、`invalid_cursor`、`not_found`、`evidence_unavailable`；
- `projection.updated`、`projection.unavailable`；
- `storage.config`、`storage.connect`、`storage.schema`、`storage.workspace`、
  `storage.readonly`、`storage.busy`。

参数类型或范围错误使用 `invalid_filter`，不得泄露 FastAPI 默认 `422` 合同。错误对象不得包含 SQL、
驱动文本、凭据、绝对路径或 traceback。

列表、账户目录和证据详情发生运行期数据库、连接或 SQLite 快照读取故障时，同样必须返回上述 `storage.*`
错误对象，不得返回未结构化 HTTP 500。

## 双后端等价性

PostgreSQL 与文件型 SQLite 的投影版本、条目、排序、游标、金额、时间、证据、组成摘要和错误码必须
一致。SQLite 忙碌可返回 `storage.busy`，但不得回退到 PostgreSQL、实时重算或改变账务结果。
