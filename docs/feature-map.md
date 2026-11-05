# Compose 功能地图

本文是页面级功能、业务路径、实现证据、跨端覆盖与待办的统一盘点入口。按模块查页面，再进入模块文档查看入口、主路径、分支、失败恢复和实现证据；行为合同以 [OpenSpec 主规格](../openspec/specs/README.md) 为准。

> 盘点日期：2026-11-02。导入及共用备注编辑修复见 [规范对齐任务](../openspec/changes/archive/2026-11-03-align-cash-import-product-rules/tasks.md)，Web 验证与独立设计复核已完成。Compose Web、Android、iOS 共用 `compose/shared`，共享代码不代表各平台设备 QA 全部完成。

## 产品总览

当前共有 **10 项页面级功能**，分布在 5 个有页面的业务模块。账户与共享规则由现有任务承载，没有独立页面。编号保持稳定，F-04 的详情面板与 F-07 的管理页分组仍各计一项独立任务。

| 模块 | 页面级功能 | 主要用户任务 | 详情与证据 |
|---|---|---|---|
| 认证 | F-01 登录与注册 | 登录、注册、恢复会话 | [认证模块](authentication/feature-map.md) |
| 工作区 | F-02 工作区选择与创建；F-06 邀请预览与接受；F-10 管理 | 进入工作区、接受邀请、管理资料／权限／成员 | [工作区模块](workspace/feature-map.md) |
| 现金账本 | F-03 收支账本；F-04 收支详情与记账；F-07 收支分类管理 | 浏览、记账、编辑、处理草稿／关联流水、分类 | [现金账本模块](cash-ledger/feature-map.md) |
| 导入 | F-05 现金账单导入 | 准备文件和账户、统一核对、一次确认、查看结果 | [导入模块](import/feature-map.md) |
| 投资 | F-08 投资持仓；F-09 投资流水 | 查看估值与表现、浏览事件与来源证据 | [投资模块](investment/feature-map.md) |
| 账户 | 无独立页面 | 在记账／导入中选择账户，在导入中创建账户和补齐币种 | [账户模块](accounts/feature-map.md) |
| 共享规则 | 无独立页面 | 支撑导航、响应式、展示、时间和演示模式等共同规则 | [共享规则与工程证据](shared/feature-map.md) |

```mermaid
flowchart TD
    A[F-01 登录与注册] -->|需选择或创建| B[F-02 工作区选择与创建]
    A -->|已有有效工作区| C[F-03 收支账本]
    B --> C
    C --> D[F-04 收支详情与记账]
    C --> E[F-05 现金账单导入]
    C --> F[F-08 投资持仓]
    C --> G[F-09 投资流水]
    C --> H[F-10 管理]
    H --> I[F-07 收支分类管理]
    J[F-06 邀请预览与接受] -->|接受后进入受邀工作区| C
```

图表示主要任务之间的到达关系，不表示所有页面都是独立路由。邀请可在登录前打开；已有有效会话和工作区时可直接进入对应页面。账户、投资账单导入、连接器及 Agent 操作指引不因后端能力存在而计为客户端页面。

## 页面入口索引

工作区内页面使用 `/w/<workspaceId>/` 上下文；认证、工作区选择和邀请入口见各行说明。切换工作区保留当前子页面路径。

