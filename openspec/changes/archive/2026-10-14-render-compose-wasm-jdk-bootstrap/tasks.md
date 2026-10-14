## 1. 思考与范围

- [x] 1.1 确认 Gradle daemon 需要 Azul Java 21，Render 构建命令在 Gradle Wrapper 启动前因缺少 Java 失败。
- [x] 1.2 完成 `grill-me` / `grilling` 澄清；用户确认采用最新 GA、SHA-256 校验与 `$XDG_CACHE_HOME` 缓存。
- [x] 1.3 Cross-platform Impact Check：Web 受影响（Wasm 静态产物 API origin，空配置默认 loopback）；Native 不受影响（Android/iOS 仍使用各自构建配置）；共享层不改代码，只沿用 `window.FT_API_ORIGIN` 合同。
- [x] 1.4 故障复盘：Render 报告 Foojay 包信息不完整。本机 Foojay API 请求返回 HTTP 403，无法取得失败响应；修复按更明确的响应校验与错误报告处理，不臆测其具体缺失字段。
- [x] 1.5 初始需求澄清：使用 Render Build Environment 的 `FT_API_ORIGIN` 注入 Compose Web API origin；当时决定未设置或为空时写入空默认值并允许构建，后由 1.6 修订。
- [x] 1.6 用户确认所有构建缺少或留空 `FT_API_ORIGIN` 时默认写入本地后端 `http://127.0.0.1:8000`；确认 Render 上该 loopback 地址指向访问者自己的设备，线上部署需设置公开 API origin。

## 2. 计划与设计

- [x] 2.1 记录脚本职责、Render 配置、依赖、缓存、校验与回滚方案。
- [x] 2.2 记录非目标：不修改 `render.yaml`，不改产品代码或云端服务设置。
- [x] 2.3 修订引导实现：保留 `build-wasm-web.sh` 作为 Render 入口，将 API、摘要校验、下载、缓存与 Gradle 启动移入 Python；支持 Foojay `checksum_uri`。沿用元数据不可用时失败的缓存策略。
- [x] 2.4 设计 Web 构建配置：将可选的 `FT_API_ORIGIN` 写入 Wasm 产物 `config.js`；空值显式重置为 `http://127.0.0.1:8000`，非空值限定为不含路径、查询参数或凭据的 HTTPS origin。

## 3. 任务拆分与一致性

- [x] 3.1 将需求映射为 OpenSpec proposal、design、tasks；声明 `skip_specs: true`，因为无产品行为规格变化。
- [x] 3.2 检查脚本、README、OpenSpec 范围和验收标准一致。
- [x] 3.3 为 Foojay 内联摘要、`checksum_uri`、缺字段、无效 URI 与多候选包排序建立标准库单元测试。
- [x] 3.4 先运行 API origin 配置回归测试并确认因实现缺失失败；实现后验证空值和空白值默认到本地后端、有效 origin 注入、非法 scheme/path/credentials/端口拒绝，并说明 Render Build Environment 与后端 CORS 设置及 loopback 部署风险。

## 4. 构建

- [x] 4.1 新增可执行脚本：查询最新 GA Azul Zulu JDK 21、校验下载、缓存并设置 `JAVA_HOME`。
- [x] 4.2 增加 Render Static Site 的根目录、构建命令和发布目录说明。
- [x] 4.3 移除 Bash 对制表符元数据的拆分；Python 直接校验 API 响应并给出具体错误。JDK 下载采用流式写入，SHA-256 校验通过后才解压与缓存。
- [x] 4.4 让构建入口读取 `FT_API_ORIGIN`，在 Wasm distribution 成功后安全写入生成的 `config.js`；缺失或空值时显式重置为本地后端地址。

## 5. 审查

- [x] 5.1 人工复核最终 diff、错误处理、缓存原子替换和归档路径检查；未发现需修改的 finding。Linux 下载与解压的端到端路径因当前主机为 macOS，列为未覆盖风险。
- [x] 5.2 按 `domain-glossary` 与 `chinese-documentation` 工作流复核术语和中英文排版；本变更未引入新的领域术语。
- [x] 5.3 复核 Python/Bash 边界、HTTPS 字段校验、校验和来源、归档路径检查、缓存一致性与错误信息；原 Render 响应无法本机复现，已在 design 中保留该限制。
- [x] 5.4 复核显式构建变量仅写入公开 origin、不包含密钥；未配置时的 loopback fallback 不包含凭据。检查 Wasm 页面先加载 `config.js`，并核对后端 CORS 使用 Compose 站点 origin。未发现阻断 finding。
- [x] 5.5 范围复核用户确认的 Render 缺省行为：未配置时浏览器请求访问者自己的 `127.0.0.1:8000`；README 明确提醒线上必须设置公开后端 origin。

## 6. 测试与验证

