## Context

首期使用短生命周期命令。四条路线都需要将业务规则、API 和导入编排与 Compose 分开，恢复原会话绑定的加密快照、批次和未决写入。首期选用 Kotlin/JS 的 Node.js 目标，经 npm 分发。

## Goals / Non-Goals

安装后，人和 AI 都可通过同一个 `ft` 入口完成任务。运行时在当前命令需要访问后端时判断可达性。选型依据为现有模块复用、宿主接口、终端安装、分发和验证成本；本次没有性能指标。

## Decisions

### 一次命令的执行与恢复

按服务地址、登录身份与工作区定位安全存储。读取账本时先核版本，必要时替换同范围的完整快照；网络不可达或超时才按已核身份读取本地事实。可纯本地完成的解析和核对直接使用已取得的输入。明确拒绝、密钥损坏和响应解码错误各按原业务合同处理。服务端持有财务事实，CLI 通过 API 获取快照、提交命令与核实收据。

将共享查询与本地导入任务从 UI 和平台绑定处分出，沿用现有领域模块与传输合同。宿主负责文件、时区、凭据、加密持久化与跨进程互斥。导入修改和确认绑定同一批次的核对版本；持久化冻结请求后再发送，退出后可按原请求恢复。实现应以相同夹具比对收支顺序、分币种汇总、来源、写集与实际结果。

### Node 客户端与分发

在 `compose/` 下增加 `:cli`，通过 `js { nodejs(); binaries.executable() }` 构建生产程序。CLI 和 Compose 依赖相同的 `:business-domain` 与 `:business-contracts`；从 `:shared` 分步提取实际共用的 HTTP 调用、账本查询与导入任务逻辑。输入参数从 Node 进程读取，HTTP、文件、凭据、缓存和进程退出集中在宿主边界。每条命令结束时关闭资源。

发包目录包含 npm 清单、带 Node shebang 的 `bin/ft.cjs`、生产 JS 模块及运行依赖。以 `npm pack` 的实际压缩包做安装与启动测试，用通过测试的最低 Node 版本填写 `engines.node`。面向用户的命令名固定为 `ft`；包名及 registry 归属在正式发布前确定。

### 首期命令

| 命令组 | 操作 |
|---|---|
| `ft auth`、`ft workspace` | register、login、status、logout；list、current、create、use、rename、delete；member list/set-role/remove、invite create/preview/accept |
| `ft account` | list、show、create；导入中选择对应账户、补币种和撤回准备阶段的变更沿独立账户操作执行 |
| `ft cash`、`ft category` | list、show、summary、add、transfer、edit、categorize、delete；草稿分摊与转正式、关系候选搜索和取舍、来源差异与身份问题处理；分类目录的创建、调整、排序和删除影响核对 |
| `ft import` | prepare、file add/replace/remove、map、review、confirm、result、resume、cancel |

`--workspace` 表达预期目标；显式切换使用 `workspace use`。提供 `--json`、`--help` 和 `--version`。JSON 模式的结果和结构化失败写入 stdout，诊断写入 stderr；非交互调用缺少参数时返回非零退出码。金额以十进制字符串传递。导入示例为 `ft import prepare bank.xlsx --workspace ws_home --json`，随后用返回的批次 ID 执行 `ft import review BATCH --json` 和 `ft import confirm BATCH --review REV --json`。

### 四种编译目标与运行方式

