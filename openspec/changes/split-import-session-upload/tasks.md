# 任务：导入会话创建与文件上传分离

> 已实现：本地/演示先建空会话，再向已知会话上传；旧入口保留兼容。

## 1. 思考与边界

- [x] 1.1 复核：实际架构是本地优先会话，无服务端会话表；文件引用与修订在本地批次内校验。
- [x] 1.2 本地优先路径与服务端 PDF/XLS parse-only 不共用会话存储；本次在生命周期接口/本地实现拆分，服务端解析保持无状态。
- [x] 1.3 旧的一体化 `upload(context=null)` 保留为兼容回退；生产 Local/Demo 先创建空会话。
- [x] 1.4 空会话在本地不持久化；Demo 仅内存，无持久化孤儿，无需清理任务。

## 2. 生命周期合同

- [x] 2.1 定义 `createSession(): ImportContext?`；返回空会话 ID/修订，不读写文件。
- [x] 2.2 定义 `uploadToSession(sessionId, files, replacingFileRef)`；默认复用 `context(sessionId)` + `upload`，保持 `client_file_ref` 幂等。
- [x] 2.3 使用既有 `context(sessionId)` 查询会话/文件状态；本地实现按批次读取。
- [x] 2.4 本地批次 ID 唯一；同批 `client_file_ref` 由现有 task/coordinator 校验；无服务端唯一索引。
- [x] 2.5 旧 `upload(context=null)` 保留兼容；生产路径不再用它创建首个会话。

## 3. 客户端

- [x] 3.1 Repository 增加 `createSession`/`uploadToSession`；Local 与 Demo 实现 `createSession`。
- [x] 3.2 `CashImportTaskViewModel.uploadOne` 先 `createSession`，再把已知 `sessionId` 交给 `uploadToSession`。
- [x] 3.3 移除 `uploadCreationUnknown` 与 `canAddFiles` 阻塞；建会话失败后可直接重试加文件。
- [x] 3.4 建会话失败沿用现有错误/重新添加；上传失败沿用 `needsContextRefresh` 的重新核对入口；不自动重试。
- [x] 3.5 上传成功后复用现有上下文/账户映射/预览/确认流程。

## 4. 测试

- [x] 4.1 本地/演示建空会话：`createSession` 返回稳定 ID；不读文件、不写账本。
- [x] 4.2 上传：现有逐文件/部分成功/重复引用测试通过；新增 split 路径测试。
- [x] 4.3 `failedFirstCreationAllowsRetryInsteadOfBlocking`：首次建会话/上传失败后 `canAddFiles` 仍为 true，可再次上传。
- [x] 4.4 旧 `upload(context=null)` 兼容回退保留；现有预览/提交/写集测试通过。
- [x] 4.5 Web 导入冒烟：添加文件→账户映射正常；关闭导入后账本保持可用。

## 5. 审查与发布

- [x] 5.1 契约复核：本地空会话不持久化；`client_file_ref` 幂等沿用现有 coordinator；旧入口兼容。
- [x] 5.2 `git diff --check` 通过；残余：无服务端会话 API（当前架构不需要）、Demo 空会话无 TTL（仅内存）。
- [x] 5.3 功能地图无需改页面/能力；设计已改为本地优先生命周期合同。
- [x] 5.4 已提交 `8af67750` 并推送。
