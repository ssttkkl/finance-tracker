## 1. 思考与范围

- [x] 1.1 确认 Gradle daemon 需要 Azul Java 21，Render 构建命令在 Gradle Wrapper 启动前因缺少 Java 失败。
- [x] 1.2 完成 `grill-me` / `grilling` 澄清；用户确认采用最新 GA、SHA-256 校验与 `$XDG_CACHE_HOME` 缓存。
- [x] 1.3 Cross-platform Impact Check：Web、Native、共享 presentation 均不受影响；变更只用于 Render 构建工具，不修改运行时或用户界面。
- [x] 1.4 故障复盘：Render 报告 Foojay 包信息不完整。本机 Foojay API 请求返回 HTTP 403，无法取得失败响应；修复按更明确的响应校验与错误报告处理，不臆测其具体缺失字段。

## 2. 计划与设计

- [x] 2.1 记录脚本职责、Render 配置、依赖、缓存、校验与回滚方案。
- [x] 2.2 记录非目标：不修改 `render.yaml`，不改产品代码或云端服务设置。
- [x] 2.3 修订引导实现：保留 `build-wasm-web.sh` 作为 Render 入口，将 API、摘要校验、下载、缓存与 Gradle 启动移入 Python；支持 Foojay `checksum_uri`。沿用元数据不可用时失败的缓存策略。

## 3. 任务拆分与一致性

- [x] 3.1 将需求映射为 OpenSpec proposal、design、tasks；声明 `skip_specs: true`，因为无产品行为规格变化。
- [x] 3.2 检查脚本、README、OpenSpec 范围和验收标准一致。
- [x] 3.3 为 Foojay 内联摘要、`checksum_uri`、缺字段、无效 URI 与多候选包排序建立标准库单元测试。

## 4. 构建

- [x] 4.1 新增可执行脚本：查询最新 GA Azul Zulu JDK 21、校验下载、缓存并设置 `JAVA_HOME`。
- [x] 4.2 增加 Render Static Site 的根目录、构建命令和发布目录说明。
- [x] 4.3 移除 Bash 对制表符元数据的拆分；Python 直接校验 API 响应并给出具体错误。JDK 下载采用流式写入，SHA-256 校验通过后才解压与缓存。

## 5. 审查

- [x] 5.1 人工复核最终 diff、错误处理、缓存原子替换和归档路径检查；未发现需修改的 finding。Linux 下载与解压的端到端路径因当前主机为 macOS，列为未覆盖风险。
- [x] 5.2 按 `domain-glossary` 与 `chinese-documentation` 工作流复核术语和中英文排版；本变更未引入新的领域术语。
- [x] 5.3 复核 Python/Bash 边界、HTTPS 字段校验、校验和来源、归档路径检查、缓存一致性与错误信息；原 Render 响应无法本机复现，已在 design 中保留该限制。

## 6. 测试与验证

- [x] 6.1 `bash -n compose/build-wasm-web.sh` 通过；执行脚本中的 Foojay 查询逻辑通过，返回 Java `21.0.12.1+1`、有效包 ID、HTTPS 地址和 SHA-256。脚本在 macOS 上按预期以状态码 1 拒绝运行。
- [x] 6.2 `./gradlew :webApp:wasmJsBrowserDistribution --no-configuration-cache` 成功；生成目录为 `compose/webApp/build/dist/wasmJs/productionExecutable`。未在 Linux Render 环境完整执行引导脚本；补跑条件为在 Render 部署或 Linux/glibc x86_64、aarch64 环境执行 `./build-wasm-web.sh`。
- [x] 6.3 `openspec validate render-compose-wasm-jdk-bootstrap --strict` 通过；`openspec validate --all --strict` 为 27 项通过、0 项失败；`openspec doctor` 通过；`git diff --check` 通过。执行时 `HEAD` 与基线均为 `4dc8eba59f88915d941171d882766e4749d30537`。
- [x] 6.4 Web 浏览器 QA 不适用：没有 Web UI、交互、路由或响应式行为变更。
- [x] 6.5 在 `HEAD` / 比较基线 `4047cd62ca8428e8a71caa96e728bfaa33ea2bab` 验证：`python3 -m unittest discover -s compose/tests -v`（6/6 通过）、`bash -n compose/build-wasm-web.sh`、`python3 -m py_compile compose/build-wasm-web.py`、`git diff --check`、`openspec validate render-compose-wasm-jdk-bootstrap --strict`、`openspec validate --all --strict`（27 项通过）和 `openspec doctor` 均通过；`cd compose && ./gradlew :webApp:wasmJsBrowserDistribution --no-configuration-cache` 构建成功。`./compose/build-wasm-web.sh` 在 macOS 按预期以状态码 1 拒绝执行。Render/Linux 引导端到端尚未复跑；补跑条件为 Render 或 Linux/glibc x86_64、aarch64 环境可用。Foojay API 在本机返回 HTTP 403。执行时间：2026-10-14 11:35 CST。

## 7. 发布准备

- [x] 7.1 Render Static Site 设置为根目录 `compose`、Build Command `./build-wasm-web.sh`、Publish Directory `webApp/build/dist/wasmJs/productionExecutable`；回滚为恢复旧命令并移除脚本与说明。
- [x] 7.2 保留现有 Render 入口与服务设置；当前修复尚未部署。未提交或推送，需 Linux/Render 成功构建后由用户按目标环境发布。

## 8. 反思

- [x] 8.1 可复用改进：Compose Web 的部署入口自带匹配 Gradle vendor/version 的 JDK 引导，避免 Render 依赖预装 Java。
- [x] 8.2 防复发：Foojay 响应字段、摘要来源、候选包选择和归档路径均有单元测试；元数据传递不再经过 Bash 字段拆分。
