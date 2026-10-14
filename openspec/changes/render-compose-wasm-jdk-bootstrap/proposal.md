## Why

Render 的构建环境可能没有预装 Java，因此直接运行 Gradle Wrapper 会在启动构建前失败。为 Compose Web 的 Render 构建提供可重复执行的 JDK 引导脚本，避免依赖服务环境手工安装 Java。

## What Changes

- 新增 Compose Web 构建脚本：查询 Azul Zulu JDK 21 最新 GA 包，校验 SHA-256，并将解压后的 JDK 缓存到 `$XDG_CACHE_HOME`。
- 脚本设置 `JAVA_HOME` 后运行 `:webApp:wasmJsBrowserDistribution`。
- 在 `compose/README.md` 记录 Render 的根目录和构建命令。

## Capabilities

本变更仅涉及构建工具和部署说明，不改变产品行为，因此不新增或修改产品能力规格；变更已声明 `skip_specs: true`。

## Impact

- 影响 `compose/build-wasm-web.sh` 和 `compose/README.md`。
- 使用系统已有的 Python 3、`curl`、`tar` 和 SHA-256 工具；不新增依赖。
- 不修改现有 `render.yaml`，该文件仍用于仓库中的 `web/` 服务。
- 回滚时删除构建脚本及对应 README 说明即可；不修改应用数据或 Render 服务配置。
