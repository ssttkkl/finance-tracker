## 1. 思考

- [x] 以 `f2515b43` 追踪整批计划、写集解码和展开、回执与本地冻结恢复。A 类，涉及服务端、共享层、CLI 与 Compose。
- [x] 在默认 Node 堆完成合成的大规模安装包基线，分项记录字节、资源和关系成员。

## 2. 计划

- [x] 记录现有资源预算、回执优先及一次交易，明确权限、回滚与范围边界。

## 3. 任务拆分

- [x] 以明确拒写、核对复用、资源热点及阶段反馈切片，按失败回归接线。

## 4. 构建

- [x] 复现正文或展开超限后冻结不退役，修复 HTTP 明确拒写和共享恢复。
- [x] 针对实际超限位置加入精确预检，复用未改变批次的已核对数据。
- [x] 聚焦实际 CPU、分配和序列化热点优化，保留逐关联和成员身份；CLI 输出阶段反馈。
- [x] 比较 Node 上的 Kotlin/JS、wasmJs 与 Kotlin/Native，运行隔离探针。

## 5. 审查

- [x] 由独立上下文复核产品、范围、工程、契约、安全、DX 及最终 diff。

独立复核覆盖 HTTP 拒写证据、收据优先、工作区与修订绑定、加密存储兼容、分页查询及终端调用。处置包括退役前再次校验 SQLite 当前行无收据、批次列表只读目录、复核修订变化时的缓存更新，以及 CLI、Wasm Worker、原生后继尝试共用容量检查。最终复核确认有界分页保留授权、软删除过滤、排序及快照一致性，无新增阻断。契约专项检查确认无新增状态机、数据表或写入入口。Compose 复用原文件调整页面、错误区域和容量文案，Web、Android、iOS 共用状态映射，无布局或响应式变更。按既有 Material 3 状态及 Hallmark 范围执行独立设计核对。

## 6. 验证

- [x] 同规模真实安装包的本地 HTTPS 基线和改后对比，核实际收据及读回。
- [x] 验证超限、换区、断线重启、丢回包及重复确认，实际执行 SQLite 与 PostgreSQL。
- [x] 验证受影响 Compose 浏览器流程、Android 和 iOS 编译单测，执行 OpenSpec 严格校验和 diff 检查。

## 7. 收尾

- [ ] 核对用户文档与功能地图、回写实测证据、完成决策反思并归档。

### 当前验证资料

环境为 macOS 26.6.2 arm64、Node.js 26.8.1、JDK 21 和 PostgreSQL 18.6。基线 `f2515b43`。账单、密钥与各 SQLite／PostgreSQL 测试库均独立；输入由程序生成，未读取用户账本或原始账单。

2026-11-27 的最终实现验证以 `f2515b43` 为比较基线，`git diff f2515b43 -- compose ':!compose/README.md' | git hash-object --stdin` 为 `e3056c941f7e111cb0f483c255d1dec6b485f6a2`。

| 命令与范围 | 结果 |
|---|---|
| `./gradlew :backend:test --tests '*CashImportTransportHttpTest' --tests '*ReceiptReadMigrationTest' --tests '*CashImportWriteSetHttpTest' --tests '*OpenApiContractTest' :client-operations:jvmTest :cli:stageNpm`，显式配置 `FT_TEST_POSTGRES_URL` | 后端 83 项、共享操作 6 项均通过，无跳过。直接正文、解压及紧凑表示展开超限均无账本写入。 |
| `npx --yes --package=node@26.2.0 -c 'node --test compose/cli/test/entry.test.cjs compose/cli/test/offline.test.cjs compose/cli/test/server.test.cjs'`，显式配置 PostgreSQL | 最低 Node 版本的真实 npm 包 4 项通过。覆盖登录、退出、换区守卫、断网重启、原提交回包丢失后接续、重复确认、具有拒写证据和不透明的 413。 |
| `./gradlew :shared:testAndroidHostTest --tests '*ImportTaskViewModelTest' --tests '*LocalCashImportRecoveryTest'`；`./gradlew :shared:iosSimulatorArm64Test --tests '*ImportTaskViewModelTest'` | Android 147 项、iOS 102 项通过，无跳过。未运行原生设备交互。Android 扩展全量试跑中 17 个历史迁移消费者测试缺少 `FT_KOTLIN_CLIENT_CONTRACT_DIR`、`FT_KOTLIN_PORTFOLIO_HOST_FILE` 或相应现行响应夹具而失败。仅当准备这些生产契约来源后可以补跑。 |
| `npm --prefix compose/qa/web run test:node` | 44 项通过。 |
| `npm --prefix compose/qa/web run test:e2e -- compose-local-import.e2e.ts --grep '确认明确超限\|冲突后重新核对\|已保存后恢复结果整理'` | Chrome 158.0.8127.37，1440×1100，预览 `http://127.0.0.1:56438`，实际点击文件选择、账户选择、核对和确认，3 项通过。覆盖明确 413 后保留原文件、冲突后重新核对及已保存后重启补读。截图 `local-csv-size-refusal.png`，重放与断网失败均为夹具预设，控制台检查通过。 |
| `openspec validate --all --strict`、`git diff --check` | 35 项规格与变更通过，diff 检查通过。 |

