## Why

KMP 首次创建工作区入口把页面标题和认证信息栏放在表单卡片之外，表单本身又只包含名称与按钮；新的 Material 3 登录/注册页面已有统一的输入控件、按钮和响应式卡片层级。统一首次创建页的表单表现，可让刚登录的使用者沿用同一套清晰的认证表单节奏。

## What Changes

- 只重排 KMP 中 `session.workspaces` 为空的首次创建状态，采用登录/注册页的 Material 3 字段、按钮与响应式表单卡片视觉。
- 将「创建工作区」作为唯一页面主标题，保留「财务账本」、当前邮箱和「退出登录」；首次创建不显示工作区选择导航或返回操作。
- 保留名称为空时可提交并显示字段校验、创建中禁用、服务端失败提示及创建成功后进入账本的现有语义。
- 不调整已有工作区选择列表或从现有工作区创建另一个工作区的表面；不改 Web、Expo、服务端、API、权限和持久化。
- 用 Pen 更新 `design/workspace-entry.pen`，覆盖 `COMPACT`、`REGULAR`、`WIDE`、默认/聚焦/校验错误/请求错误/创建中及成功导航示意。
- 在 change 内提供可离线打开的 `prototype/index.html`，用于在使用者确认前预览布局与表单状态；它不接入生产 Web 路由或服务端。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `workspace/workspace-entry`: 明确 KMP 首次创建的页面结构、适配尺寸与现有表单及导航语义。

## Impact

- KMP UI：`compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt`。
- 设计稿：`design/workspace-entry.pen` 与 `prototype/index.html`；在 `design.md` 记录 Pen 节点与 Compose 映射并取得使用者确认后才能实施。
- 规格：`openspec/specs/workspace/workspace-entry/spec.md` 的首次创建与 KMP 入口要求。
- 验证：受影响的 Compose UI/逻辑测试、KMP desktop/Android/iOS 构建，以及三个窗口类别的截图和 Pen 对照检查。
- 无 API、数据、权限、数据库或财务计算影响；回滚仅还原首次创建表面，不改工作区会话。
