## ADDED Requirements

### Requirement: Web 与 Native 的会话恢复保持一致

Web 与 Native MUST 对保存的登录令牌使用相同的会话恢复结果语义：恢复期间不把未完成状态展示为未登录，认证失败进入登录，暂时性失败最多恢复 3 次；两端的恢复加载态文案 MUST 为「加载中...」。

#### Scenario: 两端均在恢复期间保持中性加载态

- **WHEN** Web 或 Native 启动时存在登录令牌且认证会话请求仍在进行
- **THEN** 对应平台 MUST 展示「加载中...」恢复加载态，不得展示登录表单

#### Scenario: 两端收到无效令牌

- **WHEN** Web 或 Native 收到 `authentication_required` 或 HTTP `401`
- **THEN** 对应平台 MUST 立即进入登录页面，不得把无效令牌当作暂时性网络失败重复请求

#### Scenario: 两端收到暂时性恢复错误

- **WHEN** Web 或 Native 的会话请求因网络或服务端暂时不可用而失败
- **THEN** 对应平台 MUST 自动恢复，最多总共发起 3 次请求；若仍失败则进入登录页面

#### Scenario: 两端没有保存令牌

- **WHEN** Web 或 Native 启动时没有保存的登录令牌
- **THEN** 对应平台 MUST 直接进入登录页面，且不得发起会话恢复请求
