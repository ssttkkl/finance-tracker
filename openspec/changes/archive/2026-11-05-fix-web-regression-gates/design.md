## Context

基线为 `805eca60`。26 个失败来自 8 组场景，部分在 390、768、1440 宽度重复。按当前 UI 规范和主规格恢复合同：独立分类已合并到管理，详细筛选编辑草稿后应用，Basic Dialog 动态高度，月份头与瀑布流行分开。分类模拟服务更新名称后仍返回旧 path/revision，触发客户端正确的数据合同拒绝。

Compose 1.12.1 当前 Web 语义导出将含点击动作的单选角色覆盖为 button，并未导出禁用、选中状态。页面已有正确 Material Button/selectable 状态；五个失败在语义断言处出现，原有无重复写入检查已通过。Python 的 `unittest.main()` 位于第二个测试类之前。

## Goals / Non-Goals

采用用户确认的范围：183 项浏览器案例与两种入口的 11 项 Python 测试通过。修正测试应继续覆盖原用户行为、请求参数、无重复写入、键盘操作和布局约束，不删测、跳过或只放宽超时。财务、数据库、后端接口与依赖版本保持本次范围外。

## Decisions

测试按现行事实源重写失效前提。导航引用管理规格；筛选保留未应用时零请求与关闭放弃；模拟分类服务重新计算路径、深度、目录修订；时间选择读取实际选中日期，不依赖某个自然月。布局验证容器、相对位置、非重叠、固定操作可达与正文滚动，不用旧的 72/800px 任意高度。

Web 语义只在当前受影响控件补齐，复用已有 `core` expect/actual 平台边界。按钮、单选的类型化状态由原 Compose 调用点的同一 enabled/selected 值提供；Web 适配等待语义 DOM 节点出现并同步 role/aria-disabled/aria-checked，销毁时解除监听并还原原属性。实际语义节点在开放 Shadow DOM 中；只缓存仍连接的目标，缺失或宿主重建时重新查找，待节点出现的 RAF 随组合生命周期取消，稳定节点仅同步属性而不反复全树扫描。真实 Chromium 工具回归覆盖延迟 attachShadow、替换宿主、状态转换、销毁与重新注册；PR CI 执行完整 Node 工具集合并安装现有锁定版本的 Chromium。Android/iOS 沿用 Material 自带语义。适配不得按文案猜状态、执行点击、修改业务结果、插入新的可见控件或改写第三方编译产物。替代方案中，重复追加 Compose semantics 无法补全当前导出器缺项；升级依赖会扩大已确认范围，故本次不采用。

刷新骨架用例在窗口缩放后先确认 Regular 表格已经出现，再等待筛选目标连续几次几何稳定且位于视口内，使用最后坐标单击一次；重试按钮复用同一局部助手，不扩大到其他已通过流程。三个响应 gate 保留，请求开始以有界阶段断言验证，并核对失败刷新和重试的 GET、首屏索引及支出参数，避免挂起响应与 networkidle 互等。

滚动测试必须等待目标位置稳定后再取实际点击坐标；滚动 helper 达到循环上限仍未进入可视位置时明确失败，不把旧坐标用于后续操作。日期清除保留未应用零请求、Escape 恢复已应用值、应用后移除日期参数及重开仍为空的完整验证。

布局测试需要稳定的月份头标识，只给现有自然月份 section header 调用点增加语义 tag，保留吸顶表头既有 tag，不改变布局或创建第二份月份呈现。实现范围包括 `CashWaterfall.kt`、`CashDraftEditor.kt`、`CashEntryEditor.kt`、任务面板及其状态读取、小型平台语义文件、Web 焦点桥、浏览器测试/fixture 及 Python 测试入口；不删除生产文件。

### UI、跨端与设计预检

信息架构、字段归属、操作顺序、视觉 token、文案与容器尺寸均沿用当前页面；无待删除冗余信息，无新页面、组件包装或主题。Hallmark 以既有界面的小型语义修复预检：不运行新页面主题/结构选择，不生成重构原型。现有 Material 控件继续唯一承载交互。Web 导出状态受影响；共享层仅提供有限平台边界和同一状态；Native 不改变语义，执行相称编译与单测，不追加设备交互。

