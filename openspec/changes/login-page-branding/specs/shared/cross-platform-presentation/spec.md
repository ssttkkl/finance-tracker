## ADDED Requirements

### Requirement: 登录与注册页面呈现一致品牌和模式对应的密码提示

Web、Android 和 iOS 的认证页面 MUST 在表单上方显示品牌名「Finance Tracker」，且不得显示「工作区访问」标签。登录模式 MUST 不显示密码最小长度提示；注册模式 MUST 显示「至少 12 个字符」提示。两种模式 MUST 保持既有密码校验和认证结果语义不变。

#### Scenario: 使用者打开登录模式

- **WHEN** 未认证使用者在 Web、Android 或 iOS 打开登录页
- **THEN** 页面 MUST 显示品牌名「Finance Tracker」，且不得显示「工作区访问」或「至少 12 个字符」
- **AND** 页面 MUST 保留现有登录字段、校验结果和认证行为

#### Scenario: 使用者切换到注册模式

- **WHEN** 使用者从登录模式切换到注册模式
- **THEN** 页面 MUST 保持品牌名「Finance Tracker」并显示「至少 12 个字符」
- **AND** 注册密码校验 MUST 继续要求至少 12 个字符
