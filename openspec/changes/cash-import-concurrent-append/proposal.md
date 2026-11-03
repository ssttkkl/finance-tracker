## Why

现金导入的文件选择步骤中，只要有一个上传请求在途，`canEdit` 就为假，“添加文件”按钮被禁用；用户必须等整批上传结束后才能继续选择文件。后端暂存层已经具备 CAS 写入能力，但 `add_files` 要求 `expected_session_revision` 精确等于当前修订，导致并发追加互相冲突。用户明确要求：上传过程中可以继续点击追加，服务端支持并发合并。

## What Changes

- 服务端 `add_files` 改为合并语义：接受不领先于当前会话的 `expected_session_revision`，在最新 open 会话上合并新增文件，并在 CAS 冲突时自动重读重试；未来修订或已关闭/已提交会话仍拒绝。
- 并发 add 之间自动合并；`remove_file`、`replace_file`、预览和确认继续严格校验 revision；取消保留既有合同。
- 重复 `client_file_ref` 且内容摘要相同视为幂等成功；摘要不同返回 `import_files_invalid`；合并后总数仍不超过 20。
- 客户端共享 Compose 改为按文件排队上传，最多 3 个并发请求；首个文件串行创建会话，其余可并行；单文件失败只影响该文件；状态按最高 revision 合并。
- 文件选择步骤在上传或其他第一步请求进行中仍允许打开文件选择并追加；按钮上限按“已上传 + 待上传”合计计算。
- 单文件上传失败显示“重试”及通用删除按钮；重试复用当前保留的文件，删除后移除该失败项。存在失败或需密码文件时，文件步骤的“下一步”呈禁用样式，点击通过 Snackbar 提示先重试或删除失败文件，或输入账单密码，不进入下一步。
- 不新增协议字段，不改变账本事实、金额、币种或数据库 schema。

## Capabilities

### New Capabilities
- 无。

### Modified Capabilities
- `import/cash-import-session`: 新增并发文件追加的合并、幂等、上限与会话状态拒绝语义；明确 add 的 revision 宽容范围和其他文件操作仍严格。
- `import/statement-import`: 文件选择步骤在上传中仍可追加；并行上传的失败隔离、3 并发上限和最高 revision 合并规则。

## Impact

- 后端：`src/ft/application/cash_import_context.py`（`add_files` 合并与重试）、`src/ft/application/parsed_import_staging.py`（复用现有 CAS，不新增字段）。
- 客户端：`CashImportTaskViewModel`、`ImportTaskUiState`、`CashImportTaskScreen`、`CashImportPreparation` 与共享 ViewModel 测试。
- API：`POST /cash-import/sessions/{id}/files` 的 `expected_session_revision` 对 add 变为“不得领先当前修订”的下界；响应结构与错误码不变。
- 存储：SQLite 与 PostgreSQL 均复用现有事务与暂存 CAS；需运行双后端合同矩阵。
- 迁移与回滚：无数据库迁移。回滚时恢复 `add_files` 的严格 revision 校验和客户端串行上传即可；已合并的临时会话随 TTL 过期，不影响账本事实。
