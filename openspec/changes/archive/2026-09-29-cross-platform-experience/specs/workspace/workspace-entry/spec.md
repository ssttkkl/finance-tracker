## ADDED Requirements

### Requirement: Native workspace entry follows the Web access presentation

Android 和 iOS 的认证、工作区选择及工作区创建入口 MUST 使用 Web 已验证的标题、字段标签、权限文案、加载/错误状态和成功后的导航语义。Native 可以使用系统输入控件和导航过渡，但不得把工作区访问失败误显示成无工作区或把未完成创建标记为成功。

#### Scenario: Restore an existing workspace on Native

- **WHEN** Native 恢复会话并收到至少一个可访问工作区
- **THEN** 客户端 MUST 使用活动工作区或按 Web 规则选择第一个工作区后进入收支账本
- **AND** 选择失败时 MUST 显示可重试的工作区访问错误

#### Scenario: Create a workspace on a narrow or wide device

- **WHEN** 已认证用户在 Native phone、tablet 或 iPad 大窗口创建工作区
- **THEN** 表单 MUST 保持 Web 的字段、创建动作、禁用状态和错误语义
- **AND** 布局 MUST 按窗口宽度适配，不得出现横向滚动或只在某一设备型号可用
