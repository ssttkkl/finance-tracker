## 1. 思考

- [x] 1.1 阅读项目上下文、规格导航、相关主规格、词表、活动变更索引和旧客户端引用；确认起始 `HEAD` 与实时 `origin/main` 一致，工作区干净。
- [x] 1.2 完成 `grilling` 澄清与 OpenSpec explore；确认移除 `web/`、`mobile/`、仅由它们使用的 `packages/`，保留 Compose、后端和归档历史。

## 2. 计划

- [x] 2.1 按 A 类建立 proposal、design 与 capability delta，说明范围、非目标、回滚和退役的 Expo 登录调试地址要求。
- [x] 2.2 Cross-platform Impact Check：旧 React Web 和 Expo Native 目标退役；Compose Web、Android、iOS 继续受支持；Compose shared 与后端不变。

## 3. 任务拆分

- [x] 3.1 对齐规格、失败回归、旧目录及旧 Render 配置删除、现行说明、复核、验证及交付证据；确认无未决产品决策。

## 4. 构建

- [x] 4.1 先把 CI 合同测试改为断言三个旧目录已退役；删除前运行并得到预期红测（1 failed、1 passed，唯一失败为目录仍存在）。
- [x] 4.2 删除 `web/`、`mobile/`、`packages/` 与只配置旧 React 静态站点的根 `render.yaml`，保留 Compose 构建入口和现有 Compose CI 检查。
- [x] 4.3 更新现行 README、AGENTS、技能说明、词表、功能地图、架构／设计／部署说明、迁移对照、规格导航及已确认的主规格；移除 Expo 登录调试地址主规格。

## 5. 审查

- [x] 5.1 产品／范围复核：功能地图与现行说明仅把 Compose Web、Android、iOS列为当前客户端；移除旧端不扩展财务产品行为。
- [x] 5.2 工程、DX、安全及设计适用性复核：移除旧端与专属 workspace package 后保留 Compose 构建／CI 入口；现行文档链接通过检查；测试签名文件随 Expo 工程删除但保留在可恢复的 Git 历史中；根 `render.yaml` 删除不代表远端服务下线。Compose UI 未改变，设计 QA 不适用。
- [x] 5.3 独立复核最终 diff：范围仅含遗留 React Web、Expo 客户端、仅由旧客户端使用的 packages、旧站点部署配置、对应历史主规格退役及当前文档／合同测试更新。未发现阻断 finding；归档历史保持原样，未触碰后端、Compose 源码或远端资源。

## 6. 测试与 QA

- [x] 6.1 运行更新后的 CI 合同定向测试，确认旧目录、旧 Render 配置及旧 Expo CI 工作流均不存在：2 passed。
- [x] 6.2 按用户后续要求，不运行完整 Python 单测（本次未改应用源码）；已将进行中的 `PYTHONPATH=tests:.:src uv run pytest -q` 在约 17% 时中断。此命令未完成，不能记为通过。
- [x] 6.3 `cd compose && ./gradlew :shared:wasmJsTest :webApp:wasmJsBrowserDistribution :androidApp:assembleDebug :shared:testAndroidHostTest --no-configuration-cache` → `BUILD SUCCESSFUL`；含 Wasm 单测、Compose Web 生产构建、Android Debug 编译及 Host 单测。无关的既有 Kotlin/webpack 体积与动态依赖告警未阻断构建；其他原生设备 QA 不适用，Compose Native 源码未改。
- [x] 6.4 `openspec validate --all --strict` → 37 passed、0 failed；`openspec doctor` → OpenSpec 根目录正常、无引用错误。
- [x] 6.5 现行 Markdown 相对链接检查 56 个文件，无失效目标；变更文档中的 5 个锚点均存在。当前引用扫描只剩退役说明、迁移历史和回归测试断言；旧目录、旧 `render.yaml` 与旧 Expo CI workflow 均已不存在。`git diff --check` 通过。Compose 浏览器冒烟不适用：当前 Web target 与 UI 均未改，删除的 React 页面不在服务入口中。
- [x] 6.6 SQLite/PostgreSQL 双后端矩阵及财务性能专项不适用：本变更不改服务端、数据库、财务计算或持久化。

## 7. 发布

- [x] 7.1 交付证据及验证基线见下文。未提交、推送、建 PR、部署或下线远端 Render 服务；这些外部操作尚未授权。残余风险：旧 Expo 测试签名文件仍可从 Git 历史恢复；远端 Render 服务状态未经检查或更改。

## 8. 反思

- [x] 8.1 CI 合同测试持续断言旧客户端目录、旧站点配置和 Expo workflow 不存在，同时保留 Compose CI 合同；功能地图和当前规格说明 Compose 仍是支持的客户端。已归档的 change 保留迁移历史，不作为当前行为事实源。

## 当前审查与验证

定向 CI 合同测试：删除前两次红测均命中预期退役条件（分别为旧目录和根 `render.yaml` 仍存在）；删除后 `PYTHONPATH=tests:.:src uv run pytest tests/test_ci_workflow_contracts.py -q --disable-warnings --tb=short` → 2 passed。用户要求不再运行单测；此前启动的完整 Python 测试在约 17% 时被中断，未完成，不计为通过。Compose 命令 `cd compose && ./gradlew :shared:wasmJsTest :webApp:wasmJsBrowserDistribution :androidApp:assembleDebug :shared:testAndroidHostTest --no-configuration-cache` → `BUILD SUCCESSFUL`。`openspec validate --all --strict` → 37 passed、0 failed；`openspec doctor` → 根目录正常、无引用错误。Markdown 相对链接与锚点检查通过，`git diff --check` 通过。

比较基线及当前工作树 `HEAD`：`0c038a75e34264bd6d4aa5cce2070727df9d9ff7`；远端 `origin/main` 仍为同一提交。分支 `jealous-peacock`；OpenSpec `1.7.0`，Node.js `v26.8.1`。最终复核时间：2026-10-28 22:54 CST。无阻断 finding。未运行完整 Python 回归；未执行真实浏览器冒烟（Compose UI 未变）、远端 Render 服务检查或下线。测试签名文件仍可从 Git 历史恢复。
