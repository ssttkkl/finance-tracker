## Why

Web 构建中关闭流水详情或导入弹层后，主画布与可访问性树会变空，用户需要额外点击/按键才可能恢复，严重时一直空白。该问题在干净 `HEAD=907fe4c2` 上同样复现，headed/headless 均可触发，属于既有 Compose Web 模态卸载/重绘缺陷，阻断“打开弹层再关闭”这条 Web QA。

## What Changes

> 已实现：Web 使用同树覆盖层，Native 保持 `Dialog`。

- 为 Web 模态建立最小复现：只保留一个空 `Dialog` 的打开/关闭，确认是否与业务内容无关。
- 确认触发边界：仅 `Dialog`、还是 `Popup`/`ModalBottomSheet`/选择器浮层也受影响；仅 demo 构建还是所有 Web 构建。
- 选择并验证修复方向：Web 专用同树 overlay、替换为 `Popup`、调整 `Dialog` 生命周期，或升级/规避 Compose 版本缺陷。
- 补 Web E2E：打开详情/导入→关闭→列表仍可见且可交互；覆盖返回键、遮罩点击、关闭按钮。
- 不改变原生端模态行为；不重做页面视觉或信息架构。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。这是既有行为的回归修复，不新增或改变业务契约；`.openspec.yaml` 使用 `skip_specs: true`。

## Impact

- **客户端**：`CashRecordModalPanel`、导入任务全屏 `Dialog`、`AlertDialog`/选择器浮层等 Web 模态路径。
- **测试**：新增 Web E2E 关闭路径；必要时增加最小 Compose Web 复现页面/测试。
- **平台**：只影响 Web 渲染/焦点恢复；Android/iOS 不改行为，只做相称编译验证。
- **风险**：模态实现替换可能影响遮罩、焦点陷阱、返回键、滚动锁定和可访问性；需要逐项回归。
- **非目标**：不改业务状态机、写入协议、接口或数据；不解决其他 Web 画布问题。