| 路线 | 本仓库的实现工作 | 安装与分发 |
|---|---|---|
| Kotlin/JS + Node | 领域与契约已有 JS Node 目标。可直接接 Node 文件、标准输入输出和 npm 包；需要补系统凭据库、加密缓存及导入任务的 Node 适配。 | 一份 JS 发行包，npm 的 `bin` 暴露命令。机器需有兼容 Node；涉及原生扩展时另验各平台预编译包及无编译器安装。 |
| Kotlin/Native | 共享层现有 Native 目标是 iOS。需为承诺支持的桌面目标添加构建，验证 ZIP/XML、HTTP 引擎及安全存储，分别测试最终产物。只支持一个平台时工程量明显下降。 | 每个系统和架构提供匹配的原生程序。可直接放入 PATH；需验证动态库和系统最低版本。可另用 npm 按平台分包，但这一安装入口需要 Node。 |
| Kotlin/Wasm `wasmJs` + Node | 领域与契约已有 Node 目标。文件、终端、凭据与加密缓存经 JS 宿主接入；浏览器 Worker 依赖浏览器环境。 | 一份 npm 包带上加载器与 Wasm 文件，用户安装兼容 Node 后运行 `ft`。 |
| Kotlin/Wasm `wasmWasi` | 需要为领域和契约新增目标。当前 XML 依赖有 WASI 产物；ZIP 和 HTTP 客户端缺少对应产物。需要替换这些依赖或通过宿主提供解包与 HTTPS 能力，并接入密钥库、缓存与文件权限。 | 模块可交给支持 Kotlin 所需 Wasm 特性的 WASI 运行时，也可用 Node 启动。独立安装需要用户已有运行时，或随 CLI 分平台分发宿主。 |

仓库锁定版本的 Gradle 模块元数据显示，`xmlutil-core 0.91.3` 提供 `wasmWasi`，`kmp-zip 2.1.0`、`ktor-client-core 3.6.0` 和 `ktor-client-engine-defaults 3.6.0` 的 Wasm 产物仅面向 `wasmJs`。因此 WASI 路线需先验证登录请求和本地 XLSX 读取。Kotlin 的 WASI 目标目前使用 Preview 1；运行时还需向程序开放读取账单和写入应用缓存所需的目录，系统凭据库需要额外宿主能力。

在 macOS ARM64 上用 Kotlin 2.4.20 验证了四种最小程序。JS 和 wasmJs 均经 `npm pack`、空目录安装及命令名执行；Native release 产物可直接执行；wasmWasi 的生产模块通过编译器生成的启动文件在 Node 26.8.1 上执行。此启动文件使用 Node WASI Preview 1，默认没有预开放目录。验证范围为程序入口，完整 HTTPS、账单导入和加密缓存仍待集成验证。

已有 Node 的用户和 AI 环境首选 Kotlin/JS，宿主适配最少。若首要目标是无 Node 机器下载即用，首选 Kotlin/Native。已有标准化 WASI 宿主、文件授权和凭据接口的环境才有条件从 wasmWasi 的模块分发中获益。

参照 [Kotlin/JS](https://kotlinlang.org/docs/js-project-setup.html)、[Kotlin/Native 目标支持](https://kotlinlang.org/docs/native-target-support.html)、[Kotlin/Wasm](https://kotlinlang.org/docs/wasm-overview.html)、[WASI](https://kotlinlang.org/docs/wasm-wasi.html) 和 [Ktor 引擎](https://ktor.io/docs/client-engines.html)。

## Risks / Trade-offs

- 共享浏览器代码涉及 Worker、浏览器存储及浏览器凭据。即使同为 JS 或 Wasm 目标，也需验证无界面环境中的读取、恢复与取消。
- 无图形会话的 CI 或 AI 环境可能无法解锁操作系统密钥库。应验证目标环境的凭据提供方式及损坏、不可用、撤权后的行为，避免静默退回明文。
- 会话活动工作区可能被并发命令切换。CLI 绑定原范围，管理写入在服务端事务内核对预期工作区；以 SQLite 和真实 PostgreSQL 验证冲突与兼容。

## Migration Plan

先交付与旧客户端兼容的工作区事务保护，再分发使用该保护的 CLI。使用专用测试库和本地 npm 压缩包验证升级、失败及恢复；正式发布需单独授权。撤回客户端版本时保留原操作身份、未决批次和收据，遇到较新本机存储格式时保留数据并报告所需版本。服务端继续保留原业务 API。