19 文件包含 5 PDF、2 XLS、6 CSV、6 XLSX，其中 3 PDF 由独立命令使用标准输入提供密码。输入共 14,424 行，账户含 CNY 与 USD。将原银行和支付宝的同一序号映射为合成商户、同日金额，预期 2,995 对，另有 1,700 条跨文件重复。

| 安装包操作 | 基线默认堆耗时／峰值 RSS | 优化后默认堆耗时／峰值 RSS |
|---|---:|---:|
| 登录并重建、选择测试工作区 | 0.9 秒／约 0.10 GB | 0.8 秒／约 0.10 GB |
| 准备 16 文件和 3 次加密 PDF 追加 | 54.5 秒／0.34 GB | 13.8 秒／0.45 GB |
| 建立导入账户 | 0.4 秒／0.12 GB | 0.2 秒／0.12 GB |
| 整批核对 | 445.2 秒／4.18 GB，堆耗尽 | 130.2 秒／4.20 GB，成功 |
| 确认、读取分页并归档 | 未到达 | 92.1 秒／3.54 GB |
| 新进程读原结果 | 未到达 | 6.5 秒／1.41 GB |
| 收支列表在线／断网重启 | 未到达 | 6.9／6.0 秒，1.36／0.97 GB |

两次均用默认 Node 堆，进程与跨端工具共用同一台开发机。基线在核对后没有提交；成功核对的峰值 RSS 接近基线的失败峰值。一次 Ktor POST 的 JSON 正文 65,403,765 字节，服务器耗时 64.5 秒。PostgreSQL 只有一张 `cash_write_set` 收据，状态 `completed`，`items_complete=true`，版本 2。12,724 条正式流水中 CNY 12,719、USD 5，1,700 条跨文件重复，2,995 条确认、0 条待定关联。按库表关联实际来源编号、金额、币种、两个真实组成项归属及共同条目，2,995 对全部吻合。在线与断网新进程读回同一版本。预览待确认条目数与收据待定关联数保留各自口径。

SQLite 用同一个 19 文件夹具和真实安装包完成了同一请求，核对 211.2 秒、峰值 RSS 4.72 GB，确认与结果整理 36.0 秒、峰值 RSS 3.69 GB。仅发出一次 POST，收到一张收据，原结果逐字节一致。按相同条件逐项验证了 2,995 条关联和 CNY、USD 流水数，并在断网重启后读回版本 2。这两组后端耗时包含不同的并发构建负载，不能用于推导后端性能排名。

另一个 14 文件同输入的较早对照用于资源诊断。基线默认堆 193.7 秒后 OOM；8 GiB 老生代基线核对 258.1 秒，优化版默认堆 78.3 秒。银行日期在该对照中产生了草稿，仅作 CPU 和内存对照。修正日期的 14 文件证据包含 967 对，旧分页联接在 PostgreSQL 上阻塞首次读回。用有界索引读取后，原收据的明细 19.2 秒续读完成，期间没有 POST。19 文件的服务端关系闭包仍占用确认时间，本次保留该业务图算法。

用同金额、同时间和同商户的三条合成流水验证拆批。整批保留建行与支付宝的待定关联，实际候选包含支付宝和微信。按建行、支付宝、微信分批时，支付宝先与建行确认，微信未建立第二条关联。候选集合及已确认成员取决于该次核对看到的输入；该夹具不推导用户历史批次的具体缺失成员。

运行时探针使用隔离 Gradle 9.5.1、Kotlin 2.4.20 工程，加入 `:client-operations`、`:business-domain`、`:business-contracts` 的源码和原依赖版本。wasmJs 的 Node 可执行模块经 npm 安装后解析合成 CSV，用实际 Node 宿主写加密 SQLite，新进程读回成功。Native 在 macOS arm64 的 debug 可执行文件通过同一个 CSV 与标准输入探针；release 链接默认 JVM 堆耗尽，改用 3 GiB 后生成可执行文件，运行该探针在 CSV 收集器处报 Kotlin receiver 类型错误。未用这些最小探针比较整批导入速度。

用户选择保持整批关联、一次确认和原子性。本次沿用现有事务与资源上限，关联规则仅做等价计算优化。原始历史账单未参与复验，仍超过 64 MiB 的完整有效表示会在写入前明确拒绝。扩大上限或改变提交语义需单独作产品决定。该约定已由导入规格覆盖，无需沉淀新的通用规则。
