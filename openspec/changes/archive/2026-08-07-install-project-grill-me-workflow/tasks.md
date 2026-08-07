## 1. 思考

- [x] 1.1 使用 `grill-me` 完成需求澄清，确认目标为项目内纳入 `grill-me`，并在 `AGENTS.md` 设置每项变更的前置澄清门禁。
- [x] 1.2 阅读 `AGENTS.md`、`openspec/project-context.md`、现有 active change 和项目技能目录，确认本变更属于 B 类纯工具/文档变更，使用 `skip_specs: true`。

## 2. 计划

- [x] 2.1 创建 proposal，记录目标、范围、非目标、影响和回滚方式。
- [x] 2.2 创建 design，确定项目路径、手动调用策略、澄清结果记录位置和风险控制。

## 3. 任务拆分与一致性

- [x] 3.1 检查 proposal、design、tasks 与 `skip_specs: true` 的一致性，确认不新增或修改业务规格。
- [x] 3.2 将文件安装、规则更新、范围复核和验证证据拆成可执行任务。

## 4. 构建

- [x] 4.1 添加 `.agents/skills/grill-me/SKILL.md` 和 `.agents/skills/grill-me/agents/openai.yaml`，内容与已确认的上游项目副本一致。
- [x] 4.2 更新根目录 `AGENTS.md`，增加每项变更实施前运行 `/grilling`、持续澄清以及记录结果的强制规则。

## 5. 审查

- [x] 5.1 进行范围化 diff 复核，确认只修改项目技能、`AGENTS.md` 和本 change artifact。
- [x] 5.2 进行工程与开发者体验复核，确认手动调用、歧义暂停、澄清结果记录和回滚说明清晰可执行。

## 6. 测试与 QA

- [x] 6.1 运行 `openspec validate --all --strict`，确认 change artifact 和 `skip_specs` 标记有效。
- [x] 6.2 运行 `openspec doctor`，确认仓库 OpenSpec 状态无新增诊断问题。
- [x] 6.3 运行 `git diff --check`，并核对项目技能文件与用户级已安装副本一致。
- [x] 6.4 不运行业务测试、类型检查、构建和 Web QA：本变更不包含可执行业务代码、依赖、路由、持久化或 UI；残余风险仅限文案规则可执行性，已由范围化 diff 复核覆盖。

## 7. 发布

- [x] 7.1 记录当前 `HEAD`、比较基线、实际命令、结果和未解决风险；本地工作树保留变更，不执行提交、推送、PR、合并或部署。

### 验证证据

- 执行时间：`2026-08-07 23:20:20 +0800`。
- 当前 `HEAD` 与比较基线：`0d89dffbc84459e2f1a3a27d19c4857109f4953f`。
- `openspec validate --all --strict`：18 项通过，0 项失败。
- `openspec doctor`：OpenSpec root 正常，无引用诊断。
- `git diff --check`：通过。
- `cmp` 核对项目内 `SKILL.md`、`agents/openai.yaml` 与用户级已安装副本：均一致。
- 未执行提交、推送、PR、合并、部署或第三方资源写入；未解决风险为项目副本后续可能与上游技能版本脱节，已在 `design.md` 记录升级策略。

## 8. 反思

- [x] 8.1 记录本次将需求澄清前置为所有变更门禁的可复用决策，并确认后续 change 必须先运行 `/grilling`。
