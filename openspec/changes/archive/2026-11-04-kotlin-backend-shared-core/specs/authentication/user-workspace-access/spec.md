## ADDED Requirements

### Requirement: 注册登录凭据只接受字符串

注册和登录的 JSON 请求中，`email` 与 `password` 一旦提供 MUST 是字符串。系统 MUST 拒绝数字、布尔、数组、对象和显式 `null`，不得用字符串转换使其成为有效凭据。缺省字段继续按现有空字符串语义处理，字符串邮箱规范化、密码规则、未知账户保护和令牌合同保持不变。

#### Scenario: 非字符串凭据
- **WHEN** 注册或登录的 `email` 或 `password` 明确为非字符串 JSON 值
- **THEN** 系统 MUST 返回 `400` 与既有安全错误形状，错误码为 `invalid_credentials`，且不得创建用户或认证会话

#### Scenario: 正常字符串及缺省
- **WHEN** 请求使用字符串凭据或省略某个凭据字段
- **THEN** 系统 MUST 按现有邮箱/密码与空字符串规则验证，不因本次类型收紧改变其他字段或暴露账户是否存在
