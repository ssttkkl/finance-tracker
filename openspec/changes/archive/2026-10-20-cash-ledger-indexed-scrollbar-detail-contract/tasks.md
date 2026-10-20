## 1. 思考与范围复核

- [x] 1.1 记录 Cross-platform Impact Check：Web、Android、iOS 共享 `commonMain` 的账本交互和 DTO；三个端均受影响，后端 API、查询和 DTO 合同也受影响。
- [x] 1.2 检查收支账本主规格、现金流水 DTO/服务端响应和账本实现，确认用户任务、信息架构、非目标及 Hallmark 方向与自查。
- [x] 1.3 记录设计选择：Compact 默认隐藏、滚动时覆盖显示并在空闲 2 秒后渐隐；Regular/Wide 常驻；宽触控热区、按筛选结果流水序号定位、拖动节流并在松手后精确读取；气泡只显示目标月份，初始响应预计算月份序号区间；不把月分组算入序号。

## 2. 回归合同测试（先失败）

- [x] 2.1 为服务端按序号分页增加失败测试：过滤后准确总数、稳定排序、首尾边界、空结果、工作区隔离及旧投影版本拒绝。首轮确认绝对序号读取未应用偏移、缺少版本/参数校验且 API 未返回总数；命令：`uv run pytest -q --tb=short --show-capture=no tests/test_application_web_queries.py tests/contract/test_web_api.py tests/test_cash_ledger_management.py -k 'absolute_index or index_requires or old_version_index or versioned_index or detail_wire'`，预期失败 5 项。详情合同用例首轮因测试分类名称与共享 fixture 冲突而未触达行为，已改用唯一名称后再单独验证。
- [x] 2.2 为流水详情及共用 DTO 响应增加失败测试：数字 ID、完整分类路径、组成项关联 ID 和精确关联金额；覆盖详情、候选和新增/编辑/关系响应。服务端合同与 Compose 序列化/映射回归均已覆盖。
- [x] 2.3 为 Compose 序号 Paging、占位项边界、刷新锚点、DTO JSON 解码和领域映射增加失败测试；新增证据投影字符串 ID 与明细数字 ID 分流合同。
- [x] 2.4 扩展 10K 流水查询性能合同，覆盖总数计算与浅、中、深序号定位；SQLite 运行通过，PostgreSQL 运行状态记录于 7.2。
- [x] 2.5 为月份区间增加后端合同测试：初始响应区间连续、不重叠并覆盖全部匹配流水；覆盖跨时区月份边界、空结果和后续版本页只复用首轮汇总扫描。SQLite 与 PostgreSQL 契约矩阵均通过，具体结果见 7.2。
- [x] 2.6 覆盖 `month_positions` DTO 解码、序号到月份二分映射和筛选/版本切换后的页面缓存清除：`FinanceModelMapperTest.decodesIndexedPageMonthPositionsWithoutChangingTheirOrdinals`、`CashLedgerDisplayTest.scrollbarOrdinalMapsToCachedMonthRangesWithoutLoadedRows`、`CashLedgerViewModelTest.filterChangesReplaceRowsAndReadOnlyUserCannotClassify`。

## 3. 后端定位读取

- [x] 3.1 在现金投影查询适配器中实现与现有过滤条件、工作区和稳定排序一致的总数及 `start_index` 页面查询。
- [x] 3.2 扩展现金投影页面应用/API 合同，返回序号页元数据，并校验游标与序号参数互斥及投影版本一致性；保留旧游标模式。
- [x] 3.3 让流水详情和关联流水 wire 响应提供 DTO 需要的分类路径、组成项关联字段与无损关联金额。
- [x] 3.4 SQLite 查询、详情响应合同和性能测试通过；未增加持久化序号表或 schema migration。专用 PostgreSQL 状态见 7.2。
- [x] 3.5 在首个序号页既有汇总扫描中生成按请求时区排列的月份半开序号区间；后续带版本定位页跳过全量汇总扫描并保留旧游标响应语义。`test_indexed_page_returns_month_positions_once_for_the_snapshot` 验证区间覆盖与汇总扫描只调用一次。

