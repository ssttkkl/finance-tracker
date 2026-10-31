## 当前范围

本变更修复收支账本、详情、关联搜索及导入核对的 UI 审查问题，并落实用户对共享瀑布流、状态文案、账户分组和滚动条的后续反馈。用户已确认第一版方向，明确保留手机 FAB 原有末尾空间、已有关联在建议前、详情“编辑”主操作和关系标签位置；允许完成后归档并提交 PR 到 `main`。协议定位缺口仍作为后续任务，不在本次实施。

## 1. 思考

- [x] 阅读项目上下文、UI 设计规范、token、主规格、词表、active change、受影响代码与测试；显式调用 `grill-me → grilling` 完成目标、范围、非目标、验收和关键风险澄清。
- [x] 复核 2026-10-30 Hallmark 截图发现与运行时行为；根因包括独立实现的瀑布流能力、搜索资格在分页后过滤、滚动条按行取整，以及导入旧计划缺少新增展示字段。
- [x] 确认现有手机末尾空间足够、快捷筛选需要自动换行、月份合计整体靠右，保留既有关系顺序和操作层级。关联建议缺少服务端精确现金部分引用的协议缺口另行登记，不猜测选择。

## 2. 计划

- [x] 更新 proposal、design 和四项 delta：`cash-ledger/cash-ledger-browser`、`cash-ledger/transaction-relations`、`import/cash-import-session`、`shared/cross-platform-presentation`；列明场景、失败、兼容、响应式、验证和回滚。
- [x] 将主流水类型、具体待处理原因、导入整份计划月度汇总及旧计划刷新语义写入合同；保留现金修改计划的通用合同版本。

## 3. 任务拆分

- [x] 将视觉与交互、服务端合同、共享组件、回归、独立审查、跨端验证和文档收尾对应到场景；关联搜索资格、旧导入计划兼容、滚动条连续拖动先写失败回归。

## 4. 构建

- [x] 4.1 导入页标题、步骤状态、账户分组与对齐；仅多来源渠道显示渠道标题和“合并不同渠道的同名账户”。
- [x] 4.2 账本和导入核对使用同一“流水类型”列：主流水类型及适用细分、业务标签与具体待处理原因；待处理行使用警告底色和有底色标签，不单列“待确认”或“已合并”。
- [x] 4.3 快捷筛选自动换行，月份合计靠右；保留手机 FAB 及已有末尾留白；桌面账本和导入核对整行打开，去掉行尾小眼睛。
- [x] 4.4 关联流水与账单原文复用成员卡布局，关系标签位于金额前，字段上方有分隔线；保持已有关联先于建议及详情“编辑”主操作。
- [x] 4.5 添加关联按钮明确为“查找流水并关联”；关联搜索只返回当前关系可选目标，资格过滤在服务端分页前执行。
- [x] 4.6 详细筛选固定底部“应用”统一生效，正文独立滚动；关闭、Escape 和返回放弃未应用修改，重置只改本地条件；复用日期和精确金额输入规则。
- [x] 4.7 唯一 `CashWaterfall` 组件供账本、导入核对及关联搜索共用时间分组、吸顶表头、右侧滚动条、列标题与行；账本和导入启用可选的月度合计。
- [x] 4.8 滚动条在已加载行内按像素连续拖动，跨未加载页仍按序号定位；补充 24 条离线账本数据与多来源导入场景。
- [x] 4.9 服务端公开导入计划补齐类型字段、按整份计划计算月度合计；旧计划读取、分页及提交返回 `plan_changed`，结果核实返回 `not_committed`，同会话重新预览生成完整计划；不兼容的旧合同版本继续拒绝。
- [x] 4.10 更新共享 DTO、映射、演示仓库、文案及对应单测／合同测试；不实施缺少精确引用的关联建议协议定位。

## 5. 审查

