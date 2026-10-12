# 设计说明：Compose 入口与主页面骨架

## 设计目标

统一访问流程与已登录页面的外层结构，同时保留各页面的业务内容、操作、状态和导航结果。改动只覆盖 Compose Multiplatform 共享 UI；Legacy React Web 和 Expo 不在范围内。

## 信息架构与视图树

```text
FinanceApp
├─ 未登录 / 工作区访问
│  └─ AuthResponsiveShell
│     ├─ 登录 / 注册表单
│     ├─ WorkspaceAccessScreen
│     │  ├─ 可访问工作区列表（已有工作区）
│     │  └─ 创建工作区表单卡片（「新工作区」、名称字段、创建操作）
│     └─ 未登录 InvitationScreen
│        └─ 邀请信息、登录并继续、取消
└─ 已登录路由
   └─ CompactAppShell / RegularAppShell / WideAppShell
      └─ DestinationScaffold (Material 3 Scaffold)
         ├─ TopAppBar
         │  ├─ 当前路由标题
         │  ├─ 返回操作（适用时）
         │  └─ 工作区切换 / 退出
         └─ 当前路由内容
            ├─ 收支流水 / 现金导入 / 分类管理
            ├─ 投资持仓 / 投资事件
            ├─ 工作区管理 / 已登录邀请
            └─ 未找到页面
```

已登录外壳的导航差异沿用当前 presentation 合同：COMPACT 和 REGULAR 使用模态抽屉；WIDE 使用常驻侧栏。三类窗口的标题均位于 TopAppBar。入口页不显示已登录 Scaffold，也不引入返回操作来替代首次创建或邀请的现有语义。

## 将删除的冗余信息

- 移除已登录页面正文中重复的路由标题，避免顶栏和正文重复表达同一标题。
- WIDE 顶栏补上当前路由标题；正文不再承担补齐宽屏标题的职责。
- 工作区创建表单卡片只显示「新工作区」任务标题和一个「创建工作区」主操作，不保留卡片外同义标题。
- 入口页不增加教学区、布局说明或重复字段提示；输入控件自身表达字段含义。

## 设计选择与响应式行为

- 登录/注册、工作区入口、未登录邀请沿用 FinanceTheme 的入口背景、居中单列表面和字段/主操作层级。三类窗口均显示居中表单卡片，最大宽度 440 dp；COMPACT 单列纵向滚动并保留 16 dp 窄屏边距，REGULAR、WIDE 在可用视口中居中。
- 工作区账户信息（品牌、邮箱、退出）保留在入口页头部；工作区列表和创建表单继续由当前会话状态决定。首次创建无返回入口；创建中禁用字段和按钮；服务端确认后进入账本。
- 未登录邀请用同一入口容器展示工作区、角色和接受/取消动作。会话存在的邀请流程继续走已登录 Scaffold。
- 已登录路由由现有 `DestinationScaffold` 提供 Material 3 `Scaffold` 和 `TopAppBar`。主标题只在 TopAppBar 中出现；导航模式、返回、工作区切换、退出及路由正文维持现有语义。
- 继续使用 FinanceTheme 语义颜色。原型的主要色值来自 Pen 变量：背景 `#F5F5F5`、表面 `#FFFFFF`、前景 `#18181B`、强调色 `#0485F7`、错误色 `#D92D20`。中文排版沿用 Noto Sans SC，金额/数字角色沿用 IBM Plex Mono。
- 原型无真实财务数据；主页面示例金额以破折号占位。控件状态使用现有语义：正常、焦点、校验错误、请求失败、禁用/加载、成功。

## Hallmark 预检与设计自检

预检基于现有 Compose `commonMain`、FinanceTheme、Noto Sans SC/IBM Plex Mono 字体与 4 dp 语义间距；选择保留品牌蓝和当前登录页控件语言，不添加插画或装饰性指标。原型 CSS 顶部包含 Hallmark 设计标记与预发自检分数。主风险是 WIDE 新增标题后与工作区操作争用横向空间，实施和代表视口复核时需检查截断、焦点和可访问名称。Hallmark 最终 UI audit 在实现形成后执行并回写本变更 tasks；尚未声称通过 audit。

## Pen 设计稿与 Compose 映射

页面/节点 ID 来自 `design/app-shell-entry.pen`；尺寸以 Pen 画板单位表示，Compose 对照按逻辑 dp 实现。

