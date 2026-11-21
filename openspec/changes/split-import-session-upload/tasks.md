# 任务：导入会话创建与文件上传分离

> 本 change 只登记设计与计划，当前不修改代码、不提交实现。

## 1. 思考与边界

- [ ] 1.1 复核现有导入会话表、TTL 清理、文件引用唯一性、会话修订和归属校验。
- [ ] 1.2 复核本地优先路径（`LocalCashImportLifecycleRepository`）与服务端解析路径是否共用同一状态机。
- [ ] 1.3 列出旧的一体化首次上传入口及所有调用方，确定迁移/弃用策略。
- [ ] 1.4 确认空会话 TTL 与清理任务不会误删仍有文件的会话。

## 2. 接口与数据

- [ ] 2.1 定义 `POST /cash-import/sessions` 请求/响应、错误码和空会话 TTL。
- [ ] 2.2 定义 `POST /cash-import/sessions/{id}/files` multipart、`client_file_ref` 幂等和部分成功响应。
- [ ] 2.3 定义 `GET /cash-import/sessions/{id}` 文件状态响应与归属校验。
- [ ] 2.4 增加 `(workspace, session_id, client_file_ref)` 唯一约束/查询索引；并发重试保护。
- [ ] 2.5 旧入口兼容/弃用：不得与新流程并存创建两个会话。

## 3. 客户端

- [ ] 3.1 Repository 增加 `createSession`、`uploadToSession`、`sessionStatus`；本地与服务端实现。
- [ ] 3.2 `CashImportTaskViewModel` 改为“先建会话再上传”，保存 session_id 与逐文件状态。
- [ ] 3.3 移除 `uploadCreationUnknown` 的不可恢复状态，拆为 `sessionCreateFailed` / `uploadIncomplete` 可重试状态。
- [ ] 3.4 导入页增加“重试创建/重新读取/重试上传”动作和明确文案；不自动重试。
- [ ] 3.5 上传失败后只补传未完成 refs；成功后复用现有账户映射/预览/确认流程。

## 4. 测试

- [ ] 4.1 服务端契约：建空会话、响应丢失、重复建会话、空会话过期、跨工作区拒绝。
- [ ] 4.2 上传：成功、响应丢失、部分成功、重复 ref、并发同 ref、只补传失败文件。
- [ ] 4.3 客户端状态机：建会话失败可重试、上传失败保留 session_id、无自动新建会话。
- [ ] 4.4 旧入口兼容（若保留）不产生双会话；现有预览/提交/写集幂等回归。
- [ ] 4.5 Web 导入主流程冒烟：建会话失败重试、上传失败查询恢复、正常导入完成。

## 5. 审查与发布

- [ ] 5.1 契约设计复核：会话/文件键、TTL、并发、归属、旧入口兼容。
- [ ] 5.2 `git diff --check`、最终 diff 复核；记录未运行项与残余风险。
- [ ] 5.3 更新 API 文档/功能地图/测试夹具。
- [ ] 5.4 提交并推送；归档前确认无 blocking finding。
