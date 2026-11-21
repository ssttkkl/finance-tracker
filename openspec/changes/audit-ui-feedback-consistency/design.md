# 设计：全页面反馈与操作一致性修复

## 分级与边界

按 B 类处理：改动覆盖 10 项页面级功能的 21 个文件，但每项都是既有页面的局部反馈、操作层级、空态或无障碍修复；没有新增页面、业务能力、数据模型、接口、鉴权或存储路径。跨模块的共同点是同一条设计规则，而不是新的架构耦合。若后续把“结果未知写入恢复”或“宽选择面板”纳入同一 change，再按 A 类重新评估。

## 设计方向

- 继续使用 Material 3 Expressive 与 `FinanceTheme`、Git token；不新增颜色、字体、圆角或间距体系。
- 反馈按信息影响和后续任务选型：阻断/财务结果/决定留在对象附近或固定操作区；短、低优先级、非阻断结果才用 Snackbar；带动作的 Snackbar 保持到用户处理。
- 每个操作区最多一个 Filled 主操作；空态下让唯一可执行动作成为主操作，而不是保留一个禁用的“下一步”。
- 危险操作（移除关联、移除成员、删除分类/账本记录）在最终确认使用 error/onError；禁用态回到中性色，不用半透明红字伪装可执行。
- 弹层与字段：基本对话框使用共享 `ModalHeader`、pane title 和安全区避让；字段错误靠近字段，首个错误聚焦；进度控件带可访问名称。

## 实现选择

- 详情计划确认统一由 `CashCurrentRecordPanel` 在非编辑、非关联态渲染 `CashMaintenanceConfirmation` 与固定底栏；编辑器和关联弹窗继续使用各自已有的 confirmation/actions slot。
- 导入空文件态把“添加文件”切换为 Filled，并隐藏“下一步”；导入主操作复用 `ic_check`/`ic_arrow_forward`，线性进度复用 `TaskProgressIndicator`。超限预览按用户决策改为文件列表附近常驻原因，不再用 Snackbar 重复提示，并同步 `cash-import-session` delta。
- 账本筛选加载中显示骨架而不是空态；金额筛选用 `invalidAmountSupportingText`；批量选择 Checkbox 补可访问名称；紧凑筛选弹层移除多余关闭图标。
- 关联弹窗复用 `ModalHeader`，补 `paneTitle`、`safeDrawing` 和 24dp 紧凑边距；移除关联最终确认使用 error 色。
- 认证与工作区：新增 `BusyButtonLabel` 统一“保留业务名 + 内联进度”；登录/注册字段错误改由 `AuthFormContent` 直接接收 feedback；管理页加载/失败只渲染一次；改名保存用独立 `savingName` 状态。
- 分类删除确认先点名对象；有子分类的禁用删除用中性色；投资筛选无匹配时区分空态并提供清除筛选。

## 风险与回滚

- 主要风险是布局变化（导入底栏、管理页 blocked、关联弹窗头部）和状态时序（改名保存、计划确认）。通过共享 Compose 编译、`:shared:wasmJsTest`、Web 生产构建和代表视口冒烟降低风险。
- 未改变写入协议，回滚按文件粒度恢复即可；无数据迁移、部署或兼容影响。
- 既有 Web 弹层关闭后画布空白在干净 HEAD 上复现，作为独立平台问题记录，不混入本次设计决策。