- [x] 5a 独立只读产品／工程／设计及最终 diff 复核：发现导入排序、疑似重复原因、业务标签、添加关联文案和旧测试断言，均已修复或按当前实现更新。未发现新的财务写入或权限变更；回滚为还原本 change 的共享 UI、接口展示字段与文档，无迁移。最终复核另发现单渠道隐藏分组后昵称丢失和演示退款错误业务标签，已先复现昵称回归再最小修复；原主规格三处通用状态标签表述已完整回写 delta。
- [x] 5b `$codebase-degradation-review` 调度独立契约设计与 UI 复用审查。契约评审发现公开类型字段遗漏、旧计划静默缺字段、旧计划结果核实／重新预览，以及不兼容版本校验遗漏；均经失败回归修复，导入专属检查不影响现金修改计划。UI 复用评审发现 REGULAR 关联搜索滚动条位置偏离，已收回共享 `CashWaterfallViewport`。低风险余项：三个调用点仍各自提供分组边界；单值辅助列参数可后续精简，不扩大本轮重构。
- [x] 5c 最新源码、12 个完整 requirement 与最终六图完成独立范围／设计复核，0 critical、0 major 阻断项；账户身份、演示标签和规格冲突均关闭。未覆盖的键盘／屏幕阅读器行为与既有 CMP 缺陷保留在下方验证边界。

## 6. 测试与 QA

- [x] 测试先行：服务端关联搜索资格、导入展示合同及兼容、共享滚动条连续位置分别先失败后转绿。
- [x] 服务端受影响合同与单测：SQLite／真实 PostgreSQL 专用库 `ft_cash_ui_audit_20261031_test` 的目标矩阵先前 339 passed、0 skipped；导入计划文件在最终兼容修复后 90 passed、0 skipped。最终后端非性能完整回归按文件分成四组运行：1292／1161／1116／1038 passed，合计 4607 passed、1439 skipped；跳过项不计通过，受影响 PostgreSQL 合同另行执行。
- [x] 最终 `./gradlew :shared:testAndroidHostTest :androidApp:compileDebugKotlin :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinJs --offline`：49 tasks，BUILD SUCCESSFUL（28s）；Android Host 448 tests、0 failed、0 skipped。
- [x] Web Chromium 账本、导入核对、详情、关联搜索在 390／768／1440 代表视口可打开和操作；`compose-cash-ui-audit.e2e.ts` 5 passed。导入页原 28 个场景的旧按钮选择器改为行点击后全部路径通过（整组 24 passed，后续目标补跑 5 passed）；关联维护 14 passed，表格布局 1 passed。单渠道同名账户新增回归先复现缺少昵称，修复后与多渠道分组 2 passed；字体稳定截图补跑 1 passed。
- [x] 相关 Web 补跑、最终 Compose 编译与后端完整回归已按下方结果收尾。最新离线演示 `http://127.0.0.1:5198/w/demo-workspace/` 经构建指纹校验和 49 tasks 全量重建（1m58s）；Chromium 1440×1000 与 390×900 实际搜索、开详情、滚动原文、导入文件、账户准备和进入核对，`pageerror`／`requestfailed` 均为空。
- [x] 最新页面完成可见文字必要性、范围化术语和独立设计复核。Hallmark 运行时不可用，按 audit 参考及 anti-patterns 做人工替代：沿用 FinanceTheme／M3、无新增装饰卡／内部状态标签／重复操作层级，0 critical、0 major；1 minor 为 compact 多币种汇总较高，但仍靠右且完整可读，保留既定多币种合同。截图保存在忽略目录 `compose/qa/web/playwright-report/final-ui/`，不入 Git；单渠道昵称补充截图位于同级 `test-results/`。

实际命令（均在本工作树，后端命令使用 `PYTHONPATH=.:tests`）：

