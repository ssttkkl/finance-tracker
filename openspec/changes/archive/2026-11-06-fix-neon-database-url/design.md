## Context

根因见 [proposal](proposal.md)。现有生产入口仅支持不带 query 的 PostgreSQL URL，`Database` 的建库、校验及日常读写均通过同一 JDBC URL 和独立凭据连接。用户已确认保留 Neon URL、TLS 与通道绑定要求并补回归；grilling 边界已闭合，不重复询问。读取项目上下文、编码规范、当前主规格和词表后，没有新增业务术语。

## Goals / Non-Goals

目标是让已确认的两个连接安全选项贯穿启动与后续读写连接，并在非法配置时保持失败关闭。非目标是任意 JDBC 配置透传、远程证书文件管理、其他驱动参数、业务库迁移、Render 发布、UI 或财务规则变化。

## Decisions

从进程入口提取一个小型数据库配置读取边界，继续复用 `Database`，不新增连接池、提供商分支或配置框架。支持既有 PostgreSQL 三种 scheme；凭据仍单独传入，不嵌回 JDBC URL。安全选项进入同一个规范 JDBC query，使 bootstrap、validate、read 和 write 使用一致配置。`sslmode` 传同名 JDBC 参数，`channel_binding` 映射为 `channelBinding`；SSL mode 接受 `disable`、`allow`、`prefer`、`require`、`verify-ca`、`verify-full`，沿驱动大小写不敏感规则规范化为小写；channel binding 严格接受小写 `disable`、`prefer`、`require`。不作安全降级或新默认。只解码一遍 URL 参数，按解码后名称判重；未知、重复、空值和错误编码安全拒绝。不能只删除 `rawQuery` 拒绝条件，因为当前 JDBC URL 构造也会丢掉 query。

拒绝把所有参数原样透传：这会引入当前不需要的驱动配置面。拒绝通过删除 TLS 参数或修改线上连接串绕过缺陷。SQLite 文件路径和参数拒绝行为保持现状，环境变量显式优先级不变。配置容器避免自动生成包含凭据的字符串表示；配置错误不保留原始输入异常消息。

## Risks / Trade-offs

TLS 和通道绑定要求必须由真实驱动执行，不能只断言解析字符串。使用仓库外专用 `_test` PostgreSQL，操作者显式启动 TLS、准备 CA 与 SCRAM，再由测试读取显式配置；测试不得启动或探测数据库。默认矩阵使用同一配置入口验证双库账户服务和无 TLS 时绑定必须拒绝；本地显式 TLS 配置另核对实际连接 `pg_stat_ssl`。安装产物通过子进程和仓库外 `user.home` 验证可信／不可信 CA、主机名与非 SCRAM 认证，不修改用户证书目录。凭据及日志不进入仓库，不读取线上业务库。没有 UI、共享客户端和 Native 代码变更，不追加设备或浏览器 UI 门禁。

连接模式采用 PGJDBC 的对应语义：`require` 只要求 TLS，`verify-ca` 额外验证 CA，`verify-full` 再验证主机名。保留显式选项不代表模拟 libpq 检测本机 `root.crt` 后的隐式行为，不能因此改写或降低用户指定模式。依据为 [驱动配置](https://jdbc.postgresql.org/documentation/use/) 和 [SSL 说明](https://jdbc.postgresql.org/documentation/ssl/)。

## Migration Plan

本地完成先红后绿、独立工程／安全及代码库防劣化复核、完整默认回归与部署参考后同步主规格并归档。当前分支与旧 PR 目标不同，提交／推送／新 PR 需要按 AGENTS.md 获得具体目标授权；Render 仍需另行发布授权。无 schema 迁移，回退代码不会修改账本；旧版本仍会拒绝本次带参数 URL。
