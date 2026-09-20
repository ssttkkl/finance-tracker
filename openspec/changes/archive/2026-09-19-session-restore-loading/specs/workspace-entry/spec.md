## ADDED Requirements

### Requirement: 会话恢复期间不展示登录表单

Web MUST 根据本机是否存在登录令牌区分直接登录和会话恢复：有令牌时，在认证会话确认完成前 MUST 展示中性的「加载中...」恢复加载态；没有令牌时 MUST 直接展示登录表单。

#### Scenario: 刷新时等待已保存会话

- **WHEN** 使用者刷新页面且浏览器存在登录令牌，但会话请求尚未完成
- **THEN** Web MUST 展示「加载中...」，不得展示邮箱、密码、登录或注册控件

#### Scenario: 没有登录令牌时启动

- **WHEN** 使用者打开页面且浏览器没有登录令牌
- **THEN** Web MUST 直接展示登录表单，且不得为恢复会话发起请求

#### Scenario: 登录令牌无效

- **WHEN** 会话请求返回 `authentication_required` 或 HTTP `401`
- **THEN** Web MUST 停止恢复并展示登录表单

#### Scenario: 暂时性恢复失败后成功

- **WHEN** 前两次会话请求因网络或服务端暂时失败，第三次请求成功
- **THEN** Web MUST 进入认证会话对应的工作区页面，且整个恢复过程最多发起 3 次请求

#### Scenario: 三次恢复均失败

- **WHEN** 3 次会话请求均因网络或服务端暂时失败
- **THEN** Web MUST 停止恢复并展示登录表单，且不得展示工作区内容
