## 1. 需求与范围

- [x] 1.1 根据用户补充确认来源为 `mattpocock/skills`，确认 `grill-me` 是委托入口、`grilling` 是实际依赖。
- [x] 1.2 记录非目标：不修改 Finance Tracker 产品代码，不新增 shell 命令，不替代上游访谈逻辑。

## 2. 实施

- [x] 2.1 将上游 `grilling` skill 及其 `agents/openai.yaml` 加入仓库，并同步 `grill-me` 委托文本。
- [x] 2.2 同步个人 `~/.codex/skills` 安装副本，验证两个 skill 文件和元数据存在。
- [x] 2.3 更新 `AGENTS.md`，明确两个 skill 的依赖和 Codex 调用方式。

## 3. 验证与交付

- [x] 3.1 运行 skill quick validation、OpenSpec 严格校验和 `git diff --check`。

  验证记录（2026-09-04，验证基线 `HEAD`：`efa313b`）：四份仓库/个人 skill 均通过 `quick_validate.py`；`openspec validate --all --strict` 为 27 passed、0 failed；`openspec doctor` 正常；`git diff --check` 通过。
- [x] 3.2 复核最终 diff，确认只包含 skill、工作流文档和本 change，不包含产品代码或敏感数据。
- [x] 3.3 记录当前会话无法热刷新 skill 清单的残余风险及新会话补跑条件。

  当前会话启动时的可用 skill 清单不会热刷新；已同步 `~/.codex/skills`，新建或重启 Codex 会话后可用 `$grilling`，并可由 `$grill-me` 委托调用。仓库不实现 `/grilling` shell 命令。