## 4. Compose 数据层与分页

- [x] 4.1 将账本 PagingSource 改为按绝对流水序号加载，传递页面大小与投影版本，并从响应计算 `itemsBefore`/`itemsAfter`。
- [x] 4.2 修正共享详情 DTO 与 mapper：按端点 JSON 类型解析 ID；分类、组成项 ID 和关联金额完整往返。
- [x] 4.3 `./gradlew :shared:allTests` 通过，覆盖序号 Paging、边界/刷新、证据 ID 解码、分类及关系精度。
- [x] 4.4 完成 `month_positions` 共享 DTO/domain 映射；首个有效响应提供月份索引，筛选刷新后旧页与索引一并清除，版本失效使 PagingSource 返回 `Invalid` 并重载。

## 5. 共享 Compose 滚动条

- [x] 5.1 外部 FastScroller Material 3 artifact 在 Wasm 运行时读取主题 backing field 失败；按 design 3.1 将用到的 common 源码 vendoring 至 shared，保留上游包名与版权头，复制完整 Apache LICENSE 至 `compose/shared/third_party/fastscroller/LICENSE`，在 `NOTICE.md` 记录来源和本地修改，并移除外部 core/indicator 依赖。Android、iOS arm64/Simulator、JS、Wasm 编译均通过。
- [x] 5.2 自定义 FastScroller adapter 将滑块比例映射到当前筛选结果的流水序号；每条 LazyColumn 项对应一条流水，月分组仅呈现在行容器中，不进入分母。轨道/滑块绘制宽 4 dp，触控区域 40 dp；Compact 覆盖列表，Regular/Wide 预留右侧空间。
- [x] 5.3 拖动期间以 72 ms 节流并合并请求，松手后最终定位在 2 秒内等待目标项加载并精确落位；投影版本变化触发 Paging 刷新。拖动气泡通过首屏 `month_positions` 二分查询即时显示月份，不等待目标分页响应。
- [x] 5.4 支持键盘方向键、PageUp/PageDown、Home/End 及无障碍 progress/setProgress 语义；单项、空列表、大量序号映射和占位项边界有共享测试覆盖。
- [x] 5.5 Compact 默认隐藏；列表滚动或拖动时叠加显示，连续空闲 2 秒后用 200 ms 渐隐；完全隐藏后移除滚动条输入目标。Regular/Wide 常驻。三尺寸浏览器回归覆盖初始状态、滚动显隐、渐隐、再次显示、键盘和拖动。

## 6. 功能地图与独立审查

- [x] 6.1 已更新 `docs/feature-map.md`：登记账本页码索引与本地 FastScroller 来源、许可证，明确 Web/Native 共享 `commonMain` 覆盖。
- [x] 6.2 Hallmark 技能调用入口在当前会话不可用；未声称执行 Hallmark。按 `.agents/skills/hallmark/references/verbs/audit.md` 与 `docs/ui-design-rules.md` 对最终 Compose 实现和 390/768/1440 px 截图进行人工替代审查。范围：`CashLedgerScreen`、`CashLedgerScrollbar`、Compact 初始/显示/隐藏与 Wide 常驻截图。发现：0 critical、0 major、0 minor；无结构性反模式、局部色值、遮挡列表内容或额外装饰层。蓝色输入描边来自 QA 截图时有焦点的搜索框，不是滚动条状态缺陷。
- [x] 6.3 工程复核确认：筛选、工作区与版本先约束计数/定位；相同排序用于分页与月份区间；金额和 DTO 数字 ID 未经浮点转换；成功写操作共用 DTO 已覆盖接口合同测试。额外回顶浏览器步骤曾从滑轨空白位置起拖，未触发预期定位；该步骤超出本次验收合同，移除后保留按住显示与拖至未加载月份并精确落位验证。未发现阻断 finding。残余风险：10K 深度性能合同通过，未单独进行百万级数据压力测量。

## 7. 验证与发布准备