已用真实 Chromium 加载并读取 Material 3 的 [Dialogs overview](https://m3.material.io/components/dialogs/overview)、[Dialogs specs](https://m3.material.io/components/dialogs/specs)、[Applying M3 Expressive](https://m3.material.io/foundations/usability/applying-m-3-expressive)、[States overview](https://m3.material.io/foundations/interaction/states/overview)。采用 Basic Dialog 动态高、280–560dp 宽、24dp 外围/内容留白与16dp标题正文间距；聚焦与禁用应表达真实状态，保留当前主要操作层级。Web 抓取只返回 JS 导航壳，原生浏览器工具不可用后改用现有 Playwright Chromium 读取正文，正文证据保存在仓库外 QA 目录。另核对 [JetBrains accessibility](https://kotlinlang.org/docs/multiplatform/compose-accessibility.html) 与 [AndroidX semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics) 的角色/状态合同；保持现有 Material3 1.12.0-alpha03，不引入新 API 或依赖。

### 搜索框离开后的 Web 键盘焦点

真实 Chromium 复现表明，搜索框 Tab 到筛选芯片时逻辑焦点已移动，但 Compose 的临时 `input.compose-backing-field` 销毁只移除 DOM 输入框，浏览器焦点退回 BODY，后续 Space 不再到达 Canvas。单次 RAF 早于实际销毁，不可靠。Web 入口增加同源小型焦点桥：只捕获临时输入框的 Tab，观察同一开放 ShadowRoot 中该输入框实际断开；仅页面仍有焦点、DOM 焦点确已丢失、原 Canvas 仍连接时恢复该 Canvas 的浏览器焦点，不改变 Compose 逻辑焦点、不阻断事件、不增加 Tab 停靠点。其他按键、指针、窗口失焦或超时取消待处理观察。真实用户点击搜索 → Tab → Space 是回归路径，测试不得手动 focus Canvas 掩盖问题。Native 不接入该脚本。

### 提交开始与 UI 重组之间的关闭保护

完整回归发现，手动备注确认提交后约 5ms 内再次点击并关闭，ViewModel 已同步进入 busy/frozen 状态，但页面回调仍可能捕获重组前快照并弹出放弃修改提示。底层现有关闭/重复提交保护保持有效，本次只恢复同一页面应有的退出保护：从已有任务状态提取相同可离开谓词，UI 回调经只读函数在事件时读取最新任务状态，各自保留原关闭与取消编辑门禁，再决定是否提示放弃。整体关闭在初次读取尚无记录时仍可退出，取消编辑仍拒绝任何 busy 状态，不修改冻结命令、幂等或任何财务规则。原快速重复确认/关闭 E2E 保留并加严“不出现放弃提示、提交仅一次、同页显示保存结果”。

共用输入测试助手在字体和目标几何稳定后实际点击，等待 INPUT/TEXTAREA backing input 接管；按原生选择区验证插入或替换结果，再等待 Compose 语义中的完整值同步。密码等待对应掩码，空值和空白按原始文本准确匹配，不强制聚焦或编辑语义镜像，也不在写请求失败后自动重试。手工编辑复用同一助手。导入账户表单测量先确认表单、标题及操作文字就绪，再读取连续稳定的四个非空几何值，保留原宽度、footer 高度与顺序合同。

登录忙碌回归同样通过实际点击禁用的邮箱和密码、发送键盘操作来验证锁定，不强制聚焦可编辑的透明语义镜像。模拟响应显式挂起，检查重复提交零新增及输入保留后再释放，保留认证错误验证；不把此测试修复扩大为全应用的 Web 语义导出修复。

合并流水成员测试的来源证据版本、投影确认状态随提交保持一致；正常流程不应因模拟版本错误出现重读。详情在网络、字体及连续渲染帧稳定后再取实际按钮坐标，保留成员 ID、修订、处理决定及单次提交断言，不重试写入动作，也不修改生产展开状态。

全量入口还暴露两个导入关闭断言受旧语义镜像残留影响，真实 Canvas 和路由已返回账本；用真实返回 URL、关闭前后画面及原重进入恢复/零取消/localStorage 会话保留断言验证，不用刷新替代恢复合同。

## Risks / Trade-offs

- 语义节点可能晚于重组出现或被重建：以生命周期内的节点监听同步，校验初始、状态切换及关闭重开；不使用固定延迟或全局业务状态。
- 修旧测试可能遗漏原行为：保留键盘激活、无写入、筛选范围、取消/应用、吸顶、滚动与避让断言，并接受独立复核。
- 更完整收集可能暴露原先未运行的 Python 问题：先记录 discovery 与直接入口数量差异，再修入口并运行全部案例。

## Migration Plan

先复现代表失败及 Python 收集差异；逐组最小修复并验证。第一版通过相称构建和真实浏览器后提供演示；随后执行独立工程/复用/设计审查、完整 183 项与工具测试、Native 编译、文档核对及归档。没有数据迁移或外部资源变更，回滚本 change 的代码提交即可。
