## 1. 思考

- [x] 1.1 核对主规格、编码规范、项目上下文、词表及独立根因；按 A 类处理并发发布风险。显式 grilling 已对齐匿名化边界、自然月和后端／浏览器范围；新发现仅恢复现行追加合同，无新增决策。

## 2. 计划

- [x] 2.1 完成 proposal、design；既有 requirement 不变，显式 skip_specs，禁止为校验虚构新规则。Web／Native／共享 UI 无变更，保留三并发和页面设计。

## 3. 任务拆分

- [x] 3.1 安排真实文件红例、并发与 CAS／幂等／容量／严格操作边界、最小修复、双库完整回归及独立审查。

## 4. 构建

- [x] 4.1 生成并独立核对 11 份身份脱敏月度切片、实际格式、759 行与独立 377 事实期望；原件哈希不变。SQLite／PostgreSQL 完整链路及收据、重放、重导断言已通过；仍须在最终实现重跑。
- [x] 4.2 一次选择四格式的真实浏览器先复现两个追加 409 `session_changed`；独立 trace 核对确认客户端请求同修订并发追加，安装 jar 与当前 class 一致。直接双库失败回归保存在 `append-red.log`；浏览器结果目录随后续运行替换，不把当前 trace 冒称初始失败证据。
- [x] 4.3 双库直接并发回归先红后绿，覆盖 CAS、幂等、容量、未知存储结果和严格操作；恢复追加合并，不放宽其他操作。
- [x] 4.4 保留真实浏览器 CNY 目标账户补齐 USD 的 404 红例，先补双库 HTTP 合同，再恢复既有批量币种接线与命令内并集；验证独立收据、重放、部分失败及协议／权限／存储未知边界。
- [x] 4.5 保留真实客户端时区参数导致 422 的红例，恢复预览／计划读取时区及全计划月度汇总；独立纯合同与双库真实 HTTP 验证，不更改金融语义。

## 5. 审查

- [x] 5.1 独立产品／范围、工程、安全与最终 diff 复核覆盖追加、夹具、批量币种、月度呈现和浏览器同步。按产品编码规范工程检查面核对异常、边界、写入确定性、事实保真、抽象与统计范围；测试的三个 finding、批量币种余额／程序错误边界及旧计划 resolve finding 已修复并复核，无遗留阻断。审查者只读，未修改实现。
- [x] 5.2 独立执行 codebase-degradation-review → contract-design-review，追加／独立账户命令／冻结月度依据的模型和职责一致，无新增 schema、依赖或业务规则。生产 UI 未改，UI 复用、Hallmark 与视觉设计复核不适用。权限撤销／工作区切换、永久收据残留、CAS 过期及非空选择保留的追加竞争场景只做静态核对；未穷举时区别名或动态验证所有旧版本分支。历史夹具已适配且编译，不宣称外部历史任务已运行。

## 6. 测试与 QA

- [x] 6.1 显式真实 PostgreSQL 与 SQLite 的默认后端 863 项通过、0 skip／failure；codegen 2、contracts 3、domain 10，ktlint、detekt 与 installDist 通过。历史测试合法夹具适配后另编译和检查通过；其默认收集集合不变，外部证据仍未运行。
- [x] 6.2 Chromium 在 `http://127.0.0.1:5188`，1440×3000，真实四格式选择、9 组账户、USD 补齐、同冻结计划刷新恢复、确认 206 条、返回账本请求和刷新后的实际流水行均通过（33.5 秒，0 自动重试）。总预览／确认各一次，确认绑定原计划 ID／修订；控制台、API／静态错误、失败请求和诊断均为零。两次真实刷新用于 Compose 关闭弹层后的语义镜像限制，不宣称无刷新连续流程或通用无障碍修复；截图和 trace 留仓库外。完整浏览器集合 186 项交由当前提交 CI，未用旧 HEAD 的 185 项结果冒充。
- [x] 6.3 独立重建 11 文件、759 行解码内容、格式、身份残留、原件摘要及独立预期均核对；新文档、导航、参考、139 个链接／锚点与 diff 检查通过。局部 Git binary 属性保留 GB18030／CRLF 与 PDF 容器格式，11 份暂存字节摘要与 manifest 完全一致。

