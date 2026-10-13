## Why

在头像上停留时，账号面板会反复打开和关闭，导致工作区切换和退出入口无法稳定使用。现有交互合同已经要求悬停头像打开面板，并在指针离开头像与面板后才关闭；侧栏展开动画不应改变这一行为。

## What Changes

- 将悬停打开的账号面板纳入侧栏的临时悬停区域，避免侧栏在菜单打开时先收起并导致菜单关闭。
- 明确区分悬停打开与点击/键盘打开：悬停菜单不抢鼠标焦点，键盘打开菜单时仍可用 Escape 关闭。
- 为聚焦后的头像入口补上 Enter 和 Space 激活，并以浏览器回归验证悬停、键盘、响应式抽屉和工作区错误状态。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `shared/cross-platform-presentation`：补充桌面侧栏展开期间账号面板悬停状态稳定的行为。

## Impact

- 影响 Compose 共享应用外框和 Compose Web 浏览器回归。
- Web、Android、iOS 共用 `commonMain`；指针悬停语义只适用于具备悬停能力的设备。
- 无路由、业务数据、API、持久化、依赖或数据库变更。回滚可恢复原菜单锚点与状态实现。
