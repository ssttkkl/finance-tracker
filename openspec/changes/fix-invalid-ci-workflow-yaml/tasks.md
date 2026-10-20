## 1. 思考

- [x] 1.1 根因确认：`run:` plain scalar 中的 `: ` 让 YAML 解析失败；范围限定为两份 workflow 的四条 grep 命令。
- [x] 1.2 仓库要求的 `grill-me` 调用接口未在当前工具中提供；用户已明确要求同一 PR 修复并推送验证，无待定范围决策。

## 2. 计划

- [x] 2.1 记录无产品行为变化，使用 `skip_specs: true`；不改触发条件、job、检查内容或应用代码。
- [x] 2.2 决定用 `>-` block scalar 包裹命令，并验证解析结果与原 shell 文本一致。

## 3. 任务拆分与一致性

- [x] 3.1 覆盖两份 workflow 的四条命令及 YAML 语法回归验证。
- [x] 3.2 跨平台影响检查：Web、Native、共享层均无影响；本次只修 CI 配置解析，不改 UI、用户操作或产品文案。

## 4. 构建与实现

- [x] 4.1 将 `pr-checks.yml` 与 `compose-ci.yml` 中四条命令改为 YAML block scalar，保持命令文本不变。

## 5. 审查

- [x] 5.1 复核 diff 仅包含两份 workflow 的 YAML 修复及本变更记录；触发和 job 配置保持原样。

## 6. 测试与 QA

- [x] 6.1 修复前使用 Ruby YAML parser 复现失败：`pr-checks.yml:59` 与 `compose-ci.yml:62` 均报 `mapping values are not allowed in this context`。
- [x] 6.2 修复后解析 `.github/workflows/*.yml` 全部文件，并断言两份 workflow 中四个 `run` 值与原命令完全相同；通过。
- [x] 6.3 `openspec validate --all --strict`：33 项通过；`openspec doctor`、本变更严格校验和 `git diff --check` 通过。基线 HEAD：`840eaa14d8ea73f7d4bffd417301568d74588fae`。

## 7. 发布

- [ ] 7.1 提交并推送至 PR #92 的 `wet-turkey` 分支。
- [ ] 7.2 检查 GitHub Actions run 是否越过 YAML 加载阶段并创建实际 jobs；记录 run URL、HEAD 与结果。

## 8. 反思

- [ ] 8.1 根据远端验证记录最终结论，并评估是否需要增加通用 workflow lint 规则。
