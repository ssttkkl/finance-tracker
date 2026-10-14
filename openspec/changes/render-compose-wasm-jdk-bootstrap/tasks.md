## 1. 思考与范围

- [x] 1.1 确认 Gradle daemon 需要 Azul Java 21，Render 构建命令在 Gradle Wrapper 启动前因缺少 Java 失败。
- [x] 1.2 完成 `grill-me` / `grilling` 澄清；用户确认采用最新 GA、SHA-256 校验与 `$XDG_CACHE_HOME` 缓存。
- [x] 1.3 Cross-platform Impact Check：Web、Native、共享 presentation 均不受影响；变更只用于 Render 构建工具，不修改运行时或用户界面。

## 2. 计划与设计

- [x] 2.1 记录脚本职责、Render 配置、依赖、缓存、校验与回滚方案。
- [x] 2.2 记录非目标：不修改 `render.yaml`，不改产品代码或云端服务设置。

## 3. 任务拆分与一致性

- [x] 3.1 将需求映射为 OpenSpec proposal、design、tasks；声明 `skip_specs: true`，因为无产品行为规格变化。
- [x] 3.2 检查脚本、README、OpenSpec 范围和验收标准一致。

## 4. 构建

- [x] 4.1 新增可执行脚本：查询最新 GA Azul Zulu JDK 21、校验下载、缓存并设置 `JAVA_HOME`。
- [x] 4.2 增加 Render Static Site 的根目录、构建命令和发布目录说明。

## 5. 审查

- [x] 5.1 人工复核最终 diff、错误处理、缓存原子替换和归档路径检查；未发现需修改的 finding。Linux 下载与解压的端到端路径因当前主机为 macOS，列为未覆盖风险。
- [x] 5.2 按 `domain-glossary` 与 `chinese-documentation` 工作流复核术语和中英文排版；本变更未引入新的领域术语。

## 6. 测试与验证

- [x] 6.1 `bash -n compose/build-wasm-web.sh` 通过；执行脚本中的 Foojay 查询逻辑通过，返回 Java `21.0.12.1+1`、有效包 ID、HTTPS 地址和 SHA-256。脚本在 macOS 上按预期以状态码 1 拒绝运行。
- [x] 6.2 `./gradlew :webApp:wasmJsBrowserDistribution --no-configuration-cache` 成功；生成目录为 `compose/webApp/build/dist/wasmJs/productionExecutable`。未在 Linux Render 环境完整执行引导脚本；补跑条件为在 Render 部署或 Linux/glibc x86_64、aarch64 环境执行 `./build-wasm-web.sh`。
- [x] 6.3 `openspec validate render-compose-wasm-jdk-bootstrap --strict` 通过；`openspec validate --all --strict` 为 27 项通过、0 项失败；`openspec doctor` 通过；`git diff --check` 通过。执行时 `HEAD` 与基线均为 `4dc8eba59f88915d941171d882766e4749d30537`。
- [x] 6.4 Web 浏览器 QA 不适用：没有 Web UI、交互、路由或响应式行为变更。

## 7. 发布准备

- [x] 7.1 Render Static Site 设置为根目录 `compose`、Build Command `./build-wasm-web.sh`、Publish Directory `webApp/build/dist/wasmJs/productionExecutable`；回滚为恢复旧命令并移除脚本与说明。HEAD/基线为 `4dc8eba59f88915d941171d882766e4749d30537`。未提交或推送。

## 8. 反思

- [x] 8.1 可复用改进：Compose Web 的部署入口自带匹配 Gradle vendor/version 的 JDK 引导，避免 Render 依赖预装 Java。