- [x] 6.1 `bash -n compose/build-wasm-web.sh` 通过；执行脚本中的 Foojay 查询逻辑通过，返回 Java `21.0.12.1+1`、有效包 ID、HTTPS 地址和 SHA-256。脚本在 macOS 上按预期以状态码 1 拒绝运行。
- [x] 6.2 `./gradlew :webApp:wasmJsBrowserDistribution --no-configuration-cache` 成功；生成目录为 `compose/webApp/build/dist/wasmJs/productionExecutable`。未在 Linux Render 环境完整执行引导脚本；补跑条件为在 Render 部署或 Linux/glibc x86_64、aarch64 环境执行 `./build-wasm-web.sh`。
- [x] 6.3 `openspec validate render-compose-wasm-jdk-bootstrap --strict` 通过；`openspec validate --all --strict` 为 27 项通过、0 项失败；`openspec doctor` 通过；`git diff --check` 通过。执行时 `HEAD` 与基线均为 `4dc8eba59f88915d941171d882766e4749d30537`。
- [x] 6.4 Web 浏览器 QA 不适用：只生成 API origin 静态配置，不改页面、路由、交互或响应式行为；另验证 Wasm 分发中的真实 `config.js` 注入与空值重置。
- [x] 6.5 在提交 `869e9ed21e9e03dffb9bd5d7b28ed917eb2e02d0` / 比较基线 `4047cd62ca8428e8a71caa96e728bfaa33ea2bab` 验证 bootstrap 修复：6 项单元测试、Wasm 构建、OpenSpec 校验通过；`./compose/build-wasm-web.sh` 在 macOS 按预期拒绝运行。Render/Linux 引导仍需部署环境验证；本机 Foojay API 返回 HTTP 403。
- [x] 6.6 在 `HEAD` `869e9ed21e9e03dffb9bd5d7b28ed917eb2e02d0` / 工作树改动上复验 API 构建配置：`python3 -m unittest discover -s compose/tests -v`（10/10 通过）、`bash -n compose/build-wasm-web.sh`、`python3 -m py_compile compose/build-wasm-web.py`、`git diff --check`、`openspec validate render-compose-wasm-jdk-bootstrap --strict`、`openspec validate --all --strict`（27 项通过）和 `openspec doctor` 均通过。`FT_API_ORIGIN=https://finance-api.example.com ./gradlew :webApp:wasmJsBrowserDistribution --no-configuration-cache` 成功；通过辅助函数在真实 Wasm `config.js` 验证 origin 注入与空值重置。Render/Linux 完整引导未运行（本机 macOS，Foojay API 返回 HTTP 403）。执行时间：2026-10-14 12:05 CST。
- [x] 6.7 在 `HEAD` / 比较基线 `ba32ad1f276a9ed1d186bf570aef59db882c97cf` 的工作树改动上验证：API 构建配置及 Foojay 单元测试 10/10 通过；Wasm distribution 构建成功；生成的真实 `config.js` 在未设置和空白变量时为 `http://127.0.0.1:8000`，显式 HTTPS 注入用例通过。生产预览使用 Chrome，URL `http://127.0.0.1:4185/`，视口 `1920×963`；页面显示登录入口，`config.js` 与 Wasm 资源成功加载。没有提交登录表单，因此未触发 API 请求；本次不改 UI，键盘/响应式 UI QA 不适用。网络观察到预加载字体 `404`（后续实际字体文件请求 `200`），控制台有 Kotlin/Wasm 对 `wasmExports.memory` 的弃用警告，均与此配置改动无关。`bash -n`、`py_compile`、`git diff --check`、OpenSpec 单项与全量严格校验（27 项）及 `openspec doctor` 通过。执行时间：2026-10-14 12:25 CST。

## 7. 发布准备

- [x] 7.1 Render Static Site 设置为根目录 `compose`、Build Command `./build-wasm-web.sh`、Publish Directory `webApp/build/dist/wasmJs/productionExecutable`；回滚为恢复旧命令并移除脚本与说明。
- [x] 7.2 Render Compose Static Site 线上部署应在 Environment 设置 `FT_API_ORIGIN` 为公开后端 HTTPS origin；若留空则构建默认写入 loopback，访问者会请求自己的设备。后端 Web Service 设置 `FT_WEB_ORIGIN` 为 Compose 站点 HTTPS origin。环境值未写入仓库；Render 服务设置尚未实际修改。

## 8. 反思

- [x] 8.1 可复用改进：Compose Web 的部署入口自带匹配 Gradle vendor/version 的 JDK 引导，避免 Render 依赖预装 Java。
- [x] 8.2 防复发：Foojay 响应字段、摘要来源、候选包选择和归档路径均有单元测试；元数据传递不再经过 Bash 字段拆分。
- [x] 8.3 防止静态站点配置漂移：通过 `FT_API_ORIGIN` 构建变量在每次构建时生成配置，不提交环境专属 API 地址；空值写入本地 loopback 默认值，不保留上次构建值，并在部署文档中说明线上风险。
