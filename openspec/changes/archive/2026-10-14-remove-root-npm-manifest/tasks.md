## 1. 思考

- [x] 1.1 阅读项目上下文，检查根 npm 清单、锁文件和脚本的活跃引用。
- [x] 1.2 与用户确认范围：删除根 npm 文件，更新活跃文档、CI 和 Dev Container，保留固定版本的可选 Pen 校验。
- [x] 1.3 风险检查：CI、Dev Container 和文档不得依赖根 npm 安装；`web/` 与 `mobile/` 的 npm 项目保持不变。

## 2. 计划

- [x] 2.1 在本变更中记录目标、非目标、验收范围、设计选择、风险和回滚方式。
- [x] 2.2 声明不产生产品能力差异（`skip_specs: true`），因为本次只涉及仓库工具和文档。

## 3. 任务拆分与一致性

- [x] 3.1 实施任务覆盖清单、锁文件、工作流、开发容器、活跃文档和遗留引用检查。
- [x] 3.2 确认 `render.yaml` 仍使用独立的 `web/` 根目录，与 Compose 部署配置无关。

## 4. 构建

- [x] 4.1 将 README 中的根 npm 快捷命令改为直接 Python、Gradle 和 Node.js 命令。
- [x] 4.2 调整 CI：移除根 npm 安装和锁文件缓存；使用 Python 生成 token；仅在有密钥时安装固定版本 Pen CLI。
- [x] 4.3 删除根目录 `package.json` 和 `package-lock.json`。
- [x] 4.4 更新 Node.js 版本说明，分别描述本地预览和 Pen CLI 的要求。
- [x] 4.5 更新 Dev Container 初始化脚本和文档索引，让 npm 命令明确使用 `web/` 子项目；清理旧 Native 说明中的根 workspace 命令。

## 5. 审查

- [x] 5.1 搜索活跃文件，确认没有根 npm 脚本或锁文件安装的陈旧引用；`render.yaml` 和 Pen CLI 说明分别指向 `web/` 子项目和独立 CLI 安装，Native 文档说明其迁移参考状态。
- [x] 5.2 检查完整差异的范围、回滚清晰度，以及是否误改子目录 npm 清单。
- [x] 5.3 完成产品/范围与工程复核，在此记录发现和处理结果。

独立复核最初发现 `.devcontainer/post-create.sh` 仍在根目录运行 `npm ci` / workspace 命令，以及 `docs/README.md` 未标明生产预览应在 Web 子项目运行。已修复为在 `web/` 执行安装和 Playwright 命令，并明确文档工作目录。全仓扫描还发现 `SKILL.md` 和旧 Native 文档依赖已失效的根 workspace 命令；现已移除，Native 文档明确标为迁移参考。复核后没有剩余阻断项。

## 6. 验证

- [x] 6.1 运行 `openspec validate remove-root-npm-manifest --type change --strict` 和 `openspec validate --all --strict`。
- [x] 6.2 运行 `openspec doctor`、`git diff --check`、工作流 YAML 解析和 Dev Container shell 语法检查。
- [x] 6.3 跨平台影响检查：本次仅改仓库工具、CI、开发容器和文档，不改变 Web UI、Native UI 或共享 presentation 行为。
- [x] 6.4 不涉及应用测试或构建；本次未运行应用测试或构建。直接运行 `python3 scripts/generate_ui_tokens.py` 成功，检查工作树确认没有额外生成文件变更。

验证证据（2026-10-14）：`openspec validate remove-root-npm-manifest --type change --strict` 通过；`openspec validate --all --strict` 为 27 passed、0 failed；`openspec doctor` 报告根目录正常且无引用错误；两份 GitHub Actions YAML 可解析；`bash -n .devcontainer/post-create.sh` 通过；`python3 scripts/generate_ui_tokens.py` 成功；`git diff --check HEAD` 通过。未运行应用测试或构建（本次仅移除工具入口并更新 CI/开发容器/文档）。

## 7. 发布准备

- [x] 7.1 记录分支、基线、最终 `HEAD`、验证证据、回滚方式和未执行检查。
- [x] 7.2 本次请求未授权提交、推送或部署。

发布记录：分支 `codex/remove-root-npm-manifest`，比较基线 `04ed9d6`（`main` / `origin/main`）；最终 `HEAD` 在实施期间未变，当前提交仍为 `04ed9d6`。回滚可恢复根 `package.json`、`package-lock.json` 并反向撤销 CI、Dev Container 和文档修改。未提交、未推送。

## 8. 反思

- [x] 8.1 后续删除工具入口时，应搜索 CI、Dev Container 和索引文档中的隐式工作区依赖，并把命令的工作目录写明。
