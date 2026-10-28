## 1. 思考

- [x] 1.1 阅读项目上下文、现金关系及详情规格、词表、active changes、现有 Compose 和后端代码，使用 openspec-explore；由独立事实调查上下文复核候选与完整组语义。
- [x] 1.2 使用项目 grill-me → grilling 澄清：自由搜索、保留财务规则、仅导入流水；纳入用户追加的弹窗操作、删除无影响操作、仅实际人工覆写差异时询问保留。

## 2. 计划

- [x] 2.1 建立 A 类 proposal、详情与交易关系 delta 及 design，补充搜索与维护新增关联合同；无数据库迁移。
- [x] 2.2 应用 compose-material3 与 hallmark，读取 UI 规则和 token，形成 HTML 原型；原型位于 Git 忽略目录。
- [x] 2.3 用户已确认最新 `cash-record-detail.html`，以其流程、结构及样式含义移植；两个正文区块不折叠，弹窗选择复用账单表格及 COMPACT 卡片。完整约束见 [设计交接](../../../docs/cash-record-detail-design-brief.md)。

## 3. 任务拆分与一致性

- [x] 3.1 对齐关系标签、候选六类状态、搜索、弹窗确认、解除差异及异常场景。
- [x] 3.2 design 明确 association-targets 只读查询、relation_additions 预览命令、服务端证明、多现金部分选择、完整组影响及 split_field_diffs；复用现有持久化，无迁移。

## 4. 构建

- [ ] 4.1 在独立 feature 分支或 worktree，先写搜索与新增关联的失败契约：资格、权限、金额币种、方向、来源证明、占用、退款额度、完整组、幂等及结果未知。
- [ ] 4.2 实施工作区导入流水搜索与共享维护新增关联预览／提交；保持原始事实和既有决定。
- [ ] 4.3 先写共享状态与字段归属失败测试，再实现 DTO、关系标签、候选方案、弹窗流程、选择失效及原文层级。
- [ ] 4.4 为解除人工覆写实际差异增加失败测试；覆盖无覆写、覆写同值、覆写不同值、清空、受影响字段和值及完整组。
- [x] 4.5 原型及后续反馈方向已获用户确认；最新反馈通过编译和浏览器复核，更新 `http://127.0.0.1:5188/` 演示。
- [x] 4.6 按用户反馈恢复原型层级：紧凑筛选、完整建议摘要、卡头同行标签、等宽单号、正确弹窗标题及 COMPACT 全屏；浏览器核对真实布局和操作后更新演示。
- [x] 4.7 按反馈将正文入口改为“添加关联”，为添加、移除、编辑、接受／核对、拒绝操作分别补上加号、断链、编辑、勾选、叉号图标，保留文字；复用共享资源并验证最新演示。
- [x] 4.8 按反馈让单备选对侧流水与已有关联流水共用完整基础信息布局，备注补上标签；先验证缺失字段回归，再编译、浏览器复核并更新演示。多个备选和零备选流程保持既定合同。
- [x] 4.9 添加关联弹窗正文顶部用 Material 3 单选分段按钮选择三种关系，搜索框位于其后；覆盖选择前可用、切换选中状态及提交沿用选择。

- [x] 4.10 按反馈调整建议操作左右顺序、危险色及边框，统一“核对建议”；补回当前流水的关系待确认提示；按最新反馈仅保留标题与说明、移除查看按钮及定位逻辑，覆盖有／无待确认状态和提示无按钮。

## 5. 审查

- [x] 5.1 独立产品／范围、工程与安全复核：提交前发现公共冲突界面 P1，以及本地日期、明确清空、混合关系摘要三个 P2；按 design 原位修复并重审。现有图规则覆盖金额、方向、币种、退款额度和占用。
- [ ] 5.2 最终 Compose 做文字必要性、范围术语搜索、Hallmark audit 和独立设计复核；原型不替代最终审查。
- [x] 5.3 最终 diff 与 artifact 一致性复核，补齐全部原文及关系端点数据，避免首边类型冒充多边方案。

## 6. 测试与 QA

- [ ] 6.1 Compose 真实浏览器检查：已检查正文原文直接显示及弹窗复用表格／卡片；搜索新增完整保存、取消保留底层、校验失败、原文标签、联合／冲突及解除两分支仍待补齐。
- [x] 6.2 最终运行受影响测试、完整回归、类型检查、构建、OpenSpec 校验和 diff 检查。
- [x] 6.3 同一 Application Service 跑 SQLite／真实 PostgreSQL 契约；人工准备 `_test` 库并显式设置 FT_TEST_POSTGRES_URL，缺配置记未完成。
- [ ] 6.4 最终 Web 真实冒烟及权限、幂等、搜索性能专项；Android／iOS 相称编译和单元测试，默认不追加设备 QA。

