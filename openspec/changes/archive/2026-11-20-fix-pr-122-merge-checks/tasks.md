## 1. 思考

- [x] 1.1 确认 PR #122 与最新 `main`（含 PR #123 Worker 迁移）冲突，读取冲突文件、双方规格和测试失败记录。
- [x] 1.2 按 C 类合并检查修复处理：冲突处置与测试时序调整，不改变生产业务语义。

## 2. 计划

- [x] 2.1 逐文件合并冲突，保留双方合同；对自动合并的测试与启动断言做语义复核。

## 3. 任务拆分

- [x] 3.1 先复现启动 E2E 3 项与管理目录 1 项失败，再分别修正断言与动画等待。

## 4. 构建

- [x] 4.1 完成 `main` 合并并提交冲突处置。
- [x] 4.2 调整启动兼容性断言、管理目录标记等待、Worker 故障触发与导入账户步骤重试。

## 5. 审查

- [x] 5a 复核冲突处置、合并后规格一致性及最终 diff；未发现阻断项。
- [x] 5b 生产契约与 UI 行为未变，契约/UI 专项不适用。

## 6. 测试与 QA

- [x] 6.1 `:shared:wasmJsTest` 通过 1045 项、0 失败。
- [x] 6.2 后端 CI 命令通过：`:backend:test :backend:codegenTest :business-contracts:jvmTest :business-domain:jvmTest :backend:ktlintCheck :backend:detekt :backend:installDist`。
- [x] 6.3 Web Node 检查 43 项、Demo Web 构建、Android Debug 构建、iOS 模拟器测试与共享 framework 链接通过。
- [x] 6.4 管理目录用例重复 4 轮、Worker 故障与账户步骤用例各重复 3 轮通过；完整 Chrome E2E 套件 64 项通过、3 项跳过、0 失败。
- [x] 6.5 OpenSpec 严格校验 31/31、`git diff --check` 通过。
- [ ] 6.6 PR #122 的必需远端检查重新运行并通过；首次推送的 E2E 暴露上述两项时序问题，已修正待重跑。

## 7. 发布

- [x] 7.1 本次没有用户可见文案、业务术语或数据格式变化；合并与测试等待不产生新的通用规则。
- [x] 7.2 已归档本 change 为 `2026-11-20-fix-pr-122-merge-checks`；合并与测试修复将随分支推送。
- [ ] 7.3 推送后等待 PR #122 的必需检查。

## 验证基线

分支 `pr122-merge-main`，代码基线 `9cf67a8ae0ce7c181f4fdc3f20ba3018e7db9ca1`，合入 `main`=`953f45b253845918a9b1ac2ddc73fbe791d1ac86`。2026-11-20 UTC 在本机完成上述验证；完整浏览器套件运行于 `http://127.0.0.1:5187/`。远端检查待推送后记录。