| 范围 | 命令 | 当前结果 |
|---|---|---|
| 完整功能回归 | `uv run pytest --collect-only -q -m 'not performance'` 后按文件分四组执行 `uv run pytest -q --disable-warnings --tb=short -m 'not performance' <该组文件>` | 四组均 exit 0；4607 passed、1439 skipped |
| 最终导入兼容合同 | 显式设置专用 `FT_TEST_POSTGRES_URL`、`FT_REQUIRE_TEST_POSTGRES=1` 后运行 `uv run pytest -q tests/contract/test_cash_import_plan_api.py` | SQLite／PostgreSQL 90 passed、0 skipped |
| 查询预算 | 同一双后端环境运行 `uv run pytest -q tests/test_application_cash_projection_evidence.py tests/test_cash_category_performance.py -k 'evidence_reads_members_and_relations_in_fixed_batch_queries or classification_and_filter_api_endpoints_have_bounded_cost'` | 3 passed；详情固定批次与分类 API 查询预算通过 |
| 浏览器主流程 | `node scripts/run-compose-playwright.mjs tests/compose-cash-ui-audit.e2e.ts` | 5 passed |
| 账户补充回归 | `node scripts/run-compose-playwright.mjs tests/compose-cash-import.e2e.ts -g 'single channel keeps\|account choices are grouped'` | 2 passed；后续仅截图等待字体的目标补跑 1 passed |
| 预览基础设施 | `node --test tests/*.node-test.mjs` | 3 passed |
| 规格与格式 | `openspec validate --all --strict`、`git diff --check` | 26 项校验通过；无空白错误 |

性能检查：`pytest -q -m performance` 为 70 passed、3 failed；其中 PostgreSQL 分类删除 775ms 超过 500ms、旧关系操作超过 100ms，在相同环境 `origin/main` 对照中分别以 582ms 和 add／confirm P95 133／122ms 复现，独立调用链审查确认两条计时路径不进入本变更代码。当前配对选择与提交整组 P95 200ms 超过 170ms，单独同环境补跑 P95 104ms、1 passed；main 对照该项也通过。未放宽预算，仍记录本机整组计时波动与两条既有超时风险。

浏览器边界：详情／草稿／手工记录等扩展组 37 个场景中 31 条路径通过；当前列表 3 个视口保留键盘定位与关闭详情后语义树未恢复的问题，草稿 3 个视口保留禁用状态语义断言失败。指针主流程（筛选、重试、打开详情与来源重试）已实际操作。上述既有 Compose Web 限制见 `docs/shared/feature-map.md` 的 CMP-10623 及禁用语义说明，未删除失败断言，不宣称全组通过。

验证环境与边界：2026-10-31（Asia/Shanghai）；验证执行于工作树 HEAD `7462aaf6d6589c75f6958109bb4580de450f127d`；该提交树与比较基线 `origin/main` `00c927fb5e330e48f2b5c4688d0563988735fcfd` 完全一致。为避免已合并历史进入 PR，保留原样工作树后切到以该 main 为基线的 `fix/cash-ui-audit-findings`；切换未改变任何源码或依赖，最后账户回归与 Compose 编译在新分支完成。OpenSpec CLI 1.7.0、Node v26.8.1。PostgreSQL 使用本机专用 `_test` 库并显式设置 `FT_TEST_POSTGRES_URL`，skip 不计通过。Chromium 通过仓库 Playwright 运行；设备／模拟器交互 QA 按默认范围未执行，不宣称通过。Web 1440 首次加载中文字体有短暂渲染延迟，稳定后页面可读；未发现失败资源请求。Cross-platform Impact Check：展示逻辑在 `commonMain`，Web／Android／iOS 消费相同 DTO、文案和语义；后端展示字段供三端使用，原生仅执行编译与单测。

## 7. 发布

- [x] 7.1 README、三个功能地图、词表、导入流程／合同与实现一致；受影响文档本地链接已检查，12 个 delta requirement 已逐块与主规格核对一致，严格校验及 `git diff --check` 通过。无新配置、依赖、迁移或部署操作；回滚为还原本次提交，已有导入旧计划需重新预览。已删除本任务临时文件、最终测试重建的 `/private/tmp/ft-compose-cash-evidence` 及 main 性能对照临时源码，保留忽略目录中的必要证据。
- [ ] 7.2 在用户已授权的范围内归档变更，提交并推送 `fix/cash-ui-audit-findings`，创建目标为 `main` 的 PR；不合并或部署。
