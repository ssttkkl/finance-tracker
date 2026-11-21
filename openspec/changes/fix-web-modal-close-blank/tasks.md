# 任务：Web 模态关闭后画布空白

> 本 change 只登记问题与实施计划，当前不修改代码、不提交实现。

## 1. 复现与边界

- [ ] 1.1 在非 demo（API fixtures 或生产预览）构建复现“打开详情→关闭”和“打开导入→关闭”，确认是否与 demo/offline 有关。
- [ ] 1.2 在 Chrome/Firefox/Safari 目标版本复核；记录 headless/headed、GPU/SwiftShader 差异。
- [ ] 1.3 用最小页面（一个空 `Dialog` 的开关）复现，排除业务内容与网络状态影响。
- [ ] 1.4 覆盖 `CashRecordModalPanel`、导入全屏 `Dialog`、`AlertDialog`、`ModalBottomSheet`、选择器浮层，记录哪些触发、哪些不触发。

## 2. 根因与方案

- [ ] 2.1 检查 `DialogProperties`、`usePlatformDefaultWidth=false`、`key(...)`、overlay 组合与 `Dialog` 卸载时序。
- [ ] 2.2 对比 Compose Multiplatform / Skiko 版本变更，确认是否为已知缺陷。
- [ ] 2.3 对候选方案做最小实验：Web 同树 overlay / `Popup` / 生命周期规避 / 版本升级。
- [ ] 2.4 记录选定方案、放弃方案、焦点/遮罩/返回键/滚动锁定影响。

## 3. 实施

- [ ] 3.1 仅在 Web 分支切换模态实现；Android/iOS 保持现有 `Dialog`。
- [ ] 3.2 补遮罩、焦点陷阱/恢复、Escape/返回键、滚动锁定和 pane title 语义。
- [ ] 3.3 不改变业务状态机、弹层内容、按钮层级和 Snackbar 行为。

## 4. 测试与 QA

- [ ] 4.1 新增 Web E2E：详情关闭、导入关闭、遮罩点击、Escape、返回键；关闭后列表可见且可点击。
- [ ] 4.2 若使用 Web 专用分支，断言非 Web 仍走原 `Dialog`（共享测试或平台分支测试）。
- [ ] 4.3 Web 代表视口（390/1024/1440）冒烟，记录控制台/网络错误。
- [ ] 4.4 `:shared:wasmJsTest`、Web 构建、共享编译通过；Android/iOS 至少做相称编译。
- [ ] 4.5 `git diff --check`、最终 diff 复核；记录未运行项与残余风险。

## 5. 发布

- [ ] 5.1 更新 `tasks.md` 证据与 `HEAD`；按需更新 README/功能地图。
- [ ] 5.2 提交并推送；归档前确认无 blocking finding。
