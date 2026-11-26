## Context

首期使用短生命周期命令。三种编译方式都需要将业务规则、API 和导入编排与 Compose 分开，恢复原会话绑定的加密快照、批次和未决写入。用户关注开发和安装负担，技术选型待确认。

## Goals / Non-Goals

安装后，人和 AI 都可通过同一个 `ft` 入口完成任务。运行时在当前命令需要访问后端时判断可达性。选型依据为现有模块复用、宿主接口、终端安装、分发和验证成本；本次没有性能指标。

## Decisions

### 一次命令的执行与恢复

按服务地址、登录身份与工作区定位安全存储。读取账本时先核版本，必要时替换同范围的完整快照；网络不可达或超时才按已核身份读取本地事实。可纯本地完成的解析和核对直接使用已取得的输入。明确拒绝、密钥损坏和响应解码错误各按原业务合同处理。服务端持有财务事实，CLI 通过 API 获取快照、提交命令与核实收据。

将共享查询与本地导入任务从 UI 和平台绑定处分出，沿用现有领域模块与传输合同。宿主负责文件、时区、凭据、加密持久化与跨进程互斥。导入修改和确认绑定同一批次的核对版本；持久化冻结请求后再发送，退出后可按原请求恢复。实现应以相同夹具比对收支顺序、分币种汇总、来源、写集与实际结果。

### 三种编译方式

| 路线 | 本仓库的实现工作 | 安装与分发 |
|---|---|---|
| Kotlin/JS + Node | 领域与契约已有 JS Node 目标。可直接接 Node 文件、标准输入输出和 npm 包；需要补系统凭据库、加密缓存及导入任务的 Node 适配。 | 一份 JS 发行包，npm 的 `bin` 暴露命令。机器需有兼容 Node；涉及原生扩展时另验各平台预编译包及无编译器安装。 |
| Kotlin/Native | 共享层现有 Native 目标是 iOS。需为承诺支持的桌面目标添加构建，验证 ZIP/XML、HTTP 引擎及安全存储，分别测试最终产物。只支持一个平台时工程量明显下降。 | 每个系统和架构提供匹配的原生程序。可直接放入 PATH；需验证动态库和系统最低版本。可另用 npm 按平台分包，但这一安装入口需要 Node。 |
| Kotlin/Wasm | 领域与契约已有 wasmJs Node 目标，现有浏览器 Wasm Worker 仍依赖浏览器宿主。Node 路线要连接 JS 宿主并一起打包加载器和 Wasm 文件。独立 wasmWasi 是另一个目标，需重新验证文件授权、HTTP/TLS、密钥库及所有依赖。 | wasmJs 可经 npm 安装，用户仍需要兼容 Node。wasmWasi 需兼容的 WASI 运行时或一同交付启动器。 |

在 macOS ARM64、Kotlin 2.4.20、Node 26.8.1 和 npm 11.19.0 上，JS 与 wasmJs 的最小生产程序均经 `npm pack`、空目录安装和命令名执行。wasmJs 包含 `.wasm` 与生成的 `.mjs` 宿主文件。这项验证只覆盖分发入口，完整财务依赖、加密缓存及支持平台仍需集成验证。

建议现有 Node 环境优先选择 Kotlin/JS。若首要安装目标是无 Node 机器下载即用，则优先 Kotlin/Native。Wasm 更适用于已有 Wasm 宿主或必须分发 Wasm 模块的环境。

参照 [Kotlin/JS](https://kotlinlang.org/docs/js-project-setup.html)、[Kotlin/Native 目标支持](https://kotlinlang.org/docs/native-target-support.html)、[Kotlin/Wasm](https://kotlinlang.org/docs/wasm-overview.html)、[WASI](https://kotlinlang.org/docs/wasm-wasi.html) 和 [Ktor 引擎](https://ktor.io/docs/client-engines.html)。

## Risks / Trade-offs

- 共享浏览器代码涉及 Worker、浏览器存储及浏览器凭据。即使同为 JS 或 Wasm 目标，也需验证无界面环境中的读取、恢复与取消。
- 无图形会话的 CI 或 AI 环境可能无法解锁操作系统密钥库。应验证目标环境的凭据提供方式及损坏、不可用、撤权后的行为，避免静默退回明文。
- 会话活动工作区可能被并发命令切换。CLI 绑定原范围，管理写入在服务端事务内核对预期工作区；以 SQLite 和真实 PostgreSQL 验证冲突与兼容。

## Open Questions

首期使用 npm 和 Node，还是交付指定系统的独立原生程序？发行平台、最低运行环境与包清单随选型落实。开发派发等待用户决定。
