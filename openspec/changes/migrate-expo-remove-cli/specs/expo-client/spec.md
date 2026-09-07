## Purpose

为 Android 和 iOS 提供与现有 Web 使用同一工作区、同一账本和同一服务端规则的 Expo 客户端，
并把可复用的客户端契约与纯业务逻辑沉淀到跨平台边界，避免平台差异改变财务结果。

## ADDED Requirements

### Requirement: Native client uses the existing account and workspace contract

Android 和 iOS 客户端 MUST 通过现有 HTTP API 完成认证、工作区访问和账本操作，并 MUST 遵守服务端返回的
工作区角色、鉴权失败和冲突错误；客户端不得直接访问数据库或绕过 Application Service。

#### Scenario: A member signs in on a mobile device

- **WHEN** 已注册用户在 Android 或 iOS 客户端提交有效凭据
- **THEN** 客户端建立会话，展示该用户可访问的工作区，并且读取到的账户、现金流水和投资数据与 Web 在同一工作区下的结果一致

#### Scenario: A viewer attempts a write

- **WHEN** 只有 `viewer` 角色的工作区成员在客户端提交记账、编辑、导入或关系确认操作
- **THEN** 服务端拒绝写入，客户端展示可理解的权限错误，并保持本地账本状态不变

### Requirement: The first mobile vertical slice supports core cash-ledger tasks

客户端 MUST 提供登录、工作区切换、收支账本浏览、手工现金流水创建、凭证入口、账单导入和关系审查的可达流程；
未实现的次级功能 MUST 明确显示为暂不可用，不得伪装成功或写入不完整数据。

#### Scenario: A user records a cash flow

- **WHEN** 具备写权限的成员在移动端填写账户、金额、币种、时间和流水类型并提交
- **THEN** 客户端以服务端确认的结果刷新收支账本；金额以精确十进制字符串传输和展示，不使用二进制浮点产生账务结果

#### Scenario: A user opens a statement import

- **WHEN** 用户从移动端选择账单文件并开始导入
- **THEN** 客户端进入导入会话，展示解析、映射、重复候选、配对建议和导入结果状态；账单密码只在当前会话内使用，不写入持久化存储

#### Scenario: A mobile import request is retried

- **WHEN** 网络重试导致同一导入确认请求被提交一次以上
- **THEN** 服务端按现有导入幂等合同处理，客户端只展示一个最终导入结果，不产生重复账本记录或重复关系

### Requirement: Platform adapters preserve security and input semantics

Native 客户端 MUST 将会话凭据保存于平台安全存储，并通过系统文件选择能力取得账单文件；Web 客户端继续使用浏览器适配器。
任何平台都 MUST 不把会话凭据、账单密码或原始账单内容写入普通日志、共享业务状态或不受控的持久化缓存。

#### Scenario: A native session is reopened

- **WHEN** 用户关闭并重新打开 Android 或 iOS 客户端
- **THEN** 客户端从平台安全存储恢复会话或要求重新认证，且不会把凭据写入普通文本存储或日志

#### Scenario: A file picker is cancelled

- **WHEN** 用户在任一平台打开账单选择器后取消选择
- **THEN** 导入会话保持未开始状态，不上传文件、不写入账本，也不显示导入失败

### Requirement: Server remains the source of truth for online writes

客户端 MUST 采用 online-first 写入：提交失败、超时、版本冲突或权限变化时 MUST 明确显示失败并重新读取服务端状态，
不得以本地缓存冒充已提交结果；本能力不承诺离线写入、离线队列或自动冲突合并。

#### Scenario: A write times out

- **WHEN** 客户端提交记账或导入确认后在规定时间内未收到服务端响应
- **THEN** 客户端把结果标记为未确认，允许用户重新读取服务端状态或安全重试，并不直接把本地草稿标记为已入账
