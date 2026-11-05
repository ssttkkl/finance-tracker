## 1. 思考

- [x] 1.1 按 A 类读取主规格、项目上下文、产品/UI 规则、词表及 active change；独立 trace 复核当前 CI 180/183 的三个失败，不把查看者真实表单当作语义镜像假象。
- [x] 1.2 grilling 核对沿用用户已授权的原浏览器修复范围：恢复当前行为与真实缺陷，不新增权限模型或视觉改版。

## 2. 计划

- [x] 2.1 proposal、完整 MODIFIED 分类 delta 和 design 定义资格回调、稳定采集及每跳 history 就绪边界；数据库及服务接口不变，记录适用安全与跨端策略。

## 3. 任务拆分

- [x] 3.1 原 CI 提供失败基线，保留全部原视觉、history、权限及零写入断言；再跑稳定页面下的查看者语义失败复现，随后最小修复。

## 4. 构建

- [x] 4.1 查看者页面稳定后保留语义激活红回归；红回归确认禁用外观的 Web button 仍报告 enabled；复用已有语义适配并修复共享顶级／子分类／编辑回调资格，原组件与表单保留。
- [x] 4.2 几何截图前后核对、history 每跳新旧 screen 交替；不放宽像素和业务断言，不添加自动重试。
- [x] 4.3 同组功能地图与实际操作文档核对；用户术语和页面内容未改变，不新增词表概念。

## 5. 审查

- [x] 5.1 独立产品／工程／权限边界及最终 diff 复核，无剩余阻断；原 F03 新采集函数不能穿透 ShadowRoot 的 finding 已改用原 locators 句柄同次 evaluate 并重审关闭。所有原像素、URL／history／分页和语义断言保留。
- [x] 5.2 独立防劣化调度 UI 复用专项，通过；DB／DTO／API／权限定义未变，契约专项不适用。已有适配器、Material 控件及 token 直接复用。390×1800 查看者截图和物理／语义路径独立复核，Hallmark 0 critical／major／minor。原“只验顶级创建”覆盖 finding 已补代表行编辑／更多禁用及拒绝、零全部写方法并关闭。

## 6. 测试与 QA

- [x] 6.1 查看者 create／edit／more、F03 和 F09 三项无重试通过（35.3 秒）；查看者另以最终截图通过（8.3 秒）。有写权限分类编辑、失败保留及提交保护在完整回归的管理案例中通过。Chromium，同源本地 QA URL；F03 390／768／1440，F09 1440→390，viewer 390×1800；包含真实点击及 dispatch，零全部分类写方法，截图保存在仓库外。快速角色／busy 切换与真实读屏未运行，不宣称通过。
- [x] 6.2 共享 519 项、Android debug 与 iOS Simulator Arm64 编译通过；默认不执行原生设备交互。
- [x] 6.3 固定实现后完整浏览器运行 184 项通过、1 项因保存截图及打包 trace 时 ENOSPC 失败（16.5 分钟）；清理本任务可重建产物后，同源码受影响项无重试补验 1/1 通过（10 秒），独立复核确认没有产品断言失败，不记为单次 185 全绿。OpenSpec 28 项严格校验、doctor、194 个本地链接／锚点和 diff 检查通过；最终推送 SHA 的完整 CI 在发布阶段核对。

## 7. 发布

- [x] 7.1 README／docs／references／接口示例、功能地图、词表与评审证据按最终范围核对，分类模块及总览同组更新；未改 API／配置／术语，无需追加说明。用户选择保留现有 UI 并恢复当前合同，现有权限与跨端规则足够，无需新增通用规范。
- [x] 7.2 分类主规格的完整需求块已同步并逐块核对，使用 `openspec archive fix-compose-browser-regression-gates --skip-specs --yes --json` 归档。该标志仅避免重复应用已经同步的 delta；本地证据对象和执行时间见下文，最终远端门禁按发布阶段核对。
- [x] 7.3 按既有授权提交推送同仓库 origin/continue-kotlin-backend-refactor，更新既有 main 目标 PR112；不合并、不部署。

比较基线为 02514846fb5ffe90dceb90a7412bcdb53f3d5301，2026-11-05。仓库外 QA 目录 ci-third-readonly-artifact 保存原远端失败 trace 与安全合成截图。backend 在本 change 未改，存储双后端、SQL 性能、依赖扫描不适用；共享前端与所有写入口均继续由原服务权限约束。与账单解析修复同分支并行验收，不把其真实账单数据加入本 change。

发布证据：代码提交 `367c0066` 与 `ab514636` 已推送至 `ssttkkl/finance-tracker` 的 `origin/continue-kotlin-backend-refactor`，[PR #112](https://github.com/ssttkkl/finance-tracker/pull/112) 目标为 `main`，已更新准确验证范围与发布条件；未合并、未部署。最终远端完整门禁以 [PR 当前提交检查](https://github.com/ssttkkl/finance-tracker/pull/112/checks) 公布的结果为准，本地环境补验不冒充远端通过。