## 7. 发布与文档收尾

- [x] 7.1 功能地图登记 F-04 共享实现及三端待验收范围，页面数不变；词表登记用户可见“关联建议”，内部候选标识保留。
- [x] 7.2 核对 README、现金流水、接口、UI 规范、词表和功能地图，更新过时行为及接口说明；README 无新增入口，协议集中在现有参考文档。尚未归档，归档前同步 delta。
- [x] 7.3 记录本开发小步的验证、回滚与观察项，按用户授权提交并推送 `origin/average-snail`；不创建 PR，不部署，完整变更最终验收仍按未完成项推进。

## 8. 反思

- [x] 8.1 设计约束：不能把一条关系边、一个对侧成员和一套完整方案当成同一层对象；不能把无影响的待确认状态做成按钮。
- [x] 8.2 保留冲突阶段、类型化清空、时区、完整关系摘要和无按钮提示回归；浏览器按真实请求验证，避免误用依赖未提供的 DOM 禁用语义。

## 当前审查与验证

2026-10-28（Asia/Shanghai）。分支 `average-snail`，当前 HEAD `02d5d81fff7b9b7333fd84a3dea89e32d9dcd8c5`；开发小步含已验证的 `013f0683058b6b9d455b8b796257280558d94414` 合并树，本轮另整合远端 `178c7237089a85f6376d83dcf0e7a51844484fd1`，补跑以下受影响验证。Node.js `v26.8.1`、OpenSpec `1.7.0`。用户已授权本地提交并推送当前分支；旧文件名展示修复及旧 React lockfile 保留备份，未纳入本次代码。

本次交付是已验证的开发小步，保留当前实现、失败回归、独立审查和证据；未宣称整个 A 类变更已最终验收，不归档、不部署。未勾选的完整浏览器保存／复杂分支验证继续保留，不能由本次提交推断已完成。

| 检查 | 实际命令及结果 |
|---|---|
| 全量非性能回归（02d5d81 代码树） | `PYTHONPATH=tests:.:src uv run python scripts/run_backend_tests.py --shard-index N --shard-count 8`，N 为 0–7；合计 3605 passed、1351 skipped，所有分片 exit 0，集合指纹均为 `3d15b009dc4b71ba8e109607a268c604d7c22e74c748f766db90a8c3548e6b70`。skip 主要为未配置的全量 PostgreSQL／外部数据夹具，不作通过计数。最后一片移除继承给子进程的 `--basetemp` 后重跑通过；代码未改变。 |
| 当前完整关系计划双后端 | 显式设置专用 `FT_TEST_POSTGRES_URL`，运行 `PYTHONPATH=tests:.:src uv run pytest tests/contract/test_cash_current_relation_plan.py -q --tb=short`，60 passed、0 skipped（157.83s），包括混合关系摘要、候选端点及公开过滤。 |
| 新增搜索／关联及拆分双后端 | 同一专用配置运行 `PYTHONPATH=tests:.:src uv run pytest tests/contract/test_cash_manual_associations.py tests/contract/test_cash_association_split.py -q -x --tb=short`，30 passed、0 skipped（63.61s），包括本地日期边界和明确清空后的预览、提交。 |
| 共享编译和单测 | 在 `compose/` 运行 `./gradlew :shared:compileKotlinWasmJs :shared:testAndroidHostTest :shared:compileKotlinIosSimulatorArm64 --no-configuration-cache`，成功（51s）；Android host XML 为 414 tests、0 failures、0 errors、0 skipped。 |
| Web 主流程与反馈 | 在 `compose/qa/web/` 运行 `npm run test:e2e -- compose-cash-detail-redesign.e2e.ts`，7 passed（29.5s），Wasm distribution 通过 freshness gate。 |
| 178c723 整合后验证 | `PYTHONPATH=tests:.:src uv run pytest tests/test_cash_hotpath_equivalence.py tests/test_value_copy.py tests/contract/test_cash_entry_lookup.py tests/contract/test_cash_import_business_plan.py tests/contract/test_cash_manual_associations.py tests/contract/test_cash_association_split.py tests/contract/test_cash_current_relation_plan.py tests/contract/test_cash_mutation_commit.py tests/contract/test_cash_draft_balances.py tests/contract/test_cash_projection_parity.py -q -x --tb=short`，186 passed、140 skipped（82.19s）。显式设置同一专用 PG 配置后重跑新增搜索、拆分和当前关系计划三文件，90 passed、0 skipped（76.84s）。共享源、资源及构建配置与 02d5d81 完全相同，Web／原生既有结果仍适用，不重复无影响的编译。 |
| 基础检查 | `uv run python -m compileall -q src`、`openspec validate cash-record-detail-relations-redesign --strict`、`git diff --check` 均通过；项目未配置独立 Python 类型检查器。 |

