## 1. 思考

- [x] 1.1 阅读 `AGENTS.md`、`openspec/project-context.md`、现有 Skill、领域词表和 Skill Creator 约定。
- [x] 1.2 完成需求澄清：唯一对象为支付宝「交易流水证明」；入口名为 `alipay-bill`；核心流程与设备后端分离；当前实现为 iOS + Computer Use；未来 Android + ADB 只新增适配文件。
- [x] 1.3 尝试运行项目 `grilling` 命令；本机未提供可执行命令，结果记录为环境限制，不以猜测替代已确认范围。

## 2. 计划

- [x] 2.1 确认本次属于 B 类纯技能/文档变更，使用 `skip_specs: true`，不修改产品主规格。
- [x] 2.2 确定核心状态、后端能力契约、适配文件职责、认证接管和恢复边界。

## 3. 任务拆分与一致性

- [x] 3.1 检查 `proposal.md`、`design.md`、`tasks.md` 与 `skip_specs: true` 一致，确认非目标不包含 Android 实现或运行时代码。
- [x] 3.2 将目录重命名、核心拆分、iOS 适配、元数据、词表和验证拆成独立任务。

## 4. 构建

- [x] 4.1 将 Skill 目录重命名为 `.agents/skills/alipay-bill/`。
- [x] 4.2 将平台无关业务流程、状态检查、后端选择、安全边界和交付报告写入 `SKILL.md`。
- [x] 4.3 将 iPhone 镜像 + Computer Use 的支付宝页面操作写入 `references/ios-computer-use.md`。
- [x] 4.4 更新 `agents/openai.yaml`，并把新增术语写入 `DOMAIN_GLOSSARY.md`。

## 5. 审查

- [x] 5.1 范围复核：核心文件不包含 iOS 控件操作、AirDrop 细节或保存页细节；适配文件承载这些实现细节。
- [x] 5.2 工程复核：后端选择先于操作；状态检查与恢复不因后端切换而改变；新增 Android 只需增加适配文件。
- [x] 5.3 安全复核：认证由用户接管；未授权不转交；申请成功、生成、下载、保存和转交分开报告。

## 6. 测试与 QA

- [x] 6.1 运行 `python3 /Users/huangwenlong/.codex/skills/.system/skill-creator/scripts/quick_validate.py .agents/skills/alipay-bill`：通过。
- [x] 6.2 解析 `agents/openai.yaml`：通过，入口为 `$alipay-bill`。
- [x] 6.3 运行 `git diff --check` 及未跟踪 Skill 文件的 `git diff --no-index --check`：通过。
- [x] 6.4 检查旧名称和核心/适配边界引用：旧目录不存在，核心只引用适配文件，iOS 操作集中在适配文件。
- [x] 6.5 不运行财务测试、类型检查、构建、Web QA、双数据库矩阵：本次不包含运行时代码、数据库、接口、路由或 UI；残余风险限于 Skill 文案和后端选择执行。

## 7. 发布

- [x] 7.1 记录本地验证证据；已提交并推送 Skill 变更，进入到 `refactor/web` 的 PR 发布流程，不执行合并、部署或第三方写入。

### 验证证据

- 执行时间：`2026-08-12 17:10:00 +0800`。
- 提交前验证基线：`4404782652421fcd3ae723c5dfd6997dd60667e1`；Skill 提交：`06bb1d0`。
- `quick_validate.py`：`Skill is valid!`。
- YAML 解析：通过。
- `openspec validate --all --strict`：19 项通过，0 项失败；`openspec doctor`：通过。
- `git diff --cached --check`：通过。
- Web Vitest：4 个测试文件、46 个测试通过。
- `uv run pytest -q`：1,294 通过、155 跳过、2 个既有性能失败；失败均为 SQLite 导入测试的进程内存峰值超过既有阈值，与本次 Skill 文档变更无关，按用户决定记录为非阻断。
- `/grilling`：本机无可执行 `grilling` 命令；已将用户确认结论写入 proposal、design 和核心 Skill。

## 8. 反思

- [x] 8.1 将“业务状态稳定、设备手段可替换”的适配器边界沉淀到核心 Skill 和领域词表，后续 Android + ADB 不需要复制核心流程。
