## Context

当前 `CashImportContextService.add_files` 在解析前后各调用一次 `_current`，要求 `expected_session_revision` 与当前修订完全相等；暂存层 `replace` 再做一次修订校验，并以 R2 `If-Match` ETag 或内存哈希做 CAS。客户端 `CashImportTaskViewModel.upload` 通过单一 `activeRequest` 串行执行请求，`canEdit` 在 busy 时为假，因此上传中无法追加。参见 `proposal.md` 的动机。

## Goals / Non-Goals

**Goals:**

- 并发追加在服务端合并，不把 add-add 之间的暂存 CAS 冲突暴露成 `session_changed`。
- 客户端在上传中继续接受文件，按文件排队、最多 3 个并发，状态按最高 revision 收敛。
- 保持现有持久化、协议字段、账本事实和错误码结构不变。

**Non-Goals:**

- 不改变 remove、replace、preview、confirm 的严格修订校验及既有取消合同。
- 不新增客户端上传序号或协议字段。
- 不实现多用户同时编辑同一会话的通用冲突合并。

## Decisions

### 1. 服务端在短事务内读最新并合并，CAS 冲突有界重试

`add_files` 仍先调用 `_uploads` 校验并把解析放在事务外。之后进入有界循环（建议 5 次）：

1. 打开 UoW，`_authorize` 获取写权限；读取提交收据与最新会话；拒绝已关闭、已提交、已过期会话。
2. 校验 `expected_session_revision` 不领先当前修订；未来值仍返回修订冲突。
3. 按 `client_file_ref` 合并：不存在则加入；存在且摘要相同则幂等跳过；存在但摘要不同则 `import_files_invalid`。
4. 校验合并后总数不超过 20。
5. `_staging.replace` 以当前修订和当前 ETag 写入；捕获 `ImportSessionRevisionConflict` 后回到循环重读，超过上限再返回修订冲突。
6. 成功则提交并返回合并后的会话。

替代方案是让客户端遇 409 重读重试，但那仍是串行或易失败的客户端逻辑，不能实现用户要求的服务端并发合并。

### 2. add 的修订下界语义

`expected_session_revision` 对 add 只要求“不领先当前修订”。这样并发请求可以携带同一基础修订而不互相失败。未来值说明客户端状态异常，继续拒绝；会话已关闭或已提交则按现有错误拒绝。其他文件操作继续保持严格相等校验，避免把合并语义扩散到移除、替换、预览和确认；取消继续读取当前会话后按内部 CAS 更新。

### 3. 幂等键为 `client_file_ref` 与内容摘要

同一 `client_file_ref` 且摘要相同表示同一文件的重试或并发重复，直接返回当前会话。摘要不同说明客户端复用了引用但内容变化，必须拒绝，避免静默替换。解析在循环外只做一次，摘要不会在重试间变化。当前客户端在新选择时会生成新的 `client_file_ref`，因此丢失响应后的重新选择不保证命中幂等；该规则主要服务 HTTP/Agent 调用方，客户端以刷新后的会话状态为准。

### 4. 事务边界与双后端

- 解析（PDF/CSV/XLS、行预算）在事务外完成，避免长时间持锁。
- 合并与 CAS 写入在短 UoW 内完成；PostgreSQL 的成员写锁使同工作区写入串行，跨实例或对象存储竞争由 CAS 重试覆盖；SQLite 以事务串行化，仍保留 CAS 重试。
- 暂存层不新增字段或迁移；R2 与内存存储的 ETag/哈希校验直接复用。
- 合同测试需在 SQLite 与真实 PostgreSQL 上运行同一并发追加场景。

### 5. 客户端按文件队列与 3 并发

ViewModel 新增上传队列与并发计数：

- `upload(files, replacingFileRef)` 对普通追加不再要求 `canEdit`；替换（带 `replacingFileRef`）继续走原串行路径。
- 已有未保存选择先沿用原 `flushChoices` 预览保存；完成后回到文件步骤再启动 worker，保存失败则不发送队列。
- 队列按文件出队，最多 3 个 worker 并行调用 `repository.upload(listOf(file), latestContext, null)`。
- 首个文件在无会话时串行执行以创建会话；成功后其余 worker 再开始。
- 每个 worker 读取最新已知会话作为请求上下文；服务端接受不领先的修订。
- 成功响应通过新增的“按最高修订合并”方法写入状态；会话标识不同或修订更低的响应丢弃。
- 单个文件失败保留文件名与安全原因并继续队列；首文件已知未发送的本地失败让下一文件创建会话，创建结果未知继续阻断。存在中断或需要重新读取时，在队列排空后刷新会话；正常成功响应直接使用最高修订的完整会话，已确认成功的未知文件不再显示失败。
- `detach()` 清空队列并使在途 worker 失效，避免旧任务回写。

