## 1. 思考

- [x] 1.1 读取投资组合估值主规格、项目上下文、产品编码规范、领域词表及受影响测试与调用链；按 C 类归为既有合同的测试时序修复，不改生产估值行为。
- [x] 1.2 用户确认范围仅限 `ignoredSingleQuoteDoesNotKeepProcessAlive` 失败及直接根因；失败位于输出断言，本机单次目标测试通过，怀疑 50 毫秒预算与异步 provider 启动竞态。

## 2. 计划

- [x] 2.1 建立轻量 change 记录；保持测试的行情状态、provider 已启动和 JVM 及时退出断言，不修改主规格或设计合同。

## 3. 任务拆分

- [x] 3.1 原始 50 毫秒设置本机重复 20 次均通过，未能复现 CI 偶发；CI 日志确认失败落在单行输出断言，支持 provider 未及时调度的时序判断。修改后目标用例重复 20 次通过。

## 4. 构建

- [x] 4.1 仅为单持仓测试的子进程传入 1 秒预算；批量测试仍用 50 毫秒，生产代码不变。

## 5. 审查

- [x] 5.1 最终 diff 仅改测试预算参数传递；现有 `BLOCKING_STARTED`、`partial` 与 8 秒 JVM 退出断言全部保留。
- [x] 5.2 未改业务契约或 UI；契约与 UI 专项不适用。双后端与性能专项不适用，因为没有运行时、存储或查询行为变更。

## 6. 测试与 QA

- [x] 6.1 原设置目标测试连续 20 次通过但未重现；修改后目标测试连续 20 次通过。CI 同范围后端命令通过：870 项 backend 测试、codegenTest、business-contracts:jvmTest、business-domain:jvmTest、ktlintCheck、detekt、installDist；OpenSpec `--all --strict` 为 27/27、doctor 与 `git diff --check` 通过。存储、迁移与数据库查询未变，本地不运行 PostgreSQL 矩阵。
- [ ] 6.2 PR #115 的所有必需检查重新运行并通过；Web 与 Native 源码未变，不重复本地前端／设备验证。

## 7. 发布

- [x] 7.1 本次只改内部测试预算，没有用户文案、业务术语、部署或运行说明变化；用户决策是限定修复到 CI 单测失败及其直接根因，没有值得沉淀的通用规则。
- [ ] 7.2 归档本 change 后按既有授权提交并推送 PR #115 更新；CI 全通过后按用户授权合入 `main`。

验证证据与时间在完成后原位记录于本文件；本地单测一次通过不能替代重复执行及最终 PR CI。

当前本地验证基线：分支 `feat/compose-web-wasm-loading-animation`，比较基线 `origin/main`=`d63ecdae9a239bfc0a8434526542e1ad026e4e77`；修复前 HEAD `4a552792e335be6acf075abe59c68af9e7af6315`。修复后目标用例 20/20 于 2026-11-06 UTC 16:39 左右通过；完整后端命令 2026-11-06 16:43 UTC 完成，Gradle 报告 29 项 actionable tasks、BUILD SUCCESSFUL（2m12s）；OpenSpec 严格校验、doctor 与 diff 检查于 16:42 UTC 通过。待提交 SHA 和更新后 PR CI 结果在发布阶段记录。
