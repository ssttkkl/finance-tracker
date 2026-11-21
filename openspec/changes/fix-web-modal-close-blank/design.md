# 设计：Web 模态关闭后画布空白

## 现状证据

- 环境：Chrome for Testing（Playwright chromium-1234），1440×900，demo/offline，URL `http://127.0.0.1:5186/w/demo-workspace/`。
- 复现 A：打开 `ledger.record-projection-audit-single`，点击 `ledger.record-panel.close`。`cash.current.detail` 与 `ledger.screen` 都变为 0，`[id]` 只剩 `compose-viewport`、`cmp_a11y_root`，画布空白；再点击/按 Tab 有时恢复。
- 复现 B：打开导入 `cash.import.dialog`，点击 `cash.import.close`，同样空白。
- 对照：干净 `HEAD=907fe4c2` 的 worktree 构建同样复现；headed 与 headless 均复现。控制台只有既有 `wasmExports` 弃用提示，没有 JS 异常。
- 现有 Web E2E 没有“打开弹层再关闭”的断言，因此未拦截。

## 影响面假设

优先验证是否所有 `Dialog` 卸载都触发，还是只有特定组合：

1. `CashRecordModalPanel` 的 `Dialog`（详情、编辑、导入子面板）。
2. `CashImportTaskScreen` 的全屏 `Dialog`。
3. `AlertDialog`（删除/批量分类）与 `ModalBottomSheet`（紧凑筛选）。
4. 选择器浮层（账户/分类/期间）关闭。

如果所有 `Dialog` 都触发，问题在 Compose Web 的模态窗口生命周期；如果只有 `CashRecordModalPanel` 触发，优先检查其 `DialogProperties`、`usePlatformDefaultWidth=false`、`key(...)` 和 overlay 结构。

## 候选修复

| 方案 | 做法 | 风险 |
|---|---|---|
| A. Web 同树 overlay | Web 不用 `Dialog`，把 `CashRecordModalOverlay` 直接渲染在页面树上，补 scrim/focus | 焦点陷阱、返回键、滚动锁定要自己实现；原生不变 |
| B. 换 `Popup` | Web 用 `Popup` 承载模态内容 | 尺寸/定位/遮罩行为与 `Dialog` 不同，可能仍有重绘问题 |
| C. 生命周期规避 | 延迟卸载/保留空 `Dialog`/手动请求重绘 | 可能只是掩盖，不确定稳定 |
| D. 升级/规避 Compose | 升级 Compose Multiplatform 或锁定已知可用版本 | 影响面大，需要完整回归 |

先做 A/B 的最小实验，不直接改生产路径；只有确认能稳定恢复且不破坏焦点/返回键才落地。

## 验收

- 打开详情/导入→关闭后，`ledger.screen` 仍可见、可点击，画布不空白。
- 关闭按钮、遮罩点击、Escape/返回键三条路径都通过。
- 原生 Android/iOS 行为不变；至少做共享编译与原有单测。
- 新增 Web E2E 覆盖关闭路径；如果修复是 Web 专用分支，测试要断言非 Web 仍走原 `Dialog`。

## 未决问题

- 非 demo（API fixtures/生产预览）构建是否同样复现？需要补一次真实 API 构建验证。
- 是否与具体 Compose 版本/浏览器 GPU/SwiftShader 有关？需要在目标浏览器矩阵复核。
