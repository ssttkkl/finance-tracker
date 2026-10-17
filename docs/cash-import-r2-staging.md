# 现金导入临时存储配置

现金导入会话需要短期保存原始文件和会话数据。后端支持 R2 对象存储和显式启用的进程内存储；暂存内容不是账本事实，正式账户、流水与关系只在确认导入时写入数据库。

## R2 配置

设置 `FT_IMPORT_STAGING_BACKEND=r2`，并提供：

- `FT_R2_ENDPOINT=https://<account-id>.r2.cloudflarestorage.com`
- `FT_R2_BUCKET=<private-bucket-name>`
- `FT_R2_ACCESS_KEY_ID=<scoped-access-key>`
- `FT_R2_SECRET_ACCESS_KEY=<scoped-secret-key>`

可选配置：

- `FT_R2_PREFIX=cash-import`：对象键前缀。
- `FT_IMPORT_STAGING_TTL_SECONDS=1800`：会话有效时间，范围为 1–1800 秒。

凭据应限制在暂存 bucket 所需的对象读、写和删权限。bucket 不应公开访问；不要将凭据写入仓库、日志或浏览器响应。可配置对象生命周期清理作为应用清理的兜底，保留期限不得超过业务允许的账单暂存周期。

部署平台、数据库和对象存储的选择由运行环境决定；本文不假设特定生产供应商。

## 本地与测试

内存后端必须显式选择并允许：

`FT_IMPORT_STAGING_BACKEND=memory`
`FT_IMPORT_STAGING_ALLOW_MEMORY=1`

内存适配器适用于本地开发或测试，不具备跨进程持久性。未显式配置暂存后端、R2 配置缺失或无效时，应用应启动失败，不应静默切换到其他存储。