| ID | 页面级功能 | 入口或承载方式 |
|---|---|---|
| F-01 | [登录与注册](authentication/feature-map.md#f-01-登录与注册) | 无有效会话时的应用入口，登录／注册共用页面 |
| F-02 | [工作区选择与创建](workspace/feature-map.md#f-02-工作区选择与创建) | 认证后的工作区入口，无独立路由 |
| F-03 | [收支账本](cash-ledger/feature-map.md#f-03-收支账本) | `/w/<workspaceId>/` |
| F-04 | [收支详情与记账](cash-ledger/feature-map.md#f-04-收支详情与记账) | F-03 的详情面板或新增表单，无独立路由 |
| F-05 | [现金账单导入](import/feature-map.md#f-05-现金账单导入) | `/w/<workspaceId>/cash-import`，全屏任务 |
| F-06 | [邀请预览与接受](workspace/feature-map.md#f-06-邀请预览与接受) | `?invite=<token>`；Native 内部 URI 为 `finance-tracker://invite/<token>` |
| F-07 | [收支分类管理](cash-ledger/feature-map.md#f-07-收支分类管理) | F-10 内嵌分组；`/w/<workspaceId>/cash-categories` 深链接定位该分组 |
| F-08 | [投资持仓](investment/feature-map.md#f-08-投资持仓) | `/w/<workspaceId>/investment-holdings` |
| F-09 | [投资流水](investment/feature-map.md#f-09-投资流水) | `/w/<workspaceId>/investment-events` |
| F-10 | [管理](workspace/feature-map.md#f-10-管理) | `/w/<workspaceId>/workspace-management` |

## 跨端覆盖与待办

10 项功能均有 Web、Android、iOS 共用的 Compose 入口。下表区分已记录的验证范围与仍待验收项；未单列的平台范围不推定通过。各模块正文集中保存代码、主规格与验证记录链接，历史结果不能替代当前缺口。

| 功能 | 已记录的实现与验证范围 | 当前待办或边界 |
|---|---|---|
| F-01／F-02 | 认证恢复、工作区进入／创建已实现；共同入口壳有 Web Chrome 与 Android QA 记录 | 未形成覆盖全部状态与平台的验收矩阵，具体范围见 [认证](authentication/feature-map.md) 和 [工作区](workspace/feature-map.md) |
| F-03 | 共享账本已接搜索、筛选、分页和批量操作；账本、导入核对和关联搜索共用瀑布流组件，账本类型列显示待处理原因；本次 Web Chromium 390／768／1440 代表视口与 Android Host／iOS Simulator 编译已验证 | 未做原生设备交互 QA；见 [现金账本](cash-ledger/feature-map.md) |
| F-04 | 当前详情、草稿／分摊、转正式及关联维护已接真实 API；关联流水和账单原文共用成员卡布局，搜索只返回可选流水，服务端资格筛选有 SQLite／PostgreSQL 合同证据 | 联合方案改选不可提交；本次未做原生设备交互 QA；见 [F-04](cash-ledger/feature-map.md#f-04-收支详情与记账) |
| F-05 | 共享文件／账户准备、统一核对、确认和收据已接入；仅多渠道时分组账户，核对列表显示类型及待处理原因；本次共享单测、跨端编译、Web Chromium 代表流程与 SQLite／PostgreSQL 合同已验证 | 未做原生设备交互 QA；iOS 文件选择器交回后的完整导入、未知账户与账户编辑分支待验收；见 [导入](import/feature-map.md) |
| F-06 | 邀请预览／接受／取消及内部 URI 路由已实现，有入口 QA 记录 | HTTPS Universal Links 待部署域名与签名配置；见 [F-06](workspace/feature-map.md#f-06-邀请预览与接受) |
| F-07 | 目录、搜索、排序、编辑、删除已实现；有 Wasm 单测、Web／Android 搜索创建、新 Android 管理布局及 iPad 11 部分操作证据 | iOS 新布局与分类完整操作集待设备验证；见 [F-07](cash-ledger/feature-map.md#f-07-收支分类管理) |
| F-08 | Web E2E、Android 三布局主题 QA、iPad 11 估值／详情及 iPad 13 wide 深色布局有通过记录 | 既有结果仅覆盖所列范围；见 [F-08](investment/feature-map.md#f-08-投资持仓) |
| F-09 | Web E2E、Android 详情／分页、iPad 11 详情／加载更多有通过记录 | 银证转账关系只读；既有结果仅覆盖所列范围；见 [F-09](investment/feature-map.md#f-09-投资流水) |
| F-10 | 资料／成员／邀请／删除已实现；Web E2E 与 Android 管理 QA、iOS 改名／角色／邀请有记录 | 新 iPhone／iPad 管理布局及 iOS 删除流程待设备验收；见 [F-10](workspace/feature-map.md#f-10-管理) |

**共同缺口：** Compose Web `1.12.1` 关闭详情弹层后无障碍树不恢复，另有禁用状态语义缺陷；Web 返回及无障碍 QA 保留未通过状态，不升级 Compose。问题与历史浏览器证据见 [共享规则](shared/feature-map.md#web-已知缺陷与历史验证)。目前仍没有覆盖全部页面、平台、窗口尺寸与状态的完整矩阵。

**部署边界：** 现金导入的真实库迁移、旧外部原文件对象清理及部署尚未执行，不能把代码接通、后端验收或共享入口视为实际部署。条件见 [导入模块](import/feature-map.md#后端能力与客户端边界) 的实施记录。

### 跨端影响检查

任何 Web／Mobile 页面、文案、交互、表单校验、弹层、状态或响应式变化都必须执行 Cross-platform Impact Check，在对应 `tasks.md` 写明 Web、Native、共享层是否受影响及理由，不得默认另一端无影响。

- Web compact 与 Native Phone 共用产品合同；Native regular／wide 按逻辑宽度适配 Android 多形态、iPhone 和 iPad，wide 保持 Web 大屏导航层级、区域顺序及筛选／详情／操作关系。
- 共享文案、状态、语义 ID、操作、响应式与确认／错误／结果语义；渲染控件可以不同。差异须登记于 [presentation 合同](../openspec/specs/shared/cross-platform-presentation/spec.md) 或 OpenSpec，否则按 defect。
- 未覆盖的 Native 页面登记缺口，不用伪页面或静默跳转掩盖。
- 当前 Web／QA 位于 `compose/`。

## 统计口径

- 一项可独立完成的用户任务计为一个页面级功能；路由页面、详情面板或管理页分组均可承载，同一任务跨窗口／平台只计一次。
- 登录／注册、记账的新建／查看／编辑、导入的文件／账户／核对／确认／结果均为各自任务内部模式或步骤。
- 邀请、工作区管理、分类管理、投资持仓和投资流水分别计数；F-07 嵌入 F-10 后仍保留独立分类任务。
- 重定向、路由守卫、导航壳、加载态、错误态、筛选和确认弹窗不另计页面。
- “共享实现覆盖”须有可达的 Compose 入口和真实 API 流程；仅有后端接口、文案、原型、规格或不可达导航不计为当前客户端实现。各 target 的实际验收范围分别登记。

## 维护规则与依据

1. 本总览与 `docs/<module>/feature-map.md` 各模块共同组成唯一功能盘点。模块文档保存业务路径、限制和证据，总览保存页面索引、统计与跨端待办；行为以 [主规格](../openspec/specs/README.md) 为准。
2. 所有功能改动，包括后端能力新增客户端入口、Web／Native／共享 presentation 改动，必须同组更新相关模块及总览的页面数、覆盖、证据和待办。
3. 每次核对模块归属、页面入口、主路径、关键分支与失败／取消／恢复出口。新增页面先登记各平台状态，一端未实现时同时登记另一端对齐 TODO。
4. 只有真实页面入口、API 流程与相称验证均完成后，才移除对应缺口；共享代码不代表设备 QA，通过的历史记录不覆盖新布局或新流程。
5. 平台特有控件可不同，但行为、文案、状态、语义和业务结果须遵守 [跨端展示规格](../openspec/specs/shared/cross-platform-presentation/spec.md)。工程、导航壳和共同验证证据集中见 [共享规则](shared/feature-map.md)。
6. 纯文档、词表、功能地图、`AGENTS.md` 或 `README.md` 改动不创建 OpenSpec 变更；涉及代码改动时遵守仓库 OpenSpec 门禁，在对应 `tasks.md` 保存准确的验证证据。

## Kotlin 后端迁移状态

Kotlin 本机预览已接入 87/87 个业务操作。原 Python 测试定义 2,352 migrated／374 retained／0 pending，迁移台账已完成；逐项实现、保留理由与验证记录见 [Kotlin 后端迁移证据](shared/kotlin-backend-migration.md) 和 [迁移盘点](../openspec/changes/archive/2026-11-04-kotlin-backend-shared-core/inventory.md)。

离线账本改造已提供选定工作区的版本查询和一致全量事实快照，事实写入口审计完成，同一合成输入的完整响应已通过 SQLite／PostgreSQL 逐字段对照。来源候选读取与操作规划保留 64 位修订；工作表行列／坐标预算已共享，完整本地工作簿读取尚未接入。PDF 与旧建行 XLS 的无状态解析入口和共享传输已提供：PDF 返回归一化文档，XLS 返回原始文档后在端侧归一；两者不读取账本或创建导入暂存，需要联网，本地文件调度和页面尚未接入。

现金文档／归一、来源身份／查重、来源计划证明准备与账户／支付组件决定、逐流水操作计划、资金扫描／分摊、昵称 NFC 及来源账户 Unicode 识别、关系发现与完整规划／校验、逐行投影和金额图已复用共享核心。文本解码、工作簿数字／日期文本与日期样式识别、平台 CSV／工银亚洲 TSV 字节到完整归一、已解码文档归一及来源账户、资金扫描、账户选择后的分配校验、完整字段验证、来源行／批次查重、完整快照上的候选／证明读取及导入选择输入校验及完整逐流水操作计划及快照逐行投影派生、稳定收支及公共字段组合、完整列表读取模型及全局筛选选项、工作区／账本／规则／筛选绑定的分页游标、显式浏览时区的月份适配与完整选择的精确月度统计具备本地入口；已接受且生效的现金资金对应仅同步所关联投资记录的最小只读字段，完整投资账本和计算仍排除；缓存版本／快照 HTTP 入口已按预期工作区校验，刷新编排已通过事实缓存端口组合，内部 SQL 快照已验证完整保存与失败保旧，快照 HTTP／落盘 JSON 数字词法使用同一专用 serializer 保真；工作簿 ZIP 预检已接共享预算／CRC／中央目录扫描，原生继续完整 XML 与单元格读取；其余生产适配、端侧工作簿、写集、本地存储和页面接线尚未完成，不能视为已支持离线导入。页面覆盖按上表记录，实施及验证见 [离线账本任务](../openspec/changes/offline-first-ledger-import/tasks.md)。