## 7. 发布

- [x] 7.1 核对 README、导入参考、测试说明、功能地图和词表；记录用户决策反思、适用边界及是否需新增规则。
- [x] 7.2 归档输入、验证证据与既有授权目标已核对：`ssttkkl/finance-tracker` 的 `origin/continue-kotlin-backend-refactor`，更新 main 目标 PR112。已使用 `openspec archive restore-concurrent-cash-file-append --skip-specs --yes --json` 归档本 change；没有 delta 需同步，不合并、不部署。外部执行结果以 PR 当前提交和 checks 为准，不提前认定 CI 通过。

用户决策反思：用户明确选择仅身份脱敏、保留日期和金额并截取一个代表性自然月，适用范围是本批获准的回归夹具，不是今后默认纳入真实财务数据的授权；已在 `docs/import/cash-import-fixtures.md` 写清边界、例外和重新生成要求。用户选择后端完整链路及少量真实浏览器流程，现有产品编码规范第 6 节的行为证据原则已覆盖，不再新增通用门禁。追加合并、每项独立的批量币种补齐及展示时区／月度汇总均恢复既有已确认主规格，没有新增用户偏好或业务语义。无需新增或修改通用规则，文档只记录本次已确认的数据和验收范围。

验证对象为 `4431d953878f3dfd95895061bcedd6ea7f54784c` 加本 change 同组实现、测试与文档工作树，对照 main `ffa91962d7357b1dfbef70a86e8fbeebc5ec12a9`；执行日期 2026-11-05～06。日志与只读独立审查保存在仓库外 `~/Library/Caches/finance-tracker/cash-import-regression-qa`。临时数据库、用户、工作区及原件映射不进入仓库。Cross-platform Impact：后端与真实 Web 回归受影响；Native 和共享 UI／domain 未改，不追加原生编译或设备 QA；现有 CI 继续运行共享及跨端编译。

| 当前命令／证据 | 结果与边界 |
|---|---|
| `:backend:test :backend:codegenTest :business-contracts:jvmTest :business-domain:jvmTest :backend:ktlintCheck :backend:detekt :backend:installDist --no-configuration-cache`，显式 `FT_TEST_POSTGRES_URL` | 863／2／3／10 项，0 失败、0 跳过；构建与静态检查通过，2 分 30 秒；`backend-full.log`、`backend-full-counts.json` |
| `:backend:compileTestKotlin :backend:ktlintTestSourceSetCheck --no-configuration-cache` | 历史夹具适配后通过，4 秒；`historical-fixture-compile.log`；不等于执行历史证据 |
| `npm --prefix compose/qa/web run test:e2e -- compose-cash-import-live.e2e.ts --retries=0` | 1/1 通过，案例 33.5 秒；`browser-live.log`、`browser-results`；预览 5188、专用后端 19262 |
| `append-red.log`／`currency-red.log`／`timezone-red.log`／`obsolete-resolve-red.log` | 直接双库红例分别证明追加冲突、404、422 和错误 retry_original；修复后纳入上述默认完整门禁 |
| `rebuilt-validation.json`、`implementation-independent-review.json`、`append-contract-independent-review.json`、`currency-requirements-independent-review.json`、`monthly-summary-independent-review.json` | 独立隐私／格式／事实与 5a、5b 复核，无遗留阻断；历史证据未运行边界如上 |
| `openspec validate --all --strict --json`、变更严格校验、文档本地链接／锚点、`git diff --check` | 通过；归档前后分别校验现状，主规格未变，无 delta 需同步 |

文档收尾已核对 README、导入参考、接口／命令及配置示例、功能地图总览和导入模块、词表与技能入口；既有术语足够，无新配置、迁移或用户文案。接口参考已有批量币种／时区合同，未重复增写。独立回滚策略是回退本次代码；无持久化结构变更，暂存恢复仍受原 TTL 和永久收据围栏约束。首次发布内存评估、非空库 schema 前置条件和未运行的设备／读屏 QA 延续原发布限制。