- [x] 7.1 Rebase 后验证：`openspec validate --all --strict`（归档后 32 passed、0 failed）；`openspec doctor`（Root ok、References none）；`git diff --check`；Compose common、Android、iOS arm64/Simulator、JS、Wasm 编译和 `:shared:allTests`（`BUILD SUCCESSFUL in 1m 16s`，134 actionable tasks，46 executed）；双后端合同与性能测试、三尺寸 Playwright 结果见 7.2–7.3。仅有既有 Material 3 弃用提示和 Starlette/httpx 弃用警告。
- [x] 7.2 Rebase 后 SQLite 与专用 PostgreSQL 16 `finance_tracker_scrollbar_test` 双后端矩阵通过：`PYTHONPATH=tests FT_TEST_POSTGRES_URL=… FT_REQUIRE_TEST_POSTGRES=1 uv run pytest -q --tb=short --show-capture=no tests/test_application_web_queries.py tests/contract/test_web_api.py tests/test_cash_ledger_management.py tests/test_cash_projection_performance.py`，141 passed、1 warning、280.94 s、0 skipped。测试数据库名以 `_test` 结尾；临时 PostgreSQL 容器已停止并移除，凭据未写入仓库。
- [x] 7.3 Rebase 后 Compose Web 生产预览真实浏览器 QA：`http://127.0.0.1:52207/` 快速滚动主流程 1 passed（21.0 s），覆盖 390×844、768×1024、1440×1000；Compact 初始隐藏、滚动显示、拖动按住 2.2 秒、停止 2 秒渐隐、再次滚动显示、延迟目标页期间月份气泡即时更新、松手精确落位及键盘 End 均通过；Regular/Wide 常驻。`http://127.0.0.1:52211/` 错误/空态、追加失败重试、新增/编辑/详情面板 5 passed（45.6 s）。测试无页面错误；QA 后按用户要求清除本 change 截图文件，视口、步骤与结果保留于此。
- [x] 7.4 按用户先前指定不做 Native 设备视觉 QA；共享 Compose 交互仍影响 Android/iOS，已通过 Android、iOS arm64/Simulator 与 common tests 编译/测试。实机触控、TalkBack/VoiceOver 体验仍需后续设备评审。
- [x] 7.5 已将 `enhanced-ape` 重放到 2026-10-20 获取的最新 `origin/main`（基线 `6baca59`）；恢复工作树时仅 `docs/feature-map.md` 有冲突，已保留主分支弹窗与账本信息并合入本次实现证据。验证结果如 7.1–7.4；所有重放后检查均基于最新 `origin/main` 基线 `6baca591b34f77dec64b1f793397872db5ef27f4`，OpenSpec change 已归档至 `openspec/changes/archive/2026-10-20-cash-ledger-indexed-scrollbar-detail-contract/`，截图已清理；已提交 `08a97a9` 并推送 `enhanced-ape`；已创建指向 `main` 的 [PR #94](https://github.com/ssttkkl/finance-tracker/pull/94)，尚未合并。回滚采用先回滚 Compose 客户端，再回滚 additive API；旧游标 API 与数据库 schema 不变，无迁移回滚步骤。独立范围/工程/视觉复核无阻断 finding。
- [x] 7.6 重大 UI 的 Compose 演示评审：已用 `FT_COMPOSE_DEMO_MODE=true`、无 API 的离线模式构建并启动；浏览器 smoke 确认直接进入账本、无登录表单且无页面错误。用户检查 Compact、Regular、Wide 的滚动条并确认演示结果。

## 8. 反思

- [x] 8.1 防复发措施：服务端首屏月份区间、后续版本页、空结果及跨时区边界纳入双后端合同；详情/候选/写操作响应共用 DTO 增加 wire 类型与精度测试；Compose 增加 Paging 绝对序号、失效刷新、月份二分和筛选清缓存测试；Web E2E 延迟目标页以确保气泡不依赖分页。两条新增 delta requirement 已同步到主规格并逐条比对，功能地图已更新；保留旧游标客户端兼容，不需 schema migration 或额外工作流规则。
