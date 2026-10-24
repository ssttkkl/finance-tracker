## 1. 思考

- [x] 1.1 通过 `grill-me → grilling` 确认保留现有外观、原详情容器和在流水内处理关系；对照当前列表、详情与维护协议核实缺口。

## 2. 计划

- [x] 2.1 完成 proposal、delta 与 design，明确列表／详情／补处理边界、公共基础、文件所有权及三模块依赖。
- [x] 2.2 完成独立规划复核及 OpenSpec strict，确认旧 UI delta 不与新责任重复；只声明规划就绪。

## 3. 任务拆分与一致性

- [x] 3.1 先补当前读取、`entry`／来源权限、维护所有意图、公共冲突／继承、草稿保存、计划／收据／核实的协议失败测试；验证 null、清空、修订、字符串金额和工作区隔离。
- [ ] 3.2 完成公共基础与宿主：
  - [x] 3.2.1 当前 DTO／domain／Repository、读取及维护 API、公共冲突和定位引用通过测试与构建。
  - [ ] 3.2.2 接入编辑宿主和稳定定位行为，冻结集中声明的文件归属。
- [ ] 3.3 提交公共基础小步，让导入和编辑模块基于同一版本开发；合成合同夹具、语义 ID／文案与最终 App 集成接口分别登记所有者。

## 4. 构建

- [x] 4.1 以失败测试接列表状态／未知值／统计／待确认快捷筛选和快照翻页；保留原筛选、月汇总及批量选择行为。
- [x] 4.2 以失败测试切换当前详情，整笔公共值总览、待处理区域和折叠只读来源，移除来源成员编辑入口。
- [ ] 4.3 以失败测试实现草稿普通保存、明确转正式、来源采用／保留及实际身份问题处理；不自动转正式或换目标。
- [ ] 4.4 以失败测试实现已有完整关系组确认／延后／拒绝／解除、公共冲突选择及解除继承；移除任意关联搜索和直接关系写入。
- [ ] 4.5 接入编辑器插槽、当前分类／删除影响及保存后的稳定定位，集成合成演示；删除无人使用的旧详情和维护适配，未实现动作保持准确受限状态。

## 5. 审查

- [ ] 5.1 在独立上下文复核产品范围、工程、API 可发现性、恢复与隐私边界，记录 finding、处置和回写；阻断项修复后重审。
- [ ] 5.2 对实际 Compose 页面执行 Hallmark audit、中文术语与可见文案必要性自检；独立设计复核清零 critical／major，不能用规划自查代替。

## 6. 测试与 QA

- [ ] 6.1 在 `compose/` 执行 `./gradlew :shared:wasmJsTest --no-configuration-cache` 及受影响 Android／iOS 单元测试；构建 `:webApp:wasmJsBrowserDistribution`、`:androidApp:assembleDebug`、`:shared:linkDebugFrameworkIosSimulatorArm64`，记录实际 HEAD、命令与结果。
- [ ] 6.2 在 `compose/qa/web/` 执行 `npm run test:node` 和受影响 Playwright，在代表视口以真实 API 冒烟受影响主流程、关键交互和直接相关复现分支，记录浏览器、URL、步骤、必要截图及 console／network；不默认穷举状态、主题或三类窗口。
- [ ] 6.3 按当前默认策略核对 Android、iOS 相称编译和单元测试证据；设备／模拟器交互、截图和端到端 QA 不在本次验收范围，不以未执行阻断，也不记为通过。
- [ ] 6.4 使用专用 SQLite 及显式 `_test` PostgreSQL 服务验证受影响真实请求与业务结果，不自动探测数据库、不写真实账本；演示只用去标识化合成场景。不得改变万行后端独立性能验收范围。
- [ ] 6.5 在 Compose 演示模式以 Web 代表视口交付受影响流程，取得用户演示确认后才勾选 UI 完成；执行 OpenSpec strict、链接和 `git diff --check`。

## 7. 发布

- [ ] 7.1 验证与审查后修订 README／业务说明／接口示例／功能地图和旧变更取代关系，原位清理过时表述，检查链接；无需修改的文档记录理由。
- [ ] 7.2 记录交付范围、依赖版本、回退和未解决风险；相称检查后按已授权仓库／分支小步提交推送，PR／合并／部署另需授权。不得通过回退恢复已关闭接口。

## 8. 反思

- [ ] 8.1 记录可复用防复发测试与必要规则，保持文档只表述当前结论；完成后再按实际依赖同步 delta，未实现内容不提前归档。

### 当前审查与验证

用户确认并发处理仅保留后端识别及重试提示，不增加自动表单合并、修订替换重放或跨版本执行兼容路径；未知提交继续使用最小原键核实。业务公共值冲突选择不受此简化影响。

