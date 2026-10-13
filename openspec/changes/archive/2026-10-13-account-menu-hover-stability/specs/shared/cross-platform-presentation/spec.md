## ADDED Requirements

### Requirement: Desktop account menu hover remains stable during rail expansion

在支持悬停的 `REGULAR` 和 `WIDE` 窗口中，桌面账号面板 MUST 在指针停留于头像期间保持打开；侧栏 MUST 将悬停打开的账号面板视为同一临时悬停区域并保持展开。侧栏展开、收起或重新布局 MUST NOT 单独导致面板关闭。指针从头像进入账号面板时，面板 MUST 保持打开；只有指针离开头像和账号面板后，悬停打开的面板才 MUST 关闭。

#### Scenario: Keep the account panel open while the hovered rail expands

- **WHEN** 使用者将指针移到侧栏底部头像并保持不动，且侧栏正因悬停展开
- **THEN** 账号面板 MUST 持续打开，不得反复关闭和重新打开
- **AND** 账号面板处于悬停打开状态时，侧栏 MUST 保持展开
- **AND** 指针进入账号面板后，面板 MUST 保持打开
- **AND** 指针离开头像与账号面板后，面板 MUST 关闭

#### Scenario: Activate the account panel with the keyboard

- **WHEN** 使用者聚焦侧栏底部头像并按 Enter 或 Space
- **THEN** 账号面板 MUST 打开
- **AND** 使用者按 Escape 后，账号面板 MUST 关闭且焦点 MUST 返回头像
