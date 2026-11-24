# shared/local-docker-deployment Specification

## Purpose
定义 Finance Tracker 在单台开发机上用容器运行 Kotlin 后端、Compose Web 与 PostgreSQL 的访问、持久化、安全边界和回滚合同，使局域网设备可以通过固定地址使用真实账本。
## Requirements
### Requirement: 单机容器部署提供 Web、后端和 PostgreSQL

部署 MUST 由同一份容器编排同时提供 PostgreSQL、Kotlin 后端、Compose Web 和 TLS 入口。PostgreSQL、后端与 Web MUST 只在容器内部网络可达，TLS 入口 MUST 是宿主机上唯一的业务入口。后端 MUST 在数据库可用且 schema 校验通过后才对外提供服务，TLS 入口 MUST 在后端与 Web 健康后才接收业务请求。

#### Scenario: 首次启动完整栈
- **WHEN** 维护者用仓库内的本地部署配置启动全部服务
- **THEN** PostgreSQL、后端、Web 和 TLS 入口 MUST 达到健康状态，TLS 入口 MUST 能打开登录页，且宿主机 MUST NOT 直接暴露后端、Web 或数据库端口

#### Scenario: 数据库未就绪
- **WHEN** PostgreSQL 尚未通过健康检查
- **THEN** 后端 MUST NOT 启动业务监听，TLS 入口 MUST NOT 把请求转发到未就绪的后端

#### Scenario: 后端不健康
- **WHEN** 后端健康检查失败
- **THEN** 编排 MUST 保持 TLS 入口不可用或返回可识别错误，不得把请求静默转发到其他数据库或匿名账本

### Requirement: 局域网 Web 使用 HTTPS 安全上下文

浏览器入口 MUST 使用 HTTPS，并由持久化的本地 CA 为固定局域网 IPv4 签发证书。浏览器 MUST 获得安全上下文和跨源隔离，`isSecureContext`、`crossOriginIsolated` MUST 为真，`SharedArrayBuffer` MUST 可用；任一条件不满足时 MUST NOT 宣称部署可用。明文 HTTP MUST NOT 作为本部署的 Web 入口发布。根证书 MUST 可由维护者导出并安装到受信设备；未安装根证书的设备 MUST 看到证书错误，不得静默降级到 HTTP。

#### Scenario: 首次打开 HTTPS 入口
- **WHEN** 浏览器打开本部署的固定 HTTPS 地址
- **THEN** 页面 MUST 加载 Compose Wasm 应用，`isSecureContext`、`crossOriginIsolated` MUST 为真，`SharedArrayBuffer` MUST 可用，登录页 MUST 可交互

#### Scenario: 设备尚未信任根证书
- **WHEN** 浏览器未安装本部署的本地 CA 根证书
- **THEN** 浏览器 MUST 显示证书错误，应用 MUST NOT 通过明文 HTTP 或其他降级入口继续运行

#### Scenario: 安装根证书后重新打开
- **WHEN** 维护者把导出的根证书安装到设备并信任
- **THEN** 浏览器 MUST 正常打开应用，登录、工作区和账本请求 MUST 继续使用该 HTTPS origin

#### Scenario: 明文 HTTP 不作为入口
- **WHEN** 设备尝试通过明文 HTTP 访问本部署
- **THEN** 宿主机 MUST 没有可用的明文 Web 入口，应用 MUST NOT 在缺少安全上下文时继续启动

### Requirement: PostgreSQL 数据持久化并受版本门禁

PostgreSQL 数据 MUST 保存在仓库外的命名卷中。空数据库首次启动时 MUST 由 Kotlin 后端建立当前基线 schema；已有数据库 MUST 只校验完整表集合和版本，版本不符时 MUST 失败关闭，不得自动升级、重建或改写。停止、重启或重建容器 MUST NOT 丢失已提交数据，除非维护者显式删除专用卷。

#### Scenario: 空库首次启动
- **WHEN** 后端连接到没有应用表的 PostgreSQL 数据库
- **THEN** 后端 MUST 建立当前基线 schema 并开始提供现有 HTTP 合同，数据库版本 MUST 等于该基线版本

#### Scenario: 重启后数据仍在
- **WHEN** 维护者停止并重新启动服务，且保留 PostgreSQL 卷
- **THEN** 此前注册的账号、工作区和账本记录 MUST 仍可读取

#### Scenario: 版本不匹配
- **WHEN** 数据库版本不是当前基线或表集合不完整
- **THEN** 后端 MUST 拒绝启动并返回脱敏的存储错误，不得修改现有表

#### Scenario: 显式删除数据
- **WHEN** 维护者删除本次部署的专用 PostgreSQL 卷
- **THEN** 数据 MUST 被删除，部署说明 MUST 明确该操作不可恢复，并要求先手工导出备份

### Requirement: 私有局域网 HTTP 来源边界

后端 MUST 接受本机 HTTP、HTTPS，以及 RFC1918 私有 IPv4 上带显式端口的 HTTP origin。私有 IPv4 MUST 限定为 `10.0.0.0/8`、`172.16.0.0/12` 和 `192.168.0.0/16`。公网 IP、域名或任意其他主机上的明文 HTTP MUST 继续被拒绝。所有 origin MUST 拒绝用户信息、路径、查询参数和片段。Web 生产构建编译的 API origin MUST 应用同一允许范围，并与后端允许的 origin 完全一致。

