## Context

详见 proposal。独立 trace 复核显示：F03 用重排前 y=473/h=71 分析重排后 y=482/h=74 的截图；F09 history 间隔 60ms、8ms，旧语义镜像提前通过 URL／screen 断言；查看者 session 始终为 viewer，语义点击却在真实 Canvas 打开了新建分类表单。

## Goals / Non-Goals

恢复既有只读入口与完整浏览器门禁。目标、范围和财务非目标沿用用户已确认的浏览器修复授权；grilling 核对没有新增角色、权限规则或产品取舍。不通过删除断言、自动重试、额外 reload 或扩大像素采样掩盖失败。分类计算、服务授权、金额、数据库和 API 不变。

## Decisions

分类页面在已有 canWrite/busy 资格处同时约束实际回调，不只依赖 Compose 按钮 enabled；保留现有 ViewModel 准备草稿方法、保存授权和全部组件。新本地红回归确认现有 Compose DOM 未报告 aria-disabled；复用仓库已有 PlatformControlAccessibility，将顶级创建及每行编辑／更多按钮的同一 canWrite/busy 资格报告给 Web 语义树，不新增适配框架或控件包装。仅使用者有写权限且没有冲突请求时才打开顶级、子分类和编辑窗口。备选的纯物理点击会减少现有语义覆盖，故不采用；把 UI 会话角色复制到领域或新增持久权限状态无必要，也不采用。

截图几何作为一次采集：同一 evaluate 读取 table/summary/row，截图后重新读取，只有两批矩形一致才分析该截图；有界等待只用于获得一致样本，原行内容、留白、分隔线阈值仍分别断言。history 每一跳同时核对目标 screen 出现与上一个 screen 移除，防止旧镜像提前满足条件；保留全部 URL、history 和分页行为断言。

Cross-platform Impact：分类回调位于共享 commonMain，Web/Android/iOS 均受资格一致性修复影响；Web 执行查看者语义和物理交互及有写权限用户的既有编辑流程，共享测试与 Android/iOS 相称编译。浏览器采集和 history 同步仅属于 Web QA。未改变布局、文案、组件或 token；Hallmark 范围预检为既有管理列表和模态表单的只读状态，不产生新设计方向或 HTML/Pen 原型，按 UI 规范 §6 以 Compose 为事实源。既有 Material 3 Button 的 enabled 外观保留，实际业务回调按项目 UI 规范 §2.5 再核对资格。

## Risks / Trade-offs

语义激活与物理输入不能互相替代，查看者保留两类检查，且继续核对零写入请求。授权和 busy 是共享瞬时状态，回调读取当前资格，不新建缓存。几何重采集必须有界，稳定样本仍失败时按真实视觉缺陷处理，不用放宽阈值。history 目标/旧 screen 交替不能证明所有平台路由无缺陷，完整 185 项仍是验收门禁。

没有存储、迁移、读取 SQL、外部回调或新依赖；双后端存储矩阵、数据库性能及依赖安全专项不适用。安全专项为查看者入口和零写入的浏览器验证。回滚本次共享回调及 QA 同步即可，无数据迁移和部署动作。

已在真实浏览器读取 Material 3 [Buttons overview](https://m3.material.io/components/buttons/overview) 与 [specs](https://m3.material.io/components/buttons/specs) 正文，核对文本按钮的禁用与活动状态沿用原组件，不修改默认尺寸、颜色或形状。本次只使现有状态的 Web 语义和实际回调一致。
