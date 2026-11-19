## 1. 思考

- [x] 已确认只修复配置缓存。在最新 `main` 的 `ebd4df19` 复现 Render 报错。

## 2. 计划

- [x] 保留运行时与部署合同，修复两个 Web 资源任务的延迟引用。

## 3. 任务拆分

- [x] 先用配置缓存严格模式复现，再验证首次构建、缓存复用和输入切换。

## 4. 构建

- [x] 修复前严格模式失败，修复后资源任务通过并保存缓存。

## 5. 审查

- [x] 范围化复核输入、输出和两个延迟动作。无阻断项。版本、校验和、许可证来源及 DSN 校验沿用现有配置。

## 6. 测试与 QA

- [x] 在 2026-11-19 对 `HEAD` 与 `origin/main` 的基线 `ebd4df19987a0666757f1d4e398a463c4a3b8143` 验证本次差异。
- `./gradlew :webApp:wasmJsProcessResources --configuration-cache --configuration-cache-problems=fail` 在修复前复现两个任务的配置缓存序列化错误，修复后通过。未设 `RENDER_GIT_COMMIT` 时，产物使用 Git 的 `HEAD`。
- `./gradlew :webApp:wasmJsBrowserDistribution --configuration-cache --configuration-cache-problems=fail` 五次通过。启用 Sentry 时检查 SDK、许可证、DSN、构建版本及 `webApp.js`，相同配置再次构建复用缓存。更换 DSN 和版本后重新生成启动脚本。清空 DSN 后移除 SDK 和许可证，保留应用包，相同配置再次构建复用缓存。
- Chrome 157 在 `http://127.0.0.1:5193/`、390 × 844 视口加载生产 Wasm 登录页，核对构建版本及隔离能力，无页面异常或失败请求。Sentry 禁用时未请求 SDK。截图及构建日志见 `/tmp/ft-cache-fix.xgkICI`。运行时没有改动，本次预览覆盖构建产物的启动入口。
- 6 项 Web 诊断及构建指纹单测、`bash -n compose/build-wasm-web.sh`、OpenSpec 严格校验、doctor 和 `git diff --check` 通过。

## 7. 发布

- [x] 回滚可恢复上一版 Gradle 脚本。交付只含 Gradle 兼容性与 OpenSpec 记录，Render 未重新部署。

## 8. 反思

- [x] 此前关闭配置缓存的预览无法覆盖生产发布故障。本次验收以开启缓存的生产分发为准。