替代方案是继续使用单一 `activeRequest`，但那会把并发上传都挡在 `beginRequest` 之外，无法满足追加要求。

### 6. busy 与添加按钮解耦

`ImportTaskUiState` 增加上传并发计数，`busy` 为 `activeRequest != null || activeUploads > 0` 的并集；`finishRequest` 只清除请求部分。新增 `canAddFiles`，只在恢复完成、无提交、无冻结命令、无收据且会话 open 时为真，不受 `busy` 影响。文件选择页的“添加文件”按钮使用 `canAddFiles && canWrite`，上限按 `context.files` 与 `pendingFiles` 的 `clientFileRef` 去重合计 20。

### 7. 待上传项保留

屏幕层保留本地 `files` 映射时把 `pendingFiles` 的 `clientFileRef` 一并纳入，避免上传排队期间源文件被清理，导致失败重试或并发 worker 丢失内容。

### 8. 失败文件的操作

本地或网络失败行沿用现有文件行的 Material 3 `TextButton` 重试与通用删除按钮。重试复用保留的原文件及 `client_file_ref`，上传开始时移除旧失败原因并显示进度；服务端已存在该引用时使用既有替换流程。删除已存在的文件调用服务端删除；仅在本地的失败项直接清除。创建结果未知时保留现有重复创建保护。

文件步骤存在失败或需密码文件时禁止进入账户步骤；部分可用文件仍按既有合同处理。按钮采用 Material 3 禁用配色，但保留点击和键盘操作以显示 Snackbar：需密码时提示“请输入账单密码”，否则提示“请先重试或删除失败文件”；两者并存时优先提示密码。忙碌、权限不足等原有禁用条件仍阻止点击。此限制属于客户端流程，服务端继续隔离单文件错误并保留成功结果。重试成功或删除失败文件后重新计算下一步资格。

## Risks / Trade-offs

- **放宽 add 修订** → 只影响追加；会话仍必须 open、未提交，总数仍限 20；个人单用户场景可接受。其他操作保持严格，避免扩大语义。
- **并发响应乱序** → 只接受同会话且更高 revision 的结果；较低修订丢弃。
- **多实例 CAS 竞争** → 有界重试并最终返回修订冲突；不会部分合并。
- **上传中退出或切换任务** → `detach()` 清空队列并失效 worker；不得回写旧状态。
- **测试覆盖不足** → 后端并发请求测试需使用真实并发（线程/异步）而非顺序调用；SQLite 与 PostgreSQL 各跑一次。
- **UI 状态复杂度** → 新增上传计数与队列需要 ViewModel 单元测试覆盖：并发上限、首会话串行、失败隔离、乱序合并、上限禁用。

## Migration Plan

无数据库迁移。部署后新客户端立即获得并发追加；旧客户端继续发送严格修订，服务端仍接受。回滚时恢复 `add_files` 的严格修订校验与客户端串行上传；已合并的临时会话随 TTL 过期，不涉及账本事实回滚。

## Open Questions

无。并发上限、幂等规则、失败隔离和修订收敛已在需求澄清中确认。

## UI 与跨端验证依据

文件、账户、核对三步层级沿用现有结构，文件行保留原名、必要状态、重试与删除；不增加教学文案。初始失败呈现由 `cash-import-blocking-load-failure` 定义，删除裸重试与不可用步骤。这是已有页面的状态修复，不是重大 UI 重构，不另造 HTML 原型。

Hallmark 预检沿用 `FinanceTheme`、Git token 与 Material 3 默认控件测量。已阅读官方 Buttons overview/specs、States overview、Applying Material 3 Expressive、Snackbar overview/specs 及 JetBrains OutlinedButton API 正文；采用短按钮文案、状态角色、底部短暂反馈和原生控件尺寸，未新增 API 或依赖。Snackbar 位于模态任务底部、操作栏上方，不遮挡下一步按钮。

Web、Android、iOS 共用 commonMain 的队列、失败判定、重试、删除及文案。Web 使用真实 Chrome 在 390 px 代表视口验证；Android/iOS 仅编译和共享单测。原生设备、其他视口/主题及真实读屏设备未执行，不宣称覆盖。
