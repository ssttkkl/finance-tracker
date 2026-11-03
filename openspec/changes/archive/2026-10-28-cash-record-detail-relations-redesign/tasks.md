## 1. 思考

- [x] 1.1 读取原型 v6、UI 规范、token、共享状态、关联接口及现有回归；按 grill-me → grilling 完成澄清。
- [x] 1.2 用户确认统一保留／恢复，以及联合方案改选允许搜索但禁止提交；既有财务规则、协议和非目标不变。

## 2. 计划

- [x] 2.1 使用 openspec-update-change 回写 proposal、design 和 delta 的 v6 状态机、作用范围和验收，保持 A 类。
- [x] 2.2 使用 compose-material3、Hallmark 预检及现有词表；原型已确认，不修改原型。

## 3. 任务拆分

- [x] 3.1 单方案、整组、搜索子类型、取消／失效、核对后保存、明细缺失和恢复原命令逐项映射到失败回归及差异表。

## 4. 构建

- [x] 4.1 失败回归后重写关联弹窗状态机和独立只读发现状态。
- [x] 4.2 完成单方案免选、完整组／拒绝／移除确认、受限搜索、冲突与恢复分支，保留基础摘要和容器。
- [x] 4.3 更新文案及语义 ID；编译和真实 Web 校验后更新演示，收到用户走查修复及直接提交授权。

## 5. 审查

- [x] 5.1 对照原型核对流程、结构、视觉业务含义、术语和可见文字；差异及处置见下表。
- [x] 5.2 独立产品／工程／设计复核、最终 diff 和 Hallmark audit 完成；本次范围无剩余阻断项。

## 6. 测试与 QA

- [x] 6.1 共享全量 421 项单测通过；Wasm、Android、iOS 共享层编译通过；Web 22 项浏览器测试通过。
- [x] 6.2 本次前端受影响回归、OpenSpec strict、资源一致性、diff 与幂等分支检查完成；资源扫描既有失败及后端历史证据另行披露。

## 7. 发布与文档收尾

- [x] 7.1 同步功能地图、现金流水和设计交接；核对主规格／词表／README／技能。
- [x] 7.2 最新演示和截图已更新；用户授权提交并推送到 origin/average-snail，不部署。本次为已验证的前端小步交付，不归档整个 A 类变更。

## 8. 反思

- [x] 8.1 测试固化核对不写入、完整方案不可降级、发现不创建编辑状态、原请求恢复、冲突返回与无关组隔离。

## 当前范围与验证证据

2026-10-28 17:31 CST，分支 average-snail。前端提交 bf578be 的比较基线为 c8032531124024108b7687179aaa5c5c5b904b98；推送前合并远端 5558766，运行时预检 HEAD 为 8b19c250725149d0fcaf2b2670cbaba1e30fe52a，之后仅更新本节交付记录。Node v26.8.1，OpenSpec 1.7.0。文件名修复的 Python／测试和旧 web/package-lock.json 属于其他工作，未纳入提交；原型及截图不提交。

Cross-platform Impact Check：共享展示、状态、资源和语义同时影响 Web、Android、iOS，无平台分叉。账本行仅增加弹窗复用所需的选择／只读参数，默认入口不变；顶部摘要、编辑、模态外框及手势不变。后端、domain/data、持久化、财务规则及依赖不变。Web 使用 Chromium，代表视口 1440 × 900／1000、390 × 1000；Native 仅编译及 host 单测，未执行设备 QA。真实数据库不迁移、不写入，现有 API 沿用已运行实例；浏览器测试拦截全部财务写入并使用合成响应。

