# 任务：Web 模态关闭后画布空白

> 已实现。根因定位为 Compose Web `Dialog` 卸载时主画布被移除；Web 改为同树覆盖层。

## 1. 复现与边界

- [x] 1.1 在 demo/offline 构建复现详情、导入、分类确认和紧凑筛选的关闭路径；非 demo API 构建未单独复现（默认 QA 范围）。
- [x] 1.2 Chrome for Testing 1440×900 与 390×844 复核；Firefox/Safari 未运行。
- [x] 1.3 最小空 `Dialog` 未单独搭建；通过现有组件对照定位到 `Dialog` 卸载与画布移除。
- [x] 1.4 覆盖 `CashRecordModalPanel`、导入全屏 `Dialog`、`AlertDialog`、紧凑 `ModalBottomSheet`；选择器浮层未单独覆盖。

## 2. 根因与方案

- [x] 2.1 确认 `Dialog` 关闭后 `#compose-viewport` 仍在但画布/语义树被移除，`resize`/`focus` 无法恢复。
- [x] 2.2 对比干净 `HEAD=907fe4c2`，确认既有问题，不是业务状态或本次 UI 改动。
- [x] 2.3 选定 Web 同树覆盖层：详情/导入/Alert/紧凑筛选在 Web 不走 `Dialog`；Native 保持原 `Dialog`。
- [x] 2.4 记录焦点/遮罩/返回键/滚动锁定影响：遮罩点击、Escape、关闭按钮保留；未实现完整焦点陷阱，列为残余。

## 3. 实施

- [x] 3.1 `CashRecordModalPanel` Web 分支直接渲染覆盖层 + scrim；Native 保持 `Dialog`。
- [x] 3.2 `CashImportTaskScreen` Web 分支直接渲染全屏 `Surface`；Native 保持 `Dialog`。
- [x] 3.3 新增 `FinanceAlertDialog`，替换 11 处 `AlertDialog` 调用；Web 同树渲染，Native 委托 M3。
- [x] 3.4 紧凑筛选 Web 分支改为底部同树 `Surface`；Native 保持 `ModalBottomSheet`。
- [x] 3.5 不改变业务状态机、弹层内容、按钮层级和 Snackbar 行为。

## 4. 测试与 QA

- [x] 4.1 新增 Web E2E：详情关闭、导入关闭、分类弹窗取消、紧凑筛选应用后 `ledger.screen` 仍可见；`compose-core-ui-workflows.e2e.ts` 通过。
- [x] 4.2 Native 分支仍走原 `Dialog`；共享编译与 Android 编译通过。
- [x] 4.3 1440×900 与 390×844 冒烟；控制台仅有既有 `wasmExports` 弃用提示。
- [x] 4.4 `:shared:wasmJsTest`、`:webApp:wasmJsBrowserDistribution`、`:shared:compileAndroidMain` 通过。
- [x] 4.5 `git diff --check` 通过；残余：未做 Safari/Firefox、非 demo API 构建、完整焦点陷阱。

## 5. 发布

- [x] 5.1 独立复核结论：同树覆盖层是 Web 专用分支，Native 行为不变；遮罩/关闭路径已覆盖。
- [x] 5.2 最终 diff 复核；未运行项与残余风险已记录。
- [x] 5.3 功能地图无需改页面或能力：仅 Web 模态呈现实现，不改变用户任务。
- [x] 5.4 已提交 `cbf35406` 并推送。