双后端使用本机专用 PostgreSQL 容器的 `ft_cash_detail_test` 库，显式配置而非测试自动探测／启动；不连接真实账本，不在记录中保存凭据。只读搜索专项在仓库外 SQLite 夹具加入 10000 条合成流水，默认页 20 条、精确搜索 1 条、未找到 0 条，分别 22.77／7.66／6.14 ms，并核对分页标志；没有以这组读性能代替财务写入验证。日志位于仓库外 `/tmp/cash-detail-*.log` 和 `/tmp/cash-detail-shards/`。

独立复核另检查 178c723 的 18 个文件及两个自动合并交点：小批读取的工作区边界、待写回退、人工目标加载不受自动候选裁剪影响，拆分字段和混合摘要未被覆盖；未发现新的 P1/P2。独立产品／工程／安全复核覆盖新增参数到真实端点、证明、完整图与同事务写入，以及 UI 与 ViewModel 的状态衔接。发现公共冲突页面 P1，本地日期、清空和首条类型冒充完整组三个 P2；均已修复并重审，无剩余 P1/P2。金额、方向、币种、退款额度、占用仍由既有关系策略和最终图校验，提交再次校验；没有绕过公共规则。公共清空只增加对应空值，未开放任意新公共值；公开关系摘要和端点保持白名单过滤。此前各问题均先有失败契约／单测或具体复现，再修复。

Web 证据为 Playwright Chromium，1440 × 1000、390 × 1000，合成 API fixture：提示仅标题和说明、无按钮；拒绝危险色文字按钮在左，接受／核对描边在最右；0／1／2 个备选和混合关系标题；完整基础信息、备注标签；普通标题直接呈现关联及原文；全部实际成员及身份标签；宽屏表格、紧凑卡片、顶部关系类型分段选择、搜索、预览参数和取消重开。新增冲突分支实际点击验证未选择时不发送预览，清空后仍携原 accept/candidate_id 并到达确认保存。截图在 Git 忽略的 `compose/qa/web/test-results/compose-cash-detail-redesi-*/`，未写入真实财务数据。

既有 Compose Web 关闭弹窗后语义树残留、控件禁用／选中状态未完整导出为 DOM 属性仍是上游依赖限制。测试通过真实鼠标及请求核对，不伪造属性、不升级依赖；冲突流程在首次稳定打开的弹窗内验证，取消重开由其他代表分支覆盖。这些限制记录为后续无障碍复核项，不宣称已修复。

`$hallmark audit` 对照用户确认原型、最新约束、共享实现及上述实际截图：无按钮警示保留层级，字段和值可辨认，操作顺序及危险含义正确，混合类型不被首条替代，冲突选择与结果确认在弹窗内，宽／窄屏没有本轮新增截断。当前检查范围 0 critical、0 major；既有 Web 语义问题如上保留，不将审查扩大到未覆盖流程。范围化术语搜索只替换本任务的“核对建议”，导入核对等其他任务原有词语保留。完整变更独立设计与最终 QA 仍以未勾选任务为准。

组件依据：已读 Android Developers Button、Segmented button 正文，采用当前 Material 3 `1.12.0-alpha03` 的文字／描边按钮、`SingleChoiceSegmentedButtonRow`、默认图标尺寸与间距及项目 warning/error token。Material 3 官网 overview/specs 在普通抓取中仅返回脚本壳，真实浏览器超时／连接关闭，未声称已读正文；本轮按用户明确指定的组件、布局和官方 Compose API 实施，不引入新视觉系统或依赖。

Cross-platform Impact Check：Web、Android、iOS 共用展示、DTO、状态和文案；COMPACT 复用流水卡片，其余宽度复用表格。已完成对应共享编译、Android host 单测和 Web 代表视口；默认不运行原生设备 QA，也不宣称设备 QA 通过。未增加页面，功能地图保留开发／未验收范围。

演示入口为 `http://127.0.0.1:5188/`，最新 UI 构建通过 freshness gate；同源代理 8002。现有 API 实例仍连接 `~/.ft/finance-tracker.sqlite3`，本次没有重置或迁移真实数据库；远端包含的索引迁移与新版 API 重启属于后续本地服务更新，不能把当前运行实例当作新版后端已部署。

文档收尾已核对现有主入口、现金流水说明、维护协议、词表、功能地图、UI 规则及技能接线说明；过时折叠、无影响操作、禁止主动搜索和无差异也询问继承的描述已原位更新。回滚本开发小步可还原本次应用／共享代码；不撤销远端既有性能及索引迁移，不自动回放关系决定。后续重点验证联合方案／冲突／解除两种保留决定的完整浏览器保存、新旧关系同事务及全面无障碍，不以合成预览冒充这些最终验收。