| 当前命令／检查 | 结果与证据 |
|---|---|
| compose/ 下 ./gradlew :shared:testAndroidHostTest :shared:compileKotlinWasmJs :shared:compileKotlinIosSimulatorArm64 --no-configuration-cache | BUILD SUCCESSFUL；421 tests、0 failures、0 errors、0 skipped；Android 编译随 host 测试执行。/tmp/cash-fix-final-native.log |
| compose/qa/web/ 下 FT_COMPOSE_PREVIEW_PORT=5190 npm run test:e2e -- compose-cash-relations.e2e.ts compose-cash-detail-redesign.e2e.ts | 22 passed，真实 Compose 浏览器交互。/tmp/cash-fix-final-web.log |
| node --test compose/qa/web/tests/*.node-test.mjs | 3 passed。/tmp/cash-fix-final-node.log |
| node --test compose/tools/compose-resources.node-test.mjs | 既有中文源码扫描失败；提取 HEAD 的基线同样失败，涉及注释、演示 fixture 及原有原文字段名判断。本轮无新增硬编码用户文案；四种资源键、格式参数及中文副本一致性另验通过。/tmp/cash-fix-resources.log、/tmp/cash-suggestion-resource-baseline.log。清理扫描基线或修改规则后须补跑完整命令，不宣称此命令通过。 |
| openspec validate cash-record-detail-relations-redesign --strict | 通过 |
| git diff --check、文档链接及范围复核 | 通过；提交前复核暂存范围，排除其他功能 |
| 推送前合并验证 | git merge --no-edit origin/average-snail 无冲突，前端 compose/ 与 bf578be 逐字节相同；重跑上述共享 Gradle 命令成功（缓存有效）。PYTHONPATH=tests:.:src uv run pytest -q tests/contract/test_cash_current_relation_plan.py tests/contract/test_cash_relation_context.py tests/contract/test_cash_projection_graph_reads.py tests/contract/test_cash_import_plan_lifetime.py tests/test_cash_hotpath_equivalence.py tests/test_cash_import_posting_validation.py tests/test_cash_import_readable_equivalence.py tests/test_json_budget_equivalence.py -k 'not postgres'：852 passed、75 deselected，未运行 PostgreSQL，不将 deselected 计通过；/tmp/cash-push-merge-backend.log、/tmp/cash-push-merge-compose.log。合并前后未提交的五个无关文件保持原样。 |
| 最新演示 | FT_COMPOSE_PREVIEW_PORT=5188 FT_API_PROXY_ORIGIN=http://127.0.0.1:8002 node compose/qa/web/scripts/start-compose-demo.mjs；freshness 核验、构建及重启通过，http://127.0.0.1:5188/；/tmp/cash-fix-final-demo.log |

Web 覆盖 0／1／多备选、只读方案、保留选择返回、冲突清空后再次返回、主动添加、退款退回类型锁定、转账细分选择、联合方案禁止替换、拒绝取消与一次确认、移除有／无差异及切换继承、明细缺失、修订失效、结果未知及原键核实、多现金部分和无关组冲突隔离。正常路径断言无控制台／静态资源／意外请求错误；结果未知分支刻意中断 commit 请求，不把预期失败冒充正常网络。

失败先行：/tmp/cash-fix-red.log 中 3 项浏览器回归先失败，/tmp/cash-fix-unit-red.log 中重新核对测试先失败；独立复核的双组串入由 /tmp/cash-unrelated-red.log 复现后修复。修复后重跑上述当前命令。

截图及日志保存在 ~/.cache/finance-tracker/ui-captures/cash-relations-v6-fixed-20261028/；原始 41 张走查截图保留在 cash-relations-v6-review-20261028。整个 A 类后端最终验收、归档另按其交付范围核验；历史 90 项双库契约及全量分片不算本次当前通过。本次没有服务端、存储、金额计算、权限或依赖改动，不重跑双库、后端全量、性能及安全专项；共享层禁止提交及幂等分支在当前单测和浏览器用例重跑。

## 审查与原型对照

| 范围／finding | 处置及最终对照 |
|---|---|
| P1 冲突处理后返回空核对 | 保留进入预览前的来源步骤，清空冲突后重新核对再返回仍显示原方案／搜索；单测及浏览器通过 |
| P1 等额现金部分无法区分 | 标明当前／对侧流水、对方、账户、部分名称及金额币种；提交校验两端 component_id |
| P1 独立复核发现其他建议冲突串入 | 仅消费本次接受组；手动新增按 kind/subtype 与实际两端匹配动态组，保留真实新增冲突。双组及动态新增单测、浏览器通过，独立重审无阻断项 |
| P2 无额外影响时缺少保存后结果 | 所有关联决定均显示计划内保存后收支；删除空确认标题，仍须最终确认 |
| 拒绝／移除重复确认的原型差异 | 原确认页先获取只读预览，当前选择与计划匹配才可确认，取消无写入；移除切换决定后等待新计划，一次明确确认保存 |
| 正文及原文 | 普通标题无折叠；关联仅对侧，单成员无重复摘要，多成员同样式身份／关系标签；宽屏及 compact 截图符合结构 |
| 建议与选择 | 0／1／多统一；单方案预选无 radio，多个方案级单选，成员复用表格／compact 卡片；建议搜索锁定类型，主动添加才有分段类型 |
| 联合方案改选 | 保留搜索和警示，但禁止继续核对／提交；按用户确认限制，不静默降为一条关系 |
| 冲突／错误 | 冲突含清空后重新核对、明细缺失禁用保存、失效显式刷新、未知核实原命令；原型演示状态选择器不进入产品 |
| 视觉实现差异 | 用现有 Material 3、项目 token 及账本控件实现相近样式；不复制 HTML 像素。数据选择宽弹窗／compact 全屏符合表格合同，结果及确认仍用 560 dp Basic Dialog |

独立复核由 relation_review 对产品范围、数据流、取消／提交、动态新增冲突及菜单实际请求执行只读审查；串入问题修复后重审，无剩余阻断 finding。主审按 Hallmark audit 对照实际 Chromium 操作、当前代码、原型和截图检查层级、键值对齐、操作优先级、危险色、标签、滚动及窄屏卡片；本次范围 0 critical、0 major、0 minor，评分 P5/H4/E4/S5/R5/V4。未添加模板装饰、实现术语或重复教学文字。原有 Compose Web 弹出菜单关闭后的语义树残留保留在功能地图；真实物理点击及请求断言已证明细分传递正确。

文档收尾：现金流水、设计交接及 F-04 功能地图同步当前状态，页面数不变、Native 共享覆盖及设备未验证范围保留；当前合同写入 change delta，主规格在归档前同步。不新增领域概念，DOMAIN_GLOSSARY 不改；README 无新入口、配置或命令变化。openspec-apply-change 技能补充完整方案、返回目标、保存后结果及单次最终确认检查。

回滚：恢复本次共享展示／状态及资源，不撤销已保存关系、不改原文、不迁移数据库。推送不代表部署或归档；联合方案替换规则及整个 A 类后端最终验收保持明确边界。