| Pen 页面 / 节点 | Compose 目标 | 实现属性映射 |
|---|---|---|
| `iqiUt` 工作区入口 / COMPACT (390)，卡片 `n0pXvz`、输入框 `UJBCj`、主操作 `x2YZF` | `AuthResponsiveShell`、`WorkspaceAccessScreen`、`FirstUseWorkspaceCard` | 页面水平边距 16 dp；账户头部在卡片上方；卡片宽度随可用宽度、内容边距 20 dp；字段高 56 dp；首要操作满宽；纵向滚动。颜色使用 FinanceTheme 的 surface/background/primary/error。 |
| `P6fLwS` 工作区入口 / REGULAR (768)，卡片 `K4LDMw` | `WorkspaceAccessScreen` 首次创建分支 | 账户头部最大宽 720 dp；表单卡片最大宽 440 dp、居中；卡片内容边距 32 dp；已有工作区列表与创建动作仍保留在同一入口壳中。 |
| `MJQrT` 工作区入口 / WIDE (1440)，卡片 `YTKuH` | `WorkspaceAccessScreen` 首次创建分支 | 头部最大宽 1120 dp；卡片最大宽 440 dp 并居中；宽屏仍使用相同字段顺序、按钮及状态。 |
| `wgTo2` 未登录邀请 / COMPACT (390)，卡片 `h1nzz` | `App.kt` 未登录邀请分支、`InvitationScreen`、`AuthResponsiveShell` | 复用入口背景和单列表面；卡片含标题、工作区与角色、登录继续及取消；加载、失效、错误、接受状态使用现有语义反馈。 |
| `h8LGY` 已登录主界面 / COMPACT (390)，TopAppBar `LcQwb` | `CompactAppShell`、`DestinationScaffold` | Material 3 Scaffold；模态抽屉；TopAppBar 64 dp，包含路由标题、导航入口和工作区操作；正文不重复路由标题。 |
| `yAv6i` 已登录主界面 / REGULAR (768)，TopAppBar `f1DyjM` | `RegularAppShell`、`DestinationScaffold` | 与 COMPACT 共用模态抽屉和标题层级；正文区域按宽度铺开，不出现常驻侧栏。 |
| `dMNT8` 已登录主界面 / WIDE (1440)，TopAppBar `dajIi` | `WideAppShell`、`DestinationScaffold` | 常驻侧栏宽 248 dp；TopAppBar 位于内容区，显示当前路由标题和工作区操作；正文不重复标题。 |
| `F0JF6` 工作区创建交互状态 | `FirstUseWorkspaceCard` 与状态提示组件 | 覆盖默认、焦点、字段错误、请求失败/重试、禁用/创建中、成功后进入账本。 |

业务页面映射：

- `CashLedgerScreen`、`CashImportScreen`、`CashCategoryScreen`、`InvestmentHoldingsScreen`、`InvestmentEventsScreen`、`WorkspaceManagementScreen`、已登录 `InvitationScreen` 和 NOT_FOUND 路由均保持在 `DestinationScaffold` 内容槽内。
- `FeaturePage` 的页面标题呈现关闭或拆分为正文区域选项；`CashLedgerScreen` 的头部操作继续显示；持仓页额外重复的路由标题移除。区块标题、表格列名、详情标题和业务操作不属于路由标题，不移除。

## 变更范围、风险与回滚

- 跨端影响：Compose Web、Android、iOS 共用 `commonMain`，三者均受影响；Legacy React Web 与 Expo 不受影响。详见 `tasks.md` Cross-platform Impact Check。
- 不涉及领域规则、API、持久化、金额计算、权限模型、依赖或数据迁移。
- 主要风险是正文标题隐藏后遗漏区块语义、WIDE 工作区动作挤占标题、未登录/已登录邀请误用外壳、键盘焦点或无障碍标题丢失。
- 可回滚边界为 Compose presentation 源文件和本 change artifacts；无需数据库/后端回滚。

## 原型与确认

- 独立交互原型：`prototype/index.html`。
- 可用页面切换器查看登录/注册、首次创建、已有工作区、未登录邀请和已登录主界面；状态切换器查看认证、创建/邀请反馈。原型不依赖生产路由、后端、凭据或外部网络。
- 已按项目规定完成 COMPACT、REGULAR、WIDE Pen 代表画板：390、768、1440。页面无新增图片资产。
- 用户已确认入口共用 `AuthResponsiveShell` 与居中卡片，并确认已登录标题统一置于 TopAppBar；随后要求推进该 change。确认的三类窗口均使用卡片，因此本 change 同步更新登录/注册 compact 的旧无卡片呈现；卡片使用「新工作区」标题、名称字段及「创建工作区」操作。
