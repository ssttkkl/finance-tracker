# 设计：导入会话创建与文件上传分离

## 目标

把首次导入从“一次请求同时建会话+写文件”改为“先建空会话，再向已知会话上传”，让上传失败可查询、可续传，建会话失败最多留下可过期的空会话，不产生重复财务数据。

## 生命周期合同

### 创建空会话

`CashImportLifecycleRepository.createSession(): ImportContext?`

- 本地优先实现：生成新的 `batchId`，返回 `origin=LOCAL` 的空 `ImportContext`；不读取文件、不解析、不发布计划、不写业务账本。
- 演示实现：在内存 `contexts` 中插入空会话。
- 旧实现可返回 `null`，调用方回退到原 `upload(context=null)` 路径；生产 Local/Demo 已实现。
- 空会话只在内存/本地存在；不新增服务端路由。

### 上传到已知会话

`CashImportLifecycleRepository.uploadToSession(sessionId, files, replacingFileRef, onContext)`

- 默认实现读取 `context(sessionId)` 后复用 `upload(files, context, ...)`。
- 幂等键：`(workspace, session_id, client_file_ref)`；重复引用返回已有文件状态，不重复写。
- 会话必须属于当前用户/工作区、未过期、`state=open`；版本不匹配按既有会话修订规则拒绝。
- 部分成功：响应返回逐文件状态/失败项；客户端只重试失败或未完成的 refs。

### 查询会话

`CashImportLifecycleRepository.context(sessionId)`

- 本地实现返回已读取/待处理文件、修订和可预览状态；服务端实现（若存在）返回归属、revision、expires_at、文件列表。
- 不返回业务账本内容；只用于恢复上传/预览状态。

## 客户端流程

1. `createSession()`：成功保存 session_id；失败显示错误，允许用户重试。重试可新建会话，旧空会话过期。
2. `uploadToSession(sessionId, files)`：按文件引用上传；失败保留 session_id 与已完成文件。
3. 上传失败时显示“文件上传未完成，请重试/重新读取”，提供查询/重试；禁止自动新建会话。
4. 查询到全部文件 ready 后进入账户映射/预览；后续流程复用现有 context。
5. 删除 `uploadCreationUnknown` 的“不可恢复”语义；状态改为 `sessionCreateFailed` / `uploadIncomplete` 等可重试状态。
6. 本地优先路径（`LocalCashImportLifecycleRepository`）同样先创建本地空批次，再写入文件；本地批次 ID 作为 session_id，失败可按批次查询本地文件状态。

## 服务端与存储

- 会话表已有归属/TTL/修订；新增按 `(workspace_id, client_file_ref)` 查活动会话的索引，或在会话内文件表加唯一约束。
- 建会话只插入会话行；上传在会话锁内按 ref upsert/查重；并发重试由唯一约束/事务保证只有一个文件。
- 空会话 TTL：复用现有导入会话过期清理；需确认清理任务不会删除仍有文件的会话。
- 安全：所有查询/上传校验用户与工作区；session_id 不可跨用户枚举；错误不泄露其他会话内容。
- 兼容：旧的一体化首次上传入口在迁移期可保留；若保留，必须内部走“建会话+上传”或明确弃用，不得与新流程并存创建两个会话。

## 失败与恢复矩阵

| 失败点 | 服务端状态 | 客户端动作 |
|---|---|---|
| 建会话超时 | 可能有一个空会话 | 报错；用户重试新建；旧空会话过期 |
| 建会话 4xx/5xx | 无会话或未知 | 报错；按错误码提示；不自动重试 |
| 上传超时 | 会话已知，部分文件可能已写 | 查询会话；只补传未完成 refs |
| 上传 4xx | 会话已知，文件失败 | 显示逐文件错误；允许替换/重试 |
| 会话过期 | 不可上传 | 提示重新开始导入；不静默新建 |
| 跨工作区/用户 | 拒绝 | 不泄露；返回统一未找到/无权限 |

## 测试

- 建会话成功、响应丢失、重复建会话、空会话过期。
- 上传成功、响应丢失、部分成功、重复 client_file_ref、并发同 ref。
- 查询会话归属/文件状态；跨工作区/用户拒绝。
- 客户端状态机：建会话失败可重试、上传失败保留 session_id、不自动新建会话。
- 兼容：旧入口（若保留）不产生双会话；现有预览/确认/写集幂等回归。

## 非目标

- 不做文件内容哈希去重；`client_file_ref` 是本次选择的身份。
- 不做自动重试/自动对账；网络错误由用户显式重试。
- 不改变最终提交的幂等键、预览容量或业务账本写入合同。
