# 任务：工作区同名提示与现金字段错误

> 已实现。邀请链接维持现有行为，不写代码。

## 1. 思考与边界

- [x] 1.1 复核工作区创建页的数据来源：`WorkspaceAccessScreen` 使用当前 `session.workspaces`；列表为空/未就绪时直接创建，不阻塞。
- [x] 1.2 复核现金新建/编辑/草稿错误码：`invalid_amount`、`cash_amount_out_of_range`、`invalid_currency`、`invalid_account`、`account_changed`、`invalid_datetime`、`invalid_allocation`；转账/分摊为复合字段。
- [x] 1.3 邀请“不提示、不撤销、旧链接丢弃”确认不需要改 `workspace-management` 规格或代码。

## 2. 计划

- [x] 2.1 字段错误模型：`cashFieldErrorKeyFor` 返回字段+文案 key；`cashFieldErrorFor` 在 composable 层解析文案；未知错误回退表单级。
- [x] 2.2 首错顺序：`fieldErrors` 插入顺序即字段顺序；文本字段 `FocusRequester`，其余 `BringIntoViewRequester` 滚动。
- [x] 2.3 同名比较：`hasSameWorkspaceName` 做 trim + 拉丁大小写不敏感；提示文案与确认/取消按钮已加中英文。

## 3. 构建

### 工作区

- [x] 3.1 创建页提交前比较已加载工作区名称（`hasSameWorkspaceName`）。
- [x] 3.2 命中同名时显示“已有同名工作区”确认；确认提交，取消保留输入；连接不可提交时确认按钮禁用。
- [x] 3.3 列表为空/未就绪时直接走原创建流程。

### 现金字段错误

- [x] 3.4 新增 `CashFieldErrors.kt` 与 `CashRecordFieldInputs` 的 per-field error/revision；文本字段用 `isError`/`supportingText`，选择/日期字段显示字段旁错误文本。
- [x] 3.5 `CashCreationPanel` 接入字段映射、首错聚焦/滚动；字段错误保留在本地状态，避免编辑无关字段时误清。
- [x] 3.6 `CashEntryEditor` 接入字段映射、首错聚焦/滚动；合并父级 `serverErrorCode`，未知错误留表单级。
- [x] 3.7 `CashDraftEditor` 接入字段映射与父级错误；保留“允许缺项、只校验已填值”。
- [x] 3.8 字段修改只清该字段错误；分摊/转账复合字段单独处理；无法归属的错误留表单级。
- [x] 3.9 转账错误显示在转账区并滚动到该区；分摊错误映射到 `components` 并显示在分摊编辑器下。

## 4. 测试

- [x] 4.1 `WorkspaceNameConflictTest`：trim、大小写、不同名、空列表；确认/取消 UI 未做设备自动化（demo 创建页仅无活动工作区时可达）。
- [x] 4.2 `CashFieldErrorsTest`：普通/转账/分摊/未知错误映射；Web demo 输入 `abc` 保存后金额字段显示“金额格式无效。”
- [x] 4.3 首错聚焦（逐次修正）、修改无关字段保留错误、服务端错误映射：代码级复核；多字段同时返回受当前单错误 `DomainFailure` 限制，规格已改为“首错逐次修正”。
- [x] 4.4 `:shared:wasmJsTest`、`:webApp:wasmJsBrowserDistribution` 通过；Web 1440×900 demo 走通现金新建非法金额分支；工作区同名确认未在 demo 触发。
- [x] 4.5 `:shared:compileAndroidMain` 通过；iOS 未运行（默认范围只做相称编译，残留风险已记）。

## 5. 审查与发布

- [x] 5.1 独立审查：0 critical；主要发现已处置——多错误改为首错逐次修正、分摊/转账复合键、父级服务端错误映射、隐藏字段回退、转账滚动、重复错误文本、`rememberSaveable`。残留：选择器错误文本为相邻节点而非控件 `supportingText`；无 Compose UI 自动化覆盖确认/聚焦。
- [x] 5.2 `git diff --check` 通过；最终 diff 已复核，残余风险见 5.1/4.5。
- [x] 5.3 功能地图无需改页面/流程：仅既有创建页和现金表单的错误呈现/确认行为，不新增页面或能力。
- [x] 5.4 提交并推送（见 7.2）。
