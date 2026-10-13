## ADDED Requirements

### Requirement: Persistent Compose navigation focus indication follows input modality

桌面 Compose 导航侧栏中的目的地通过键盘导航获得焦点时 MUST 显示焦点边框。目的地通过鼠标或触屏点击获得焦点时 MUST NOT 显示焦点边框；选中状态 MUST 保持可见。

#### Scenario: Pointer activation does not leave a destination outline

- **WHEN** 使用者在展开或收起的桌面侧栏中点击一个目的地
- **THEN** 选中的目的地 MUST NOT 留下蓝色焦点边框
- **AND** 选中标记和路由切换 MUST 保持正常

#### Scenario: Keyboard focus retains the destination outline

- **WHEN** 使用者在展开或收起的桌面侧栏中通过键盘将焦点移动到一个目的地
- **THEN** 该目的地 MUST 显示蓝色焦点边框，直到焦点移到其他位置
