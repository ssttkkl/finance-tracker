# 设计：工作区同名提示与现金字段错误

## 工作区创建同名提示

- 数据源：当前会话已加载的 `session.workspaces` 名称列表；比较前 `trim()`，拉丁字母按大小写不敏感。
- 交互：点击创建时先比较；命中同名则展示确认对话框“已有同名工作区，仍要创建？”，按钮为“取消/仍要创建”。确认后调用原创建命令；取消关闭对话框并保留输入。
- 不变量：允许同名，不加后端唯一约束；提示只是客户端提示。列表未加载/加载失败时不阻塞创建。
- 边界：首次创建没有已加载工作区，直接走原流程；列表可能过期，若服务端已有同名但本地列表没有，则不提示，属于可接受限制。
- 测试：同名确认、同名取消、无同名、列表未就绪、创建失败保留输入。

## 邀请链接

- 明确为无行为变化：每次点击“创建链接”生成一条新链接；不提示旧链接、不撤销旧链接、不提供旧链接管理。
- 服务端按既有 7 天有效期自然过期；旧链接从当前界面丢掉，不参与后续展示或复制。
- 因此本 change 不修改 `workspace/workspace-management` 规格，只记录决策，避免被误读为新增撤销/复用能力。

## 现金字段错误

### 错误模型

在现有 `errorCode`/`DomainFailure` 之上增加字段归属映射，例如 `FieldError(field, code, message)`。映射只覆盖可确定归属的错误：

- 金额：`invalid_amount`、`cash_amount_out_of_range`、分摊金额不一致；
- 币种：`invalid_currency`、账户不支持币种；
- 账户：`invalid_account`、`account_changed`、账户停用；
- 发生时间：`invalid_datetime`；
- 分类/分摊：既有 allocation/category 校验。

无法归属的网络、权限、服务端错误保持表单级 `StateMessage`。

### 呈现

- `CashRecordFieldInputs` 接受每个字段的 `FieldError?`，把 `isError`、`supportingText` 传给现有 `OutlinedTextField`/`ChoicePicker`；错误文案沿用既有 `ledgerErrorText`/本地化 key，不新增第二套文案。
- 新建/编辑/草稿三个入口共用映射和字段错误组件；草稿的“允许缺项”规则不变，只校验已填写值。
- 表单级错误继续使用现有 `cash.create.error`、`cash.entry.error`、`cash.draft.error`，仅承接无法归属的错误。

### 定位与清除

- 保存/预览失败后，按字段顺序（金额、币种、账户、时间、分类、分摊）找到首个错误，`FocusRequester.requestFocus()` 并滚动到该字段；Compose Multiplatform 各端使用现有 `BringIntoViewRequester`/滚动状态，避免仅 Web 生效。
- 每个字段的 `onValueChange` 只清除该字段错误；其他错误保留。
- 重新提交时重新计算完整错误集，不沿用旧错误。

### 风险

- 字段归属映射可能与后端新增错误码漂移；映射未知 code 时回退表单级，不猜测字段。
- 转账/分摊有多个成员字段，需定义复合键（如 `outgoing.amount`、`incoming.amount`），避免只标一个输入。
- 焦点/滚动在 Compose Web、Android、iOS 的行为不同，需要至少共享测试与 Web 冒烟。
- 不改变写入协议；错误映射只影响呈现。

## 实施顺序

1. 先建立字段错误模型与 `CashRecordFieldInputs` 的 per-field API。
2. 接入新建/编辑/草稿三个入口的映射与首错定位。
3. 补同名确认对话框与创建页比较逻辑。
4. 单测/共享测试 + Web 冒烟；邀请不写代码，只补决策记录。
