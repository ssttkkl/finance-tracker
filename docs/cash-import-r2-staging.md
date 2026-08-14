# 现金导入临时存储（Cloudflare R2）

生产环境使用私有 R2 Bucket 保存一次导入会话的原始账单和短期预览对象。R2 只承载临时来源，不是账本事实源；账户、流水、映射和关系仍只在最终确认时写入 Neon 的单一事务。

## Render 环境变量

在 Render 的 Secret Environment Variables 中配置：

- `FT_IMPORT_STAGING_BACKEND=r2`
- `FT_R2_ENDPOINT=https://<account-id>.r2.cloudflarestorage.com`
- `FT_R2_BUCKET=<private-bucket-name>`
- `FT_R2_ACCESS_KEY_ID=<scoped-access-key>`
- `FT_R2_SECRET_ACCESS_KEY=<scoped-secret-key>`
- `FT_R2_REGION=auto`
- `FT_R2_PREFIX=cash-import`
- `FT_IMPORT_STAGING_TTL_SECONDS=1800`

凭据只授予临时 Bucket 所需的对象读、写、删权限。不要启用公开 `r2.dev` 地址，也不要把上述值写入仓库、日志或浏览器响应。

## Bucket 清理

为 `cash-import/` 前缀配置不超过 24 小时的生命周期删除规则，作为应用成功删除和会话 TTL 的兜底。应用默认会话 TTL 为 30 分钟，成功确认后立即删除对象；清理失败不能回滚已经成功的业务事务，也不能让会话再次确认。

## 本地和测试

没有 R2 凭据时只能显式使用：

```text
FT_IMPORT_STAGING_BACKEND=memory
FT_IMPORT_STAGING_ALLOW_MEMORY=1
```

内存适配器仅用于本地或测试。生产缺少 R2 配置时应用应失败关闭，不使用 Render 本地文件系统作为回退。
