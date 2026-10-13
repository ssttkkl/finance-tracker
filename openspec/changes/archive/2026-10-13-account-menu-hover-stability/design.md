## Context

已有规格和已确认原型要求：桌面悬停底部头像打开账号面板，指针从头像移入面板时保持打开，离开两者后关闭。当前悬停菜单状态只在 `DesktopAccountMenu` 内部维护，外层 shell 只根据侧栏和面板 hover 状态展开。头像打开菜单后，侧栏可能先收起；这个重排会使头像/菜单 hover 状态丢失并关闭菜单，指针仍停留在头像时又重新打开，形成循环。修复前的视觉回归在现有 5186 Compose Web 预览中稳定复现：1045×1000 下菜单打开帧与 75 ms 后的头像菜单区域截图不同；无障碍树会残留隐藏菜单节点，因此回归使用截图检查可见状态。

## Goals / Non-Goals

**Goals:**

- 在桌面 shell 和账号菜单之间同步“悬停打开”状态；菜单打开时把侧栏保留在展开状态。
- 让指针停在头像上时菜单稳定打开；指针进入面板后保持打开；只有离开头像和面板后才关闭。
- 让聚焦后的头像按钮通过 Enter 和 Space 打开菜单；保留点击打开、点击外部关闭、Escape 关闭和 compact 底部抽屉/面板行为。

**Non-Goals:**

- 不改变侧栏、头像、菜单内容、路由、视觉 token 或响应式信息架构。
- 不增加持久用户偏好或更改账户/工作区行为。

## Decisions

- 先用生产 Compose Web 预览添加并运行定点悬停稳定性回归，验证菜单可见状态在侧栏动画及停留期间不抖动。
- 由 `DesktopAccountMenu` 向 shell 报告 hover-open 状态，作为独立的临时展开原因；关闭或指针离开后的 hover-open 延迟结束时清除此状态。
- 将 hover sensor 放在固定的头像槽位上，使其命中区域不随侧栏宽度变化。
- hover-open 菜单使用非焦点 Popup，避免弹出层打开时丢失鼠标 hover；click-open 菜单使用可聚焦 Popup。
- 头像按钮在 KeyDown 阶段处理 Enter 和 Space，并消费该事件，确保键盘激活可用且不会和按钮自身处理重复触发。
- 保持点击打开状态独立，避免把点击打开的永久状态误当作悬停状态；原有 Escape、点击外部、键盘和 compact 行为不变。
- 复用已批准的 [app-shell-icon-navigation 原型](../2026-10-12-app-shell-icon-navigation/prototype/index.html)。该缺陷修复不改变既有布局、视觉层级或交互信息架构，因此不创建新原型。

## Risks / Trade-offs

- [shell 与菜单间的 hover-open 状态同步可能滞留] → 只在菜单内部的 hover-open 状态为真时展开 rail；关闭与离开宽限期结束均复位，并在浏览器验证。
- [鼠标 hover 与键盘焦点可能相互影响] → 仅 hover-open Popup 非焦点；click-open Popup 保持可聚焦；浏览器分别验证两种打开方式、Escape 关闭和焦点返回。
- [显式按键处理与按钮默认行为可能重复触发] → KeyDown 事件被消费；浏览器分别验证 Enter 和 Space 各只打开一次。

## Migration Plan

无迁移。通过回退本次应用外框和测试改动恢复既有行为。

## Open Questions

无。头像悬停与离开的预期行为已在当前主规格和先前确认的交互方案中明确。
