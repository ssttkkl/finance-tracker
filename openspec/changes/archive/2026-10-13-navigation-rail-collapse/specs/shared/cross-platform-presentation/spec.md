## MODIFIED Requirements

### Requirement: Authenticated Compose destinations use one shared page-title hierarchy

所有已登录 Compose 路由 MUST 由同一应用外框组织一级导航、页面标题、账号操作和路由内容。收支账本、账单导入、收支分类、当前持仓、投资事件和工作区管理 MUST 保持为六个同级入口，不得按类别嵌套为二级菜单。`REGULAR` 和 `WIDE` MUST 默认显示可展开的图标侧栏；支持鼠标悬停时，指针进入侧栏 MUST 自动展开，离开侧栏及账号面板后 MUST 自动收起。鼠标点击导航项造成的焦点 MUST NOT 阻止指针离开后收起。侧栏已展开时，激活顶部控制 MUST 立即收起侧栏，并在指针或键盘焦点仍处于侧栏/账号面板内时抑制自动展开；指针和焦点离开后 MUST 恢复悬停展开。无悬停能力的设备 MUST 保留通过控制点击展开或收起的能力；键盘焦点仍 MUST 展开侧栏以支持键盘导航。悬停头像 MUST 打开账号面板；鼠标进入面板后 MUST 保持打开，离开头像与面板后 MUST 关闭。`COMPACT` MUST 使用可从顶栏打开的模态抽屉。三个窗口等级 MUST 在顶栏显示当前路由的唯一页面标题，正文 MUST 不重复该路由标题。身份与工作区操作 MUST 从侧栏底部的头像入口访问，顶栏 MUST 不再放置工作区切换或退出登录入口。头像 MUST 使用会话邮箱首字母，不得伪装成可上传或编辑的个人头像。

#### Scenario: Open a main destination at any window size

- **WHEN** 使用者在任一逻辑窗口等级打开收支账本、账单导入、收支分类、当前持仓、投资事件、工作区管理或已登录邀请页面
- **THEN** 页面 MUST 显示当前路由标题，且六个导航目的地 MUST 作为同级入口展示
- **AND** 页面 MUST 保留该路由需要的导航与正文内容
- **AND** 标题 MUST 只显示一次

#### Scenario: Navigate on regular and wide windows

- **WHEN** 使用者在 `REGULAR` 或 `WIDE` 窗口首次进入已登录页面
- **THEN** 侧栏 MUST 默认收起并显示全部一级目的地的图标
- **AND** 展开操作 MUST 显示每个目的地的文字，收起操作 MUST 恢复图标侧栏
- **AND** 导航切换 MUST 更新选中项和顶栏页面标题
- **AND** 图标项 MUST 提供可感知的名称与焦点样式

#### Scenario: Expand the rail and account panel on hover

- **WHEN** 使用者在支持悬停的 `REGULAR` 或 `WIDE` 窗口将鼠标移入侧栏任意区域
- **THEN** 侧栏 MUST 自动展开并显示目的地名称
- **AND** 鼠标离开侧栏及其账号面板后，侧栏 MUST 自动收起
- **AND** 鼠标悬停底部头像 MUST 自动打开账号面板
- **AND** 鼠标从头像移入账号面板时，面板 MUST 保持打开；离开侧栏和面板后，面板 MUST 关闭
- **AND** 触屏 MUST 可通过侧栏控制按钮展开或收起；键盘 MUST 可通过聚焦和激活相同操作

#### Scenario: Mouse navigation does not keep the rail expanded

- **WHEN** 使用者用鼠标点击侧栏目的地后将指针移出侧栏和账号面板
- **THEN** 侧栏 MUST 自动收起，即使点击产生的焦点仍留在目的地上
- **AND** 使用键盘移动焦点时，侧栏 MUST 在焦点位于侧栏内时保持展开

#### Scenario: Collapse a hover-expanded rail with its control

- **WHEN** 使用者在侧栏因悬停展开时激活顶部收起控制
- **THEN** 侧栏 MUST 立即收起
- **AND** 指针或键盘焦点仍处于侧栏/账号面板内时，侧栏 MUST 保持收起
- **AND** 指针与焦点离开后，再次将鼠标移入侧栏 MUST 恢复自动展开

#### Scenario: Navigate on compact windows

- **WHEN** 使用者在 `COMPACT` 窗口打开导航抽屉
- **THEN** 抽屉 MUST 以同一级顺序列出全部六个目的地及其可见名称
- **AND** 抽屉 MUST 在底部提供头像入口，且关闭、打开和选择目的地时不得遮挡页面标题或造成横向溢出

#### Scenario: Open the account and workspace panel

- **WHEN** 使用者点击侧栏底部头像
- **THEN** 账号操作面板 MUST 显示当前认证邮箱、可访问工作区列表、活动工作区标记与每个工作区的角色
- **AND** 面板 MUST 提供工作区管理入口和退出登录操作
- **AND** 使用者选择另一工作区时 MUST 沿用现有工作区访问、路由更新和错误处理语义
- **AND** 面板 MUST 在当前窗口内完整可见，并支持键盘聚焦、激活、Escape 关闭及焦点返回头像

#### Scenario: Return, switch workspace, or sign out

- **WHEN** 使用者打开任一已登录页面或账号操作面板
- **THEN** 适用时的返回操作、工作区切换、工作区管理、退出登录和响应式导航 MUST 继续可用
- **AND** 退出登录 MUST 沿用现有会话清除与登录入口语义
- **AND** 宽屏导航与内容、窄屏导航与内容以及账号操作面板 MUST 不发生遮挡、焦点丢失或横向溢出
