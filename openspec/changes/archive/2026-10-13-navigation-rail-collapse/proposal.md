## Why

在桌面侧栏中点击导航项后，焦点会留在侧栏，导致鼠标移出后侧栏仍保持展开。悬停展开时点击顶部收起箭头也会把侧栏固定为展开状态，无法执行可见的收起操作。

## What Changes

- 恢复鼠标悬停展开的临时性：鼠标点击产生的焦点不阻止离开后收起。
- 悬停展开时，点击顶部收起控制立即收起侧栏；指针离开侧栏和账号面板后恢复悬停展开。
- 保留键盘焦点导航及无悬停设备的点击展开/收起能力。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `shared/cross-platform-presentation`：明确悬停、点击焦点和侧栏收起控制的优先关系。

## Impact

- 影响 Compose 共享应用外框的侧栏状态处理及 Compose Web 浏览器回归用例。
- Web、Android、iOS 共用 `commonMain`；页面、路由、账号操作与业务数据不变。
- 无 API、持久化、依赖或数据库变更。回滚可恢复到当前侧栏交互实现。