当前未解决缺陷：Web 的 Compose `1.12.1` 关闭详情弹层后画布已回到账本，无障碍树仍保留详情、账本语义节点不恢复；鼠标与 Escape 路径均复现。关联上游 [CMP-10623](https://youtrack.jetbrains.com/issue/CMP-10623)，用户明确不升级依赖。保留失败回归及 `/tmp/ft-compose-cash-evidence/after-close-390.png`；对应无障碍与返回 QA 未通过，其他业务实施继续，不据此勾选 UI 完成。

2026-10-24，验证时 `HEAD=f2b1970`，前端比较基线相同；后端稳定引用已独立交付。列表、详情和草稿／来源／关系补处理已有开发接线；旧编辑器、手动转账、新导入页及最终 Web 冒烟／演示确认尚未完成，不能据此提升三端完整可用覆盖。

| 范围／命令 | 当前结论 |
|---|---|
| 产品／工程复核 | 当前 `next_action=edit`、具体草稿问题合并、其他成员问题与所属修订、保存后来源折叠已修复。原文读取校验明确成员 ID、修订及展示版本；模式切换不提交隐藏分摊，不继承旧明确采用状态。新增两项 major 经失败回归、修复及局部独立重审关闭；旧任意关系入口退役及编辑宿主仍在 4.4–4.5 |
| 简单重试 | 明确修订／过期拒绝或 `commit_state=not_committed` 释放命令，不重放；来源变化提示重读。已消费／幂等冲突及网络未知保留原键。失败／部分收据不显示成功；已知保存后的读取失败不抹去收据 |
| 共享测试与构建 | `./gradlew :shared:testAndroidHostTest :shared:wasmJsTest :androidApp:assembleDebug :shared:compileKotlinIosSimulatorArm64 --no-configuration-cache` 通过（1m4s）：Wasm 336、Android host 331，零失败／skip；Android 包与 iOS 共享编译通过。`/tmp/ft-cash-source-allocation-final-matrix.log`。本提交包含 iOS 本机时间转换实现，共享编译通过；iOS 平台单测及设备／应用包 QA 依用户本轮明确免验未执行，不记通过 |
| 原文／分摊失败回归 | `/tmp/ft-cash-source-body-red.log` 原文缺失失败1项；`/tmp/ft-cash-allocation-red.log` 明确分摊失败2项；`/tmp/ft-cash-allocation-boundaries-red.log` 模式切换及来源版本失败3项；当前完整矩阵全部转绿 |
| 真实接口 Web 冒烟 | Chromium，Web 1440×900，`http://127.0.0.1:5308/` 与专用合成 SQLite API `127.0.0.1:58792`；当前开发 QA 包指纹一致。实际列表→来源原文→双分摊修改→普通保存仍 draft→明确确认 posted 已通过，保留组成项 ID／顺序／账户／币种，仅发送 `components`；无 pageerror 或新增 console／network 错误。最终 1 passed（16.3s），`/tmp/ft-cash-split-settled-web-smoke.log`；截图 `/tmp/ft-compose-cash-evidence/split-{original,form,draft-saved,finalized}-1440.png`。生产包（4m2s）已覆盖业务源码；最终纯文案修改通过开发 QA 包（1m50s）、Android host 331 项及原生相称编译再验，`/tmp/ft-cash-source-allocation-dev-build.log`。此前实际标量保存、来源展开→保存后折叠→重新展开三宽度3 passed，`/tmp/ft-cash-source-settled-final-qa.log`；后续标签修改不复用旧截图宣称全部当前 UI 通过 |
| 浏览器已知缺陷 | 列表未知值／零额退款、筛选及来源失败重试已走通；关闭弹层后的语义树停更仍复现并按用户决定保留。确定拒绝后实际保存按钮灰色且物理点击不发送请求，但无障碍禁用状态仍报告 enabled；此项与整树冻结分开登记，未声称修复。保留相应失败回归，不因缩小默认 QA 伪造通过 |
| Hallmark／独立设计 | 重复草稿警示和缺字箭头已修复并局部复核关闭；当前原文／分摊 1440 px 截图 Hallmark audit 与独立设计复核无 critical／major；保存后截图时机的 minor 已补稳定页面并局部重审关闭；未将忙碌截图计为保存完成证据。Native 设备与穷举深色／三窗口不在本次默认门禁；重大 UI 的最终演示确认仍未取得 |
| 后端／工具／文档 | 稳定引用后端 `5a7f3ab`：121+最终2项专用双后端通过；完整SQLite3389 passed，其skip不计PG。本轮 Node 3 passed、OpenSpec strict 37/37、doctor healthy、链接和 diff 通过。其他成员来源问题在当前 QA 包 Web 1440 px 重验 1 passed（4.9s），`/tmp/ft-cash-source-allocation-member-qa.log`。协议／功能地图／skill已同步，未新增领域术语；README启动方式不变，三端完整流程TODO保留 |
| 跨端与剩余门禁 | Web、Android、iOS共用查询、草稿输入、计划／原键状态和错误语义。当前来源原文与分摊普通保存／明确转正式已跑通；编辑器和双端转账、新导入、Web代表视口演示与用户确认未完成；Native设备QA不在本次范围；10k性能独立待办不变 |
| 发布与回退 | 发布范围为当前列表／详情及草稿维护小步，完整 change 的编辑器、关系清理、统一导入及演示确认仍待完成；后端已独立交付。不改Compose依赖、不新增迁移、不操作真实账本或部署，不回退恢复旧写入口 |