#### Scenario: 接受私有局域网 HTTP 兼容配置
- **WHEN** `FT_WEB_ORIGIN` 和 Web 构建的 API origin 都是 `http://192.168.1.111:8080`
- **THEN** 后端 MUST 启动成功，Web 构建 MUST 接受并编译该地址；该 HTTP 入口只作为兼容配置，浏览器运行仍 MUST 遵守“局域网 Web 使用 HTTPS 安全上下文”

#### Scenario: 接受其他私有网段
- **WHEN** origin 使用 `10.`、`172.16` 至 `172.31` 或 `192.168` 的 IPv4 地址并带显式端口
- **THEN** 后端和 Web 构建 MUST 接受该 HTTP origin

#### Scenario: 拒绝公网明文 HTTP
- **WHEN** origin 是 `http://8.8.8.8:8080` 或任何非私有 IPv4 的 HTTP 地址
- **THEN** 后端 MUST 拒绝启动，Web 构建 MUST 失败并给出可操作错误

#### Scenario: 保持 HTTPS 与本机行为
- **WHEN** origin 是任意 HTTPS origin，或 `http://127.0.0.1`、`http://localhost` 加端口
- **THEN** 既有允许行为 MUST 保持不变

#### Scenario: 拒绝非法 origin 形状
- **WHEN** origin 包含用户信息、路径、查询参数、片段或非 IPv4 的私有主机名
- **THEN** 后端和 Web 构建 MUST 拒绝该值，不得降级为其他来源

### Requirement: Web 以同源方式提供静态应用和 API

Web MUST 提供 Compose Wasm 生产分发包所需的静态文件、脚本与 Wasm MIME 类型，并对未知前端路径回退到应用入口。Web MUST 发送 `Cross-Origin-Opener-Policy: same-origin` 与 `Cross-Origin-Embedder-Policy: require-corp`，使浏览器进入跨源隔离上下文。浏览器对 `/api` 的请求 MUST 通过同一 Web origin 反向代理到后端，不得要求浏览器直连内部后端。代理 MUST 支持现有导入与账本请求所需的上传大小和超时。

#### Scenario: 浏览器启动应用
- **WHEN** 浏览器打开固定局域网地址
- **THEN** 页面 MUST 加载 Compose Wasm 应用，`crossOriginIsolated` MUST 为真，且登录页可交互

#### Scenario: 同源 API 调用
- **WHEN** 页面发起登录、账本读取或导入请求
- **THEN** 请求 MUST 发往同一 Web origin 的 `/api` 路径，并由 Web 转发到后端

#### Scenario: 前端子路径刷新
- **WHEN** 浏览器直接请求工作区等前端子路径
- **THEN** Web MUST 返回应用入口，而不是 404

#### Scenario: 大型导入请求
- **WHEN** 已认证用户通过 Web 上传现有合同允许的大账单或提交大请求体
- **THEN** 代理 MUST NOT 以默认的小请求体上限提前拒绝，错误 MUST 保持可诊断

### Requirement: 部署凭据和真实数据留在仓库外

仓库 MUST 只包含占位配置和示例环境文件。真实数据库密码、会话令牌、账单内容和账本数据 MUST NOT 写入仓库、镜像层、构建日志或部署说明。维护者 MUST 通过仓库外环境文件提供数据库密码，并固定 Web origin 与后端一致。

#### Scenario: 从示例配置部署
- **WHEN** 维护者复制示例环境文件并填写自己的数据库密码和局域网 origin
- **THEN** 编排 MUST 使用这些值启动，且 `git status` MUST NOT 显示真实环境文件

#### Scenario: 检查镜像和文档
- **WHEN** 审查本次提交、镜像构建上下文和部署说明
- **THEN** MUST NOT 出现真实密码、连接串凭据或账本数据

### Requirement: 启停、回滚和手工备份可操作

部署说明 MUST 提供启动、停止、保留数据重建、完全删除、日志查看和手工 `pg_dump` 的命令。停止或重建容器 MUST 保留 PostgreSQL 卷；完全删除 MUST 明确不可恢复。回滚 MUST 说明如何停止当前栈、保留或导出数据，以及如何恢复到不包含本变更的代码。

#### Scenario: 停止后重启
- **WHEN** 维护者执行停止命令后再次启动
- **THEN** 容器 MUST 重新创建，PostgreSQL 卷、TLS 根证书和已提交账本数据 MUST 保留

#### Scenario: 手工备份
- **WHEN** 维护者按部署说明执行 `pg_dump`
- **THEN** MUST 在仓库外生成可恢复的 PostgreSQL 备份文件，命令 MUST 不把密码写入 shell 历史

#### Scenario: 回滚代码
- **WHEN** 维护者需要撤销本次变更
- **THEN** 部署说明 MUST 给出停止服务、保留数据和恢复旧提交的步骤，不得要求删除数据库卷才能回退代码
