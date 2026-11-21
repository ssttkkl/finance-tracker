## Why

当前首次上传在 `context == null` 时用一次请求同时创建导入会话并写入文件。响应丢失后，客户端既不知道会话是否已创建，也不知道文件是否已写入；重试可能建出第二个会话，最终把同一批流水导入两次。现有 `uploadCreationUnknown` 还会关闭加文件能力，却没有恢复入口，用户会被卡在导入页。

把“建空会话”和“上传文件”拆开后，危险窗口从“会话未知且文件已写”降级为“只可能留下空的孤儿会话”：空会话可过期清理，不产生财务数据；上传失败时客户端已知会话 ID，可以查询后按文件引用重试。

## What Changes

- **BREAKING（内部 API）**：新增 `POST /api/v1/cash-import/sessions`，只创建一个空导入会话，返回 `session_id`、`revision`、`expires_at`；创建时不接收文件、不写业务账本。
- 新增 `POST /api/v1/cash-import/sessions/{session_id}/files`，在已知会话内上传文件；以 `(workspace, session_id, client_file_ref)` 幂等，重复引用返回已有文件状态而不是重复写入。
- 新增 `GET /api/v1/cash-import/sessions/{session_id}`，返回会话归属、修订、过期时间和文件状态，供上传失败后查询/恢复。
- 首次导入客户端流程改为“先建空会话，再上传文件”；建会话失败直接报错让用户重试，允许留下空会话并按 TTL 清理；上传失败保留会话 ID，提供重新读取/重试上传，不再进入 `uploadCreationUnknown` 卡死状态。
- 网络错误不自动重试，由用户显式重试；上传重试复用同一批 `clientFileRef`。
- 空会话必须按工作区/用户隔离，并设置 TTL 或等效清理；已过期会话不可再上传。
- 现有预览、账户映射、最终确认、写集幂等和提交结果核实规则不变；建会话和上传都不得写业务账本。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `import/cash-import-session`: 增加“会话创建与文件上传分离、上传按文件引用幂等、会话可查询、空会话有 TTL”的行为要求；现有预览、容量、账户和提交要求保持不变。

## Impact

- **后端**：新增会话创建/文件上传/会话查询路由；导入会话存储增加 TTL、归属校验、按 `client_file_ref` 查询与唯一性/并发保护；现有 multipart 上传解析和预览/提交服务复用。
- **客户端**：`CashImportLifecycleRepository`/本地与服务端实现增加 `createSession`、`uploadToSession`、`getSession`；`CashImportTaskViewModel`/`ImportTaskUiState` 移除 `uploadCreationUnknown` 卡死状态，改为“建会话失败可重试、上传失败可刷新/重试”；导入页增加对应状态与动作。
- **接口/兼容**：旧的一体化首次上传入口在迁移期可保留或由新流程替代；若保留，必须标注弃用并不得与新流程同时创建会话。需要同步 API 客户端与测试夹具。
- **数据/财务**：不改变最终写集、幂等键、预览/确认合同；不新增业务账本写入。空会话只占临时存储，按 TTL 清理。
- **验证**：新增建会话超时、上传超时、部分上传、重复文件引用、并发重试、空会话过期、跨工作区访问等契约/集成测试；Web 导入主流程冒烟覆盖“建会话失败重试”和“上传失败后查询恢复”。
- **非目标**：不做自动重试/自动对账；不做文件内容哈希去重；不改工作区创建、邀请、成员变更；不改变最终提交的现有幂等语义。
